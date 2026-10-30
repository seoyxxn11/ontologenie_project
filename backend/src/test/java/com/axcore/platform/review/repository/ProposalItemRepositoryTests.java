package com.axcore.platform.review.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.review.domain.ProposalItem;
import java.sql.Types;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ProposalItemRepositoryTests {

    @Autowired ProposalItemRepository repository;

    @Autowired JdbcClient jdbcClient;

    @Test
    void findsProposalById() {
        Long proposalId = insertManualProposal();

        ProposalItem proposal = repository.findById(proposalId).orElseThrow();

        assertThat(proposal.getId()).isEqualTo(proposalId);
        assertThat(proposal.getOrigin()).isEqualTo("MANUAL");
        assertThat(proposal.getItemKind()).isEqualTo("LINK_RULE");
        assertThat(proposal.getRevision()).isEqualTo(1);
        assertThat(proposal.getReviewStatus()).isEqualTo("PENDING");
        assertThat(proposal.getApplyStatus()).isEqualTo("NOT_APPLIED");

        assertThat(proposal.getPayload()).containsEntry("linkTypeId", 5);
        assertThat(proposal.getEvidence()).containsEntry("reason", "테스트용 근거");

        assertThat(proposal.getAnalysisRunId()).isNull();
        assertThat(proposal.getAiConfidence()).isNull();
        assertThat(proposal.getAppliedAt()).isNull();
        assertThat(proposal.getCreatedAt()).isNotNull();
        assertThat(proposal.getRowVersion()).isZero();
    }

    @Test
    void returnsEmptyWhenProposalDoesNotExist() {
        assertThat(repository.findById(-1L)).isEmpty();
    }

    @Test
    void findsProposalsByPage() {
        Long firstId = insertManualProposal();
        Long secondId = insertManualProposal();
        Long thirdId = insertManualProposal();

        Page<ProposalItem> firstPage =
                repository.findAll(PageRequest.of(0, 2, Sort.by("id").ascending()));

        Page<ProposalItem> secondPage =
                repository.findAll(PageRequest.of(1, 2, Sort.by("id").ascending()));

        assertThat(firstPage.getContent())
                .extracting(ProposalItem::getId)
                .containsExactly(firstId, secondId);
        assertThat(firstPage.getNumber()).isZero();
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();

        assertThat(secondPage.getContent())
                .extracting(ProposalItem::getId)
                .containsExactly(thirdId);
        assertThat(secondPage.getNumber()).isEqualTo(1);
        assertThat(secondPage.getTotalElements()).isEqualTo(3);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    void combinesAllFilters() {
        Long expectedId = insertProposal("MANUAL", "LINK_RULE", "PENDING", "NOT_APPLIED");

        insertProposal("AI", "LINK_RULE", "PENDING", "NOT_APPLIED");
        insertProposal("MANUAL", "OBJECT_TYPE", "PENDING", "NOT_APPLIED");
        insertProposal("MANUAL", "LINK_RULE", "HELD", "NOT_APPLIED");
        insertProposal("MANUAL", "LINK_RULE", "PENDING", "FAILED");

        Page<ProposalItem> result =
                repository.findAll(
                        ProposalItemSpecifications.byFilters(
                                "MANUAL", "LINK_RULE", "PENDING", "NOT_APPLIED"),
                        PageRequest.of(0, 20, Sort.by("id").ascending()));

        assertThat(result.getContent()).extracting(ProposalItem::getId).containsExactly(expectedId);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void returnsAllWhenFiltersAreAbsent() {
        Long manualId = insertManualProposal();
        Long aiId = insertProposal("AI", "LINK_RULE", "PENDING", "NOT_APPLIED");

        Page<ProposalItem> result =
                repository.findAll(
                        ProposalItemSpecifications.byFilters(null, null, null, null),
                        PageRequest.of(0, 20, Sort.by("id").ascending()));

        assertThat(result.getContent())
                .extracting(ProposalItem::getId)
                .containsExactly(manualId, aiId);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void returnsEmptyWhenNoProposalMatchesFilters() {
        insertManualProposal();

        Page<ProposalItem> result =
                repository.findAll(
                        ProposalItemSpecifications.byFilters(null, null, "HELD", null),
                        PageRequest.of(0, 20, Sort.by("id").ascending()));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void pagesOnlyProposalsMatchingFilters() {
        Long firstId = insertManualProposal();

        insertProposal("AI", "LINK_RULE", "PENDING", "NOT_APPLIED");

        Long secondId = insertManualProposal();
        Long thirdId = insertManualProposal();

        Page<ProposalItem> firstPage =
                repository.findAll(
                        ProposalItemSpecifications.byFilters("MANUAL", null, null, null),
                        PageRequest.of(0, 2, Sort.by("id").ascending()));

        Page<ProposalItem> secondPage =
                repository.findAll(
                        ProposalItemSpecifications.byFilters("MANUAL", null, null, null),
                        PageRequest.of(1, 2, Sort.by("id").ascending()));

        assertThat(firstPage.getContent())
                .extracting(ProposalItem::getId)
                .containsExactly(firstId, secondId);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();

        assertThat(secondPage.getContent())
                .extracting(ProposalItem::getId)
                .containsExactly(thirdId);
        assertThat(secondPage.getTotalElements()).isEqualTo(3);
        assertThat(secondPage.hasNext()).isFalse();
    }

    private Long insertManualProposal() {
        return insertProposal("MANUAL", "LINK_RULE", "PENDING", "NOT_APPLIED");
    }

    private Long insertProposal(
            String origin, String itemKind, String reviewStatus, String applyStatus) {

        Long accountId =
                jdbcClient
                        .sql(
                                """
                                insert into account (
                                    login_id, display_name, password_hash, role_id
                                )
                                values (
                                    :loginId, '테스트 사용자', 'test-hash',
                                    (select id from role where code = 'ADMIN')
                                )
                                returning id
                                """)
                        .param("loginId", "proposal-reader-" + UUID.randomUUID())
                        .query(Long.class)
                        .single();

        Long analysisRunId = null;

        if ("AI".equals(origin)) {
            analysisRunId =
                    jdbcClient
                            .sql(
                                    """
                                    insert into analysis_run (
                                        run_kind, work_key, status,
                                        requested_by, started_at, finished_at
                                    )
                                    values (
                                        'INITIAL', :workKey, 'SUCCEEDED',
                                        :accountId, now(), now()
                                    )
                                    returning id
                                    """)
                            .param("workKey", "proposal-test-" + UUID.randomUUID())
                            .param("accountId", accountId)
                            .query(Long.class)
                            .single();
        }

        String payload =
                "OBJECT_TYPE".equals(itemKind)
                        ? """
                          {"code": "TEST_TYPE", "name": "테스트 유형"}
                          """
                        : """
                          {"linkTypeId": 5}
                          """;

        Long proposalId =
                jdbcClient
                        .sql(
                                """
                                insert into proposal_item (
                                    analysis_run_id, origin, item_kind, revision,
                                    payload, evidence, review_status, apply_status,
                                    apply_error, applied_at, created_by
                                )
                                values (
                                    :analysisRunId, :origin, :itemKind, 1,
                                    cast(:payload as jsonb),
                                    '{"reason": "테스트용 근거"}'::jsonb,
                                    :reviewStatus, :applyStatus,
                                    case
                                        when :applyStatus = 'FAILED'
                                        then '테스트용 반영 오류'
                                        else null
                                    end,
                                    case
                                        when :applyStatus = 'APPLIED'
                                        then now()
                                        else null
                                    end,
                                    case
                                        when :origin = 'MANUAL'
                                        then :accountId
                                        else null
                                    end
                                )
                                returning id
                                """)
                        .param("analysisRunId", analysisRunId, Types.BIGINT)
                        .param("origin", origin)
                        .param("itemKind", itemKind)
                        .param("payload", payload)
                        .param("reviewStatus", reviewStatus)
                        .param("applyStatus", applyStatus)
                        .param("accountId", accountId)
                        .query(Long.class)
                        .single();

        jdbcClient
                .sql(
                        """
                        insert into proposal_revision (
                            proposal_item_id, revision,
                            payload, evidence, ai_confidence, created_by
                        )
                        select
                            id, revision,
                            payload, evidence, ai_confidence, created_by
                        from proposal_item
                        where id = :proposalId
                        """)
                .param("proposalId", proposalId)
                .update();

        return proposalId;
    }
}
