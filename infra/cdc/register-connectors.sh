#!/usr/bin/env bash
# 원천 CDC 커넥터(Debezium PostgreSQL)를 Kafka Connect에 등록하거나 설정을 바꾼다(S15P31C106-267). 다시 실행해도 된다.
# 사용: ~/axcore/cdc/register-connectors.sh            (connectors/ 안의 모든 커넥터)
#       ~/axcore/cdc/register-connectors.sh cdc-erp    (하나만)
# Connect REST(8083)는 호스트에 열지 않으므로 kafka-connect 컨테이너 안의 curl로 호출한다.
# 설정 파일에는 비밀번호 대신 ${env:...} 참조만 있다. 값은 kafka-connect 컨테이너 환경변수(~/axcore/cdc/.env)에서 읽는다.
set -euo pipefail
cd "$(dirname "$0")"

rest() { docker exec -i kafka-connect curl -sS -H 'Content-Type: application/json' "$@"; }

if [ $# -gt 0 ]; then names=("$@"); else names=(); for f in connectors/*.json; do names+=("$(basename "$f" .json)"); done; fi

for name in "${names[@]}"; do
    file="connectors/$name.json"
    [ -f "$file" ] || { echo "$file이 없다" >&2; exit 1; }

    # 1. 설정 검증. 원천 접속·publication·권한 오류는 여기서 걸린다.
    errors="$(rest -X PUT --data @- \
        localhost:8083/connector-plugins/io.debezium.connector.postgresql.PostgresConnector/config/validate < "$file" |
        grep -o '"error_count":[0-9]*' | cut -d: -f2)"
    [ "$errors" = 0 ] || { echo "$name: 설정 검증 오류 ${errors:-?}개" >&2; exit 1; }

    # 2. 등록 또는 설정 교체(PUT은 없으면 만들고 있으면 바꾼다)
    rest -o /dev/null -w "$name: HTTP %{http_code}\n" -X PUT --data @- "localhost:8083/connectors/$name/config" < "$file"

    # 3. RUNNING 확인
    state=""
    for _ in $(seq 1 30); do
        status="$(rest "localhost:8083/connectors/$name/status")"
        state="$(echo "$status" | grep -o '"state":"[A-Z]*"' | cut -d'"' -f4 | sort -u | tr '\n' ' ')"
        # 커넥터와 태스크가 모두 RUNNING이어야 한다(태스크가 아직 없으면 기다린다)
        [ "$state" = "RUNNING " ] && echo "$status" | grep -q '"tasks":\[{' && break
        sleep 2
    done
    echo "$name: $state"
    [ "$state" = "RUNNING " ] || { echo "$status" >&2; exit 1; }
done
