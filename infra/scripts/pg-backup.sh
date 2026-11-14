#!/usr/bin/env bash
# 플랫폼 PostgreSQL 자동 백업
# - 매일 새벽 cron 으로 실행. pg_dump 로 DB 전체를 파일 하나로 뜬다.
# - 7일 지난 백업 파일은 지운다.
# - 덤프에는 업무 데이터 전체가 들어 있으므로 폴더는 700, 파일은 600으로 둔다(S15P31C106-397). 서버의 다른 계정·컨테이너가 읽지 못하게 한다.
# - 비밀번호 불필요: 컨테이너 안에서 실행하므로 로컬 접속으로 인증된다.
set -euo pipefail
umask 077

cd /home/ubuntu/axcore
BACKUP_DIR=/home/ubuntu/axcore/backup
KEEP_DAYS=7
STAMP=$(date +%Y%m%d_%H%M)

# 비밀값이 포함된 dotenv를 셸 코드로 실행하지 않는다.
# DB 컨테이너에 이미 전달된 이름만 읽으며 비밀번호는 꺼내지 않는다.
POSTGRES_DB=$(docker exec postgres printenv POSTGRES_DB)
POSTGRES_USER=$(docker exec postgres printenv POSTGRES_USER)
[[ "$POSTGRES_DB" =~ ^[a-zA-Z0-9_]+$ ]] || { echo 'DB 이름 형식 오류'; exit 1; }
[[ "$POSTGRES_USER" =~ ^[a-zA-Z0-9_]+$ ]] || { echo 'DB 사용자 이름 형식 오류'; exit 1; }

mkdir -p "$BACKUP_DIR"
# 폴더 주인일 때만 좁힌다(배포가 같은 폴더에 설정 사본을 남긴다)
[ -O "$BACKUP_DIR" ] && chmod 700 "$BACKUP_DIR"
FILE="$BACKUP_DIR/${POSTGRES_DB}_${STAMP}.dump"

docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$FILE"

find "$BACKUP_DIR" -name "${POSTGRES_DB}_*.dump" -mtime +"$KEEP_DAYS" -delete

echo "[$(date '+%F %T')] backup OK: $(basename "$FILE") $(du -h "$FILE" | cut -f1), kept $(ls "$BACKUP_DIR" | wc -l) files"
