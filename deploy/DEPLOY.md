# EC2 배포 가이드 — nginx + certbot(HTTPS) + UFW

보안그룹을 쓸 수 없는(빌린) EC2에 이 회의 스택을 HTTPS로 올리는 순서다.
도커 컨테이너는 그대로 두고, **호스트에 nginx 를 하나 더 세워** TLS 를 종료한다.

> 표기: `example.com` = 앱 도메인, `livekit.example.com` = LiveKit 시그널링 도메인.
> `ubuntu@<EC2-IP>` = SSH 접속 계정/주소. 본인 값으로 바꿔 읽는다.

---

## 0. 먼저 — 지금 설정 점검 결과

클론한 저장소를 그대로 EC2에 올리면 **HTTPS에서 회의가 안 된다.** 이유와 조치:

| # | 문제 | 왜 | 조치 |
| --- | --- | --- | --- |
| 1 | **`ws://` 혼합 콘텐츠 차단** | HTTPS 페이지는 `ws://`(평문) 연결을 브라우저가 막는다. `.env` 기본값이 `LIVEKIT_WS_URL=ws://localhost:7880` 이다 | LiveKit 을 nginx 로 감싸 `wss://` 로 바꾼다 (§4, §5) |
| 2 | **Docker 가 UFW 를 우회** | 도커는 iptables 를 직접 건드려, `3306:3306` 같은 매핑을 UFW 로 막아도 외부에서 접속된다 | 내부 포트를 `127.0.0.1` 에 바인딩(이미 `docker-compose.yml` 수정됨) + UFW (§6) |
| 3 | **LiveKit `use_external_ip: false`** | EC2 는 NAT 뒤라 사설IP만 보여, 브라우저에 잘못된 미디어 주소를 알려준다 | `livekit.yaml` 에서 `true` 로 (§3) |
| 4 | **데모 키 그대로** | `devkey` / `secretsecret...` 는 공개된 값이라 누구나 방에 입장·녹음 가능 | 실제 키로 교체 (§3) |
| 5 | **미디어 UDP/TCP 포트** | 시그널링만 nginx 로 가고, 실제 오디오는 UDP 50000-50060 / TCP 7881 로 직접 흐른다 | 이 포트만 UFW 로 연다 (§6) |

이 문서대로 하면 1~5가 모두 처리된다.

---

## 1. 최종 아키텍처

```
                    인터넷
                      │
        ┌─────────────┼───────────────────────────┐
        │ 443/tcp     │ 443/tcp        7881/tcp    │ 50000-50060/udp
        ▼             ▼                ▼            ▼
  example.com   livekit.example.com   (LiveKit 미디어 — nginx 안 거침)
        │             │                └────────────┬───────────┐
   ┌────▼─────────────▼────┐  호스트 nginx(TLS)      │           │
   │  nginx (certbot 인증서) │                        │           │
   └────┬─────────────┬────┘                        │           │
        │ 127.0.0.1   │ 127.0.0.1                    │           │
        ▼ :8081       ▼ :7880 (wss→ws)               ▼           ▼
   ┌─────────┐   ┌──────────┐  docker 내부      ┌──────────────────┐
   │ frontend│──▶│ backend  │──────────────────▶│ LiveKit(OpenVidu3)│
   │ (nginx) │   │ (Spring) │◀── webhook ────────│  + Egress        │
   └─────────┘   └────┬─────┘                    └────────┬─────────┘
                      │                                   │ .ogg
                 MySQL·Redis                          MinIO(내부)
```

- **호스트 nginx** 만 공인망에 열려 있고(443/80), 나머지는 전부 `127.0.0.1` 뒤에 숨는다.
- **미디어(UDP/TCP)** 만 nginx 를 우회해 브라우저와 EC2 가 직접 주고받는다 → UFW 로 연다.

---

## 2. 사전 준비 (DNS)

도메인 A 레코드가 EC2 공인 IP를 가리키는지 확인한다.

```bash
dig +short example.com
dig +short livekit.example.com   # 서브도메인 방식일 때
```

둘 다 EC2 공인 IP가 나와야 한다. **서브도메인을 못 만들면** 단일 도메인 방식(§5-B)으로 간다.

> 필요한 패키지: `sudo apt update && sudo apt install -y nginx docker.io docker-compose-plugin`
> (도커/컴포즈가 이미 있으면 nginx 만 설치)

---

## 3. 프로덕션 값 채우기 (도커 올리기 전에)

### 3-1. 실제 LiveKit 키 생성

```bash
# API Key(짧아도 됨)와 Secret(길게) 각각 생성
openssl rand -hex 16      # → LIVEKIT_API_KEY 로 쓸 값
openssl rand -base64 32   # → LIVEKIT_API_SECRET 로 쓸 값
```

이 두 값을 **아래 세 곳에 똑같이** 넣어야 한다:
`~/.../.env`, `infra/livekit/livekit.yaml`, `infra/livekit/egress.yaml`.

### 3-2. `.env` 작성

```bash
cp .env.example .env
```

`.env` 를 아래처럼 수정:

```dotenv
# 브라우저가 붙는 주소 — 반드시 wss:// (서브도메인 방식)
LIVEKIT_WS_URL=wss://livekit.example.com
#  ↑ 단일 도메인 방식이면:  LIVEKIT_WS_URL=wss://example.com

LIVEKIT_API_KEY=<3-1 에서 만든 key>
LIVEKIT_API_SECRET=<3-1 에서 만든 secret>

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
  use_external_ip: true        # ← false 에서 변경 (EC2 필수)

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

### 3-5. 도커 스택 기동

```bash
docker compose up --build -d
docker compose ps      # 전부 healthy / running 인지 확인
```

이 시점엔 아직 HTTPS가 아니다. `curl http://127.0.0.1:8081` 이 HTML을 뱉으면 컨테이너는 정상.

---

## 4. nginx 설정 심기 (서브도메인 방식 = 권장)

```bash
# 저장소가 예: /home/ubuntu/test 에 있다고 가정
cd /home/ubuntu/test

# 1) 설정 복사
sudo cp deploy/nginx/app.conf     /etc/nginx/sites-available/app.conf
sudo cp deploy/nginx/livekit.conf /etc/nginx/sites-available/livekit.conf

# 2) 도메인 치환 (example.com → 실제 도메인)
sudo sed -i 's/example\.com/실제도메인/g'           /etc/nginx/sites-available/app.conf
sudo sed -i 's/livekit\.example\.com/실제livekit도메인/g' /etc/nginx/sites-available/livekit.conf

# 3) 활성화
sudo ln -sf /etc/nginx/sites-available/app.conf     /etc/nginx/sites-enabled/app.conf
sudo ln -sf /etc/nginx/sites-available/livekit.conf /etc/nginx/sites-enabled/livekit.conf
sudo rm -f /etc/nginx/sites-enabled/default    # 기본 페이지 제거

# 4) 문법 검사 후 재적재
sudo nginx -t && sudo systemctl reload nginx
```

> ⚠️ `sed` 실행 순서 주의: `app.conf` 는 `example.com` 만 있으니 첫 sed로 충분하고,
> `livekit.conf` 는 `livekit.example.com` 이므로 두 번째 sed로 정확히 바꾼다.

---

## 5. HTTPS 발급 (certbot)

### 5-A. 서브도메인 방식

```bash
sudo apt install -y certbot python3-certbot-nginx

sudo certbot --nginx -d example.com -d livekit.example.com \
  --redirect --agree-tos -m 본인이메일@example.com --no-eff-email
```

certbot 이 두 conf 파일에 **443/SSL 블록과 80→443 리다이렉트를 자동으로 추가**한다.
끝나면 `sudo nginx -t && sudo systemctl reload nginx`.

자동 갱신은 systemd 타이머로 이미 걸린다. 확인:
```bash
sudo systemctl status certbot.timer
sudo certbot renew --dry-run
```

### 5-B. 단일 도메인 방식 (서브도메인을 못 만들 때)

§4 대신 이 파일 **하나만** 심는다:

```bash
sudo cp deploy/nginx/app-single-domain.conf /etc/nginx/sites-available/app.conf
sudo sed -i 's/example\.com/실제도메인/g' /etc/nginx/sites-available/app.conf
sudo ln -sf /etc/nginx/sites-available/app.conf /etc/nginx/sites-enabled/app.conf
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

sudo certbot --nginx -d example.com --redirect --agree-tos -m 본인이메일 --no-eff-email
```

그리고 `.env` 는 `LIVEKIT_WS_URL=wss://example.com` (§3-2 주석 참고) 로 두고
`docker compose up -d --force-recreate backend` 로 백엔드만 재기동.

---

## 6. UFW 방화벽

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

## 7. 검증

```bash
# 1) 앱이 HTTPS 로 뜨는가
curl -I https://example.com            # 200/301, 인증서 유효

# 2) LiveKit 시그널링이 wss 로 열리는가 (426 Upgrade Required = 정상)
curl -I https://livekit.example.com    # 서브도메인 방식
#   단일 도메인: curl -i https://example.com/rtc/validate

# 3) 방화벽
sudo ufw status verbose
```

브라우저 최종 확인:
1. `https://example.com` 접속 → **자물쇠 아이콘** 확인.
2. `회의 참여` → 개발자도구 콘솔에 혼합 콘텐츠 에러가 **없어야** 한다.
3. 다른 기기/네트워크(예: 휴대폰 LTE)에서 같이 들어가 **오디오가 오가는지** 확인
   → 여기서 안 되면 UDP 50000-50060 이 안 열린 것(§6, §8).

---

## 8. 자주 겪는 문제

| 증상 | 원인 / 조치 |
| --- | --- |
| 콘솔에 `Mixed Content` / `insecure WebSocket` | `.env` 가 아직 `ws://`. `wss://` 로 고치고 `docker compose up -d --force-recreate backend` |
| 방엔 들어가지는데 **소리가 안 남** | 미디어 포트 문제. UFW 에 UDP 50000-50060 열렸는지, `livekit.yaml` `use_external_ip: true` 인지 확인 |
| `nginx -t` 실패 | 도메인 치환 누락 또는 `sites-enabled/default` 미삭제 |
| certbot 실패(챌린지) | DNS 가 아직 EC2 IP 를 안 가리킴(§2) 또는 80 포트가 UFW/다른 프로세스에 막힘 |
| 웹훅이 안 옴(녹음 시작 안 됨) | `livekit.yaml` `webhook.urls` 가 `http://backend:8080/...`(도커 내부명) 인지 확인 |
| DB/MinIO 가 외부에서 열림 | `docker-compose.yml` 포트가 `127.0.0.1:` 로 시작하는지 확인(이 저장소는 이미 적용) |

---

## 9. 요약 체크리스트

- [ ] DNS A 레코드 → EC2 IP (앱, 필요시 livekit 서브도메인)
- [ ] LiveKit 키 생성 후 `.env` / `livekit.yaml` / `egress.yaml` 세 곳 일치
- [ ] `livekit.yaml` `use_external_ip: true`
- [ ] `.env` `LIVEKIT_WS_URL=wss://...`
- [ ] `docker compose up --build -d` → 전부 정상
- [ ] nginx conf 복사·도메인 치환·활성화·`nginx -t`
- [ ] `certbot --nginx` 로 인증서 발급 + 리다이렉트
- [ ] `bash deploy/setup-firewall.sh`
- [ ] HTTPS 접속 + 자물쇠 + 2인 오디오 통화 확인
