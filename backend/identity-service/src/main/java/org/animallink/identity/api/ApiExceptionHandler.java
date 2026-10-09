package org.animallink.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ValidationException;
import org.animallink.identity.domain.ConflictException;
import org.animallink.identity.domain.ForbiddenException;
import org.animallink.identity.domain.NotFoundException;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.CredentialStorageException;
import org.animallink.identity.domain.UnsupportedCredentialMaterialException;
import org.animallink.identity.domain.CredentialObjectMissingException;
import org.animallink.identity.domain.VolunteerMembershipException;
import org.animallink.identity.domain.VolunteerMembershipNotFoundException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(VolunteerMembershipException.class)
    public ResponseEntity<ApiError> volunteerMembership(VolunteerMembershipException exception,
                                                         HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "CAMPUS_MEMBERSHIP_REQUIRED", "GOVERNANCE_REQUIRED" -> HttpStatus.FORBIDDEN;
            case "VOLUNTEER_APPLICATION_EXISTS", "INVALID_VOLUNTEER_TRANSITION" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(status, exception.code(), exception.getMessage(), request);
    }
    @ExceptionHandler(VolunteerMembershipNotFoundException.class)
    public ResponseEntity<ApiError> volunteerMembershipNotFound(VolunteerMembershipNotFoundException exception,
                                                                 HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "VOLUNTEER_MEMBERSHIP_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> unauthorized(UnauthorizedException exception, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", exception.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(UnsupportedCredentialMaterialException.class)
    public ResponseEntity<ApiError> unsupportedCredential(UnsupportedCredentialMaterialException exception,
                                                           HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CREDENTIAL_MATERIAL", exception.getMessage(), request);
    }

    @ExceptionHandler(CredentialStorageException.class)
    public ResponseEntity<ApiError> credentialStorage(CredentialStorageException exception,
                                                       HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "凭证存储暂不可用", request);
    }

    @ExceptionHandler(CredentialObjectMissingException.class)
    public ResponseEntity<ApiError> credentialMissing(CredentialObjectMissingException exception,
                                                       HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CREDENTIAL_OBJECT_MISSING", exception.getMessage(), request);
    }

    @ExceptionHandler({ValidationException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> badRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception,
                                               HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> constraint(DataIntegrityViolationException exception,
                                               HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT", "请求与当前数据状态冲突", request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                              HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(), status.value(), code, message, request.getRequestURI(), MDC.get("traceId")));
    }

    public record ApiError(
            Instant timestamp,
            int status,
            String code,
            String message,
            String path,
            String traceId) {
    }
}
