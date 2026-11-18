#!/usr/bin/env bash
# 플랫폼 PostgreSQL 자동 백업
# - 매일 새벽 cron 으로 실행. pg_dump 로 DB 전체를 파일 하나로 뜬다.
# - 중복 실행을 직렬화하고, 검증한 dump만 최종 파일로 공개한다. 성공 후 7일 지난 dump를 지운다.
# - 덤프에는 업무 데이터 전체가 들어 있으므로 폴더는 700, 파일은 600으로 둔다(S15P31C106-397). 서버의 다른 계정·컨테이너가 읽지 못하게 한다.
# - 비밀번호 불필요: 컨테이너 안에서 실행하므로 로컬 접속으로 인증된다.
set -euo pipefail
umask 077

cd /home/ubuntu/axcore
BACKUP_DIR="$PWD/backup"
KEEP_DAYS=7
PARTIAL=''
ACTIVE_PID=''

mkdir -p "$BACKUP_DIR"
[ -O "$BACKUP_DIR" ] && chmod 700 "$BACKUP_DIR"
exec 9>"$BACKUP_DIR/.pg-backup.lock"
flock -w 120 9 || { echo 'backup failed: lock wait exceeded 120 seconds' >&2; exit 1; }

cleanup() {
    status=$?
    trap - EXIT
    if [ -n "$ACTIVE_PID" ]; then
        kill -TERM "$ACTIVE_PID" 2>/dev/null || true
        wait "$ACTIVE_PID" 2>/dev/null || true
    fi
    [ -z "$PARTIAL" ] || rm -f -- "$PARTIAL"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_backup_command() {
    timeout -k 30s "$@" <&0 &
    ACTIVE_PID=$!
    if wait "$ACTIVE_PID"; then result=0; else result=$?; fi
    ACTIVE_PID=''
    return "$result"
}

# 비밀값이 포함된 dotenv를 셸 코드로 실행하지 않는다.
# DB 컨테이너에 이미 전달된 이름만 읽으며 비밀번호는 꺼내지 않는다.
POSTGRES_DB=$(timeout -k 5s 15s docker exec postgres printenv POSTGRES_DB)
POSTGRES_USER=$(timeout -k 5s 15s docker exec postgres printenv POSTGRES_USER)
[[ "$POSTGRES_DB" =~ ^[a-zA-Z0-9_]+$ ]] || { echo 'DB 이름 형식 오류'; exit 1; }
[[ "$POSTGRES_USER" =~ ^[a-zA-Z0-9_]+$ ]] || { echo 'DB 사용자 이름 형식 오류'; exit 1; }

STAMP=$(date +%Y%m%d_%H%M%S)
PARTIAL=$(mktemp "$BACKUP_DIR/${POSTGRES_DB}_${STAMP}_XXXXXX.dump.partial")
FILE=${PARTIAL%.partial}

# Docker exec 클라이언트를 중단해도 컨테이너 안의 pg_dump는 남을 수 있다.
# 서버 안에서도 같은 잠금과 제한 시간을 적용해 다음 백업과 겹치지 않게 한다.
run_backup_command 18m docker exec postgres flock -w 120 /tmp/axcore-pg-backup.lock \
    timeout -k 30s 15m pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$PARTIAL"
[ -s "$PARTIAL" ] || { echo 'backup failed: empty dump' >&2; exit 1; }
# 아카이브 목차 검사이며 DB에 복원하거나 데이터를 변경하지 않는다.
run_backup_command 75s docker exec -i postgres timeout -k 5s 60s pg_restore --list < "$PARTIAL" > /dev/null
mv -- "$PARTIAL" "$FILE"
PARTIAL=''

find "$BACKUP_DIR" -maxdepth 1 -type f -name "${POSTGRES_DB}_*.dump" -mtime +"$KEEP_DAYS" -delete
KEPT=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${POSTGRES_DB}_*.dump" -printf '.\n' | wc -l)

echo "[$(date '+%F %T')] backup OK: $(basename "$FILE") $(du -h "$FILE" | cut -f1), kept $KEPT dumps"
