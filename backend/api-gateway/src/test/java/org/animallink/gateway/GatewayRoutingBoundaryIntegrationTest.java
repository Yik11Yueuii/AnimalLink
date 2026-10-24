package org.animallink.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.profiles.active=secure",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.config.import=optional:classpath:gateway-test-absent.yaml"
})
@AutoConfigureWebTestClient
@Import(GatewayRoutingBoundaryIntegrationTest.TestRoute.class)
class GatewayRoutingBoundaryIntegrationTest {
    @Autowired WebTestClient client;

    @Test
    void gatewayRouteForwardsPublicReadWithoutSpoofedHeaders() {
        client.get().uri("/identity/api/v1/campuses")
                .header("X-User-Id", "00000000-0000-0000-0000-000000000002")
                .header("X-Internal-Service", "animal-service")
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("none");
    }

    @Test
    void gatewayRejectsUnauthenticatedWriteBeforeRouting() {
        client.post().uri("/identity/api/v1/campuses")
                .header("X-User-Id", "00000000-0000-0000-0000-000000000002")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void gatewayRoutesOnlyTheExactAnonymousAnimalProjectionAndStripsSpoofedHeaders() {
        String id = "00000000-0000-0000-0000-000000000001";
        for (String path : new String[] {
                "/animal/api/v1/public/animals",
                "/animal/api/v1/public/animals/" + id,
                "/animal/api/v1/public/campuses/" + id + "/feed",
                "/animal/api/v1/public/posts/" + id,
                "/animal/api/v1/public/posts/" + id + "/comments" }) {
            client.get().uri(path).header("Authorization", "Bearer forged")
                    .header("X-User-Id", id).header("X-Internal-Service", "animal-service")
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("none");
        }
        client.get().uri("/animal/api/v1/animals/" + id).exchange().expectStatus().isUnauthorized();
        client.get().uri("/animal/api/v1/public/animals/" + id + "/timeline")
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/animal/api/v1/public/animals").exchange().expectStatus().isUnauthorized();
        client.post().uri("/animal/api/v1/public/posts/" + id).exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/animal/api/v1/public/posts/" + id + "/comments/extra")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void routingDeniesProtectedReadsEvenWhenCatchAllDownstreamWouldAnswer() {
        String id = "00000000-0000-0000-0000-000000000001";
        for (String path : new String[] {
                "/identity/api/v1/users/me",
                "/identity/api/v1/admin/users/" + id + "/governance-actions",
                "/incident/api/v1/cases/" + id,
                "/incident/api/v1/cases/" + id + "/support/expenses/" + id + "/private",
                "/incident/api/v1/admin/support-expenses/" + id }) {
            client.get().uri(path).header("X-User-Id", id).header("X-Internal-Service", "incident-service")
                    .exchange().expectStatus().isUnauthorized();
            client.head().uri(path).exchange().expectStatus().isUnauthorized();
        }
        client.options().uri("/identity/api/v1/campuses").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void routingAllowsOnlyExactPublishedExpenseListNotItsChildren() {
        String id = "00000000-0000-0000-0000-000000000001";
        String list = "/incident/api/v1/cases/" + id + "/support/expenses";
        client.get().uri(list).header("X-User-Id", id)
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("none");
        client.get().uri(list + "/" + id + "/private")
                .exchange().expectStatus().isUnauthorized();
    }

    @TestConfiguration
    static class TestRoute {
        @Bean
        RouteLocator publicRoute(RouteLocatorBuilder builder) {
            return builder.routes().route("test-animal", route -> route.order(-100)
                    .path("/identity/api/v1/campuses")
                    .filters(filters -> filters.setPath("/test-gateway-probe"))
                    .uri("forward:/test-gateway-probe"))
                    .route("test-animal-catch-all", route -> route.order(-100)
                    .path("/animal/**")
                    .filters(filters -> filters.setPath("/test-gateway-probe"))
                    .uri("forward:/test-gateway-probe"))
                    .route("test-incident-catch-all", route -> route.order(-100)
                    .path("/incident/**")
                    .filters(filters -> filters.setPath("/test-gateway-probe"))
                    .uri("forward:/test-gateway-probe")).build();
        }

        @Bean
        RouterFunction<ServerResponse> probe() {
            return RouterFunctions.route(GET("/test-gateway-probe"), request ->
                    ServerResponse.ok().bodyValue(request.headers().firstHeader("X-User-Id") == null
                            && request.headers().firstHeader("X-Internal-Service") == null
                            && request.headers().firstHeader("Authorization") == null ? "none" : "spoofed"));
        }
    }
}
