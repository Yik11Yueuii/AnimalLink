package org.animallink.gateway;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

/** Exact native Mini Program routes for the caller's own campus verification state. */
@RestController
@RequestMapping("/auth/wechat/campus-identities")
public class WechatCampusIdentityController {
    public static final String SNAPSHOT_PATH = "/auth/wechat/campus-identities";
    public static final String APPLICATIONS_PATH = SNAPSHOT_PATH + "/applications";
    private final WechatCampusIdentityClient identities;

    public WechatCampusIdentityController(WechatCampusIdentityClient identities) { this.identities = identities; }

    @GetMapping
    public Mono<ResponseEntity<?>> mine(@RequestHeader(WechatAuthenticationController.SESSION_HEADER) String token) {
        return identities.mine(token).<ResponseEntity<?>>map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .onErrorResume(WechatSessionClient.Invalid.class, ignored -> Mono.just(error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID")))
                .onErrorResume(WechatCampusIdentityClient.Conflict.class, ignored -> Mono.just(error(HttpStatus.CONFLICT, "APPLICATION_CONFLICT")))
                .onErrorResume(WechatSessionClient.Unavailable.class, ignored -> Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE, "CAMPUS_IDENTITY_UNAVAILABLE")));
    }

    @PostMapping("/applications")
    public Mono<ResponseEntity<?>> submit(@RequestHeader(WechatAuthenticationController.SESSION_HEADER) String token,
                                           @Valid @RequestBody ApplicationRequest request) {
        return identities.submit(token, new WechatCampusIdentityClient.ApplicationRequest(request.campusId(),
                        request.requestedMembershipType(), request.applicantName(), request.affiliationNote(),
                        request.expectedGraduationDate(), request.materialMediaId()))
                .<ResponseEntity<?>>map(value -> ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(value))
                .onErrorResume(WechatSessionClient.Invalid.class, ignored -> Mono.just(error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID")))
                .onErrorResume(WechatCampusIdentityClient.Conflict.class, ignored -> Mono.just(error(HttpStatus.CONFLICT, "APPLICATION_CONFLICT")))
                .onErrorResume(WechatSessionClient.Unavailable.class, ignored -> Mono.just(error(HttpStatus.SERVICE_UNAVAILABLE, "CAMPUS_IDENTITY_UNAVAILABLE")));
    }

    private static ResponseEntity<Object> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new ErrorResponse(code));
    }
    public record ApplicationRequest(@NotBlank @Size(max = 36) String campusId,
                                     @NotBlank @Pattern(regexp = "STUDENT|ALUMNI") String requestedMembershipType,
                                     @NotBlank @Size(max = 80) String applicantName,
                                     @Size(max = 255) String affiliationNote,
                                     LocalDate expectedGraduationDate,
                                     @Size(max = 36) String materialMediaId) { }
    public record ErrorResponse(String code) { }
}
