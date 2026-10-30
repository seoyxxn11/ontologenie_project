package com.axcore.platform.review.repository;

import com.axcore.platform.review.dto.LatestCheckResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProposalLatestCheckRepository {

    private final JdbcClient jdbcClient;

    public Map<Long, LatestCheckResponse> findLatestByProposalRevisions(
            Map<Long, Integer> proposalRevisions) {

        if (proposalRevisions.isEmpty()) {
            return Map.of();
        }

        StringJoiner targets = new StringJoiner(", ");
        Map<String, Object> parameters = new HashMap<>();
        int index = 0;

        for (Map.Entry<Long, Integer> entry : proposalRevisions.entrySet()) {
            targets.add(
                    "(cast(:proposalId%d as bigint), cast(:revision%d as integer))"
                            .formatted(index, index));

            parameters.put("proposalId" + index, entry.getKey());
            parameters.put("revision" + index, entry.getValue());
            index++;
        }

        String sql =
                """
                with targets (proposal_item_id, revision) as (
                    values %s
                )
                select distinct on (target.proposal_item_id)
                    target.proposal_item_id,
                    run.id as check_run_id,
                    run.status,
                    case when run.status = 'SUCCEEDED' then result.matched_count end as matched_count,
                    case when run.status = 'SUCCEEDED' then result.total_count end as total_count
                from targets target
                join check_run run
                    on run.trigger_kind = 'CANDIDATE'
                    and run.scope @> jsonb_build_object(
                        'proposalItems', jsonb_build_array(jsonb_build_object(
                            'proposalItemId', target.proposal_item_id,
                            'revision', target.revision)))
                left join check_result result
                    on result.check_run_id = run.id
                    and result.proposal_item_id = target.proposal_item_id
                    and result.proposal_item_revision = target.revision
                order by target.proposal_item_id,
                    run.requested_at desc, run.id desc, result.id desc
                """
                        .formatted(targets);

        return jdbcClient
                .sql(sql)
                .params(parameters)
                .query(
                        (rs, rowNum) -> {
                            Integer matchedCount = rs.getObject("matched_count", Integer.class);
                            Integer totalCount = rs.getObject("total_count", Integer.class);

                            BigDecimal matchRate = null;

                            if (matchedCount != null && totalCount != null && totalCount > 0) {

                                matchRate =
                                        BigDecimal.valueOf(matchedCount)
                                                .divide(
                                                        BigDecimal.valueOf(totalCount),
                                                        4,
                                                        RoundingMode.HALF_UP);
                            }

                            LatestCheckResponse response =
                                    new LatestCheckResponse(
                                            rs.getLong("check_run_id"),
                                            rs.getString("status"),
                                            matchedCount,
                                            totalCount,
                                            matchRate);

                            return Map.entry(rs.getLong("proposal_item_id"), response);
                        })
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
