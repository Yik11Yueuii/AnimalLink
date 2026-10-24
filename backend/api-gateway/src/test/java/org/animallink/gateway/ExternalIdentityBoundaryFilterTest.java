package org.animallink.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import reactor.core.publisher.Mono;

class ExternalIdentityBoundaryFilterTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";

    private MockEnvironment dev(String profile, String address) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        env.setProperty("server.address", address);
        env.setProperty("animallink.gateway.allow-dev-user-header", "true");
        return env;
    }

    private AtomicReference<ServerWebExchange> run(ExternalIdentityBoundaryFilter filter, MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, next -> { forwarded.set(next); return Mono.empty(); }).block();
        return forwarded;
    }

    @Test
    void formalModeStripsSpoofedIdentityRegardlessOfCaseAndMultiplicity() {
        var request = MockServerHttpRequest.get("/identity/api/v1/campuses")
                .header("x-USER-id", ADMIN, "00000000-0000-0000-0000-000000000011")
                .header("x-INTERNAL-service", "incident-service")
                .header("X-Authenticated-User", ADMIN)
                .header("X-Trusted-Assertion", "fake")
                .header("Authorization", "Bearer fake")
                .header("Accept", "application/json").build();
        var forwarded = run(new ExternalIdentityBoundaryFilter(new MockEnvironment()),
                MockServerWebExchange.from(request)).get();
        assertNotNull(forwarded);
        var headers = forwarded.getRequest().getHeaders();
        assertFalse(headers.containsKey("X-User-Id"));
        assertFalse(headers.containsKey("X-Internal-Service"));
        assertFalse(headers.containsKey("X-Authenticated-User"));
        assertFalse(headers.containsKey("X-Trusted-Assertion"));
        assertFalse(headers.containsKey("Authorization"));
        assertEquals("application/json", headers.getFirst("Accept"));
    }

    @Test
    void formalModeRejectsUnauthenticatedMutationsEvenWithForgedAdmin() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/animal/api/v1/admin/animals")
                .header("X-User-Id", ADMIN).header("X-Internal-Service", "animal-service").build());
        assertNull(run(new ExternalIdentityBoundaryFilter(new MockEnvironment()), exchange).get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void formalModeAllowsOnlyExactAuthenticationMethodsAndPaths() {
        var filter = new ExternalIdentityBoundaryFilter(new MockEnvironment());
        for (var allowed : new Object[][] {
                {HttpMethod.GET, "/auth/admin/csrf"},
                {HttpMethod.POST, "/auth/admin/login"},
                {HttpMethod.GET, "/auth/admin/session"},
                {HttpMethod.POST, "/auth/admin/logout"}}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method((HttpMethod) allowed[0], (String) allowed[1]).build());
            assertNotNull(run(filter, exchange).get(), (String) allowed[1]);
        }

        for (var denied : new Object[][] {
                {HttpMethod.POST, "/auth/admin/csrf"},
                {HttpMethod.GET, "/auth/admin/login"},
                {HttpMethod.GET, "/auth/admin/logout"},
                {HttpMethod.GET, "/auth/admin/session/child"},
                {HttpMethod.GET, "/auth/anything"}}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method((HttpMethod) denied[0], (String) denied[1]).build());
            assertNull(run(filter, exchange).get(), (String) denied[1]);
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        }
    }

    @Test
    void localSimulationRequiresExplicitOptInLoopbackListenerAndLoopbackPeer() {
        Environment env = dev("local", "127.0.0.1");
        var filter = new ExternalIdentityBoundaryFilter(env);
        var request = MockServerHttpRequest.post("/identity/api/v1/example")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .header("X-User-Id", ADMIN).header("X-Internal-Service", "identity-service").build();
        var forwarded = run(filter, MockServerWebExchange.from(request)).get();
        assertEquals(ADMIN, forwarded.getRequest().getHeaders().getFirst("X-User-Id"));
        assertFalse(forwarded.getRequest().getHeaders().containsKey("X-Internal-Service"));

        var remote = MockServerWebExchange.from(MockServerHttpRequest.post("/identity/api/v1/example")
                .remoteAddress(new InetSocketAddress("192.0.2.1", 12345)).header("X-User-Id", ADMIN).build());
        assertNull(run(filter, remote).get());
        assertEquals(HttpStatus.UNAUTHORIZED, remote.getResponse().getStatusCode());

        var duplicates = MockServerWebExchange.from(MockServerHttpRequest.get("/identity/api/v1/users/me")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .header("X-User-Id", ADMIN, ADMIN).build());
        assertFalse(run(filter, duplicates).get().getRequest().getHeaders().containsKey("X-User-Id"));
    }

    @Test
    void misconfiguredSimulationFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new ExternalIdentityBoundaryFilter(dev("prod", "127.0.0.1")));
        assertThrows(IllegalStateException.class, () -> new ExternalIdentityBoundaryFilter(dev("local", "0.0.0.0")));
        var mixed = dev("local", "127.0.0.1");
        mixed.setActiveProfiles("local", "prod");
        assertThrows(IllegalStateException.class, () -> new ExternalIdentityBoundaryFilter(mixed));
    }

    @Test
    void publicSafeRequestsPassAndPreRouteFilterBlocksInternalVariants() {
        var pathFilter = new InternalPathBlockFilter();
        for (String path : new String[] {"/identity/internal/v1/users", "/animal/%69nternal/v1",
                "/animal%2Finternal/v1", "/animal//internal/v1", "/animal/%2569nternal/v1",
                "/animal/internal;v=1/v1", "/animal/../identity/internal/v1"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)).build());
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
            pathFilter.filter(exchange, next -> { forwarded.set(next); return Mono.empty(); }).block();
            assertNull(forwarded.get(), path);
            assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode(), path);
        }
        var publicExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/identity/api/v1/campuses").build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        pathFilter.filter(publicExchange, next -> { forwarded.set(next); return Mono.empty(); }).block();
        assertNotNull(forwarded.get());
    }

    @Test
    void publicWebFluxRouteContinuesToWorkWithBothFilters() {
        var router = RouterFunctions.route(GET("/identity/api/v1/campuses"),
                request -> ServerResponse.ok().bodyValue("public"));
        var client = WebTestClient.bindToRouterFunction(router)
                .webFilter(new InternalPathBlockFilter(), new ExternalIdentityBoundaryFilter(new MockEnvironment()))
                .build();
        client.get().uri("/identity/api/v1/campuses").header("X-User-Id", ADMIN)
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("public");
    }
}
