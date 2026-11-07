package org.animallink.animal.infrastructure;

import java.time.Instant;

public interface ObservationAssertionReplayStore {
    boolean consume(String nonce, Instant expiresAt);
}
