package org.animallink.gateway;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class PublicReadPolicyTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private final ExternalIdentityBoundaryFilter filter = new ExternalIdentityBoundaryFilter(new MockEnvironment());

    private boolean forwarded(HttpMethod method, String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create(path))
                .header("X-User-Id", ID).header("X-Internal-Service", "incident-service").build());
        AtomicReference<ServerWebExchange> next = new AtomicReference<>();
        new InternalPathBlockFilter().filter(exchange, e -> filter.filter(e, f -> {
            next.set(f);
            return Mono.empty();
        })).block();
        if (next.get() == null) {
            assertTrue(exchange.getResponse().getStatusCode() == HttpStatus.UNAUTHORIZED
                    || exchange.getResponse().getStatusCode() == HttpStatus.NOT_FOUND, path);
            return false;
        }
        assertFalse(next.get().getRequest().getHeaders().containsKey("X-User-Id"));
        assertFalse(next.get().getRequest().getHeaders().containsKey("X-Internal-Service"));
        return true;
    }

    @Test void onlyExplicitPublicReadsAndHeadsPass() {
        for (String path : new String[] {"/identity/api/v1/campuses", "/identity/api/v1/campuses/" + ID,
                "/animal/api/v1/public/animals", "/animal/api/v1/public/animals/" + ID,
                "/incident/api/v1/cases/" + ID + "/support/expenses"}) {
            assertTrue(forwarded(HttpMethod.GET, path), path);
            assertTrue(forwarded(HttpMethod.HEAD, path), path);
            assertFalse(forwarded(HttpMethod.POST, path), path);
            assertFalse(forwarded(HttpMethod.PATCH, path), path);
            assertFalse(forwarded(HttpMethod.DELETE, path), path);
            assertFalse(forwarded(HttpMethod.OPTIONS, path), path);
        }
    }

    @Test void protectedAndUnconfirmedReadsDenyForgedIdentity() {
        for (String path : new String[] {"/identity/api/v1/users/me",
                "/identity/api/v1/admin/users/" + ID + "/governance-actions",
                "/incident/api/v1/cases/" + ID,
                "/incident/api/v1/cases/" + ID + "/support/expenses/" + ID + "/private",
                "/incident/api/v1/admin/support-expenses/" + ID,
                "/incident/api/v1/cases/" + ID + "/support/expenses/" + ID,
                "/identity/api/v1/campuses/" + ID + "/members",
                "/animal/api/v1/animals", "/animal/api/v1/animals/" + ID,
                "/animal/api/v1/public/animals/" + ID + "/timeline",
                "/animal/api/v1/campuses/" + ID + "/feed",
                "/incident/api/v1/rescues/" + ID,
                "/intelligence/api/v1/ai/tasks/" + ID}) {
            assertFalse(forwarded(HttpMethod.GET, path), path);
            assertFalse(forwarded(HttpMethod.HEAD, path), path);
        }
    }

    @Test void ambiguousAndInternalPathsNeverBecomePublic() {
        for (String path : new String[] {"/identity/api/v1/%63ampuses",
                "/identity/api/v1/campuses/", "/identity//api/v1/campuses",
                "/identity/api/v1/campuses;foo", "/IDENTITY/api/v1/campuses",
                "/animal/api/v1/public/animals/", "/animal//api/v1/public/animals",
                "/animal/api/v1/public/animals;foo", "/ANIMAL/api/v1/public/animals",
                "/animal/api/v1/public/animals/not-a-uuid",
                "/animal/api/v1/public/animals/" + ID + "/extra",
                "/animal/api/v1/%70ublic/animals",
                "/identity/internal/v1/users", "/identity/%69nternal/v1/users",
                "/incident/api/v1/cases/" + ID + "/support/expenses%2f" + ID + "/private"}) {
            assertFalse(forwarded(HttpMethod.GET, path), path);
        }
    }
}
