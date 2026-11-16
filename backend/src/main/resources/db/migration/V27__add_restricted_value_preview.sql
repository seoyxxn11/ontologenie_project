-- =====================================================================
-- V27: 제안 검토 때 제한 컬럼의 실제 값 확인 (Jira S15P31C106-447, 384 보완)
--   제한(민감) 컬럼은 AI에 값 없이 이름·자료형·주석만 넘어가고(416) 제안 근거에도 값이 남지 않는다(399). 관리자는 그 추측만 보고 승인했다.
--   제안을 검토하는 관리자가 그 컬럼의 서로 다른 값 몇 개를 행과 떼어서 볼 수 있게 source_preview에 작업 종류 하나를 더한다.
--   - RESTRICTED_VALUES: request = {proposalId, column}. 배치가 SYNC 계정으로 서로 다른 값 row_limit개와 건수만 읽어 result에 채운다.
--     키 컬럼·같은 행의 다른 컬럼은 읽지 않는다.
--   - 결과는 요청한 관리자가 한 번 읽으면 지우고(열람 기록을 남긴 뒤), 읽지 않아도 expires_at(요청 10분 뒤)에 정리 작업이 지운다.
--   - 원천 미리보기 조회(API 102)는 이 종류를 돌려주지 않는다(열람 기록 없이 값이 나가지 않게).
-- =====================================================================

ALTER TABLE source_preview DROP CONSTRAINT ck_source_preview_kind;
ALTER TABLE source_preview
    ADD CONSTRAINT ck_source_preview_kind
        CHECK (preview_kind IN ('SAMPLE', 'MAPPING_PREVIEW', 'RESTRICTED_VALUES'));

ALTER TABLE source_preview DROP CONSTRAINT ck_source_preview_request;
ALTER TABLE source_preview
    ADD CONSTRAINT ck_source_preview_request
        CHECK (jsonb_typeof(request) = 'object'
               AND (preview_kind <> 'MAPPING_PREVIEW' OR request ? 'mapping')
               AND (preview_kind <> 'RESTRICTED_VALUES' OR (request ? 'proposalId' AND request ? 'column')));

COMMENT ON COLUMN source_preview.preview_kind IS 'SAMPLE: 원천 표본(016) / MAPPING_PREVIEW: 매핑 미리보기(040) / RESTRICTED_VALUES: 제안 검토 때 제한 컬럼의 서로 다른 값(447)';
