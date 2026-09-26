package org.animallink.animal.domain;

import java.time.Instant;

public record TimelineEntry(
        String id,
        String animalId,
        String sourceType,
        String sourceId,
        String entryType,
        String title,
        String summary,
        Instant occurredAt,
        Visibility visibility,
        Instant createdAt) {
}
