package org.animallink.animal.domain;

import java.time.Instant;

public record AnimalAdoptionStatusCommand(
        String idempotencyKey,
        String requestHash,
        String animalId,
        Action action,
        AdoptionStatus fromStatus,
        AdoptionStatus toStatus,
        String actorUserId,
        String reason,
        int resultVersion,
        Instant createdAt) {

    public enum Action {
        OPEN,
        CLOSE
    }
}
