package org.animallink.identity.infrastructure;

import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserGovernanceAction;
import org.animallink.identity.domain.UserGovernanceAudit;
import org.animallink.identity.domain.UserGovernanceRepository;
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
public class JdbcUserGovernanceRepository implements UserGovernanceRepository {
    private static final String USER_COLUMNS = """
            SELECT id, display_name, account_status, system_role, created_at, updated_at
            FROM `user`
            """;
    private static final String AUDIT_COLUMNS = """
            SELECT id, user_id, action, from_status, to_status, actor_user_id, reason, created_at
            FROM user_governance_audit
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserGovernanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserAccount> findUserByIdForUpdate(String userId) {
        return jdbcTemplate.query(USER_COLUMNS + " WHERE id = ? FOR UPDATE", USER_MAPPER, userId)
                .stream().findFirst();
    }

    @Override
    public int transitionStatus(String userId, AccountStatus expected, AccountStatus next) {
        return jdbcTemplate.update("""
                UPDATE `user`
                SET account_status = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND account_status = ?
                """, next.name(), userId, expected.name());
    }

    @Override
    public void insertAudit(UserGovernanceAudit audit) {
        jdbcTemplate.update("""
                INSERT INTO user_governance_audit
                    (id, user_id, action, from_status, to_status, actor_user_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, audit.id(), audit.userId(), audit.action().name(), audit.fromStatus().name(),
                audit.toStatus().name(), audit.actorUserId(), audit.reason(),
                Timestamp.from(audit.createdAt()));
    }

    @Override
    public List<UserGovernanceAudit> findAudits(String userId, int limit, int offset) {
        return jdbcTemplate.query(AUDIT_COLUMNS + """
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, AUDIT_MAPPER, userId, limit, offset);
    }

    @Override
    public long countAudits(String userId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_governance_audit WHERE user_id = ?", Long.class, userId);
        return value == null ? 0 : value;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static final RowMapper<UserAccount> USER_MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getString("id"), rs.getString("display_name"),
            AccountStatus.valueOf(rs.getString("account_status")),
            SystemRole.valueOf(rs.getString("system_role")),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<UserGovernanceAudit> AUDIT_MAPPER = (rs, rowNum) ->
            new UserGovernanceAudit(rs.getString("id"), rs.getString("user_id"),
                    UserGovernanceAction.valueOf(rs.getString("action")),
                    AccountStatus.valueOf(rs.getString("from_status")),
                    AccountStatus.valueOf(rs.getString("to_status")),
                    rs.getString("actor_user_id"), rs.getString("reason"),
                    instant(rs, "created_at"));
}
