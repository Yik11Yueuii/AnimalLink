package org.animallink.adoption.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.animallink.adoption.application.AdoptionApplicationRepository;
import org.animallink.adoption.domain.AdoptionApplication;
import org.animallink.adoption.domain.ApplicationAlreadyExistsException;
import org.animallink.adoption.domain.ApplicationStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdoptionApplicationRepository implements AdoptionApplicationRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<AdoptionApplication> ROW = (rs, row) -> new AdoptionApplication(
            rs.getString("id"), rs.getString("listing_id"), rs.getString("applicant_user_id"),
            ApplicationStatus.valueOf(rs.getString("status")), rs.getString("message"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("withdrawn_at") == null ? null : rs.getTimestamp("withdrawn_at").toInstant());
    public JdbcAdoptionApplicationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean insertIfListingPublished(AdoptionApplication application) {
        try {
            return jdbc.update("""
                    INSERT INTO adoption_application(id,listing_id,applicant_user_id,status,message,created_at,updated_at,withdrawn_at)
                    SELECT ?,id,?, ?,?,?,?,? FROM adoption_listing WHERE id=? AND status='PUBLISHED'
                    """, application.id(), application.applicantUserId(), application.status().name(), application.message(),
                    Timestamp.from(application.createdAt()), Timestamp.from(application.updatedAt()), null, application.listingId()) == 1;
        } catch (DuplicateKeyException e) { throw new ApplicationAlreadyExistsException("该领养信息已有进行中的申请"); }
    }
    public Optional<AdoptionApplication> findById(String id) { return jdbc.query("SELECT * FROM adoption_application WHERE id=?", ROW, id).stream().findFirst(); }
    public List<AdoptionApplication> findByApplicant(String applicantUserId, int limit, int offset) { return jdbc.query("SELECT * FROM adoption_application WHERE applicant_user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", ROW, applicantUserId, limit, offset); }
    public long countByApplicant(String applicantUserId) { Long value = jdbc.queryForObject("SELECT COUNT(*) FROM adoption_application WHERE applicant_user_id=?", Long.class, applicantUserId); return value == null ? 0L : value; }
    public boolean withdraw(String id, String applicantUserId, java.time.Instant updatedAt, java.time.Instant withdrawnAt) { return jdbc.update("UPDATE adoption_application SET status='WITHDRAWN',updated_at=?,withdrawn_at=? WHERE id=? AND applicant_user_id=? AND status='SUBMITTED'", Timestamp.from(updatedAt), Timestamp.from(withdrawnAt), id, applicantUserId) == 1; }
}
