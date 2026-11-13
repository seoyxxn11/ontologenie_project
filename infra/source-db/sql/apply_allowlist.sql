-- 허용 목록(allowlist.sql)대로 플랫폼 계정 권한과 publication axcore_cdc를 맞춘다(S15P31C106-395).
-- 접속한 DB 이름이 곧 스키마 이름이다(erp / mes). 호출하는 쪽이 연 트랜잭션 안에서, 원천 관리자 계정으로 실행한다.
-- 순서: 정의 확인 → 권한 모두 회수 → 허용 목록만 부여 → publication 맞춤 → 결과가 허용 목록과 정확히 같은지 확인.
-- 어느 단계든 문제가 있으면 예외를 던지고 트랜잭션 전체가 되돌아간다. 몇 번을 다시 실행해도 결과는 같고, 권한이 넓어지지 않는다.
do $apply$
declare
    src constant text := current_database();
    platform_roles constant text[] := array['platform_ro', 'platform_ai', 'platform_cdc'];
    r record;
    cols text[];
    spec text;
    problems text[] := '{}';
begin
    -- 0. 대상
    if not exists (select 1 from pg_namespace where nspname = src) then
        raise exception '스키마 %가 없다. DB 이름과 스키마 이름이 같아야 한다', src;
    end if;
    for r in select unnest(platform_roles) as role_name loop
        if not exists (select 1 from pg_roles where rolname = r.role_name) then
            raise exception '계정 %가 없다. 새 설치는 setup_roles.sql로 한다', r.role_name;
        end if;
    end loop;

    -- 1. 정의 확인. 이름이 틀리거나 원천 컬럼 이름이 바뀌면 막는 대신 조용히 넓어지므로 먼저 멈춘다.
    for r in
        select p.entry, split_part(p.entry, '.', 2) as table_name, split_part(p.entry, '.', 3) as column_name
        from axcore_policy p
        where split_part(p.entry, '.', 1) = src
    loop
        if to_regclass(format('%I.%I', src, r.table_name)) is null then
            problems := problems || format('정책 %s: 표가 없다', r.entry);
        elsif r.column_name <> '*' and not exists (
            select 1 from pg_attribute
            where attrelid = format('%I.%I', src, r.table_name)::regclass
              and attname = r.column_name and attnum > 0 and not attisdropped) then
            problems := problems || format('정책 %s: 컬럼이 없다', r.entry);
        end if;
    end loop;

    for r in select * from axcore_allow where schema_name = src loop
        if to_regclass(format('%I.%I', src, r.table_name)) is null then
            problems := problems || format('허용 %s.%s: 표가 없다', src, r.table_name);
            continue;
        end if;
        select array_agg(c order by c) into cols
        from unnest(r.ro_columns || r.ai_columns) c
        where not exists (
            select 1 from pg_attribute
            where attrelid = format('%I.%I', src, r.table_name)::regclass
              and attname = c and attnum > 0 and not attisdropped);
        if cols is not null then
            problems := problems || format('허용 %s.%s: 없는 컬럼 %s', src, r.table_name, cols);
        end if;
        if r.ro_columns is not null and r.ai_columns is not null and not r.ai_columns <@ r.ro_columns then
            problems := problems || format('허용 %s.%s: ai_columns가 ro_columns를 넘는다', src, r.table_name);
        end if;
        -- 금지: 표 전체면 허용 목록에 있으면 안 되고, 컬럼이면 ro_columns에 적힌 목록에서 빠져 있어야 한다.
        for cols in
            select array_agg(split_part(p.entry, '.', 3) order by p.entry)
            from axcore_policy p
            where split_part(p.entry, '.', 1) = src and split_part(p.entry, '.', 2) = r.table_name
              and p.grade = 'FORBIDDEN'
            having count(*) > 0
        loop
            if '*' = any(cols) then
                problems := problems || format('허용 %s.%s: 표 전체가 금지다', src, r.table_name);
            elsif r.ro_columns is null or r.ro_columns && cols then
                problems := problems || format('허용 %s.%s: ro_columns에 금지 컬럼 %s을 빼고 적어야 한다', src, r.table_name, cols);
            end if;
        end loop;
        -- 금지·제한 컬럼은 platform_ai가 읽는 목록에서 빠져 있어야 한다.
        for cols in
            select array_agg(split_part(p.entry, '.', 3) order by p.entry)
            from axcore_policy p
            where split_part(p.entry, '.', 1) = src and split_part(p.entry, '.', 2) = r.table_name
            having count(*) > 0
        loop
            if coalesce(r.ai_columns, r.ro_columns) is null or coalesce(r.ai_columns, r.ro_columns) && cols then
                problems := problems || format('허용 %s.%s: AI 목록에 금지·제한 컬럼 %s을 빼고 적어야 한다', src, r.table_name, cols);
            end if;
        end loop;
    end loop;

    -- CDC 표는 UPDATE·DELETE를 보낼 replica identity가 있어야 하고, 컬럼 목록이 그 컬럼을 덮어야 한다(PostgreSQL 15+).
    -- 없으면 publication에 든 표의 UPDATE·DELETE가 원천에서 실패한다.
    for r in
        select a.table_name, a.ro_columns, c.relreplident,
               (select array_agg(att.attname::text)
                from pg_index i
                join pg_attribute att on att.attrelid = i.indrelid and att.attnum = any(i.indkey)
                where i.indrelid = c.oid
                  and ((c.relreplident = 'd' and i.indisprimary) or (c.relreplident = 'i' and i.indisreplident))) as identity_columns
        from axcore_allow a
        join pg_class c on c.oid = to_regclass(format('%I.%I', a.schema_name, a.table_name))
        where a.schema_name = src and a.cdc
    loop
        if r.relreplident = 'n' then
            problems := problems || format('CDC %s.%s: replica identity가 NOTHING이다', src, r.table_name);
        elsif r.relreplident = 'f' and r.ro_columns is not null then
            problems := problems || format('CDC %s.%s: replica identity FULL은 컬럼 목록과 함께 쓸 수 없다', src, r.table_name);
        elsif r.relreplident in ('d', 'i') and r.identity_columns is null then
            problems := problems || format('CDC %s.%s: 기본 키(또는 replica identity 인덱스)가 없다', src, r.table_name);
        elsif r.relreplident in ('d', 'i') and r.ro_columns is not null and not r.identity_columns <@ r.ro_columns then
            problems := problems || format('CDC %s.%s: 컬럼 목록이 replica identity 컬럼 %s을 덮지 않는다', src, r.table_name, r.identity_columns);
        end if;
    end loop;

    for r in
        select e.table_name from axcore_excluded e
        join axcore_allow a using (schema_name, table_name)
        where e.schema_name = src
    loop
        problems := problems || format('%s.%s: 허용 목록과 제외 목록에 함께 있다', src, r.table_name);
    end loop;

    if exists (select 1 from pg_publication where pubname = 'axcore_cdc' and puballtables) then
        problems := problems || 'publication axcore_cdc가 FOR ALL TABLES다. 컬럼 목록을 쓸 수 없으니 관리자가 지우고 다시 만든다'::text;
    end if;

    if cardinality(problems) > 0 then
        raise exception E'허용 목록 확인 실패(아무것도 바꾸지 않음):\n%', array_to_string(problems, E'\n');
    end if;

    -- 2. 결정되지 않은 표·컬럼 알림. 권한은 주지 않는다(허용 목록에 넣어야 읽힘).
    for r in
        select c.relname from pg_class c
        where c.relnamespace = src::regnamespace and c.relkind in ('r', 'p', 'v', 'm', 'f')
          and not exists (select 1 from axcore_allow a where a.schema_name = src and a.table_name = c.relname)
          and not exists (select 1 from axcore_excluded e where e.schema_name = src and e.table_name = c.relname)
          and not exists (select 1 from axcore_policy p where p.entry = format('%s.%s.*', src, c.relname))
        order by c.relname
    loop
        raise notice '허용 목록에 없는 표 %.%: 어떤 플랫폼 계정도 읽지 못하고 CDC에도 없다', src, r.relname;
    end loop;
    for r in
        select a.table_name, att.attname
        from axcore_allow a
        join pg_attribute att on att.attrelid = to_regclass(format('%I.%I', a.schema_name, a.table_name))
        where a.schema_name = src and a.ro_columns is not null
          and att.attnum > 0 and not att.attisdropped
          and not att.attname = any(a.ro_columns)
          and not exists (select 1 from axcore_policy p where p.entry = format('%s.%s.%s', src, a.table_name, att.attname))
        order by 1, 2
    loop
        raise notice '컬럼 목록에 없는 컬럼 %.%.%: 플랫폼 계정이 읽지 못한다', src, r.table_name, r.attname;
    end loop;

    -- 3. 이 스키마의 모든 표·시퀀스 권한을 회수한다. 표 권한을 회수하면 컬럼 권한도 함께 회수된다.
    --    예전 스크립트가 준 기본 권한(새 표 자동 SELECT)도 지운다. 새 표는 허용 목록에 넣어야 읽힌다.
    for r in
        select c.oid::regclass as rel from pg_class c
        where c.relnamespace = src::regnamespace and c.relkind in ('r', 'p', 'v', 'm', 'f')
    loop
        execute format('revoke all on table %s from platform_ro, platform_ai, platform_cdc', r.rel);
    end loop;
    execute format('revoke all on all sequences in schema %I from platform_ro, platform_ai, platform_cdc', src);
    execute format('alter default privileges in schema %I revoke all on tables from platform_ro, platform_ai, platform_cdc', src);
    execute format('alter default privileges in schema %I revoke all on sequences from platform_ro, platform_ai, platform_cdc', src);

    -- 4. 허용 목록만 SELECT로 부여한다. NULL은 표 전체, 배열은 그 컬럼만이다.
    for r in select * from axcore_allow where schema_name = src order by table_name loop
        execute format('grant select %s on %I.%I to platform_ro',
                       coalesce('(' || (select string_agg(quote_ident(c), ', ') from unnest(r.ro_columns) c) || ')', ''),
                       src, r.table_name);
        execute format('grant select %s on %I.%I to platform_ai',
                       coalesce('(' || (select string_agg(quote_ident(c), ', ')
                                        from unnest(coalesce(r.ai_columns, r.ro_columns)) c) || ')', ''),
                       src, r.table_name);
        if r.cdc then
            execute format('grant select %s on %I.%I to platform_cdc',
                           coalesce('(' || (select string_agg(quote_ident(c), ', ') from unnest(r.ro_columns) c) || ')', ''),
                           src, r.table_name);
        end if;
    end loop;

    -- 5. publication. 다른 계정의 권한을 보지 않고 허용 목록에서 바로 만든다. 있으면 SET TABLE로 목록을 통째로 바꾼다.
    select string_agg(format('%I.%I', schema_name, table_name)
                      || coalesce(' (' || (select string_agg(quote_ident(c), ', ') from unnest(ro_columns) c) || ')', ''),
                      ', ' order by table_name)
    into spec
    from axcore_allow
    where schema_name = src and cdc;
    if spec is null then
        raise exception 'CDC로 보낼 표가 허용 목록에 없다(%)', src;
    end if;
    -- 슬롯 확인. 논리 복제 슬롯은 바꾸기 전에 쌓인 WAL을 예전 publication 정의로 푼다. 그래서 슬롯이 있는 채 publication을 좁히면
    -- 그 사이 변경의 금지 컬럼이 나갈 수 있다. publication 정의가 실제로 바뀔 때만 본다(같은 정의로 다시 실행하는 것은 운영 중에도 된다).
    -- 시험 실행(axcore.dry_run=on)이면 경고만 하고, axcore.allow_slot=on이면 알고 넘어간다(migrate_allowlist.sql -v allow_slot=1).
    if exists (select 1 from pg_publication where pubname = 'axcore_cdc') then
        declare
            current_def text;
            target_def text;
            slots text;
        begin
            select string_agg(pt.tablename || '(' || array_to_string(
                       array(select a from unnest(pt.attnames) a order by a), ',') || ')', ' ' order by pt.tablename)
            into current_def
            from pg_publication_tables pt
            where pt.pubname = 'axcore_cdc';
            -- 컬럼 목록이 없는 표(ro_columns NULL)는 지금 컬럼 전부가 실린다. unnest(NULL)의 array()는 NULL이 아니라 빈 배열이라 case로 나눈다.
            select string_agg(a.table_name || '(' || array_to_string(
                       case when a.ro_columns is null
                            then array(select att.attname::text from pg_attribute att
                                       where att.attrelid = format('%I.%I', src, a.table_name)::regclass
                                         and att.attnum > 0 and not att.attisdropped and att.attgenerated = ''
                                       order by att.attname)
                            else array(select c from unnest(a.ro_columns) c order by c)
                       end, ',') || ')', ' ' order by a.table_name)
            into target_def
            from axcore_allow a
            where a.schema_name = src and a.cdc;
            select string_agg(slot_name, ', ' order by slot_name) into slots
            from pg_replication_slots
            where database = current_database() and slot_type = 'logical';
            if current_def is distinct from target_def and slots is not null then
                if coalesce(current_setting('axcore.allow_slot', true), '') = 'on' then
                    raise warning '슬롯 %이 있지만 axcore.allow_slot=on이라 publication을 바꾼다. 바꾸기 전 WAL은 예전 정의로 풀린다', slots;
                elsif coalesce(current_setting('axcore.dry_run', true), '') = 'on' then
                    raise warning '확정하면 멈춘다: publication axcore_cdc가 바뀌는데 논리 복제 슬롯 %이 있다. 커넥터를 멈추고 슬롯을 지운 뒤 확정한다', slots;
                else
                    raise exception 'publication axcore_cdc가 바뀌는데 이 DB에 논리 복제 슬롯 %이 있다. 바꾸기 전에 쌓인 WAL은 예전 정의로 풀려 금지 컬럼이 나갈 수 있다. 커넥터를 멈추고 슬롯을 지운 뒤 다시 실행한다(README "기존 서버에 적용"). 알고 바꿔야 하면 migrate_allowlist.sql을 -v allow_slot=1로 실행한다', slots;
                end if;
            end if;
        end;
    end if;
    if exists (select 1 from pg_publication where pubname = 'axcore_cdc') then
        execute 'alter publication axcore_cdc set table ' || spec;
    else
        execute 'create publication axcore_cdc for table ' || spec;
    end if;

    -- 6. 결과 확인. 계정 하나·컬럼 하나씩, 실제로 읽을 수 있는지(PUBLIC·상속·소유 포함)가 허용 목록과 정확히 같아야 한다.
    for r in
        with expected as (
            select a.table_name, rl.role_name,
                   case rl.role_name
                       when 'platform_ro' then a.ro_columns
                       when 'platform_ai' then coalesce(a.ai_columns, a.ro_columns)
                       else a.ro_columns
                   end as columns,
                   rl.role_name <> 'platform_cdc' or a.cdc as allowed
            from axcore_allow a
            cross join unnest(platform_roles) as rl(role_name)
            where a.schema_name = src
        )
        select c.relname, att.attname, rl.role_name,
               has_column_privilege(rl.role_name, c.oid, att.attnum, 'SELECT') as actual,
               coalesce(e.allowed and (e.columns is null or att.attname = any(e.columns)), false) as wanted
        from pg_class c
        join pg_attribute att on att.attrelid = c.oid and att.attnum > 0 and not att.attisdropped
        cross join unnest(platform_roles) as rl(role_name)
        left join expected e on e.table_name = c.relname and e.role_name = rl.role_name
        where c.relnamespace = src::regnamespace and c.relkind in ('r', 'p', 'v', 'm', 'f')
    loop
        if r.actual <> r.wanted then
            problems := problems || format('%s: %s.%s.%s SELECT가 %s', r.role_name, src, r.relname, r.attname,
                                           case when r.actual then '허용 목록 밖인데 된다' else '허용 목록인데 안 된다' end);
        end if;
    end loop;
    -- 정책 대조(위 확인과 겹치지만 정책을 기준으로 한 번 더 본다).
    for r in
        select p.entry, p.grade, rl.role_name, c.oid as rel, split_part(p.entry, '.', 3) as column_name
        from axcore_policy p
        join pg_class c on c.oid = to_regclass(format('%I.%I', src, split_part(p.entry, '.', 2)))
        cross join unnest(platform_roles) as rl(role_name)
        where split_part(p.entry, '.', 1) = src
          and (p.grade = 'FORBIDDEN' or rl.role_name = 'platform_ai')
    loop
        if exists (select 1 from pg_attribute att
                   where att.attrelid = r.rel and att.attnum > 0 and not att.attisdropped
                     and (r.column_name = '*' or att.attname = r.column_name)
                     and has_column_privilege(r.role_name, r.rel, att.attnum, 'SELECT')) then
            problems := problems || format('%s가 %s %s를 읽을 수 있다', r.role_name, r.grade, r.entry);
        end if;
    end loop;
    -- 쓰기 권한은 어느 표에도 없어야 한다.
    for r in
        select c.relname, rl.role_name
        from pg_class c cross join unnest(platform_roles) as rl(role_name)
        where c.relnamespace = src::regnamespace and c.relkind in ('r', 'p', 'v', 'm', 'f')
          and (has_table_privilege(rl.role_name, c.oid, 'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
               or has_any_column_privilege(rl.role_name, c.oid, 'INSERT, UPDATE, REFERENCES'))
    loop
        problems := problems || format('%s: %s.%s에 쓰기 권한이 있다', r.role_name, src, r.relname);
    end loop;
    -- 다른 역할을 물려받으면(pg_read_all_data 등) 표 권한과 상관없이 읽을 수 있다.
    for r in
        select m.member::regrole as member, m.roleid::regrole as granted
        from pg_auth_members m
        where m.member in (select oid from pg_roles where rolname = any(platform_roles))
    loop
        problems := problems || format('%s가 역할 %s를 물려받았다', r.member, r.granted);
    end loop;
    for r in
        select rolname from pg_roles
        where rolname = any(platform_roles)
          and (rolsuper or rolbypassrls or rolcreaterole or rolcreatedb or (rolreplication and rolname <> 'platform_cdc'))
    loop
        problems := problems || format('%s의 계정 속성이 넓다(superuser·bypassrls·createrole·createdb·replication)', r.rolname);
    end loop;
    for r in
        select d.defaclrole::regrole as owner, d.defaclobjtype, a.grantee::regrole as grantee
        from pg_default_acl d, aclexplode(d.defaclacl) a
        where a.grantee in (select oid from pg_roles where rolname = any(platform_roles))
    loop
        problems := problems || format('기본 권한이 남아 있다: %s가 만드는 %s를 %s가 받는다', r.owner, r.defaclobjtype, r.grantee);
    end loop;
    -- publication: 허용 목록의 CDC 표·컬럼과 정확히 같아야 하고, 스키마 단위 항목이 없어야 한다.
    for r in
        with expected as (
            select a.table_name,
                   coalesce(a.ro_columns,
                            (select array_agg(att.attname::text) from pg_attribute att
                             where att.attrelid = to_regclass(format('%I.%I', src, a.table_name))
                               and att.attnum > 0 and not att.attisdropped)) as columns
            from axcore_allow a where a.schema_name = src and a.cdc
        ), actual as (
            select pt.schemaname, pt.tablename, pt.attnames::text[] as columns
            from pg_publication_tables pt where pt.pubname = 'axcore_cdc'
        )
        select coalesce(x.schemaname || '.' || x.tablename, src || '.' || e.table_name) as name,
               e.columns as expected_columns, x.columns as actual_columns
        from expected e
        full join actual x on x.schemaname = src and x.tablename = e.table_name
        where e.table_name is null or x.tablename is null
           or not (e.columns @> x.columns and x.columns @> e.columns)
    loop
        problems := problems || format('publication %s: 기대 %s, 실제 %s', r.name, r.expected_columns, r.actual_columns);
    end loop;
    if exists (select 1 from pg_publication_namespace pn join pg_publication p on p.oid = pn.pnpubid
               where p.pubname = 'axcore_cdc') then
        problems := problems || 'publication axcore_cdc에 스키마 단위 항목이 있다'::text;
    end if;

    if cardinality(problems) > 0 then
        raise exception E'적용 결과가 허용 목록과 다르다(되돌림):\n%', array_to_string(problems, E'\n');
    end if;
    raise notice '허용 목록 적용 완료(%): 표 %개, CDC 표 %개', src,
        (select count(*) from axcore_allow where schema_name = src),
        (select count(*) from axcore_allow where schema_name = src and cdc);
end
$apply$;
