package com.axcore.platform.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.axcore.platform.audit.service.RestrictedViewRecorder;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.dto.LatestCheckResponse;
import com.axcore.platform.review.dto.ProposalResponse;
import com.axcore.platform.review.dto.ProposalSearchRequest;
import com.axcore.platform.review.repository.ProposalItemRepository;
import com.axcore.platform.review.repository.ProposalLatestCheckRepository;
import com.axcore.platform.review.service.ProposalEvidenceScope.Shown;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class ProposalServiceTests {

    @Mock ProposalItemRepository proposalItemRepository;

    @Mock ProposalLatestCheckRepository proposalLatestCheckRepository;

    @Mock DataAccessPolicy dataAccessPolicy;

    @Mock ProposalEvidenceScope evidenceScope;

    @Mock RestrictedViewRecorder restrictedViews;

    private static final LoginAccount ADMIN = new LoginAccount(1L, "admin", "ADMIN", false);

    @InjectMocks ProposalService proposalService;

    // 근거 가림은 StoredCopyScopeApiTests(실제 DB·보안 설정)에서 본다. 여기서는 근거를 그대로 넘긴다.
    @BeforeEach
    void passEvidenceThrough() {
        lenient()
                .when(evidenceScope.show(any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<Map<String, Object>> evidences = invocation.getArgument(1);
                            return evidences.stream().map(e -> new Shown(e, List.of())).toList();
                        });
    }

    @Test
    void returnsProposalAsResponse() {
        ProposalItem proposal = sampleProposal();

        given(proposalItemRepository.findById(10L)).willReturn(Optional.of(proposal));

        ProposalResponse response = proposalService.getProposal(ADMIN, 10L);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.analysisRunId()).isEqualTo(12L);
        assertThat(response.origin()).isEqualTo("AI");
        assertThat(response.itemKind()).isEqualTo("LINK_RULE");
        assertThat(response.revision()).isEqualTo(3);
        assertThat(response.payload()).containsEntry("linkTypeId", 5);
        assertThat(response.evidence()).containsEntry("reason", "테스트용 근거");
        assertThat(response.aiConfidence()).isEqualByComparingTo(new BigDecimal("0.8600"));
        assertThat(response.reviewStatus()).isEqualTo("PENDING");
        assertThat(response.applyStatus()).isEqualTo("NOT_APPLIED");
        assertThat(response.applyError()).isNull();
        assertThat(response.appliedAt()).isNull();
        assertThat(response.latestCheck()).isNull();

        verify(proposalItemRepository).findById(10L);
    }

    @Test
    void throwsNotFoundWhenProposalDoesNotExist() {
        given(proposalItemRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> proposalService.getProposal(ADMIN, 999L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
                            assertThat(exception.getDetails())
                                    .containsEntry("resource", "proposal_item")
                                    .containsEntry("id", 999L);
                        });

        verifyNoInteractions(proposalLatestCheckRepository);
    }

    @Test
    void convertsSearchResultsAndPreservesPageInformation() {
        ProposalSearchRequest request =
                new ProposalSearchRequest("AI", "LINK_RULE", "PENDING", "NOT_APPLIED", 1, 1);

        PageRequest pageable = PageRequest.of(1, 1, Sort.by("id").ascending());

        ProposalItem proposal = sampleProposal();

        Page<ProposalItem> repositoryResult = new PageImpl<>(List.of(proposal), pageable, 3);

        given(
                        proposalItemRepository.findAll(
                                ArgumentMatchers.<Specification<ProposalItem>>any(), eq(pageable)))
                .willReturn(repositoryResult);

        Page<ProposalResponse> result = proposalService.search(ADMIN, request, pageable);

        assertThat(result.getContent()).extracting(ProposalResponse::id).containsExactly(10L);
        assertThat(result.getContent().getFirst().origin()).isEqualTo("AI");
        assertThat(result.getContent().getFirst().revision()).isEqualTo(3);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.hasNext()).isTrue();

        verify(proposalItemRepository)
                .findAll(ArgumentMatchers.<Specification<ProposalItem>>any(), eq(pageable));
    }

    @Test
    void returnsEmptyPageWhenSearchHasNoResults() {
        ProposalSearchRequest request = new ProposalSearchRequest(null, null, "HELD", null, 0, 20);

        PageRequest pageable = PageRequest.of(0, 20, Sort.by("id").ascending());

        given(
                        proposalItemRepository.findAll(
                                ArgumentMatchers.<Specification<ProposalItem>>any(), eq(pageable)))
                .willReturn(Page.<ProposalItem>empty(pageable));

        Page<ProposalResponse> result = proposalService.search(ADMIN, request, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.hasNext()).isFalse();

        verifyNoInteractions(proposalLatestCheckRepository);
    }

    @Test
    void attachesLatestCheckForTheReturnedRevision() {
        ProposalItem proposal = sampleProposal();

        given(proposalItemRepository.findById(10L)).willReturn(Optional.of(proposal));

        LatestCheckResponse check =
                new LatestCheckResponse(402L, "QUEUED", null, null, null, false);

        given(proposalLatestCheckRepository.findLatestByProposalRevisions(Map.of(10L, 3)))
                .willReturn(Map.of(10L, check));

        ProposalResponse result = proposalService.getProposal(ADMIN, 10L);

        assertThat(result.revision()).isEqualTo(3);
        assertThat(result.latestCheck()).isEqualTo(check);
    }

    @Test
    void loadsChecksOnceForAllProposalsInThePage() {
        ProposalSearchRequest request = new ProposalSearchRequest(null, null, null, null, 0, 20);

        PageRequest pageable = PageRequest.of(0, 20, Sort.by("id").ascending());

        ProposalItem first = sampleProposal();
        ProposalItem second = sampleProposal(11L, 2);

        given(
                        proposalItemRepository.findAll(
                                ArgumentMatchers.<Specification<ProposalItem>>any(), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(first, second), pageable, 2));

        LatestCheckResponse firstCheck =
                new LatestCheckResponse(402L, "RUNNING", null, null, null, false);

        LatestCheckResponse secondCheck =
                new LatestCheckResponse(403L, "SUCCEEDED", 1, 4, new BigDecimal("0.2500"), false);

        given(proposalLatestCheckRepository.findLatestByProposalRevisions(Map.of(10L, 3, 11L, 2)))
                .willReturn(Map.of(10L, firstCheck, 11L, secondCheck));

        Page<ProposalResponse> result = proposalService.search(ADMIN, request, pageable);

        assertThat(result.getContent())
                .extracting(ProposalResponse::latestCheck)
                .containsExactly(firstCheck, secondCheck);
        assertThat(result.getTotalElements()).isEqualTo(2);

        verify(proposalLatestCheckRepository).findLatestByProposalRevisions(Map.of(10L, 3, 11L, 2));

        verifyNoMoreInteractions(proposalLatestCheckRepository);
    }

    @Test
    void keepsNullForProposalWithoutCheckInThePage() {
        ProposalSearchRequest request = new ProposalSearchRequest(null, null, null, null, 0, 20);

        PageRequest pageable = PageRequest.of(0, 20);

        List<ProposalItem> proposals = List.of(sampleProposal(), sampleProposal(11L, 2));

        given(
                        proposalItemRepository.findAll(
                                ArgumentMatchers.<Specification<ProposalItem>>any(), eq(pageable)))
                .willReturn(new PageImpl<>(proposals));

        LatestCheckResponse check =
                new LatestCheckResponse(402L, "FAILED", null, null, null, false);

        given(proposalLatestCheckRepository.findLatestByProposalRevisions(Map.of(10L, 3, 11L, 2)))
                .willReturn(Map.of(10L, check));

        Page<ProposalResponse> result = proposalService.search(ADMIN, request, pageable);

        assertThat(result.getContent().get(0).latestCheck()).isEqualTo(check);
        assertThat(result.getContent().get(1).latestCheck()).isNull();
    }

    private ProposalItem sampleProposal() {
        return sampleProposal(10L, 3);
    }

    private ProposalItem sampleProposal(long id, int revision) {
        ProposalItem proposal = mock(ProposalItem.class);

        given(proposal.getId()).willReturn(id);
        given(proposal.getAnalysisRunId()).willReturn(12L);
        given(proposal.getOrigin()).willReturn("AI");
        given(proposal.getItemKind()).willReturn("LINK_RULE");
        given(proposal.getRevision()).willReturn(revision);
        given(proposal.getPayload()).willReturn(Map.of("linkTypeId", 5));
        given(proposal.getEvidence()).willReturn(Map.of("reason", "테스트용 근거"));
        given(proposal.getAiConfidence()).willReturn(new BigDecimal("0.8600"));
        given(proposal.getReviewStatus()).willReturn("PENDING");
        given(proposal.getApplyStatus()).willReturn("NOT_APPLIED");

        return proposal;
    }
}
