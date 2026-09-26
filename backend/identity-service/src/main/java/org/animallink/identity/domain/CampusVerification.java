package org.animallink.identity.domain;

import java.time.Instant;
import java.time.LocalDate;

public record CampusVerification(
        String id,
        String userId,
        String campusId,
        MembershipType requestedMembershipType,
        String applicantName,
        String affiliationNote,
        LocalDate expectedGraduationDate,
        String materialMediaId,
        VerificationStatus status,
        String reviewReason,
        String reviewedBy,
        Instant reviewedAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
