package org.animallink.gateway;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Native Mini Program session boundary. Tokens are opaque and are never forwarded to business services. */
@RestController
@RequestMapping("/auth/wechat")
public class WechatAuthenticationController {
    public static final String LOGIN_PATH="/auth/wechat/login", ME_PATH="/auth/wechat/me", LOGOUT_PATH="/auth/wechat/logout";
    public static final String SESSION_HEADER="X-Miniapp-Session";
    private final WechatSessionClient sessions;
    public WechatAuthenticationController(WechatSessionClient sessions) { this.sessions=sessions; }
    @PostMapping("/login") public Mono<ResponseEntity<?>> login(@Valid @RequestBody LoginRequest request) {
        return sessions.login(request.code()).<ResponseEntity<?>>map(s -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(s))
                .onErrorResume(WechatSessionClient.Invalid.class, e -> Mono.just(error(HttpStatus.UNAUTHORIZED,"WECHAT_CODE_INVALID")))
                .onErrorResume(WechatSessionClient.Unavailable.class, e -> Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE,"WECHAT_LOGIN_UNAVAILABLE")));
    }
    @GetMapping("/me") public Mono<ResponseEntity<?>> me(@RequestHeader(SESSION_HEADER) String token) {
        return sessions.current(token).<ResponseEntity<?>>map(v -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v))
                .onErrorResume(WechatSessionClient.Invalid.class, e -> Mono.just(error(HttpStatus.UNAUTHORIZED,"SESSION_INVALID")))
                .onErrorResume(WechatSessionClient.Unavailable.class, e -> Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE,"SESSION_VALIDATION_UNAVAILABLE")));
    }
    @PostMapping("/logout") public Mono<ResponseEntity<?>> logout(@RequestHeader(SESSION_HEADER) String token) {
        return sessions.revoke(token).<ResponseEntity<?>>map(v -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LogoutResponse(v)))
                .onErrorResume(WechatSessionClient.Invalid.class, e -> Mono.just(error(HttpStatus.UNAUTHORIZED,"SESSION_INVALID")))
                .onErrorResume(WechatSessionClient.Unavailable.class, e -> Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE,"SESSION_REVOCATION_UNAVAILABLE")));
    }
    private static ResponseEntity<Object> error(HttpStatus status,String code){return ResponseEntity.<Object>status(status).body(new Error(code));}
    public record LoginRequest(@NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9_-]{8,128}") String code) {}
    public record LogoutResponse(boolean revoked) {} public record Error(String code) {}
}
