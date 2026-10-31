package org.animallink.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.animallink.identity.application.ApplicantCampusVerificationView;
import org.animallink.identity.application.CampusVerificationApplicationService;
import org.animallink.identity.application.SubmitVerificationCommand;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.MembershipType;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserRepository;
import org.animallink.identity.infrastructure.InternalSessionServiceAuthenticationFilter;
import org.animallink.identity.infrastructure.ServiceAssertionVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Gateway-only ordinary-user access to the existing campus verification state machine. */
@RestController
@RequestMapping("/internal/v1/wechat/campus-identities")
public class InternalWechatCampusIdentityController {
    private final CampusVerificationApplicationService verifications;
    private final UserRepository users;

    public InternalWechatCampusIdentityController(CampusVerificationApplicationService verifications, UserRepository users) {
        this.verifications = verifications;
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<?> mine(@RequestParam String actorUserId, HttpServletRequest request) {
        if (!gateway(request, actorUserId)) return forbidden();
        try { return ResponseEntity.ok(snapshot(actor(actorUserId))); }
        catch (UnauthorizedException rejected) { return forbidden(); }
    }

    @PostMapping("/applications")
    public ResponseEntity<?> submit(@Valid @RequestBody SubmitRequest request, HttpServletRequest servletRequest) {
        if (!gateway(servletRequest, request.actorUserId())) return forbidden();
        try {
            UserAccount actor = actor(request.actorUserId());
            var result = verifications.submitForVerifiedUser(actor, new SubmitVerificationCommand(request.campusId(),
                    request.requestedMembershipType(), request.applicantName(), request.affiliationNote(),
                    request.expectedGraduationDate(), request.materialMediaId()));
            return ResponseEntity.status(HttpStatus.CREATED).body(ApplicationResponse.from(result));
        } catch (UnauthorizedException rejected) { return forbidden(); }
    }

    private SnapshotResponse snapshot(UserAccount actor) {
        return new SnapshotResponse(
                verifications.membershipsForVerifiedUser(actor).stream().map(MembershipResponse::from).toList(),
                verifications.listForVerifiedUser(actor).stream().map(ApplicationResponse::from).toList());
    }

    private UserAccount actor(String userId) {
        try { UUID.fromString(userId); } catch (RuntimeException invalid) { throw new UnauthorizedException("无效普通用户身份"); }
        return users.findUserById(userId)
                .filter(user -> user.accountStatus() == AccountStatus.ACTIVE && user.systemRole() == SystemRole.USER)
                .orElseThrow(() -> new UnauthorizedException("普通用户会话无效"));
    }

    private static boolean gateway(HttpServletRequest request, String actorUserId) {
        return request.getAttribute(InternalSessionServiceAuthenticationFilter.PRINCIPAL_ATTRIBUTE)
                instanceof ServiceAssertionVerifier.AuthenticatedService service
                && actorUserId != null && actorUserId.equals(service.actorUserId());
    }
    private static ResponseEntity<ErrorResponse> forbidden() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("SERVICE_AUTHENTICATION_FAILED"));
    }

    public record SubmitRequest(@NotBlank @Size(max = 36) String actorUserId,
                                @NotBlank @Size(max = 36) String campusId,
                                @NotNull MembershipType requestedMembershipType,
                                @NotBlank @Size(max = 80) String applicantName,
                                @Size(max = 255) String affiliationNote,
                                LocalDate expectedGraduationDate,
                                @Size(max = 36) String materialMediaId) { }
    public record SnapshotResponse(List<MembershipResponse> memberships, List<ApplicationResponse> applications) { }
    public record MembershipResponse(String id, IdentityDtos.CampusResponse campus, MembershipType membershipType,
                                     String status, LocalDate expectedGraduationDate, Instant approvedAt) {
        static MembershipResponse from(org.animallink.identity.domain.CampusMembershipView view) {
            var value = view.membership();
            return new MembershipResponse(value.id(), IdentityDtos.CampusResponse.from(view.campus()), value.membershipType(),
                    value.status().name(), value.expectedGraduationDate(), value.approvedAt());
        }
    }
    public record ApplicationResponse(String id, IdentityDtos.CampusResponse campus, MembershipType requestedMembershipType,
                                      String applicantName, String affiliationNote, LocalDate expectedGraduationDate,
                                      String status, String reviewReason, Instant reviewedAt, Instant createdAt,
                                      Instant updatedAt, String credentialPrecheckStatus) {
        static ApplicationResponse from(ApplicantCampusVerificationView value) {
            var verification = value.verification().verification();
            return new ApplicationResponse(verification.id(), IdentityDtos.CampusResponse.from(value.verification().campus()),
                    verification.requestedMembershipType(), verification.applicantName(), verification.affiliationNote(),
                    verification.expectedGraduationDate(), verification.status().name(), verification.reviewReason(),
                    verification.reviewedAt(), verification.createdAt(), verification.updatedAt(),
                    value.credentialPrecheckStatus().name());
        }
    }
    public record ErrorResponse(String code) { }
}
