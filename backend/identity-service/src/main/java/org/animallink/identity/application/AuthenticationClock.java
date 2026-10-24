package org.animallink.identity.application;

import java.time.Instant;

public interface AuthenticationClock {
    Instant now();
}
