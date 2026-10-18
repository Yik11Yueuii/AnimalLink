package org.animallink.animal.application;

import java.time.Instant;

public record AdoptionLifecycleEvent(String eventId, String eventType, int eventVersion,
                                     Instant occurredAt, String animalId,
                                     String handoverId, Instant lifecycleStartedAt,
                                     String relationId) {
}
