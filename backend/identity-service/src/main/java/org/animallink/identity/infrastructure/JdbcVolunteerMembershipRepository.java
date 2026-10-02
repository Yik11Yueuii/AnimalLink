package org.animallink.identity.infrastructure;

import org.animallink.identity.domain.VolunteerMembership;
import org.animallink.identity.domain.VolunteerMembershipRepository;
import org.animallink.identity.domain.VolunteerMembershipStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcVolunteerMembershipRepository implements VolunteerMembershipRepository {
    private static final String COLUMNS = """
            SELECT id, user_id, campus_id, status, application_note, review_reason,
                   reviewed_by, reviewed_at, activated_at, paused_at, ended_at, version,
                   created_at, updated_at FROM volunteer_membership
            """;
    private final JdbcTemplate jdbc;
    public JdbcVolunteerMembershipRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void insert(VolunteerMembership m) {
        jdbc.update("INSERT INTO volunteer_membership (id,user_id,campus_id,status,application_note,review_reason,reviewed_by,reviewed_at,activated_at,paused_at,ended_at,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", m.id(),m.userId(),m.campusId(),m.status().name(),m.applicationNote(),m.reviewReason(),m.reviewedBy(),ts(m.reviewedAt()),ts(m.activatedAt()),ts(m.pausedAt()),ts(m.endedAt()),m.version());
    }
    @Override public Optional<VolunteerMembership> findById(String id) { return one(COLUMNS + " WHERE id=?", id); }
    @Override public Optional<VolunteerMembership> findByIdForUpdate(String id) { return one(COLUMNS + " WHERE id=? FOR UPDATE", id); }
    @Override public Optional<VolunteerMembership> findByUserAndCampus(String userId, String campusId) { return one(COLUMNS + " WHERE user_id=? AND campus_id=?", userId,campusId); }
    @Override public List<VolunteerMembership> findByUserId(String userId, int limit, int offset) { return jdbc.query(COLUMNS + " WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?", ROW, userId,limit,offset); }
    @Override public List<VolunteerMembership> findForAdmin(String campusId, VolunteerMembershipStatus status, int limit, int offset) {
        return campusId == null || campusId.isBlank()
                ? jdbc.query(COLUMNS + " WHERE status=? ORDER BY created_at,id LIMIT ? OFFSET ?", ROW,status.name(),limit,offset)
                : jdbc.query(COLUMNS + " WHERE campus_id=? AND status=? ORDER BY created_at,id LIMIT ? OFFSET ?", ROW,campusId,status.name(),limit,offset);
    }
    @Override public int transition(String id,int version,VolunteerMembershipStatus expected,VolunteerMembershipStatus next,String reason,String reviewer,Instant reviewed,Instant activated,Instant paused,Instant ended) {
        return jdbc.update("UPDATE volunteer_membership SET status=?,review_reason=COALESCE(?,review_reason),reviewed_by=COALESCE(?,reviewed_by),reviewed_at=COALESCE(?,reviewed_at),activated_at=COALESCE(?,activated_at),paused_at=COALESCE(?,paused_at),ended_at=COALESCE(?,ended_at),version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status=? AND version=?",next.name(),reason,reviewer,ts(reviewed),ts(activated),ts(paused),ts(ended),id,expected.name(),version);
    }
    private Optional<VolunteerMembership> one(String sql,Object... args) { return jdbc.query(sql,ROW,args).stream().findFirst(); }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs,String name) throws SQLException { Timestamp value=rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
    private static final RowMapper<VolunteerMembership> ROW=(rs,n)->new VolunteerMembership(rs.getString("id"),rs.getString("user_id"),rs.getString("campus_id"),VolunteerMembershipStatus.valueOf(rs.getString("status")),rs.getString("application_note"),rs.getString("review_reason"),rs.getString("reviewed_by"),instant(rs,"reviewed_at"),instant(rs,"activated_at"),instant(rs,"paused_at"),instant(rs,"ended_at"),rs.getInt("version"),instant(rs,"created_at"),instant(rs,"updated_at"));
}
