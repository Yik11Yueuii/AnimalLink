package org.animallink.intelligence.application;

public interface IdentityGateway {
    CurrentUser requireCurrentUser();
    MembershipFact campusMembership(String userId, String campusId);

    record CurrentUser(String id, String displayName, String accountStatus, String systemRole) {}
    record MembershipFact(boolean exists, String membershipType, String status) {}
}
