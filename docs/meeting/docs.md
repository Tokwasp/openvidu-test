# 회의(Meeting) 설계 — OpenVidu 3(LiveKit) 기반, 현재 구현 기준

> 이 문서는 **현재 구현된 코드**를 설명하는 전체 명세다. 처음이면 [README.md](README.md) 요약부터 본다.
> 주제별 문서 — [회의방 관리](01-room-lifecycle.md) · [접근 토큰](02-access-token.md) · [녹음](03-recording.md) · [로그·진단](04-logging-and-troubleshooting.md)
>
> **초기 설계에서 크게 바뀐 3가지**: ① 방 상태를 MySQL이 아니라 **Redis**로, ② 개인 녹음을
> Participant가 아니라 **Track Egress**로, ③ 녹음 결과를 DB 저장·조회가 아니라 **SQS 발행**으로.

## 1. 개요 / 목표

- 프로젝트 단위로 **음성 회의방**을 만들고 참여한다. 한 프로젝트에는 **진행 중인 회의방이 최대 하나**다.
- 방 이름을 고르는 게 아니라, 프로젝트에서 "회의 참여"를 누르면 **없으면 만들고 있으면 붙는다.**
- 회의가 끝나면 **참가자별 개인 음성**과 **전체 믹스 음성**이 모두 S3에 남는다.
- 녹음은 **우리 AWS S3**에 Egress가 직접 업로드하고, 끝나면 **SQS**로 후처리 메시지를 발행한다.
- 미디어 서버는 **OpenVidu 3.x CE = LiveKit 스택**을 쓴다. 서버 SDK는 `io.livekit:livekit-server`다.

---

## 2. 용어

| 우리 용어 | LiveKit 용어 | 설명 |
| --- | --- | --- |
| 프로젝트 | — | LiveKit엔 없다. 우리 쪽 개념 |
| 회의(Meeting) | Room | `roomName`(UUID)이 Room 이름 |
| 참가자 | Participant | `identity` = `memberId`, `name` = 회원 이름 |
| 입장 티켓 | AccessToken(JWT) | 서버가 서명해 내려준다 |
| 개인 녹음 | **Track Egress** | 오디오 트랙 하나를 무변환 저장 |
| 전체 믹스 | **RoomComposite Egress** (`audioOnly`) | 방 전체를 하나로 합성 |
| 후처리 메시지 | SQS message(`RecordingEvent`) | egress_ended 시 발행 |

---

## 3. 왜 OpenVidu 3인가 (요약)

"참가자별 개인 음성 + 전체 믹스"를 **둘 다 동시에** 뽑아야 하는데, OpenVidu 2는 세션당 녹화 하나만 가능해
COMPOSED와 INDIVIDUAL을 동시에 못 돌린다(409). OpenVidu 3의 녹음은 LiveKit **Egress**라
요청 단위로 여러 개를 병렬로 돌릴 수 있다. 그래서 개인 Egress 여러 개 + 전체 믹스 Egress 하나를 동시에 건다.

| 항목 | OpenVidu 2 | **OpenVidu 3 / 우리 구현** |
| --- | --- | --- |
| 방 개념 | Session | Room |
| 서버 SDK(Java) | `openvidu-java-client` | `io.livekit:livekit-server` (Retrofit `Call<T>` → `.execute()`) |
| 입장 토큰 | createConnection → token | `AccessToken` JWT(`RoomJoin`+`RoomName`) |
| 개인 음성 | INDIVIDUAL(zip 안 webm) | **Track Egress** → `.ogg` |
| 전체 믹스 | COMPOSED + hasVideo=false | **RoomComposite** + `audioOnly` → `.ogg` |
| 둘 동시 | 불가(409) | **가능** |
| 저장 | 서버 로컬 | **우리 AWS S3 직접 업로드** |
| 결과 처리 | — | **SQS 발행**(워커/Lambda가 후처리) |

---

## 4. 전체 흐름

```
[프론트]                [백엔드]                   [LiveKit/Egress]      [S3]     [SQS]
   |-- POST join ------->|                            |                 |        |
   |                     |-- @Login 회원 확인          |                 |        |
   |                     |-- Redis: 열린 방 있나?       |                 |        |
   |                     |   [없음] Redis open  ------→ (먼저!)           |        |
   |                     |          createRoom  ------→ Room 생성         |        |
   |                     |<-- webhook: room_started --| (방 인식 →)       |        |
   |                     |-- startMixedEgress -------→ 전체 믹스 시작      |        |
   |                     |-- AccessToken 서명(로컬)     |                 |        |
   |<-- {roomName,token,livekitUrl,created} ----------|                 |        |
   |== livekit-client WebRTC =======================→|                 |        |
   |                     |<-- webhook: track_published(audio)            |        |
   |                     |-- startAudioTrackEgress --→ 개인 녹음 시작      |        |
   |                     |                            |==== 업로드 =====→|        |
   |                     |<-- webhook: egress_ended --|                 |        |
   |                     |-- SQS 발행(PARTICIPANT) ---------------------------→| 개인 큐
   |-- (모두 나감 + 20s)  |<-- webhook: egress_ended --| 전체 믹스 완료 ==→|        |
   |                     |-- SQS 발행(MIXED) --------------------------------→| 단체 큐
   |                     |<-- webhook: room_finished -|                 |        |
   |                     |-- Redis closeByRoom        |                 |        |
```

**미디어도 파일도 백엔드를 거치지 않는다.** 백엔드가 하는 일은 (1) 토큰 발급, (2) 방 수명 관리(Redis),
(3) 웹훅 받아 Egress 시작, (4) egress_ended 시 SQS 발행뿐이다.

---

## 5. 회의방 상태 관리 (Redis)

상태의 주인은 **Redis**다(`RoomRegistry`). MySQL은 쓰지 않는다. 자세한 내용은 [01](01-room-lifecycle.md).

- 키: `meeting:project:{projectId} → roomName`, `meeting:room:{roomName} → projectId`. 둘 다 TTL 2h.
- `findRoom` / `isKnownRoom` / `open` / `closeByRoom` 네 메서드가 전부.
- **여는 순서가 중요**: `open()`을 `createRoom()`보다 먼저 — `room_started` 웹훅이 방을 인식해야
  전체 믹스 Egress가 시작된다([01 §5-2](01-room-lifecycle.md)). 실패 시 `closeByRoom()`으로 롤백.
- 닫는 경로는 `room_finished` 웹훅 하나뿐. "회의 종료" 버튼도 `deleteRoom()`만 하고 Redis는 웹훅이 닫는다.
- 타임아웃: `empty-timeout-sec=120`, `departure-timeout-sec=20`(방 생성 시 명시).

---

## 6. 참여 흐름 — get-or-create

### 6-1. 엔드포인트 하나

`POST /api/projects/{projectId}/meetings/join` 하나가 확인·생성·토큰을 다 처리한다.

### 6-2. 서버 처리 순서 (`MeetingService.join`)

```
1. @Login LoginMember 로 회원 확인 (세션 없고 dev-auth-bypass 도 아니면 401)
2. roomRegistry.findRoom(projectId)
   ├─ 있음 → 그 roomName 재사용 (created=false)
   └─ 없음 → openNewRoom(projectId)
        a. roomName = UUID.randomUUID().toString()
        b. roomRegistry.open(projectId, roomName)   ← 먼저 (room_started 웹훅 대비)
        c. createRoom(roomName, emptyTimeout, departureTimeout)  ← 실패 시 closeByRoom 롤백
3. tokenService.issue(memberId, name, roomName)   (항상 실행)
4. JoinResponse { roomName, token, livekitUrl, created }
```

> **회의 식별자는 roomName(UUID) 하나다.** `meetingId`(DB PK)는 없앴다 — DB가 없다.

### 6-3. 인증 두 경계

- 브라우저 ↔ 백엔드: **Upstash Redis 세션**(`SESSION` 쿠키). 모놀리식과 공유([02 §7](02-access-token.md)).
- 브라우저 ↔ LiveKit: **AccessToken(JWT)**. 백엔드는 서명만 하고 검증하지 않는다.
- 개발 단독 테스트용 **세션 우회**(`meeting.dev-auth-bypass=true`)는 쿼리 `?memberId=&memberName=`을 받는다.
  **운영 금지.**

---

## 7. 녹음 설계

자세한 건 [03-recording.md](03-recording.md). 요점만.

| 결과물 | Egress | 시작 | 출력 |
| --- | --- | --- | --- |
| 개인 음성 | **Track Egress**(오디오 트랙 무변환) | `track_published`(audio) | `meetings/{room}/{memberId}/{time}.ogg` |
| 전체 믹스 | **RoomComposite**(`audioOnly`, layout 빈값) | `room_started` | `meetings/{room}/mixed/{time}.ogg` |

- 개인이 Track Egress인 이유: Participant Egress + OGG는 비디오 코덱 충돌로 **400**. 오디오 트랙 SID만 무변환 저장.
- 개인은 `(room|memberId)` 단위로 **사람당 한 번**만 시작(중복 방지 셋). 실패 시 셋에서 빼 재시도.
- **멈추는 코드 없음.** 개인은 트랙/사람이 끊기면, 전체 믹스는 방이 끝나면 자동 종료.
- **전체 믹스는 방이 닫혀야 끝난다** → 개인보다 늦게 S3/SQS에 뜬다([03 §3](03-recording.md)).
- 경로에 이름 대신 `memberId`를 쓴다(동명이인·개명·인코딩·개인정보).

---

## 8. 녹음 결과 처리 — DB가 아니라 SQS

초기 설계는 `meeting_recording` DB 저장 + presigned URL 조회 API였지만, **현재는 둘 다 없다.**

- Egress가 파일을 **우리 S3에 직접** 업로드한다(요청마다 `S3Upload` 지정).
- `egress_ended` 웹훅이 오면 백엔드는 **SQS로 `RecordingEvent` 한 건을 발행**한다(`RecordingEventPublisher`).
- 워커/Lambda가 큐에서 꺼내 S3 음성을 읽고 **VAD → 발화시간 → RDS**로 이어간다(백엔드 밖).

### 8-1. `RecordingEvent` (SQS 메시지)

```json
{ "roomName": "...", "memberId": 123, "kind": "PARTICIPANT",
  "objectKey": "meetings/.../123/....ogg", "egressId": "EG_...", "endedAt": "..." }
```
`kind`는 `MIXED`(memberId=null) 또는 `PARTICIPANT`. objectKey가 `.../mixed/...`이면 MIXED로 판단.
파일이 없으면(`fileResultsCount==0`) 발행하지 않는다.

### 8-2. 큐 분리

| kind | 큐 | 설정 키 |
| --- | --- | --- |
| PARTICIPANT | 개인 음성 큐 | `aws.sqs.personal-queue-url` |
| MIXED | 단체 믹스 큐 | `aws.sqs.queue-url` |

개인 큐가 비면 개인도 단체 큐로(하위 호환). 둘 다 비면 발행 생략(로컬). 발행 실패는 로그만(웹훅은 200 유지).

---

## 9. 저장 구조 — DB 없음

**관계형 DB 스키마가 없다.** 회의/참가자/녹음 메타데이터를 위한 테이블을 두지 않는다.

- **방 상태** = Redis 키 2개([01 §1](01-room-lifecycle.md)).
- **녹음 메타데이터** = "파일 경로가 곧 정보". `meetings/{roomName}/{memberId 또는 mixed}/{time}.ogg`.
  더 필요한 정보(발화시간 등)는 SQS 뒤의 워커가 만들어 RDS에 넣는다(이 백엔드 범위 밖).
- 백엔드에 붙는 유일한 상태 저장소는 **세션용 Upstash Redis**와 **방 상태용 Redis**뿐이다.

---

## 10. API 명세

응답은 `ApiResponse.ok(data)` → `{ status, message, data }`.

### `POST /api/projects/{projectId}/meetings/join`
회의방이 없으면 만들고, 있으면 그 방의 입장 토큰을 발급한다. 바디 없음(세션 + 경로변수).

**200**
```json
{ "status": 200, "message": "성공",
  "data": { "roomName": "9f1c...", "token": "eyJhbGci...", "livekitUrl": "ws://...", "created": true } }
```

| 필드 | 설명 |
| --- | --- |
| `roomName` | LiveKit Room 이름(UUID). 프론트가 연결에 사용 |
| `token` | AccessToken(JWT). 10분 만료, 이 방 전용 |
| `livekitUrl` | 클라이언트가 붙을 주소(`livekit.ws-url`) |
| `created` | `true`면 이번 요청이 방을 새로 열었다 |

미로그인 401. (프로젝트 멤버십 검증 등은 모놀리식/후속 범위.)

### `DELETE /api/meetings/{roomName}`
회의 강제 종료. `deleteRoom(roomName)`만 호출하고 200. Redis는 뒤이어 오는 `room_finished` 웹훅이 닫는다.
`data: null`.

### `POST /api/livekit/webhook`
LiveKit 서버가 호출한다(사용자 요청 아님).
- `Content-Type`은 `application/webhook+json` — 컨트롤러는 `consumes=ALL_VALUE`로 받는다(제한하면 415).
- `Authorization` 헤더의 JWT를 `WebhookReceiver`로 검증.
- 처리 성공/실패와 무관하게 **항상 200** 반환(실패는 로그만) — LiveKit 재시도 폭주 방지.

---

## 11. 웹훅 처리 (`WebhookService`)

### 11-1. 이벤트별 처리

| 이벤트 | 처리 |
| --- | --- |
| `room_started` | 우리 방이면 **전체 믹스 Egress 시작**(`startMixedEgress`) |
| `track_published` | 오디오 & 숫자 identity & 첫 발행이면 **개인 Track Egress 시작**(`startAudioTrackEgress`) |
| `room_finished` | **Redis 닫기**(`closeByRoom`) + 개인 Egress 시작 표시 정리 |
| `egress_ended` | 파일 있으면 **SQS 발행**(`RecordingEventPublisher`) |
| 그 외(`participant_joined/left`, `track_unpublished`, `egress_started/updated` …) | **처리 안 함**(DEBUG 로그) |

모든 처리의 문지기는 `isOurRoom(roomName)`(= `roomRegistry.isKnownRoom`)이다. 우리 방이 아니면 무시.

### 11-2. 서명 검증
`WebhookReceiver(apiKey, apiSecret).receive(body, authHeader)`로 검증. 실패하면 예외 → 로그만 남기고 200.
키는 `infra/livekit/livekit.yaml`의 `webhook.api_key`와 백엔드 `livekit.api-*`가 일치해야 한다.

### 11-3. 멱등성
- `room_finished` / `closeByRoom` — 이미 없으면 아무 일도 안 함.
- 개인 Egress — `(room|memberId)` 중복 방지 셋으로 사람당 한 번.
- `egress_ended` — 파일 있는 것만 발행. 워커 쪽 멱등은 `egressId`로.

---

## 12. 설정

### 12-1. `application.yml` (요지)
```yaml
spring:
  data.redis: { host: ${UPSTASH_REDIS_HOST}, port: ..., password: ..., ssl.enabled: true }   # 세션 공유
  session: { store-type: redis, timeout: 30m }
meeting:
  dev-auth-bypass: ${MEETING_DEV_AUTH_BYPASS:false}        # 운영 false
livekit:
  ws-url: ${LIVEKIT_WS_URL:ws://localhost:7880}            # 브라우저가 붙는 주소(응답에 내려감)
  host:   ${LIVEKIT_HOST:http://livekit:7880}              # 백엔드→LiveKit REST(도커 내부)
  api-key / api-secret
  token-ttl-ms: 600000                                     # 10분(ms)
  empty-timeout-sec: 120
  departure-timeout-sec: 20
aws:
  s3:  { bucket, region, access-key, secret-key }          # Egress가 직접 업로드
  sqs: { queue-url, personal-queue-url }                   # 비우면 발행 생략
logging.level.com.ssafy.meeting: DEBUG
```

> `ws-url`(브라우저용, `ws`/`wss`)과 `host`(백엔드 REST용, 도커 내부 `http://livekit:7880`)를 나눠 둔다.
> S3와 SQS는 **같은 IAM 사용자 키**를 재사용한다(`AwsSqsConfig`가 `S3Properties`의 키/리전을 씀).

### 12-2. 인프라(`docker-compose.yml` + `infra/livekit/`)
- 서비스: `redis`, `livekit`, `egress`(`livekit/egress:latest`, `cap_add: SYS_ADMIN`), `backend`, `frontend`, `caddy`(선택).
- `livekit.yaml`: `auto_create: false`, `empty_timeout: 120`, `departure_timeout: 20`,
  `webhook.urls: http://backend:8080/api/livekit/webhook`.
- `egress.yaml`: 같은 `redis`, 같은 api 키, `ws_url: ws://livekit:7880`, `insecure: true`, `log_level: info`.
- `.env.example`을 복사해 `.env`로. 로컬은 `LIVEKIT_WS_URL=ws://localhost:7880` 그대로 동작.

---

## 13. 예외 / 오류 처리
- 컨트롤러/서비스 실패는 예외로 던진다(`IllegalStateException` 등). 참여 실패 시 방 등록은 롤백된다([01 §5-2](01-room-lifecycle.md)).
- **웹훅 경로는 예외를 던지지 않는다** — 로그만 남기고 200(§10, §11-2). LiveKit 재시도 폭주를 막기 위해서다.

---

## 14. 보안 / 주의
| 항목 | 내용 |
| --- | --- |
| API Secret | 클라이언트에 내려가면 안 된다. 토큰 서명은 서버에서만 |
| 토큰 범위 | `RoomName` grant로 그 방 하나만, 만료 10분 |
| identity | `memberId` 사용. 이름·이메일은 S3 경로에 남으므로 금지 |
| dev 우회 | `meeting.dev-auth-bypass`는 운영에서 반드시 false(사칭 위험) |
| 웹훅 | 서명 검증 필수. 전역 인증 필터 도입 시 이 경로 예외 등록 |
| S3/SQS 키 | 같은 IAM 사용자 키 재사용. `s3:PutObject/GetObject/ListBucket` + `sqs:SendMessage` |
| 녹음 고지 | 항상 녹음됨을 입장 전 UI로 고지 |

---

## 15. 후속 (이 백엔드 범위 밖)
- **SQS 뒤 후처리 워커/Lambda** — VAD → 발화시간 → RDS. STT/회의록.
- **녹음 조회 API** — 필요하면 RDS(워커가 채운) 기준으로 별도 서비스에서.
- **웹훅 유실 정리** — TTL 2h가 1차 안전망. 필요 시 죽은 방 정리 스케줄러.
- **동시 첫 입장 방어** — 지금은 안 막음. 문제되면 Redis `SET NX`.
- **화면공유/비디오** — 지금은 오디오 전용.

---

## 참고 자료
- [LiveKit — Egress 개요](https://docs.livekit.io/home/egress/overview/) · [출력/ filepath 템플릿](https://docs.livekit.io/home/egress/outputs/)
- [LiveKit — Webhooks](https://docs.livekit.io/home/server/webhooks/)
- [OpenVidu 3 문서](https://openvidu.io/latest/docs/)
- 코드: `backend/src/main/java/com/ssafy/meeting/` (service · api · config)
