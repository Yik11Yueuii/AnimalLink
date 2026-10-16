package org.animallink.identity.domain;

import java.time.Instant;

public record UserGovernanceAudit(
        String id,
        String userId,
        UserGovernanceAction action,
        AccountStatus fromStatus,
        AccountStatus toStatus,
        String actorUserId,
        String reason,
        Instant createdAt) {
}
