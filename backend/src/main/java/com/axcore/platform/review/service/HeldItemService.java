package com.axcore.platform.review.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.review.dto.HeldItemResponse;
import com.axcore.platform.review.dto.HeldItemSearchRequest;
import com.axcore.platform.review.repository.HeldItemQueryRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HeldItemService {
    private final HeldItemQueryRepository heldItemQueryRepository;

    public Page<HeldItemResponse> search(HeldItemSearchRequest request, Pageable pageable) {
        return heldItemQueryRepository.search(request, pageable);
    }

    public HeldItemResponse getHeldItem(long id) {
        return heldItemQueryRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "held_item", "id", id)));
    }
}
