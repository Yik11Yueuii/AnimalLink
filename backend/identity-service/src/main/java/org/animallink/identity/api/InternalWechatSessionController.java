package org.animallink.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.animallink.identity.application.WechatAuthenticationService;
import org.animallink.identity.application.WechatCodeExchangeClient;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.infrastructure.InternalSessionServiceAuthenticationFilter;
import org.animallink.identity.infrastructure.ServiceAssertionVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gateway-only boundary: neither WeChat codes nor Mini Program tokens reach public identity routes. */
@RestController
@RequestMapping("/internal/v1/wechat/sessions")
public class InternalWechatSessionController {
    private final WechatAuthenticationService authenticationService;
    public InternalWechatSessionController(WechatAuthenticationService authenticationService) { this.authenticationService = authenticationService; }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        if (!gateway(servletRequest)) return error(HttpStatus.UNAUTHORIZED, "SERVICE_AUTHENTICATION_FAILED");
        try { var session = authenticationService.login(request.code()); return ResponseEntity.ok(new SessionResponse(session.token(), session.userId(), session.displayName(), session.idleExpiresAt(), session.absoluteExpiresAt())); }
        catch (WechatCodeExchangeClient.RejectedCodeException | UnauthorizedException invalid) { return error(HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID"); }
        catch (WechatCodeExchangeClient.UnavailableException unavailable) { return error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_EXCHANGE_UNAVAILABLE"); }
        catch (RuntimeException unavailable) { return error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_LOGIN_UNAVAILABLE"); }
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@Valid @RequestBody TokenRequest request, HttpServletRequest servletRequest) {
        if (!gateway(servletRequest)) return error(HttpStatus.UNAUTHORIZED, "SERVICE_AUTHENTICATION_FAILED");
        try { var session = authenticationService.validate(request.token()); return ResponseEntity.ok(new CurrentUserResponse(session.userId(), session.displayName(), "USER", session.idleExpiresAt(), session.absoluteExpiresAt())); }
        catch (UnauthorizedException invalid) { return error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID"); }
        catch (RuntimeException unavailable) { return error(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_VALIDATION_UNAVAILABLE"); }
    }

    @PostMapping("/revoke")
    public ResponseEntity<?> revoke(@Valid @RequestBody TokenRequest request, HttpServletRequest servletRequest) {
        if (!gateway(servletRequest)) return error(HttpStatus.UNAUTHORIZED, "SERVICE_AUTHENTICATION_FAILED");
        try { return ResponseEntity.ok(new RevocationResponse(authenticationService.logout(request.token()))); }
        catch (UnauthorizedException invalid) { return error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID"); }
        catch (RuntimeException unavailable) { return error(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_REVOCATION_UNAVAILABLE"); }
    }

    @ExceptionHandler(Exception.class) public ResponseEntity<ErrorResponse> invalid() { return error(HttpStatus.BAD_REQUEST, "REQUEST_INVALID"); }
    private static boolean gateway(HttpServletRequest r) { return r.getAttribute(InternalSessionServiceAuthenticationFilter.PRINCIPAL_ATTRIBUTE) instanceof ServiceAssertionVerifier.AuthenticatedService; }
    private static ResponseEntity<ErrorResponse> error(HttpStatus s, String code) { return ResponseEntity.status(s).body(new ErrorResponse(code)); }
    public record LoginRequest(@NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9_-]{8,128}") String code) {}
    public record TokenRequest(@NotBlank @Size(min=43,max=43) @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {}
    public record SessionResponse(String token, String userId, String displayName, Instant idleExpiresAt, Instant absoluteExpiresAt) {}
    public record CurrentUserResponse(String userId, String displayName, String systemRole, Instant idleExpiresAt, Instant absoluteExpiresAt) {}
    public record RevocationResponse(boolean revoked) {}
    public record ErrorResponse(String code) {}
}
