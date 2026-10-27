package org.animallink.identity.domain;

import java.time.Instant;
import java.time.LocalDate;

public record Campus(
        String id,
        String universityId,
        String name,
        String shortName,
        String city,
        String region,
        CampusType campusType,
        String address,
        String sourceUrl,
        LocalDate sourceUpdatedAt,
        CampusStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
