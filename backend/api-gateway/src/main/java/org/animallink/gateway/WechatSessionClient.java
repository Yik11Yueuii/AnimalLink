package org.animallink.gateway;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.time.Instant;
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

/** Gateway-only client for ordinary Mini Program sessions; it never accepts caller claims as identity. */
@Component
public class WechatSessionClient {
    private static final String LOGIN = "/internal/v1/wechat/sessions/login";
    private static final String VALIDATE = "/internal/v1/wechat/sessions/validate";
    private static final String REVOKE = "/internal/v1/wechat/sessions/revoke";
    private final WebClient client; private final ServiceAssertionSigner signer; private final Duration timeout;
    public WechatSessionClient(@Qualifier("identitySessionWebClientBuilder") WebClient.Builder builder,
                               ServiceAssertionSigner signer,
                               @Value("${animallink.authentication.identity-session.base-url:http://identity-service}") String baseUrl,
                               @Value("${animallink.authentication.identity-session.connect-timeout:PT2S}") Duration connectTimeout,
                               @Value("${animallink.authentication.identity-session.response-timeout:PT3S}") Duration timeout) {
        this.client = builder.clone().baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis())).responseTimeout(timeout))).build();
        this.signer=signer; this.timeout=timeout;
    }
    public Mono<IssuedSession> login(String code) { return call(LOGIN, new Code(code), IssuedSession.class).flatMap(v -> validIssued(v) ? Mono.just(v) : Mono.error(new Unavailable())); }
    public Mono<CurrentUser> current(String token) { return call(VALIDATE, new Token(token), CurrentUser.class); }
    public Mono<Boolean> revoke(String token) { return call(REVOKE, new Token(token), Revocation.class).map(Revocation::revoked); }
    private <T> Mono<T> call(String path, Object body, Class<T> type) {
        return Mono.fromCallable(signer::sign).flatMap(assertion -> client.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + assertion).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) return response.bodyToMono(type);
                    if (response.statusCode().equals(HttpStatus.UNAUTHORIZED)) return response.releaseBody().then(Mono.error(new Invalid()));
                    if (response.statusCode().equals(HttpStatus.BAD_REQUEST)) return response.releaseBody().then(Mono.error(new Invalid()));
                    if (response.statusCode().equals(HttpStatus.SERVICE_UNAVAILABLE)) return response.releaseBody().then(Mono.error(new Unavailable()));
                    return response.releaseBody().then(Mono.error(new Unavailable()));
                })).timeout(timeout.plusMillis(250)).onErrorMap(error -> !(error instanceof Invalid) && !(error instanceof Unavailable), error -> new Unavailable());
    }
    private static boolean validIssued(IssuedSession v) { return v != null && v.token()!=null && v.token().matches("[A-Za-z0-9_-]{43}") && v.userId()!=null && v.displayName()!=null && v.idleExpiresAt()!=null && v.absoluteExpiresAt()!=null; }
    record Code(String code) {} record Token(String token) {} record Revocation(boolean revoked) {}
    public record IssuedSession(String token,String userId,String displayName,Instant idleExpiresAt,Instant absoluteExpiresAt) {}
    public record CurrentUser(String userId,String displayName,String systemRole,Instant idleExpiresAt,Instant absoluteExpiresAt) {}
    public static final class Invalid extends RuntimeException {} public static final class Unavailable extends RuntimeException {}
}
