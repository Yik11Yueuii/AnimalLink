package org.animallink.animal.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.animallink.animal.domain.AdoptionStatus;
import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalMergeRecord;
import org.animallink.animal.domain.AnimalMergeRepository;
import org.animallink.animal.domain.AnimalSex;
import org.animallink.animal.domain.AnimalSpecies;
import org.animallink.animal.domain.CurrentContext;
import org.animallink.animal.domain.IdentityStatus;
import org.animallink.animal.domain.StateConflictException;
import org.animallink.animal.domain.SterilizationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnimalMergeRepository implements AnimalMergeRepository {
    private static final String ANIMAL_COLUMNS = """
            SELECT id, campus_id, display_name, species, sex, coat_color,
                   distinctive_features, description, sterilization_status, typical_area,
                   identity_status, adoption_status, current_context, version, created_at, updated_at
            FROM animal
            """;
    private static final String MERGE_COLUMNS = """
            SELECT id, source_animal_id, target_animal_id, actor_user_id, reason, created_at
            FROM animal_merge_record
            """;

    private final JdbcTemplate jdbc;

    public JdbcAnimalMergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Animal> lockAnimals(String firstAnimalId, String secondAnimalId) {
        return jdbc.query(ANIMAL_COLUMNS + """
                WHERE id IN (?, ?)
                ORDER BY id
                FOR UPDATE
                """, this::mapAnimal, firstAnimalId, secondAnimalId);
    }

    @Override
    public boolean existsAsSource(String animalId) {
        return exists("SELECT EXISTS(SELECT 1 FROM animal_merge_record WHERE source_animal_id = ?)",
                animalId);
    }

    @Override
    public boolean existsAsTarget(String animalId) {
        return exists("SELECT EXISTS(SELECT 1 FROM animal_merge_record WHERE target_animal_id = ?)",
                animalId);
    }

    @Override
    public void reassignMedia(String sourceAnimalId, String targetAnimalId) {
        jdbc.update("UPDATE animal_media SET animal_id = ? WHERE animal_id = ?",
                targetAnimalId, sourceAnimalId);
    }

    @Override
    public void reassignTimeline(String sourceAnimalId, String targetAnimalId) {
        jdbc.update("UPDATE timeline_entry SET animal_id = ? WHERE animal_id = ?",
                targetAnimalId, sourceAnimalId);
    }

    @Override
    public void consolidateFollowers(String sourceAnimalId, String targetAnimalId) {
        jdbc.update("""
                UPDATE animal_follow target_follow
                JOIN animal_follow source_follow
                  ON source_follow.user_id = target_follow.user_id
                 AND source_follow.animal_id = ?
                SET target_follow.created_at = LEAST(
                    target_follow.created_at, source_follow.created_at)
                WHERE target_follow.animal_id = ?
                """, sourceAnimalId, targetAnimalId);
        jdbc.update("""
                INSERT INTO animal_follow (user_id, animal_id, created_at)
                SELECT source_follow.user_id, ?, source_follow.created_at
                FROM animal_follow source_follow
                WHERE source_follow.animal_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM animal_follow target_follow
                      WHERE target_follow.user_id = source_follow.user_id
                        AND target_follow.animal_id = ?
                  )
                """, targetAnimalId, sourceAnimalId, targetAnimalId);
        jdbc.update("DELETE FROM animal_follow WHERE animal_id = ?", sourceAnimalId);
    }

    @Override
    public void insert(AnimalMergeRecord record) {
        jdbc.update("""
                INSERT INTO animal_merge_record
                    (id, source_animal_id, target_animal_id, actor_user_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, record.id(), record.sourceAnimalId(), record.targetAnimalId(),
                record.actorUserId(), record.reason(), Timestamp.from(record.createdAt()));
    }

    @Override
    public void markSourceMerged(String sourceAnimalId, int expectedVersion) {
        int changed = jdbc.update("""
                UPDATE animal
                SET identity_status = 'MERGED', version = version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND identity_status = 'ACTIVE' AND version = ?
                """, sourceAnimalId, expectedVersion);
        if (changed != 1) {
            throw new StateConflictException("source Animal 已被其他操作修改，请重试");
        }
    }

    @Override
    public Optional<AnimalMergeRecord> findBySource(String sourceAnimalId) {
        return jdbc.query(MERGE_COLUMNS + " WHERE source_animal_id = ?",
                this::mapMerge, sourceAnimalId).stream().findFirst();
    }

    @Override
    public List<AnimalMergeRecord> findHistory(String animalId, int limit, int offset) {
        return jdbc.query(MERGE_COLUMNS + """
                WHERE source_animal_id = ? OR target_animal_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, this::mapMerge, animalId, animalId, limit, offset);
    }

    @Override
    public long countHistory(String animalId) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM animal_merge_record
                WHERE source_animal_id = ? OR target_animal_id = ?
                """, Long.class, animalId, animalId);
        return value == null ? 0 : value;
    }

    private boolean exists(String sql, String animalId) {
        Boolean value = jdbc.queryForObject(sql, Boolean.class, animalId);
        return Boolean.TRUE.equals(value);
    }

    private Animal mapAnimal(ResultSet rs, int rowNum) throws SQLException {
        return new Animal(rs.getString("id"), rs.getString("campus_id"),
                rs.getString("display_name"), AnimalSpecies.valueOf(rs.getString("species")),
                AnimalSex.valueOf(rs.getString("sex")), rs.getString("coat_color"),
                rs.getString("distinctive_features"), rs.getString("description"),
                SterilizationStatus.valueOf(rs.getString("sterilization_status")),
                rs.getString("typical_area"),
                IdentityStatus.valueOf(rs.getString("identity_status")),
                AdoptionStatus.valueOf(rs.getString("adoption_status")),
                CurrentContext.valueOf(rs.getString("current_context")), rs.getInt("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private AnimalMergeRecord mapMerge(ResultSet rs, int rowNum) throws SQLException {
        return new AnimalMergeRecord(rs.getString("id"), rs.getString("source_animal_id"),
                rs.getString("target_animal_id"), rs.getString("actor_user_id"),
                rs.getString("reason"), instant(rs, "created_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
