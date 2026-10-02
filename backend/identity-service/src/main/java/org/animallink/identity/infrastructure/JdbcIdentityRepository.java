package org.animallink.identity.infrastructure;

import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.Campus;
import org.animallink.identity.domain.CampusMembership;
import org.animallink.identity.domain.CampusMembershipRepository;
import org.animallink.identity.domain.CampusMembershipView;
import org.animallink.identity.domain.CampusRepository;
import org.animallink.identity.domain.CampusStatus;
import org.animallink.identity.domain.CampusVerification;
import org.animallink.identity.domain.CampusVerificationRepository;
import org.animallink.identity.domain.MembershipStatus;
import org.animallink.identity.domain.MembershipType;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserRepository;
import org.animallink.identity.domain.VerificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class JdbcIdentityRepository implements UserRepository, CampusRepository,
        CampusMembershipRepository, CampusVerificationRepository {

    private static final String VERIFICATION_COLUMNS = """
            SELECT id, user_id, campus_id, requested_membership_type, applicant_name,
                   affiliation_note, expected_graduation_date, material_media_id, status,
                   review_reason, reviewed_by, reviewed_at, version, created_at, updated_at
            FROM campus_verification
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdentityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserAccount> findUserById(String id) {
        return queryOptional("""
                SELECT id, display_name, account_status, system_role, created_at, updated_at
                FROM `user` WHERE id = ?
                """, USER_ROW_MAPPER, id);
    }

    @Override
    public List<UserAccount> findUsersByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        return jdbcTemplate.query("""
                SELECT id, display_name, account_status, system_role, created_at, updated_at
                FROM `user` WHERE id IN (%s)
                """.formatted(placeholders), USER_ROW_MAPPER, ids.toArray());
    }

    @Override
    public Optional<Campus> findById(String id) {
        return queryOptional("""
                SELECT id, name, short_name, city, region, status, created_at, updated_at
                FROM campus WHERE id = ?
                """, CAMPUS_ROW_MAPPER, id);
    }

    @Override
    public Optional<Campus> findActiveById(String id) {
        return queryOptional("""
                SELECT id, name, short_name, city, region, status, created_at, updated_at
                FROM campus WHERE id = ? AND status = 'ACTIVE'
                """, CAMPUS_ROW_MAPPER, id);
    }

    @Override
    public List<Campus> searchActive(String query, int limit, int offset) {
        String normalized = query == null ? "" : query.trim();
        String pattern = "%" + normalized + "%";
        return jdbcTemplate.query("""
                SELECT id, name, short_name, city, region, status, created_at, updated_at
                FROM campus
                WHERE status = 'ACTIVE'
                  AND (? = '' OR name LIKE ? OR short_name LIKE ? OR city LIKE ?)
                ORDER BY name, id
                LIMIT ? OFFSET ?
                """, CAMPUS_ROW_MAPPER, normalized, pattern, pattern, pattern, limit, offset);
    }

    @Override
    public List<CampusMembershipView> findMembershipsByUserId(String userId) {
        return jdbcTemplate.query("""
                SELECT m.id AS m_id, m.user_id, m.campus_id, m.membership_type,
                       m.status AS m_status, m.expected_graduation_date, m.approved_at,
                       m.last_verification_id, m.created_at AS m_created_at,
                       m.updated_at AS m_updated_at,
                       c.id AS c_id, c.name, c.short_name, c.city, c.region,
                       c.status AS c_status, c.created_at AS c_created_at,
                       c.updated_at AS c_updated_at
                FROM campus_membership m
                JOIN campus c ON c.id = m.campus_id
                WHERE m.user_id = ?
                ORDER BY m.created_at DESC, m.id
                """, MEMBERSHIP_VIEW_ROW_MAPPER, userId);
    }

    @Override
    public boolean existsActiveByUserAndCampus(String userId, String campusId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM campus_membership
                WHERE user_id = ? AND campus_id = ? AND status = 'ACTIVE'
                """, Integer.class, userId, campusId);
        return count != null && count > 0;
    }

    @Override
    public Optional<CampusMembership> findByUserAndCampus(String userId, String campusId) {
        return queryOptional("""
                SELECT id, user_id, campus_id, membership_type, status,
                       expected_graduation_date, approved_at, last_verification_id,
                       created_at, updated_at
                FROM campus_membership
                WHERE user_id = ? AND campus_id = ?
                """, MEMBERSHIP_ROW_MAPPER, userId, campusId);
    }

    @Override
    public void upsertApproved(String membershipId, CampusVerification verification, Instant approvedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE campus_membership
                SET membership_type = ?,
                    status = 'ACTIVE',
                    expected_graduation_date = ?,
                    approved_at = ?,
                    last_verification_id = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE user_id = ? AND campus_id = ?
                """,
                verification.requestedMembershipType().name(),
                verification.expectedGraduationDate(),
                Timestamp.from(approvedAt),
                verification.id(),
                verification.userId(),
                verification.campusId());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO campus_membership
                        (id, user_id, campus_id, membership_type, status,
                         expected_graduation_date, approved_at, last_verification_id)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                    """,
                    membershipId,
                    verification.userId(),
                    verification.campusId(),
                    verification.requestedMembershipType().name(),
                    verification.expectedGraduationDate(),
                    Timestamp.from(approvedAt),
                    verification.id());
        }
    }

    @Override
    public void insert(CampusVerification verification) {
        jdbcTemplate.update("""
                INSERT INTO campus_verification
                    (id, user_id, campus_id, requested_membership_type, applicant_name,
                     affiliation_note, expected_graduation_date, material_media_id, status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                verification.id(),
                verification.userId(),
                verification.campusId(),
                verification.requestedMembershipType().name(),
                verification.applicantName(),
                verification.affiliationNote(),
                verification.expectedGraduationDate(),
                verification.materialMediaId(),
                verification.status().name(),
                verification.version());
    }

    @Override
    public Optional<CampusVerification> findVerificationById(String id) {
        return queryOptional(VERIFICATION_COLUMNS + " WHERE id = ?", VERIFICATION_ROW_MAPPER, id);
    }

    @Override
    public Optional<CampusVerification> findByIdForUpdate(String id) {
        return queryOptional(VERIFICATION_COLUMNS + " WHERE id = ? FOR UPDATE", VERIFICATION_ROW_MAPPER, id);
    }

    @Override
    public Optional<CampusVerification> findByIdAndUserId(String id, String userId) {
        return queryOptional(VERIFICATION_COLUMNS + " WHERE id = ? AND user_id = ?",
                VERIFICATION_ROW_MAPPER, id, userId);
    }

    @Override
    public List<CampusVerification> findVerificationsByUserId(String userId) {
        return jdbcTemplate.query(VERIFICATION_COLUMNS +
                " WHERE user_id = ? ORDER BY created_at DESC, id", VERIFICATION_ROW_MAPPER, userId);
    }

    @Override
    public List<CampusVerification> findByStatus(VerificationStatus status, int limit, int offset) {
        return jdbcTemplate.query(VERIFICATION_COLUMNS +
                        " WHERE status = ? ORDER BY created_at, id LIMIT ? OFFSET ?",
                VERIFICATION_ROW_MAPPER, status.name(), limit, offset);
    }

    @Override
    public boolean existsPendingByUserAndCampus(String userId, String campusId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM campus_verification
                WHERE user_id = ? AND campus_id = ? AND status = 'PENDING_REVIEW'
                """, Integer.class, userId, campusId);
        return count != null && count > 0;
    }

    @Override
    public int updateReview(String id, int expectedVersion, VerificationStatus status,
                            String reason, String reviewerId, Instant reviewedAt) {
        return jdbcTemplate.update("""
                UPDATE campus_verification
                SET status = ?, review_reason = ?, reviewed_by = ?, reviewed_at = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'PENDING_REVIEW' AND version = ?
                """, status.name(), reason, reviewerId, Timestamp.from(reviewedAt), id, expectedVersion);
    }

    private <T> Optional<T> queryOptional(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbcTemplate.query(sql, mapper, args);
        return rows.stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }


    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static final RowMapper<UserAccount> USER_ROW_MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getString("id"),
            rs.getString("display_name"),
            AccountStatus.valueOf(rs.getString("account_status")),
            SystemRole.valueOf(rs.getString("system_role")),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final RowMapper<Campus> CAMPUS_ROW_MAPPER = (rs, rowNum) -> new Campus(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("short_name"),
            rs.getString("city"),
            rs.getString("region"),
            CampusStatus.valueOf(rs.getString("status")),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final RowMapper<CampusVerification> VERIFICATION_ROW_MAPPER = (rs, rowNum) ->
            new CampusVerification(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("campus_id"),
                    MembershipType.valueOf(rs.getString("requested_membership_type")),
                    rs.getString("applicant_name"),
                    rs.getString("affiliation_note"),
                    localDate(rs, "expected_graduation_date"),
                    rs.getString("material_media_id"),
                    VerificationStatus.valueOf(rs.getString("status")),
                    rs.getString("review_reason"),
                    rs.getString("reviewed_by"),
                    instant(rs, "reviewed_at"),
                    rs.getInt("version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));

    private static final RowMapper<CampusMembership> MEMBERSHIP_ROW_MAPPER = (rs, rowNum) ->
            new CampusMembership(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("campus_id"),
                    MembershipType.valueOf(rs.getString("membership_type")),
                    MembershipStatus.valueOf(rs.getString("status")),
                    localDate(rs, "expected_graduation_date"),
                    instant(rs, "approved_at"),
                    rs.getString("last_verification_id"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));

    private static final RowMapper<CampusMembershipView> MEMBERSHIP_VIEW_ROW_MAPPER = (rs, rowNum) -> {
        CampusMembership membership = new CampusMembership(
                rs.getString("m_id"),
                rs.getString("user_id"),
                rs.getString("campus_id"),
                MembershipType.valueOf(rs.getString("membership_type")),
                MembershipStatus.valueOf(rs.getString("m_status")),
                localDate(rs, "expected_graduation_date"),
                instant(rs, "approved_at"),
                rs.getString("last_verification_id"),
                instant(rs, "m_created_at"),
                instant(rs, "m_updated_at"));
        Campus campus = new Campus(
                rs.getString("c_id"),
                rs.getString("name"),
                rs.getString("short_name"),
                rs.getString("city"),
                rs.getString("region"),
                CampusStatus.valueOf(rs.getString("c_status")),
                instant(rs, "c_created_at"),
                instant(rs, "c_updated_at"));
        return new CampusMembershipView(membership, campus);
    };

}
