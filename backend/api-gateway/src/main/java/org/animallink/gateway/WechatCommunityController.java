package org.animallink.gateway;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
@RestController
public class WechatCommunityController {
 private final WechatSessionClient sessions; private final AnimalMiniappAssertionSigner signer; private final WebClient client;
 public WechatCommunityController(WechatSessionClient sessions,AnimalMiniappAssertionSigner signer,@Qualifier("identitySessionWebClientBuilder") WebClient.Builder builder,@Value("${animallink.authentication.animal-miniapp.base-url:http://animal-service}") String url){this.sessions=sessions;this.signer=signer;this.client=builder.clone().baseUrl(url).build();}
 @PostMapping("/auth/wechat/community/posts") public Mono<ResponseEntity<?>> publish(@RequestHeader(value="X-Miniapp-Session",required=false) String token,@RequestBody Map<String,Object> body){return call(token,"POST","/auth/wechat/community/posts",body,null);}
 @PostMapping("/auth/wechat/community/posts/{id}/comments") public Mono<ResponseEntity<?>> comment(@RequestHeader(value="X-Miniapp-Session",required=false) String token,@PathVariable("id") String id,@RequestBody Map<String,Object> body){return call(token,"POST","/auth/wechat/community/posts/"+id+"/comments",body,null);}
 @RequestMapping(value="/auth/wechat/community/posts/{id}/like",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.DELETE}) public Mono<ResponseEntity<?>> like(@RequestHeader(value="X-Miniapp-Session",required=false) String token,@PathVariable("id") String id,@RequestParam("campusId") String campusId,org.springframework.http.server.reactive.ServerHttpRequest request){return call(token,request.getMethod().name(),"/auth/wechat/community/posts/"+id+"/like",null,campusId);}
 private Mono<ResponseEntity<?>> call(String token,String method,String path,Object body,String campus){
  if(token==null||token.isBlank())return Mono.just(WechatRescueClaimController.failure(new WechatSessionClient.Invalid()));
  if(!AnimalMiniappGatewayPolicy.allowed(method,path))return Mono.just(ResponseEntity.badRequest().build());
  return sessions.current(token).flatMap(user->Mono.fromCallable(()->signer.sign(user.userId(),user.systemRole(),method,path)).flatMap(assertion->{String target=AnimalMiniappGatewayPolicy.downstreamPath(path);if(campus!=null)target=UriComponentsBuilder.fromPath(target).queryParam("campusId",campus).build().encode().toUriString();var spec=client.method(HttpMethod.valueOf(method)).uri(target).header(HttpHeaders.AUTHORIZATION,"Bearer "+assertion);var request=body==null?spec:spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);return request.exchangeToMono(response->{if(response.statusCode().is2xxSuccessful())return response.bodyToMono(Object.class).map(value->(ResponseEntity<?>)ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value));int status=response.statusCode().value();return response.releaseBody().thenReturn(ResponseEntity.status(status==401?503:status).build());});})).timeout(Duration.ofSeconds(15)).onErrorResume(e->Mono.just(WechatRescueClaimController.failure(e)));
 }
}
