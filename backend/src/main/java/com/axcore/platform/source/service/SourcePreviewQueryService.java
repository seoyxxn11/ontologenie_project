package com.axcore.platform.source.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.source.dto.SourcePreviewResponse;
import com.axcore.platform.source.repository.SourcePreviewRepository;
import com.axcore.platform.source.repository.SourcePreviewRepository.Preview;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * API 102 원천 미리보기 결과 조회(FN-SRC-03·FN-MAP-04). 백엔드는 원천에 접속하지 않고 {@code source_preview} 행만 읽는다. 결과는
 * 배치가 채운다.
 *
 * <ul>
 *   <li>없는 ID, 지워진 작업, 결과를 지울 시각이 지났지만 아직 지워지지 않은 작업은 모두 404다. 화면은 016·040을 다시 요청한다.
 *   <li>관리자 전용이며 다른 관리자가 요청한 작업도 볼 수 있다. 결과 조회는 감사 기록을 남기지 않는다(요청 때 남긴다).
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourcePreviewQueryService {

    private final SourcePreviewRepository previewRepository;
    private final JsonMapper json;
    private final Clock clock;

    @SuppressWarnings("unchecked")
    public SourcePreviewResponse get(long previewId) {
        Instant now = Instant.now(clock);
        Preview preview =
                previewRepository
                        .findById(previewId)
                        .filter(p -> p.expiresAt().isAfter(now))
                        // 제안 검토의 제한 값 확인(447)은 이 API로 내보내지 않는다. 열람 기록과 한 번 읽기를 거치는 제안 API로만 읽는다.
                        .filter(
                                p ->
                                        !SourcePreviewRepository.RESTRICTED_VALUES.equals(
                                                p.previewKind()))
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of(
                                                        "resource",
                                                        "source_preview",
                                                        "id",
                                                        previewId)));
        return new SourcePreviewResponse(
                preview.id(),
                preview.previewKind(),
                preview.sourceResourceId(),
                preview.status(),
                preview.requestedAt(),
                preview.startedAt(),
                preview.finishedAt(),
                preview.expiresAt(),
                preview.result() == null ? null : json.readValue(preview.result(), Map.class),
                preview.errorMessage());
    }
}
