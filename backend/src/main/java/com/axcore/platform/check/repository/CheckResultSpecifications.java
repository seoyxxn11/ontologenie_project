package com.axcore.platform.check.repository;

import com.axcore.platform.check.domain.CheckResult;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** 검사 실행 범위를 고정하고, 전달된 규칙·후보 필터를 AND로 결합한다. */
public final class CheckResultSpecifications {

    private CheckResultSpecifications() {}

    public static Specification<CheckResult> byFilters(
            long checkRunId, Long linkMappingId, Long proposalItemId) {

        return (root, query, criteriaBuilder) -> {
            List<Predicate> conditions = new ArrayList<>();

            // 다른 검사 실행의 결과가 섞이지 않도록 항상 적용한다.
            conditions.add(criteriaBuilder.equal(root.get("checkRunId"), checkRunId));

            // 승인 규칙 ID가 전달되면 해당 규칙의 결과만 조회한다.
            if (linkMappingId != null) {
                conditions.add(criteriaBuilder.equal(root.get("linkMappingId"), linkMappingId));
            }

            // 후보 ID가 전달되면 해당 후보의 결과만 조회한다.
            if (proposalItemId != null) {
                conditions.add(criteriaBuilder.equal(root.get("proposalItemId"), proposalItemId));
            }

            return criteriaBuilder.and(conditions.toArray(new Predicate[0]));
        };
    }
}
