package com.axcore.platform.review.controller;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.dto.ManualProposalCreateRequest;
import com.axcore.platform.review.dto.ProposalOptionResponse;
import com.axcore.platform.review.dto.ProposalResponse;
import com.axcore.platform.review.dto.ProposalRevisionCreateRequest;
import com.axcore.platform.review.dto.ProposalRevisionResponse;
import com.axcore.platform.review.dto.ProposalRevisionSearchRequest;
import com.axcore.platform.review.dto.ProposalSearchRequest;
import com.axcore.platform.review.dto.ProposalWriteResponse;
import com.axcore.platform.review.service.ProposalCommandService;
import com.axcore.platform.review.service.ProposalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/proposals")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ProposalController {

    private final ProposalService proposalService;
    private final ProposalCommandService proposalCommandService;

    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProposalWriteResponse> create(
            @Valid @RequestBody ManualProposalCreateRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(proposalCommandService.create(request, account));
    }

    @PostMapping("/{id}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProposalWriteResponse> revise(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody ProposalRevisionCreateRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(proposalCommandService.revise(id, request, account));
    }

    @GetMapping
    public ApiResponse<List<ProposalResponse>> search(
            @Valid @ModelAttribute ProposalSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {

        requireAdmin(loginAccount);

        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();

        PageRequest pageable = PageRequest.of(page, size, Sort.by("id").descending());

        return ApiResponse.ofPage(proposalService.search(request, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProposalResponse> getProposal(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {

        requireAdmin(loginAccount);

        return ApiResponse.of(proposalService.getProposal(id));
    }

    @GetMapping("/{id}/options")
    public ApiResponse<List<ProposalOptionResponse>> getOptions(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @RequestParam(name = "revision", required = false)
                    @Min(value = 1, message = "revision은 1 이상이어야 합니다.")
                    Integer revision,
            @AuthenticationPrincipal LoginAccount loginAccount) {

        requireAdmin(loginAccount);

        return ApiResponse.of(proposalService.getOptions(id, revision));
    }

    @GetMapping("/{id}/revisions")
    public ApiResponse<List<ProposalRevisionResponse>> getRevisions(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @Valid @ModelAttribute ProposalRevisionSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {

        requireAdmin(loginAccount);

        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? 20 : request.size();

        PageRequest pageable = PageRequest.of(page, size);

        return ApiResponse.ofPage(proposalService.getRevisions(id, pageable));
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (loginAccount == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }

        if (!"ADMIN".equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", "ADMIN"));
        }
    }
}
