package org.animallink.gateway;

import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
public class WechatCaseActionController {
    private final WechatRescueDraftClient client;
    public WechatCaseActionController(WechatRescueDraftClient client) { this.client = client; }
    @GetMapping("/auth/wechat/cases/{caseId}/actions") public Mono<ResponseEntity<?>> list(@RequestHeader(value=WechatAuthenticationController.SESSION_HEADER,required=false) String token, @PathVariable("caseId") String caseId, @RequestParam("campusId") String campusId, @RequestParam(name="page",defaultValue="0") int page, @RequestParam(name="size",defaultValue="20") int size) {
        if (token == null || token.isBlank()) return Mono.just(WechatRescueClaimController.failure(new WechatSessionClient.Invalid()));
        if (campusId.isBlank() || page < 0 || size < 1 || size > 100) return Mono.just(WechatRescueClaimController.failure(new WechatRescueDraftClient.BadRequest()));
        return response(client.listActions(token,caseId,campusId,page,size));
    }
    @PostMapping("/auth/wechat/cases/{caseId}/actions") public Mono<ResponseEntity<?>> create(@RequestHeader(value=WechatAuthenticationController.SESSION_HEADER,required=false) String token, @PathVariable("caseId") String caseId, @RequestBody Input input) {
        if (token == null || token.isBlank()) return Mono.just(WechatRescueClaimController.failure(new WechatSessionClient.Invalid()));
        return response(client.createAction(token,caseId,input));
    }
    @GetMapping("/auth/wechat/rescues/{eventId}/case") public Mono<ResponseEntity<?>> caseByEvent(@RequestHeader(value=WechatAuthenticationController.SESSION_HEADER,required=false) String token, @PathVariable("eventId") String event, @RequestParam("campusId") String campus) {
        if (token == null || token.isBlank()) return Mono.just(WechatRescueClaimController.failure(new WechatSessionClient.Invalid()));
        if (campus.isBlank()) return Mono.just(WechatRescueClaimController.failure(new WechatRescueDraftClient.BadRequest()));
        return response(client.caseByEvent(token,event,campus));
    }
    @PostMapping("/auth/wechat/cases/{caseId}/{operation:result|close}") public Mono<ResponseEntity<?>> transition(@RequestHeader(value=WechatAuthenticationController.SESSION_HEADER,required=false) String token,@PathVariable("caseId") String id,@PathVariable("operation") String operation,@RequestBody java.util.Map<String,Object> body) {
        if (token == null || token.isBlank()) return Mono.just(WechatRescueClaimController.failure(new WechatSessionClient.Invalid()));
        return response(client.caseTransition(token,id,operation,body));
    }
    private Mono<ResponseEntity<?>> response(Mono<Object> value) { return value.<ResponseEntity<?>>map(v -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v)).onErrorResume(e -> Mono.just(WechatRescueClaimController.failure(e))); }
    record Input(String campusId, String description, Instant occurredAt) {}
}
