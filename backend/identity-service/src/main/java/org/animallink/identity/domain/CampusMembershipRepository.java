package org.animallink.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CampusMembershipRepository {
    List<CampusMembershipView> findMembershipsByUserId(String userId);

    boolean existsActiveByUserAndCampus(String userId, String campusId);

    Optional<CampusMembership> findByUserAndCampus(String userId, String campusId);

    void upsertApproved(String membershipId, CampusVerification verification, Instant approvedAt);
}
