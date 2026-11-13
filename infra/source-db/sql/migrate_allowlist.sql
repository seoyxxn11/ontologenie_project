-- 이미 세팅된 원천 서버의 플랫폼 계정 권한과 publication을 허용 목록으로 바꾼다(S15P31C106-395).
-- 계정 속성·비밀번호는 건드리지 않는다. 원천 관리자 계정으로, 접속한 DB(erp / mes)에 대해 실행한다.
--   기본은 시험 실행이다: 한 트랜잭션 안에서 바꾸고 결과를 보여준 뒤 되돌린다.
--   확정: psql ... -v apply=1 -f migrate_allowlist.sql
-- 하는 일(apply_allowlist.sql):
--   REVOKE ALL ON 스키마의 모든 표 / ALTER DEFAULT PRIVILEGES ... REVOKE (예전 스크립트의 표 전체·기본 권한 회수)
--   GRANT SELECT [(컬럼, ...)] ON 허용 목록 표
--   ALTER PUBLICATION axcore_cdc SET TABLE 허용 목록 표 [(컬럼, ...)], ...
--   replica identity 확인(기본 키가 컬럼 목록에 들어 있어야 UPDATE·DELETE가 원천에서 실패하지 않는다)
--   슬롯: publication이 바뀌는데 이 DB에 논리 복제 슬롯이 있으면 확정을 멈춘다(시험 실행은 경고만). 바꾸기 전에 쌓인 WAL은 예전 정의로
--   풀려 금지 컬럼이 나갈 수 있어서다. 커넥터를 멈추고 슬롯을 지운 뒤 확정한다. 알고 넘어가야 하면 -v allow_slot=1.
\set ON_ERROR_STOP on
\if :{?apply}
\else
\set apply 0
\endif
\if :{?allow_slot}
\else
\set allow_slot 0
\endif

\echo '==== 적용 전 ===='
\ir report_access.sql

begin;
\if :apply
\else
select set_config('axcore.dry_run', 'on', true) as dry_run_set \gset
\endif
\if :allow_slot
select set_config('axcore.allow_slot', 'on', true) as allow_slot_set \gset
\endif
\ir allowlist.sql
\ir apply_allowlist.sql

\echo '==== 적용 뒤(아직 확정 전) ===='
\ir report_access.sql

\if :apply
commit;
\echo '확정했다.'
\else
rollback;
\echo '시험 실행이라 되돌렸다. 확정하려면 -v apply=1로 다시 실행한다.'
\endif
