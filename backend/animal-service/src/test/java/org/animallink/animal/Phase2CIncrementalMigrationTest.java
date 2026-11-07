package org.animallink.animal;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises V15 backfill and V16 nonce storage against a populated V14 database. */
@Testcontainers
class Phase2CIncrementalMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animal_phase2c_migration")
            .withUsername("test").withPassword("test_password");

    @Test
    void existingFinalizationIsPermanentlyFencedAndNonceTableIsUnique() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("14")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String animal = "20000000-0000-0000-0000-000000000701";
        String post = "30000000-0000-0000-0000-000000000701";
        String task = "40000000-0000-0000-0000-000000000701";
        String record = "50000000-0000-0000-0000-000000000701";
        String actor = "60000000-0000-0000-0000-000000000701";
        String campus = "70000000-0000-0000-0000-000000000701";
        jdbc.update("""
                INSERT INTO animal (id, campus_id, display_name, species)
                VALUES (?, ?, '迁移前动物', 'CAT')
                """, animal, campus);
        jdbc.update("""
                INSERT INTO post (id, campus_id, animal_id, author_user_id, text_content,
                                  source_ai_task_id, source_matching_record_id)
                VALUES (?, ?, ?, ?, '迁移前观察', ?, ?)
                """, post, campus, animal, actor, task, record);
        jdbc.update("""
                INSERT INTO observation_finalization
                (id, source_matching_record_id, source_ai_task_id, user_id, campus_id,
                 decision_type, selected_animal_id, post_id, decision_hash)
                VALUES (?, ?, ?, ?, ?, 'SELECT_EXISTING', ?, ?, REPEAT('a', 64))
                """, "80000000-0000-0000-0000-000000000701", record, task,
                actor, campus, animal, post);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM observation_attempt_gate WHERE source_ai_task_id = ?
                """, String.class, task)).isEqualTo("FINALIZED");
        assertThat(jdbc.queryForObject("""
                SELECT fencing_version FROM observation_attempt_gate WHERE source_ai_task_id = ?
                """, Long.class, task)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT attempt_id FROM observation_attempt_history WHERE source_ai_task_id = ?
                """, String.class, task)).isEqualTo(record);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO observation_assertion_replay (jti, expires_at)
                VALUES ('90000000-0000-0000-0000-000000000701', CURRENT_TIMESTAMP(6)),
                       ('90000000-0000-0000-0000-000000000701', CURRENT_TIMESTAMP(6))
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class)).isGreaterThanOrEqualTo(16);
    }
}
