package org.animallink.intelligence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.animallink.intelligence.infrastructure.JdbcMatchingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An existing V8 task intent must survive V9 without losing the task-level unique key. */
@Testcontainers
class Phase2CIncrementalMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("intelligence_phase2c_migration")
            .withUsername("test").withPassword("test_password");

    @Test
    void historicalIntentRemainsCurrentAndCanBeSafelyArchivedAfterUpgrade() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(),
                MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("8")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String task = "40000000-0000-0000-0000-000000000702";
        String record = "50000000-0000-0000-0000-000000000702";
        String actor = "60000000-0000-0000-0000-000000000702";
        String campus = "70000000-0000-0000-0000-000000000702";
        String intent = "80000000-0000-0000-0000-000000000702";
        jdbc.update("""
                INSERT INTO ai_task
                (id, user_id, campus_id, task_type, status, model_provider,
                 model_name, prompt_version, input_summary, created_at)
                VALUES (?, ?, ?, 'ANIMAL_OBSERVATION_PARSE', 'SUCCEEDED',
                        'TEST', 'historical', 'v1', '{}', CURRENT_TIMESTAMP(6))
                """, task, actor, campus);
        jdbc.update("""
                INSERT INTO matching_record
                (id, user_id, ai_task_id, campus_id, algorithm_version, weight_version,
                 experiment_code, weights_json, top_k, candidate_count, low_confidence, created_at)
                VALUES (?, ?, ?, ?, 'v1', 'v1', 'D', '{}', 1, 0, TRUE, CURRENT_TIMESTAMP(6))
                """, record, actor, task, campus);
        jdbc.update("""
                INSERT INTO matching_decision_intent
                (id, matching_record_id, source_ai_task_id, user_id, decision_type,
                 selected_animal_id, selected_rank, selected_score, post_text, created_at)
                VALUES (?, ?, ?, ?, 'SELECT_EXISTING', ?, 1, 0.9, '历史确认文本', CURRENT_TIMESTAMP(6))
                """, intent, record, task, actor,
                "20000000-0000-0000-0000-000000000702");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();
        assertThat(jdbc.queryForObject("""
                SELECT decision_type FROM matching_decision_intent WHERE id = ?
                """, String.class, intent)).isEqualTo("SELECT_EXISTING");
        assertThat(jdbc.queryForObject("""
                SELECT fencing_version IS NULL AND deterministic_failure_code IS NULL
                FROM matching_decision_intent WHERE id = ?
                """, Boolean.class, intent)).isTrue();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO matching_decision_intent
                (id, matching_record_id, source_ai_task_id, user_id, decision_type, created_at)
                VALUES ('90000000-0000-0000-0000-000000000702', ?, ?, ?,
                        'UNSURE', CURRENT_TIMESTAMP(6))
                """, record, task, actor))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var repository = new JdbcMatchingRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.executeWithoutResult(status -> {
            repository.setIntentVersion(intent, 1);
            repository.setIntentFailure(intent, "SELECTED_ANIMAL_ARCHIVED");
            repository.retireIntent(repository.findIntentByAiTaskId(task).orElseThrow());
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matching_decision_intent WHERE id = ?",
                Integer.class, intent)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT deterministic_failure_code FROM matching_decision_intent_history WHERE id = ?
                """, String.class, intent)).isEqualTo("SELECTED_ANIMAL_ARCHIVED");
        jdbc.update("""
                INSERT INTO matching_decision_intent
                (id, matching_record_id, source_ai_task_id, user_id, decision_type, created_at)
                VALUES ('90000000-0000-0000-0000-000000000702', ?, ?, ?,
                        'UNSURE', CURRENT_TIMESTAMP(6))
                """, record, task, actor);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matching_decision_intent WHERE source_ai_task_id = ?",
                Integer.class, task)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class)).isGreaterThanOrEqualTo(9);
    }
}
