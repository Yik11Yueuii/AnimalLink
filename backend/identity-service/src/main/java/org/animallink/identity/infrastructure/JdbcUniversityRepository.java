package org.animallink.identity.infrastructure;

import org.animallink.identity.domain.University;
import org.animallink.identity.domain.UniversityRepository;
import org.animallink.identity.domain.UniversityStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcUniversityRepository implements UniversityRepository {
    private static final String PUBLIC_COLUMNS = """
            SELECT u.id, u.moe_institution_code, u.official_name, u.short_name,
                   u.province, u.city, u.status, u.source_name, u.source_url,
                   u.source_updated_at, u.created_at, u.updated_at,
                   EXISTS(
                       SELECT 1 FROM campus c
                       WHERE c.university_id = u.id
                         AND c.campus_type = 'UNIVERSITY_COMMUNITY'
                         AND c.status = 'ACTIVE'
                   ) AS community_available
            FROM university u
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUniversityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<University> findActiveById(String universityId) {
        List<University> matches = jdbcTemplate.query(PUBLIC_COLUMNS +
                " WHERE u.id = ? AND u.status = 'ACTIVE'", ROW_MAPPER, universityId);
        return matches.stream().findFirst();
    }

    @Override
    public List<University> searchActive(String province, String query, int limit, int offset) {
        String normalizedProvince = normalize(province);
        String normalizedQuery = normalize(query);
        String pattern = "%" + normalizedQuery + "%";
        return jdbcTemplate.query(PUBLIC_COLUMNS + """
                WHERE u.status = 'ACTIVE'
                  AND (? = '' OR u.province = ?)
                  AND (? = '' OR u.official_name LIKE ? OR u.short_name LIKE ?
                       OR u.city LIKE ? OR u.moe_institution_code = ?)
                ORDER BY u.province, u.city, u.official_name, u.id
                LIMIT ? OFFSET ?
                """, ROW_MAPPER, normalizedProvince, normalizedProvince,
                normalizedQuery, pattern, pattern, pattern, normalizedQuery, limit, offset);
    }

    @Override
    public long countActive(String province, String query) {
        String normalizedProvince = normalize(province);
        String normalizedQuery = normalize(query);
        String pattern = "%" + normalizedQuery + "%";
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM university u
                WHERE u.status = 'ACTIVE'
                  AND (? = '' OR u.province = ?)
                  AND (? = '' OR u.official_name LIKE ? OR u.short_name LIKE ?
                       OR u.city LIKE ? OR u.moe_institution_code = ?)
                """, Long.class, normalizedProvince, normalizedProvince,
                normalizedQuery, pattern, pattern, pattern, normalizedQuery);
        return count == null ? 0L : count;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<University> ROW_MAPPER = (rs, rowNum) -> new University(
            rs.getString("id"),
            rs.getString("moe_institution_code"),
            rs.getString("official_name"),
            rs.getString("short_name"),
            rs.getString("province"),
            rs.getString("city"),
            UniversityStatus.valueOf(rs.getString("status")),
            rs.getString("source_name"),
            rs.getString("source_url"),
            rs.getDate("source_updated_at").toLocalDate(),
            rs.getBoolean("community_available"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));
}
