package org.animallink.animal.application;

public interface IdentityGateway {
    void requireActiveCampus(String campusId);

    CurrentUser requireCurrentUser();

    record CurrentUser(String id, String accountStatus, String systemRole) {
    }
}
