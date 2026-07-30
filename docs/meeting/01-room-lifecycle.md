# 회의방 관리 — Redis 키로 방을 통제한다

> 한 줄 요약: **회의 한 건 = Redis 키 한 쌍.**
> LiveKit(OpenVidu 3)의 Room은 그 키를 따라 만들어졌다 사라지는 소모품이다.
>
> (초기 설계는 MySQL `meeting` 테이블이었지만, 지금은 MySQL을 쓰지 않는다. 상태는 전부 Redis에 있다.)

---

## 1. 상태의 주인은 Redis다 — `RoomRegistry`

`RoomRegistry`가 두 개의 Redis 키로 방 상태를 표현한다.

```
meeting:project:{projectId} → roomName    (참여 시 "이 프로젝트에 열린 방이 있나"를 이 키로 판단)
meeting:room:{roomName}     → projectId    (웹훅은 roomName만 아므로 역인덱스)
```

두 키 모두 **TTL 2시간**을 건다. 회의는 길어야 2시간이라는 가정 아래, 웹훅이 유실돼 방이 안 닫혀도
2시간 뒤 자동으로 사라지는 안전망이다. 정상 종료는 `room_finished` 웹훅이 키를 지운다(§5).

```java
// RoomRegistry (요약)
public Optional<String> findRoom(int projectId) { ... }   // meeting:project:{id} 조회
public boolean isKnownRoom(String roomName)     { ... }   // meeting:room:{roomName} 존재?
public void open(int projectId, String roomName){ ... }   // 두 키를 함께 심는다(TTL 2h)
public void closeByRoom(String roomName)        { ... }   // roomName으로 닫는다(멱등)
```

### 왜 LiveKit에 안 묻고 Redis를 SoT로 두나

LiveKit Room은 휘발성이다. 마지막 참가자가 나가고 `departure_timeout`이 지나면 스스로 사라진다.
그래서 `listRooms()`로 "방 있나?"를 물으면, 잠깐 다 나간 사이엔 "없다"가 나와 같은 회의가 여러 개로 쪼개진다.
**"회의가 열려 있나"는 항상 Redis에 묻는다.**

---

## 2. 있으면 쓰고 없으면 만든다 (get-or-create)

```
findRoom(projectId)
  ├─ 있음 → 그 roomName을 그대로 쓴다. 아무것도 만들지 않는다. 토큰만 새로 서명해 내려준다. (created=false)
  └─ 없음 → LiveKit에 createRoom + Redis에 open. (created=true)
```

### 동시 클릭은 방어하지 않는다 — 의도적이다

두 사람이 **같은 순간에** "회의 참여"를 누르면 둘 다 "방 없음"으로 읽고 방을 두 개 만들 수 있다.
유니크 제약이나 락으로 막을 수는 있지만 이 규모에서는 비용이 이득보다 크다고 판단한다.

| | |
| --- | --- |
| 발생 조건 | 같은 프로젝트에서 두 명이 **거의 동시에** 첫 입장을 누를 때만 |
| 데이터 손상 | 없다. 두 방 모두 정상이다 |
| 회복 | 두 방 모두 사람이 나가면 각각 `room_finished`(또는 TTL)로 닫힌다. **다음 회의는 정상** |

> 나중에 실제로 문제가 되면 그때 막는다(예: `SET NX`로 프로젝트 키 선점).

---

## 3. roomName은 회의마다 바뀐다

회의 한 건에 roomName 하나(UUID)다. 재사용하지 않는다.

```java
String roomName = UUID.randomUUID().toString();   // 회의마다 새 UUID (녹음 폴더의 자연 키)
```

`project-5` 같은 고정 이름을 쓰면 두 가지가 깨진다.

| 문제 | 설명 |
| --- | --- |
| 녹음 폴더가 섞인다 | 월요일 회의와 화요일 회의 파일이 `meetings/project-5/` 한 폴더에 쌓인다 |
| 웹훅이 헷갈린다 | `room_finished`는 roomName만 알려준다. 방금 새로 연 회의를 닫아버릴 수 있다 |

### 프론트는 신경 쓸 필요 없다

roomName이 매번 바뀌지만 프론트가 저장할 이유가 없다. 참여 API가 매번 현재 roomName을 토큰과 함께 내려준다.

```
POST /api/projects/5/meetings/join
  → { roomName, token, livekitUrl, created }
```

> **회의 식별자는 roomName(UUID) 하나로 통일한다.** 초기 설계의 `meetingId`(DB PK)는 없앴다
> — DB가 없으니 PK도 없다. 종료 API도 roomName으로 부른다: `DELETE /api/meetings/{roomName}`.

---

## 4. 타임아웃 두 개

두 값 모두 **방이 언제 없어지는가**를 정한다. `application.yml`에서 방 생성 시 명시한다.

| 설정 | 언제 세는가 | 우리 값 |
| --- | --- | --- |
| `departure-timeout-sec` | 사람이 있었다가 **마지막 한 명이 나간 뒤** | **20초** |
| `empty-timeout-sec` | 방은 만들어졌는데 **아무도 안 들어온 채** | **120초** |

```
방 생성 ──(아무도 안 옴)────── empty_timeout(120s) 만료 → 방 삭제 → room_finished
   │
   └─ 첫 참가자 입장 ─ ... 회의 ... ─ 마지막 퇴장 ─ departure_timeout(20s) 만료 → 방 삭제 → room_finished
```

방이 삭제되면 LiveKit이 **`room_finished` 웹훅**을 보내고, 그때 Redis 키가 지워진다(§5).

> **주의(녹음과 직결):** 전체 mixed Egress는 **방이 닫혀야** 끝난다.
> 즉 마지막 사람이 나가고 `departure_timeout`(20초) 뒤에야 mixed 파일이 S3에 올라오고 SQS 메시지가 나간다.
> 개인 Egress는 그 사람이 나가는 즉시 끝나므로 훨씬 빨리 뜬다. → [03 §3](03-recording.md), [04 진단](04-logging-and-troubleshooting.md)

---

## 5. 상태가 바뀌는 지점과 room_started 경합 (중요)

### 5-1. 여는 곳 / 닫는 곳

| 언제 | 무슨 일 | Redis |
| --- | --- | --- |
| 참여 API (방이 없을 때) | `MeetingService.openNewRoom` | `open()` — 두 키 심기 |
| `room_finished` 웹훅 | 방 삭제됨 | `closeByRoom()` — 두 키 지우기(멱등) |

**닫는 경로는 `room_finished` 웹훅 하나뿐이다.** "회의 종료" 버튼(`DELETE /api/meetings/{roomName}`)도
Redis를 직접 건드리지 않는다. LiveKit에 `deleteRoom()`만 요청하고, 그 결과로 오는 `room_finished` 웹훅이 닫는다.

### 5-2. 등록은 createRoom보다 **먼저** 해야 한다 — 이게 핵심이다

`createRoom()`을 부르는 순간 LiveKit이 **`room_started` 웹훅을 즉시 발사**한다
(`auto_create: false`라 방은 백엔드 createRoom으로만 생기고, 그 시점에 room_started가 온다).
이 웹훅은 전체 mixed Egress를 시작하려고 `isKnownRoom()`을 확인한다.

그래서 순서가 뒤집히면 안 된다.

```java
// MeetingService.openNewRoom  — 순서가 핵심이다
roomRegistry.open(projectId, roomName);   // ① Redis 등록 먼저 → room_started 웹훅이 방을 인식
try {
    createRoom(roomName);                 // ② 이제 createRoom (여기서 room_started 발사)
} catch (RuntimeException e) {
    roomRegistry.closeByRoom(roomName);   // ③ 방 생성 실패 → 등록 롤백(못 들어가는 방 방지)
    throw e;
}
```

**과거 버그:** 예전엔 `createRoom()` → `open()` 순이라, `room_started` 웹훅이 Redis 등록보다 먼저 도착하면
`isKnownRoom() == false` → early return → **mixed Egress가 아예 시작 안 됐다.**
개인 Egress는 한참 뒤 `track_published` 시점이라 방이 이미 등록돼 있어 멀쩡했다 → "개인만 되고 mixed는 안 됨" 증상.
로그로 확인하는 법은 [04 §진단 A](04-logging-and-troubleshooting.md).

---

## 6. 웹훅이 유실되면 (드리프트)

LiveKit은 웹훅을 재시도하지만 영구 보장은 아니다. 백엔드가 재배포 중이면 `room_finished`를 놓칠 수 있다.
그러면 Redis에는 방이 열려 있는데 LiveKit에는 방이 없는 상태가 남는다.
이 경우 **TTL 2시간이 안전망**이라 자동으로 정리된다. 사용자는 못 들어가는 게 아니라
빈 방에 혼자 들어가게 되고(같은 이름으로 방이 다시 생성됨), 치명적이지 않다.

---

## 7. 한 장으로

```
프로젝트 5
   │
   └─ meeting:project:5 → "e5f6..."       ← 지금 열린 방 (참여 요청은 이 키를 찾아 재사용)
      meeting:room:e5f6... → "5"          ← 역인덱스 (웹훅이 roomName으로 프로젝트를 되찾음)
                    │
                    └─ LiveKit Room "e5f6..."   ← 사람이 다 나가면 timeout 후 사라짐
                                                  사라지면 room_finished 웹훅 → Redis 두 키 삭제
                                                  다음에 회의를 열면 새 UUID로 새 키 한 쌍
```

- 다음: [접근 토큰](02-access-token.md) · [녹음](03-recording.md) · [로그·진단](04-logging-and-troubleshooting.md) · [전체 설계](docs.md)
