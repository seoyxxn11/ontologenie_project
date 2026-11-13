-- 원천 플랫폼 계정의 허용 목록(S15P31C106-395). 자료만 있고 권한은 바꾸지 않는다.
-- setup_roles.sql(새 설치·재실행)과 migrate_allowlist.sql(기존 서버 변경)이 한 트랜잭션 안에서 \ir로 읽고,
-- apply_allowlist.sql이 이 표대로 권한과 publication을 만든다. 임시 표라 트랜잭션이 끝나면 사라진다.

-- 1. 원천 데이터 정책. backend/src/main/resources/application.yml의 axcore.source-data-policy와 글자까지 같아야 한다
--    (infra/tests/test_source_allowlist.py가 비교한다). "스키마.표.*"는 표 전체다.
--    FORBIDDEN(금지): 어떤 플랫폼 계정도 읽지 못하고 CDC에도 넣지 않는다.
--    RESTRICTED(제한): 플랫폼은 읽되(PLANNER만 열람), 모델로 보내지 않으므로 platform_ai는 읽지 못한다.
create temp table axcore_policy (
    entry text primary key,
    grade text not null check (grade in ('FORBIDDEN', 'RESTRICTED'))
) on commit drop;

insert into axcore_policy (entry, grade) values
    ('erp.attendance.*', 'FORBIDDEN'),
    ('erp.fixed_asset.*', 'FORBIDDEN'),
    ('erp.employee.emp_name', 'FORBIDDEN'),
    ('erp.employee.position', 'FORBIDDEN'),
    ('erp.employee.hire_date', 'FORBIDDEN'),
    ('erp.employee.employment_type', 'FORBIDDEN'),
    ('erp.employee.status', 'FORBIDDEN'),
    ('mes.worker.name', 'FORBIDDEN'),
    ('mes.maintenance_order.technician', 'RESTRICTED'),
    ('erp.vendor.contact_name', 'RESTRICTED'),
    ('mes.worker.skill_level', 'RESTRICTED');

-- 2. 허용 목록. 여기 없는 표는 어떤 플랫폼 계정에도 권한이 없다(기본 권한도 주지 않는다).
--    ro_columns: platform_ro·platform_cdc가 읽는 컬럼. NULL이면 표 전체이고, 나중에 추가되는 컬럼도 따라온다.
--                금지 컬럼이 있는 표는 반드시 컬럼을 적는다. 그러면 새 컬럼은 여기 적기 전까지 아무도 못 읽는다.
--    ai_columns: platform_ai가 읽는 컬럼. NULL이면 ro_columns와 같다. 제한 컬럼이 있는 표는 반드시 적는다.
--    cdc: publication axcore_cdc에 넣는지. 넣으면 ro_columns가 publication의 컬럼 목록이 된다.
create temp table axcore_allow (
    schema_name text not null,
    table_name text not null,
    ro_columns text[],
    ai_columns text[],
    cdc boolean not null,
    primary key (schema_name, table_name)
) on commit drop;

insert into axcore_allow (schema_name, table_name, ro_columns, ai_columns, cdc) values
    -- ERP
    ('erp', 'item', null, null, true),
    ('erp', 'vendor', null,
        '{vendor_code,vendor_name,biz_no,vendor_type,payment_terms,use_yn}', true),
    ('erp', 'purchase_order', null, null, true),
    ('erp', 'purchase_order_line', null, null, true),
    ('erp', 'goods_receipt', null, null, true),
    ('erp', 'stock', null, null, true),
    ('erp', 'sales_order', null, null, true),
    ('erp', 'sales_order_line', null, null, true),
    ('erp', 'shipment', null, null, true),
    ('erp', 'employee', '{emp_no,dept_code}', null, true),
    ('erp', 'department', null, null, true),
    -- 원천 변경 이력 표. CDC를 쓰면 필요 없어 publication에서 뺀다.
    ('erp', 'change_log', null, null, false),
    -- MES
    ('mes', 'equipment', null, null, true),
    ('mes', 'worker', '{id,shift,line,skill_level,active_yn}', '{id,shift,line,active_yn}', true),
    ('mes', 'routing', null, null, true),
    ('mes', 'work_order', null, null, true),
    ('mes', 'work_order_operation', null, null, true),
    ('mes', 'production_result', null, null, true),
    ('mes', 'material_issue', null, null, true),
    ('mes', 'downtime', null, null, true),
    ('mes', 'sensor_reading', null, null, true),
    ('mes', 'defect', null, null, true),
    ('mes', 'inspection', null, null, true),
    ('mes', 'die', null, null, true),
    ('mes', 'maintenance_order', null,
        '{mo_no,target_type,target_code,mo_type,status,planned_date,completed_at,parts_cost,note}', true),
    ('mes', 'handover_note', null, null, true),
    ('mes', 'change_log', null, null, false);

-- 3. 정책 밖의 이유로 일부러 뺀 표. 권한은 위 허용 목록이 정하고, 이 표는 "결정 안 된 표" 알림에서 빼는 데만 쓴다.
create temp table axcore_excluded (
    schema_name text not null,
    table_name text not null,
    reason text not null,
    primary key (schema_name, table_name)
) on commit drop;

insert into axcore_excluded (schema_name, table_name, reason) values
    ('erp', 'sales_invoice', '회계 표 회수(S15P31C106-133)'),
    ('erp', 'purchase_invoice', '회계 표 회수(S15P31C106-133)'),
    ('erp', 'gl_account', '회계 표 회수(S15P31C106-133)'),
    ('erp', 'journal_entry', '회계 표 회수(S15P31C106-133)');
