package org.animallink.animal.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import org.animallink.animal.domain.AdoptionStatus;
import org.animallink.animal.domain.AnimalAdoptionStatusCommand;
import org.animallink.animal.domain.AnimalAdoptionStatusCommandRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnimalAdoptionStatusCommandRepository
        implements AnimalAdoptionStatusCommandRepository {
    private static final String COLUMNS = """
            SELECT idempotency_key, request_hash, animal_id, transition_action,
                   from_status, to_status, actor_user_id, reason, result_version, created_at
            FROM animal_adoption_status_command
            """;

    private final JdbcTemplate jdbc;

    public JdbcAnimalAdoptionStatusCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AnimalAdoptionStatusCommand> findByKey(String idempotencyKey) {
        return jdbc.query(COLUMNS + " WHERE idempotency_key = ?", this::map, idempotencyKey)
                .stream().findFirst();
    }

    @Override
    public Optional<AnimalAdoptionStatusCommand> findByKeyForUpdate(String idempotencyKey) {
        return jdbc.query(COLUMNS + " WHERE idempotency_key = ? FOR UPDATE", this::map,
                idempotencyKey).stream().findFirst();
    }

    @Override
    public void insert(AnimalAdoptionStatusCommand command) {
        jdbc.update("""
                INSERT INTO animal_adoption_status_command
                    (idempotency_key, request_hash, animal_id, transition_action,
                     from_status, to_status, actor_user_id, reason, result_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.idempotencyKey(), command.requestHash(), command.animalId(),
                command.action().name(), command.fromStatus().name(), command.toStatus().name(),
                command.actorUserId(), command.reason(), command.resultVersion(),
                Timestamp.from(command.createdAt()));
    }

    private AnimalAdoptionStatusCommand map(ResultSet rs, int rowNum) throws SQLException {
        return new AnimalAdoptionStatusCommand(rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getString("animal_id"),
                AnimalAdoptionStatusCommand.Action.valueOf(rs.getString("transition_action")),
                AdoptionStatus.valueOf(rs.getString("from_status")),
                AdoptionStatus.valueOf(rs.getString("to_status")),
                rs.getString("actor_user_id"), rs.getString("reason"),
                rs.getInt("result_version"), rs.getTimestamp("created_at").toInstant());
    }
}
