package org.animallink.intelligence.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import org.animallink.intelligence.application.ObservationFinalizationGateway;
import org.animallink.intelligence.domain.MatchingDecisionType;
import org.animallink.intelligence.domain.AnimalObservationDraft;
import org.animallink.intelligence.domain.ObservationSpecies;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class HttpObservationFinalizationGatewayTest {
    private static final String BASE = "http://animal.test/internal/v1/observation-finalizations";

    @Test void registerAndRetireSignTheBytesActuallySent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://animal.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var gateway = new HttpObservationFinalizationGateway(builder.build(), mapper,
                new ObservationServiceAssertionSigner(
                        Base64.getEncoder().encodeToString(new byte[32]), "observation-v1"));
        server.expect(requestTo(BASE + "/attempts/register")).andExpect(method(POST))
                .andExpect(this::assertSignedBody)
                .andRespond(withSuccess("{\"fencingVersion\":3}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/attempts/retire")).andExpect(method(POST))
                .andExpect(this::assertSignedBody)
                .andRespond(withSuccess("{\"retired\":true}", MediaType.APPLICATION_JSON));
        var attempt = new ObservationFinalizationGateway.AttemptCommand(
                "actor", "campus", "task", "record", "attempt",
                MatchingDecisionType.SELECT_EXISTING, "animal");
        assertThat(gateway.registerAttempt(attempt)).isEqualTo(3);
        gateway.retireAttempt(new ObservationFinalizationGateway.RetireCommand(
                "actor", "campus", "task", "record", "attempt", 3,
                MatchingDecisionType.SELECT_EXISTING, "animal", "SELECTED_ANIMAL_ARCHIVED"));
        server.verify();
    }

    @Test void finalizeBindsMediaAndConfirmedDraftToTheWireDigest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://animal.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var gateway = new HttpObservationFinalizationGateway(builder.build(), mapper,
                new ObservationServiceAssertionSigner(
                        Base64.getEncoder().encodeToString(new byte[32]), "observation-v1"));
        server.expect(requestTo(BASE)).andExpect(method(POST))
                .andExpect(request -> {
                    assertSignedBody(request);
                    String json = new String(((org.springframework.mock.http.client.MockClientHttpRequest)
                            request).getBodyAsBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(json).contains("private/observation.jpg", "人工确认的位置");
                })
                .andRespond(withSuccess("{\"postId\":\"post\",\"proposalId\":null,\"animalId\":\"animal\"}",
                        MediaType.APPLICATION_JSON));
        var draft = new AnimalObservationDraft(ObservationSpecies.CAT, null, null,
                List.of(), null, null, null, null, List.of(), "人工确认的位置",
                Instant.parse("2026-01-01T00:00:00Z"), null, null, List.of(), List.of());
        var command = new ObservationFinalizationGateway.FinalizationCommand(
                "actor", "campus", "task", "record", "attempt", 1,
                MatchingDecisionType.SELECT_EXISTING, "animal", "观察记录",
                List.of("private/observation.jpg"), draft);
        assertThat(gateway.finalizeObservation(command).postId()).isEqualTo("post");
        server.verify();
    }

    private void assertSignedBody(org.springframework.http.HttpRequest request) throws java.io.IOException {
        // MockRestServiceServer exposes the exact body written by RestClient.
        byte[] body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes();
        String bearer = request.getHeaders().getFirst("Authorization");
        try {
            SignedJWT jwt = SignedJWT.parse(bearer.substring(7));
            assertThat(jwt.getJWTClaimsSet().getStringClaim("body_sha256")).isEqualTo(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
            assertThat(jwt.getJWTClaimsSet().getStringClaim("path"))
                    .isEqualTo(request.getURI().getPath());
            assertThat(request.getHeaders().getFirst("X-Miniapp-Session")).isNull();
        } catch (Exception invalid) {
            throw new AssertionError("HTTP 请求断言没有绑定实际正文", invalid);
        }
    }
}
