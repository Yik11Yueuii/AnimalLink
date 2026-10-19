package org.animallink.identity.infrastructure;

import org.animallink.identity.application.AuthenticationClock;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class SystemAuthenticationClock implements AuthenticationClock {
    private final Clock clock = Clock.systemUTC();

    @Override
    public Instant now() {
        return clock.instant();
    }
}
