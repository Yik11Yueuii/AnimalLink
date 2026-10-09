package org.animallink.identity.infrastructure;

import org.animallink.identity.domain.CredentialMaterial;
import org.animallink.identity.domain.CredentialMaterialRepository;
import org.animallink.identity.domain.CredentialMaterialStatus;
import org.animallink.identity.domain.CredentialPrecheckAttempt;
import org.animallink.identity.domain.CredentialPrecheckAttemptRepository;
import org.animallink.identity.domain.CredentialPrecheckStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCredentialMaterialRepository implements CredentialMaterialRepository, CredentialPrecheckAttemptRepository {
    private final JdbcTemplate jdbc;

    public JdbcCredentialMaterialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(CredentialMaterial material) {
        jdbc.update("""
                INSERT INTO credential_material
                    (id, owner_user_id, object_key, content_type, size_bytes, status, created_at, uploaded_at, attached_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, material.id(), material.ownerUserId(), material.objectKey(), material.contentType(),
                material.sizeBytes(), material.status().name(), Timestamp.from(material.createdAt()),
                timestamp(material.uploadedAt()), timestamp(material.attachedAt()));
    }

    @Override
    public Optional<CredentialMaterial> findById(String materialId) {
        return one("SELECT * FROM credential_material WHERE id = ?", materialId);
    }

    @Override
    public Optional<CredentialMaterial> findOwnedById(String materialId, String ownerUserId) {
        return one("SELECT * FROM credential_material WHERE id = ? AND owner_user_id = ?", materialId, ownerUserId);
    }

    @Override
    public Optional<CredentialMaterial> findByIdForUpdate(String materialId) {
        return one("SELECT * FROM credential_material WHERE id = ? FOR UPDATE", materialId);
    }

    @Override
    public boolean markReady(String materialId, Instant uploadedAt, String contentType, long sizeBytes) {
        return jdbc.update("""
                UPDATE credential_material
                SET status = 'READY', content_type = ?, size_bytes = ?, uploaded_at = ?
                WHERE id = ? AND status = 'PENDING_UPLOAD'
                """, contentType, sizeBytes, Timestamp.from(uploadedAt), materialId) == 1;
    }

    @Override
    public void markInvalid(String materialId) {
        jdbc.update("UPDATE credential_material SET status = 'INVALID' WHERE id = ? AND status = 'PENDING_UPLOAD'", materialId);
    }

    @Override
    public boolean attachReady(String materialId, Instant attachedAt) {
        return jdbc.update("""
                UPDATE credential_material
                SET status = 'ATTACHED', attached_at = ?
                WHERE id = ? AND status = 'READY'
                """, Timestamp.from(attachedAt), materialId) == 1;
    }

    @Override
    public void insert(CredentialPrecheckAttempt attempt) {
        jdbc.update("""
                INSERT INTO credential_precheck_attempt
                    (id, verification_id, attempt_no, intelligence_task_id, status, provider, model_name,
                     overall_confidence, extracted_school_name, extracted_person_name, credential_type,
                     consistency_flags, summary, error_category, created_at, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, attempt.id(), attempt.verificationId(), attempt.attemptNo(), attempt.intelligenceTaskId(),
                attempt.status().name(), attempt.provider(), attempt.modelName(), attempt.overallConfidence(),
                attempt.extractedSchoolName(), attempt.extractedPersonName(), attempt.credentialType(),
                attempt.consistencyFlags(), attempt.summary(), attempt.errorCategory(), Timestamp.from(attempt.createdAt()),
                timestamp(attempt.startedAt()), timestamp(attempt.completedAt()));
    }

    @Override
    public List<CredentialPrecheckAttempt> findByVerificationId(String verificationId) {
        return jdbc.query("SELECT * FROM credential_precheck_attempt WHERE verification_id = ? ORDER BY attempt_no", ATTEMPT, verificationId);
    }

    private Optional<CredentialMaterial> one(String sql, Object... arguments) {
        return jdbc.query(sql, MATERIAL, arguments).stream().findFirst();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<CredentialMaterial> MATERIAL = (rs, ignored) -> new CredentialMaterial(
            rs.getString("id"), rs.getString("owner_user_id"), rs.getString("object_key"),
            rs.getString("content_type"), (Long) rs.getObject("size_bytes"),
            CredentialMaterialStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
            instant(rs, "uploaded_at"), instant(rs, "attached_at"));

    private static final RowMapper<CredentialPrecheckAttempt> ATTEMPT = (rs, ignored) -> new CredentialPrecheckAttempt(
            rs.getString("id"), rs.getString("verification_id"), rs.getInt("attempt_no"),
            rs.getString("intelligence_task_id"), CredentialPrecheckStatus.valueOf(rs.getString("status")),
            rs.getString("provider"), rs.getString("model_name"), rs.getObject("overall_confidence", BigDecimal.class),
            rs.getString("extracted_school_name"), rs.getString("extracted_person_name"),
            rs.getString("credential_type"), rs.getString("consistency_flags"), rs.getString("summary"),
            rs.getString("error_category"), instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "completed_at"));
}
