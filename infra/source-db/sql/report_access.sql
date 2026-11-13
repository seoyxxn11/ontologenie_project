-- 원천 DB의 플랫폼 계정 권한·publication·복제 슬롯을 보여준다(S15P31C106-395). 읽기만 한다.
-- migrate_allowlist.sql이 적용 전·뒤에 부르고, 따로 실행해 현재 상태를 볼 수도 있다. 접속한 DB 이름이 스키마 이름이다.

\echo '-- 계정별로 읽을 수 있는 컬럼(전체 = 표 SELECT, - = 없음)'
select c.relname as "표",
       acc.ro as platform_ro, acc.ai as platform_ai, acc.cdc as platform_cdc
from pg_class c
cross join lateral (
    select
        max(v) filter (where role_name = 'platform_ro') as ro,
        max(v) filter (where role_name = 'platform_ai') as ai,
        max(v) filter (where role_name = 'platform_cdc') as cdc
    from (
        select rl.role_name,
               case
                   when not exists (select 1 from pg_roles where rolname = rl.role_name) then '(계정 없음)'
                   when has_table_privilege(rl.role_name, c.oid, 'SELECT') then '전체'
                   else coalesce((select string_agg(att.attname, ', ' order by att.attnum)
                                  from pg_attribute att
                                  where att.attrelid = c.oid and att.attnum > 0 and not att.attisdropped
                                    and has_column_privilege(rl.role_name, c.oid, att.attnum, 'SELECT')), '-')
               end as v
        from unnest(array['platform_ro', 'platform_ai', 'platform_cdc']) as rl(role_name)
    ) per_role
) acc
where c.relnamespace = current_database()::regnamespace and c.relkind in ('r', 'p', 'v', 'm', 'f')
order by c.relname;

\echo '-- publication axcore_cdc의 표·컬럼'
select pt.schemaname || '.' || pt.tablename as "표", array_to_string(pt.attnames, ', ') as "컬럼"
from pg_publication_tables pt
where pt.pubname = 'axcore_cdc'
order by pt.tablename;

\echo '-- replica identity(d = 기본 키, f = 전체, i = 인덱스, n = 없음)'
select c.relname as "표", c.relreplident as replica_identity,
       exists (select 1 from pg_index i where i.indrelid = c.oid and i.indisprimary) as "기본 키"
from pg_class c
where c.relnamespace = current_database()::regnamespace and c.relkind in ('r', 'p')
order by c.relname;

\echo '-- 플랫폼 계정에 남은 기본 권한(비어 있어야 함)'
select d.defaclrole::regrole as "만드는 계정", d.defaclnamespace::regnamespace as "스키마",
       d.defaclobjtype as "종류", a.grantee::regrole as "받는 계정", a.privilege_type as "권한"
from pg_default_acl d, aclexplode(d.defaclacl) a
where a.grantee in (select oid from pg_roles where rolname in ('platform_ro', 'platform_ai', 'platform_cdc'));

\echo '-- 복제 슬롯(axcore_erp·axcore_mes, pgoutput 외의 슬롯은 확인 대상)'
select slot_name, plugin, slot_type, database, active, wal_status
from pg_replication_slots
order by slot_name;
