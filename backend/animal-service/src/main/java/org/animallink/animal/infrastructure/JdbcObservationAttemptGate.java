package org.animallink.animal.infrastructure;

import org.animallink.animal.domain.ObservationAttemptConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** All methods that mutate the gate are called inside the caller's local transaction. */
@Repository
public class JdbcObservationAttemptGate {
    private final JdbcTemplate jdbc;

    public JdbcObservationAttemptGate(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Gate(String taskId, String attemptId, String actorId, String campusId,
                       String matchingRecordId, String decisionType, String selectedAnimalId,
                       long version, String status,
                       String finalizationId) { }

    public Gate lock(String taskId) {
        List<Gate> rows = jdbc.query("""
                SELECT source_ai_task_id, attempt_id, actor_user_id, campus_id,
                       matching_record_id, decision_type, selected_animal_id,
                       fencing_version, status, finalization_id
                FROM observation_attempt_gate WHERE source_ai_task_id = ? FOR UPDATE
        """, (rs, row) -> new Gate(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7),
                rs.getLong(8), rs.getString(9), rs.getString(10)), taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Gate register(String taskId, String attemptId, String actorId,
                         String campusId, String matchingRecordId,
                         String decisionType, String selectedAnimalId) {
        Gate current = lock(taskId);
        Instant now = Instant.now();
        if (current == null) {
            jdbc.update("""
                    INSERT INTO observation_attempt_gate
                    (source_ai_task_id, attempt_id, actor_user_id, campus_id, matching_record_id,
                     decision_type, selected_animal_id,
                     fencing_version, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'ACTIVE', ?, ?)
                    """, taskId, attemptId, actorId, campusId, matchingRecordId,
                    decisionType, selectedAnimalId,
                    Timestamp.from(now), Timestamp.from(now));
            history(taskId, attemptId, actorId, campusId, matchingRecordId,
                    decisionType, selectedAnimalId, 1, now);
            return new Gate(taskId, attemptId, actorId, campusId, matchingRecordId,
                    decisionType, selectedAnimalId,
                    1, "ACTIVE", null);
        }
        if (("ACTIVE".equals(current.status()) || "FINALIZED".equals(current.status()))
                && attemptId.equals(current.attemptId())) {
            requireContext(current, actorId, campusId, matchingRecordId,
                    decisionType, selectedAnimalId);
            return current;
        }
        if (!"RETIRED".equals(current.status()) || attemptId.equals(current.attemptId())) {
            throw new ObservationAttemptConflictException("该 AI Task 已保留其他正式化尝试");
        }
        Integer reused = jdbc.queryForObject("""
                SELECT COUNT(*) FROM observation_attempt_history
                WHERE source_ai_task_id = ? AND attempt_id = ?
                """, Integer.class, taskId, attemptId);
        if (reused != null && reused > 0) {
            throw new ObservationAttemptConflictException("旧尝试 ID 不可重新取得写入资格");
        }
        long version = Math.addExact(current.version(), 1);
        // The history uniqueness constraint also rejects reuse of any older retired attempt ID.
        history(taskId, attemptId, actorId, campusId, matchingRecordId,
                decisionType, selectedAnimalId, version, now);
        jdbc.update("""
                UPDATE observation_attempt_gate
                SET attempt_id = ?, actor_user_id = ?, campus_id = ?, matching_record_id = ?,
                    decision_type = ?, selected_animal_id = ?,
                    fencing_version = ?, status = 'ACTIVE',
                    finalization_id = NULL, updated_at = ?
                WHERE source_ai_task_id = ?
                """, attemptId, actorId, campusId, matchingRecordId,
                decisionType, selectedAnimalId,
                version, Timestamp.from(now), taskId);
        return new Gate(taskId, attemptId, actorId, campusId, matchingRecordId,
                decisionType, selectedAnimalId,
                version, "ACTIVE", null);
    }

    public Gate requireCurrent(String taskId, String attemptId, long version,
                               String actorId, String campusId, String matchingRecordId,
                               String decisionType, String selectedAnimalId) {
        Gate gate = lock(taskId);
        if (gate == null || !attemptId.equals(gate.attemptId()) || version != gate.version()
                || !("ACTIVE".equals(gate.status()) || "FINALIZED".equals(gate.status()))) {
            throw new ObservationAttemptConflictException("正式化尝试已失效或版本不匹配");
        }
        requireContext(gate, actorId, campusId, matchingRecordId,
                decisionType, selectedAnimalId);
        return gate;
    }

    private void requireContext(Gate gate, String actorId, String campusId,
                                String matchingRecordId, String decisionType,
                                String selectedAnimalId) {
        if (!gate.actorId().equals(actorId) || !gate.campusId().equals(campusId)
                || !gate.matchingRecordId().equals(matchingRecordId)
                || !gate.decisionType().equals(decisionType)
                || !java.util.Objects.equals(gate.selectedAnimalId(), selectedAnimalId)) {
            throw new ObservationAttemptConflictException("正式化尝试上下文不匹配");
        }
    }

    public void finalizeAttempt(Gate gate, String finalizationId) {
        Instant now = Instant.now();
        int changed = jdbc.update("""
                UPDATE observation_attempt_gate SET status = 'FINALIZED', finalization_id = ?, updated_at = ?
                WHERE source_ai_task_id = ? AND fencing_version = ? AND status = 'ACTIVE'
                """, finalizationId, Timestamp.from(now), gate.taskId(), gate.version());
        if (changed != 1) throw new ObservationAttemptConflictException("正式化屏障状态已变化");
        jdbc.update("""
                UPDATE observation_attempt_history SET status = 'FINALIZED', finalization_id = ?, updated_at = ?
                WHERE source_ai_task_id = ? AND fencing_version = ?
                """, finalizationId, Timestamp.from(now), gate.taskId(), gate.version());
    }

    public void retire(Gate gate, String failureCode) {
        if (!"ACTIVE".equals(gate.status())) {
            throw new ObservationAttemptConflictException("正式化尝试不可撤销");
        }
        Instant now = Instant.now();
        int changed = jdbc.update("""
                UPDATE observation_attempt_gate SET status = 'RETIRED', updated_at = ?
                WHERE source_ai_task_id = ? AND fencing_version = ? AND status = 'ACTIVE'
                """, Timestamp.from(now), gate.taskId(), gate.version());
        if (changed != 1) throw new ObservationAttemptConflictException("撤销屏障状态已变化");
        jdbc.update("""
                UPDATE observation_attempt_history SET status = 'RETIRED', failure_code = ?, updated_at = ?
                WHERE source_ai_task_id = ? AND fencing_version = ?
                """, failureCode, Timestamp.from(now), gate.taskId(), gate.version());
    }

    private void history(String taskId, String attemptId, String actorId, String campusId,
                         String matchingRecordId, String decisionType, String selectedAnimalId,
                         long version, Instant now) {
        jdbc.update("""
                INSERT INTO observation_attempt_history
                (source_ai_task_id, fencing_version, attempt_id, actor_user_id, campus_id,
                 matching_record_id, decision_type, selected_animal_id,
                 status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, taskId, version, attemptId, actorId, campusId, matchingRecordId,
                decisionType, selectedAnimalId,
                Timestamp.from(now), Timestamp.from(now));
    }
}
