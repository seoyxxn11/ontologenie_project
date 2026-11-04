#!/usr/bin/env bash
# develop 배포 Job 전용. 비밀값은 읽어 출력하지 않으며 설정 실패 시 파일·서비스를 복구한다.
set +x
set -euo pipefail
umask 077

: "${DEPLOY_DIR:?}"
: "${WORKSPACE:?}"
: "${BUILD_NUMBER:?}"
: "${GIT_SHA:?}"
[[ "$BUILD_NUMBER" =~ ^[0-9]+$ && "$GIT_SHA" =~ ^[a-f0-9]+$ ]] || exit 1

HOST_WS=${WORKSPACE/#\/var\/jenkins_home/\/home\/ubuntu\/axcore\/jenkins-data}
transaction="$DEPLOY_DIR/backup/config-${GIT_SHA}-${BUILD_NUMBER}"
promoted=false
finished=false

helper() {
    docker run --rm --network none --memory 128m \
        --user "$(id -u):$(id -g)" \
        -v "$HOST_WS/infra:/source:ro" \
        -v "$DEPLOY_DIR:$DEPLOY_DIR" \
        python:3.11-slim python /source/deploy/configuration.py "$1" \
        --source /source --root "$DEPLOY_DIR" --transaction "$transaction"
}

compose() {
    local project=$1
    shift
    local directory="$DEPLOY_DIR" name=axcore
    if [ "$project" = cdc ]; then
        directory="$DEPLOY_DIR/cdc"
        name=axcore-cdc
    fi
    docker compose --project-name "$name" --project-directory "$directory" \
        --env-file "$directory/.env" -f "$directory/compose.yaml" "$@"
}

container_name() {
    if [ "$1" = cdc:console ]; then printf '%s' kafka-console; else printf '%s' "${1#*:}"; fi
}

check_batch() {
    local cid started state found=false
    cid=$(docker inspect --format '{{.Id}}' batch)
    started=$(docker inspect --format '{{.State.StartedAt}}' batch)
    for _ in $(seq 1 18); do
        state=$(docker inspect --format '{{.Id}} {{.State.StartedAt}} {{.State.Running}} {{.RestartCount}} {{.State.OOMKilled}}' batch)
        [ "$state" = "$cid $started true 0 false" ] || return 1
        if docker logs --since "$started" "$cid" 2>&1 | grep 'Started PlatformApplication' >/dev/null; then
            found=true
            break
        fi
        sleep 5
    done
    [ "$found" = true ] || return 1
    for _ in $(seq 1 12); do
        sleep 5
        state=$(docker inspect --format '{{.Id}} {{.State.StartedAt}} {{.State.Running}} {{.RestartCount}} {{.State.OOMKilled}}' batch)
        [ "$state" = "$cid $started true 0 false" ] || return 1
    done
}

check_services() {
    local entry name status ready
    if grep -qx 'main:batch' "$transaction/services.txt"; then check_batch || return 1; fi
    # batch 안정화 후에도 다른 서비스가 정상인지 함께 검사한다.
    for _ in $(seq 1 24); do
        ready=true
        while IFS= read -r entry; do
            [ -n "$entry" ] || continue
            name=$(container_name "$entry")
            status=$(docker inspect --format '{{.State.Running}} {{.State.OOMKilled}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name") || return 1
            case "$status" in
                'true false healthy') ;;
                'true false none')
                    case "$name" in
                        batch|nginx) ;;
                        alloy)
                            docker exec prometheus wget -T 5 -qO- http://alloy:12345/-/ready >/dev/null 2>&1 || ready=false
                            docker exec prometheus wget -T 5 -qO- http://alloy:12345/-/healthy >/dev/null 2>&1 || ready=false ;;
                        loki) docker exec prometheus wget -T 5 -qO- http://loki:3100/ready >/dev/null 2>&1 || ready=false ;;
                        kafka-console) docker exec prometheus wget -T 5 -qO- http://kafka-console:8080/ >/dev/null 2>&1 || ready=false ;;
                        *) ready=false ;;
                    esac ;;
                *) ready=false ;;
            esac
        done < "$transaction/services.txt"
        [ "$ready" = true ] && return 0
        sleep 5
    done
    return 1
}

restart_services() {
    local entry
    while IFS= read -r entry; do
        [ -n "$entry" ] || continue
        compose "${entry%%:*}" up -d --no-deps --force-recreate "${entry#*:}" || return 1
    done < "$transaction/services.txt"
}

cleanup() {
    local result=$?
    trap - EXIT
    if [ "$promoted" = true ] && [ "$finished" != true ]; then
        echo '설정 반영 실패. 이전 설정과 서비스를 복구합니다.'
        if helper restore && compose main config --quiet && compose cdc config --quiet \
            && restart_services && docker exec nginx nginx -t \
            && docker exec nginx nginx -s reload && check_services; then
            echo '이전 설정·서비스 복구 검사 통과'
        else
            echo '복구 검사 실패. 백업을 유지했습니다. 담당자 수동 확인이 필요합니다.'
        fi
        result=1
    elif [ "$finished" != true ]; then
        helper restore-settings || echo '일반 설정 복구 실패. 담당자 확인이 필요합니다.'
    fi
    # Compose 렌더링 결과에는 비밀값이 포함되므로 성공·실패 모두 즉시 제거한다.
    for project in main cdc; do
        for version in old new; do
            rm -f -- "$transaction/$project-$version.json"
        done
        rm -f -- "$transaction/$project-old.env"
    done
    exit "$result"
}
trap cleanup EXIT

# Credentials 생성 후 첫 Compose 검사도 일반 설정 복구 범위 안에서 수행한다.
compose main config --quiet
compose cdc config --quiet
helper prepare
helper old-env
for project in main cdc; do
    directory="$DEPLOY_DIR"
    name=axcore
    file=compose.yaml
    if [ "$project" = cdc ]; then directory="$DEPLOY_DIR/cdc"; name=axcore-cdc; file=cdc/compose.yaml; fi
    docker compose --project-name "$name" --project-directory "$directory" \
        --env-file "$transaction/$project-old.env" -f "$directory/compose.yaml" \
        config --format json > "$transaction/$project-old.json"
    docker compose --project-name "$name" --project-directory "$directory" \
        --env-file "$directory/.env" -f "$transaction/stage/$file" \
        config --format json > "$transaction/$project-new.json"
done
helper compare

while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    name=$(container_name "$entry")
    [ "$(docker inspect --format '{{.State.Running}}' "$name")" = true ] || {
        echo '변경 대상 중 정지된 서비스가 있어 자동 반영을 중단합니다.'
        exit 1
    }
done < "$transaction/services.txt"

if grep -Eq '^main:(backend|batch)$' "$transaction/services.txt"; then
    bash "$DEPLOY_DIR/scripts/pg-backup.sh"
fi
promoted=true
helper promote
compose main config --quiet
compose cdc config --quiet

if grep -qx 'main:alloy' "$transaction/services.txt"; then
    compose main run --rm --no-deps alloy validate /etc/alloy
fi
if grep -qx 'main:prometheus' "$transaction/services.txt"; then
    compose main run --rm --no-deps --entrypoint /bin/promtool prometheus check config /etc/prometheus/prometheus.yml
fi
if grep -qx 'main:loki' "$transaction/services.txt"; then
    compose main run --rm --no-deps loki -config.file=/etc/loki/loki.yaml -verify-config=true
fi
docker exec nginx nginx -t
restart_services
docker exec nginx nginx -t
docker exec nginx nginx -s reload
check_services
helper finish
finished=true
