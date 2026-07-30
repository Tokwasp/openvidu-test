# 접근 토큰 — 언제 만들고, 얼마나 살고, 어디에 두나

> 한 줄 요약: **어디에도 저장하지 않는다.** 요청이 올 때마다 새로 만들어서 내려주고 잊는다.

---

## 1. 저장하나 → 안 한다

토큰을 담아둘 곳이 애초에 없다. 회의 상태는 Redis 키 두 개뿐이고([01](01-room-lifecycle.md)),
거기에 토큰 자리는 없다. 만들지 않는다.

---

## 2. "이미 회의가 있으면 토큰도 알고 있어야 하지 않나" → 아니다

**토큰은 "방"의 것이 아니라 "사람 × 방"의 것이다.** 같은 회의방 `e5f6`에 대해서도 사람마다 토큰이 다르다.

| 사람 | 토큰에 들어가는 내용 |
| --- | --- |
| memberId 7 | `identity=7`, `name=김경현`, `room=e5f6` |
| memberId 11 | `identity=11`, `name=이철수`, `room=e5f6` |

방에 토큰 하나를 저장해두고 돌려쓰면, 셋 다 **같은 identity로 입장**해 LiveKit이 중복 접속으로 서로를 밀어낸다.
녹음 파일도 전부 같은 폴더(`meetings/{room}/7/`)에 섞인다. 즉 저장할 수 있는 물건이 아니다.

> 비유: 회의방은 **강의실**, 토큰은 **출입 카드**다. 강의실에 카드 한 장을 붙여두는 게 아니라,
> 들어가려는 사람마다 이름이 적힌 카드를 발급한다.

---

## 3. 언제 만드나 → 참여 API가 호출될 때마다

```
POST /api/projects/5/meetings/join
  ↓
  1. @Login 으로 "누구인지" 확인          (memberId 7)  — 세션 또는 dev 우회(§7)
  2. 회의방 찾거나 만들기                 (roomName = e5f6)
  3. ★ 여기서 토큰 생성 ★               (identity=7 + room=e5f6 로 서명)
  4. 응답 { roomName, token, livekitUrl, created }
```

**2번에서 방을 새로 만들든 기존 방을 찾든, 3번은 똑같이 매번 실행된다.**

```java
// TokenService.issue — 실제 코드
AccessToken token = new AccessToken(props.apiKey(), props.apiSecret());
token.setIdentity(String.valueOf(memberId));   // "7"
token.setName(memberName);
token.setTtl(props.tokenTtlMs());              // ms 단위. 600000 = 10분
token.addGrants(new RoomJoin(true), new RoomName(roomName));
return token.toJwt();                          // HMAC 서명 한 번. 네트워크도 DB도 안 탐
```

LiveKit 서버에 물어보지도 않는다. **우리가 가진 API Secret으로 서명하면 그게 곧 유효한 토큰이다.**

---

## 4. 얼마나 유효한가 → 10분. 그런데 회의 길이와 무관하다

**토큰 만료는 "입장할 수 있는 시간"이지 "회의할 수 있는 시간"이 아니다.**
한 번 연결이 맺어지면 그 뒤로는 토큰을 다시 검사하지 않는다. 그래서 짧게 잡아도 3시간 회의가 안전하다.

`application.yml`: `livekit.token-ttl-ms: 600000` (10분).
`AccessToken.setTtl`은 **밀리초**를 받는다(SDK 기본값 6시간을 10분으로 줄인 것).

---

## 5. 어디에 보관하나

| 위치 | 보관 여부 | 이유 |
| --- | --- | --- |
| 서버 (Redis/DB) | ❌ 저장 안 함 | §2 — 사람마다 다르고, 만드는 게 공짜다 |
| **프론트 (변수/메모리)** | ⭕ 여기만 | 연결할 때 한 번 쓰고 버린다 |
| localStorage / 쿠키 / 로그 | ❌ 금지 | 만료돼도 남고, 유출되면 만료 전까지 누구나 쓴다 |

새로고침하면 토큰은 사라진다. **그러면 참여 API를 다시 호출하면 된다.**
그때 서버는 "이미 열린 회의가 있네" 하고 같은 방의 새 토큰을 내려준다(`created=false`) — 회의는 끊기지 않는다.

---

## 6. 토큰에 뭐가 들어가나

| 항목 | 값 | 왜 |
| --- | --- | --- |
| `identity` | `memberId` (문자열 `"7"`) | **녹음 파일 폴더 이름이 된다.** 절대 안 바뀌는 값이어야 한다 |
| `name` | 회원 이름 | 다른 참가자 화면에 표시되는 이름 |
| grant `RoomJoin` | `true` | 입장 허용 |
| grant `RoomName` | `e5f6...` | **이 방에만** 들어갈 수 있다 |
| 만료(ttl) | 10분 | §4 |

`identity`에 이름이나 이메일을 쓰지 않는 이유 — 동명이인, 개명, 한글 인코딩, 개인정보 노출.
녹음 경로에 `{memberId}`가 그대로 박히기 때문이다. → [03 §5-2](03-recording.md)

---

## 7. 우리 세션(Upstash Redis)과는 무슨 관계인가

이 접근 토큰(LiveKit JWT)과, 우리 앱 로그인(세션)은 **별개의 인증**이다.

| | 우리 세션 | 접근 토큰(AccessToken) |
| --- | --- | --- |
| 누가 ↔ 누구 | 브라우저 ↔ **우리 백엔드** | 브라우저 ↔ **LiveKit 서버** |
| 형태 | `SESSION` 쿠키 + Redis | JWT |
| 서명 키 | — | `LIVEKIT_API_SECRET` |
| 검증하는 쪽 | 우리 백엔드 | **LiveKit 서버** |
| 우리 백엔드가 검증하나 | 한다 | **안 한다.** 만들어서 줄 뿐 |

회의 서비스는 **모놀리식과 같은 Upstash Redis·같은 `SESSION` 쿠키·같은 직렬화**를 공유한다.
프론트가 세션 쿠키만 담아 보내면 `LoginMemberArgumentResolver`가 Redis 세션에서 `LoginMember`를 읽어 회원을 식별한다.
(`application.yml`의 `spring.data.redis` + `spring.session`, `SessionConfig` 참고.)

### 7-1. 개발용 세션 우회 (`meeting.dev-auth-bypass`)

모놀리식 로그인 없이 녹음 파이프라인만 단독으로 테스트하려고 만든 우회다.

- `meeting.dev-auth-bypass=true`일 때만, 세션이 없으면 **쿼리파라미터** `?memberId=123&memberName=홍길동`으로 회원을 만든다.
- 로그에 `[DEV] 세션 우회로 참여 — memberId=123 (운영에서 끌 것)`이 찍힌다.
- **운영에서는 절대 켜지 말 것** — 아무나 memberId를 사칭할 수 있다. 기본값은 `false`.

> **JWT를 쓰는 건 우리 선택이 아니라 LiveKit 프로토콜의 요구다.** LiveKit은 별도 호스트라 우리 Redis를 모르고
> 쿠키 도메인도 공유하지 않는다. 자기 API Secret으로 서명된 JWT만 받는다.
> 그래서 `jjwt` 같은 라이브러리도 필요 없다 — `livekit-server` SDK가 서명까지 한다.

---

## 8. 정리

```
만드는 시점 : 참여 API 요청마다 (방이 있든 없든 똑같이)
유효 기간   : 10분 — 단, 입장까지만. 연결 후에는 만료돼도 회의 유지
서버 저장   : 안 함
프론트 저장 : 메모리에만. 새로고침하면 참여 API 재호출
```

- 다음: [회의방 관리](01-room-lifecycle.md) · [녹음](03-recording.md) · [로그·진단](04-logging-and-troubleshooting.md) · [전체 설계](docs.md)
