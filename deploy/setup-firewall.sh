#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────
# EC2 UFW 방화벽 설정 — 보안그룹을 쓸 수 없는(빌린) 인스턴스용.
#
# ⚠️ 매우 중요: Docker 는 iptables 를 직접 건드려 UFW 를 "우회"한다.
#    docker-compose 에서 `- "3306:3306"` 처럼 0.0.0.0 에 바인딩하면
#    UFW 로 3306 을 막아도 외부에서 접속된다.
#    → 이 저장소의 docker-compose.yml 은 내부 포트를 127.0.0.1 에
#      바인딩해 이 문제를 원천 차단해 두었다. 반드시 그 상태로 배포한다.
#
# 실행: bash deploy/setup-firewall.sh
# ────────────────────────────────────────────────────────────────
set -euo pipefail

echo "[1/4] 기본 정책: 들어오는 건 차단, 나가는 건 허용"
sudo ufw default deny incoming
sudo ufw default allow outgoing

echo "[2/4] SSH 허용 (가장 먼저 — 잠기지 않도록)"
# SSH 포트를 22 가 아닌 것으로 바꿨다면 아래 숫자를 그 포트로 수정한다.
sudo ufw allow 22/tcp        comment 'SSH'

echo "[3/4] HTTP/HTTPS 허용 (caddy: ACME 인증서 발급 + TLS)"
sudo ufw allow 80/tcp        comment 'HTTP (ACME challenge, redirect)'
sudo ufw allow 443/tcp       comment 'HTTPS (caddy)'

echo "[4/4] LiveKit WebRTC 미디어 포트 (브라우저 ↔ EC2 직접 통신)"
sudo ufw allow 7881/tcp              comment 'LiveKit ICE/TCP fallback'
sudo ufw allow 50000:50060/udp      comment 'LiveKit RTC media'

echo "방화벽 활성화"
sudo ufw --force enable
sudo ufw status verbose

cat <<'EOF'

────────────────────────────────────────────────────────────────
열린 포트 요약:
  22/tcp                SSH
  80/tcp                HTTP  (caddy ACME / 443 리다이렉트)
  443/tcp               HTTPS (caddy: 앱 + wss 시그널링)
  7881/tcp              LiveKit ICE/TCP 폴백
  50000-50060/udp       LiveKit 미디어

일부러 안 연 포트(caddy/도커 내부에서만 접근):
  8080(백엔드) 8081(프론트) 7880(LiveKit 시그널) 3306(MySQL) 9000/9001(MinIO)
  → docker-compose.yml 에서 127.0.0.1 에 바인딩되어 외부 노출되지 않는다.

MinIO 콘솔을 보려면 SSH 터널을 쓴다:
  ssh -L 9001:127.0.0.1:9001 ubuntu@<EC2-IP>   → 브라우저로 http://localhost:9001
────────────────────────────────────────────────────────────────
EOF
