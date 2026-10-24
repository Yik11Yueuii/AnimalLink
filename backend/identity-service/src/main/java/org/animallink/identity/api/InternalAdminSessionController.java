package org.animallink.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.animallink.identity.application.AdminAuthenticationService;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.infrastructure.InternalSessionServiceAuthenticationFilter;
import org.animallink.identity.infrastructure.ServiceAssertionVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/internal/v1/auth/sessions")
public class InternalAdminSessionController {
    private final AdminAuthenticationService authenticationService;

    public InternalAdminSessionController(AdminAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@Valid @RequestBody SessionValidationRequest request,
                                      HttpServletRequest servletRequest) {
        Object principal = servletRequest.getAttribute(
                InternalSessionServiceAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof ServiceAssertionVerifier.AuthenticatedService)) {
            return error(HttpStatus.UNAUTHORIZED, "SERVICE_AUTHENTICATION_FAILED");
        }
        try {
            var session = authenticationService.validateAdminSession(request.token());
            return ResponseEntity.ok(new SessionValidationResponse(
                    session.sessionId(), session.user().id(), session.user().systemRole().name(),
                    session.idleExpiresAt(), session.absoluteExpiresAt()));
        } catch (UnauthorizedException invalid) {
            return error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID");
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_VALIDATION_UNAVAILABLE");
        }
    }

    private static ResponseEntity<InternalError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new InternalError(code));
    }

    public record SessionValidationRequest(
            @NotBlank @Size(min = 32, max = 256) String token) {
    }

    public record SessionValidationResponse(String sessionId, String userId, String systemRole,
                                            Instant idleExpiresAt, Instant absoluteExpiresAt) {
    }

    public record InternalError(String code) {
    }
}
