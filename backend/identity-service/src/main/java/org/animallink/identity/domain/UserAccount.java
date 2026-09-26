package org.animallink.identity.domain;

import java.time.Instant;

public record UserAccount(
        String id,
        String displayName,
        AccountStatus accountStatus,
        SystemRole systemRole,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isActive() {
        return accountStatus == AccountStatus.ACTIVE;
    }

    public boolean isGovernanceAdmin() {
        return systemRole == SystemRole.GOVERNANCE_ADMIN;
    }
}
