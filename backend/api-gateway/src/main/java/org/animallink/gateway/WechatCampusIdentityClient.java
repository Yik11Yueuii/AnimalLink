package org.animallink.gateway;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Resolves an opaque Mini Program session before forwarding a gateway-verified ordinary-user actor. */
@Component
public class WechatCampusIdentityClient {
    private static final String SNAPSHOT = "/internal/v1/wechat/campus-identities";
    private static final String APPLICATIONS = SNAPSHOT + "/applications";
    private final WechatSessionClient sessions;
    private final WebClient client;
    private final ServiceAssertionSigner signer;
    private final Duration timeout;

    public WechatCampusIdentityClient(WechatSessionClient sessions,
                                      @Qualifier("identitySessionWebClientBuilder") WebClient.Builder builder,
                                      ServiceAssertionSigner signer,
                                      @Value("${animallink.authentication.identity-session.base-url:http://identity-service}") String baseUrl,
                                      @Value("${animallink.authentication.identity-session.connect-timeout:PT2S}") Duration connectTimeout,
                                      @Value("${animallink.authentication.identity-session.response-timeout:PT3S}") Duration timeout) {
        this.sessions = sessions;
        this.signer = signer;
        this.timeout = timeout;
        this.client = builder.clone().baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis())).responseTimeout(timeout))).build();
    }

    public Mono<Snapshot> mine(String token) {
        return sessions.current(token).flatMap(actor -> callGet(actor.userId()));
    }

    public Mono<Application> submit(String token, ApplicationRequest request) {
        return sessions.current(token).flatMap(actor -> callPost(actor.userId(), request));
    }

    private Mono<Snapshot> callGet(String actorUserId) {
        return Mono.fromCallable(() -> signer.signForActor(actorUserId)).flatMap(assertion -> client.get().uri(builder -> builder.path(SNAPSHOT)
                .queryParam("actorUserId", actorUserId).build()).header(HttpHeaders.AUTHORIZATION, "Bearer " + assertion)
                .exchangeToMono(response -> result(response, Snapshot.class)));
    }

    private Mono<Application> callPost(String actorUserId, ApplicationRequest request) {
        return Mono.fromCallable(() -> signer.signForActor(actorUserId)).flatMap(assertion -> client.post().uri(APPLICATIONS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + assertion).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InternalApplicationRequest(actorUserId, request.campusId(), request.requestedMembershipType(),
                        request.applicantName(), request.affiliationNote(), request.expectedGraduationDate(), request.materialMediaId()))
                .exchangeToMono(response -> result(response, Application.class)));
    }

    private <T> Mono<T> result(org.springframework.web.reactive.function.client.ClientResponse response, Class<T> type) {
        if (response.statusCode().is2xxSuccessful()) return response.bodyToMono(type);
        if (response.statusCode().equals(HttpStatus.UNAUTHORIZED) || response.statusCode().equals(HttpStatus.FORBIDDEN)
                || response.statusCode().equals(HttpStatus.BAD_REQUEST)) return response.releaseBody().then(Mono.error(new WechatSessionClient.Invalid()));
        if (response.statusCode().equals(HttpStatus.CONFLICT)) return response.releaseBody().then(Mono.error(new Conflict()));
        return response.releaseBody().then(Mono.error(new WechatSessionClient.Unavailable()));
    }

    public record ApplicationRequest(String campusId, String requestedMembershipType, String applicantName,
                                     String affiliationNote, LocalDate expectedGraduationDate, String materialMediaId) { }
    record InternalApplicationRequest(String actorUserId, String campusId, String requestedMembershipType,
                                     String applicantName, String affiliationNote, LocalDate expectedGraduationDate,
                                     String materialMediaId) { }
    public record Snapshot(List<Membership> memberships, List<Application> applications) { }
    public record Campus(String id, String universityId, String name, String shortName, String city,
                         String region, String campusType, String status) { }
    public record Membership(String id, Campus campus, String membershipType, String status,
                             LocalDate expectedGraduationDate, Instant approvedAt) { }
    public record Application(String id, Campus campus, String requestedMembershipType, String applicantName,
                              String affiliationNote, LocalDate expectedGraduationDate, String status,
                              String reviewReason, Instant reviewedAt, Instant createdAt, Instant updatedAt,
                              String credentialPrecheckStatus) { }
    public static final class Conflict extends RuntimeException { }
}
