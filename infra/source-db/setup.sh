#!/usr/bin/env bash
# 원천 서버 한 대의 PostgreSQL을 세팅한다(S15P31C106-60~63). 다시 실행해도 된다.
# 사용: ~/source-db/setup.sh erp   (MES 서버는 mes)
# 먼저 같은 폴더에 data/(ai/data/data-v1.0의 load_<db>.psql, verify_counts.sql, <db>/)를 복사해 둔다.
set -euo pipefail

db="${1:?erp 또는 mes를 지정한다}"
case "$db" in erp | mes) ;; *) echo "erp 또는 mes만 된다" >&2; exit 1 ;; esac
cd "$(dirname "$0")"

# 1. 비밀값. 서버 .env에만 두고 화면에 내보내지 않는다.
gen() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32; }
if [ ! -f .env ]; then
    (
        umask 077
        {
            echo "SOURCE_DB=$db"
            echo "POSTGRES_USER=source_admin"
            echo "POSTGRES_PASSWORD=$(gen)"
        } > .env
    )
    echo ".env 생성"
fi
grep -q "^SOURCE_DB=$db$" .env || { echo ".env의 SOURCE_DB가 $db가 아니다" >&2; exit 1; }
# 계정 비밀번호는 없는 것만 채운다. 나중에 추가한 계정(platform_ai)도 다시 실행하면 생긴다.
for key in PLATFORM_RO_PASSWORD PLATFORM_AI_PASSWORD PLATFORM_CDC_PASSWORD; do
    grep -q "^$key=" .env || { echo "$key=$(gen)" >> .env; echo "$key 생성"; }
done

# 2. SSL 인증서(자체 서명). 컨테이너의 postgres 사용자(uid 999)만 키를 읽는다.
if [ ! -f certs/server.key ]; then
    mkdir -p certs
    openssl req -new -x509 -days 825 -nodes -subj "/CN=${db}-source-db" \
        -keyout certs/server.key -out certs/server.crt 2> /dev/null
    sudo chown 999:999 certs/server.key certs/server.crt
    sudo chmod 600 certs/server.key
    sudo chmod 644 certs/server.crt
    chmod 755 certs
    echo "인증서 생성"
fi

# 3. 기동
docker compose up -d
for _ in $(seq 1 30); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' source-db)" = healthy ] && break
    sleep 2
done
[ "$(docker inspect -f '{{.State.Health.Status}}' source-db)" = healthy ] || { echo "source-db가 healthy가 아니다" >&2; exit 1; }

set -a
. ./.env
set +a
# 관리자 계정으로 컨테이너 안에서 psql을 실행한다. "--" 앞은 docker compose exec 옵션, 뒤는 psql 인자다.
psql_admin() {
    local opts=()
    while [ "$1" != "--" ]; do opts+=("$1"); shift; done
    shift
    docker compose exec -T "${opts[@]}" source-db psql -X -q -U "$POSTGRES_USER" -d "$db" -v ON_ERROR_STOP=1 "$@"
}

# 4. 예시 데이터 적재(표가 없을 때만). 값은 고치지 않는다(ai/data/data-v1.0/README.md).
if [ "$(psql_admin -- -tA -c "select count(*) from pg_tables where schemaname = '$db'")" = 0 ]; then
    psql_admin -w /data -- -f "load_${db}.psql"
    echo "데이터 적재"
fi

# 5. 행 수 확인. verify_counts.sql은 ERP·MES 두 DB를 함께 세므로 이 DB의 표만 골라 비교한다.
tables=0
mismatches=0
while read -r table expected; do
    tables=$((tables + 1))
    # docker compose exec가 반복문의 입력을 먹지 않게 표준 입력을 끊는다.
    actual="$(psql_admin -- -tA -c "select count(*) from $table" < /dev/null)"
    if [ "$actual" != "$expected" ]; then
        echo "행 수 다름: $table $actual (기대 $expected)"
        mismatches=$((mismatches + 1))
    fi
done < <(grep -oE "'${db}\.[a-z_]+' as table_name, count\(\*\) as actual, [0-9]+" data/verify_counts.sql |
    sed -E "s/'([a-z_.]+)' .* ([0-9]+)$/\1 \2/")
echo "행 수 확인: 표 ${tables}개 중 다름 ${mismatches}개"
[ "$mismatches" = 0 ] || exit 1

# 6. 계정·publication. 비밀번호는 명령 인자가 아니라 환경변수 이름으로만 넘긴다.
psql_admin -e PLATFORM_RO_PASSWORD -e PLATFORM_AI_PASSWORD -e PLATFORM_CDC_PASSWORD -e SOURCE_DB -- -f /sql/setup_roles.sql
echo "계정·publication 완료"
