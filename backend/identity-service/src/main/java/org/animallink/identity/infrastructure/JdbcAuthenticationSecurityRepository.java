package org.animallink.identity.infrastructure;

import org.animallink.identity.domain.AuthenticationSecurityRepository;
import org.animallink.identity.domain.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Repository
public class JdbcAuthenticationSecurityRepository implements AuthenticationSecurityRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthenticationSecurityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LoginAttemptDecision reserveLoginAttempt(String usernameKey, String normalizedUsername,
                                                    String sourceFingerprint, Instant now,
                                                    Duration attemptWindow, Duration blockDuration,
                                                    int maximumUsernameAttempts,
                                                    int maximumSourceAttempts) {
        ScopeReservation usernameReservation = reserveScope("USERNAME", usernameKey,
                normalizedUsername, sourceFingerprint, now, attemptWindow, blockDuration,
                maximumUsernameAttempts);
        if (!usernameReservation.allowed()) {
            return denied(usernameReservation.blockedUntil());
        }
        ScopeReservation sourceReservation = reserveScope("SOURCE", sourceFingerprint,
                null, sourceFingerprint, now, attemptWindow, blockDuration, maximumSourceAttempts);
        if (!sourceReservation.allowed()) {
            completeScope("USERNAME", usernameKey, usernameReservation.windowStartedAt(), false, now);
            return denied(sourceReservation.blockedUntil());
        }
        return new LoginAttemptDecision(true, null, usernameReservation.windowStartedAt(),
                sourceReservation.windowStartedAt());
    }

    private ScopeReservation reserveScope(String scopeType, String scopeKey, String normalizedUsername,
                                          String sourceFingerprint, Instant now,
                                          Duration attemptWindow, Duration blockDuration,
                                          int maximumAttempts) {
        jdbcTemplate.update("""
                INSERT INTO admin_login_attempt_state
                    (scope_type, scope_key, normalized_username, failure_count, in_flight_count, window_started_at,
                     blocked_until, last_source_fingerprint, updated_at, version)
                VALUES (?, UNHEX(?), ?, 0, 0, ?, NULL, UNHEX(?), ?, 0)
                ON DUPLICATE KEY UPDATE scope_key = scope_key
                """, scopeType, scopeKey, normalizedUsername, Timestamp.from(now),
                sourceFingerprint, Timestamp.from(now));

        AttemptState state = jdbcTemplate.queryForObject("""
                SELECT failure_count, in_flight_count, window_started_at, blocked_until, version
                FROM admin_login_attempt_state
                WHERE scope_type = ? AND scope_key = UNHEX(?)
                FOR UPDATE
                """, (rs, rowNum) -> new AttemptState(rs.getInt("failure_count"),
                rs.getInt("in_flight_count"),
                rs.getTimestamp("window_started_at").toInstant(),
                rs.getTimestamp("blocked_until") == null ? null : rs.getTimestamp("blocked_until").toInstant(),
                rs.getInt("version")), scopeType, scopeKey);
        if (state == null) throw new IllegalStateException("Login attempt state was not created");

        if (state.blockedUntil() != null && state.blockedUntil().isAfter(now)) {
            touchSource(scopeType, scopeKey, sourceFingerprint, now);
            return new ScopeReservation(false, state.blockedUntil(), state.windowStartedAt());
        }

        boolean newWindow = !state.windowStartedAt().plus(attemptWindow).isAfter(now);
        int failureCount = newWindow ? 0 : state.failureCount();
        int inFlightCount = newWindow ? 0 : state.inFlightCount();
        Instant windowStartedAt = newWindow ? now : state.windowStartedAt();
        if (failureCount >= maximumAttempts) {
            Instant blockedUntil = now.plus(blockDuration);
            updateScope(scopeType, scopeKey, normalizedUsername, failureCount, inFlightCount,
                    windowStartedAt, blockedUntil, sourceFingerprint, now, state.version());
            return new ScopeReservation(false, blockedUntil, windowStartedAt);
        }
        if (failureCount + inFlightCount >= maximumAttempts) {
            updateScope(scopeType, scopeKey, normalizedUsername, failureCount, inFlightCount,
                    windowStartedAt, null, sourceFingerprint, now, state.version());
            return new ScopeReservation(false, null, windowStartedAt);
        }
        updateScope(scopeType, scopeKey, normalizedUsername, failureCount, inFlightCount + 1,
                windowStartedAt, null, sourceFingerprint, now, state.version());
        return new ScopeReservation(true, null, windowStartedAt);
    }

    private void updateScope(String scopeType, String scopeKey, String normalizedUsername,
                             int failureCount, int inFlightCount, Instant windowStartedAt,
                             Instant blockedUntil, String sourceFingerprint, Instant now,
                             int expectedVersion) {
        int changed = jdbcTemplate.update("""
                UPDATE admin_login_attempt_state
                SET normalized_username = ?, failure_count = ?, in_flight_count = ?, window_started_at = ?,
                    blocked_until = ?, last_source_fingerprint = UNHEX(?), updated_at = ?,
                    version = version + 1
                WHERE scope_type = ? AND scope_key = UNHEX(?) AND version = ?
                """, normalizedUsername, failureCount, inFlightCount, Timestamp.from(windowStartedAt),
                nullableTimestamp(blockedUntil), sourceFingerprint, Timestamp.from(now),
                scopeType, scopeKey, expectedVersion);
        if (changed != 1) throw new IllegalStateException("Concurrent login-attempt update was not serialized");
    }

    @Override
    public void completeLoginAttempt(String usernameKey, Instant usernameWindowStartedAt,
                                     String sourceFingerprint, Instant sourceWindowStartedAt,
                                     boolean authenticationFailed, Instant now) {
        completeScope("USERNAME", usernameKey, usernameWindowStartedAt, authenticationFailed, now);
        completeScope("SOURCE", sourceFingerprint, sourceWindowStartedAt, authenticationFailed, now);
    }

    private void completeScope(String scopeType, String scopeKey, Instant expectedWindowStartedAt,
                               boolean authenticationFailed, Instant now) {
        int changed = jdbcTemplate.update("""
                UPDATE admin_login_attempt_state
                SET failure_count = failure_count + ?, in_flight_count = in_flight_count - 1,
                    updated_at = ?, version = version + 1
                WHERE scope_type = ? AND scope_key = UNHEX(?) AND window_started_at = ?
                  AND in_flight_count > 0
                """, authenticationFailed ? 1 : 0, Timestamp.from(now), scopeType, scopeKey,
                Timestamp.from(expectedWindowStartedAt));
        if (changed != 1) {
            throw new IllegalStateException("Login-attempt reservation is missing or already completed");
        }
    }

    @Override
    public void insertAuditEvent(AuthenticationAuditEvent event) {
        jdbcTemplate.update("""
                INSERT INTO authentication_audit_event
                    (id, event_type, outcome, subject_user_id, normalized_username,
                     actor_type, actor_reference, source_fingerprint, reason_code,
                     session_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.eventType().name(), event.outcome().name(), event.subjectUserId(),
                event.normalizedUsername(), event.actorType().name(), event.actorReference(),
                event.sourceFingerprint(), event.reasonCode(), event.sessionId(), Timestamp.from(event.createdAt()));
    }

    @Override
    public BootstrapState lockBootstrapState() {
        BootstrapState state = jdbcTemplate.queryForObject("""
                SELECT status, completed_user_id, completed_at, version
                FROM admin_bootstrap_control WHERE singleton_id = 1 FOR UPDATE
                """, (rs, rowNum) -> new BootstrapState(rs.getString("status"),
                rs.getString("completed_user_id"),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getInt("version")));
        if (state == null) throw new IllegalStateException("Administrator bootstrap control is missing");
        return state;
    }

    @Override
    public long countGovernanceAdministrators() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE system_role = 'GOVERNANCE_ADMIN'", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void insertGovernanceAdministrator(UserAccount user) {
        jdbcTemplate.update("""
                INSERT INTO `user` (id, display_name, account_status, system_role, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, user.id(), user.displayName(), user.accountStatus().name(), user.systemRole().name(),
                Timestamp.from(user.createdAt()), Timestamp.from(user.updatedAt()));
    }

    @Override
    public boolean completeBootstrap(String userId, Instant completedAt, int expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE admin_bootstrap_control
                SET status = 'COMPLETED', completed_user_id = ?, completed_at = ?, version = version + 1
                WHERE singleton_id = 1 AND status = 'OPEN' AND version = ?
                """, userId, Timestamp.from(completedAt), expectedVersion) == 1;
    }

    @Override
    public int deleteAuditEventsBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                DELETE FROM authentication_audit_event
                WHERE created_at < ?
                ORDER BY created_at, id
                LIMIT ?
                """, Timestamp.from(cutoff), limit);
    }

    @Override
    public int deleteLoginAttemptStatesBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                DELETE FROM admin_login_attempt_state
                WHERE updated_at < ? AND (blocked_until IS NULL OR blocked_until < ?)
                ORDER BY updated_at
                LIMIT ?
                """, Timestamp.from(cutoff), Timestamp.from(cutoff), limit);
    }

    private void touchSource(String scopeType, String scopeKey, String sourceFingerprint, Instant now) {
        jdbcTemplate.update("""
                UPDATE admin_login_attempt_state
                SET last_source_fingerprint = UNHEX(?), updated_at = ?, version = version + 1
                WHERE scope_type = ? AND scope_key = UNHEX(?)
                """, sourceFingerprint, Timestamp.from(now), scopeType, scopeKey);
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static LoginAttemptDecision denied(Instant blockedUntil) {
        return new LoginAttemptDecision(false, blockedUntil, null, null);
    }

    private record AttemptState(int failureCount, int inFlightCount, Instant windowStartedAt,
                                Instant blockedUntil, int version) {
    }

    private record ScopeReservation(boolean allowed, Instant blockedUntil, Instant windowStartedAt) {
    }
}
