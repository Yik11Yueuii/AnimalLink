package org.animallink.identity.domain;

import java.time.Instant;

public record VolunteerMembership(
        String id, String userId, String campusId, VolunteerMembershipStatus status,
        String applicationNote, String reviewReason, String reviewedBy, Instant reviewedAt,
        Instant activatedAt, Instant pausedAt, Instant endedAt, int version,
        Instant createdAt, Instant updatedAt) {
    public boolean isActive() { return status == VolunteerMembershipStatus.ACTIVE; }
}
