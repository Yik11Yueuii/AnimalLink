package org.animallink.adoption.application;
public interface IdentityGateway {
    CurrentUser requireCurrentUser();
    record CurrentUser(String id, String accountStatus, String systemRole) {
        public boolean isActiveGovernanceAdmin() { return "ACTIVE".equals(accountStatus) && "GOVERNANCE_ADMIN".equals(systemRole); }
    }
}
