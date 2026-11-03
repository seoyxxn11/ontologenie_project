package com.axcore.platform.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** 설정값이 비어 있으면 어떤 토큰으로도 내부 API를 열 수 없다. 빈 헤더끼리 같다고 통과하지 않는다. */
class InternalApiTokenFilterTests {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsEveryRequestWhenTokenIsNotConfigured(String configured) throws Exception {
        InternalApiTokenFilter filter =
                new InternalApiTokenFilter(
                        new InternalApiProperties(configured),
                        new SecurityErrorResponder(JsonMapper.builder().build()));
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/internal/analyses/1/results");
        request.addHeader(InternalApiTokenFilter.HEADER, configured == null ? "" : configured);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).as("다음 필터로 넘기지 않는다").isNull();
    }
}
