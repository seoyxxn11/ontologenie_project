#!/usr/bin/env bash
# S15P31C106-396: 원천 허용 목록(395)을 적용한 원천에서 금지·제한·일반 값이 어디까지 나가는지 확인한다.
#
# 일회용 PostgreSQL(postgres:18.6, wal_level=logical)을 띄워 ERP·MES 시험 데이터(ai/data/data-v1.0)를 싣고
# infra/source-db/sql/setup_roles.sql을 적용한 뒤 두 가지를 본다.
#   1. 원천 SQL: platform_ro·platform_ai·platform_cdc가 컬럼마다 읽을 수 있는지(README "허용 목록" 표와 같아야 한다)
#   2. CDC: 시험 값으로 행을 바꾸고 pgoutput 슬롯(publication axcore_cdc)에서 변경을 꺼내, 금지 값은 실리지 않고
#      제한·일반 값은 실리는지. Debezium이 받는 것과 같은 흐름이다(Kafka 토픽 내용은 이 흐름을 그대로 옮긴 것).
#
# 네트워크에 붙지 않는 로컬 전용 시험이다. 운영 원천에는 쓰지 않는다. 필요: docker, bash.
#   bash infra/tests/source_exposure_check.sh
# 결과를 표로 찍고, 기대와 하나라도 다르면 종료 코드 1이다.
set -uo pipefail
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NAME="axcore-exposure-$$"
IMAGE="postgres:18.6"
FAILS=0

to_docker_path() {
    # Git Bash에서는 C:/... 경로를 넘긴다
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi
}

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" \
    -e POSTGRES_USER=source_admin -e POSTGRES_PASSWORD=admin-396 -e POSTGRES_DB=erp \
    -v "$(to_docker_path "$ROOT/infra/source-db/sql"):/sql:ro" \
    -v "$(to_docker_path "$ROOT/ai/data/data-v1.0"):/data:ro" \
    "$IMAGE" -c wal_level=logical >/dev/null || { echo "컨테이너를 띄우지 못했습니다"; exit 2; }

for _ in $(seq 1 60); do
    docker exec "$NAME" pg_isready -U source_admin -d erp >/dev/null 2>&1 && break
    sleep 1
done
sleep 2

admin() { docker exec -i -w /data "$NAME" psql -X -q -v ON_ERROR_STOP=1 -U source_admin -d "$1" "${@:2}"; }

docker exec "$NAME" psql -X -q -U source_admin -d erp -c "create database mes" >/dev/null
for db in erp mes; do
    admin "$db" -f "load_${db}.psql" >/dev/null || { echo "$db 적재 실패"; exit 2; }
    docker exec -i \
        -e PLATFORM_RO_PASSWORD=ro-396 -e PLATFORM_AI_PASSWORD=ai-396 -e PLATFORM_CDC_PASSWORD=cdc-396 \
        -e SOURCE_DB="$db" \
        "$NAME" psql -X -q -v ON_ERROR_STOP=1 -U source_admin -d "$db" -f /sql/setup_roles.sql >/dev/null 2>&1 \
        || { echo "$db 허용 목록 적용 실패"; exit 2; }
done

password() { case "$1" in platform_ro) echo ro-396 ;; platform_ai) echo ai-396 ;; platform_cdc) echo cdc-396 ;; esac; }

# 계정이 그 컬럼을 읽을 수 있으면 yes, 권한이 없으면 no
can_read() {
    local account="$1" db="$2" table="$3" column="$4" out
    out="$(docker exec -e PGPASSWORD="$(password "$account")" "$NAME" \
        psql -X -qtA -h 127.0.0.1 -U "$account" -d "$db" -c "select $column from $table limit 1" 2>&1)"
    if echo "$out" | grep -qi "permission denied\|권한 없음"; then echo no; else echo yes; fi
}

echo "## 1. 원천 SQL (계정별 컬럼 읽기)"
echo
echo "| 등급 | 컬럼 | platform_ro | platform_ai | platform_cdc | 결과 |"
echo "|---|---|---|---|---|---|"
# 등급, DB, 표, 컬럼, 기대 ro, ai, cdc (README 허용 목록 표)
while IFS=' ' read -r grade db table column ro ai cdc; do
    [ -z "$grade" ] && continue
    got_ro="$(can_read platform_ro "$db" "$table" "$column")"
    got_ai="$(can_read platform_ai "$db" "$table" "$column")"
    got_cdc="$(can_read platform_cdc "$db" "$table" "$column")"
    result=OK
    if [ "$got_ro $got_ai $got_cdc" != "$ro $ai $cdc" ]; then result="**다름** (기대 $ro $ai $cdc)"; FAILS=$((FAILS + 1)); fi
    echo "| $grade | $table.$column | $got_ro | $got_ai | $got_cdc | $result |"
done <<'EOF'
금지 erp erp.attendance emp_no no no no
금지 erp erp.fixed_asset asset_name no no no
금지 erp erp.change_log row_key no no no
금지 mes mes.change_log row_key no no no
금지 erp erp.employee emp_name no no no
금지 erp erp.employee position no no no
금지 erp erp.employee hire_date no no no
금지 erp erp.employee employment_type no no no
금지 erp erp.employee status no no no
금지 mes mes.worker name no no no
제한 mes mes.maintenance_order technician yes no yes
제한 erp erp.vendor contact_name yes no yes
제한 mes mes.worker skill_level yes no yes
제외 erp erp.sales_invoice invoice_no no no no
제외 erp erp.journal_entry entry_no no no no
일반 erp erp.employee emp_no yes yes yes
일반 erp erp.employee dept_code yes yes yes
일반 mes mes.worker id yes yes yes
일반 erp erp.vendor vendor_name yes yes yes
일반 mes mes.maintenance_order mo_no yes yes yes
일반 erp erp.item item_name yes yes yes
EOF

echo
echo "## 2. CDC (publication axcore_cdc, pgoutput)"
echo
echo "| 등급 | 바꾼 컬럼 | 시험 값 | 변경 흐름에 있음 | 결과 |"
echo "|---|---|---|---|---|"
# 등급, DB, 바꾸는 SQL, 시험 값, 기대(yes = 실림)
cdc_cases() {
    cat <<'EOF'
금지|erp|erp.employee.emp_name|update erp.employee set emp_name = 'FORBID396EMP' where emp_no = (select min(emp_no) from erp.employee)|FORBID396EMP|no
금지|erp|erp.employee.position|update erp.employee set position = 'FORBID396POS' where emp_no = (select min(emp_no) from erp.employee)|FORBID396POS|no
금지|erp|erp.attendance.work_type|update erp.attendance set work_type = 'FBD396ATT' where (emp_no, work_date) = (select emp_no, work_date from erp.attendance order by 1, 2 limit 1)|FBD396ATT|no
금지|erp|erp.change_log.row_key|update erp.change_log set row_key = 'FORBID396LOG' where change_id = (select min(change_id) from erp.change_log)|FORBID396LOG|no
금지|mes|mes.worker.name|update mes.worker set name = 'FORBID396WRK' where id = (select min(id) from mes.worker)|FORBID396WRK|no
제한|erp|erp.vendor.contact_name|update erp.vendor set contact_name = 'RESTRICT396VND' where vendor_code = (select min(vendor_code) from erp.vendor)|RESTRICT396VND|yes
제한|mes|mes.maintenance_order.technician|update mes.maintenance_order set technician = 'RESTRICT396TEC' where mo_no = (select min(mo_no) from mes.maintenance_order)|RESTRICT396TEC|yes
일반|erp|erp.employee.dept_code|update erp.employee set dept_code = 'NORMAL396' where emp_no = (select max(emp_no) from erp.employee)|NORMAL396|yes
일반|erp|erp.item.item_name|update erp.item set item_name = 'NORMAL396ITEM' where item_code = (select min(item_code) from erp.item)|NORMAL396ITEM|yes
일반|mes|mes.maintenance_order.note|update mes.maintenance_order set note = 'NORMAL396NOTE' where mo_no = (select max(mo_no) from mes.maintenance_order)|NORMAL396NOTE|yes
EOF
}

for db in erp mes; do
    admin "$db" -tA -c "select pg_create_logical_replication_slot('exposure396_$db', 'pgoutput')" >/dev/null
done
# dept_code 'NORMAL396'은 부서 FK가 있으면 실패하므로, 실패한 변경은 결과에 따로 적는다
while IFS='|' read -r grade db column sql marker expected; do
    # admin은 docker exec -i라 표준 입력을 끊지 않으면 반복의 남은 줄을 먹는다
    if ! err="$(admin "$db" -c "$sql" </dev/null 2>&1)"; then
        echo "| $grade | $column | $marker | (변경 실패: $(echo "$err" | head -1 | cut -c1-80)) | **확인 못함** |"
        FAILS=$((FAILS + 1))
        continue
    fi
    seen="$(admin "$db" -tA </dev/null -c "select case when count(*) filter (where position('$marker' in encode(data, 'escape')) > 0) > 0 then 'yes' else 'no' end
        from pg_logical_slot_peek_binary_changes('exposure396_$db', null, null, 'proto_version', '1', 'publication_names', 'axcore_cdc')")"
    result=OK
    if [ "$seen" != "$expected" ]; then result="**다름** (기대 $expected)"; FAILS=$((FAILS + 1)); fi
    echo "| $grade | $column | $marker | $seen | $result |"
done < <(cdc_cases)

echo
echo "## 3. publication 컬럼 목록"
echo
for db in erp mes; do
    admin "$db" -tA -c "select '- ' || schemaname || '.' || tablename || coalesce(' (' || array_to_string(attnames, ', ') || ')', '')
        from pg_publication_tables where pubname = 'axcore_cdc'
          and tablename in ('employee', 'worker', 'vendor', 'maintenance_order', 'attendance', 'fixed_asset', 'change_log')
        order by 1"
done
echo
echo "다른 결과: $FAILS"
[ "$FAILS" -eq 0 ]
