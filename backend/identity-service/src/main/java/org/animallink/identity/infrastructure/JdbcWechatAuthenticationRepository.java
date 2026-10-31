package org.animallink.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.WechatAuthenticationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWechatAuthenticationRepository implements WechatAuthenticationRepository {
    private final JdbcTemplate jdbc;

    public JdbcWechatAuthenticationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findUserByWechatSubject(String appId, String openId) {
        return jdbc.query("""
                SELECT u.id, u.display_name, u.account_status, u.system_role, u.created_at, u.updated_at
                FROM external_identity e JOIN `user` u ON u.id = e.user_id
                WHERE e.provider = 'WECHAT_MINIPROGRAM' AND e.provider_tenant = ?
                  AND e.provider_subject = ?
                """, (rs, row) -> user(rs, row), appId, openId).stream().findFirst();
    }

    @Override
    public UserAccount createWechatUserIfAbsent(String appId, String openId, String displayName, Instant now) {
        UserAccount existing = findUserByWechatSubject(appId, openId).orElse(null);
        if (existing != null) return existing;
        String userId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO `user` (id, display_name, account_status, system_role, created_at, updated_at)
                    VALUES (?, ?, 'ACTIVE', 'USER', ?, ?)
                    """, userId, displayName, Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO external_identity
                      (id, user_id, provider, provider_tenant, provider_subject, created_at, updated_at)
                    VALUES (?, ?, 'WECHAT_MINIPROGRAM', ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), userId, appId, openId,
                    Timestamp.from(now), Timestamp.from(now));
            return new UserAccount(userId, displayName, AccountStatus.ACTIVE, SystemRole.USER, now, now);
        } catch (DuplicateKeyException concurrentLogin) {
            // The unique external-identity key is the cross-instance idempotency boundary.
            // Remove only this attempt's otherwise-unreferenced user before returning the winner.
            jdbc.update("DELETE FROM `user` WHERE id = ? AND NOT EXISTS "
                    + "(SELECT 1 FROM external_identity WHERE user_id = ?)", userId, userId);
            UserAccount winner = findUserByWechatSubject(appId, openId).orElse(null);
            if (winner != null) return winner;
            throw concurrentLogin;
        }
    }

    @Override
    public boolean insertSession(UserSession session) {
        return jdbc.update("""
                INSERT INTO wechat_user_session
                  (id, user_id, token_hash, created_at, last_seen_at, idle_expires_at, absolute_expires_at,
                   revoked_at, revoke_reason, version)
                SELECT ?, ?, UNHEX(?), ?, ?, ?, ?, ?, ?, ?
                FROM `user` WHERE id = ? AND account_status = 'ACTIVE' AND system_role = 'USER'
                """, session.id(), session.userId(), session.tokenHash(), Timestamp.from(session.createdAt()),
                Timestamp.from(session.lastSeenAt()), Timestamp.from(session.idleExpiresAt()),
                Timestamp.from(session.absoluteExpiresAt()), nullable(session.revokedAt()), session.revokeReason(),
                session.version(), session.userId()) == 1;
    }

    @Override
    public Optional<ValidatedSession> findValidatedSession(String tokenHash, Instant now) {
        return jdbc.query("""
                SELECT s.id, s.user_id, LOWER(HEX(s.token_hash)) token_hash, s.created_at, s.last_seen_at,
                       s.idle_expires_at, s.absolute_expires_at, s.revoked_at, s.revoke_reason, s.version,
                       u.display_name, u.account_status, u.system_role,
                       u.created_at user_created_at, u.updated_at user_updated_at
                FROM wechat_user_session s JOIN `user` u ON u.id = s.user_id
                WHERE s.token_hash = UNHEX(?) AND s.revoked_at IS NULL AND s.idle_expires_at > ?
                  AND s.absolute_expires_at > ? AND u.account_status = 'ACTIVE' AND u.system_role = 'USER'
                """, (rs, row) -> new ValidatedSession(session(rs), new UserAccount(rs.getString("user_id"),
                rs.getString("display_name"), AccountStatus.valueOf(rs.getString("account_status")),
                SystemRole.valueOf(rs.getString("system_role")), instant(rs, "user_created_at"),
                instant(rs, "user_updated_at"))), tokenHash, Timestamp.from(now), Timestamp.from(now))
                .stream().findFirst();
    }

    @Override
    public boolean touchIfValid(String tokenHash, Instant now, Instant proposedIdleExpiry) {
        return jdbc.update("""
                UPDATE wechat_user_session s JOIN `user` u ON u.id = s.user_id
                SET s.last_seen_at = ?, s.idle_expires_at = LEAST(?, s.absolute_expires_at),
                    s.version = s.version + 1
                WHERE s.token_hash = UNHEX(?) AND s.revoked_at IS NULL AND s.idle_expires_at > ?
                  AND s.absolute_expires_at > ? AND u.account_status = 'ACTIVE' AND u.system_role = 'USER'
                """, Timestamp.from(now), Timestamp.from(proposedIdleExpiry), tokenHash,
                Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    @Override
    public boolean revokeByTokenHash(String tokenHash, Instant revokedAt, String reason) {
        return jdbc.update("""
                UPDATE wechat_user_session SET revoked_at = ?, revoke_reason = ?, version = version + 1
                WHERE token_hash = UNHEX(?) AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), reason, tokenHash) == 1;
    }

    private static UserSession session(ResultSet rs) throws SQLException {
        return new UserSession(rs.getString("id"), rs.getString("user_id"), rs.getString("token_hash"),
                instant(rs, "created_at"), instant(rs, "last_seen_at"), instant(rs, "idle_expires_at"),
                instant(rs, "absolute_expires_at"), nullableInstant(rs, "revoked_at"),
                rs.getString("revoke_reason"), rs.getInt("version"));
    }
    private static UserAccount user(ResultSet rs, int row) throws SQLException {
        return new UserAccount(rs.getString("id"), rs.getString("display_name"),
                AccountStatus.valueOf(rs.getString("account_status")), SystemRole.valueOf(rs.getString("system_role")),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException { return rs.getTimestamp(name).toInstant(); }
    private static Instant nullableInstant(ResultSet rs, String name) throws SQLException { Timestamp v=rs.getTimestamp(name); return v == null ? null : v.toInstant(); }
    private static Timestamp nullable(Instant value) { return value == null ? null : Timestamp.from(value); }
}
