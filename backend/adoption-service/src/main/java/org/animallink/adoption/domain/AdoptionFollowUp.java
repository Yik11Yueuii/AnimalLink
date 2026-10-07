package org.animallink.adoption.domain;

import java.time.Instant;
import java.util.UUID;

public record AdoptionFollowUp(String id, String relationId, String content, Instant followedUpAt, Instant createdAt, Instant updatedAt) {
    public static AdoptionFollowUp create(String relationId, String content, Instant followedUpAt, Instant now) {
        return new AdoptionFollowUp(UUID.randomUUID().toString(), relationId, content, followedUpAt, now, now);
    }
}
