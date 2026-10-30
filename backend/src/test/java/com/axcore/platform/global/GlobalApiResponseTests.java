package com.axcore.platform.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.global.config.RequestIdFilter;
import com.axcore.platform.global.error.ApiErrorController;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.error.ErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.RequestDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 테스트 클래스 안의 컨트롤러는 컴포넌트 스캔에서 빠지므로 직접 등록한다.
// 응답 형식만 본다. 로그인·CSRF 검사는 AuthApiTests가 실제 보안 설정으로 확인한다.
@WebMvcTest(
        controllers = {GlobalApiResponseTests.TestController.class, ApiErrorController.class},
        excludeAutoConfiguration = {
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
        })
@Import(GlobalApiResponseTests.TestController.class)
class GlobalApiResponseTests {

    @Autowired MockMvc mockMvc;

    @Test
    void wrapsSingleResultInData() throws Exception {
        mockMvc.perform(get("/test/single"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("MES"))
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void addsPageMetaToListResult() throws Exception {
        mockMvc.perform(get("/test/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(5))
                .andExpect(jsonPath("$.meta.hasNext").value(true));
    }

    @Test
    void reportsEveryInvalidFieldAndFirstOneSeparately() throws Exception {
        mockMvc.perform(
                        post("/test/body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("요청 필드를 확인해 주세요."))
                .andExpect(jsonPath("$.error.details.field").exists())
                .andExpect(jsonPath("$.error.details.reason").exists())
                .andExpect(jsonPath("$.error.details.errors.length()").value(2))
                .andExpect(
                        jsonPath("$.error.details.errors[*].field")
                                .value(containsInAnyOrder("name", "size")));
    }

    // 경로 변수에 제약이 있으면 Spring이 메서드 단위로 검증한다. 그때도 본문 오류는 칸 이름으로 알린다.
    @Test
    void reportsBodyFieldsWhenPathVariableIsAlsoConstrained() throws Exception {
        mockMvc.perform(
                        post("/test/body/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.errors.length()").value(2))
                .andExpect(
                        jsonPath("$.error.details.errors[*].field")
                                .value(containsInAnyOrder("name", "size")));
        mockMvc.perform(
                        post("/test/body/0")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"MES\", \"size\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("id"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(
                        post("/test/body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("request"));
    }

    @Test
    void rejectsUnsupportedContentTypeAsBadRequest() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsWrongParameterType() throws Exception {
        mockMvc.perform(get("/test/param").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("size"));
    }

    @Test
    void rejectsMissingParameter() throws Exception {
        mockMvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.field").value("size"))
                .andExpect(jsonPath("$.error.details.reason").value("필수 값입니다."));
    }

    @Test
    void rejectsParameterConstraintViolation() throws Exception {
        mockMvc.perform(get("/test/param").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("size"));
    }

    @Test
    void returnsNotFoundForUnknownPath() throws Exception {
        mockMvc.perform(get("/test/nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void returnsMethodNotAllowedWithAllowHeader() throws Exception {
        mockMvc.perform(delete("/test/single"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void usesBusinessExceptionCodeAndDetails() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.resource").value("source"));
    }

    @Test
    void keepsDomainErrorCodeAndStatus() throws Exception {
        mockMvc.perform(get("/test/domain"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("SAMPLE_RULE_VIOLATED"))
                .andExpect(jsonPath("$.error.message").value("업무 조건을 충족하지 못했습니다."));
    }

    @Test
    void usesApiSpecificMessageWhenGiven() throws Exception {
        mockMvc.perform(get("/test/custom-message"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("미리보기 결과가 없거나 만료되었습니다. 다시 요청해 주세요."));
    }

    @Test
    void returnsInUseWithReferences() throws Exception {
        mockMvc.perform(get("/test/in-use"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IN_USE"))
                .andExpect(jsonPath("$.error.message").value("다른 데이터가 참조하고 있어 처리할 수 없습니다."))
                .andExpect(
                        jsonPath("$.error.details.references[0].tableName").value("property_def"));
    }

    @Test
    void returnsBusinessRuleViolationAsUnprocessable() throws Exception {
        mockMvc.perform(get("/test/business-rule"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details.field").value("displayPropertyId"));
    }

    @Test
    void mapsOptimisticLockFailureToVersionConflict() throws Exception {
        mockMvc.perform(get("/test/version-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void mapsConstraintViolationToConflict() throws Exception {
        mockMvc.perform(get("/test/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void hidesInternalDetailOnUnexpectedError() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void sendsSameRequestIdInHeaderAndErrorBody() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/test/not-found"))
                        .andExpect(status().isNotFound())
                        .andReturn();

        String header = result.getResponse().getHeader(RequestIdFilter.HEADER);
        String body = JsonPath.read(result.getResponse().getContentAsString(), "$.error.requestId");
        assertThat(header).isNotBlank().isEqualTo(body);
    }

    @Test
    void createsNewRequestIdIgnoringClientValue() throws Exception {
        mockMvc.perform(get("/test/single").header(RequestIdFilter.HEADER, "client-value"))
                .andExpect(header().string(RequestIdFilter.HEADER, not("client-value")));
    }

    @Test
    void formatsContainerErrorsInCommonShape() throws Exception {
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 401))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }

    enum TestErrorCode implements ErrorCode {
        SAMPLE_RULE_VIOLATED;

        @Override
        public String code() {
            return name();
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.UNPROCESSABLE_CONTENT;
        }

        @Override
        public String message() {
            return "업무 조건을 충족하지 못했습니다.";
        }
    }

    record SampleRequest(@NotBlank String name, @NotNull Integer size) {}

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/single")
        ApiResponse<Map<String, String>> single() {
            return ApiResponse.of(Map.of("name", "MES"));
        }

        @GetMapping("/page")
        ApiResponse<List<String>> page() {
            return ApiResponse.ofPage(new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 2), 5));
        }

        @PostMapping("/body")
        ApiResponse<String> body(@Valid @RequestBody SampleRequest request) {
            return ApiResponse.of(request.name());
        }

        @PostMapping("/body/{id}")
        ApiResponse<String> bodyWithPath(
                @PathVariable("id") @Min(1) long id, @Valid @RequestBody SampleRequest request) {
            return ApiResponse.of(request.name());
        }

        @GetMapping("/param")
        ApiResponse<Integer> param(@RequestParam @Min(1) int size) {
            return ApiResponse.of(size);
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "source"));
        }

        @GetMapping("/domain")
        void domain() {
            throw new BusinessException(TestErrorCode.SAMPLE_RULE_VIOLATED);
        }

        @GetMapping("/custom-message")
        void customMessage() {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    "미리보기 결과가 없거나 만료되었습니다. 다시 요청해 주세요.",
                    Map.of());
        }

        @GetMapping("/in-use")
        void inUse() {
            throw new BusinessException(
                    CommonErrorCode.IN_USE,
                    Map.of(
                            "references",
                            List.of(
                                    Map.of(
                                            "tableName",
                                            "property_def",
                                            "count",
                                            2,
                                            "ids",
                                            List.of(1, 2)))));
        }

        @GetMapping("/business-rule")
        void businessRule() {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "대표 속성은 같은 객체 유형의 속성이어야 합니다.",
                    Map.of("field", "displayPropertyId"));
        }

        @GetMapping("/version-conflict")
        void versionConflict() {
            throw new OptimisticLockingFailureException("row version changed");
        }

        @GetMapping("/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("duplicate key");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
