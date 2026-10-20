package org.animallink.gateway;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentitySessionClientHttpTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String KEYS = "v1=" + Base64.getEncoder().encodeToString(KEY);
    private static final String TOKEN = "A".repeat(43);
    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.disposeNow();
    }

    @Test
    void signsAndSendsSessionValidationOverRealNonBlockingHttp() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            authorization.set(request.requestHeaders().get("Authorization"));
            return request.receive().aggregate().asString().flatMap(body -> {
                requestBody.set(body);
                return response.status(200).header("Content-Type", "application/json")
                        .sendString(Mono.just("{\"sessionId\":\"session-1\","
                                + "\"userId\":\"user-1\",\"systemRole\":\"GOVERNANCE_ADMIN\","
                                + "\"idleExpiresAt\":\"2026-10-20T12:30:00Z\","
                                + "\"absoluteExpiresAt\":\"2026-10-20T20:00:00Z\"}" )).then();
            });
        }).bindNow();

        var principal = client(Duration.ofSeconds(2)).validate(TOKEN).block();

        assertThat(principal.userId()).isEqualTo("user-1");
        assertThat(requestBody.get()).contains(TOKEN);
        assertThat(authorization.get()).startsWith("Bearer ").doesNotContain(TOKEN);
        SignedJWT assertion = SignedJWT.parse(authorization.get().substring(7));
        assertThat(assertion.verify(new MACVerifier(KEY))).isTrue();
        assertThat(assertion.getJWTClaimsSet().getIssuer()).isEqualTo("api-gateway");
        assertThat(assertion.getJWTClaimsSet().getSubject()).isEqualTo("api-gateway");
        assertThat(assertion.getJWTClaimsSet().getAudience()).containsExactly("identity-service");
        assertThat(assertion.getJWTClaimsSet().getStringClaim("target")).isEqualTo("identity-service");
    }

    @Test
    void invalidSessionAndServiceAuthenticationFailuresHaveDifferentGatewaySemantics() {
        server = fixedResponse(401, "{\"code\":\"SESSION_INVALID\"}", Duration.ZERO);
        assertThatThrownBy(() -> client(Duration.ofSeconds(1)).validate(TOKEN).block())
                .isInstanceOf(IdentitySessionClient.SessionUnauthenticatedException.class);
        server.disposeNow();

        server = fixedResponse(403, "{\"code\":\"SESSION_FORBIDDEN\"}", Duration.ZERO);
        assertThatThrownBy(() -> client(Duration.ofSeconds(1)).validate(TOKEN).block())
                .isInstanceOf(IdentitySessionClient.SessionForbiddenException.class);
        server.disposeNow();

        server = fixedResponse(401, "{\"code\":\"SERVICE_AUTHENTICATION_FAILED\"}", Duration.ZERO);
        assertThatThrownBy(() -> client(Duration.ofSeconds(1)).validate(TOKEN).block())
                .isInstanceOf(IdentitySessionClient.IdentitySessionUnavailableException.class);
    }

    @Test
    void timeoutFailsClosedAsIdentityUnavailable() {
        server = fixedResponse(200, "{}", Duration.ofMillis(500));
        assertThatThrownBy(() -> client(Duration.ofMillis(100)).validate(TOKEN).block())
                .isInstanceOf(IdentitySessionClient.IdentitySessionUnavailableException.class);
    }

    private DisposableServer fixedResponse(int status, String body, Duration delay) {
        return HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                Mono.delay(delay).then(response.status(status).header("Content-Type", "application/json")
                        .sendString(Mono.just(body)).then())).bindNow();
    }

    private IdentitySessionClient client(Duration timeout) {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        var signer = new ServiceAssertionSigner("api-gateway", "api-gateway", "identity-service",
                Duration.ofSeconds(15), "v1", KEYS, Clock.fixed(now, ZoneOffset.UTC));
        return new IdentitySessionClient(WebClient.builder(), signer,
                "http://127.0.0.1:" + server.port(), Duration.ofSeconds(1), timeout);
    }
}
