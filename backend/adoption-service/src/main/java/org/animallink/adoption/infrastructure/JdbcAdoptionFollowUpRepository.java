package org.animallink.adoption.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import org.animallink.adoption.application.AdoptionFollowUpRepository;
import org.animallink.adoption.domain.AdoptionFollowUp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdoptionFollowUpRepository implements AdoptionFollowUpRepository {
    private static final RowMapper<AdoptionFollowUp> ROW = (rs, row) -> new AdoptionFollowUp(
            rs.getString("id"), rs.getString("relation_id"), rs.getString("content"),
            rs.getTimestamp("followed_up_at").toInstant(), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;

    public JdbcAdoptionFollowUpRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void insert(AdoptionFollowUp followUp) {
        jdbc.update("INSERT INTO adoption_follow_up(id,relation_id,content,followed_up_at,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                followUp.id(), followUp.relationId(), followUp.content(), Timestamp.from(followUp.followedUpAt()), Timestamp.from(followUp.createdAt()), Timestamp.from(followUp.updatedAt()));
    }
    public List<AdoptionFollowUp> findByRelationId(String relationId, int limit, int offset) {
        return jdbc.query("SELECT * FROM adoption_follow_up WHERE relation_id=? ORDER BY followed_up_at DESC,id DESC LIMIT ? OFFSET ?", ROW, relationId, limit, offset);
    }
    public long countByRelationId(String relationId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM adoption_follow_up WHERE relation_id=?", Long.class, relationId);
        return count == null ? 0L : count;
    }
}
