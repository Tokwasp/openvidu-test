# 로그 보는 법 & 문제 진단

> "녹음이 안 돼요"를 **로그 한두 줄로** 어디가 막혔는지 짚는 문서다.
> 흐름 자체는 [03-recording.md](03-recording.md)를, 방 상태는 [01](01-room-lifecycle.md)을 본다.

---

## 0. 로그 어떻게 보나 (docker compose)

이 프로젝트는 `docker-compose.yml`로 `backend` · `livekit` · `egress` · `redis` 컨테이너를 띄운다.

```bash
# 실시간(회의 진행하면서 같이 본다)
docker compose logs -f backend

# 방금 회의 것만 (최근 10분)
docker compose logs --since 10m backend

# 녹음 관련만 필터 — 가장 자주 쓴다
docker compose logs backend | grep -E "mixed|Egress|room_started|알 수 없는|egress_ended|SQS"

# Egress 워커(전체 믹스가 실제 녹화되는 곳)
docker compose logs egress | tail -100
```

- `docker compose`가 안 되면 예전 문법 `docker-compose logs backend`.
- 컨테이너 이름 확인: `docker compose ps`.
- 백엔드 로그 레벨은 `application.yml`에서 `logging.level.com.ssafy.meeting: DEBUG`로 이미 상세하다.

---

## 1. 정상일 때 이렇게 찍힌다 (기준선)

2명이 참여 → 잠깐 회의 → "회의 종료"까지의 **정상 로그**다. 이 모양과 다르면 그 지점이 문제다.

```
# ① 첫 사람 참여 → 방 생성 → room_started 웹훅이 방을 인식 → 전체 믹스 시작
[DEV] 세션 우회로 참여 — memberId=123 ...
WebhookService : [Webhook] event=room_started
MeetingService : [Join] 새 회의 생성 project=5 room=a332d8a4-...
EgressService  : [Egress] mixed 시작 room=a332d8a4-... egressId=EG_...      ← ★ 전체 믹스 시작됨

# ② 마이크 발행 → 개인 Track Egress 시작 (사람마다)
WebhookService : [Webhook] event=track_published
EgressService  : [Egress] track(오디오) 시작 room=a332d8a4-... member=123 track=TR_... egressId=EG_...

# ③ 사람이 나감 → 개인 Egress 종료 → 업로드 → SQS(개인 큐) 발행
WebhookService : [Webhook] event=egress_ended
RecordingEventPublisher : [SQS] 발행 room=a332d8a4-... member=123 kind=PARTICIPANT key=meetings/.../123/....ogg

# ④ 방이 닫힘 → 전체 믹스 종료 → 업로드 → SQS(단체 큐) 발행
MeetingService : [End] deleteRoom 요청 room=a332d8a4-...
WebhookService : [Webhook] event=room_finished
WebhookService : [Webhook] event=egress_ended
RecordingEventPublisher : [SQS] 발행 room=a332d8a4-... member=null kind=MIXED key=meetings/.../mixed/....ogg   ← ★ 전체 믹스가 SQS로
```

### 봐야 하는 핵심 4줄

| 로그 | 의미 | 없으면 |
| --- | --- | --- |
| `[Egress] mixed 시작 ... egressId=` | 전체 믹스 Egress가 **시작**됨 | 진단 A |
| `[Egress] track(오디오) 시작 ... member=` | 개인 Egress가 **시작**됨 | 진단 C |
| `[SQS] 발행 ... kind=MIXED` | 전체 믹스가 끝나 SQS로 나감 | 진단 B / D |
| `[SQS] 발행 ... kind=PARTICIPANT` | 개인이 끝나 SQS로 나감 | 진단 C / D |

> **참고:** `egress_started`, `egress_updated`, `participant_joined`, `participant_left`, `track_unpublished`는
> 우리가 처리하지 않는 이벤트라 `[Webhook] 처리 안 함: ...` DEBUG 로그만 남는다. 정상이다.

---

## 2. 증상별 진단

### 진단 A — mixed가 S3·SQS 어디에도 없다 / `[Egress] mixed 시작`이 안 보인다

**원인: `room_started` 경합** (과거 버그, 현재는 수정됨). Redis 등록이 웹훅보다 늦으면 mixed가 시작조차 안 된다.

찾을 로그:
```
WebhookService : [Webhook] event=room_started
WebhookService : [Webhook] 알 수 없는 room=a332d8a4-...     ← ★ 이게 뜨면 경합
MeetingService : [Join] 새 회의 생성 room=a332d8a4-...       ← Redis 등록이 그 "뒤"에 찍힘
```

- `알 수 없는 room` 뒤에 `[Egress] mixed 시작`이 **없다** → mixed가 안 걸린 것.
- **해결:** `MeetingService.openNewRoom`에서 `roomRegistry.open()`을 `createRoom()` **앞에** 둔다.
  현재 코드는 이미 그렇게 돼 있다([01 §5-2](01-room-lifecycle.md)). 이 로그가 또 보이면 순서가 되돌아간 것.

### 진단 B — `[Egress] mixed 시작`은 찍히는데 S3에 mixed 파일이 없다 / `kind=MIXED` SQS가 안 나간다

두 가지를 순서대로 확인한다.

**B-1. 아직 방이 안 닫혔다 (가장 흔함, 버그 아님)**
mixed는 방이 닫혀야 끝난다. 마지막 사람이 나가고 `departure_timeout`(20초) 전이거나 회의가 아직 열려 있으면
`egress_ended(mixed)`가 안 온 게 정상이다.
→ **회의를 완전히 끝내고(모두 퇴장 또는 "회의 종료" 버튼) 30초~1분 뒤** 다시 본다.
"회의 종료"는 `[End] deleteRoom 요청` 로그와 곧이은 `room_finished`로 확인.

**B-2. RoomComposite(Chrome) 녹화 실패**
전체 믹스는 Egress 컨테이너에서 **Chrome을 띄워** 합성한다(개인 Track Egress는 Chrome 불필요).
Chrome/합성이 실패하면 Egress가 에러로 끝나 파일이 안 생기고, `egress_ended`에 파일이 없어 SQS도 안 나간다.

```bash
# mixed egressId(위 [Egress] mixed 시작 로그의 EG_...)로 egress 컨테이너 추적
docker compose logs egress | grep "EG_<그 mixed egressId>"
docker compose logs egress | grep -iE "error|failed|chrome|EGRESS_FAILED"
```
`chrome`, `failed`, `EGRESS_FAILED`가 보이면 B-2다.
확인 포인트: `docker-compose.yml`의 egress 서비스에 `cap_add: [SYS_ADMIN]`(Chrome 샌드박스), 컨테이너 CPU/디스크 여유.

### 진단 C — 개인 녹음이 안 된다 (`[Egress] track(오디오) 시작`이 없다)

원인을 위에서부터 배제한다.

1. **웹훅 자체가 안 온다** — `[Webhook] event=track_published`가 없다
   → LiveKit → 백엔드 웹훅 경로 문제. `infra/livekit/livekit.yaml`의 `webhook.urls`가
   `http://backend:8080/api/livekit/webhook`인지, 서명 키(`api_key`)가 백엔드와 같은지 확인.
   백엔드에 `[Webhook] 처리 오류(무시): ...`(서명 실패)만 반복되면 키 불일치.
2. **우리 방이 아니라고 걸러짐** — `[Webhook] 알 수 없는 room=` → 진단 A 참고(Redis 등록/경합).
3. **오디오 트랙이 아니거나 identity가 숫자가 아님** — 카메라만 켜고 마이크를 안 켰거나,
   토큰 identity가 memberId(숫자)가 아니면 개인 Egress를 안 건다([03 §2](03-recording.md)). DEBUG 로그로 사유가 남는다.
4. **Egress 시작이 400 등으로 실패** — `[Egress] participant 시작 실패(재시도 가능) member=...: <메시지>`
   → 다음 track_published 때 재시도된다. 메시지에 `no supported codec ... OGG` 류가 있으면
   Participant Egress 방식으로 되돌아간 것([03 §2](03-recording.md)의 400 사연).

### 진단 D — Egress는 끝나는데(`egress_ended`) `[SQS] 발행`이 안 보인다

1. **큐 URL이 비어 있음** — `[SQS] 대상 큐 미설정 → 발행 생략 ...`(DEBUG)
   → `application.yml`의 `aws.sqs.queue-url` / `personal-queue-url`(= 환경변수 `AWS_SQS_QUEUE_URL` /
   `AWS_SQS_PERSONAL_QUEUE_URL`)이 채워졌는지. 개인 큐가 비면 개인도 단체 큐로 발행된다(하위 호환).
2. **발행은 시도했는데 실패** — `[SQS] 발행 실패(무시): <메시지>`
   → IAM 사용자에 `sqs:SendMessage` 권한이 있는지, 큐 URL·리전이 맞는지. (S3와 같은 키를 재사용한다.)
3. **파일이 없어서 생략** — `[Webhook] egress_ended 파일 없음 egressId=...`
   → Egress가 실패로 끝나 파일이 없다. mixed면 진단 B-2, 개인이면 진단 C-4.

---

## 3. 어디를 보면 되나 (요약표)

| 확인 대상 | 어디서 | 무엇을 |
| --- | --- | --- |
| mixed/개인 Egress **시작** 여부 | `docker compose logs backend` | `[Egress] mixed 시작` / `[Egress] track(오디오) 시작` |
| 웹훅 수신 여부 | backend 로그 | `[Webhook] event=...`, `알 수 없는 room`, `처리 오류(무시)` |
| SQS 발행 여부 | backend 로그 | `[SQS] 발행 ...` / `대상 큐 미설정` / `발행 실패` |
| 전체 믹스 **녹화 실패** | `docker compose logs egress` | `error` / `failed` / `chrome` / egressId 추적 |
| 파일이 실제로 올라갔나 | AWS S3 콘솔/CLI | `meetings/{room}/mixed/*.ogg`, `meetings/{room}/{memberId}/*.ogg` |
| 메시지가 큐에 들어갔나 | AWS SQS 콘솔 | 단체 큐 / 개인 큐의 메시지 수, 본문(objectKey) |
| 방 상태(Redis) | `redis` | `meeting:project:{id}`, `meeting:room:{roomName}` 키 존재/ TTL |

S3·SQS 빠른 확인(CLI, 같은 IAM 키):
```bash
aws s3 ls s3://<버킷>/meetings/<roomName>/ --recursive
aws sqs get-queue-attributes --queue-url <큐URL> --attribute-names ApproximateNumberOfMessages
```

Redis 방 키 확인(컨테이너 안에서):
```bash
docker compose exec redis redis-cli KEYS "meeting:*"
docker compose exec redis redis-cli TTL "meeting:room:<roomName>"
```

---

## 4. 로그를 더 찍고 싶을 때

- 백엔드: `application.yml`의 `logging.level.com.ssafy.meeting`은 이미 `DEBUG`. 더 넓히려면
  `logging.level.io.livekit: DEBUG`(SDK 호출), `logging.level.org.springframework.web: DEBUG`(요청 매핑).
- Egress 워커: `infra/livekit/egress.yaml`의 `log_level: info`를 `debug`로 올리면 Chrome/합성 단계가 자세히 남는다.
- LiveKit 서버: `infra/livekit/livekit.yaml`에 `log_level: debug` 추가.

변경 후 해당 컨테이너만 재기동: `docker compose up -d --no-deps backend`(또는 `egress`, `livekit`).

---

## 5. 한 줄 진단 순서

```
1) backend 로그에 [Egress] mixed 시작 있나?
     없다  → 진단 A (room_started 경합 / 알 수 없는 room)
     있다  → 2)
2) 회의를 완전히 끝냈나? (deleteRoom/room_finished 뒤 30초)
     아니오 → 기다린다 (mixed는 방 닫혀야 나옴 · 진단 B-1)
     예     → 3)
3) [SQS] 발행 kind=MIXED 있나?
     없고 egress_ended 파일 없음 → 진단 B-2 (egress 컨테이너 Chrome 로그)
     없고 대상 큐 미설정/발행 실패 → 진단 D
     있다 → 정상 (S3/SQS 콘솔에서 실물 확인)
```

- 관련: [녹음 흐름](03-recording.md) · [방 상태](01-room-lifecycle.md) · [전체 설계](docs.md)
