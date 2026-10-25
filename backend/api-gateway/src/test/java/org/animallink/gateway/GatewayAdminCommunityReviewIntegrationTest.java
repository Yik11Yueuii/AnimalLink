package org.animallink.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.profiles.active=secure",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.config.import=optional:classpath:gateway-test-absent.yaml",
        "animallink.authentication.animal-admin-assertion.current-key-id=animal-v1",
        "animallink.authentication.animal-admin-assertion.keys=animal-v1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureWebTestClient
@Import(GatewayAdminCommunityReviewIntegrationTest.TestRoute.class)
class GatewayAdminCommunityReviewIntegrationTest {
    private static final String TOKEN = "A".repeat(43);
    private static final String ID = "00000000-0000-0000-0000-000000000001";

    @Autowired WebTestClient client;
    @MockBean IdentitySessionClient sessionValidator;

    @BeforeEach
    void authenticate() {
        when(sessionValidator.validate(anyString())).thenReturn(Mono.just(
                new IdentitySessionClient.GatewayAuthenticatedPrincipal("session", ID,
                        "GOVERNANCE_ADMIN", Instant.now().plusSeconds(600),
                        Instant.now().plusSeconds(3600))));
    }

    @Test
    void validSessionRoutesOnlyExactReadsWithFreshTrustedAssertion() {
        for (String path : new String[] {
                "/animal/api/v1/admin/community-review/posts",
                "/animal/api/v1/admin/community-review/posts/" + ID,
                "/animal/api/v1/admin/community-review/comments",
                "/animal/api/v1/admin/community-review/comments/" + ID}) {
            client.get().uri(path).cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                    .header("Authorization", "Bearer forged")
                    .header("X-User-Id", ID).header("X-Internal-Service", "forged")
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("trusted");
        }
    }

    @Test
    void missingInvalidOrInsufficientSessionFailsBeforeRouting() {
        client.get().uri("/animal/api/v1/admin/community-review/posts")
                .exchange().expectStatus().isUnauthorized();

        when(sessionValidator.validate(TOKEN)).thenReturn(Mono.error(
                new IdentitySessionClient.SessionUnauthenticatedException()));
        request().exchange().expectStatus().isUnauthorized();

        when(sessionValidator.validate(TOKEN)).thenReturn(Mono.error(
                new IdentitySessionClient.SessionForbiddenException()));
        request().exchange().expectStatus().isForbidden();

        when(sessionValidator.validate(TOKEN)).thenReturn(Mono.error(
                new IdentitySessionClient.IdentitySessionUnavailableException()));
        request().exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void adjacentAdminPathsAndAllMutationsRemainDenied() {
        client.get().uri("/animal/api/v1/admin/posts")
                .cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/animal/api/v1/admin/community-review/posts/" + ID + "/extra")
                .cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/animal/api/v1/admin/community-review/posts")
                .cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                .exchange().expectStatus().isUnauthorized();
        client.head().uri("/animal/api/v1/admin/community-review/posts")
                .cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                .exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.RequestHeadersSpec<?> request() {
        return client.get().uri("/animal/api/v1/admin/community-review/posts")
                .cookie("ANIMALLINK_ADMIN_SESSION", TOKEN);
    }

    @TestConfiguration
    static class TestRoute {
        @Bean
        RouteLocator reviewRoute(RouteLocatorBuilder builder) {
            return builder.routes().route("review-probe", route -> route.order(-100)
                    .path("/animal/**")
                    .filters(filters -> filters.setPath("/test-review-probe"))
                    .uri("forward:/test-review-probe")).build();
        }

        @Bean
        RouterFunction<ServerResponse> reviewProbe() {
            return RouterFunctions.route(GET("/test-review-probe"), request -> {
                String authorization = request.headers().firstHeader("Authorization");
                boolean trusted = authorization != null && authorization.startsWith("Bearer ")
                        && request.headers().firstHeader("X-User-Id") == null
                        && request.headers().firstHeader("X-Internal-Service") == null
                        && (request.headers().firstHeader("Cookie") == null
                        || !request.headers().firstHeader("Cookie").contains("ANIMALLINK_ADMIN_SESSION"));
                return ServerResponse.ok().bodyValue(trusted ? "trusted" : "unsafe");
            });
        }
    }
}
