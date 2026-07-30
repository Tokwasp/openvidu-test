# EC2 배포 가이드 — Caddy(HTTPS) + UFW

보안그룹을 쓸 수 없는(빌린) EC2에 이 회의 스택을 **HTTPS**로 올리는 순서다.
HTTPS 리버스 프록시는 저장소에 이미 포함된 **Caddy**(`--profile proxy`)를 쓴다.
Caddy 가 Let's Encrypt 인증서를 **자동 발급·갱신**하므로 certbot 을 따로 돌릴 필요가 없다.

> 표기: `i15d205.p.ssafy.io` = 서비스 도메인. `ubuntu@<EC2-IP>` = SSH 접속 계정/주소.
> 본인 값으로 바꿔 읽는다.

---

## 0. 먼저 — 지금 설정 점검 결과

클론한 저장소를 **로컬 데모 상태 그대로** EC2에 올리면 HTTPS에서 회의가 안 된다. 이유와 조치:

| # | 문제 | 왜 | 조치 |
| --- | --- | --- | --- |
| 1 | **`ws://` 혼합 콘텐츠 차단** | HTTPS 페이지는 `ws://`(평문) 연결을 브라우저가 막는다. `.env` 기본값이 `ws://localhost:7880` 이다 | `LIVEKIT_WS_URL=wss://<도메인>/rtc` 로 바꾼다. Caddy 가 `/rtc` 를 LiveKit 으로 넘긴다 (§4) |
| 2 | **Docker 가 UFW 를 우회** | 도커는 iptables 를 직접 건드려, `3306:3306` 같은 매핑을 UFW 로 막아도 외부에서 접속된다 | 내부 포트를 `127.0.0.1` 에 바인딩(이미 `docker-compose.yml` 에 반영) + UFW (§5) |
| 3 | **LiveKit `use_external_ip: false`** | EC2 는 (퍼블릭이라도) 공인 IP 가 NIC 에 직접 붙지 않고 AWS 가 공인↔사설을 1:1 매핑한다. NIC 엔 사설IP(172.31.x.x)만 보여, LiveKit 이 브라우저에 사설IP 를 알려준다 | `livekit.yaml` 에서 `true` 로 → STUN 으로 공인IP 자동 감지 (§3) |
| 4 | **데모 키 그대로** | `devkey` / `secretsecret...` 는 공개된 값이라 누구나 입장·녹음 가능 | 실제 키로 교체 (§3) |
| 5 | **미디어 UDP/TCP 포트** | 시그널링만 Caddy 로 가고, 실제 오디오는 UDP 50000-50060 / TCP 7881 로 직접 흐른다 | 이 포트만 UFW 로 연다 (§5) |

이 문서대로 하면 1~5가 모두 처리된다.

> **왜 nginx+certbot 이 아니라 Caddy 인가**: main 에 이미 Caddy 리버스 프록시가 들어와 있고
> (`caddy` 서비스 + `infra/caddy/Caddyfile`), 인증서 자동 관리라 운영이 더 단순하다.
> nginx 를 꼭 써야 하는 사정이 있으면 히스토리에서 되살릴 수 있다.

---

## 1. 최종 아키텍처

```
                       인터넷
                         │
        ┌────────────────┼───────────────────────────┐
        │ 443/tcp, 80/tcp│               7881/tcp     │ 50000-50060/udp
        ▼                                ▼            ▼
   i15d205.p.ssafy.io              (LiveKit 미디어 — Caddy 안 거침)
        │                                └────────────┬───────────┐
   ┌────▼─────────────────────────┐                  │           │
   │  caddy (Let's Encrypt 자동)   │  docker 컨테이너 │           │
   │   /rtc*  → livekit:7880 (wss) │                  │           │
   │   /      → frontend:80        │                  │           │
   └────┬───────────────┬─────────┘                  │           │
        │ frontend:80    │ livekit:7880 (도커 네트워크) ▼           ▼
   ┌─────────┐   ┌──────────┐  docker 내부      ┌──────────────────┐
   │ frontend│──▶│ backend  │──────────────────▶│ LiveKit(OpenVidu3)│
   │ (nginx) │   │ (Spring) │◀── webhook ────────│  + Egress        │
   └─────────┘   └────┬─────┘                    └────────┬─────────┘
                      │                                   │ .ogg
                 MySQL·Redis                          MinIO(내부)
```

- **Caddy** 만 공인망 80/443 에 열린다. 나머지는 전부 `127.0.0.1` 뒤에 숨는다.
- **미디어(UDP/TCP)** 만 Caddy 를 우회해 브라우저와 EC2 가 직접 주고받는다 → UFW 로 연다.
- Caddy 는 frontend/livekit 에 **도커 네트워크 이름**으로 접근하므로, 그 포트들이
  `127.0.0.1` 에만 바인딩돼 있어도 문제없다.

---

## 2. 사전 준비 (DNS)

도메인 A 레코드가 EC2 공인 IP를 가리키는지 확인한다. (SSAFY 도메인은 이미 연결돼 있다.)

```bash
dig +short i15d205.p.ssafy.io    # → EC2 공인 IP 가 나와야 한다
```

필요한 패키지:
```bash
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
```

---

## 3. 프로덕션 값 채우기 (도커 올리기 전에)

### 3-1. 실제 LiveKit 키 생성

```bash
openssl rand -hex 16      # → LIVEKIT_API_KEY 로 쓸 값
openssl rand -base64 32   # → LIVEKIT_API_SECRET 로 쓸 값
```

이 두 값을 **아래 세 곳에 똑같이** 넣는다:
`.env`, `infra/livekit/livekit.yaml`, `infra/livekit/egress.yaml`.

### 3-2. `.env` 작성

```bash
cp .env.example .env
```

`.env` 를 아래처럼 수정:

```dotenv
# 브라우저가 붙는 주소 — 반드시 wss:// + /rtc (caddy 가 /rtc 를 LiveKit 으로 넘긴다)
LIVEKIT_WS_URL=wss://i15d205.p.ssafy.io/rtc

# 도메인 HTTPS (caddy 프로필)
DOMAIN=i15d205.p.ssafy.io
ACME_EMAIL=본인이메일@example.com

LIVEKIT_API_KEY=<3-1 의 key>
LIVEKIT_API_SECRET=<3-1 의 secret>

MINIO_ACCESS_KEY=<임의의 값으로 교체>
MINIO_SECRET_KEY=<임의의 값으로 교체>
MINIO_BUCKET=recordings
```

### 3-3. `infra/livekit/livekit.yaml` 수정

```yaml
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 50060
  use_external_ip: true        # ← false 에서 변경. 퍼블릭 EC2 라도 공인IP 가
                               #    NIC 에 없어 STUN 으로 공인IP 를 찾아 알려줘야 한다.
                               #    (고정 EIP 면 대신 node_ip: <공인IP> 로 못박아도 됨)

keys:
  <key>: <secret>              # ← 3-1 값으로 교체 (devkey 삭제)

webhook:
  api_key: <key>               # ← 3-1 의 key 와 동일
  urls:
    - http://backend:8080/api/livekit/webhook
```

### 3-4. `infra/livekit/egress.yaml` 수정

```yaml
api_key: <key>                 # ← 3-1 값
api_secret: <secret>           # ← 3-1 값
```

---

## 4. 도커 스택 + Caddy 기동

```bash
# caddy 프로필까지 함께 올린다 (도메인 HTTPS 접속용)
docker compose --profile proxy up --build -d
docker compose ps          # 전부 healthy / running 인지 확인
docker compose logs -f caddy   # 인증서 발급 로그 확인 (Ctrl+C 로 빠져나옴)
```

`.env` 의 값만 바꿨을 때 백엔드·caddy 재기동:
```bash
docker compose --profile proxy up -d --force-recreate backend caddy
```

> caddy 로그에 `certificate obtained` 계열 메시지가 뜨면 인증서 발급 성공.
> 실패하면 DNS(§2)와 UFW 80/443(§5)을 먼저 확인한다.

---

## 5. UFW 방화벽

```bash
bash deploy/setup-firewall.sh
```

여는 포트: `22, 80, 443, 7881/tcp, 50000-50060/udp`.
나머지(8080·8081·7880·3306·9000·9001)는 `docker-compose.yml` 에서 `127.0.0.1` 에
묶여 있어 UFW 로 굳이 막지 않아도 외부에서 안 보인다.

> **SSH가 22번이 아니면** 스크립트의 `allow 22/tcp` 를 본인 포트로 바꾸고 실행한다.
> 안 그러면 `ufw enable` 순간 SSH가 끊긴다.

MinIO 콘솔 확인은 SSH 터널로:
```bash
ssh -L 9001:127.0.0.1:9001 ubuntu@<EC2-IP>
# 이후 로컬 브라우저에서 http://localhost:9001
```

---

## 6. 검증

```bash
# 1) 앱이 HTTPS 로 뜨는가
curl -I https://i15d205.p.ssafy.io               # 200/301 + 유효 인증서

# 2) LiveKit 시그널링이 wss(/rtc) 로 열리는가
curl -i https://i15d205.p.ssafy.io/rtc/validate  # LiveKit 응답(426/200 계열)이면 프록시 OK

# 3) 방화벽
sudo ufw status verbose
```

브라우저 최종 확인:
1. `https://i15d205.p.ssafy.io` 접속 → **자물쇠 아이콘** 확인.
2. `회의 참여` → 개발자도구 콘솔에 혼합 콘텐츠 에러가 **없어야** 한다.
3. 다른 기기/네트워크(예: 휴대폰 LTE)에서 같이 들어가 **오디오가 오가는지** 확인
   → 여기서 안 되면 UDP 50000-50060 이 안 열렸거나 `use_external_ip` 미설정(§3, §5).

---

## 7. 자주 겪는 문제

| 증상 | 원인 / 조치 |
| --- | --- |
| 콘솔에 `Mixed Content` / `insecure WebSocket` | `.env` 의 `LIVEKIT_WS_URL` 이 `wss://<도메인>/rtc` 인지 확인 후 `docker compose --profile proxy up -d --force-recreate backend` |
| 방엔 들어가지는데 **소리가 안 남** | 미디어 포트 문제. UFW 에 UDP 50000-50060 열렸는지, `livekit.yaml` `use_external_ip: true` 인지 확인 |
| caddy 인증서 발급 실패 | DNS 가 아직 EC2 IP 를 안 가리킴(§2), 또는 80/443 이 UFW/다른 프로세스에 막힘. 다른 웹서버(nginx 등)가 80 을 점유했는지 확인 |
| `/rtc/validate` 가 404 | `.env` `DOMAIN` 이 실제 접속 도메인과 다름 → Caddyfile 이 그 도메인 블록을 못 만든다 |
| 웹훅이 안 옴(녹음 시작 안 됨) | `livekit.yaml` `webhook.urls` 가 `http://backend:8080/...`(도커 내부명) 인지 확인 |
| DB/MinIO 가 외부에서 열림 | `docker-compose.yml` 포트가 `127.0.0.1:` 로 시작하는지 확인(이 저장소는 이미 적용) |
| mysql/egress 가 자꾸 죽음 | 메모리 부족. 스왑 2GB 추가: `sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile` |

---

## 8. 요약 체크리스트

- [ ] DNS A 레코드 → EC2 IP (`dig +short i15d205.p.ssafy.io`)
- [ ] LiveKit 키 생성 후 `.env` / `livekit.yaml` / `egress.yaml` 세 곳 일치
- [ ] `livekit.yaml` `use_external_ip: true`
- [ ] `.env` `LIVEKIT_WS_URL=wss://i15d205.p.ssafy.io/rtc`, `DOMAIN`, `ACME_EMAIL`
- [ ] `docker compose --profile proxy up --build -d` → 전부 정상, caddy 인증서 발급
- [ ] `bash deploy/setup-firewall.sh`
- [ ] HTTPS 접속 + 자물쇠 + 2인 오디오 통화 확인
