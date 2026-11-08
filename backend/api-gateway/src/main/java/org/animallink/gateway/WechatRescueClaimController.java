package org.animallink.gateway;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import reactor.core.publisher.Mono;

@RestController
public class WechatRescueClaimController {
    private final WechatRescueDraftClient client;
    public WechatRescueClaimController(WechatRescueDraftClient client) { this.client = client; }

    @PostMapping("/auth/wechat/rescues/{eventId}/claim")
    public Mono<ResponseEntity<?>> claim(@RequestHeader(value = WechatAuthenticationController.SESSION_HEADER, required = false) String token,
                                         @PathVariable("eventId") String eventId, @RequestBody ClaimRequest body) {
        if (token == null || token.isBlank()) return Mono.just(failure(new WechatSessionClient.Invalid()));
        return client.claim(token, eventId, body).<ResponseEntity<?>>map(value ->
                ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .onErrorResume(error -> Mono.just(failure(error)));
    }
    static ResponseEntity<Object> failure(Throwable error) {
        HttpStatus status = error instanceof WechatRescueDraftClient.BadRequest ? HttpStatus.BAD_REQUEST
                : error instanceof WechatSessionClient.Invalid ? HttpStatus.UNAUTHORIZED
                : error instanceof WechatRescueDraftClient.Forbidden ? HttpStatus.FORBIDDEN
                : error instanceof WechatRescueDraftClient.Conflict ? HttpStatus.CONFLICT
                : error instanceof WechatRescueDraftClient.NotFound ? HttpStatus.NOT_FOUND
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new Error(status.name()));
    }
    record ClaimRequest(String campusId) {}
    record Error(String code) {}
}
