-- =====================================================================
-- V5 원천 수집을 로그 기반 CDC로 바꾼다 (S15P31C106-266, 2026-10-30 확정)
-- 원천 PostgreSQL WAL → Debezium(Kafka Connect) → Kafka 토픽 → 배치(소비자) → 플랫폼 DB.
-- 원천에는 변경 이력 테이블·트리거를 만들지 않는다. wal_level=logical, CDC 복제 계정, publication만 둔다.
-- 그래서 원천 변경 번호(change_seq) 체크포인트를 없애고, 원천 테이블마다 수집 방식을 둔다.
--   CDC          : 테이블 변경을 이벤트로 받는다. 원천 PostgreSQL 테이블의 기본 방식
--   FULL_COMPARE : 테이블 전체를 SELECT로 읽고 저장된 값과 비교해 바뀐 행만 쓴다. 이번에 안 나온 키는 DELETED_AT_SOURCE.
--                  뷰처럼 WAL이 없는 자원은 늘 이 방식이다. CDC 테이블도 첫 적재·매핑 변경·장애 복구 때 이 방식으로 전체를 다시 읽는다
-- 순서 보장: 전체 읽기 직전 LSN을 source_resource.loaded_lsn에, 객체마다 마지막으로 반영한 LSN을 object_instance.source_lsn에 둔다.
--   이보다 앞선 변경은 반영하지 않으므로, 전체 읽기와 CDC 이벤트가 어떤 순서로 도착해도 값이 되돌아가지 않는다.
-- LSN은 pg_lsn을 숫자로 바꾼 값(pg_current_wal_lsn() - '0/0'::pg_lsn, Debezium 이벤트의 source.lsn과 같은 값)을 bigint로 저장한다.
-- 8절은 CDC와 별개로, 10-30에 정한 정의·검사 규칙을 주석에 맞춘다.
-- =====================================================================

-- 1. 원천 테이블: 변경 번호 체크포인트 → 수집 방식과 전체 읽기 기준 LSN
ALTER TABLE source_resource DROP CONSTRAINT ck_source_resource_seq;
ALTER TABLE source_resource
    DROP COLUMN initial_loaded_at,         -- 적재 여부는 loaded_lsn(CDC)과 수집 기록(sync_run_resource)으로 안다
    DROP COLUMN last_applied_change_seq;
ALTER TABLE source_resource
    ADD COLUMN sync_strategy varchar(20) NOT NULL DEFAULT 'FULL_COMPARE',
    ADD COLUMN loaded_lsn    bigint,
    ADD CONSTRAINT ck_source_resource_strategy CHECK (sync_strategy IN ('CDC', 'FULL_COMPARE')),
    ADD CONSTRAINT ck_source_resource_cdc_kind CHECK (sync_strategy <> 'CDC' OR resource_kind = 'TABLE'),   -- 뷰·문서는 WAL이 없다
    ADD CONSTRAINT ck_source_resource_lsn      CHECK (loaded_lsn IS NULL OR (sync_strategy = 'CDC' AND loaded_lsn >= 0));

COMMENT ON COLUMN source_resource.sync_strategy IS 'CDC(변경 이벤트로 반영) / FULL_COMPARE(전체를 읽고 저장값과 비교). CDC는 원천 PostgreSQL의 TABLE만 가능하다(원천 종류는 배치가 검사). 같은 테이블의 매핑은 모두 이 방식을 따른다';
COMMENT ON COLUMN source_resource.loaded_lsn IS 'CDC 테이블을 마지막으로 전체 읽기 직전의 원천 LSN. NULL이면 아직 전체 읽기 전이라 이 테이블의 CDC 이벤트를 버린다. 이보다 앞선 이벤트도 버린다. 매핑을 바꾸면 NULL로 되돌리고 다시 전체 읽기를 요청한다';

-- 2. 수집 실행: CDC 소비 묶음도 수집 실행 하나로 남긴다
ALTER TABLE sync_run
    DROP CONSTRAINT ck_sync_run_trigger,
    DROP CONSTRAINT ck_sync_run_requester;
ALTER TABLE sync_run
    ADD CONSTRAINT ck_sync_run_trigger   CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'ON_DEMAND', 'CDC')),
    ADD CONSTRAINT ck_sync_run_requester CHECK (trigger_kind IN ('SCHEDULED', 'CDC') OR requested_by IS NOT NULL);

COMMENT ON TABLE sync_run IS '원천 수집 한 번(FN-BAT-02~04). CDC = 소비자가 한 트랜잭션으로 반영한 이벤트 묶음 하나(변경이 없으면 만들지 않음). MANUAL = 관리자 요청·매핑 변경 뒤 전체 다시 읽기, SCHEDULED = FULL_COMPARE 원천의 주기 수집, ON_DEMAND = Agent 질문에 필요한 FULL_COMPARE 테이블만. CDC 외에는 작업 표 방식이다: 백엔드가 QUEUED로 넣고 배치가 가져가 RUNNING으로 바꾼다. 테이블별 구간·건수는 sync_run_resource';

-- 3. 테이블별 수집 기록: 변경 번호 구간 → LSN 구간
ALTER TABLE sync_run_resource DROP CONSTRAINT ck_sync_run_resource_range;
ALTER TABLE sync_run_resource
    DROP COLUMN from_change_seq,
    DROP COLUMN to_change_seq;
ALTER TABLE sync_run_resource
    ADD COLUMN from_lsn bigint,
    ADD COLUMN to_lsn   bigint,
    ADD CONSTRAINT ck_sync_run_resource_lsn   CHECK ((from_lsn IS NULL OR from_lsn >= 0) AND (to_lsn IS NULL OR to_lsn >= 0)),
    ADD CONSTRAINT ck_sync_run_resource_range CHECK (from_lsn IS NULL OR to_lsn IS NULL OR to_lsn >= from_lsn);

COMMENT ON TABLE sync_run_resource IS '수집 실행의 테이블별 방식·구간·건수·상태. FULL = 전체 읽기, INCREMENTAL = CDC 이벤트 반영. CDC 묶음의 행은 반영이 끝난 상태(SUCCEEDED)로 바로 넣으므로 같은 테이블의 전체 읽기와 대기·실행 중 유일 조건이 겹치지 않는다. SKIPPED = 잠금을 받고 다시 확인했더니 그 사이 다른 실행이 이미 읽었음(중복 방지, A4)';
COMMENT ON COLUMN sync_run_resource.mode IS 'FULL = 테이블 전체 읽기(첫 적재·매핑 변경·복구·FULL_COMPARE), INCREMENTAL = CDC 이벤트 반영';
COMMENT ON COLUMN sync_run_resource.from_lsn IS 'INCREMENTAL: 이 묶음에서 반영한 첫 이벤트의 LSN. FULL이면 NULL';
COMMENT ON COLUMN sync_run_resource.to_lsn IS 'INCREMENTAL: 이 묶음에서 반영한 마지막 이벤트의 LSN. FULL(CDC 테이블): 읽기 직전 LSN(source_resource.loaded_lsn이 되는 값)';

-- 4. 객체: 마지막으로 반영한 원천 변경 위치
ALTER TABLE object_instance
    ADD COLUMN source_lsn bigint,
    ADD CONSTRAINT ck_object_instance_lsn CHECK (source_lsn IS NULL OR (source_mapping_id IS NOT NULL AND source_lsn >= 0));

COMMENT ON COLUMN object_instance.source_lsn IS 'CDC 테이블에서 이 객체에 마지막으로 반영한 원천 LSN(이벤트의 LSN, 전체 읽기면 그 읽기 직전 LSN). 이보다 앞선 변경은 반영하지 않는다. FULL_COMPARE 테이블과 플랫폼 객체는 NULL';
COMMENT ON COLUMN object_instance.last_synced_at IS '내용이 마지막으로 바뀐(추가·수정·삭제 표시) 시각. 안 바뀐 행은 수집해도 건드리지 않는다. 테이블을 마지막으로 읽은 시각은 sync_run_resource. 업무 값의 기준일은 temporal_role=AS_OF 속성으로 따로 표시한다';
COMMENT ON COLUMN object_instance.last_sync_run_id IS '내용을 마지막으로 바꾼 수집 실행. 같은 원천의 실행인지는 배치가 검사한다';

-- 5. CDC 소비 위치: 소비자가 다음에 읽을 토픽 위치. 객체 반영과 같은 트랜잭션에서만 전진한다
CREATE TABLE cdc_offset (
    topic          varchar(249) NOT NULL,    -- Kafka 토픽 이름 최대 길이
    partition_no   integer      NOT NULL,
    data_source_id bigint       NOT NULL REFERENCES data_source (id) ON DELETE CASCADE,
    next_offset    bigint       NOT NULL,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (topic, partition_no),
    CONSTRAINT ck_cdc_offset_partition CHECK (partition_no >= 0),
    CONSTRAINT ck_cdc_offset_next      CHECK (next_offset >= 0)
);
CREATE INDEX ix_cdc_offset_source ON cdc_offset (data_source_id);
COMMENT ON TABLE cdc_offset IS 'CDC 소비자의 체크포인트. 토픽 이름은 {data_source.code}.{스키마}.{테이블}. 소비자는 재시작하면 Kafka에 커밋한 위치가 아니라 이 값으로 위치를 잡는다. 이 값이 토픽 보관 기간을 넘었거나 원천 복제 슬롯이 깨졌으면 그 원천의 CDC 테이블을 전체 다시 읽어 복구한다. Debezium이 원천 WAL을 어디까지 읽었는지는 Kafka Connect가 관리한다';
COMMENT ON COLUMN cdc_offset.updated_at IS '마지막으로 반영한 시각. 소비 지연(질문 답변의 기준 시점) 표시에 쓴다';

-- 6. 분석 대상: 재분석은 고른 테이블 전체를 다시 분석한다(변경 구간 없음)
ALTER TABLE analysis_run_target DROP CONSTRAINT ck_analysis_run_target_range;
ALTER TABLE analysis_run_target
    DROP COLUMN change_seq_from,
    DROP COLUMN change_seq_to;

COMMENT ON TABLE analysis_run IS 'AI 분석·추출·후보 생성 실행. REANALYSIS = 원천이 바뀐 뒤 고른 테이블을 다시 분석(테이블 전체). 같은 work_key가 대기·실행 중이면 새로 만들지 않고 합류한다(A4)';
COMMENT ON COLUMN analysis_run.work_key IS '[A4] 원천 + 대상 테이블·문서 + 고른 컬럼(없으면 전체)을 정규화한 키. 같은 키가 대기·실행 중이면 합류';
COMMENT ON TABLE analysis_run_target IS '관리자가 고른 분석 대상 표·문서·메모(FN-SRC-03)';

-- 7. 원천 변경 이력을 전제로 한 주석 고치기
COMMENT ON TABLE data_source IS '원천 시스템(ERP, MES 등)과 연결 상태. 수집 방식은 원천 테이블마다(source_resource.sync_strategy), CDC 체크포인트는 cdc_offset. 자동 수집 주기는 FULL_COMPARE 테이블에만 쓴다';
COMMENT ON COLUMN data_source.source_kind IS 'POSTGRES = 원천 DB 직접 접속(보안 그룹·pg_hba는 플랫폼 서버 IP만 허용). 스키마는 information_schema로 읽고, 변경은 Debezium이 WAL에서 읽는다(V5). 원천에 변경 이력 테이블을 만들지 않는다';
COMMENT ON TABLE data_source_credential IS '원천 접속 계정. 비밀번호는 암호문만 저장하고 화면·API·로그에는 내보내지 않는다(A1, B10). SYNC = 조회 전용(전체 읽기·연결 검사), AI = 분석. CDC 복제 계정은 Kafka Connect가 쓰므로 여기 두지 않고 서버 .env·config provider로만 넘긴다(V5)';
COMMENT ON TABLE object_source_mapping IS '원천 자원의 행에서 어떤 객체 유형을 만들지와 원천 키. 관리자가 직접 편집한다(2026-10-27). 객체가 만들어진 뒤에는 키 컬럼을 바꿀 수 없다(트리거). 컬럼·변환·행 조건을 바꾸면 그 테이블을 전체 다시 읽어 새 매핑을 적용한다(CDC 테이블은 loaded_lsn을 비운다)';
COMMENT ON COLUMN property_definition.temporal_role IS 'AS_OF=이 객체 값의 업무 기준일(예: 장부가 기준일), EVENT_TIME=사건 발생 시각. 답변의 기준 시점은 이 속성으로, 수집 시점은 객체의 last_synced_at과 그 원천의 소비 지연(cdc_offset.updated_at)으로 구분한다';
COMMENT ON COLUMN check_run.data_basis IS '[A4] 시작 시점의 기준: 관계된 원천 테이블별 마지막 성공 수집 실행 id. 같은 기준이면 성공한 결과를 다시 쓴다';

-- 8. 2026-10-30에 정한 정의·검사 규칙을 주석에 맞춘다(구조 변경 없음, S15P31C106-178·179)
COMMENT ON TABLE check_result IS '규칙별 시험 적용 결과. 검사한 원천(출발) 행마다 하나의 분류: 정상·미연결(대상 0개)·중복·조건 위반. 겹치면 조건 위반 > 중복 > 미연결 > 정상 순. 정상과 중복은 규칙이 속한 link_type.cardinality의 도착 쪽으로 가른다: 도착이 ONE(ONE_TO_ONE·MANY_TO_ONE)이면 대상 1개가 정상이고 2개 이상은 중복, 도착이 MANY(ONE_TO_MANY·MANY_TO_MANY)면 대상 1개 이상이 정상. 원천 키 중복은 언제나 중복. 건수는 모두 출발 행 수이고 만들어지는 연결 수는 저장하지 않는다(필요하면 object_link를 센다). 승인 전 후보 매칭률은 matched_count/total_count로 계산한다(원천 전체 행 기준). 검사 결과에는 AI 신뢰도를 넣지 않고, 신뢰도는 검토 화면에서 proposal_revision.ai_confidence를 별도 칸으로 보여준다. 둘을 합친 점수는 만들지 않는다 [결정 O1]';
COMMENT ON COLUMN link_type.cardinality IS '출발 기준으로 읽는다(MANY_TO_ONE = 출발 여럿이 도착 하나). 정합성 검사는 도착 쪽(ONE / MANY)으로 정상과 중복을 가른다(check_result). 바꿔도 기존 연결이 새 관계 수를 지키는지는 검사하지 않는다(MVP)';
COMMENT ON COLUMN property_definition.constraints IS '네 항목만 받는다: min·max(INTEGER·DECIMAL 범위, 경계 포함), maxLength(STRING 최대 글자 수, 1 이상), allowedValues(허용값 배열, JSON·BOOLEAN 제외). 다른 키, 자료형에 맞지 않는 항목, min > max는 API가 거부한다';
COMMENT ON COLUMN object_instance.properties IS '속성 코드 → 값. 자료형별 형식: STRING 문자열, INTEGER 정수, DECIMAL 숫자 또는 숫자 문자열, DATE YYYY-MM-DD, TIME HH:MM[:SS], DATETIME 시간대가 붙은 ISO 8601 문자열, BOOLEAN 참거짓, JSON 아무 값. 속성 정의를 바꿀 때 기존 값과 이 형식으로 대조한다';
