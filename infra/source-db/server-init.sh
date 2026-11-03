#!/usr/bin/env bash
# 원천 서버(Amazon Linux 2023) 기본 세팅(S15P31C106-59): 시간대, 스왑, Docker, compose 플러그인. 다시 실행해도 된다.
# 사용: ~/source-db/server-init.sh  (ec2-user, sudo 필요)
set -euo pipefail

COMPOSE_VERSION=v2.40.3
SWAP_SIZE=2G

sudo timedatectl set-timezone Asia/Seoul

# 메모리 3.8GiB에 스왑이 없어 PostgreSQL이 메모리를 다 쓰면 바로 죽는다. 2GiB 스왑을 둔다.
if ! swapon --show | grep -q /swapfile; then
    sudo fallocate -l "$SWAP_SIZE" /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile > /dev/null
    sudo swapon /swapfile
    grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab > /dev/null
fi

if ! command -v docker > /dev/null; then
    sudo dnf install -y docker
fi
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"

# Amazon Linux 저장소에는 compose 플러그인이 없어 Docker 공식 릴리스를 받는다.
plugin=/usr/local/lib/docker/cli-plugins/docker-compose
if [ ! -x "$plugin" ] || ! "$plugin" version | grep -q "${COMPOSE_VERSION#v}"; then
    sudo mkdir -p "$(dirname "$plugin")"
    sudo curl -fsSL -o "$plugin" \
        "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-$(uname -m)"
    sudo chmod 755 "$plugin"
fi

timedatectl | grep 'Time zone'
swapon --show
sudo docker version --format 'docker {{.Server.Version}}'
sudo docker compose version
echo "docker 그룹은 다시 로그인해야 적용된다"
