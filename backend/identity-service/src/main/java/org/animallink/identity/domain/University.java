package org.animallink.identity.domain;

import java.time.Instant;
import java.time.LocalDate;

public record University(
        String id,
        String moeInstitutionCode,
        String officialName,
        String shortName,
        String province,
        String city,
        UniversityStatus status,
        String sourceName,
        String sourceUrl,
        LocalDate sourceUpdatedAt,
        boolean communityAvailable,
        Instant createdAt,
        Instant updatedAt) {
}
