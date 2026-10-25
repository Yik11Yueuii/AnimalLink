package org.animallink.gateway;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.profiles.active=secure",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.config.import=optional:classpath:gateway-test-absent.yaml",
        "animallink.authentication.browser.allowed-origins=https://admin.animallink.test",
        "animallink.authentication.animal-admin-assertion.current-key-id=animal-v1",
        "animallink.authentication.animal-admin-assertion.keys=animal-v1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureWebTestClient
@Import(GatewayAdminCommunityReviewWriteIntegrationTest.TestRoute.class)
class GatewayAdminCommunityReviewWriteIntegrationTest {
    private static final String ORIGIN = "https://admin.animallink.test";
    private static final String TOKEN = "A".repeat(43);
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String PATH =
            "/animal/api/v1/admin/community-review/posts/" + ID + "/approve";

    @Autowired WebTestClient client;
    @MockBean IdentitySessionClient sessionValidator;
    @SpyBean AnimalAdminAssertionSigner assertionSigner;

    @BeforeEach
    void authenticate() {
        when(sessionValidator.validate(anyString())).thenReturn(Mono.just(
                new IdentitySessionClient.GatewayAuthenticatedPrincipal("session", ID,
                        "GOVERNANCE_ADMIN", Instant.now().plusSeconds(600),
                        Instant.now().plusSeconds(3600))));
    }

    @Test
    void validSessionOriginAndCsrfRouteExactWritesWithWriteAssertion() {
        CsrfMaterial csrf = csrf();
        for (String path : new String[] {
                "/animal/api/v1/admin/community-review/posts/" + ID + "/approve",
                "/animal/api/v1/admin/community-review/posts/" + ID + "/reject",
                "/animal/api/v1/admin/community-review/comments/" + ID + "/approve",
                "/animal/api/v1/admin/community-review/comments/" + ID + "/reject"}) {
            write(path, csrf).header(HttpHeaders.AUTHORIZATION, "Bearer forged")
                    .header("X-User-Id", ID).header("X-Internal-Service", "forged")
                    .exchange().expectStatus().isOk().expectBody(String.class)
                    .isEqualTo("trusted-write");
        }
    }

    @Test
    void csrfAndStrictOriginAreRequiredBeforeRouting() {
        CsrfMaterial csrf = csrf();
        clearInvocations(assertionSigner);
        client.post().uri(PATH).cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reason\":\"ok\"}")
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(assertionSigner);

        write(PATH, csrf).header(HttpHeaders.ORIGIN, "https://evil.example")
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(assertionSigner);
        client.post().uri(PATH).cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                .cookie(csrf.cookie().getName(), csrf.cookie().getValue())
                .header(csrf.headerName(), csrf.token())
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "cross-site")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(assertionSigner);

        CsrfMaterial other = csrf();
        client.post().uri(PATH).cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                .cookie(csrf.cookie().getName(), csrf.cookie().getValue())
                .header(csrf.headerName(), other.token())
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reason\":\"ok\"}")
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(assertionSigner);
    }

    @Test
    void missingInvalidOrInsufficientSessionFailsClosed() {
        CsrfMaterial csrf = csrf();
        write(PATH, csrf, false).exchange().expectStatus().isUnauthorized();

        when(sessionValidator.validate(TOKEN)).thenReturn(Mono.error(
                new IdentitySessionClient.SessionUnauthenticatedException()));
        write(PATH, csrf).exchange().expectStatus().isUnauthorized();

        when(sessionValidator.validate(TOKEN)).thenReturn(Mono.error(
                new IdentitySessionClient.SessionForbiddenException()));
        write(PATH, csrf).exchange().expectStatus().isForbidden();

        when(sessionValidator.validate(TOKEN)).thenReturn(Mono.error(
                new IdentitySessionClient.IdentitySessionUnavailableException()));
        write(PATH, csrf).exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void adjacentPathsAndOtherMethodsRemainDenied() {
        CsrfMaterial csrf = csrf();
        write("/animal/api/v1/admin/community-review/posts/" + ID + "/hide", csrf)
                .exchange().expectStatus().isUnauthorized();
        write(PATH + "/extra", csrf).exchange().expectStatus().isUnauthorized();
        client.patch().uri(PATH).cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                .exchange().expectStatus().isUnauthorized();
        client.delete().uri(PATH).cookie("ANIMALLINK_ADMIN_SESSION", TOKEN)
                .exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.RequestBodySpec write(String path, CsrfMaterial csrf) {
        return write(path, csrf, true);
    }

    private WebTestClient.RequestBodySpec write(String path, CsrfMaterial csrf,
                                                 boolean includeSession) {
        WebTestClient.RequestBodySpec request = client.post().uri(path)
                .cookie(csrf.cookie().getName(), csrf.cookie().getValue())
                .header(csrf.headerName(), csrf.token())
                .header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON);
        return includeSession ? request.cookie("ANIMALLINK_ADMIN_SESSION", TOKEN) : request;
    }

    private CsrfMaterial csrf() {
        EntityExchangeResult<Map> response = client.get().uri(AdminAuthenticationController.CSRF_PATH)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
        ResponseCookie cookie = response.getResponseCookies().getFirst(
                GatewaySecurityConfiguration.CSRF_SESSION_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        return new CsrfMaterial(cookie, (String) response.getResponseBody().get("token"),
                (String) response.getResponseBody().get("headerName"));
    }

    private record CsrfMaterial(ResponseCookie cookie, String token, String headerName) {
    }

    @TestConfiguration
    static class TestRoute {
        @Bean
        RouteLocator reviewWriteRoute(RouteLocatorBuilder builder) {
            return builder.routes().route("review-write-probe", route -> route.order(-100)
                    .path("/animal/**")
                    .filters(filters -> filters.setPath("/test-review-write-probe"))
                    .uri("forward:/test-review-write-probe")).build();
        }

        @Bean
        RouterFunction<ServerResponse> reviewWriteProbe() {
            return RouterFunctions.route(POST("/test-review-write-probe"), request -> {
                try {
                    String authorization = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
                    SignedJWT jwt = SignedJWT.parse(authorization.substring(7));
                    boolean trusted = jwt.getJWTClaimsSet().getStringListClaim("scope")
                            .equals(java.util.List.of("community.review.write"))
                            && "POST".equals(jwt.getJWTClaimsSet().getStringClaim("request_method"))
                            && request.headers().firstHeader("X-User-Id") == null
                            && request.headers().firstHeader("X-Internal-Service") == null
                            && (request.headers().firstHeader(HttpHeaders.COOKIE) == null
                            || !request.headers().firstHeader(HttpHeaders.COOKIE)
                            .contains("ANIMALLINK_ADMIN_SESSION"));
                    return ServerResponse.ok().bodyValue(trusted ? "trusted-write" : "unsafe");
                } catch (Exception invalid) {
                    return ServerResponse.ok().bodyValue("unsafe");
                }
            });
        }
    }
}
