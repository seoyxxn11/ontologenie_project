-- =====================================================================
-- V22: 쓰지 않는 보류 건(held_item)을 걷어 낸다 (S15P31C106-377)
--   366부터 규칙이 잇지 못한 행은 보류 건으로 남기지 않고 SYNC 검사가 문제(issue)로만 기록한다. 개별 수동 연결은 문제에서 정한다
--   (238, API 058 재정의, confirmed-link). 그래서 보류 건 표, 문제의 보류 건 참조, 보류 건 복합 FK만을 위한 고유 제약을 지운다.
--   남은 행은 옮기지 않는다(서버 데이터는 다시 제안·적재하면서 초기화한다).
--   제안 단위의 보류(review_decision HOLD, proposal_item.review_status = 'HELD')는 다른 개념이라 그대로 둔다.
-- =====================================================================

ALTER TABLE issue DROP COLUMN held_item_id;

DROP TABLE held_item;

-- 보류 건의 승인 연결 복합 FK(fk_held_item_resolved_link)만 이 제약을 썼다.
ALTER TABLE object_link DROP CONSTRAINT uq_object_link_source;

COMMENT ON TABLE issue IS '정합성 문제. 상태: 신규 → 확인중 → 조치완료·재검증대기 → 해결(FN-ISS-03). 담당자 배정 없음. 규칙이 잇지 못한 출발도 문제로 기록하고 수동 연결은 문제에서 정한다(366·238, 보류 건은 V22에서 삭제). DB 보장: 해결이면 이 문제를 그 검사에서 검증해 PASSED한 기록이 있음. Handler 보장: 그 검사가 REVERIFY·SUCCEEDED이고 최신 수정 자료를 대상으로 했음. 해결로 바꾸는 것은 관리자다(2026-10-27 결정, 시나리오 S5-6): 재검증이 통과해도 자동으로 해결되지 않는다. 원천 사이 규칙의 미연결은 확인 대기(link_check_pending)에서 승격된 것만 issue가 된다(V12)';
COMMENT ON TABLE object_link IS '확정된 객체 간 연결. 조회는 status=ACTIVE만. 출처는 RULE(규칙)·MANUAL(문제에서 관리자가 정함)·DOCUMENT(문서 근거). 검토 중 후보는 운영 ERD. 양 끝 객체 유형이 관계 유형과 맞는지는 Handler가 검사';
