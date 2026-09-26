package org.animallink.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.animallink.identity.domain.Campus;
import org.animallink.identity.domain.CampusMembershipView;
import org.animallink.identity.domain.CampusVerificationView;
import org.animallink.identity.domain.MembershipStatus;
import org.animallink.identity.domain.MembershipType;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.VerificationStatus;

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
            Instant updatedAt) {
        public static CampusVerificationResponse from(CampusVerificationView view) {
            var verification = view.verification();
            return new CampusVerificationResponse(
                    verification.id(), verification.userId(), CampusResponse.from(view.campus()),
                    verification.requestedMembershipType(), verification.applicantName(),
                    verification.affiliationNote(), verification.expectedGraduationDate(),
                    verification.materialMediaId(), verification.status(), verification.reviewReason(),
                    verification.reviewedBy(), verification.reviewedAt(), verification.createdAt(),
                    verification.updatedAt());
        }
    }
}
