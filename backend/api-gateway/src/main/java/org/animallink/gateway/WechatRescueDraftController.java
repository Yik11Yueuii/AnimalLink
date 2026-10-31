package org.animallink.gateway;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController @RequestMapping("/auth/wechat/rescue-drafts")
public class WechatRescueDraftController {
    public static final String COLLECTION_PATH="/auth/wechat/rescue-drafts";
    private final WechatRescueDraftClient drafts; public WechatRescueDraftController(WechatRescueDraftClient drafts){this.drafts=drafts;}
    @PostMapping public Mono<ResponseEntity<?>> create(@RequestHeader(WechatAuthenticationController.SESSION_HEADER) String token,@Valid @RequestBody DraftRequest body){return response(drafts.create(token,body),HttpStatus.CREATED);}
    @GetMapping("/{draftId}") public Mono<ResponseEntity<?>> get(@RequestHeader(WechatAuthenticationController.SESSION_HEADER)String token,@PathVariable("draftId") String draftId){return response(drafts.get(token,draftId),HttpStatus.OK);}
    @PatchMapping("/{draftId}") public Mono<ResponseEntity<?>> patch(@RequestHeader(WechatAuthenticationController.SESSION_HEADER)String token,@PathVariable("draftId") String draftId,@Valid@RequestBody DraftRequest body){return response(drafts.patch(token,draftId,body),HttpStatus.OK);}
    @PostMapping("/{draftId}/submit") public Mono<ResponseEntity<?>> submit(@RequestHeader(WechatAuthenticationController.SESSION_HEADER)String token,@PathVariable("draftId") String draftId){return response(drafts.submit(token,draftId),HttpStatus.OK);}
    private Mono<ResponseEntity<?>> response(Mono<Object> value,HttpStatus success){return value.<ResponseEntity<?>>map(v->ResponseEntity.status(success).cacheControl(CacheControl.noStore()).body(v)).onErrorResume(WechatSessionClient.Invalid.class,e->Mono.just(error(HttpStatus.UNAUTHORIZED,"SESSION_INVALID"))).onErrorResume(WechatRescueDraftClient.Forbidden.class,e->Mono.just(error(HttpStatus.FORBIDDEN,"CAMPUS_MEMBERSHIP_REQUIRED"))).onErrorResume(WechatRescueDraftClient.Conflict.class,e->Mono.just(error(HttpStatus.CONFLICT,"DRAFT_STATE_CONFLICT"))).onErrorResume(e->Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE,"RESCUE_DRAFT_UNAVAILABLE")));}
    private static ResponseEntity<Object> error(HttpStatus status,String code){return ResponseEntity.status(status).body(new Error(code));}
    public record DraftRequest(@NotBlank@Size(max=36)String campusId,@Size(max=36)String animalId,@NotBlank@Size(max=2000)String description,@Size(max=1000)String abnormalitySummary,@NotNull Instant occurredAt,@NotBlank@Size(max=500)String publicLocationDescription,@Size(max=1000)String exactLocationDescription,Double latitude,Double longitude){}
    record Error(String code){}
}
