package com.axcore.platform.agent.controller;

import com.axcore.platform.agent.dto.AgentMessageResponse;
import com.axcore.platform.agent.dto.AgentMessageSearchRequest;
import com.axcore.platform.agent.dto.QuestionRequest;
import com.axcore.platform.agent.service.AgentMessageService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/conversations/{id}/messages")
public class AgentMessageController {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final Sort OLDEST_FIRST =
            Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    private final AgentMessageService service;

    @Operation(summary = "084 대화 메시지 목록")
    @GetMapping
    public ApiResponse<List<AgentMessageResponse>> search(
            @PathVariable("id") @Min(1) long id,
            @Valid @ModelAttribute AgentMessageSearchRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? DEFAULT_PAGE_SIZE : request.size();
        return ApiResponse.ofPage(
                service.search(account.id(), id, PageRequest.of(page, size, OLDEST_FIRST)));
    }

    @Operation(summary = "085 질문하고 답변 받기")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<List<AgentMessageResponse>> ask(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody QuestionRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        return ApiResponse.of(service.ask(account.id(), id, request));
    }
}
