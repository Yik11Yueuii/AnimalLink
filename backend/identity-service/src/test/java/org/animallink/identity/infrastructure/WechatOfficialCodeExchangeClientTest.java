package org.animallink.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.animallink.identity.application.WechatCodeExchangeClient;
import org.animallink.identity.application.WechatAuthenticationService;
import org.animallink.identity.domain.WechatAuthenticationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class WechatOfficialCodeExchangeClientTest {
    private static final String APP_ID = "fake-app+id&value";
    private static final String APP_SECRET = "fake-secret+&=/%";
    private static final String CODE = "fake-code+&=/%";
    private static final String SESSION_KEY = "fake-session-key-never-returned";

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(200, "{}", Duration.ZERO));
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> rawQuery = new AtomicReference<>();
    private final AtomicReference<String> redirectLocation = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger redirectTargetCalls = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/sns/jscode2session", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            Reply current = reply.get();
            try {
                Thread.sleep(current.delay());
                byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (redirectLocation.get() != null) exchange.getResponseHeaders().set("Location", redirectLocation.get());
                exchange.sendResponseHeaders(current.status(), body.length == 0 ? -1 : body.length);
                if (body.length > 0) exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException disconnected) {
                // A read-timeout test deliberately closes the client connection first.
            } finally {
                exchange.close();
            }
        });
        server.createContext("/redirect-target", exchange -> {
            redirectTargetCalls.incrementAndGet();
            byte[] body = "{\"openid\":\"openid-valid123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) server.stop(0);
        if (executor != null) executor.close();
        server = null;
        executor = null;
    }

    @Test
    void exchangesEncodedParametersAndReturnsOnlyPublicIdentityFields(CapturedOutput output) {
        reply.set(new Reply(200, "{\"errcode\":0,\"openid\":\"openid-valid123\","
                + "\"unionid\":\"unionid-valid123\",\"session_key\":\"" + SESSION_KEY + "\"}", Duration.ZERO));

        var result = client(APP_ID, APP_SECRET, Duration.ofSeconds(1)).exchange(CODE);

        assertThat(result.openId()).isEqualTo("openid-valid123");
        assertThat(result.unionId()).isEqualTo("unionid-valid123");
        assertThat(result.toString()).doesNotContain(SESSION_KEY);
        assertThat(output.getAll()).doesNotContain(APP_SECRET, CODE, SESSION_KEY);
        assertThat(path.get()).isEqualTo("/sns/jscode2session");
        assertThat(query()).containsOnlyKeys("appid", "secret", "js_code", "grant_type")
                .containsEntry("appid", APP_ID).containsEntry("secret", APP_SECRET)
                .containsEntry("js_code", CODE).containsEntry("grant_type", "authorization_code");
        assertThat(rawQuery.get()).doesNotContain(APP_SECRET, CODE);
        assertThat(calls.get()).isOne();
    }

    @Test
    void aSuccessfulResponseMayOmitErrcode() {
        reply.set(new Reply(200, "{\"openid\":\"openid-valid123\"}", Duration.ZERO));
        assertThat(client(APP_ID, APP_SECRET, Duration.ofSeconds(1)).exchange(CODE).openId())
                .isEqualTo("openid-valid123");
    }

    @Test
    void classifiesDocumentedWechatErrorCodesWithoutExposingResponse() {
        assertError("{\"errcode\":-1,\"errmsg\":\"" + APP_SECRET + "\"}",
                WechatCodeExchangeClient.UnavailableException.class);
        assertError("{\"errcode\":40029}", WechatCodeExchangeClient.RejectedCodeException.class);
        assertError("{\"errcode\":40163}", WechatCodeExchangeClient.RejectedCodeException.class);
        assertError("{\"errcode\":40226}", WechatCodeExchangeClient.RiskBlockedException.class);
        assertError("{\"errcode\":45011}", WechatCodeExchangeClient.RateLimitedException.class);
        assertError("{\"errcode\":123456}", WechatCodeExchangeClient.UnavailableException.class);
        assertThat(calls.get()).isEqualTo(6);
    }

    @Test
    void malformedOrMissingPublicIdentityIsAnUpstreamFailureNotAnInvalidUserCode() {
        for (String body : new String[] {"{}", "{\"errcode\":0}",
                "{\"openid\":\"bad!\"}", "{\"openid\":\"short\"}", "{", ""}) {
            assertError(body, WechatCodeExchangeClient.UnavailableException.class);
        }
        assertThat(calls.get()).isEqualTo(6);
    }

    @Test
    void httpFailuresDistinguishRateLimitFromUpstreamRequestAndServerErrors() {
        for (int status : new int[] {400, 401, 500}) {
            reply.set(new Reply(status, "{\"errmsg\":\"" + APP_SECRET + "\"}", Duration.ZERO));
            assertThatThrownBy(() -> client(APP_ID, APP_SECRET, Duration.ofSeconds(1)).exchange(CODE))
                    .isInstanceOf(WechatCodeExchangeClient.UnavailableException.class)
                    .hasMessageNotContaining(APP_SECRET);
        }
        reply.set(new Reply(429, "{}", Duration.ZERO));
        assertThatThrownBy(() -> client(APP_ID, APP_SECRET, Duration.ofSeconds(1)).exchange(CODE))
                .isInstanceOf(WechatCodeExchangeClient.RateLimitedException.class);
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void redirectsNeverFollowOrCreateAUserEvenWhenTheBodyContainsAValidOpenId() {
        redirectLocation.set("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target");
        int expectedRequests = 0;
        for (int status : new int[] {301, 302, 307, 308}) {
            reply.set(new Reply(status, "{\"errcode\":0,\"openid\":\"openid-valid123\"}", Duration.ZERO));
            var repository = mock(WechatAuthenticationRepository.class);
            var service = new WechatAuthenticationService(
                    client(APP_ID, APP_SECRET, Duration.ofSeconds(1)), repository,
                    new SecureOpaqueSessionTokenCodec(), () -> Instant.parse("2026-10-31T00:00:00Z"),
                    APP_ID, Duration.ofDays(7), Duration.ofDays(30));

            assertThatThrownBy(() -> service.login("valid-code-123"))
                    .isInstanceOf(WechatCodeExchangeClient.UnavailableException.class);
            verifyNoInteractions(repository);
            assertThat(calls.get()).isEqualTo(++expectedRequests);
            assertThat(redirectTargetCalls.get()).isZero();
        }
    }

    @Test
    void missingServerSideCredentialsNeverMakeARequest() {
        assertThatThrownBy(() -> client("", APP_SECRET, Duration.ofSeconds(1)).exchange(CODE))
                .isInstanceOf(WechatCodeExchangeClient.UnavailableException.class);
        assertThatThrownBy(() -> client(APP_ID, "", Duration.ofSeconds(1)).exchange(CODE))
                .isInstanceOf(WechatCodeExchangeClient.UnavailableException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void productionConfigurationAcceptsAnAbsoluteHttpsBaseUrl() {
        new WechatOfficialCodeExchangeClient(APP_ID, APP_SECRET, "https://api.weixin.qq.com",
                Duration.ofMillis(100), Duration.ofSeconds(1));
        assertThat(calls.get()).isZero();
    }

    @Test
    void productionConfigurationRejectsEveryHttpHostIncludingLoopback() {
        for (String baseUrl : new String[] {"http://example.org", "http://192.168.1.20:8080",
                "http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080"}) {
            assertInvalidBaseUrl(baseUrl);
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void productionConfigurationRejectsMalformedOrSensitiveBaseUrlsWithoutARequest() {
        for (String baseUrl : new String[] {null, "", "/sns/jscode2session", "https://",
                "ftp://api.weixin.qq.com", "https://user:password@api.weixin.qq.com",
                "https://api.weixin.qq.com?secret=fake", "https://api.weixin.qq.com#fragment",
                "https://api.weixin.qq.com:0", "https://api.weixin.qq.com:65536",
                "https://api.weixin.qq.com:not-a-port"}) {
            assertInvalidBaseUrl(baseUrl);
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void connectionFailureAndLoopbackReadTimeoutFailClosedWithoutLeakingParameters() {
        int closedPort = server.getAddress().getPort();
        stopStub();
        assertThatThrownBy(() -> client(APP_ID, APP_SECRET, Duration.ofMillis(100), closedPort).exchange(CODE))
                .isInstanceOf(WechatCodeExchangeClient.UnavailableException.class)
                .hasMessageNotContaining(APP_SECRET);

        // Start another loopback stub; the response delay exceeds the client's read timeout.
        try {
            startStub();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        reply.set(new Reply(200, "{\"openid\":\"openid-valid123\"}", Duration.ofMillis(500)));
        assertThatThrownBy(() -> client(APP_ID, APP_SECRET, Duration.ofMillis(80)).exchange(CODE))
                .isInstanceOf(WechatCodeExchangeClient.UnavailableException.class)
                .hasMessageNotContaining(CODE);
        assertThat(calls.get()).isOne();
    }

    @Test
    void delayedWechatResponsePastThreeSecondsStillSucceedsWithinReadBudget(CapturedOutput output) {
        reply.set(new Reply(200, "{\"openid\":\"openid-valid123\",\"session_key\":\"" + SESSION_KEY + "\"}", Duration.ofMillis(3200)));
        assertThat(client(APP_ID, APP_SECRET, Duration.ofSeconds(5)).exchange(CODE).openId())
                .isEqualTo("openid-valid123");
        assertThat(calls.get()).isOne();
        assertThat(output.getAll()).doesNotContain(APP_SECRET, CODE, SESSION_KEY);
    }

    @Test
    void delayedWechatTimeoutDoesNotCreateUserOrRetry(CapturedOutput output) {
        reply.set(new Reply(200, "{\"openid\":\"openid-valid123\"}", Duration.ofMillis(800)));
        var repository = mock(WechatAuthenticationRepository.class);
        var service = new WechatAuthenticationService(client(APP_ID, APP_SECRET, Duration.ofMillis(200)),
                repository, new SecureOpaqueSessionTokenCodec(), () -> Instant.parse("2026-10-31T00:00:00Z"),
                APP_ID, Duration.ofDays(7), Duration.ofDays(30));
        assertThatThrownBy(() -> service.login("valid-code-123"))
                .isInstanceOf(WechatCodeExchangeClient.UnavailableException.class);
        verifyNoInteractions(repository);
        assertThat(calls.get()).isOne();
        assertThat(output.getAll()).doesNotContain(APP_SECRET, "valid-code-123", SESSION_KEY);
    }

    @Test
    void consumedWechatCodeMapsToInternal401WithoutCreatingUser(CapturedOutput output) {
        reply.set(new Reply(200, "{\"errcode\":40163,\"errmsg\":\"code been used\"}", Duration.ZERO));
        var repository = mock(WechatAuthenticationRepository.class);
        var service = new WechatAuthenticationService(client(APP_ID, APP_SECRET, Duration.ofSeconds(1)),
                repository, new SecureOpaqueSessionTokenCodec(), () -> Instant.parse("2026-10-31T00:00:00Z"),
                APP_ID, Duration.ofDays(7), Duration.ofDays(30));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(InternalSessionServiceAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
                new ServiceAssertionVerifier.AuthenticatedService("api-gateway", "api-gateway",
                        "identity-service", "assertion-id", null));
        var response = new org.animallink.identity.api.InternalWechatSessionController(service).login(
                new org.animallink.identity.api.InternalWechatSessionController.LoginRequest("valid-code-123"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(new org.animallink.identity.api.InternalWechatSessionController.ErrorResponse("WECHAT_CODE_INVALID"));
        assertThat(calls.get()).isOne();
        verifyNoInteractions(repository);
        assertThat(output.getAll()).doesNotContain(APP_SECRET, "valid-code-123", SESSION_KEY);
    }

    private void assertError(String body, Class<? extends RuntimeException> type) {
        reply.set(new Reply(200, body, Duration.ZERO));
        assertThatThrownBy(() -> client(APP_ID, APP_SECRET, Duration.ofSeconds(1)).exchange(CODE))
                .isInstanceOf(type).hasMessageNotContaining(APP_SECRET).hasMessageNotContaining(CODE)
                .hasMessageNotContaining(SESSION_KEY);
    }

    private WechatOfficialCodeExchangeClient client(String appId, String secret, Duration readTimeout) {
        return client(appId, secret, readTimeout, server.getAddress().getPort());
    }

    private WechatOfficialCodeExchangeClient client(String appId, String secret, Duration readTimeout, int port) {
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build();
        assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        var stubClient = RestClient.builder().baseUrl("http://127.0.0.1:" + port)
                .requestFactory(requestFactory).build();
        return new WechatOfficialCodeExchangeClient(appId, secret, stubClient);
    }

    private void assertInvalidBaseUrl(String baseUrl) {
        assertThatThrownBy(() -> new WechatOfficialCodeExchangeClient(APP_ID, APP_SECRET, baseUrl,
                Duration.ofMillis(100), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("WeChat code exchange base URL must be an absolute HTTPS URL without credentials, query, or fragment");
    }

    private Map<String, String> query() {
        return Arrays.stream(rawQuery.get().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                        part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
    }

    private record Reply(int status, String body, Duration delay) {
    }
}
