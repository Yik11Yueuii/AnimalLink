package org.animallink.adoption.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ValidationException;
import java.time.Instant;
import org.animallink.adoption.domain.AnimalNotEligibleException;
import org.animallink.adoption.domain.AnimalNotFoundException;
import org.animallink.adoption.domain.DependencyUnavailableException;
import org.animallink.adoption.domain.InvalidListingTransitionException;
import org.animallink.adoption.domain.ListingAccessDeniedException;
import org.animallink.adoption.domain.ListingAlreadyExistsException;
import org.animallink.adoption.domain.ListingNotFoundException;
import org.animallink.adoption.domain.ApplicationNotFoundException;
import org.animallink.adoption.domain.ApplicationNotOwnerException;
import org.animallink.adoption.domain.ApplicationAlreadyExistsException;
import org.animallink.adoption.domain.InvalidApplicationTransitionException;
import org.animallink.adoption.domain.ListingNotOpenForApplicationException;
import org.animallink.adoption.domain.ApplicantNotEligibleException;
import org.animallink.adoption.domain.ApplicationNotApprovedException;
import org.animallink.adoption.domain.ListingAlreadySelectedException;
import org.animallink.adoption.domain.ListingNotSelectableException;
import org.animallink.adoption.domain.SelectionNotFoundException;
import org.animallink.adoption.domain.HandoverNotFoundException;
import org.animallink.adoption.domain.HandoverAlreadyExistsException;
import org.animallink.adoption.domain.InvalidHandoverTransitionException;
import org.animallink.adoption.domain.SelectionNotActiveException;
import org.animallink.adoption.domain.AdoptionRelationNotFoundException;
import org.animallink.adoption.domain.InvalidAdoptionRelationTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ListingNotFoundException.class) ResponseEntity<ApiError> missing(ListingNotFoundException e, HttpServletRequest r) { return response(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND", e.getMessage(), r); }
    @ExceptionHandler(AnimalNotFoundException.class) ResponseEntity<ApiError> animalMissing(AnimalNotFoundException e, HttpServletRequest r) { return response(HttpStatus.NOT_FOUND, "ANIMAL_NOT_FOUND", e.getMessage(), r); }
    @ExceptionHandler(ListingAccessDeniedException.class) ResponseEntity<ApiError> forbidden(ListingAccessDeniedException e, HttpServletRequest r) { return response(HttpStatus.FORBIDDEN, "GOVERNANCE_REQUIRED", e.getMessage(), r); }
    @ExceptionHandler(AnimalNotEligibleException.class) ResponseEntity<ApiError> ineligible(AnimalNotEligibleException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "ANIMAL_NOT_ELIGIBLE", e.getMessage(), r); }
    @ExceptionHandler(ListingAlreadyExistsException.class) ResponseEntity<ApiError> exists(ListingAlreadyExistsException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "ACTIVE_LISTING_EXISTS", e.getMessage(), r); }
    @ExceptionHandler(InvalidListingTransitionException.class) ResponseEntity<ApiError> transition(InvalidListingTransitionException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "INVALID_LISTING_TRANSITION", e.getMessage(), r); }
    @ExceptionHandler(DependencyUnavailableException.class) ResponseEntity<ApiError> dependency(DependencyUnavailableException e, HttpServletRequest r) { return response(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", e.getMessage(), r); }
    @ExceptionHandler(ApplicationNotFoundException.class) ResponseEntity<ApiError> applicationMissing(ApplicationNotFoundException e, HttpServletRequest r) { return response(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", e.getMessage(), r); }
    @ExceptionHandler(ApplicationNotOwnerException.class) ResponseEntity<ApiError> applicationOwner(ApplicationNotOwnerException e, HttpServletRequest r) { return response(HttpStatus.FORBIDDEN, "APPLICATION_NOT_OWNER", e.getMessage(), r); }
    @ExceptionHandler(ApplicantNotEligibleException.class) ResponseEntity<ApiError> applicantEligible(ApplicantNotEligibleException e, HttpServletRequest r) { return response(HttpStatus.FORBIDDEN, "APPLICANT_NOT_ELIGIBLE", e.getMessage(), r); }
    @ExceptionHandler(ApplicationAlreadyExistsException.class) ResponseEntity<ApiError> applicationExists(ApplicationAlreadyExistsException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "APPLICATION_ALREADY_EXISTS", e.getMessage(), r); }
    @ExceptionHandler(ListingNotOpenForApplicationException.class) ResponseEntity<ApiError> listingClosed(ListingNotOpenForApplicationException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "LISTING_NOT_OPEN_FOR_APPLICATION", e.getMessage(), r); }
    @ExceptionHandler(InvalidApplicationTransitionException.class) ResponseEntity<ApiError> applicationTransition(InvalidApplicationTransitionException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "INVALID_APPLICATION_TRANSITION", e.getMessage(), r); }
    @ExceptionHandler(ApplicationNotApprovedException.class) ResponseEntity<ApiError> applicationNotApproved(ApplicationNotApprovedException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "APPLICATION_NOT_APPROVED", e.getMessage(), r); }
    @ExceptionHandler(ListingAlreadySelectedException.class) ResponseEntity<ApiError> listingSelected(ListingAlreadySelectedException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "LISTING_ALREADY_SELECTED", e.getMessage(), r); }
    @ExceptionHandler(ListingNotSelectableException.class) ResponseEntity<ApiError> listingNotSelectable(ListingNotSelectableException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "LISTING_NOT_SELECTABLE", e.getMessage(), r); }
    @ExceptionHandler(SelectionNotFoundException.class) ResponseEntity<ApiError> selectionMissing(SelectionNotFoundException e, HttpServletRequest r) { return response(HttpStatus.NOT_FOUND, "SELECTION_NOT_FOUND", e.getMessage(), r); }
    @ExceptionHandler(HandoverNotFoundException.class) ResponseEntity<ApiError> handoverMissing(HandoverNotFoundException e, HttpServletRequest r) { return response(HttpStatus.NOT_FOUND, "HANDOVER_NOT_FOUND", e.getMessage(), r); }
    @ExceptionHandler(HandoverAlreadyExistsException.class) ResponseEntity<ApiError> handoverExists(HandoverAlreadyExistsException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "HANDOVER_ALREADY_EXISTS", e.getMessage(), r); }
    @ExceptionHandler(InvalidHandoverTransitionException.class) ResponseEntity<ApiError> handoverTransition(InvalidHandoverTransitionException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "INVALID_HANDOVER_TRANSITION", e.getMessage(), r); }
    @ExceptionHandler(SelectionNotActiveException.class) ResponseEntity<ApiError> selectionInactive(SelectionNotActiveException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "SELECTION_NOT_ACTIVE", e.getMessage(), r); }
    @ExceptionHandler(AdoptionRelationNotFoundException.class) ResponseEntity<ApiError> relationMissing(AdoptionRelationNotFoundException e, HttpServletRequest r) { return response(HttpStatus.NOT_FOUND, "ADOPTION_RELATION_NOT_FOUND", e.getMessage(), r); }
    @ExceptionHandler(InvalidAdoptionRelationTransitionException.class) ResponseEntity<ApiError> relationTransition(InvalidAdoptionRelationTransitionException e, HttpServletRequest r) { return response(HttpStatus.CONFLICT, "INVALID_ADOPTION_RELATION_TRANSITION", e.getMessage(), r); }
    @ExceptionHandler({IllegalArgumentException.class, ValidationException.class}) ResponseEntity<ApiError> invalid(Exception e, HttpServletRequest r) { return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), r); }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e, HttpServletRequest r) { String message = e.getBindingResult().getFieldErrors().stream().findFirst().map(x -> x.getField() + ": " + x.getDefaultMessage()).orElse("请求参数校验失败"); return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, r); }
    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message, HttpServletRequest request) { return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI())); }
    public record ApiError(Instant timestamp, int status, String code, String message, String path) { }
}
