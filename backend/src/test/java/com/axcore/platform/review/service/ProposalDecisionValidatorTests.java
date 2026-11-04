package com.axcore.platform.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import com.axcore.platform.review.repository.ProposalDecisionCheckRepository;
import com.axcore.platform.review.repository.ProposalDecisionCheckRepository.CheckRunStatus;
import com.axcore.platform.review.repository.ProposalItemRepository;
import com.axcore.platform.review.repository.ProposalOptionRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class ProposalDecisionValidatorTests {
    private final ProposalItemRepository items = mock(ProposalItemRepository.class);
    private final ProposalOptionRepository options = mock(ProposalOptionRepository.class);
    private final ProposalDecisionCheckRepository checks =
            mock(ProposalDecisionCheckRepository.class);
    private final ProposalDecisionValidator validator =
            new ProposalDecisionValidator(items, options, checks);
    private final LoginAccount admin = new LoginAccount(1L, "admin", "ADMIN", false);
    private ProposalItem item;

    @BeforeEach
    void prepareCandidate() {
        item = ProposalItem.manual(Map.of("rule", "test"), Map.of());
        ReflectionTestUtils.setField(item, "id", 10L);
        ReflectionTestUtils.setField(item, "revision", 2);
    }

    @Test
    void acceptsAnApprovalWithASuccessfulCheckForTheCurrentRevision() {
        given(items.findForUpdate(10L)).willReturn(Optional.of(item));
        given(checks.hasSuccessfulCheck(10L, 2)).willReturn(true);

        assertThat(validator.validate(10L, request(2, "APPROVE", null), admin)).isSameAs(item);
        assertThat(item.getReviewStatus()).isEqualTo("PENDING");
        assertThat(item.getApplyStatus()).isEqualTo("NOT_APPLIED");
    }

    @Test
    void rejectsAnUnauthenticatedRequestBeforeReadingTheCandidate() {
        BusinessException error =
                catchThrowableOfType(
                        BusinessException.class,
                        () -> validator.validate(10L, request(2, "APPROVE", null), null));
        assertError(error, "UNAUTHENTICATED", 401);
        verifyNoInteractions(items, checks, options);
    }

    @Test
    void rejectsANonAdminBeforeReadingTheCandidate() {
        LoginAccount user = new LoginAccount(2L, "user", "USER", false);
        BusinessException error =
                catchThrowableOfType(
                        BusinessException.class,
                        () -> validator.validate(10L, request(2, "APPROVE", null), user));
        assertError(error, "FORBIDDEN", 403);
        assertThat(error.getDetails()).containsEntry("requiredPermission", "ADMIN");
        verifyNoInteractions(items, checks, options);
    }

    @Test
    void rejectsAMissingCandidate() {
        given(items.findForUpdate(10L)).willReturn(Optional.empty());
        BusinessException error =
                catchThrowableOfType(
                        BusinessException.class,
                        () -> validator.validate(10L, request(2, "APPROVE", null), admin));
        assertError(error, "RESOURCE_NOT_FOUND", 404);
    }

    @Test
    void rejectsTheRevisionThatTheAdminSawBeforeAnotherEdit() {
        given(items.findForUpdate(10L)).willReturn(Optional.of(item));
        BusinessException error =
                catchThrowableOfType(
                        BusinessException.class,
                        () -> validator.validate(10L, request(1, "APPROVE", null), admin));
        assertError(error, "REVISION_CONFLICT", 409);
        assertThat(error.getDetails())
                .containsEntry("revision", 1)
                .containsEntry("currentRevision", 2);
        assertThat(item.getRevision()).isEqualTo(2);
        verifyNoInteractions(checks, options);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void rejectsAnAlreadyDecidedCandidate(String status) {
        ReflectionTestUtils.setField(item, "reviewStatus", status);
        given(items.findForUpdate(10L)).willReturn(Optional.of(item));
        BusinessException error =
                catchThrowableOfType(
                        BusinessException.class,
                        () -> validator.validate(10L, request(2, "HOLD", null), admin));
        assertError(error, "PROPOSAL_ALREADY_DECIDED", 409);
        assertThat(item.getReviewStatus()).isEqualTo(status);
        verifyNoInteractions(checks, options);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "FAILED", "SUCCEEDED"})
    void rejectsApprovalWithoutAnEligibleResultAndExplainsTheLatestRun(String status) {
        given(items.findForUpdate(10L)).willReturn(Optional.of(item));
        given(checks.findLatestCheck(10L, 2))
                .willReturn(Optional.of(new CheckRunStatus(402L, status)));
        BusinessException error =
                catchThrowableOfType(
                        BusinessException.class,
                        () -> validator.validate(10L, request(2, "APPROVE", null), admin));
        assertError(error, "REVIEW_VALIDATION_FAILED", 422);
        assertThat(error.getDetails())
                .containsEntry("revision", 2)
                .containsEntry("latestCheckRunId", 402L)
                .containsEntry("latestCheckStatus", status);
        assertThat(item.getReviewStatus()).isEqualTo("PENDING");
    }

    @Test
    void rejectsApprovalWhenNoCheckHasEverRun() {
        given(items.findForUpdate(10L)).willReturn(Optional.of(item));
        given(checks.findLatestCheck(10L, 2)).willReturn(Optional.empty());
        BusinessException error =
                catchThrowableOfType(
                        BusinessException.class,
                        () -> validator.validate(10L, request(2, "APPROVE", null), admin));
        assertError(error, "REVIEW_VALIDATION_FAILED", 422);
        assertThat(error.getDetails()).containsEntry("revision", 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECT", "HOLD"})
    void doesNotRequireASuccessfulCheckForRejectionOrHold(String decision) {
        given(items.findForUpdate(10L)).willReturn(Optional.of(item));
        assertThatCode(() -> validator.validate(10L, request(2, decision, null), admin))
                .doesNotThrowAnyException();
        verifyNoInteractions(checks);
        assertThat(item.getReviewStatus()).isEqualTo("PENDING");
    }

    @Test
    void acceptsAHeldCandidateWithAnEligibleCheck() {
        ReflectionTestUtils.setField(item, "reviewStatus", "HELD");
        given(items.findForUpdate(10L)).willReturn(Optional.of(item));
        given(checks.hasSuccessfulCheck(10L, 2)).willReturn(true);
        assertThat(validator.validate(10L, request(2, "APPROVE", null), admin)).isSameAs(item);
        assertThat(item.getReviewStatus()).isEqualTo("HELD");
    }

    @Test
    void rejectsAnOptionOutsideTheCandidateRevision() {
        given(items.findForUpdate(10L)).willReturn(Optional.of(item));
        BusinessException error =
                catchThrowableOfType(
                        BusinessException.class,
                        () -> validator.validate(10L, request(2, "HOLD", 99L), admin));
        assertError(error, "VALIDATION_FAILED", 400);
        assertThat(error.getDetails()).containsEntry("field", "selectedOptionId");
        verifyNoInteractions(checks);
    }

    @Test
    void acceptsAnOptionFromTheSameCandidateAndRevision() {
        given(items.findForUpdate(10L)).willReturn(Optional.of(item));
        given(options.existsByIdAndProposalItemIdAndRevision(99L, 10L, 2)).willReturn(true);
        given(checks.hasSuccessfulCheck(10L, 2)).willReturn(true);
        assertThat(validator.validate(10L, request(2, "APPROVE", 99L), admin)).isSameAs(item);
    }

    private static ProposalDecisionRequest request(int revision, String decision, Long optionId) {
        return new ProposalDecisionRequest(revision, decision, optionId, "검토 사유");
    }

    private static void assertError(BusinessException error, String code, int status) {
        assertThat(error).isNotNull();
        assertThat(error.getErrorCode().code()).isEqualTo(code);
        assertThat(error.getErrorCode().status().value()).isEqualTo(status);
    }
}
