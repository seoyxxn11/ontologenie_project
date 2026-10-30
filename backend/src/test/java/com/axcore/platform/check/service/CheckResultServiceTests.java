package com.axcore.platform.check.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.axcore.platform.check.domain.CheckResult;
import com.axcore.platform.check.dto.CheckResultResponse;
import com.axcore.platform.check.dto.CheckResultSearchRequest;
import com.axcore.platform.check.repository.CheckResultRepository;
import com.axcore.platform.check.repository.CheckRunQueryRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/** DB 연결 없이 검사 상태별 처리와 응답 변환·매칭률 계산을 검증한다. */
@ExtendWith(MockitoExtension.class)
class CheckResultServiceTests {

    private static final long RUN_ID = 10L;

    private static final CheckResultSearchRequest REQUEST =
            new CheckResultSearchRequest(null, null, null, null);

    @Mock CheckResultRepository checkResultRepository;

    @Mock CheckRunQueryRepository checkRunQueryRepository;

    @InjectMocks CheckResultService checkResultService;

    @Test
    void returnsResponseWithEvidenceAndInspectedRevision() {
        PageRequest pageable = PageRequest.of(0, 20);

        CheckResult entity = sampleResult(10, 7);

        ReflectionTestUtils.setField(entity, "unmatchedCount", 1);
        ReflectionTestUtils.setField(entity, "duplicateCount", 1);
        ReflectionTestUtils.setField(entity, "violationCount", 1);
        ReflectionTestUtils.setField(
                entity,
                "sampleRows",
                List.of(
                        Map.of(
                                "rawValue", "W23",
                                "transformedValue", "E023",
                                "classification", "UNMATCHED",
                                "reason", "대상 객체 없음")));

        givenSuccessfulPage(pageable, List.of(entity), 1);

        Page<CheckResultResponse> result = checkResultService.search(RUN_ID, REQUEST, pageable);

        assertThat(result.getContent()).hasSize(1);

        CheckResultResponse response = result.getContent().get(0);

        assertThat(response.id()).isEqualTo(20L);
        assertThat(response.checkRunId()).isEqualTo(10L);
        assertThat(response.linkMappingId()).isNull();
        assertThat(response.proposalItemId()).isEqualTo(30L);

        // 현재 후보의 개정 번호로 바꾸지 않고 검사 당시 번호를 전달한다.
        assertThat(response.proposalItemRevision()).isEqualTo(2);
        assertThat(response.previousResultId()).isEqualTo(19L);

        assertThat(response.totalCount()).isEqualTo(10);
        assertThat(response.matchedCount()).isEqualTo(7);
        assertThat(response.unmatchedCount()).isEqualTo(1);
        assertThat(response.duplicateCount()).isEqualTo(1);
        assertThat(response.violationCount()).isEqualTo(1);
        assertThat(response.matchRate()).isEqualByComparingTo(new BigDecimal("0.7000"));

        assertThat(response.sampleRows()).hasSize(1);
        assertThat(response.sampleRows().get(0))
                .containsEntry("rawValue", "W23")
                .containsEntry("transformedValue", "E023")
                .containsEntry("classification", "UNMATCHED")
                .containsEntry("reason", "대상 객체 없음");
    }

    @Test
    void throwsNotFoundWhenRunDoesNotExist() {
        PageRequest pageable = PageRequest.of(0, 20);

        given(checkRunQueryRepository.findStatusById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> checkResultService.search(999L, REQUEST, pageable))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
                            assertThat(exception.getDetails())
                                    .containsEntry("resource", "check_run")
                                    .containsEntry("id", 999L);
                        });

        // 존재하지 않는 실행이면 결과 테이블 조회까지 진행하지 않는다.
        verifyNoInteractions(checkResultRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "FAILED"})
    void returnsEmptyPageForNonSucceededRun(String status) {
        PageRequest pageable = PageRequest.of(1, 2);

        given(checkRunQueryRepository.findStatusById(RUN_ID)).willReturn(Optional.of(status));

        Page<CheckResultResponse> result = checkResultService.search(RUN_ID, REQUEST, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.hasNext()).isFalse();

        // 부분 결과를 읽어서 노출하지 않도록 결과 조회 자체를 건너뛴다.
        verifyNoInteractions(checkResultRepository);
    }

    @Test
    void roundsMatchRateToFourDecimalPlaces() {
        PageRequest pageable = PageRequest.of(0, 20);

        givenSuccessfulPage(pageable, List.of(sampleResult(3, 2)), 1);

        Page<CheckResultResponse> result = checkResultService.search(RUN_ID, REQUEST, pageable);

        assertThat(result.getContent().get(0).matchRate())
                .isEqualByComparingTo(new BigDecimal("0.6667"));
    }

    @Test
    void returnsNullMatchRateWhenTotalCountIsZero() {
        PageRequest pageable = PageRequest.of(0, 20);

        givenSuccessfulPage(pageable, List.of(sampleResult(0, 0)), 1);

        Page<CheckResultResponse> result = checkResultService.search(RUN_ID, REQUEST, pageable);

        CheckResultResponse response = result.getContent().get(0);

        assertThat(response.totalCount()).isZero();
        assertThat(response.matchedCount()).isZero();
        assertThat(response.matchRate()).isNull();
        assertThat(response.sampleRows()).isEmpty();
    }

    @Test
    void preservesPageInformationWhenConvertingResults() {
        PageRequest pageable = PageRequest.of(1, 1, Sort.by("id").ascending());

        givenSuccessfulPage(pageable, List.of(sampleResult(10, 7)), 3);

        Page<CheckResultResponse> result = checkResultService.search(RUN_ID, REQUEST, pageable);

        assertThat(result.getContent()).extracting(CheckResultResponse::id).containsExactly(20L);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void returnsEmptyPageWhenSucceededRunHasNoMatchingResults() {
        PageRequest pageable = PageRequest.of(0, 20);

        givenSuccessfulPage(pageable, List.of(), 0);

        Page<CheckResultResponse> result = checkResultService.search(RUN_ID, REQUEST, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void preservesApprovedMappingTarget() {
        PageRequest pageable = PageRequest.of(0, 20);

        CheckResult entity = sampleResult(10, 7);

        // 승인 규칙 결과에는 후보 ID와 후보 revision이 없다.
        ReflectionTestUtils.setField(entity, "linkMappingId", 600L);
        ReflectionTestUtils.setField(entity, "proposalItemId", null);
        ReflectionTestUtils.setField(entity, "proposalItemRevision", null);

        givenSuccessfulPage(pageable, List.of(entity), 1);

        Page<CheckResultResponse> result = checkResultService.search(RUN_ID, REQUEST, pageable);

        CheckResultResponse response = result.getContent().get(0);

        assertThat(response.linkMappingId()).isEqualTo(600L);
        assertThat(response.proposalItemId()).isNull();
        assertThat(response.proposalItemRevision()).isNull();
    }

    private void givenSuccessfulPage(
            PageRequest pageable, List<CheckResult> content, long totalElements) {

        given(checkRunQueryRepository.findStatusById(RUN_ID)).willReturn(Optional.of("SUCCEEDED"));

        given(
                        checkResultRepository.findAll(
                                ArgumentMatchers.<Specification<CheckResult>>any(), eq(pageable)))
                .willReturn(new PageImpl<>(content, pageable, totalElements));
    }

    private CheckResult sampleResult(int totalCount, int matchedCount) {
        // 테스트 데이터 준비를 위해 Entity에 별도 Setter를 추가하지 않는다.
        CheckResult entity = BeanUtils.instantiateClass(CheckResult.class);

        ReflectionTestUtils.setField(entity, "id", 20L);
        ReflectionTestUtils.setField(entity, "checkRunId", RUN_ID);
        ReflectionTestUtils.setField(entity, "proposalItemId", 30L);
        ReflectionTestUtils.setField(entity, "proposalItemRevision", 2);
        ReflectionTestUtils.setField(entity, "previousResultId", 19L);
        ReflectionTestUtils.setField(entity, "totalCount", totalCount);
        ReflectionTestUtils.setField(entity, "matchedCount", matchedCount);
        ReflectionTestUtils.setField(entity, "unmatchedCount", totalCount - matchedCount);
        ReflectionTestUtils.setField(entity, "duplicateCount", 0);
        ReflectionTestUtils.setField(entity, "violationCount", 0);
        ReflectionTestUtils.setField(entity, "sampleRows", List.of());

        return entity;
    }
}
