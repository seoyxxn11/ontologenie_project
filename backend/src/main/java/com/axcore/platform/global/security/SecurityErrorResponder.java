package com.axcore.platform.global.security;

import com.axcore.platform.global.config.RequestIdFilter;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 보안 필터에서 막힌 요청(401·403)도 Controller 오류와 같은 공통 오류 형식으로 응답한다. */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String CSRF_MESSAGE = "CSRF 토큰이 없거나 올바르지 않습니다.";
    private static final String PASSWORD_CHANGE_MESSAGE = "비밀번호를 변경한 뒤 이용할 수 있습니다.";

    private final JsonMapper jsonMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        write(
                request,
                response,
                CommonErrorCode.UNAUTHENTICATED,
                CommonErrorCode.UNAUTHENTICATED.message());
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        write(
                request,
                response,
                CommonErrorCode.FORBIDDEN,
                forbiddenMessage(accessDeniedException));
    }

    private static String forbiddenMessage(AccessDeniedException exception) {
        if (exception instanceof CsrfException) {
            return CSRF_MESSAGE;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof LoginAccount account
                && account.mustChangePassword()) {
            return PASSWORD_CHANGE_MESSAGE;
        }
        return CommonErrorCode.FORBIDDEN.message();
    }

    void write(
            HttpServletRequest request,
            HttpServletResponse response,
            CommonErrorCode errorCode,
            String message)
            throws IOException {
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(
                        errorCode, message, Map.of(), RequestIdFilter.currentRequestId(request)));
    }
}
