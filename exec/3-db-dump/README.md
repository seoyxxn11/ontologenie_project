# 3. DB 덤프 최신본

2026-11-16 develop 기준으로 만든 덤프입니다. 운영 DB를 그대로 뜬 것이 아니라, 같은 구조를 새로 만들어 뜬 정리본입니다. 운영 계정, 비밀번호 해시, 원천 접속 비밀값 암호문, Agent 대화, 감사 기록은 들어 있지 않습니다.

| 파일 | 내용 | 크기 |
| --- | --- | --- |
| `platform_axcore_v26_schema_seed.sql` | 플랫폼 DB `axcore`, 스키마 `platform`. Flyway V1~V26 적용 결과(표 54개, `flyway_schema_history` 포함). 초기 데이터는 역할 4개, 권한, 역할별 조회 범위, Action 정의 25개뿐이고 계정은 0개 | 약 210KB |
| `source_erp_data-v1.0.sql` | 원천 ERP DB `erp`, 스키마 `erp`. 팀이 만든 합성 데이터 `ai/data/data-v1.0` | 약 160KB |
| `source_mes_data-v1.0.sql` | 원천 MES DB `mes`, 스키마 `mes`. 같은 합성 데이터 | 약 220KB |

원천 두 DB는 표 33개, 5,231행이고 `ai/data/data-v1.0/verify_counts.sql`의 기대 행 수와 모두 일치합니다.

## 만든 방법

```bash
# 플랫폼 DB: 빈 PostgreSQL 18.6에 Flyway 마이그레이션만 적용
docker run -d --name exec-pg -e POSTGRES_USER=axcore -e POSTGRES_PASSWORD=<임시값> -e POSTGRES_DB=axcore postgres:18.6
docker run --rm --network container:exec-pg -v "$PWD/backend/src/main/resources/db/migration:/flyway/sql:ro" \
  flyway/flyway:12 -url=jdbc:postgresql://localhost:5432/axcore -user=axcore -password=<임시값> \
  -schemas=platform -locations=filesystem:/flyway/sql migrate
docker exec exec-pg pg_dump -U axcore -d axcore --no-owner --no-privileges > platform_axcore_v26_schema_seed.sql

# 원천 ERP·MES: 합성 데이터 적재 후 덤프
docker exec exec-pg psql -U axcore -d axcore -c "create database erp" -c "create database mes"
docker cp ai/data/data-v1.0 exec-pg:/tmp/data
docker exec -w /tmp/data exec-pg psql -U axcore -d erp -f load_erp.psql
docker exec -w /tmp/data exec-pg psql -U axcore -d mes -f load_mes.psql
docker exec exec-pg pg_dump -U axcore -d erp --no-owner --no-privileges > source_erp_data-v1.0.sql
docker exec exec-pg pg_dump -U axcore -d mes --no-owner --no-privileges > source_mes_data-v1.0.sql
```

합성 데이터는 `ai/`에서 `python -m app.dataset.build --version v1.0 --force`로 다시 만들 수 있습니다. 같은 seed(20261023)면 결과가 같습니다.

## 복구

PostgreSQL 18 클라이언트(psql 18 이상)가 필요합니다. 덤프 첫머리의 `\restrict` 줄은 pg_dump 18이 넣는 복구 안전 표시라 그대로 둡니다.

```bash
# 플랫폼 DB (빈 DB axcore에)
docker compose exec -T postgres psql -U axcore -d axcore < platform_axcore_v26_schema_seed.sql

# 원천 DB (원천 서버마다 빈 DB erp 또는 mes에)
psql -U source_admin -d erp < source_erp_data-v1.0.sql
psql -U source_admin -d mes < source_mes_data-v1.0.sql
```

- 플랫폼 DB를 복구한 뒤 backend를 띄우면 Flyway는 V26까지 적용된 것으로 보고 넘어갑니다. ADMIN이 없으므로 `AXCORE_INITIAL_ADMIN_LOGIN_ID`·`AXCORE_INITIAL_ADMIN_PASSWORD` 값으로 최초 관리자가 만들어집니다.
- 원천 DB를 덤프로 복구했다면 계정·허용 목록·publication은 `infra/source-db/setup.sh`(또는 `sql/setup_roles.sql`)로 따로 만듭니다. 덤프에는 원천 계정과 권한이 들어 있지 않습니다.
- 원천 합성 데이터에는 원천 쪽 데이터로서 금지 표(근태, 고정자산, change_log)와 금지 컬럼(직원 이름 등)도 들어 있습니다. 이름 등은 모두 만든 값입니다. 플랫폼은 원천 허용 목록 때문에 이 표·컬럼을 읽지 못합니다.
- 시연용 변경(`ai/data/scenario-v1.0`의 `s5_fix.sql`, `cdc_*.sql`)은 이 덤프에 들어 있지 않습니다. 쓰는 때는 [4-demo-scenario.md](../4-demo-scenario.md)를 봅니다.
