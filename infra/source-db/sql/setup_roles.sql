-- 원천 DB의 플랫폼 계정과 CDC publication(S15P31C106-61, 허용 목록 S15P31C106-395). 새 설치에 쓰고, 다시 실행해도 된다.
-- 원천 관리자 계정으로 실행한다(setup.sh). 권한·publication은 allowlist.sql의 허용 목록대로만 만들고,
-- 다시 실행하면 허용 목록 밖의 권한은 회수한다(넓어지지 않는다). 전체가 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않는다.
-- 비밀번호는 실행할 때 환경변수로만 받는다: PLATFORM_RO_PASSWORD, PLATFORM_AI_PASSWORD, PLATFORM_CDC_PASSWORD, SOURCE_DB(erp / mes = DB 이름 = 스키마 이름)
\set ON_ERROR_STOP on
\getenv ro_pw PLATFORM_RO_PASSWORD
\getenv ai_pw PLATFORM_AI_PASSWORD
\getenv cdc_pw PLATFORM_CDC_PASSWORD
\getenv src SOURCE_DB

begin;

-- 접속한 DB가 SOURCE_DB와 다르면 멈춘다(apply_allowlist.sql은 접속한 DB 이름을 스키마로 쓴다).
select set_config('axcore.source_db', :'src', true) as source_db_set \gset
do $check$
begin
    if current_setting('axcore.source_db') is distinct from current_database() then
        raise exception 'SOURCE_DB(%)와 접속한 DB(%)가 다르다', current_setting('axcore.source_db'), current_database();
    end if;
end
$check$;

-- 조회 전용(배치, SYNC 용도): 접속 검사·구조 조회·표본 보기·테이블 전체 다시 읽기
SELECT 'CREATE ROLE platform_ro' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_ro') \gexec
SELECT format('ALTER ROLE platform_ro LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'ro_pw') \gexec
ALTER ROLE platform_ro SET default_transaction_read_only = on;

-- 조회 전용(FastAPI 제안기, AI 용도). 배치와 나눠 시간·접속 수를 따로 묶고, 외부 LLM으로 보내면 안 되는 제한 컬럼은 이 계정 권한에서 뺀다
SELECT 'CREATE ROLE platform_ai' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_ai') \gexec
SELECT format('ALTER ROLE platform_ai LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 5 PASSWORD %L', :'ai_pw') \gexec
ALTER ROLE platform_ai SET default_transaction_read_only = on;
ALTER ROLE platform_ai SET statement_timeout = '30s';

-- CDC 복제: Debezium이 WAL을 읽는다. superuser가 아니다.
-- REPLICATION 권한은 publication과 상관없이 WAL을 읽을 수 있다(README "REPLICATION 권한의 한계").
SELECT 'CREATE ROLE platform_cdc' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_cdc') \gexec
SELECT format('ALTER ROLE platform_cdc LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE REPLICATION NOBYPASSRLS PASSWORD %L', :'cdc_pw') \gexec

GRANT CONNECT ON DATABASE :"src" TO platform_ro, platform_ai, platform_cdc;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA :"src" TO platform_ro, platform_ai, platform_cdc;

-- 표·컬럼 권한과 publication은 허용 목록으로만 정한다.
\ir allowlist.sql
\ir apply_allowlist.sql

commit;
