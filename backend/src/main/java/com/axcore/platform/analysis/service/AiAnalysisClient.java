package com.axcore.platform.analysis.service;

import com.axcore.platform.analysis.dto.AnalysisStartRequest;
import com.axcore.platform.global.security.InternalApiProperties;
import java.net.http.HttpClient;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FastAPI에 분석 시작을 알린다(API 018 하단, POST /internal/v1/analysis-runs). 요청 본문에 원천 접속 정보가 있으므로 본문과 응답을
 * 로그에 남기지 않는다.
 */
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AiAnalysisProperties.class)
public class AiAnalysisClient {
    static final String PATH = "/internal/v1/analysis-runs";
    static final String TOKEN_HEADER = "X-Internal-Token";
    static final String FAILURE_MESSAGE = "AI 서비스에 분석 시작을 알리지 못했습니다";

    private final AiAnalysisProperties properties;
    private final String token;
    private final JsonMapper json;
    private final RestClient rest;

    public AiAnalysisClient(
            AiAnalysisProperties properties, InternalApiProperties internalApi, JsonMapper json) {
        this.properties = properties;
        this.token = Objects.requireNonNullElse(internalApi.token(), "");
        this.json = json;
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(properties.connectTimeout())
                                .build());
        factory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder().requestFactory(factory).build();
        if (!properties.isConfigured()) {
            log.warn("AXCORE_AI_BASE_URL이 비어 있어 AI에 분석 시작을 알리지 않습니다. 새 분석 실행은 QUEUED로 남습니다.");
        }
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** AI의 202 응답에 담긴 accepted. 같은 실행이 이미 돌고 있으면 false다. */
    public boolean start(AnalysisStartRequest request) {
        try {
            String body =
                    rest.post()
                            .uri(properties.baseUrl() + PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TOKEN_HEADER, token)
                            .body(json.writeValueAsString(request))
                            .retrieve()
                            .body(String.class);
            JsonNode accepted = body == null ? null : json.readTree(body).get("accepted");
            if (accepted == null || !accepted.isBoolean()) {
                log.warn("분석 실행 {} 시작 알림의 AI 응답에 accepted가 없습니다.", request.runId());
                throw new AnalysisStartException(FAILURE_MESSAGE);
            }
            return accepted.booleanValue();
        } catch (RestClientResponseException ex) {
            log.warn(
                    "분석 실행 {} 시작 알림을 AI가 거절했습니다. HTTP {}",
                    request.runId(),
                    ex.getStatusCode().value());
            throw new AnalysisStartException(FAILURE_MESSAGE);
        } catch (RestClientException | JacksonException ex) {
            log.warn("분석 실행 {} 시작 알림에 실패했습니다: {}", request.runId(), ex.getClass().getSimpleName());
            throw new AnalysisStartException(FAILURE_MESSAGE);
        }
    }
}
