# 3. DB 덤프 최신본

2026-11-18 develop 기준으로 만든 덤프입니다(플랫폼 DB는 V29까지, 원천 데이터는 2026-11-16과 같음). 운영 DB를 그대로 뜬 것이 아니라, 같은 구조를 새로 만들어 뜬 정리본입니다. 운영 계정, 비밀번호 해시, 원천 접속 비밀값 암호문, Agent 대화, 감사 기록은 들어 있지 않습니다.

플랫폼의 원천 연결(`data_source`), 객체·관계·유형, 분석 후보, 재배정 계획도 비어 있습니다. 이 파일은 **최신 구조와 초기 데이터의 복원본**이며 발표 직전 운영 상태의 스냅샷이 아닙니다. 복원 뒤 [빈 플랫폼에서 시연 상태 준비](../4-demo-scenario.md#빈-플랫폼에서-시연-상태-준비)를 진행해야 합니다. 동일한 AI 후보 수·이름·객체 ID를 자동으로 재현한다고 보장하지 않습니다.

| 파일 | 내용 | 크기 |
| --- | --- | --- |
| `platform_axcore_v29_schema_seed.sql` | 플랫폼 DB `axcore`, 스키마 `platform`. Flyway V1~V29 적용 결과(표 54개, `flyway_schema_history` 포함). 초기 데이터는 역할 4개, 권한, 역할별 조회 범위 7행(관리자의 미분류 유형 조회 포함), Action 정의 25개뿐이고 계정은 0개 | 약 220KB |
| `source_erp_data-v1.0.sql` | 원천 ERP DB `erp`, 스키마 `erp`. 팀이 만든 합성 데이터 `ai/data/data-v1.0` | 약 160KB |
| `source_mes_data-v1.0.sql` | 원천 MES DB `mes`, 스키마 `mes`. 같은 합성 데이터 | 약 220KB |

원천 두 DB는 표 33개, 5,231행이고 `ai/data/data-v1.0/verify_counts.sql`의 기대 행 수와 모두 일치합니다.

## 만든 방법

```bash
# 저장소 루트에서 실행. 실제 운영 비밀번호를 쓰지 않는 로컬 임시 DB
read -r -s -p '로컬 덤프 생성용 임시 비밀번호: ' DUMP_DB_PASSWORD
printf '\n'
docker run -d --name exec-pg -e POSTGRES_USER=axcore -e POSTGRES_PASSWORD="$DUMP_DB_PASSWORD" -e POSTGRES_DB=axcore postgres:18.6
# docker logs exec-pg의 최종 초기화 완료와 아래 접속 성공 후 계속 진행
docker exec exec-pg psql -X -U axcore -d axcore -Atc 'SELECT 1'
docker run --rm --network container:exec-pg -v "$PWD/backend/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:12 -url=jdbc:postgresql://localhost:5432/axcore -user=axcore -password="$DUMP_DB_PASSWORD" \
  -schemas=platform -locations=filesystem:/flyway/sql migrate
docker exec exec-pg pg_dump -U axcore -d axcore --no-owner --no-privileges > platform_axcore_v29_schema_seed.sql

# 원천 ERP·MES: 합성 데이터 적재 후 덤프
docker exec exec-pg psql -U axcore -d axcore -c "create database erp" -c "create database mes"
docker cp ai/data/data-v1.0 exec-pg:/tmp/data
docker exec -w /tmp/data exec-pg psql -U axcore -d erp -f load_erp.psql
docker exec -w /tmp/data exec-pg psql -U axcore -d mes -f load_mes.psql
docker exec exec-pg pg_dump -U axcore -d erp --no-owner --no-privileges > source_erp_data-v1.0.sql
docker exec exec-pg pg_dump -U axcore -d mes --no-owner --no-privileges > source_mes_data-v1.0.sql
unset DUMP_DB_PASSWORD
```

위 생성 예시는 저장소 루트에 SQL 파일을 만듭니다. 제출 파일을 갱신할 때는 버전·스키마·행 수와 민감 데이터 제외를 검토한 뒤 `exec/3-db-dump/`에 옮깁니다. 임시 컨테이너와 데이터의 정리는 생성한 `exec-pg` 대상인지 확인한 뒤 별도로 수행합니다. 기존 파일을 복원·확인하는 데에는 Flyway 생성 예시를 다시 실행할 필요가 없습니다.

합성 데이터는 `ai/`에서 `python -m app.dataset.build --version v1.0 --force`로 다시 만들 수 있습니다. 같은 seed(20261023)면 결과가 같습니다.

## 복구

PostgreSQL 18 클라이언트(psql 18 이상)가 필요합니다. 덤프 첫머리의 `\restrict` 줄은 pg_dump 18이 넣는 복구 안전 표시라 그대로 둡니다.

**대상은 표가 없는 새 DB입니다.** 실행 중인 플랫폼 DB에 이 SQL을 덮어쓰는 초기화 절차로 사용하지 않습니다. 1장의 일반 기동으로 Flyway가 이미 표를 만들었다면 중복 복원하지 않습니다. 기존 데이터를 되돌려야 할 때는 대상 DB의 현재 백업과 별도 승인·복구 계획을 먼저 준비합니다.

플랫폼 덤프를 사용할 새 서버에서는 전체 앱 기동 전에 PostgreSQL만 올립니다. 아래는 메인 서버의 Bash에서 실행하며 `REPO_ROOT`는 서버에 클론한 저장소입니다. `POSTGRES_DB`·`POSTGRES_USER`가 `axcore`인 기준 설정의 예시입니다.

```bash
cd /home/ubuntu/axcore
docker compose --env-file .env up -d postgres
docker compose --env-file .env ps postgres
# postgres가 healthy이고 아래 SELECT가 성공한 뒤 복원
docker compose --env-file .env exec -T postgres psql -X -U axcore -d axcore -Atc 'SELECT 1'
docker compose --env-file .env exec -T postgres psql -X -1 -v ON_ERROR_STOP=1 -U axcore -d axcore \
  < "$REPO_ROOT/exec/3-db-dump/platform_axcore_v29_schema_seed.sql"
docker compose --env-file .env exec -T postgres psql -X -U axcore -d axcore -c \
  "SELECT max(version::int) FROM platform.flyway_schema_history WHERE version ~ '^[0-9]+$' AND success"
```

복원 검사가 끝나면 [최초 설치 5번](../1-build-deploy.md#처음-세팅-메인-서버)으로 돌아가 Basic Auth 파일 등 기동 전 준비를 완료한 뒤 전체 앱을 시작합니다.

원천은 `setup.sh`가 이미 `data-v1.0`을 적재했다면 덤프를 추가 적용하지 않습니다. 덤프를 쓰는 별도 복원 경로에서는 운영 담당자가 각 서버의 `source-db` 컨테이너·표가 없는 DB `erp`/`mes`를 먼저 준비합니다. 관리자는 외부 DB 포트로 접속하지 않고 SSH 안에서 컨테이너의 `psql`을 사용합니다. 클론한 저장소 루트의 Bash에서 주소를 바꿔 실행합니다.

```bash
SOURCE_KEY=~/.ssh/C106_Source.pem
ERP_SSH=ec2-user@erp.example.internal
MES_SSH=ec2-user@mes.example.internal
ssh -i "$SOURCE_KEY" "$ERP_SSH" 'cd ~/source-db && docker compose exec -T source-db psql -X -1 -v ON_ERROR_STOP=1 -U source_admin -d erp' \
  < exec/3-db-dump/source_erp_data-v1.0.sql
ssh -i "$SOURCE_KEY" "$MES_SSH" 'cd ~/source-db && docker compose exec -T source-db psql -X -1 -v ON_ERROR_STOP=1 -U source_admin -d mes' \
  < exec/3-db-dump/source_mes_data-v1.0.sql
```

- 플랫폼 DB를 복구한 뒤 backend를 띄우면 Flyway는 V29까지 적용된 것으로 보고 넘어갑니다. ADMIN이 없으므로 `AXCORE_INITIAL_ADMIN_LOGIN_ID`·`AXCORE_INITIAL_ADMIN_PASSWORD` 값으로 최초 관리자가 만들어집니다.
- 원천 DB를 덤프로 복구했다면 계정·허용 목록·publication은 `infra/source-db/setup.sh`(또는 `sql/setup_roles.sql`)로 따로 만듭니다. 덤프에는 원천 계정과 권한이 들어 있지 않습니다.
- 원천 합성 데이터에는 원천 쪽 데이터로서 금지 표(근태, 고정자산, change_log)와 금지 컬럼(직원 이름 등)도 들어 있습니다. 이름 등은 모두 만든 값입니다. 플랫폼은 원천 허용 목록 때문에 이 표·컬럼을 읽지 못합니다.
- 시연용 변경(`ai/data/scenario-v1.0`의 `s5_fix.sql`, `cdc_*.sql`)은 이 덤프에 들어 있지 않습니다. 쓰는 때는 [4-demo-scenario.md](../4-demo-scenario.md)를 봅니다.

## 복원 검증 결과 (2026-11-18)

영속 볼륨·호스트 포트·외부 네트워크 없이 만든 로컬 PostgreSQL 18.6에 세 SQL을 `psql -X -1 -v ON_ERROR_STOP=1`로 복원했습니다. 검사 컨테이너는 끝나고 정리했습니다.

| 대상 | 확인한 결과 |
| --- | --- |
| 플랫폼 | 54표, 성공한 버전 마이그레이션 29개·최대 V29. 계정·원천 연결·객체 각각 0행, Action 정의 25행 |
| ERP | 18표·2,356행, 기준 행 수와 차이 없음 |
| MES | 15표·2,875행, 기준 행 수와 차이 없음 |

Flyway 이력에는 V1~V29 외에 스키마 생성 이력 1행이 있어 전체 이력 행 수는 30입니다. 실제 운영 DB 복구나 복원 뒤 AI 분석·로그인 시연을 실행한 결과는 아닙니다.
