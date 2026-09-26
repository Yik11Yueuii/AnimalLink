package org.animallink.intelligence.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ValidationException;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(Unauthorized.class)
    ResponseEntity<ApiError> unauthorized(Unauthorized e, HttpServletRequest r) {
        return response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage(), r);
    }

    @ExceptionHandler(Forbidden.class)
    ResponseEntity<ApiError> forbidden(Forbidden e, HttpServletRequest r) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage(), r);
    }

    @ExceptionHandler({NotFound.class, MediaNotFound.class})
    ResponseEntity<ApiError> notFound(RuntimeException e, HttpServletRequest r) {
        String code = e instanceof MediaNotFound ? "MEDIA_NOT_FOUND" : "RESOURCE_NOT_FOUND";
        return response(HttpStatus.NOT_FOUND, code, e.getMessage(), r);
    }

    @ExceptionHandler(UnsupportedMedia.class)
    ResponseEntity<ApiError> unsupported(UnsupportedMedia e, HttpServletRequest r) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA", e.getMessage(), r);
    }

    @ExceptionHandler({Conflict.class, DataIntegrityViolationException.class})
    ResponseEntity<ApiError> conflict(RuntimeException e, HttpServletRequest r) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", "请求与当前任务状态冲突", r);
    }

    @ExceptionHandler(ProviderTimeout.class)
    ResponseEntity<ApiError> timeout(ProviderTimeout e, HttpServletRequest r) {
        return response(HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT", e.getMessage(), r);
    }

    @ExceptionHandler(InvalidModelResponse.class)
    ResponseEntity<ApiError> invalidModel(InvalidModelResponse e, HttpServletRequest r) {
        return response(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_RESPONSE", e.getMessage(), r);
    }

    @ExceptionHandler(ProviderUnavailable.class)
    ResponseEntity<ApiError> providerUnavailable(ProviderUnavailable e, HttpServletRequest r) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE", e.getMessage(), r);
    }

    @ExceptionHandler(DependencyUnavailable.class)
    ResponseEntity<ApiError> dependencyUnavailable(DependencyUnavailable e, HttpServletRequest r) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", e.getMessage(), r);
    }

    @ExceptionHandler({ValidationException.class, IllegalArgumentException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> badRequest(Exception e, HttpServletRequest r) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), r);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
        String message = e.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, r);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                              HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code,
                message, request.getRequestURI(), MDC.get("traceId")));
    }

    public record ApiError(Instant timestamp, int status, String code, String message,
                           String path, String traceId) {}
}
