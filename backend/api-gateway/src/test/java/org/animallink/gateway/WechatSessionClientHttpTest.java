package org.animallink.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class WechatSessionClientHttpTest {
    private static final String KEYS = "v1=" + Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String CODE = "fake-code-123";
    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.disposeNow();
    }

    @Test
    void exactInternalErrorCodesReachTheMiniappWithDistinctSafeStatuses() {
        for (Failure failure : new Failure[] {
                new Failure(401, "WECHAT_CODE_INVALID", HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID"),
                new Failure(403, "WECHAT_USER_BLOCKED", HttpStatus.FORBIDDEN, "WECHAT_USER_BLOCKED"),
                new Failure(429, "WECHAT_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "WECHAT_RATE_LIMITED"),
                new Failure(503, "WECHAT_EXCHANGE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_LOGIN_UNAVAILABLE"),
                new Failure(401, "SERVICE_AUTHENTICATION_FAILED", HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_LOGIN_UNAVAILABLE"),
                new Failure(403, "UNKNOWN_FORBIDDEN", HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_LOGIN_UNAVAILABLE"),
                new Failure(429, "UNKNOWN_LIMIT", HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_LOGIN_UNAVAILABLE") }) {
            AtomicInteger requests = new AtomicInteger();
            AtomicReference<String> assertion = new AtomicReference<>();
            server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
                requests.incrementAndGet();
                assertion.set(request.requestHeaders().get("Authorization"));
                return response.status(failure.internalStatus()).header("Content-Type", "application/json")
                        .sendString(Mono.just("{\"code\":\"" + failure.internalCode() + "\"}" )).then();
            }).bindNow();
            var response = new WechatAuthenticationController(client()).login(
                    new WechatAuthenticationController.LoginRequest(CODE)).block();

            assertThat(response).isNotNull();
            assertThat(response.getStatusCode()).isEqualTo(failure.publicStatus());
            assertThat(response.getBody()).isEqualTo(new WechatAuthenticationController.Error(failure.publicCode()));
            assertThat(assertion.get()).startsWith("Bearer ").doesNotContain(CODE);
            assertThat(requests.get()).isOne();
            server.disposeNow();
            server = null;
        }
    }

    @Test
    void sessionInvalidAndServiceAssertionFailureAreNotConfused() {
        server = fixedResponse(401, "SESSION_INVALID");
        assertThatThrownBy(() -> client().current("A".repeat(43)).block())
                .isInstanceOf(WechatSessionClient.Invalid.class);
        server.disposeNow();

        server = fixedResponse(401, "SERVICE_AUTHENTICATION_FAILED");
        assertThatThrownBy(() -> client().current("A".repeat(43)).block())
                .isInstanceOf(WechatSessionClient.Unavailable.class);
    }

    @Test
    void usedCodeClassificationIsReturnedAs401() {
        server = fixedResponse(401, "WECHAT_CODE_INVALID");
        var response = new WechatAuthenticationController(client()).login(
                new WechatAuthenticationController.LoginRequest(CODE)).block();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(new WechatAuthenticationController.Error("WECHAT_CODE_INVALID"));
    }

    @Test
    void loginHasSeparateLongerBudgetAndTimedOutRequestsAreNotRetried() {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            requests.incrementAndGet();
            return response.header("Content-Type", "application/json").sendString(Mono.delay(Duration.ofMillis(3200))
                    .map(ignored -> "{\"token\":\"" + "A".repeat(43) + "\",\"userId\":\"user\","
                            + "\"displayName\":\"user\",\"idleExpiresAt\":\"2027-01-01T00:00:00Z\","
                            + "\"absoluteExpiresAt\":\"2027-02-01T00:00:00Z\"}")).then();
        }).bindNow();
        var longer = client(Duration.ofSeconds(1), Duration.ofSeconds(5));
        assertThat(longer.login(CODE).block().userId()).isEqualTo("user");
        assertThat(requests.get()).isOne();
        var shortLogin = client(Duration.ofSeconds(1), Duration.ofMillis(250));
        assertThatThrownBy(() -> shortLogin.login(CODE).block())
                .isInstanceOf(WechatSessionClient.Unavailable.class)
                .hasMessageNotContaining(CODE);
        assertThat(requests.get()).isEqualTo(2);
        assertThatThrownBy(() -> longer.current("A".repeat(43)).block())
                .isInstanceOf(WechatSessionClient.Unavailable.class);
        assertThat(requests.get()).isEqualTo(3);
    }

    private DisposableServer fixedResponse(int status, String code) {
        return HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                response.status(status).header("Content-Type", "application/json")
                        .sendString(Mono.just("{\"code\":\"" + code + "\"}")).then()).bindNow();
    }

    private WechatSessionClient client() {
        return client(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private WechatSessionClient client(Duration timeout, Duration loginTimeout) {
        var signer = new ServiceAssertionSigner("api-gateway", "api-gateway", "identity-service",
                Duration.ofSeconds(15), "v1", KEYS,
                Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));
        return new WechatSessionClient(WebClient.builder(), signer,
                "http://127.0.0.1:" + server.port(), Duration.ofSeconds(1), timeout, loginTimeout);
    }

    private record Failure(int internalStatus, String internalCode, HttpStatus publicStatus, String publicCode) {
    }
}
