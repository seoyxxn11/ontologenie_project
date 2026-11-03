package com.axcore.platform.agent.controller;

import com.axcore.platform.agent.dto.AgentConversationResponse;
import com.axcore.platform.agent.dto.AgentConversationSearchRequest;
import com.axcore.platform.agent.service.AgentConversationService;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/conversations")
public class AgentConversationController {
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AgentConversationService service;

    @Operation(summary = "081 내 대화 목록")
    @GetMapping
    public ApiResponse<List<AgentConversationResponse>> search(
            @Valid @ModelAttribute AgentConversationSearchRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? DEFAULT_PAGE_SIZE : request.size();
        Sort latestFirst = Sort.by(Sort.Order.desc("lastMessageAt"), Sort.Order.desc("id"));
        return ApiResponse.ofPage(
                service.search(account.id(), PageRequest.of(page, size, latestFirst)));
    }

    @Operation(summary = "082 대화 생성")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentConversationResponse> create(
            @AuthenticationPrincipal LoginAccount account) {
        return ApiResponse.of(service.create(account.id()));
    }

    @Operation(summary = "083 대화 상세")
    @GetMapping("/{id}")
    public ApiResponse<AgentConversationResponse> get(
            @PathVariable("id") @Min(1) long id, @AuthenticationPrincipal LoginAccount account) {
        return ApiResponse.of(service.get(account.id(), id));
    }
}
