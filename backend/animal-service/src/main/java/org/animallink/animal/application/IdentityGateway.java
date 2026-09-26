package org.animallink.animal.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface IdentityGateway {
    void requireActiveCampus(String campusId);

    CurrentUser requireCurrentUser();

    Optional<CurrentUser> currentUserIfPresent();

    MembershipFact campusMembership(String userId, String campusId);

    Map<String, UserSummary> userSummaries(Set<String> userIds);

    record CurrentUser(String id, String displayName, String accountStatus, String systemRole) {
    }

    record MembershipFact(boolean exists, String membershipType, String status) {
    }

    record UserSummary(String id, String displayName) {
    }
}
