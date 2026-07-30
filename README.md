# OpenVidu3(LiveKit) 회의 데모 — EC2 / 로컬 한 방 실행

설계 문서(01~06)를 실제로 **돌려보며 흐름을 눈으로 확인**하기 위한 데모입니다.
서버를 띄우고 → 화면공유/음소거/마이크 토글 → 회의를 종료하면,
**전체 믹스 음성**과 **개인 음성**이 MinIO에 생겼다가 VAD 처리 후 삭제되고,
DB에는 **발언 시간 숫자만** 남는 전 과정을 볼 수 있습니다.

> AI(LLM)로 넘기는 부분은 요청대로 **실제 호출 없이 파이프라인만** 구현했습니다.
> (프롬프트를 만들어 로그로 출력하고, 예시 조언 문구를 반환합니다.)

---

## 구성 (docker compose 한 방)

```
┌────────────┐   /api    ┌────────────┐  Room/Egress REST  ┌──────────────┐
│ frontend   │──────────▶│ backend    │───────────────────▶│ LiveKit      │
│ (React     │◀──────────│ (Spring    │◀── webhook ────────│ (OpenVidu3   │
│  nginx:8081│  token    │  Boot:8080)│                    │  코어:7880)  │
└─────┬──────┘           └─────┬──────┘                    └──────┬───────┘
      │ WebRTC(오디오/화면)                                        │ 구독
      └───────────────────────────────────────────────▶(7880/udp)│
                                                            ┌──────▼───────┐
                        backend가 읽고 삭제 ◀───────────────│ Egress       │
                                                            │  └─ .ogg ────▶ MinIO(9000/9001)
                                                            └──────────────┘
   MySQL(3306) · Redis(LiveKit·Egress 메시지 버스)
```

| 서비스 | 포트 | 용도 |
| --- | --- | --- |
| frontend | http://localhost:8081 | 회의 UI |
| backend | http://localhost:8080 | 참여/종료 API, 웹훅 수신, VAD 워커 |
| LiveKit | 7880 / 7881 / 50000-50060(udp) | OpenVidu3 미디어 서버 |
| Egress | (내부) | 오디오 녹음 → MinIO |
| MinIO | http://localhost:9001 (콘솔) | 녹음 스크래치 (minioadmin/minioadmin) |
| MySQL | 3306 | meeting / stat / recording |

---

## 실행

```bash
cp .env.example .env       # 로컬은 그대로 둬도 됨
docker compose up --build
```

- 브라우저에서 **http://localhost:8081** 접속 → `회의 참여`.
- 마이크/카메라 권한을 허용하세요. (WebRTC는 localhost에서는 보안 컨텍스트로 취급되어 동작합니다.)
- 여러 명을 흉내내려면 **다른 브라우저/시크릿 탭**에서 `memberId`·이름만 바꿔 같은 `projectId(=5)`로 참여하세요.

### EC2에 올릴 때

> 브라우저는 `http://IP` 에서는 마이크·화면공유를 막는다(보안 컨텍스트 필요). 접속은 아래 두 방식 중 하나.

**A. 혼자 테스트 (SSH 터널, 추가 세팅 없음)**
- `.env`의 `LIVEKIT_WS_URL`을 `ws://<EC2 공인 IP>:7880`으로.
- `infra/livekit/livekit.yaml`의 `use_external_ip: true`.
- 보안그룹에서 `7880(tcp), 7881(tcp), 50000-50060(udp)` 개방.
- 노트북에서 `ssh -L 8081:localhost:8081 ec2-user@<IP>` 후 `http://localhost:8081` 접속.

**B. 도메인으로 여러 명 접속 (HTTPS, caddy)**
1. DNS A레코드로 도메인 → EC2 공인 IP 연결.
2. 보안그룹에 **80, 443**(그리고 미디어용 `50000-50060/udp`, 폴백 `7881/tcp`) 개방.
3. `.env`에 `DOMAIN`, `ACME_EMAIL` 채우고 `LIVEKIT_WS_URL=wss://<도메인>/rtc` 로 변경.
4. `docker compose --profile proxy up -d --build` — caddy가 Let's Encrypt 인증서를 자동 발급하고 `https://<도메인>` 하나로 UI(+/api)와 `wss://.../rtc`(LiveKit 신호)를 서빙한다. 미디어(UDP)는 caddy를 거치지 않고 EC2로 직접 흐른다.

> 작은 인스턴스(메모리 빠듯)면 스왑 2GB 정도 잡아두면 mysql/egress가 안정적이다.

---

## 눈으로 확인하는 시나리오

1. **참여** — `회의 참여` 클릭.
   백엔드가 `findOpenMeeting` → 없으면 `createRoom` + INSERT, 토큰 서명해 내려줌(01·02).
   화면 로그에 `참여 응답 … created=true` 가 뜹니다.
2. **첫 입장 시 녹음 자동 시작** — `room_started` 웹훅 → 전체 믹스 Egress,
   `participant_joined` 웹훅 → 사람별 Egress 시작(03·04).
   백엔드 로그에서 `[Egress] mixed 시작…`, `[Egress] participant 시작…` 확인.
3. **화면공유 / 음소거 / 마이크 on·off** — 버튼으로 토글.
   **마이크를 껐다 켜도 개인 파일은 나뉘지 않습니다**(Participant Egress, 03 §3).
4. **회의 종료** — `회의 종료`(deleteRoom) 또는 마지막 한 명이 `나가기`.
   → `room_finished` 웹훅으로 `active_flag=false`(01 §5).
5. **MinIO 확인** — 종료 직후 http://localhost:9001 (minioadmin/minioadmin) →
   `recordings` 버킷의 `meetings/{roomName}/` 아래에
   `mixed/…​.ogg`(전체 믹스)와 `7/…​.ogg`(개인, memberId 폴더)가 **잠깐 생겼다가**,
   워커가 VAD 처리 후 **삭제**되는 것을 볼 수 있습니다(06 §2).
6. **결과** — 결과 화면의 발언시간 그래프(`GET /speech-stats`)와
   AI 코칭(LLM 호출은 생략)을 확인. 파일은 지워졌고 **숫자만 DB에 남습니다**.

> 5번에서 파일이 순식간에 사라져 못 볼 수 있습니다. 삭제 과정을 천천히 보고 싶다면
> `backend/.../RecordingWorker.java`의 `minioStorage.delete(...)` 를 잠시 주석 처리하세요.

---

## 코드에서 흐름 따라가기

| 단계 | 파일 |
| --- | --- |
| 참여/종료, 방 재사용·생성 | `backend/.../service/MeetingService.java` |
| 토큰 서명(사람×방, 저장 안 함) | `backend/.../service/TokenService.java` |
| 웹훅 라우팅 | `backend/.../api/LiveKitWebhookController.java` → `service/WebhookService.java` |
| Egress 시작(참가자/전체 믹스) | `backend/.../service/EgressService.java` |
| VAD 파이프라인(비동기 워커) | `backend/.../service/RecordingWorker.java` + `VadAnalyzer.java`(스텁) |
| AI 코칭(LLM 호출 생략) | `backend/.../service/AiCoachingService.java` |
| 발언시간·균형지수 조회 | `backend/.../service/SpeechStatsService.java` |
| 회의 UI/컨트롤 | `frontend/src/App.tsx` |
| 미디어 스택 설정 | `infra/livekit/livekit.yaml`, `infra/livekit/egress.yaml` |

---

## 알아둘 점 / 한계

- **OpenVidu3 = 내부적으로 LiveKit** 이므로, 토큰(AccessToken)·Egress·웹훅은 LiveKit
  server SDK로 그대로 구현했습니다(문서의 `livekit-server SDK` 서술과 일치).
- **Egress SDK 메서드의 인자 순서**는 SDK 버전(`io.livekit:livekit-server:0.8.1`)에 따라
  다를 수 있어 `EgressService.java` 한 곳에 격리했습니다. 빌드가 안 맞으면 이 파일만 조정하세요.
- 방 타임아웃(empty 120초 / departure 20초)은 `livekit.yaml`의 `room` 기본값으로 두었습니다(01 §4).
- 로그인/세션(Redis)은 데모 단순화를 위해 생략하고, `memberId`·이름을 참여 요청 바디로 받습니다(02 §7 참고).
- `ddl-auto: update` 로 테이블을 자동 생성합니다(데모 편의용).
```
