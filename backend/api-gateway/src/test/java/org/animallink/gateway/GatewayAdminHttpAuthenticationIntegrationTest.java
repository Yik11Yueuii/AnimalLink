package org.animallink.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.profiles.active=secure",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.config.import=optional:classpath:gateway-test-absent.yaml",
        "animallink.authentication.browser.allowed-origins=https://admin.animallink.test"
})
@AutoConfigureWebTestClient
class GatewayAdminHttpAuthenticationIntegrationTest {
    private static final String ORIGIN = "https://admin.animallink.test";
    private static final String SESSION_TOKEN = "A".repeat(43);
    private static final Instant IDLE_EXPIRY = Instant.now().plusSeconds(1200);
    private static final Instant ABSOLUTE_EXPIRY = Instant.now().plusSeconds(7200);

    @Autowired WebTestClient client;
    @MockBean AdminAuthenticationClient authenticationClient;
    @MockBean TrustedClientSourceResolver trustedClientSourceResolver;

    @Test
    void csrfBootstrapAndLoginIssueOnlyHardenedCookieAndMinimalJson() {
        CsrfMaterial csrf = csrf();
        when(trustedClientSourceResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("ip:127.0.0.1");
        when(authenticationClient.login("admin.root", "correct horse battery staple", "ip:127.0.0.1"))
                .thenReturn(Mono.just(issued()));

        String body = "{\"username\":\"admin.root\",\"password\":\"correct horse battery staple\"}";
        EntityExchangeResult<Map> result = login(body, csrf).exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(Map.class).returnResult();

        ResponseCookie cookie = result.getResponseCookies().getFirst("ANIMALLINK_ADMIN_SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getMaxAge()).isPositive();
        assertThat(result.getResponseBody()).containsEntry("userId", "user-1")
                .containsEntry("systemRole", "GOVERNANCE_ADMIN")
                .doesNotContainKey("token").doesNotContainKey("password");
        assertThat(result.getResponseBody().toString()).doesNotContain(SESSION_TOKEN)
                .doesNotContain("correct horse battery staple");
    }

    @Test
    void loginRequiresServerBoundCsrfStateAndStrictBrowserOrigin() {
        CsrfMaterial csrf = csrf();
        CsrfMaterial otherSession = csrf();
        String body = "{\"username\":\"admin.root\",\"password\":\"wrong\"}";

        client.post().uri(AdminAuthenticationController.LOGIN_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .bodyValue(body).exchange().expectStatus().isForbidden();

        client.post().uri(AdminAuthenticationController.LOGIN_PATH)
                .header(HttpHeaders.ORIGIN, "https://evil.example").header("Sec-Fetch-Site", "same-origin")
                .cookie(csrf.cookie().getName(), csrf.cookie().getValue())
                .header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON).contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .bodyValue(body).exchange().expectStatus().isForbidden();

        client.post().uri(AdminAuthenticationController.LOGIN_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .cookie(csrf.cookie().getName(), "attacker-controlled-session")
                .header(csrf.headerName(), "attacker-controlled-token")
                .contentType(MediaType.APPLICATION_JSON).contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .bodyValue(body).exchange().expectStatus().isForbidden();

        client.post().uri(AdminAuthenticationController.LOGIN_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .cookie(csrf.cookie().getName(), csrf.cookie().getValue())
                .header(csrf.headerName(), otherSession.token())
                .contentType(MediaType.APPLICATION_JSON).contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .bodyValue(body).exchange().expectStatus().isForbidden();

        verify(authenticationClient, never()).login(anyString(), anyString(), anyString());
    }

    @Test
    void staleSessionCookieDoesNotBlockFreshLogin() {
        CsrfMaterial csrf = csrf();
        when(trustedClientSourceResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("ip:127.0.0.1");
        when(authenticationClient.login("admin.root", "new password", "ip:127.0.0.1"))
                .thenReturn(Mono.just(issued()));
        String body = "{\"username\":\"admin.root\",\"password\":\"new password\"}";

        login(body, csrf).cookie("ANIMALLINK_ADMIN_SESSION", "expired")
                .exchange().expectStatus().isOk();

        verify(authenticationClient, never()).validate(anyString());
    }

    @Test
    void successfulLoginKeepsServerBoundCsrfStateForImmediateLogout() {
        CsrfMaterial csrf = csrf();
        when(trustedClientSourceResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("ip:127.0.0.1");
        when(authenticationClient.login("admin.root", "password", "ip:127.0.0.1"))
                .thenReturn(Mono.just(issued()));
        when(authenticationClient.revoke(SESSION_TOKEN))
                .thenReturn(Mono.just(new IdentitySessionClient.RevocationResult(true)));
        String body = "{\"username\":\"admin.root\",\"password\":\"password\"}";

        login(body, csrf).exchange().expectStatus().isOk();

        client.post().uri(AdminAuthenticationController.LOGOUT_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .cookie(csrf.cookie().getName(), csrf.cookie().getValue())
                .header(csrf.headerName(), csrf.token())
                .cookie("ANIMALLINK_ADMIN_SESSION", SESSION_TOKEN)
                .exchange().expectStatus().isNoContent();
        verify(authenticationClient).revoke(SESSION_TOKEN);
    }

    @Test
    void sessionRecoveryRefreshesCookieAndLogoutRevokesThenClearsIt() {
        CsrfMaterial csrf = csrf();
        when(authenticationClient.validate(SESSION_TOKEN)).thenReturn(Mono.just(principal()));
        when(authenticationClient.revoke(SESSION_TOKEN))
                .thenReturn(Mono.just(new IdentitySessionClient.RevocationResult(true)));

        EntityExchangeResult<Map> session = client.get().uri(AdminAuthenticationController.SESSION_PATH)
                .cookie("ANIMALLINK_ADMIN_SESSION", SESSION_TOKEN)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
        ResponseCookie refreshed = session.getResponseCookies().getFirst("ANIMALLINK_ADMIN_SESSION");
        assertThat(refreshed).isNotNull();
        assertThat(refreshed.getValue()).isEqualTo(SESSION_TOKEN);
        assertThat(session.getResponseBody()).doesNotContainKey("token");

        EntityExchangeResult<Void> logout = client.post().uri(AdminAuthenticationController.LOGOUT_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .cookie(csrf.cookie().getName(), csrf.cookie().getValue())
                .header(csrf.headerName(), csrf.token())
                .cookie("ANIMALLINK_ADMIN_SESSION", SESSION_TOKEN)
                .exchange().expectStatus().isNoContent().expectBody().isEmpty();
        ResponseCookie cleared = logout.getResponseCookies().getFirst("ANIMALLINK_ADMIN_SESSION");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        verify(authenticationClient).revoke(SESSION_TOKEN);
    }

    @Test
    void logoutRequiresCsrfAndOriginBeforeRevocation() {
        CsrfMaterial csrf = csrf();

        client.post().uri(AdminAuthenticationController.LOGOUT_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .cookie("ANIMALLINK_ADMIN_SESSION", SESSION_TOKEN)
                .exchange().expectStatus().isForbidden();

        client.post().uri(AdminAuthenticationController.LOGOUT_PATH)
                .header(HttpHeaders.ORIGIN, "https://evil.example").header("Sec-Fetch-Site", "same-origin")
                .cookie(csrf.cookie().getName(), csrf.cookie().getValue())
                .header(csrf.headerName(), csrf.token())
                .cookie("ANIMALLINK_ADMIN_SESSION", SESSION_TOKEN)
                .exchange().expectStatus().isForbidden();

        verify(authenticationClient, never()).revoke(anyString());
    }

    @Test
    void identityFailuresStayClosedAndProtectedBusinessRoutesRemainClosed() {
        CsrfMaterial csrf = csrf();
        when(trustedClientSourceResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("ip:127.0.0.1");
        when(authenticationClient.login(anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new IdentitySessionClient.IdentitySessionUnavailableException()));
        String body = "{\"username\":\"admin.root\",\"password\":\"password\"}";
        login(body, csrf).exchange().expectStatus().isEqualTo(503);

        when(authenticationClient.validate(SESSION_TOKEN))
                .thenReturn(Mono.just(principal()));
        client.get().uri("/identity/api/v1/users/me")
                .cookie("ANIMALLINK_ADMIN_SESSION", SESSION_TOKEN)
                .header("X-User-Id", "forged").header(HttpHeaders.AUTHORIZATION, "Bearer forged")
                .exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.RequestHeadersSpec<?> login(String body, CsrfMaterial csrf) {
        return client.post().uri(AdminAuthenticationController.LOGIN_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .cookie(csrf.cookie().getName(), csrf.cookie().getValue())
                .header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .bodyValue(body);
    }

    private CsrfMaterial csrf() {
        EntityExchangeResult<Map> response = client.get().uri(AdminAuthenticationController.CSRF_PATH)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
        ResponseCookie cookie = response.getResponseCookies().getFirst(
                GatewaySecurityConfiguration.CSRF_SESSION_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        return new CsrfMaterial(cookie, (String) response.getResponseBody().get("token"),
                (String) response.getResponseBody().get("headerName"));
    }

    private IdentitySessionClient.IssuedAdminSession issued() {
        return new IdentitySessionClient.IssuedAdminSession(SESSION_TOKEN, "session-1", "user-1",
                "GOVERNANCE_ADMIN", IDLE_EXPIRY, ABSOLUTE_EXPIRY);
    }

    private IdentitySessionClient.GatewayAuthenticatedPrincipal principal() {
        return new IdentitySessionClient.GatewayAuthenticatedPrincipal("session-1", "user-1",
                "GOVERNANCE_ADMIN", IDLE_EXPIRY, ABSOLUTE_EXPIRY);
    }

    private record CsrfMaterial(ResponseCookie cookie, String token, String headerName) {
    }
}
