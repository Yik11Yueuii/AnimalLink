package org.animallink.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
class GatewayAdminSessionAuthenticationFilterTest {
    private static final String TOKEN = "A".repeat(43);
    private final ExternalIdentityBoundaryFilter boundary =
            new ExternalIdentityBoundaryFilter(new MockEnvironment());

    @Test
    void validSessionCreatesOnlyInternalPrincipalAndDoesNotOpenProtectedRoute() {
        AtomicInteger calls = new AtomicInteger();
        var authentication = filter(token -> {
            calls.incrementAndGet();
            return Mono.just(principal());
        });
        var exchange = request(HttpMethod.GET, "/identity/api/v1/users/me", true);

        authentication.filter(exchange, authenticated -> boundary.filter(authenticated, ignored -> Mono.empty())).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat((Object) exchange.getAttribute(GatewayAdminSessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE))
                .isEqualTo(principal());
        assertThat(calls).hasValue(1);
    }

    @Test
    void publicReadRemainsAvailableAndDoesNotForwardSessionOrForgedIdentityHeaders() {
        var authentication = filter(token -> Mono.just(principal()));
        var exchange = request(HttpMethod.GET, "/identity/api/v1/campuses", true);
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        authentication.filter(exchange, authenticated -> boundary.filter(authenticated, routed -> {
            downstream.set(routed);
            return Mono.empty();
        })).block();

        assertThat(downstream.get()).isNotNull();
        assertThat(downstream.get().getRequest().getHeaders().getFirst(HttpHeaders.COOKIE)).isNull();
        assertThat(downstream.get().getRequest().getHeaders().getFirst("X-User-Id")).isNull();
        assertThat(downstream.get().getRequest().getHeaders().getFirst("X-Internal-Service")).isNull();
    }

    @Test
    void identityUnavailabilityFailsClosedForProtectedRouteButPublicReadDegradesToAnonymous() {
        var authentication = filter(token -> Mono.error(
                new IdentitySessionClient.IdentitySessionUnavailableException()));
        var protectedExchange = request(HttpMethod.GET, "/identity/api/v1/users/me", true);
        authentication.filter(protectedExchange, ignored -> Mono.empty()).block();
        assertThat(protectedExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        var publicExchange = request(HttpMethod.GET, "/identity/api/v1/campuses", true);
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        authentication.filter(publicExchange, routed -> {
            downstream.set(routed);
            return Mono.empty();
        }).block();
        assertThat(downstream.get()).isNotNull();
        assertThat(downstream.get().getRequest().getHeaders().getFirst(HttpHeaders.COOKIE)).isNull();
    }

    @Test
    void forgedIdentityHeadersWithoutSessionNeverCreatePrincipal() {
        AtomicInteger calls = new AtomicInteger();
        var authentication = filter(token -> {
            calls.incrementAndGet();
            return Mono.just(principal());
        });
        var exchange = request(HttpMethod.GET, "/identity/api/v1/users/me", false);
        authentication.filter(exchange, authenticated -> boundary.filter(authenticated, ignored -> Mono.empty())).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat((Object) exchange.getAttribute(GatewayAdminSessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE))
                .isNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    void loginIgnoresAndRemovesAnExpiredOrMalformedSessionCookie() {
        AtomicInteger calls = new AtomicInteger();
        var authentication = filter(token -> {
            calls.incrementAndGet();
            return Mono.just(principal());
        });
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/admin/login")
                .cookie(new HttpCookie("ANIMALLINK_ADMIN_SESSION", "expired"))
                .cookie(new HttpCookie("theme", "warm"))
                .build());
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        authentication.filter(exchange, routed -> {
            downstream.set(routed);
            return Mono.empty();
        }).block();

        assertThat(calls).hasValue(0);
        assertThat(downstream.get()).isNotNull();
        assertThat(downstream.get().getRequest().getHeaders().getFirst(HttpHeaders.COOKIE))
                .isEqualTo("theme=warm");
    }

    @Test
    void logoutCarriesTheRawTokenOnlyInPrivateExchangeStateWithoutValidatingIt() {
        AtomicInteger calls = new AtomicInteger();
        var authentication = filter(token -> {
            calls.incrementAndGet();
            return Mono.just(principal());
        });
        var exchange = request(HttpMethod.POST, "/auth/admin/logout", true);
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        authentication.filter(exchange, routed -> {
            downstream.set(routed);
            return Mono.empty();
        }).block();

        assertThat(calls).hasValue(0);
        assertThat((Object) downstream.get().getAttribute(
                GatewayAdminSessionAuthenticationFilter.SESSION_TOKEN_ATTRIBUTE)).isEqualTo(TOKEN);
        assertThat(downstream.get().getRequest().getHeaders().getFirst(HttpHeaders.COOKIE)).isNull();
    }

    @Test
    void duplicateSessionCookiesAreRejectedBeforeAnyIdentityCall() {
        AtomicInteger calls = new AtomicInteger();
        var authentication = filter(token -> {
            calls.incrementAndGet();
            return Mono.just(principal());
        });
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/auth/admin/session")
                .cookie(new HttpCookie("ANIMALLINK_ADMIN_SESSION", TOKEN))
                .cookie(new HttpCookie("ANIMALLINK_ADMIN_SESSION", "B".repeat(43)))
                .build());

        authentication.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(calls).hasValue(0);
        assertThat(exchange.getResponse().getCookies().getFirst("ANIMALLINK_ADMIN_SESSION").getMaxAge())
                .isZero();
    }

    private MockServerWebExchange request(HttpMethod method, String path, boolean session) {
        var builder = MockServerHttpRequest.method(method, path)
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .header("X-Internal-Service", "api-gateway");
        if (session) builder.cookie(new HttpCookie("ANIMALLINK_ADMIN_SESSION", TOKEN));
        return MockServerWebExchange.from(builder.build());
    }

    private IdentitySessionClient.GatewayAuthenticatedPrincipal principal() {
        return new IdentitySessionClient.GatewayAuthenticatedPrincipal("session-1", "user-1",
                "GOVERNANCE_ADMIN", Instant.parse("2026-10-20T12:30:00Z"),
                Instant.parse("2026-10-20T20:00:00Z"));
    }

    private GatewayAdminSessionAuthenticationFilter filter(AdminSessionValidator validator) {
        var resolver = new AdminSessionCredentialResolver("ANIMALLINK_ADMIN_SESSION");
        return new GatewayAdminSessionAuthenticationFilter(
                resolver, validator, new AdminSessionCookieManager(resolver));
    }
}
