package org.animallink.identity;

import org.animallink.identity.application.AdminAuthenticationService;
import org.animallink.identity.application.AdminBootstrapService;
import org.animallink.identity.application.AdminLoginProtectionService;
import org.animallink.identity.application.AdminSessionIssuer;
import org.animallink.identity.application.AuthenticationClock;
import org.animallink.identity.application.AuthenticationSecurityMaintenanceService;
import org.animallink.identity.domain.AdminBootstrapRejectedException;
import org.animallink.identity.domain.LoginRateLimitedException;
import org.animallink.identity.domain.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("admin-bootstrap")
@Testcontainers
@ContextConfiguration(classes = {IdentityServiceApplication.class,
        AdminLoginProtectionIntegrationTest.ClockConfiguration.class})
class AdminLoginProtectionIntegrationTest {
    private static final String PASSWORD = "correct horse battery staple";
    private static final String SOURCE = "gateway:test-client-1";
    private static final Instant START = Instant.parse("2026-10-19T02:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("admin_login_protection_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("management.health.nacos.enabled", () -> "false");
        registry.add("animallink.credential-precheck.dispatcher.enabled", () -> "false");
        registry.add("animallink.minio.access-key", () -> "test-access");
        registry.add("animallink.minio.secret-key", () -> "test-secret");
        registry.add("animallink.authentication.login-protection.maximum-attempts", () -> "5");
        registry.add("animallink.authentication.login-protection.maximum-source-attempts", () -> "8");
        registry.add("animallink.authentication.login-protection.attempt-window", () -> "PT5M");
        registry.add("animallink.authentication.login-protection.block-duration", () -> "PT10M");
    }

    @Autowired AdminBootstrapService bootstrapService;
    @Autowired AdminAuthenticationService authenticationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired AuthenticationSecurityMaintenanceService maintenanceService;
    @Autowired AdminLoginProtectionService loginProtectionService;
    @Autowired AdminSessionIssuer sessionIssuer;

    @BeforeEach
    void reset() {
        clock.set(START);
        jdbc.update("DELETE FROM authentication_audit_event");
        jdbc.update("DELETE FROM admin_login_attempt_state");
        jdbc.update("DELETE FROM admin_session");
        jdbc.update("DELETE FROM admin_password_credential");
        jdbc.update("DELETE FROM external_identity");
        jdbc.update("UPDATE admin_bootstrap_control SET status='OPEN', completed_user_id=NULL, "
                + "completed_at=NULL, version=0 WHERE singleton_id=1");
        jdbc.update("DELETE FROM `user`");
    }

    @Test
    void firstAdministratorInitializationIsAtomicAndAudited() {
        var user = bootstrap("Admin.Root", PASSWORD);

        assertThat(user.systemRole().name()).isEqualTo("GOVERNANCE_ADMIN");
        assertThat(value("SELECT system_role FROM `user` WHERE id=?", user.id())).isEqualTo("GOVERNANCE_ADMIN");
        assertThat(value("SELECT provider_subject FROM external_identity WHERE user_id=?", user.id()))
                .isEqualTo("admin.root");
        assertThat(value("SELECT status FROM admin_bootstrap_control WHERE singleton_id=1"))
                .isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_INITIALIZATION_SUCCESS' AND subject_user_id=?", user.id())).isOne();
    }

    @Test
    void missingAuthorizationDuplicateAndInvalidInputAreRejectedAndAudited() {
        assertThatThrownBy(() -> bootstrapService.initialize(null, "管理员", "admin.root", PASSWORD))
                .isInstanceOf(AdminBootstrapRejectedException.class);
        assertThat(countUsers()).isZero();

        bootstrap("admin.root", PASSWORD);
        assertThatThrownBy(() -> bootstrap("second.admin", PASSWORD))
                .isInstanceOf(AdminBootstrapRejectedException.class);
        assertThatThrownBy(() -> bootstrap("INVALID USERNAME", PASSWORD))
                .isInstanceOfAny(IllegalArgumentException.class, AdminBootstrapRejectedException.class);

        assertThat(countUsers()).isOne();
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_INITIALIZATION_DENIED'" )).isEqualTo(3);
    }

    @Test
    void initializationFailureRollsBackUserIdentityAndCredentialButKeepsFailureAudit() {
        String existingUser = "00000000-0000-0000-0000-000000000711";
        String existingIdentity = "10000000-0000-0000-0000-000000000711";
        jdbc.update("INSERT INTO `user` (id,display_name,account_status,system_role) "
                + "VALUES (?,'ordinary','ACTIVE','USER')", existingUser);
        jdbc.update("INSERT INTO external_identity "
                + "(id,user_id,provider,provider_tenant,provider_subject) "
                + "VALUES (?,?,'ADMIN_PASSWORD','','admin.root')", existingIdentity, existingUser);
        jdbc.update("INSERT INTO admin_password_credential "
                + "(external_identity_id,password_hash,password_algorithm,credential_version) "
                + "VALUES (?,'$argon2id$v=19$m=19456,t=2,p=1$test$test','ARGON2ID_V1',1)", existingIdentity);

        assertThatThrownBy(() -> bootstrap("admin.root", PASSWORD)).isInstanceOf(RuntimeException.class);

        assertThat(countUsers()).isOne();
        assertThat(count("SELECT COUNT(*) FROM `user` WHERE system_role='GOVERNANCE_ADMIN'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM external_identity")).isOne();
        assertThat(value("SELECT status FROM admin_bootstrap_control WHERE singleton_id=1")).isEqualTo("OPEN");
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_INITIALIZATION_DENIED' AND outcome='FAILURE' "
                + "AND reason_code='BOOTSTRAP_TRANSACTION_FAILED'" )).isOne();
    }

    @Test
    void correctLoginCompletesOnlyItsReservationAndWritesSuccessAudit() {
        var user = bootstrap("admin.root", PASSWORD);
        assertThatThrownBy(() -> authenticationService.createAdminSession(
                "ADMIN.ROOT", "wrong password value", SOURCE)).isInstanceOf(UnauthorizedException.class);

        var session = authenticationService.createAdminSession(" admin.root ", PASSWORD, SOURCE);

        assertThat(session.userId()).isEqualTo(user.id());
        assertThat(attemptCount("USERNAME", "admin.root", "failure_count")).isOne();
        assertThat(attemptCount("USERNAME", "admin.root", "in_flight_count")).isZero();
        assertThat(singleScopeCount("SOURCE", "failure_count")).isOne();
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_LOGIN_FAILURE'" )).isOne();
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_LOGIN_SUCCESS' AND session_id=?", session.sessionId())).isOne();
    }

    @Test
    void successfulLoginDoesNotClearAnotherUsernameOrSharedSourceFailures() {
        bootstrap("admin.root", PASSWORD);
        assertUniformFailure("missing.admin", "wrong password value");

        authenticationService.createAdminSession("admin.root", PASSWORD, SOURCE);

        assertThat(attemptCount("USERNAME", "missing.admin", "failure_count")).isOne();
        assertThat(attemptCount("USERNAME", "admin.root", "failure_count")).isZero();
        assertThat(singleScopeCount("SOURCE", "failure_count")).isOne();
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();
    }

    @Test
    void sharedSourceFailuresRemainCumulativeAcrossSuccessfulLoginAndRotatedUsernames() {
        bootstrap("admin.root", PASSWORD);
        for (int i = 0; i < 4; i++) assertUniformFailure("missing.before." + i, PASSWORD);
        authenticationService.createAdminSession("admin.root", PASSWORD, SOURCE);
        for (int i = 0; i < 4; i++) assertUniformFailure("missing.after." + i, PASSWORD);

        assertThat(singleScopeCount("SOURCE", "failure_count")).isEqualTo(8);
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();
        assertThatThrownBy(() -> authenticationService.createAdminSession(
                "another.missing", PASSWORD, SOURCE)).isInstanceOf(LoginRateLimitedException.class);
    }

    @Test
    void reservedFailureSurvivesAnotherRequestsSuccessfulCompletion() {
        bootstrap("admin.root", PASSWORD);
        var pendingFailure = loginProtectionService.reserve("missing.interleaved", SOURCE);
        assertThat(pendingFailure.allowed()).isTrue();
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isOne();

        authenticationService.createAdminSession("admin.root", PASSWORD, SOURCE);
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isOne();
        assertThat(singleScopeCount("SOURCE", "failure_count")).isZero();

        loginProtectionService.recordFailure(pendingFailure);
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();
        assertThat(singleScopeCount("SOURCE", "failure_count")).isOne();
        assertThat(attemptCount("USERNAME", "missing.interleaved", "failure_count")).isOne();
    }

    @Test
    void expiredReservationCannotBlockOrAlterTheNextWindow() {
        bootstrap("admin.root", PASSWORD);
        var expired = loginProtectionService.reserve("missing.expired", SOURCE);
        assertThat(expired.allowed()).isTrue();
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isOne();

        clock.advance(Duration.ofMinutes(5));
        authenticationService.createAdminSession("admin.root", PASSWORD, SOURCE);

        assertThat(singleScopeCount("SOURCE", "failure_count")).isZero();
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();
        assertThatThrownBy(() -> loginProtectionService.recordFailure(expired))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Login-attempt reservation is missing or already completed");
        assertThat(singleScopeCount("SOURCE", "failure_count")).isZero();
    }

    @Test
    void preReservedSuccessfulAndFailedAttemptsCompleteConcurrentlyWithoutLostCounts() throws Exception {
        bootstrap("admin.root", PASSWORD);
        List<AdminLoginProtectionService.LoginAttemptContext> successful = new ArrayList<>();
        List<AdminLoginProtectionService.LoginAttemptContext> failed = new ArrayList<>();
        for (int i = 0; i < 4; i++) successful.add(loginProtectionService.reserve("admin.root", SOURCE));
        for (int i = 0; i < 4; i++) failed.add(loginProtectionService.reserve("missing.concurrent." + i, SOURCE));
        assertThat(successful).allMatch(AdminLoginProtectionService.LoginAttemptContext::allowed);
        assertThat(failed).allMatch(AdminLoginProtectionService.LoginAttemptContext::allowed);
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isEqualTo(8);

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> completions = new ArrayList<>();
            for (var context : successful) {
                completions.add(executor.submit(() -> {
                    start.await();
                    sessionIssuer.issue(context, PASSWORD);
                    return null;
                }));
            }
            for (var context : failed) {
                completions.add(executor.submit(() -> {
                    start.await();
                    assertThatThrownBy(() -> sessionIssuer.issue(context, "wrong password value"))
                            .isInstanceOf(UnauthorizedException.class);
                    loginProtectionService.recordFailure(context);
                    return null;
                }));
            }
            start.countDown();
            for (var completion : completions) completion.get();
        }

        assertThat(singleScopeCount("SOURCE", "failure_count")).isEqualTo(4);
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();
        assertThat(attemptCount("USERNAME", "admin.root", "failure_count")).isZero();
        assertThat(attemptCount("USERNAME", "admin.root", "in_flight_count")).isZero();
        assertThat(count("SELECT COUNT(*) FROM admin_session")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_LOGIN_SUCCESS'" )).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_LOGIN_FAILURE'" )).isEqualTo(4);
    }

    @Test
    void missingUsernameAndWrongPasswordHaveUniformFailureAndPersistentAudit() {
        bootstrap("admin.root", PASSWORD);

        assertUniformFailure("missing.admin", PASSWORD);
        assertUniformFailure("admin.root", "wrong password value");

        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_LOGIN_FAILURE'" )).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM admin_session")).isZero();
    }

    @Test
    void repeatedFailuresAreLimitedAndRecoverAfterExpiry() {
        bootstrap("admin.root", PASSWORD);
        for (int i = 0; i < 5; i++) assertUniformFailure("admin.root", "wrong password value");
        assertThatThrownBy(() -> authenticationService.createAdminSession(
                "admin.root", PASSWORD, SOURCE)).isInstanceOf(LoginRateLimitedException.class)
                .hasMessage("用户名或密码错误");
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_LOGIN_RATE_LIMITED'" )).isOne();
        assertThat(attemptCount("USERNAME", "admin.root", "failure_count")).isEqualTo(5);
        assertThat(singleScopeCount("SOURCE", "failure_count")).isEqualTo(5);
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();

        clock.advance(Duration.ofMinutes(10));
        assertThat(authenticationService.createAdminSession("admin.root", PASSWORD, SOURCE).rawToken())
                .hasSize(43);
    }

    @Test
    void concurrentAttemptsCannotExceedDatabaseEnforcedAllowance() throws Exception {
        bootstrap("admin.root", PASSWORD);
        try (var executor = Executors.newFixedThreadPool(12)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Class<?>>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        authenticationService.createAdminSession("admin.root", "wrong password value", SOURCE);
                        return Void.class;
                    } catch (RuntimeException failure) {
                        return failure.getClass();
                    }
                }));
            }
            start.countDown();
            int credentialFailures = 0;
            int limited = 0;
            for (var result : results) {
                Class<?> type = result.get();
                if (type == UnauthorizedException.class) credentialFailures++;
                if (type == LoginRateLimitedException.class) limited++;
            }
            assertThat(credentialFailures).isEqualTo(5);
            assertThat(limited).isEqualTo(7);
        }
        assertThat(count("SELECT COUNT(*) FROM admin_session")).isZero();
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_LOGIN_FAILURE'" )).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_LOGIN_RATE_LIMITED'" )).isEqualTo(7);
        assertThat(attemptCount("USERNAME", "admin.root", "failure_count")).isEqualTo(5);
        assertThat(attemptCount("USERNAME", "admin.root", "in_flight_count")).isZero();
        assertThat(singleScopeCount("SOURCE", "failure_count")).isEqualTo(5);
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();
    }

    @Test
    void usernameLimitAppliesAcrossDifferentTrustedSources() {
        bootstrap("admin.root", PASSWORD);
        for (int i = 0; i < 5; i++) {
            String source = SOURCE + i;
            assertThatThrownBy(() -> authenticationService.createAdminSession(
                    "admin.root", "wrong password value", source))
                    .isInstanceOf(UnauthorizedException.class)
                    .hasMessage("用户名或密码错误");
        }

        assertThatThrownBy(() -> authenticationService.createAdminSession(
                "admin.root", PASSWORD, SOURCE + "-new"))
                .isInstanceOf(LoginRateLimitedException.class)
                .hasMessage("用户名或密码错误");
        assertThat(attemptCount("USERNAME", "admin.root", "failure_count")).isEqualTo(5);
        assertThat(attemptCount("USERNAME", "admin.root", "in_flight_count")).isZero();
    }

    @Test
    void rotatingUnknownUsernamesCannotBypassTrustedSourceLimit() {
        bootstrap("admin.root", PASSWORD);
        for (int i = 0; i < 8; i++) {
            assertUniformFailure("missing." + i, PASSWORD);
        }
        assertThatThrownBy(() -> authenticationService.createAdminSession(
                "another.missing", PASSWORD, SOURCE)).isInstanceOf(LoginRateLimitedException.class);
        assertThat(singleScopeCount("SOURCE", "failure_count")).isEqualTo(8);
        assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='ADMIN_LOGIN_RATE_LIMITED'" )).isOne();
    }

    @Test
    void auditRowsContainNoPasswordTokenOrPasswordHash() {
        bootstrap("admin.root", PASSWORD);
        var session = authenticationService.createAdminSession("admin.root", PASSWORD, SOURCE);
        authenticationService.revokeCurrentSession(session.rawToken(), "USER_LOGOUT");
        authenticationService.revokeAllSessions(session.userId(), "LOGOUT_ALL");

        List<String> auditValues = jdbc.query("SELECT CONCAT_WS('|', event_type, outcome, "
                        + "COALESCE(normalized_username,''), actor_type, COALESCE(actor_reference,''), "
                        + "COALESCE(source_fingerprint,''), reason_code, COALESCE(session_id,'')) "
                        + "FROM authentication_audit_event",
                (rs, rowNum) -> rs.getString(1));
        assertThat(auditValues).allSatisfy(value -> {
            assertThat(value).doesNotContain(PASSWORD).doesNotContain(session.rawToken()).doesNotContain("$argon2id$");
        });
        assertThat(auditValues).anyMatch(value -> value.startsWith("SESSION_REVOKED|SUCCESS"));
        assertThat(auditValues).anyMatch(value -> value.startsWith("ALL_SESSIONS_REVOKED|SUCCESS"));
    }

    @Test
    void auditWriteFailureRollsBackSessionIssueAndRevocation() {
        var user = bootstrap("admin.root", PASSWORD);
        jdbc.execute("ALTER TABLE authentication_audit_event ADD CONSTRAINT chk_reject_auth_audit "
                + "CHECK (event_type <> 'ADMIN_LOGIN_SUCCESS')");
        try {
            assertThatThrownBy(() -> authenticationService.createAdminSession(
                    "admin.root", PASSWORD, SOURCE)).isInstanceOf(RuntimeException.class);
            assertThat(count("SELECT COUNT(*) FROM admin_session")).isZero();
            assertThat(singleScopeCount("SOURCE", "failure_count")).isZero();
            assertThat(singleScopeCount("SOURCE", "in_flight_count")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE authentication_audit_event DROP CHECK chk_reject_auth_audit");
        }

        var session = authenticationService.createAdminSession("admin.root", PASSWORD, SOURCE);
        jdbc.execute("ALTER TABLE authentication_audit_event ADD CONSTRAINT chk_reject_auth_audit "
                + "CHECK (event_type <> 'SESSION_REVOKED')");
        try {
            assertThatThrownBy(() -> authenticationService.revokeCurrentSession(
                    session.rawToken(), "USER_LOGOUT")).isInstanceOf(RuntimeException.class);
            assertThat(authenticationService.validateAdminSession(session.rawToken()).user().id())
                    .isEqualTo(user.id());
        } finally {
            jdbc.execute("ALTER TABLE authentication_audit_event DROP CHECK chk_reject_auth_audit");
        }
    }

    @Test
    void expiredAttemptAndAuditRecordsArePurgedByConfiguredRetention() {
        bootstrap("admin.root", PASSWORD);
        assertUniformFailure("admin.root", "wrong password value");
        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event")).isPositive();
        assertThat(count("SELECT COUNT(*) FROM admin_login_attempt_state")).isPositive();

        clock.advance(Duration.ofDays(180));
        maintenanceService.purgeExpiredSecurityRecords();

        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event")).isPositive();
        assertThat(count("SELECT COUNT(*) FROM admin_login_attempt_state")).isZero();

        clock.advance(Duration.ofMillis(1));
        maintenanceService.purgeExpiredSecurityRecords();

        assertThat(count("SELECT COUNT(*) FROM authentication_audit_event")).isZero();
    }

    private org.animallink.identity.domain.UserAccount bootstrap(String username, String password) {
        return bootstrapService.initialize(
                AdminBootstrapService.LocalBootstrapAuthorization.confirmedInteractive("local:test-operator"),
                "治理管理员", username, password);
    }

    private void assertUniformFailure(String username, String password) {
        assertThatThrownBy(() -> authenticationService.createAdminSession(username, password, SOURCE))
                .isInstanceOf(UnauthorizedException.class).hasMessage("用户名或密码错误");
    }

    private int countUsers() {
        return count("SELECT COUNT(*) FROM `user`");
    }

    private int attemptCount(String scopeType, String normalizedUsername, String column) {
        return count("SELECT " + column + " FROM admin_login_attempt_state "
                + "WHERE scope_type=? AND normalized_username=?", scopeType, normalizedUsername);
    }

    private int singleScopeCount(String scopeType, String column) {
        return count("SELECT " + column + " FROM admin_login_attempt_state WHERE scope_type=?", scopeType);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String value(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }

    static class MutableClock implements AuthenticationClock {
        private Instant value = START;
        synchronized void set(Instant value) { this.value = value; }
        synchronized void advance(Duration duration) { value = value.plus(duration); }
        @Override public synchronized Instant now() { return value; }
    }
}
