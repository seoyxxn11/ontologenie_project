package com.axcore.platform.issue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.global.error.GlobalExceptionHandler;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.issue.controller.IssueController;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 데이터베이스 없이도 058의 HTTP 입력 계약을 검증한다. */
@ExtendWith(MockitoExtension.class)
class IssueManualLinkRouteTests {

    @InjectMocks IssueController controller;

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void rejectsInvalidManualLinkInputAtThePostRoute(String scenario, String body, String field)
            throws Exception {
        var account = new LoginAccount(1L, "issue-test", "ADMIN", false);
        SecurityContextHolder.getContext().setAuthentication(account.toAuthentication());
        try {
            MockMvcBuilders.standaloneSetup(controller)
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build()
                    .perform(
                            post("/api/v1/issues/42/manual-links")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.field").value(field));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(
                        "도착 객체 ID 누락", "{\"reason\": \"확인\", \"rowVersion\": 0}", "targetObjectId"),
                Arguments.of(
                        "도착 객체 ID가 0",
                        "{\"targetObjectId\": 0, \"reason\": \"확인\", \"rowVersion\": 0}",
                        "targetObjectId"),
                Arguments.of(
                        "도착 객체 ID가 소수",
                        "{\"targetObjectId\": 1.5, \"reason\": \"확인\", \"rowVersion\": 0}",
                        "request"),
                Arguments.of("사유 누락", "{\"targetObjectId\": 1, \"rowVersion\": 0}", "reason"),
                Arguments.of(
                        "공백 사유",
                        "{\"targetObjectId\": 1, \"reason\": \" \", \"rowVersion\": 0}",
                        "reason"),
                Arguments.of(
                        "사유가 1,001자",
                        "{\"targetObjectId\": 1, \"reason\": \"%s\", \"rowVersion\": 0}"
                                .formatted("가".repeat(1001)),
                        "reason"),
                Arguments.of("버전 누락", "{\"targetObjectId\": 1, \"reason\": \"확인\"}", "rowVersion"),
                Arguments.of(
                        "버전이 음수",
                        "{\"targetObjectId\": 1, \"reason\": \"확인\", \"rowVersion\": -1}",
                        "rowVersion"),
                Arguments.of(
                        "버전이 소수",
                        "{\"targetObjectId\": 1, \"reason\": \"확인\", \"rowVersion\": 1.5}",
                        "request"));
    }
}
