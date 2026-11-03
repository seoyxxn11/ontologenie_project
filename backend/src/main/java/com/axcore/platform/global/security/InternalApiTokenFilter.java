package com.axcore.platform.global.security;

import com.axcore.platform.global.error.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 내부 API를 서비스 토큰으로 인증한다(API 105·043). {@code X-Internal-Token} 헤더가 설정값과 같으면 내부 서비스로 인증하고, 아니면 여기서
 * 막는다. 토큰 없이 사용자 세션으로 부른 요청은 403, 그 밖은 401이다. 내부 API 보안 체인에만 넣으므로 Bean으로 등록하지 않는다.
 */
@Slf4j
final class InternalApiTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Token";
    static final String PRINCIPAL = "internal-service";

    private static final String UNAUTHENTICATED_MESSAGE = "서비스 자격 증명이 없거나 유효하지 않습니다.";
    private static final String SESSION_MESSAGE = "내부 API는 사용자 세션으로 호출할 수 없습니다.";

    private final byte[] expectedToken;
    private final SecurityErrorResponder errorResponder;

    InternalApiTokenFilter(
            InternalApiProperties properties, SecurityErrorResponder errorResponder) {
        this.expectedToken =
                properties.isConfigured()
                        ? properties.token().getBytes(StandardCharsets.UTF_8)
                        : null;
        this.errorResponder = errorResponder;
        if (expectedToken == null) {
            log.warn("AXCORE_INTERNAL_API_TOKEN이 비어 있어 내부 API 요청을 모두 거절합니다.");
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (matches(request.getHeader(HEADER))) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, List.of()));
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getRequestedSessionId() != null) {
            errorResponder.write(request, response, CommonErrorCode.FORBIDDEN, SESSION_MESSAGE);
            return;
        }
        errorResponder.write(
                request, response, CommonErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_MESSAGE);
    }

    // 길이와 내용을 한 번에 비교해 맞은 글자 수가 응답 시간으로 드러나지 않게 한다.
    private boolean matches(String token) {
        if (expectedToken == null || token == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8));
    }
}
