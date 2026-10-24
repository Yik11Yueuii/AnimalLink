package org.animallink.gateway;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/auth/admin")
public class AdminAuthenticationController {
    public static final String CSRF_PATH = "/auth/admin/csrf";
    public static final String LOGIN_PATH = "/auth/admin/login";
    public static final String SESSION_PATH = "/auth/admin/session";
    public static final String LOGOUT_PATH = "/auth/admin/logout";

    private final AdminAuthenticationClient authenticationClient;
    private final TrustedClientSourceResolver trustedSourceResolver;
    private final AdminSessionCookieManager cookieManager;

    public AdminAuthenticationController(AdminAuthenticationClient authenticationClient,
                                         TrustedClientSourceResolver trustedSourceResolver,
                                         AdminSessionCookieManager cookieManager) {
        this.authenticationClient = authenticationClient;
        this.trustedSourceResolver = trustedSourceResolver;
        this.cookieManager = cookieManager;
    }

    @GetMapping("/csrf")
    public Mono<ResponseEntity<?>> csrf(ServerWebExchange exchange) {
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        if (token == null) return Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE, "CSRF_UNAVAILABLE"));
        return token.map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(value.getToken(), value.getHeaderName())));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<?>> login(@Valid @RequestBody LoginRequest request,
                                         ServerWebExchange exchange) {
        return Mono.fromCallable(() -> trustedSourceResolver.resolve(exchange))
                .flatMap(source -> authenticationClient.login(request.username(), request.password(), source))
                .flatMap(session -> {
                    exchange.getResponse().addCookie(cookieManager.active(
                            session.token(), session.idleExpiresAt(), session.absoluteExpiresAt()));
                    return Mono.<ResponseEntity<?>>just(ResponseEntity.ok().cacheControl(CacheControl.noStore())
                            .body(new SessionResponse(session.sessionId(), session.userId(),
                                    session.systemRole(), session.idleExpiresAt(), session.absoluteExpiresAt())));
                })
                .onErrorResume(TrustedClientSourceResolver.UnavailableTrustedSourceException.class,
                        ignored -> Mono.just(error(HttpStatus.FORBIDDEN, "TRUSTED_SOURCE_UNAVAILABLE")))
                .onErrorResume(IdentitySessionClient.LoginRejectedException.class,
                        ignored -> Mono.just(error(HttpStatus.UNAUTHORIZED, "CREDENTIALS_INVALID")))
                .onErrorResume(IdentitySessionClient.LoginRateLimitedException.class,
                        ignored -> Mono.just(error(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED")))
                .onErrorResume(IdentitySessionClient.IdentitySessionUnavailableException.class,
                        ignored -> Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE")));
    }

    @GetMapping("/session")
    public ResponseEntity<?> session(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(GatewayAdminSessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        String token = exchange.getAttribute(GatewayAdminSessionAuthenticationFilter.SESSION_TOKEN_ATTRIBUTE);
        if (!(value instanceof IdentitySessionClient.GatewayAuthenticatedPrincipal principal) || token == null) {
            exchange.getResponse().addCookie(cookieManager.expired());
            return error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID");
        }
        exchange.getResponse().addCookie(cookieManager.active(
                token, principal.idleExpiresAt(), principal.absoluteExpiresAt()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionResponse(
                principal.sessionId(), principal.userId(), principal.systemRole(),
                principal.idleExpiresAt(), principal.absoluteExpiresAt()));
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<?>> logout(ServerWebExchange exchange) {
        String token = exchange.getAttribute(GatewayAdminSessionAuthenticationFilter.SESSION_TOKEN_ATTRIBUTE);
        if (token == null) {
            exchange.getResponse().addCookie(cookieManager.expired());
            return Mono.just((ResponseEntity<?>) ResponseEntity.noContent().build());
        }
        return authenticationClient.revoke(token)
                .flatMap(result -> {
                    exchange.getResponse().addCookie(cookieManager.expired());
                    return Mono.<ResponseEntity<?>>just(ResponseEntity.noContent().build());
                })
                .onErrorResume(IdentitySessionClient.SessionUnauthenticatedException.class, ignored -> {
                    exchange.getResponse().addCookie(cookieManager.expired());
                    return Mono.just((ResponseEntity<?>) ResponseEntity.noContent().build());
                })
                .onErrorResume(IdentitySessionClient.IdentitySessionUnavailableException.class,
                        ignored -> Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE")));
    }

    private static ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(code));
    }

    public record LoginRequest(@NotBlank @Size(max = 128) String username,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record CsrfResponse(String token, String headerName) {
    }

    public record SessionResponse(String sessionId, String userId, String systemRole,
                                  java.time.Instant idleExpiresAt, java.time.Instant absoluteExpiresAt) {
    }

    public record ErrorResponse(String code) {
    }
}
