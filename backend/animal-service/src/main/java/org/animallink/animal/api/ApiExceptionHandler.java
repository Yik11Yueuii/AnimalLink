package org.animallink.animal.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ValidationException;
import org.animallink.animal.domain.DependencyUnavailableException;
import org.animallink.animal.domain.ActiveAdoptionRelationRequiredException;
import org.animallink.animal.domain.CrossCampusAnimalException;
import org.animallink.animal.domain.DuplicateFinalizeConflictException;
import org.animallink.animal.domain.ForbiddenException;
import org.animallink.animal.domain.MediaCopyException;
import org.animallink.animal.domain.ProposalAlreadyReviewedException;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.animallink.animal.domain.SelectedAnimalArchivedException;
import org.animallink.animal.domain.SelectedAnimalNotFoundException;
import org.animallink.animal.domain.StateConflictException;
import org.animallink.animal.domain.UnauthorizedException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException exception,
                                      HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ApiError> unauthorized(UnauthorizedException exception,
                                          HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", exception.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ApiError> forbidden(ForbiddenException exception,
                                       HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage(), request);
    }

    @ExceptionHandler(ActiveAdoptionRelationRequiredException.class)
    ResponseEntity<ApiError> activeAdoptionRelationRequired(
            ActiveAdoptionRelationRequiredException exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "ACTIVE_ADOPTION_RELATION_REQUIRED",
                exception.getMessage(), request);
    }

    @ExceptionHandler(StateConflictException.class)
    ResponseEntity<ApiError> conflict(StateConflictException exception,
                                      HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(SelectedAnimalNotFoundException.class)
    ResponseEntity<ApiError> selectedNotFound(SelectedAnimalNotFoundException exception,
                                              HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "SELECTED_ANIMAL_NOT_FOUND",
                exception.getMessage(), request);
    }

    @ExceptionHandler(SelectedAnimalArchivedException.class)
    ResponseEntity<ApiError> selectedArchived(SelectedAnimalArchivedException exception,
                                              HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "SELECTED_ANIMAL_ARCHIVED",
                exception.getMessage(), request);
    }

    @ExceptionHandler(CrossCampusAnimalException.class)
    ResponseEntity<ApiError> crossCampus(CrossCampusAnimalException exception,
                                         HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CROSS_CAMPUS_ANIMAL",
                exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateFinalizeConflictException.class)
    ResponseEntity<ApiError> duplicateFinalize(DuplicateFinalizeConflictException exception,
                                               HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DUPLICATE_FINALIZE_CONFLICT",
                exception.getMessage(), request);
    }

    @ExceptionHandler(ProposalAlreadyReviewedException.class)
    ResponseEntity<ApiError> proposalReviewed(ProposalAlreadyReviewedException exception,
                                              HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "PROPOSAL_ALREADY_REVIEWED",
                exception.getMessage(), request);
    }

    @ExceptionHandler(MediaCopyException.class)
    ResponseEntity<ApiError> mediaCopy(MediaCopyException exception,
                                       HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_COPY_FAILED",
                exception.getMessage(), request);
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    ResponseEntity<ApiError> unavailable(DependencyUnavailableException exception,
                                         HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                exception.getMessage(), request);
    }

    @ExceptionHandler({ValidationException.class, IllegalArgumentException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> badRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception,
                                        HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> constraint(DataIntegrityViolationException exception,
                                        HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT", "请求与当前数据约束冲突", request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                              HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code,
                message, request.getRequestURI(), MDC.get("traceId")));
    }

    public record ApiError(Instant timestamp, int status, String code, String message,
                           String path, String traceId) {
    }
}
