package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalItem;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class ProposalItemSpecifications {

    private ProposalItemSpecifications() {}

    public static Specification<ProposalItem> byFilters(
            String origin, String itemKind, String reviewStatus, String applyStatus) {

        return (root, query, criteriaBuilder) -> {
            List<Predicate> conditions = new ArrayList<>();

            if (origin != null) {
                conditions.add(criteriaBuilder.equal(root.get("origin"), origin));
            }

            if (itemKind != null) {
                conditions.add(criteriaBuilder.equal(root.get("itemKind"), itemKind));
            }

            if (reviewStatus != null) {
                conditions.add(criteriaBuilder.equal(root.get("reviewStatus"), reviewStatus));
            }

            if (applyStatus != null) {
                conditions.add(criteriaBuilder.equal(root.get("applyStatus"), applyStatus));
            }

            return criteriaBuilder.and(conditions.toArray(Predicate[]::new));
        };
    }
}
