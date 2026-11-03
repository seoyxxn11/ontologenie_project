-- 원천 DB의 플랫폼 계정과 CDC publication(S15P31C106-61). 다시 실행해도 된다.
-- 비밀번호는 실행할 때 환경변수로만 받는다: PLATFORM_RO_PASSWORD, PLATFORM_AI_PASSWORD, PLATFORM_CDC_PASSWORD, SOURCE_DB(erp / mes = DB 이름 = 스키마 이름)
\set ON_ERROR_STOP on
\getenv ro_pw PLATFORM_RO_PASSWORD
\getenv ai_pw PLATFORM_AI_PASSWORD
\getenv cdc_pw PLATFORM_CDC_PASSWORD
\getenv src SOURCE_DB

-- 조회 전용(배치, SYNC 용도): 접속 검사·구조 조회·표본 보기·테이블 전체 다시 읽기
SELECT 'CREATE ROLE platform_ro' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_ro') \gexec
SELECT format('ALTER ROLE platform_ro LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', :'ro_pw') \gexec
ALTER ROLE platform_ro SET default_transaction_read_only = on;

-- 조회 전용(FastAPI 제안기, AI 용도). 배치와 나눠 시간·접속 수를 따로 묶고, 외부 LLM으로 보내면 안 되는 제한 컬럼은 이 계정 권한에서 뺀다(A5)
SELECT 'CREATE ROLE platform_ai' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_ai') \gexec
SELECT format('ALTER ROLE platform_ai LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION CONNECTION LIMIT 5 PASSWORD %L', :'ai_pw') \gexec
ALTER ROLE platform_ai SET default_transaction_read_only = on;
ALTER ROLE platform_ai SET statement_timeout = '30s';

-- CDC 복제: Debezium이 WAL을 읽는다. superuser가 아니다
SELECT 'CREATE ROLE platform_cdc' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_cdc') \gexec
SELECT format('ALTER ROLE platform_cdc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE REPLICATION PASSWORD %L', :'cdc_pw') \gexec

GRANT CONNECT ON DATABASE :"src" TO platform_ro, platform_ai, platform_cdc;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA :"src" TO platform_ro, platform_ai, platform_cdc;
-- 제한·금지 컬럼이 정해지기 전에는 두 조회 계정 모두 전체 표를 읽는다. 정해지면 표 단위 권한을 컬럼 단위로 바꾼다
GRANT SELECT ON ALL TABLES IN SCHEMA :"src" TO platform_ro, platform_ai;
ALTER DEFAULT PRIVILEGES IN SCHEMA :"src" GRANT SELECT ON TABLES TO platform_ro, platform_ai;

-- CDC로 내보낼 표. 원천 변경 이력 표(change_log)는 CDC를 쓰면 필요 없어 뺀다.
-- 금지 컬럼(A5)이 정해지면 표 이름 뒤에 컬럼 목록을 적어 그 컬럼을 뺀다(ALTER PUBLICATION ... SET TABLE).
SELECT format('CREATE PUBLICATION axcore_cdc FOR TABLE %s',
              string_agg(format('%I.%I', schemaname, tablename), ', ' ORDER BY tablename))
FROM pg_tables
WHERE schemaname = :'src' AND tablename <> 'change_log'
  AND NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'axcore_cdc')
HAVING count(*) > 0 \gexec

SELECT format('GRANT SELECT ON %I.%I TO platform_cdc', schemaname, tablename)
FROM pg_publication_tables WHERE pubname = 'axcore_cdc' \gexec
