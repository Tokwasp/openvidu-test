# 녹음 — 어떤 경로로 어떻게 만들어지나

> 두 가지 결과물을 각각 다른 Egress로 만들고, S3에 직접 올린 뒤 SQS로 후처리 메시지를 쏜다.
> **DB에는 아무것도 저장하지 않는다** — 파일 경로가 곧 정보다.

---

## 1. 결론 (현재 구현)

| 원하는 것 | Egress 종류 | 시작 웹훅 | 출력 경로 |
| --- | --- | --- | --- |
| 참가자별 개인 음성 | **Track Egress** (오디오 트랙 무변환) | `track_published` | `meetings/{room}/{memberId}/{time}.ogg` |
| 회의 전체 믹스 | **RoomComposite Egress** (`audioOnly`) | `room_started` | `meetings/{room}/mixed/{time}.ogg` |

**멈추는 코드는 없다.** Track Egress는 그 트랙이 끊기거나 사람이 나가면, RoomComposite는 방이 끝나면 자동 종료된다.

> **초기 설계와 달라진 점:** 처음엔 개인 녹음을 **Participant Egress**로 하려 했으나 **OGG 400 에러**로 못 썼다.
> 자세한 건 §2. 지금은 오디오 트랙 하나만 저장하는 **Track Egress**를 쓴다.

---

## 2. 개인 녹음은 왜 Track Egress인가 (OGG 400 사연)

Participant Egress는 그 사람의 **오디오+비디오를 함께** 녹화하려 한다. 그런데 출력을 OGG(오디오 전용 컨테이너)로
지정하면, 비디오 트랙을 담을 코덱이 없어 LiveKit이
`no supported codec is compatible with all outputs`로 **400**을 낸다.

마이크 한 트랙만 저장하려면 **오디오 트랙 SID를 지정하는 Track Egress**를 쓴다.
Track Egress는 무변환(passthrough) — OPUS를 그대로 OGG로 떨어뜨려 코덱 충돌이 없다.

```java
// EgressService.startAudioTrackEgress (요약)
DirectFileOutput file = DirectFileOutput.newBuilder()
        .setFilepath("meetings/{room_name}/" + memberId + "/{time}.ogg")
        .setS3(s3Upload())            // 우리 AWS S3
        .build();
egressClient.startTrackEgress(roomName, file, trackId).execute();   // trackId = 오디오 트랙 SID
```

### 언제 시작하나 — `track_published`(오디오), 사람당 한 번

`WebhookService.onTrackPublished`가 다음 조건을 모두 만족할 때만 개인 Egress를 건다.

- 우리 방일 것 (`isKnownRoom`)
- 트랙 타입이 **AUDIO**일 것 (카메라·화면공유는 개인 오디오 녹음 대상이 아니다)
- identity가 **숫자(memberId)**일 것
- `(room|memberId)`가 아직 시작 안 됐을 것 (`ConcurrentHashMap` 기반 중복 방지 셋)

시작에 실패하면 그 키를 셋에서 빼서 **다음 발행 때 재시도**할 수 있게 한다.

> **왜 `participant_joined`가 아니라 `track_published`인가:** 참가자가 마이크 트랙을 발행하기 전에
> Egress를 걸면 LiveKit이 400을 낸다. 그래서 오디오 트랙이 실제로 올라온 시점에 건다.

---

## 3. 전체 믹스는 RoomComposite Egress (audioOnly)

```java
// EgressService.startMixedEgress (요약)
EncodedFileOutput file = EncodedFileOutput.newBuilder()
        .setFileType(EncodedFileType.OGG)                 // 오디오 전용 컨테이너
        .setFilepath("meetings/{room_name}/mixed/{time}.ogg")
        .setS3(s3Upload())
        .build();
egressClient.startRoomCompositeEgress(
        roomName, file,
        "",                    // layout 비움(오디오 전용 최적화 경로)
        null, null,
        true,                  // audioOnly
        false);                // videoOnly
```

`room_started` 웹훅에서, **방이 Redis에 등록돼 있을 때만**(`isKnownRoom`) 시작한다.
이 "등록돼 있을 때만"이 과거에 발목을 잡았다 — [01 §5-2](01-room-lifecycle.md) 참고.

### ⚠️ mixed는 개인보다 늦게 뜬다 (종료 시점이 다르다)

Egress는 **끝나야(egress_ended)** S3 업로드가 확정되고 그때 SQS 메시지가 나간다.
그런데 개인과 mixed는 끝나는 조건이 다르다.

| | 시작 | **종료 = S3/SQS로 나가는 시점** |
| --- | --- | --- |
| **개인** (Track) | `track_published` | 그 사람이 **나가는 즉시** 트랙 끊김 → 바로 `egress_ended` |
| **전체 mixed** (RoomComposite) | `room_started` | **방(room)이 닫혀야** 끝남 → `departure_timeout`(20초) 뒤 또는 "회의 종료" 버튼 |

즉 개인 파일은 각자 나가는 순간순간 뜨는데, **mixed는 회의가 완전히 끝나고 ~20초 뒤에야 한 번에 올라온다.**
회의가 아직 열려있거나 나간 지 20초 안 됐으면 mixed가 없는 게 정상이다. → [04 §진단 B](04-logging-and-troubleshooting.md)

---

## 4. 파일이 어떻게 남는가

### 4-1. S3 경로 규칙

```
meetings/{room_name}/{memberId}/{time}.ogg    ← 참가자별 (Track Egress)
meetings/{room_name}/mixed/{time}.ogg         ← 전체 믹스 (RoomComposite)
```

2명이 회의한 실제 예:

```
meetings/2196def2-5ac9-.../
├── mixed/2026-07-30T123043.ogg      ← 회의 전체 믹스 (1개)
├── 123/2026-07-30T123042.ogg        ← memberId 123
└── 456/2026-07-30T123505.ogg        ← memberId 456
```

`{room_name}`·`{time}`은 LiveKit이 채우는 템플릿 변수이고, `{memberId}`는 **우리가 경로에 직접 박는다**
(Egress를 시작할 때 그 사람의 memberId를 알고 있으므로).

### 4-2. 왜 폴더 이름이 이름이 아니라 memberId인가

| 문제 | 설명 |
| --- | --- |
| 동명이인 | 같은 이름이 둘이면 폴더가 겹쳐 파일이 섞인다 |
| 이름 변경 | 회원이 이름을 바꾸면 과거 파일과의 연결이 끊긴다 |
| 인코딩 | 한글·공백·특수문자가 S3 키·URL에서 깨질 수 있다 |
| 개인정보 | URL에 실명이 그대로 노출된다 |

`memberId`는 절대 바뀌지 않는다. 사람이 읽는 이름은 **후처리 단계**에서 DB 조인으로 붙인다.

---

## 5. 끝나면 DB가 아니라 SQS로 보낸다

`egress_ended` 웹훅이 오면 백엔드는 DB에 저장하지 않는다. **SQS로 후처리 메시지 한 건을 발행**한다.
워커/Lambda가 그걸 받아 S3의 음성을 읽고 VAD → 발화시간 → RDS로 이어간다.

### 5-1. 무엇을 보내나 (`RecordingEvent`)

```json
{
  "roomName": "2196def2-5ac9-43f7-8192-00fe77d729fe",
  "memberId": 123,                 // MIXED 이면 null
  "kind": "PARTICIPANT",           // MIXED | PARTICIPANT
  "objectKey": "meetings/2196def2-.../123/2026-07-30T123042.ogg",
  "egressId": "EG_bFYsJhdoUQVE",
  "endedAt": "2026-07-30T12:35:30.15"
}
```

`WebhookService.onEgressEnded`는 `EgressInfo`의 `file_results[0].filename`으로 objectKey를 얻고,
경로가 `meetings/{room}/mixed/...`이면 `MIXED`, 아니면 경로에서 memberId를 파싱해 `PARTICIPANT`로 판단한다.
**파일이 없으면(`fileResultsCount == 0`) 발행하지 않는다** — 처리할 게 없기 때문이다(실패/빈 Egress).

### 5-2. 개인/전체는 서로 다른 큐로 나눈다 (`RecordingEventPublisher`)

| kind | 대상 큐 (`application.yml`) |
| --- | --- |
| `PARTICIPANT` (개인) | `aws.sqs.personal-queue-url` |
| `MIXED` (전체) | `aws.sqs.queue-url` |

- 개인 큐(`personal-queue-url`)가 비어 있으면 개인 녹음도 단체 큐로 발행한다(하위 호환).
- 둘 다 비어 있으면 발행을 **조용히 생략**한다(로컬/개발).
- 발행 실패는 **로그만 남기고 넘어간다** — 웹훅은 항상 200을 유지해야 LiveKit 재시도 폭주를 막는다.

```
egress_ended (kind=MIXED)        → queue-url          → 단체 믹스 후처리 워커
egress_ended (kind=PARTICIPANT)  → personal-queue-url → 개인 음성 후처리 워커
```

---

## 6. 녹음이 만들어지는 전체 경로

```
[사용자]           [백엔드]                    [LiveKit / Egress]        [S3]        [SQS]
   |                  |                             |                    |            |
   |-- 회의 참여 ----->|                             |                    |            |
   |                  |-- (방 없으면) Redis open ---→ (먼저!)             |            |
   |                  |-- createRoom -------------→ room 생성            |            |
   |                  |<-- webhook: room_started --| (Redis에 있음 →)     |            |
   |                  |-- startMixedEgress -------→ 전체 믹스 시작        |            |
   |<-- token --------|                             |                    |            |
   |== WebRTC 연결 ==============================→|                    |            |
   |                  |<-- webhook: track_published(audio)               |            |
   |                  |-- startAudioTrackEgress --→ 개인 녹음 시작        |            |
   |-- 마이크 사용 ------------------------------→|                    |            |
   |-- 퇴장 ------------------------------------→| 개인 Egress 종료      |            |
   |                  |                             |==== 업로드 =======→|            |
   |                  |<-- webhook: egress_ended --|                    |            |
   |                  |-- SQS 발행(kind=PARTICIPANT) ------------------------------→| 개인 큐
   |                  |                             |                    |            |
   |        (마지막 사람 퇴장 + departure_timeout 20s)                    |            |
   |                  |<-- webhook: egress_ended --| 전체 믹스 완료 ====→|            |
   |                  |-- SQS 발행(kind=MIXED) ------------------------------------→| 단체 큐
   |                  |<-- webhook: room_finished -|                    |            |
   |                  |-- Redis closeByRoom        |                    |            |
```

---

## 7. 알아둘 것

- **사람 수만큼 Egress가 돈다.** 6명이면 개인 6 + 전체 믹스 1 = 동시에 7개. 오디오 전용이라 무겁진 않다.
- **전체 믹스(RoomComposite)는 Egress 컨테이너에서 Chrome을 띄운다.** 개인 Track Egress는 무변환이라 Chrome이 필요 없다.
  그래서 "개인만 되고 mixed만 실패"하면 Chrome/RoomComposite 쪽을 의심한다. → [04 §진단 B](04-logging-and-troubleshooting.md)
- **개인 파일에 없는 구간은 전체 믹스에만 있다.** 나갔다 들어온 사이의 대화 등. 시간축은 파일명의 `{time}`으로 맞춘다.
- **실패해도 통째로 날아가지 않는다.** 개인 하나가 실패해도 전체 믹스가 남는다(반대도 마찬가지).
- **녹음 고지.** 회의가 항상 녹음된다는 사실은 입장 전에 UI로 알린다. 법적 문제 이전에 신뢰 문제다.

---

## 8. 정리

```
개인 = Track Egress(오디오 트랙 무변환) → meetings/{room}/{memberId}/{time}.ogg
전체 = RoomComposite Egress(audioOnly) → meetings/{room}/mixed/{time}.ogg
S3에 직접 업로드 → egress_ended → SQS 발행 (개인=personal 큐, 전체=단체 큐)
DB 저장 없음. mixed는 방이 닫혀야 나오므로 개인보다 늦다.
```

- 다음: [접근 토큰](02-access-token.md) · [회의방 관리](01-room-lifecycle.md) · [로그·진단](04-logging-and-troubleshooting.md) · [전체 설계](docs.md)
