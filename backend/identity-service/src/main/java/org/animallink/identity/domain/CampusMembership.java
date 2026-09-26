package org.animallink.identity.domain;

import java.time.Instant;
import java.time.LocalDate;

public record CampusMembership(
        String id,
        String userId,
        String campusId,
        MembershipType membershipType,
        MembershipStatus status,
        LocalDate expectedGraduationDate,
        Instant approvedAt,
        String lastVerificationId,
        Instant createdAt,
        Instant updatedAt) {
}
