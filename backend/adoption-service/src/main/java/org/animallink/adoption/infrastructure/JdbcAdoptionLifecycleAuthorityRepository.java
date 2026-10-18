package org.animallink.adoption.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.animallink.adoption.application.AdoptionLifecycleAuthorityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdoptionLifecycleAuthorityRepository
        implements AdoptionLifecycleAuthorityRepository {
    private final JdbcTemplate jdbc;

    public JdbcAdoptionLifecycleAuthorityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Lifecycle> findLatestByAnimalId(String animalId) {
        return jdbc.query("""
                SELECT l.animal_id, h.id AS handover_id, h.status AS handover_status,
                       h.initiated_at AS lifecycle_started_at,
                       r.id AS relation_id, r.status AS relation_status,
                       r.activated_at AS relation_activated_at, r.ended_at AS relation_ended_at
                FROM adoption_handover h
                JOIN adoption_selection s ON s.id = h.selection_id
                JOIN adoption_listing l ON l.id = s.listing_id
                LEFT JOIN adoption_relation r ON r.handover_id = h.id
                WHERE l.animal_id = ?
                ORDER BY h.initiated_at DESC, h.id DESC
                LIMIT 1
                """, (rs, row) -> new Lifecycle(rs.getString("animal_id"),
                rs.getString("handover_id"), rs.getString("handover_status"),
                rs.getTimestamp("lifecycle_started_at").toInstant(),
                rs.getString("relation_id"), rs.getString("relation_status"),
                instant(rs.getTimestamp("relation_activated_at")),
                instant(rs.getTimestamp("relation_ended_at"))), animalId).stream().findFirst();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
