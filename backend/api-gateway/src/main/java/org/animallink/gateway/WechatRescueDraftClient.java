package org.animallink.gateway;

import io.netty.channel.ChannelOption;
import java.time.Duration;
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

/** Resolves the opaque Miniapp session, then calls Incident with a short-lived trusted assertion only. */
@Component
public class WechatRescueDraftClient {
    private final WechatSessionClient sessions; private final IncidentMiniappAssertionSigner signer; private final WebClient client;
    public WechatRescueDraftClient(WechatSessionClient sessions, IncidentMiniappAssertionSigner signer,
            @Qualifier("identitySessionWebClientBuilder") WebClient.Builder builder,
            @Value("${animallink.authentication.incident-miniapp.base-url:http://incident-service}") String baseUrl,
            @Value("${animallink.authentication.incident-miniapp.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${animallink.authentication.incident-miniapp.response-timeout:PT3S}") Duration timeout) {
        this.sessions=sessions;this.signer=signer;this.client=builder.clone().baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS,Math.toIntExact(connectTimeout.toMillis())).responseTimeout(timeout))).build();
    }
    public Mono<Object> create(String token, Object body) { return call(token,"POST","/auth/wechat/rescue-drafts",body,Object.class); }
    public Mono<Object> get(String token, String id) { return call(token,"GET","/auth/wechat/rescue-drafts/"+id,null,Object.class); }
    public Mono<Object> patch(String token, String id, Object body) { return call(token,"PATCH","/auth/wechat/rescue-drafts/"+id,body,Object.class); }
    public Mono<Object> submit(String token, String id) { return call(token,"POST","/auth/wechat/rescue-drafts/"+id+"/submit",null,Object.class); }
    private Mono<Object> call(String token,String method,String externalPath,Object body,Class<Object> type) {
        return sessions.current(token).flatMap(actor -> Mono.fromCallable(() -> signer.sign(actor.userId(),actor.systemRole(),method,externalPath)).flatMap(assertion -> {
            String internal=IncidentMiniappGatewayPolicy.downstreamPath(externalPath); WebClient.RequestHeadersSpec<?> request;
            if("POST".equals(method)) request=client.post().uri(internal).header(HttpHeaders.AUTHORIZATION,"Bearer "+assertion).contentType(MediaType.APPLICATION_JSON).bodyValue(body==null?java.util.Map.of():body);
            else if("PATCH".equals(method)) request=client.patch().uri(internal).header(HttpHeaders.AUTHORIZATION,"Bearer "+assertion).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            else request=client.get().uri(internal).header(HttpHeaders.AUTHORIZATION,"Bearer "+assertion);
            return request.exchangeToMono(response -> result(response,type));
        }));
    }
    private <T> Mono<T> result(org.springframework.web.reactive.function.client.ClientResponse response,Class<T> type) {
        if(response.statusCode().is2xxSuccessful())return response.bodyToMono(type);
        if(response.statusCode().equals(HttpStatus.BAD_REQUEST))return response.releaseBody().then(Mono.error(new BadRequest()));
        if(response.statusCode().equals(HttpStatus.UNAUTHORIZED))return response.releaseBody().then(Mono.error(new WechatSessionClient.Invalid()));
        if(response.statusCode().equals(HttpStatus.FORBIDDEN))return response.releaseBody().then(Mono.error(new Forbidden()));
        if(response.statusCode().equals(HttpStatus.NOT_FOUND))return response.releaseBody().then(Mono.error(new NotFound()));
        if(response.statusCode().equals(HttpStatus.CONFLICT))return response.releaseBody().then(Mono.error(new Conflict()));
        return response.releaseBody().then(Mono.error(new Unavailable()));
    }
    public static final class BadRequest extends RuntimeException{} public static final class Forbidden extends RuntimeException{} public static final class NotFound extends RuntimeException{} public static final class Conflict extends RuntimeException{} public static final class Unavailable extends RuntimeException{}
}
