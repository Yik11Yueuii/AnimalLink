package org.animallink.identity.infrastructure;

import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.AdminAuthenticationRepository;
import org.animallink.identity.domain.AdminSessionClientType;
import org.animallink.identity.domain.ExternalIdentityProvider;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcAdminAuthenticationRepository implements AdminAuthenticationRepository {
    private static final String CREDENTIAL_SELECT = """
            SELECT e.id AS identity_id, e.user_id, e.provider, e.provider_tenant,
                   e.provider_subject, e.created_at AS identity_created_at,
                   e.updated_at AS identity_updated_at, c.password_hash,
                   c.password_algorithm, c.credential_version,
                   c.created_at AS credential_created_at, c.updated_at AS credential_updated_at
            FROM external_identity e
            JOIN admin_password_credential c ON c.external_identity_id = e.id
            WHERE e.provider = 'ADMIN_PASSWORD'
            """;
    private static final String SESSION_SELECT = """
            SELECT s.id, s.user_id, s.client_type, LOWER(HEX(s.token_hash)) AS token_hash,
                   s.credential_version, s.created_at, s.last_seen_at, s.idle_expires_at,
                   s.absolute_expires_at, s.revoked_at, s.revoke_reason, s.version
            FROM admin_session s
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAdminAuthenticationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AdminCredentialRecord> findCredentialByUsername(String normalizedUsername) {
        return jdbcTemplate.query(CREDENTIAL_SELECT + " AND e.provider_tenant = '' AND e.provider_subject = ?",
                CREDENTIAL_MAPPER, normalizedUsername).stream().findFirst();
    }

    @Override
    public Optional<AdminCredentialRecord> findCredentialByUserId(String userId) {
        return jdbcTemplate.query(CREDENTIAL_SELECT + " AND e.provider_tenant = '' AND e.user_id = ?",
                CREDENTIAL_MAPPER, userId).stream().findFirst();
    }

    @Override
    public void insertCredential(ExternalIdentityRecord identity, String passwordHash,
                                 String passwordAlgorithm, int credentialVersion) {
        jdbcTemplate.update("""
                INSERT INTO external_identity
                    (id, user_id, provider, provider_tenant, provider_subject, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, identity.id(), identity.userId(), identity.provider().name(),
                identity.providerTenant(), identity.providerSubject(), Timestamp.from(identity.createdAt()),
                Timestamp.from(identity.updatedAt()));
        jdbcTemplate.update("""
                INSERT INTO admin_password_credential
                    (external_identity_id, password_hash, password_algorithm, credential_version,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, identity.id(), passwordHash, passwordAlgorithm, credentialVersion,
                Timestamp.from(identity.createdAt()), Timestamp.from(identity.updatedAt()));
    }

    @Override
    public int upgradeCredential(String externalIdentityId, int expectedVersion,
                                 String passwordHash, String passwordAlgorithm) {
        return jdbcTemplate.update("""
                UPDATE admin_password_credential
                SET password_hash = ?, password_algorithm = ?,
                    credential_version = credential_version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE external_identity_id = ? AND credential_version = ?
                """, passwordHash, passwordAlgorithm, externalIdentityId, expectedVersion);
    }

    @Override
    public boolean insertSessionIfEligible(AdminSessionRecord session) {
        return jdbcTemplate.update("""
                INSERT INTO admin_session
                    (id, user_id, client_type, token_hash, credential_version, created_at,
                     last_seen_at, idle_expires_at, absolute_expires_at, revoked_at,
                     revoke_reason, version)
                SELECT ?, ?, ?, UNHEX(?), ?, ?, ?, ?, ?, ?, ?, ?
                FROM `user` u
                JOIN external_identity e
                  ON e.user_id = u.id AND e.provider = 'ADMIN_PASSWORD' AND e.provider_tenant = ''
                JOIN admin_password_credential c ON c.external_identity_id = e.id
                WHERE u.id = ? AND u.account_status = 'ACTIVE'
                  AND u.system_role = 'GOVERNANCE_ADMIN' AND c.credential_version = ?
                """, session.id(), session.userId(), session.clientType().name(), session.tokenHash(),
                session.credentialVersion(), Timestamp.from(session.createdAt()),
                Timestamp.from(session.lastSeenAt()), Timestamp.from(session.idleExpiresAt()),
                Timestamp.from(session.absoluteExpiresAt()), nullableTimestamp(session.revokedAt()),
                session.revokeReason(), session.version(), session.userId(), session.credentialVersion()) == 1;
    }

    @Override
    public Optional<AdminSessionRecord> findSessionByTokenHash(String tokenHash) {
        return jdbcTemplate.query(SESSION_SELECT + " WHERE s.token_hash = UNHEX(?)", SESSION_MAPPER, tokenHash)
                .stream().findFirst();
    }

    @Override
    public boolean touchIfValidAdmin(String tokenHash, Instant now, Instant proposedIdleExpiry) {
        return jdbcTemplate.update("""
                UPDATE admin_session s
                JOIN `user` u ON u.id = s.user_id
                JOIN external_identity e
                  ON e.user_id = s.user_id AND e.provider = 'ADMIN_PASSWORD' AND e.provider_tenant = ''
                JOIN admin_password_credential c
                  ON c.external_identity_id = e.id AND c.credential_version = s.credential_version
                SET s.last_seen_at = ?,
                    s.idle_expires_at = LEAST(?, s.absolute_expires_at),
                    s.version = s.version + 1
                WHERE s.token_hash = UNHEX(?)
                  AND s.revoked_at IS NULL
                  AND s.idle_expires_at > ?
                  AND s.absolute_expires_at > ?
                  AND u.account_status = 'ACTIVE'
                  AND u.system_role = 'GOVERNANCE_ADMIN'
                """, Timestamp.from(now), Timestamp.from(proposedIdleExpiry), tokenHash,
                Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    @Override
    public Optional<ValidatedAdminSession> findValidatedSession(String tokenHash, Instant now) {
        return jdbcTemplate.query("""
                SELECT s.id, s.user_id, s.client_type, LOWER(HEX(s.token_hash)) AS token_hash,
                       s.credential_version, s.created_at, s.last_seen_at, s.idle_expires_at,
                       s.absolute_expires_at, s.revoked_at, s.revoke_reason, s.version,
                       u.display_name, u.account_status, u.system_role,
                       u.created_at AS user_created_at, u.updated_at AS user_updated_at
                FROM admin_session s
                JOIN `user` u ON u.id = s.user_id
                JOIN external_identity e
                  ON e.user_id = s.user_id AND e.provider = 'ADMIN_PASSWORD' AND e.provider_tenant = ''
                JOIN admin_password_credential c
                  ON c.external_identity_id = e.id AND c.credential_version = s.credential_version
                WHERE s.token_hash = UNHEX(?) AND s.revoked_at IS NULL
                  AND s.idle_expires_at > ? AND s.absolute_expires_at > ?
                  AND u.account_status = 'ACTIVE' AND u.system_role = 'GOVERNANCE_ADMIN'
                """, (rs, rowNum) -> new ValidatedAdminSession(mapSession(rs), new UserAccount(
                        rs.getString("user_id"), rs.getString("display_name"),
                        AccountStatus.valueOf(rs.getString("account_status")),
                        SystemRole.valueOf(rs.getString("system_role")),
                        instant(rs, "user_created_at"), instant(rs, "user_updated_at"))),
                tokenHash, Timestamp.from(now), Timestamp.from(now)).stream().findFirst();
    }

    @Override
    public boolean revokeByTokenHash(String tokenHash, Instant revokedAt, String reason) {
        return jdbcTemplate.update("""
                UPDATE admin_session
                SET revoked_at = ?, revoke_reason = ?, version = version + 1
                WHERE token_hash = UNHEX(?) AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), reason, tokenHash) == 1;
    }

    @Override
    public int revokeAllByUserId(String userId, Instant revokedAt, String reason) {
        return jdbcTemplate.update("""
                UPDATE admin_session
                SET revoked_at = ?, revoke_reason = ?, version = version + 1
                WHERE user_id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), reason, userId);
    }

    private static final RowMapper<AdminCredentialRecord> CREDENTIAL_MAPPER = (rs, rowNum) -> {
        var identity = new ExternalIdentityRecord(rs.getString("identity_id"), rs.getString("user_id"),
                ExternalIdentityProvider.valueOf(rs.getString("provider")),
                rs.getString("provider_tenant"), rs.getString("provider_subject"),
                instant(rs, "identity_created_at"), instant(rs, "identity_updated_at"));
        return new AdminCredentialRecord(identity, rs.getString("password_hash"),
                rs.getString("password_algorithm"), rs.getInt("credential_version"),
                instant(rs, "credential_created_at"), instant(rs, "credential_updated_at"));
    };

    private static final RowMapper<AdminSessionRecord> SESSION_MAPPER =
            (rs, rowNum) -> mapSession(rs);

    private static AdminSessionRecord mapSession(ResultSet rs) throws SQLException {
        return new AdminSessionRecord(rs.getString("id"), rs.getString("user_id"),
                AdminSessionClientType.valueOf(rs.getString("client_type")), rs.getString("token_hash"),
                rs.getInt("credential_version"), instant(rs, "created_at"), instant(rs, "last_seen_at"),
                instant(rs, "idle_expires_at"), instant(rs, "absolute_expires_at"),
                nullableInstant(rs, "revoked_at"), rs.getString("revoke_reason"), rs.getInt("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
