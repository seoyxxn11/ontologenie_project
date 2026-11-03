package com.axcore.platform.global.security;

import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 세션 인증과 CSRF 검사 (개발 가이드 11.1). 로그인·API 문서·health 체크·Prometheus 지표 GET을 뺀 모든 요청은 로그인이 필요하다. 임시 비밀번호
 * 상태에서는 내 계정 조회, 로그아웃, 비밀번호 변경만 쓸 수 있다. 내부 API(/api/v1/internal/**)는 세션 대신 서비스 토큰으로만 연다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalApiProperties.class)
public class SecurityConfig {

    public static final String LOGIN_PATH = "/api/v1/auth/login";
    public static final String INTERNAL_API_PATHS = "/api/v1/internal/**";

    private static final String[] PUBLIC_PATHS = {
        "/actuator/health",
        "/actuator/health/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/error"
    };

    private static final String[] PASSWORD_CHANGE_ALLOWED_PATHS = {
        "/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/auth/password"
    };

    // 내부 API는 FastAPI가 X-Internal-Token으로만 부른다(API 105). 세션을 읽거나 만들지 않고, 쿠키를 쓰지 않으므로 CSRF도 검사하지
    // 않는다.
    // 아래 일반 체인보다 먼저 경로를 가져간다.
    @Bean
    @Order(1)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain internalApiSecurityFilterChain(
            HttpSecurity http,
            InternalApiProperties properties,
            SecurityErrorResponder errorResponder) {
        http.securityMatcher(INTERNAL_API_PATHS)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .addFilterBefore(
                        new InternalApiTokenFilter(properties, errorResponder),
                        AuthorizationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(
                        context ->
                                context.securityContextRepository(
                                        new RequestAttributeSecurityContextRepository()))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(errorResponder)
                                        .accessDeniedHandler(errorResponder))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    // 배치 컨테이너는 웹 서버가 없어 필터 체인만 웹 애플리케이션에서 만든다.
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityErrorResponder errorResponder,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository) {
        http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(HttpMethod.POST, LOGIN_PATH)
                                        .permitAll()
                                        .requestMatchers(PUBLIC_PATHS)
                                        .permitAll()
                                        // 정확한 GET 경로만 허용한다. 다른 Actuator 경로와 CSRF 규칙은 유지한다.
                                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                                        .permitAll()
                                        .requestMatchers(PASSWORD_CHANGE_ALLOWED_PATHS)
                                        .authenticated()
                                        .anyRequest()
                                        .access(SecurityConfig::passwordChanged))
                // 화면은 XSRF-TOKEN 쿠키 값을 그대로 X-XSRF-TOKEN 헤더에 넣는다. 로그인 요청은 세션이 없어 검사하지 않는다(API 001).
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository)
                                        .csrfTokenRequestHandler(
                                                new CsrfTokenRequestAttributeHandler())
                                        .ignoringRequestMatchers(LOGIN_PATH))
                .securityContext(
                        context -> context.securityContextRepository(securityContextRepository))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(errorResponder)
                                        .accessDeniedHandler(errorResponder))
                // 로그인 안 한 요청을 세션에 저장하지 않는다. 저장하면 익명 요청마다 세션 행이 생긴다.
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private static AuthorizationDecision passwordChanged(
            Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        return new AuthorizationDecision(
                current != null
                        && current.getPrincipal() instanceof LoginAccount account
                        && !account.mustChangePassword());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }
}
