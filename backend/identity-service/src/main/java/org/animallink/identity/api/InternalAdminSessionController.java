package org.animallink.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.animallink.identity.application.AdminAuthenticationService;
import org.animallink.identity.domain.LoginRateLimitedException;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.infrastructure.InternalSessionServiceAuthenticationFilter;
import org.animallink.identity.infrastructure.ServiceAssertionVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;

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

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AdminLoginRequest request,
                                   HttpServletRequest servletRequest) {
        if (!hasAuthenticatedGateway(servletRequest)) {
            return error(HttpStatus.UNAUTHORIZED, "SERVICE_AUTHENTICATION_FAILED");
        }
        try {
            var session = authenticationService.createAdminSession(
                    request.username(), request.password(), request.trustedSource());
            return ResponseEntity.ok(new IssuedSessionResponse(session.rawToken(), session.sessionId(),
                    session.userId(), "GOVERNANCE_ADMIN", session.idleExpiresAt(), session.absoluteExpiresAt()));
        } catch (LoginRateLimitedException limited) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED");
        } catch (UnauthorizedException rejected) {
            return error(HttpStatus.UNAUTHORIZED, "CREDENTIALS_INVALID");
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "LOGIN_UNAVAILABLE");
        }
    }

    @PostMapping("/revoke")
    public ResponseEntity<?> revoke(@Valid @RequestBody SessionValidationRequest request,
                                    HttpServletRequest servletRequest) {
        if (!hasAuthenticatedGateway(servletRequest)) {
            return error(HttpStatus.UNAUTHORIZED, "SERVICE_AUTHENTICATION_FAILED");
        }
        try {
            return ResponseEntity.ok(new SessionRevocationResponse(
                    authenticationService.revokeCurrentSession(request.token(), "ADMIN_HTTP_LOGOUT")));
        } catch (UnauthorizedException invalid) {
            return error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID");
        } catch (RuntimeException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_REVOCATION_UNAVAILABLE");
        }
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<InternalError> invalidRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_INVALID");
    }

    private static boolean hasAuthenticatedGateway(HttpServletRequest servletRequest) {
        return servletRequest.getAttribute(InternalSessionServiceAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
                instanceof ServiceAssertionVerifier.AuthenticatedService;
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

    public record AdminLoginRequest(
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(max = 80)
            @Pattern(regexp = "^ip:[0-9A-Fa-f:.]{3,64}$") String trustedSource) {
    }

    public record IssuedSessionResponse(String token, String sessionId, String userId, String systemRole,
                                        Instant idleExpiresAt, Instant absoluteExpiresAt) {
    }

    public record SessionRevocationResponse(boolean revoked) {
    }

    public record InternalError(String code) {
    }
}
