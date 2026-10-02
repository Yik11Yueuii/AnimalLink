package org.animallink.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VolunteerMembershipRepository {
    void insert(VolunteerMembership membership);
    Optional<VolunteerMembership> findById(String id);
    Optional<VolunteerMembership> findByIdForUpdate(String id);
    Optional<VolunteerMembership> findByUserAndCampus(String userId, String campusId);
    List<VolunteerMembership> findByUserId(String userId, int limit, int offset);
    List<VolunteerMembership> findForAdmin(String campusId, VolunteerMembershipStatus status, int limit, int offset);
    int transition(String id, int expectedVersion, VolunteerMembershipStatus expectedStatus,
                   VolunteerMembershipStatus nextStatus, String reviewReason, String reviewerId,
                   Instant reviewedAt, Instant activatedAt, Instant pausedAt, Instant endedAt);
}
