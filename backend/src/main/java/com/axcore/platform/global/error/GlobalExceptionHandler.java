package com.axcore.platform.global.error;

import com.axcore.platform.global.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 API 예외를 공통 오류 응답으로 바꾼다. Spring MVC 표준 예외는 상위 클래스가 상태 코드를 정하고, {@link
 * #handleExceptionInternal}에서 공통 형식으로 맞춘다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String DEFAULT_FIELD = "request";
    private static final String DEFAULT_REASON = "형식 또는 필수 값 오류";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(
            BusinessException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getErrorCode().status())
                .body(
                        ErrorResponse.of(
                                ex.getErrorCode(),
                                ex.getMessage(),
                                ex.getDetails(),
                                RequestIdFilter.currentRequestId(request)));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        return respond(CommonErrorCode.VERSION_CONFLICT, Map.of(), request);
    }

    /** 도메인이 미리 잡지 못한 DB 제약 위반. 예상할 수 있는 중복은 Service에서 구체적인 코드로 던진다. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("DB 제약 위반: constraint={}", constraintName(ex));
        return respond(CommonErrorCode.CONFLICT, Map.of(), request);
    }

    /** 원본 메시지에는 중복된 실제 값이 들어 있어 로그에 남기지 않는다. 제약 이름만 남긴다. */
    private static String constraintName(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return "unknown";
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("처리하지 못한 예외", ex);
        return respond(CommonErrorCode.INTERNAL_ERROR, Map.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        CommonErrorCode errorCode = CommonErrorCode.fromStatus(statusCode.value());
        if (errorCode == CommonErrorCode.INTERNAL_ERROR) {
            log.error("처리하지 못한 예외", ex);
        }
        Map<String, Object> details =
                errorCode == CommonErrorCode.VALIDATION_FAILED ? validationDetails(ex) : Map.of();
        return ResponseEntity.status(errorCode.status())
                .headers(headers)
                .body(ErrorResponse.of(errorCode, details, requestId(request)));
    }

    private ResponseEntity<ErrorResponse> respond(
            ErrorCode errorCode, Map<String, Object> details, HttpServletRequest request) {
        return ResponseEntity.status(errorCode.status())
                .body(
                        ErrorResponse.of(
                                errorCode, details, RequestIdFilter.currentRequestId(request)));
    }

    /** 첫 번째 오류는 field·reason에, 전체 목록은 errors에 담는다. */
    private static Map<String, Object> validationDetails(Exception ex) {
        List<ValidationError> errors = validationErrors(ex);
        ValidationError first = errors.getFirst();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", first.field());
        details.put("reason", first.reason());
        details.put("errors", errors);
        return details;
    }

    private static List<ValidationError> validationErrors(Exception ex) {
        List<ValidationError> errors = new ArrayList<>();
        switch (ex) {
            case BindException bind -> addBindingErrors(bind, errors);
            // 경로 변수 등에 제약이 있으면 @Valid 본문 오류도 여기로 온다. 본문 오류(ParameterErrors)는 칸 이름으로 알린다.
            case HandlerMethodValidationException validation -> {
                for (ParameterValidationResult result :
                        validation.getParameterValidationResults()) {
                    if (result instanceof ParameterErrors parameterErrors) {
                        addBindingErrors(parameterErrors, errors);
                        continue;
                    }
                    String field = result.getMethodParameter().getParameterName();
                    result.getResolvableErrors()
                            .forEach(
                                    error -> errors.add(new ValidationError(field, reason(error))));
                }
            }
            case TypeMismatchException mismatch ->
                    errors.add(new ValidationError(mismatch.getPropertyName(), "형식이 올바르지 않습니다."));
            case MissingServletRequestParameterException missing ->
                    errors.add(new ValidationError(missing.getParameterName(), "필수 값입니다."));
            case HttpMessageNotReadableException ignored ->
                    errors.add(new ValidationError(DEFAULT_FIELD, "요청 본문을 읽을 수 없습니다."));
            default -> {}
        }
        if (errors.isEmpty()) {
            errors.add(new ValidationError(DEFAULT_FIELD, DEFAULT_REASON));
        }
        return errors;
    }

    private static void addBindingErrors(Errors bindingErrors, List<ValidationError> errors) {
        for (FieldError error : bindingErrors.getFieldErrors()) {
            errors.add(new ValidationError(error.getField(), reason(error)));
        }
        bindingErrors
                .getGlobalErrors()
                .forEach(
                        error ->
                                errors.add(
                                        new ValidationError(error.getObjectName(), reason(error))));
    }

    private static String reason(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : DEFAULT_REASON;
    }

    private static String requestId(WebRequest request) {
        if (request instanceof NativeWebRequest nativeRequest
                && nativeRequest.getNativeRequest() instanceof HttpServletRequest servletRequest) {
            return RequestIdFilter.currentRequestId(servletRequest);
        }
        return null;
    }

    public record ValidationError(String field, String reason) {}
}
