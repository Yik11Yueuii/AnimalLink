package org.animallink.intelligence.infrastructure;

import org.animallink.intelligence.domain.*;
import org.animallink.intelligence.domain.ApiExceptions.Conflict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

@Repository
public class JdbcAiTaskRepository implements AiTaskRepository {
    private final JdbcTemplate jdbc;

    public JdbcAiTaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void create(AiTask task, List<String> mediaObjectKeys) {
        jdbc.update("""
                INSERT INTO ai_task (id, user_id, campus_id, task_type, status, model_provider,
                    model_name, prompt_version, input_summary, created_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.id(), task.userId(), task.campusId(), task.taskType().name(), task.status().name(),
                task.modelProvider(), task.modelName(), task.promptVersion(), task.inputSummary(),
                Timestamp.from(task.createdAt()), task.version());
        for (int index = 0; index < mediaObjectKeys.size(); index++) {
            jdbc.update("INSERT INTO ai_task_media (task_id, sort_order, object_key) VALUES (?, ?, ?)",
                    task.id(), index, mediaObjectKeys.get(index));
        }
    }

    @Override
    public boolean markRunning(String taskId, long version) {
        return jdbc.update("""
                UPDATE ai_task SET status = 'RUNNING', started_at = ?, version = version + 1
                WHERE id = ? AND status = 'PENDING' AND version = ?
                """, Timestamp.from(Instant.now()), taskId, version) == 1;
    }

    @Override
    @Transactional
    public void complete(AiTask task, AiResult result) {
        int changed = jdbc.update("""
                UPDATE ai_task SET status = 'SUCCEEDED', completed_at = ?, version = version + 1
                WHERE id = ? AND status = 'RUNNING' AND version = ?
                """, Timestamp.from(task.completedAt()), task.id(), task.version());
        if (changed != 1) throw new Conflict("AI 任务状态已变化");
        jdbc.update("""
                INSERT INTO ai_result (id, task_id, result_type, raw_response, structured_json,
                    schema_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, result.id(), result.taskId(), result.resultType(), result.rawResponse(),
                result.structuredJson(), result.schemaVersion(), Timestamp.from(result.createdAt()));
    }

    @Override
    public void fail(AiTask task) {
        jdbc.update("""
                UPDATE ai_task SET status = 'FAILED', failed_at = ?, error_code = ?, error_message = ?,
                    version = version + 1 WHERE id = ? AND status = 'RUNNING' AND version = ?
                """, Timestamp.from(task.failedAt()), task.errorCode(), task.errorMessage(), task.id(), task.version());
    }

    @Override
    public Optional<AiTaskBundle> findBundle(String taskId) {
        return jdbc.query("""
                SELECT t.*, r.id AS result_id, r.result_type, r.raw_response, r.structured_json,
                    r.schema_version, r.created_at AS result_created_at,
                    c.id AS confirmation_id, c.result_id AS confirmation_result_id,
                    c.confirmed_structured_json, c.was_modified,
                    c.confirmed_by AS confirmation_confirmed_by, c.confirmed_at AS confirmation_confirmed_at
                FROM ai_task t
                LEFT JOIN ai_result r ON r.task_id = t.id
                LEFT JOIN ai_confirmation c ON c.task_id = t.id
                WHERE t.id = ?
                """, this::mapBundle, taskId).stream().findFirst();
    }

    @Override
    public Optional<CredentialPrecheckTaskBundle> findCredentialPrecheckByIdempotencyKey(
            String idempotencyKey) {
        return jdbc.query("""
                SELECT t.*, r.id AS result_id, r.result_type, r.raw_response, r.structured_json,
                    r.schema_version, r.created_at AS result_created_at,
                    c.id AS confirmation_id, c.result_id AS confirmation_result_id,
                    c.confirmed_structured_json, c.was_modified,
                    c.confirmed_by AS confirmation_confirmed_by, c.confirmed_at AS confirmation_confirmed_at
                FROM ai_task t
                LEFT JOIN ai_result r ON r.task_id = t.id
                LEFT JOIN ai_confirmation c ON c.task_id = t.id
                WHERE t.task_type = 'CAMPUS_CREDENTIAL_PRECHECK' AND t.idempotency_key = ?
                """, (rs, rowNum) -> new CredentialPrecheckTaskBundle(
                mapBundle(rs, rowNum), rs.getString("idempotency_key"),
                rs.getString("external_reference_id")), idempotencyKey).stream().findFirst();
    }

    @Override
    public void createCredentialPrecheck(AiTask task, String idempotencyKey,
                                         String externalReferenceId) {
        jdbc.update("""
                INSERT INTO ai_task (id, user_id, campus_id, task_type, status, model_provider,
                    model_name, prompt_version, input_summary, idempotency_key,
                    external_reference_id, created_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.id(), task.userId(), task.campusId(), task.taskType().name(),
                task.status().name(), task.modelProvider(), task.modelName(), task.promptVersion(),
                task.inputSummary(), idempotencyKey, externalReferenceId,
                Timestamp.from(task.createdAt()), task.version());
    }

    @Override
    @Transactional
    public void confirm(AiTask task, AiConfirmation confirmation) {
        int changed = jdbc.update("""
                UPDATE ai_task SET confirmed_at = ?, confirmed_by = ?, version = version + 1
                WHERE id = ? AND status = 'SUCCEEDED' AND confirmed_at IS NULL AND version = ?
                """, Timestamp.from(task.confirmedAt()), task.confirmedBy(), task.id(), task.version());
        if (changed != 1) throw new Conflict("该草稿已经确认或任务状态已变化");
        jdbc.update("""
                INSERT INTO ai_confirmation (id, task_id, result_id, confirmed_structured_json,
                    was_modified, confirmed_by, confirmed_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, confirmation.id(), confirmation.taskId(), confirmation.resultId(),
                confirmation.confirmedStructuredJson(), confirmation.wasModified(),
                confirmation.confirmedBy(), Timestamp.from(confirmation.confirmedAt()));
    }

    @Override
    public List<String> findMediaObjectKeys(String taskId) {
        return jdbc.queryForList("""
                SELECT object_key FROM ai_task_media WHERE task_id = ? ORDER BY sort_order
                """, String.class, taskId);
    }

    private AiTaskBundle mapBundle(ResultSet rs, int rowNum) throws SQLException {
        AiTask task = new AiTask(rs.getString("id"), rs.getString("user_id"), rs.getString("campus_id"),
                AiTaskType.valueOf(rs.getString("task_type")), AiTaskStatus.valueOf(rs.getString("status")),
                rs.getString("model_provider"), rs.getString("model_name"), rs.getString("prompt_version"),
                rs.getString("input_summary"), instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "completed_at"), instant(rs, "failed_at"), rs.getString("error_code"),
                rs.getString("error_message"), instant(rs, "confirmed_at"), rs.getString("confirmed_by"),
                rs.getLong("version"));
        AiResult result = rs.getString("result_id") == null ? null : new AiResult(
                rs.getString("result_id"), task.id(), rs.getString("result_type"),
                rs.getString("raw_response"), rs.getString("structured_json"),
                rs.getString("schema_version"), instant(rs, "result_created_at"));
        AiConfirmation confirmation = rs.getString("confirmation_id") == null ? null : new AiConfirmation(
                rs.getString("confirmation_id"), task.id(), rs.getString("confirmation_result_id"),
                rs.getString("confirmed_structured_json"), rs.getBoolean("was_modified"),
                rs.getString("confirmation_confirmed_by"), instant(rs, "confirmation_confirmed_at"));
        return new AiTaskBundle(task, result, confirmation);
    }

    private Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
