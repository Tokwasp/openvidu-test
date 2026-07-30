# 회의(Meeting) 문서

> 이 문서들은 **현재 구현된 코드** 기준이다(초기 설계 명세가 아니다).
> 코드가 바뀌면 이 문서도 같이 고친다.

## 어떤 순서로 읽나

| 문서 | 내용 | 언제 보나 |
| --- | --- | --- |
| **이 파일** | 꼭 알아야 하는 것만 추린 요약 | 먼저 |
| [01-room-lifecycle.md](01-room-lifecycle.md) | 회의방을 **Redis 키**로 관리하는 법, roomName, 타임아웃, room_started 경합 | 방이 어떻게 열리고 닫히는지 볼 때 |
| [02-access-token.md](02-access-token.md) | 접근 토큰을 언제 만들고 어디에 두나 | "토큰을 저장해야 하나" 싶을 때 |
| [03-recording.md](03-recording.md) | 녹음이 만들어지는 경로(Track Egress + RoomComposite), S3·SQS로 나가는 법 | 녹음 관련 전부 |
| [04-logging-and-troubleshooting.md](04-logging-and-troubleshooting.md) | **로그 보는 법 + 문제별 진단 순서** | 녹음이 안 될 때, 뭘 봐야 하나 |
| [docs.md](docs.md) | 전체 설계 명세 (API, Redis, 웹훅, SQS, 설정) | 구현/배포 직전에 |

---

## 꼭 알아야 하는 것 8개

### 1. 회의방 상태는 Redis 키 하나로 관리한다 (DB 아님)

초기 설계는 MySQL `meeting` 테이블이었지만, **지금은 Redis만 쓴다**(MySQL 제거).
`RoomRegistry`가 두 개의 Redis 키로 "이 프로젝트에 열린 방이 있나"를 판단한다.

```
meeting:project:{projectId} → roomName    (참여 시 방 유무 판단)
meeting:room:{roomName}     → projectId    (웹훅은 roomName만 아므로 역인덱스)
```

두 키 모두 TTL 2시간(웹훅 유실 시 자동 정리 안전망). → [01 §1](01-room-lifecycle.md)

### 2. 프로젝트당 열린 회의는 하나 — Redis 조회로 판단한다

`findRoom(projectId)`로 `meeting:project:{id}` 키를 찾아, **있으면 재사용하고 없으면 만든다.**
동시 첫 입장으로 방이 둘 생기는 건 이 규모에서 방어하지 않는다(의도적). → [01 §2](01-room-lifecycle.md)

### 3. 방은 Redis에 먼저 등록하고 그다음 createRoom 한다 (순서 중요)

`createRoom()`을 부르는 순간 LiveKit이 **`room_started` 웹훅을 즉시 쏜다.**
이 웹훅이 전체 mixed Egress를 시작하려고 `isKnownRoom()`을 보므로, **Redis 등록이 먼저**여야 한다.
반대로 하면 웹훅이 "알 수 없는 room"으로 걸러져 **mixed 녹음이 통째로 안 남는다.**
(실제로 이 버그가 있었고 순서를 바꿔 고쳤다.) → [01 §5](01-room-lifecycle.md)

### 4. roomName은 회의마다 새로 만들어진다

회의 한 건 = UUID 하나. 재사용하지 않는다. 녹음 폴더가 섞이고 웹훅이 어느 회의인지 구분 못 하기 때문이다.
**프론트는 저장할 필요 없다** — 참여 API가 매번 현재 roomName을 내려준다. → [01 §3](01-room-lifecycle.md)

### 5. 토큰은 어디에도 저장하지 않는다

토큰은 방의 것이 아니라 **"사람 × 방"의 것**이다. 요청마다 새로 서명한다 — HMAC 한 번이라 사실상 공짜다.
`identity`는 `memberId`다(녹음 폴더 이름이 되므로 안 바뀌는 값). → [02](02-access-token.md)

### 6. 개인 녹음 = Track Egress(오디오 트랙 무변환 저장)

초기 설계의 Participant Egress는 **OGG 400 에러**가 나서 못 썼다(오디오+비디오를 함께 담으려는데
OGG는 오디오 전용 컨테이너라 코덱 충돌). 그래서 **오디오 트랙 하나만 무변환으로 저장하는 Track Egress**로 갔다.
`track_published`(마이크 발행) 웹훅에서, 사람당 한 번 시작한다. → [03 §2](03-recording.md)

### 7. 전체 믹스 녹음 = RoomComposite Egress(audioOnly)

`room_started` 웹훅에서 방 전체를 하나로 합친 오디오 믹스를 시작한다.
**mixed는 방(room)이 닫혀야 종료**되고 그때 S3/SQS로 나간다 — 개인보다 늦게 뜬다. → [03 §3](03-recording.md)

### 8. 녹음은 S3에 직접 올라가고, 끝나면 SQS로 후처리 메시지를 쏜다 (DB 저장 안 함)

Egress가 파일을 **우리 AWS S3**에 직접 쓴다(백엔드는 파일을 중계하지 않는다).
`egress_ended` 웹훅이 오면 백엔드는 DB에 저장하는 게 아니라 **SQS로 메시지 한 건을 발행**한다.
워커/Lambda가 그걸 받아 VAD → 발화시간 → RDS로 이어간다.
개인(PARTICIPANT)과 전체(MIXED)는 **서로 다른 큐**로 나눠 발행한다. → [03 §5](03-recording.md)

---

## 결과물이 어떻게 남는지 한눈에

S3에 쌓이는 모습 (2명 회의)

```
meetings/2196def2-5ac9-.../     ← 이 회의의 room_name (UUID)
├── mixed/2026-07-30T123043.ogg      ← 전체 믹스 1개 (RoomComposite)
├── 123/2026-07-30T123042.ogg        ← memberId 123 개인 음성 (Track Egress)
└── 456/2026-07-30T123505.ogg        ← memberId 456 개인 음성
```

`egress_ended` 시 SQS로 나가는 메시지 (개인 한 건)

```json
{
  "roomName": "2196def2-5ac9-43f7-8192-00fe77d729fe",
  "memberId": 123,
  "kind": "PARTICIPANT",
  "objectKey": "meetings/2196def2-5ac9-43f7-8192-00fe77d729fe/123/2026-07-30T123042.ogg",
  "egressId": "EG_bFYsJhdoUQVE",
  "endedAt": "2026-07-30T12:35:30.15"
}
```

폴더 이름이 이름이 아니라 `123`(memberId)인 이유 — 동명이인, 개명, 한글 인코딩, 개인정보 노출.
사람이 읽는 이름은 후처리 워커/조회 단계에서 붙인다. → [03 §5-2](03-recording.md)

---

## 백엔드가 실제로 하는 일은 네 가지뿐

```
1. 토큰 발급        POST /api/projects/{projectId}/meetings/join
2. 방 수명 관리      Redis 등록/조회 + 웹훅(room_finished)으로 닫기
3. 녹음 시작 지시    웹훅(room_started/track_published) 받고 Egress 두 종류 시작 (멈추는 코드는 없다)
4. 후처리 발행       egress_ended 웹훅 → SQS 메시지 (DB 저장 없음)
```

**음성 데이터는 백엔드를 한 번도 지나가지 않는다.**
브라우저 ↔ LiveKit이 직접 주고받고, LiveKit(Egress) ↔ S3가 직접 주고받는다.

**녹음 메타데이터를 DB에 저장하지 않는다** — 파일 경로가 곧 정보다
(`meetings/{roomName}/{memberId}/{time}.ogg`). SQS 메시지에 roomName·memberId·kind·objectKey를 담아 넘긴다.

---

## 초기 설계에서 바뀐 것 (이 문서들이 반영한 것)

| 항목 | 초기 설계(zip 원본) | **현재 구현** |
| --- | --- | --- |
| 방 상태 저장소 | MySQL `meeting` 테이블 | **Redis 키(RoomRegistry)**, MySQL 제거 |
| 회의 식별자 | `meetingId`(DB PK) | **roomName(UUID)** 하나로 통일 |
| 개인 녹음 | Participant Egress | **Track Egress**(오디오 트랙, OGG 400 회피) |
| 방 등록 순서 | createRoom → DB 저장 | **Redis 등록 → createRoom**(room_started 경합 해결) |
| 녹음 결과 처리 | DB `meeting_recording` 저장 + presigned URL 조회 API | **SQS 발행**(개인/전체 큐 분리), DB·조회 API 없음 |
| 저장소 | 내장 MinIO | **우리 AWS S3** 직접 업로드 |
