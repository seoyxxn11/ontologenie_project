package com.axcore.platform.audit.service;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 관리 기능이 변경 기록(entity_change)에 남기는 표 중 객체 속성 값이 없는 표(S15P31C106-404). 감사 가림은 허용 목록이라 ({@link
 * AuditValueMasker}) 여기나 기록하는 쪽이 알리지 않은 표는 값이 가려진다. 아래 표는 정의·계정·원천 설정·후보 검토·관계 상태라 행 전체를 보여 준다.
 *
 * <p>정의 표에는 관리자가 적은 원천 리터럴(매핑의 row_filter, 속성의 constraints, 속성 매핑의 transform_steps 등)이 담길 수 있다. 객체
 * 속성 값이 아니라 정의이므로 그대로 보여 준다.
 *
 * <p>여기 넣는 표는 행에 객체 속성 값이 들어가지 않는다는 뜻이다. 표에 속성 값을 담기 시작하면 이 목록에서 빼고 그 쪽에서 자리를 알린다.
 */
@Component
class ManagementRecordedTables implements RecordedPropertyPaths {

    /** 객체 값이 없는 관리 표. */
    static final List<String> PLAIN_TABLES =
            List.of(
                    // 계정 관리(005·007·103). 비밀번호는 기록할 때 빠진다.
                    "account",
                    // Action 정의 관리(088·090)
                    "action_definition",
                    // 원천별 수집 주기(072)
                    "data_source",
                    // 온톨로지 정의 편집·후보 적용
                    "object_type",
                    "property_definition",
                    "link_type",
                    "object_source_mapping",
                    "property_source_mapping",
                    "link_mapping",
                    // 후보 검토(051·054)
                    "proposal_item",
                    "proposal_revision",
                    "review_decision",
                    // 관계 확정(058·문서 연결 적용). 관계 유형·양 끝 ID·출처·상태만 남긴다. 근거(evidence)는 기록하지 않는다.
                    "object_link");

    @Override
    public List<Path> recordedPropertyPaths() {
        return PLAIN_TABLES.stream().map(table -> Path.plainChange(table, List.of())).toList();
    }
}
