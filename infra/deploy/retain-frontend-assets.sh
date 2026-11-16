#!/usr/bin/env bash
# 현재·다음 프론트 이미지의 공개 정적 파일만 보관한다. 이미지 실행·서비스 전환은 하지 않는다.
set -euo pipefail
umask 022

: "${DEPLOY_DIR:?}"
: "${WORKSPACE:?}"
: "${NEXT_FRONTEND_IMAGE:?}"
days=${FRONTEND_ASSET_RETENTION_DAYS:-7}
[[ "$NEXT_FRONTEND_IMAGE" =~ ^axcore-frontend:[a-f0-9]{7,40}$ && "$days" =~ ^[1-9][0-9]*$ ]] || exit 1
[[ "$DEPLOY_DIR" = /* && "$DEPLOY_DIR" != / ]] || exit 1
archive="$DEPLOY_DIR/frontend-assets"
[[ ! -L "$archive" && -d "$archive" ]] || { echo '보관 디렉터리를 먼저 준비해야 합니다.' >&2; exit 1; }
HOST_WS=${WORKSPACE/#\/var\/jenkins_home/\/home\/ubuntu\/axcore\/jenkins-data}
snapshot=$(mktemp -d "$archive/.incoming.XXXXXXXX")
next_container=''
cleanup() {
    if [[ "$next_container" =~ ^[a-f0-9]+$ ]]; then docker rm "$next_container" >/dev/null; fi
    case "$snapshot" in "$archive"/.incoming.*) rm -rf -- "$snapshot" ;; esac
}
trap cleanup EXIT

mkdir "$snapshot/current" "$snapshot/next"
current=$(docker ps -a --filter 'name=^/frontend$' --format '{{.ID}}')
args=(--root /archive --days "$days")
if [ -n "$current" ]; then
    [[ "$current" =~ ^[a-f0-9]+$ ]] || exit 1
    current_image=$(docker inspect --format '{{.Image}}' "$current")
    docker cp "$current:/usr/share/nginx/html/assets/." "$snapshot/current"
    args+=(--snapshot "$current_image" "/archive/${snapshot##*/}/current")
fi
next_image=$(docker image inspect --format '{{.Id}}' "$NEXT_FRONTEND_IMAGE")
next_container=$(docker create --network none "$next_image")
[[ "$next_container" =~ ^[a-f0-9]+$ ]] || exit 1
docker cp "$next_container:/usr/share/nginx/html/assets/." "$snapshot/next"
args+=(--snapshot "$next_image" "/archive/${snapshot##*/}/next")

docker run --rm --network none --memory 128m \
    --user "$(id -u):$(id -g)" -e PYTHONDONTWRITEBYTECODE=1 \
    --mount "type=bind,src=$HOST_WS/infra/deploy,dst=/work,readonly" \
    --mount "type=bind,src=$archive,dst=/archive" \
    python:3.11-slim python /work/frontend_assets.py "${args[@]}"
