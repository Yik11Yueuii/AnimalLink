package org.animallink.animal.infrastructure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** Autocommit inserts make replay protection independent of the business transaction outcome. */
@Repository
public class JdbcObservationAssertionReplayStore implements ObservationAssertionReplayStore {
    private final JdbcTemplate jdbc;

    public JdbcObservationAssertionReplayStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean consume(String nonce, Instant expiresAt) {
        // Retain nonces beyond token expiry to tolerate clock differences across instances.
        // Both the insert and cleanup use JDBC timestamps from the application clock.
        jdbc.update("DELETE FROM observation_assertion_replay WHERE expires_at <= ?",
                Timestamp.from(Instant.now().minusSeconds(60)));
        try {
            return jdbc.update("INSERT INTO observation_assertion_replay (jti, expires_at) VALUES (?, ?)",
                    nonce, Timestamp.from(expiresAt)) == 1;
        } catch (DuplicateKeyException replay) {
            return false;
        }
    }
}
