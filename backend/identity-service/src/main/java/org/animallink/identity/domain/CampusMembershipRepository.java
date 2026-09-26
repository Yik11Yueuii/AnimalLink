package org.animallink.identity.domain;

import java.time.Instant;
import java.util.List;

public interface CampusMembershipRepository {
    List<CampusMembershipView> findMembershipsByUserId(String userId);

    boolean existsActiveByUserAndCampus(String userId, String campusId);

    void upsertApproved(String membershipId, CampusVerification verification, Instant approvedAt);
}
