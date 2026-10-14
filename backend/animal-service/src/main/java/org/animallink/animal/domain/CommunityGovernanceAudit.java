package org.animallink.animal.domain;

import java.time.Instant;

public record CommunityGovernanceAudit(
        String id,
        GovernanceResourceType resourceType,
        String resourceId,
        GovernanceAction action,
        String fromState,
        String toState,
        String actorUserId,
        String reason,
        Instant createdAt) {
}
