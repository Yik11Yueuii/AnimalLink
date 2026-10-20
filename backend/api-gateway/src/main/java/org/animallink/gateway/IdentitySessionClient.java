package org.animallink.gateway;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;

@Component
public class IdentitySessionClient implements AdminAuthenticationClient {
    private static final String VALIDATION_PATH = "/internal/v1/auth/sessions/validate";
    private static final String LOGIN_PATH = "/internal/v1/auth/sessions/login";
    private static final String REVOKE_PATH = "/internal/v1/auth/sessions/revoke";

    private final WebClient webClient;
    private final ServiceAssertionSigner assertionSigner;
    private final Duration responseTimeout;

    public IdentitySessionClient(
            @Qualifier("identitySessionWebClientBuilder") WebClient.Builder builder,
            ServiceAssertionSigner assertionSigner,
            @Value("${animallink.authentication.identity-session.base-url:http://identity-service}") String baseUrl,
            @Value("${animallink.authentication.identity-session.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${animallink.authentication.identity-session.response-timeout:PT3S}") Duration responseTimeout) {
        if (baseUrl == null || baseUrl.isBlank() || connectTimeout.isNegative() || connectTimeout.isZero()
                || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid Identity session client configuration");
        }
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(responseTimeout);
        this.webClient = builder.clone().baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient)).build();
        this.assertionSigner = assertionSigner;
        this.responseTimeout = responseTimeout;
    }

    @Override
    public Mono<GatewayAuthenticatedPrincipal> validate(String rawSessionToken) {
        return assertion().flatMap(assertion -> webClient.post().uri(VALIDATION_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + assertion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new ValidationRequest(rawSessionToken))
                        .exchangeToMono(response -> {
                            if (response.statusCode().is2xxSuccessful()) {
                                return response.bodyToMono(ValidationResponse.class).map(this::principal);
                            }
                            if (response.statusCode().equals(HttpStatus.UNAUTHORIZED)) {
                                return response.bodyToMono(ErrorResponse.class)
                                        .defaultIfEmpty(new ErrorResponse(""))
                                        .flatMap(error -> "SESSION_INVALID".equals(error.code())
                                                ? Mono.error(new SessionUnauthenticatedException())
                                                : Mono.error(new IdentitySessionUnavailableException()));
                            }
                            if (response.statusCode().equals(HttpStatus.FORBIDDEN)) {
                                return Mono.error(new SessionForbiddenException());
                            }
                            return response.releaseBody()
                                    .then(Mono.error(new IdentitySessionUnavailableException()));
                        })).transform(this::failClosed);
    }

    @Override
    public Mono<IssuedAdminSession> login(String username, String password, String trustedSource) {
        return assertion().flatMap(assertion -> webClient.post().uri(LOGIN_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + assertion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new LoginRequest(username, password, trustedSource))
                        .exchangeToMono(response -> {
                            if (response.statusCode().is2xxSuccessful()) {
                                return response.bodyToMono(IssuedAdminSession.class)
                                        .flatMap(this::validateIssuedSession);
                            }
                            if (response.statusCode().equals(HttpStatus.TOO_MANY_REQUESTS)) {
                                return response.releaseBody().then(Mono.error(new LoginRateLimitedException()));
                            }
                            if (response.statusCode().equals(HttpStatus.UNAUTHORIZED)) {
                                return response.bodyToMono(ErrorResponse.class)
                                        .defaultIfEmpty(new ErrorResponse(""))
                                        .flatMap(error -> "CREDENTIALS_INVALID".equals(error.code())
                                                ? Mono.error(new LoginRejectedException())
                                                : Mono.error(new IdentitySessionUnavailableException()));
                            }
                            return response.releaseBody()
                                    .then(Mono.error(new IdentitySessionUnavailableException()));
                        })).transform(this::failClosed);
    }

    @Override
    public Mono<RevocationResult> revoke(String rawSessionToken) {
        return assertion().flatMap(assertion -> webClient.post().uri(REVOKE_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + assertion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new ValidationRequest(rawSessionToken))
                        .exchangeToMono(response -> {
                            if (response.statusCode().is2xxSuccessful()) {
                                return response.bodyToMono(RevocationResult.class)
                                        .defaultIfEmpty(new RevocationResult(false));
                            }
                            if (response.statusCode().equals(HttpStatus.UNAUTHORIZED)) {
                                return response.bodyToMono(ErrorResponse.class)
                                        .defaultIfEmpty(new ErrorResponse(""))
                                        .flatMap(error -> "SESSION_INVALID".equals(error.code())
                                                ? Mono.error(new SessionUnauthenticatedException())
                                                : Mono.error(new IdentitySessionUnavailableException()));
                            }
                            return response.releaseBody()
                                    .then(Mono.error(new IdentitySessionUnavailableException()));
                        })).transform(this::failClosed);
    }

    private Mono<String> assertion() {
        return Mono.fromCallable(assertionSigner::sign);
    }

    private <T> Mono<T> failClosed(Mono<T> operation) {
        return operation.timeout(responseTimeout.plusMillis(250))
                .onErrorMap(error -> !(error instanceof SessionUnauthenticatedException)
                                && !(error instanceof SessionForbiddenException)
                                && !(error instanceof LoginRejectedException)
                                && !(error instanceof LoginRateLimitedException)
                                && !(error instanceof IdentitySessionUnavailableException),
                        error -> new IdentitySessionUnavailableException());
    }

    private GatewayAuthenticatedPrincipal principal(ValidationResponse response) {
        if (response == null || response.userId() == null || response.sessionId() == null
                || response.idleExpiresAt() == null || response.absoluteExpiresAt() == null) {
            throw new IdentitySessionUnavailableException();
        }
        if (!"GOVERNANCE_ADMIN".equals(response.systemRole())) throw new SessionForbiddenException();
        return new GatewayAuthenticatedPrincipal(response.sessionId(), response.userId(),
                response.systemRole(), response.idleExpiresAt(), response.absoluteExpiresAt());
    }

    private Mono<IssuedAdminSession> validateIssuedSession(IssuedAdminSession session) {
        if (session == null || session.token() == null || !session.token().matches("[A-Za-z0-9_-]{43}")
                || session.sessionId() == null || session.userId() == null
                || session.idleExpiresAt() == null || session.absoluteExpiresAt() == null
                || !"GOVERNANCE_ADMIN".equals(session.systemRole())) {
            return Mono.error(new IdentitySessionUnavailableException());
        }
        return Mono.just(session);
    }

    record ValidationRequest(String token) {
    }

    record LoginRequest(String username, String password, String trustedSource) {
    }

    record ValidationResponse(String sessionId, String userId, String systemRole,
                              Instant idleExpiresAt, Instant absoluteExpiresAt) {
    }

    record ErrorResponse(String code) {
    }

    public record GatewayAuthenticatedPrincipal(String sessionId, String userId, String systemRole,
                                                Instant idleExpiresAt, Instant absoluteExpiresAt) {
    }

    public record IssuedAdminSession(String token, String sessionId, String userId, String systemRole,
                                     Instant idleExpiresAt, Instant absoluteExpiresAt) {
    }

    public record RevocationResult(boolean revoked) {
    }

    public static final class SessionUnauthenticatedException extends RuntimeException {
    }

    public static final class SessionForbiddenException extends RuntimeException {
    }

    public static final class IdentitySessionUnavailableException extends RuntimeException {
    }

    public static final class LoginRejectedException extends RuntimeException {
    }

    public static final class LoginRateLimitedException extends RuntimeException {
    }
}
