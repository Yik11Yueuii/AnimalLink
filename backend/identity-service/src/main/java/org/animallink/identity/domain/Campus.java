package org.animallink.identity.domain;

import java.time.Instant;

public record Campus(
        String id,
        String name,
        String shortName,
        String city,
        String region,
        CampusStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
