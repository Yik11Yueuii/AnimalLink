package org.animallink.gateway;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
@RestController
public class WechatObservationController {
 private final WechatSessionClient sessions;private final IntelligenceMiniappAssertionSigner signer;private final WebClient client;
 public WechatObservationController(WechatSessionClient sessions,IntelligenceMiniappAssertionSigner signer,@Qualifier("identitySessionWebClientBuilder") WebClient.Builder builder,@Value("${animallink.authentication.intelligence-miniapp.base-url:http://intelligence-service}") String url){this.sessions=sessions;this.signer=signer;this.client=builder.clone().baseUrl(url).build();}
 @PostMapping("/auth/wechat/observations/{operation:parse|manual|upload-url}") public Mono<ResponseEntity<?>> create(@RequestHeader(value=WechatAuthenticationController.SESSION_HEADER,required=false) String token,@PathVariable("operation") String op,@RequestBody Map<String,Object> body){return call(token,"POST","/auth/wechat/observations/"+op,body);}
 @GetMapping("/auth/wechat/observations/{taskId}") public Mono<ResponseEntity<?>> get(@RequestHeader(value=WechatAuthenticationController.SESSION_HEADER,required=false) String token,@PathVariable("taskId") String id){return call(token,"GET","/auth/wechat/observations/"+id,null);}
 @PostMapping("/auth/wechat/observations/{taskId}/confirm") public Mono<ResponseEntity<?>> confirm(@RequestHeader(value=WechatAuthenticationController.SESSION_HEADER,required=false) String token,@PathVariable("taskId") String id,@RequestBody Map<String,Object> body){return call(token,"POST","/auth/wechat/observations/"+id+"/confirm",body);}
 private Mono<ResponseEntity<?>> call(String token,String method,String path,Object body){if(token==null||token.isBlank())return Mono.just(WechatRescueClaimController.failure(new WechatSessionClient.Invalid()));return sessions.current(token).flatMap(user->Mono.fromCallable(()->signer.sign(user.userId(),user.systemRole(),method,path)).flatMap(assertion->{String target=IntelligenceMiniappGatewayPolicy.downstreamPath(path);var req="GET".equals(method)?client.get().uri(target).header(HttpHeaders.AUTHORIZATION,"Bearer "+assertion):client.post().uri(target).header(HttpHeaders.AUTHORIZATION,"Bearer "+assertion).contentType(MediaType.APPLICATION_JSON).bodyValue(body);return req.exchangeToMono(response->{if(response.statusCode().is2xxSuccessful())return response.bodyToMono(Object.class).map(v->(ResponseEntity<?>)ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(v));int status=response.statusCode().value();if(status==401)return response.releaseBody().thenReturn(ResponseEntity.status(503).build());return response.releaseBody().thenReturn(ResponseEntity.status(status).build());});})).timeout(Duration.ofSeconds(35)).onErrorResume(e->Mono.just(WechatRescueClaimController.failure(e)));}
}
