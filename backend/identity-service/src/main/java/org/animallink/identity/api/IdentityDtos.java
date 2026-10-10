package org.animallink.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.animallink.identity.application.AdminCampusVerificationView;
import org.animallink.identity.application.ApplicantCampusVerificationView;
import org.animallink.identity.application.ApplicantCredentialPrecheckStatus;
import org.animallink.identity.domain.Campus;
import org.animallink.identity.domain.CampusMembershipView;
import org.animallink.identity.domain.CampusVerificationView;
import org.animallink.identity.domain.CredentialPrecheckStatus;
import org.animallink.identity.domain.MembershipStatus;
import org.animallink.identity.domain.MembershipType;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.VerificationStatus;
import org.animallink.identity.domain.VolunteerMembership;
import org.animallink.identity.domain.VolunteerMembershipStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class IdentityDtos {
    private IdentityDtos() {
    }

    public record UserResponse(
            String id,
            String displayName,
            String accountStatus,
            String systemRole,
            Instant createdAt,
            Instant updatedAt) {
        public static UserResponse from(UserAccount user) {
            return new UserResponse(user.id(), user.displayName(), user.accountStatus().name(),
                    user.systemRole().name(), user.createdAt(), user.updatedAt());
        }
    }

    public record CampusResponse(
            String id,
            String name,
            String shortName,
            String city,
            String region,
            String status) {
        public static CampusResponse from(Campus campus) {
            return new CampusResponse(campus.id(), campus.name(), campus.shortName(), campus.city(),
                    campus.region(), campus.status().name());
        }
    }

    public record CampusMembershipResponse(
            String id,
            CampusResponse campus,
            MembershipType membershipType,
            MembershipStatus status,
            LocalDate expectedGraduationDate,
            Instant approvedAt,
            Instant createdAt,
            Instant updatedAt) {
        public static CampusMembershipResponse from(CampusMembershipView view) {
            var membership = view.membership();
            return new CampusMembershipResponse(membership.id(), CampusResponse.from(view.campus()),
                    membership.membershipType(), membership.status(), membership.expectedGraduationDate(),
                    membership.approvedAt(), membership.createdAt(), membership.updatedAt());
        }
    }

    public record InternalMembershipResponse(
            boolean exists,
            String membershipType,
            String status) {
        public static InternalMembershipResponse missing() {
            return new InternalMembershipResponse(false, null, null);
        }

        public static InternalMembershipResponse from(org.animallink.identity.domain.CampusMembership membership) {
            return new InternalMembershipResponse(true, membership.membershipType().name(),
                    membership.status().name());
        }
    }

    public record UserSummariesRequest(
            @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 36) String> userIds) {
    }

    public record UserSummaryResponse(String id, String displayName, String accountStatus) {
        public static UserSummaryResponse from(UserAccount user) {
            return new UserSummaryResponse(user.id(), user.displayName(), user.accountStatus().name());
        }
    }

    public record SubmitVerificationRequest(
            @NotBlank @Size(max = 36) String campusId,
            @NotNull MembershipType requestedMembershipType,
            @NotBlank @Size(max = 80) String applicantName,
            @Size(max = 255) String affiliationNote,
            LocalDate expectedGraduationDate,
            @Size(max = 36) String materialMediaId) {
    }

    public record ReviewRequest(@Size(max = 500) String reviewReason) {
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String reviewReason) {
    }

    public record VolunteerMembershipApplyRequest(
            @NotBlank @Size(max = 36) String campusId,
            @Size(max = 500) String applicationNote) {
    }

    public record VolunteerMembershipResponse(
            String id, String userId, String campusId, VolunteerMembershipStatus status,
            String applicationNote, String reviewReason, String reviewedBy, Instant reviewedAt,
            Instant activatedAt, Instant pausedAt, Instant endedAt, Instant createdAt, Instant updatedAt) {
        public static VolunteerMembershipResponse from(VolunteerMembership value) {
            return new VolunteerMembershipResponse(value.id(), value.userId(), value.campusId(), value.status(),
                    value.applicationNote(), value.reviewReason(), value.reviewedBy(), value.reviewedAt(),
                    value.activatedAt(), value.pausedAt(), value.endedAt(), value.createdAt(), value.updatedAt());
        }
    }

    public record InternalVolunteerMembershipResponse(
            boolean exists, String userId, String campusId, String status, boolean active) {
        public static InternalVolunteerMembershipResponse missing(String userId, String campusId) {
            return new InternalVolunteerMembershipResponse(false, userId, campusId, null, false);
        }
        public static InternalVolunteerMembershipResponse from(VolunteerMembership value) {
            return new InternalVolunteerMembershipResponse(true, value.userId(), value.campusId(),
                    value.status().name(), value.isActive());
        }
    }

    public record CampusVerificationResponse(
            String id,
            String userId,
            CampusResponse campus,
            MembershipType requestedMembershipType,
            String applicantName,
            String affiliationNote,
            LocalDate expectedGraduationDate,
            String materialMediaId,
            VerificationStatus status,
            String reviewReason,
            String reviewedBy,
            Instant reviewedAt,
            Instant createdAt,
            Instant updatedAt,
            ApplicantCredentialPrecheckStatus credentialPrecheckStatus) {
        public static CampusVerificationResponse from(ApplicantCampusVerificationView applicantView) {
            var view = applicantView.verification();
            var verification = view.verification();
            return new CampusVerificationResponse(
                    verification.id(), verification.userId(), CampusResponse.from(view.campus()),
                    verification.requestedMembershipType(), verification.applicantName(),
                    verification.affiliationNote(), verification.expectedGraduationDate(),
                    verification.materialMediaId(), verification.status(), verification.reviewReason(),
                    verification.reviewedBy(), verification.reviewedAt(), verification.createdAt(),
                    verification.updatedAt(), applicantView.credentialPrecheckStatus());
        }
    }

    public record AdminCampusVerificationListResponse(
            String id,
            String userId,
            CampusResponse campus,
            MembershipType requestedMembershipType,
            String applicantName,
            String affiliationNote,
            LocalDate expectedGraduationDate,
            String materialMediaId,
            VerificationStatus status,
            String reviewReason,
            String reviewedBy,
            Instant reviewedAt,
            Instant createdAt,
            Instant updatedAt,
            boolean credentialMaterialPresent) {
        public static AdminCampusVerificationListResponse from(CampusVerificationView view) {
            var verification = view.verification();
            return new AdminCampusVerificationListResponse(
                    verification.id(), verification.userId(), CampusResponse.from(view.campus()),
                    verification.requestedMembershipType(), verification.applicantName(),
                    verification.affiliationNote(), verification.expectedGraduationDate(),
                    verification.materialMediaId(), verification.status(), verification.reviewReason(),
                    verification.reviewedBy(), verification.reviewedAt(), verification.createdAt(),
                    verification.updatedAt(), verification.materialMediaId() != null);
        }
    }

    public record AdminCampusVerificationResponse(
            String id,
            String userId,
            CampusResponse campus,
            MembershipType requestedMembershipType,
            String applicantName,
            String affiliationNote,
            LocalDate expectedGraduationDate,
            String materialMediaId,
            VerificationStatus status,
            String reviewReason,
            String reviewedBy,
            Instant reviewedAt,
            Instant createdAt,
            Instant updatedAt,
            AdminCredentialPrecheckResponse precheck) {
        public static AdminCampusVerificationResponse from(AdminCampusVerificationView adminView) {
            var view = adminView.verification();
            var verification = view.verification();
            return new AdminCampusVerificationResponse(
                    verification.id(), verification.userId(), CampusResponse.from(view.campus()),
                    verification.requestedMembershipType(), verification.applicantName(),
                    verification.affiliationNote(), verification.expectedGraduationDate(),
                    verification.materialMediaId(), verification.status(), verification.reviewReason(),
                    verification.reviewedBy(), verification.reviewedAt(), verification.createdAt(),
                    verification.updatedAt(), AdminCredentialPrecheckResponse.from(adminView.precheck()));
        }
    }

    public record AdminCredentialPrecheckResponse(
            String attemptId,
            int attemptNo,
            CredentialPrecheckStatus status,
            String intelligenceTaskId,
            String provider,
            String modelName,
            BigDecimal overallConfidence,
            String extractedSchoolName,
            String extractedPersonName,
            String credentialType,
            List<String> consistencyFlags,
            String summary,
            String errorCategory,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt) {
        static AdminCredentialPrecheckResponse from(AdminCampusVerificationView.CredentialPrecheckView value) {
            if (value == null) return null;
            return new AdminCredentialPrecheckResponse(
                    value.attemptId(), value.attemptNo(), value.status(), value.intelligenceTaskId(),
                    value.provider(), value.modelName(), value.overallConfidence(), value.extractedSchoolName(),
                    value.extractedPersonName(), value.credentialType(), value.consistencyFlags(), value.summary(),
                    value.errorCategory(), value.createdAt(), value.startedAt(), value.completedAt());
        }
    }
}
