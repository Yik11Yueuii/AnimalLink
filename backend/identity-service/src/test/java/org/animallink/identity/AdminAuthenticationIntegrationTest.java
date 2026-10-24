package org.animallink.identity;

import io.minio.MinioClient;
import org.animallink.identity.application.AdminAuthenticationService;
import org.animallink.identity.application.AdminBootstrapService;
import org.animallink.identity.application.AuthenticationClock;
import org.animallink.identity.application.OpaqueSessionTokenCodec;
import org.animallink.identity.domain.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@ContextConfiguration(classes = {IdentityServiceApplication.class,
        AdminAuthenticationIntegrationTest.ClockTestConfiguration.class})
class AdminAuthenticationIntegrationTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000901";
    private static final String ORDINARY = "00000000-0000-0000-0000-000000000902";
    private static final String DISABLED = "00000000-0000-0000-0000-000000000903";
    private static final String PASSWORD = "correct horse battery staple";
    private static final Instant START = Instant.parse("2026-10-19T00:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("admin_authentication_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("animallink.authentication.session.idle-timeout", () -> "PT30M");
        registry.add("animallink.authentication.session.absolute-timeout", () -> "PT8H");
        registry.add("animallink.authentication.login-protection.maximum-attempts", () -> "100");
        registry.add("animallink.authentication.login-protection.maximum-source-attempts", () -> "100");
    }

    @Autowired AdminAuthenticationService service;
    @Autowired OpaqueSessionTokenCodec tokenCodec;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableAuthenticationClock clock;
    @Autowired ApplicationContext applicationContext;

    @BeforeEach
    void reset() {
        clock.set(START);
        jdbc.update("DELETE FROM admin_session");
        jdbc.update("DELETE FROM admin_password_credential");
        jdbc.update("DELETE FROM external_identity");
        jdbc.update("DELETE FROM `user`");
        user(ADMIN, "管理员", "ACTIVE", "GOVERNANCE_ADMIN");
        user(ORDINARY, "普通用户", "ACTIVE", "GOVERNANCE_ADMIN");
        user(DISABLED, "禁用管理员", "ACTIVE", "GOVERNANCE_ADMIN");
        service.provisionAdminCredential(ADMIN, "Admin.Root", PASSWORD);
        service.provisionAdminCredential(ORDINARY, "ordinary", PASSWORD);
        service.provisionAdminCredential(DISABLED, "disabled", PASSWORD);
        jdbc.update("UPDATE `user` SET system_role='USER' WHERE id=?", ORDINARY);
        jdbc.update("UPDATE `user` SET account_status='DISABLED' WHERE id=?", DISABLED);
    }

    @Test
    void correctPasswordCreatesRandomOpaqueSessionAndPersistsOnlyHash() {
        var first = service.createAdminSession(" ADMIN.ROOT ", PASSWORD);
        var second = service.createAdminSession("admin.root", PASSWORD);

        assertThat(first.rawToken()).hasSize(43).isNotEqualTo(second.rawToken());
        assertThat(new HashSet<>(List.of(first.rawToken(), second.rawToken()))).hasSize(2);
        assertThat(service.validateAdminSession(first.rawToken()).user().id()).isEqualTo(ADMIN);
        String storedHash = jdbc.queryForObject(
                "SELECT LOWER(HEX(token_hash)) FROM admin_session WHERE id=?", String.class, first.sessionId());
        assertThat(storedHash).isEqualTo(tokenCodec.hash(first.rawToken())).doesNotContain(first.rawToken());
        assertThat(jdbc.queryForObject("SELECT password_hash FROM admin_password_credential c "
                + "JOIN external_identity e ON e.id=c.external_identity_id WHERE e.user_id=?",
                String.class, ADMIN)).startsWith("$argon2id$").isNotEqualTo(PASSWORD);
    }

    @Test
    void wrongPasswordAndUnknownUsernameDoNotCreateSessions() {
        assertThatThrownBy(() -> service.createAdminSession("admin.root", "wrong password value"))
                .isInstanceOf(UnauthorizedException.class).hasMessage("用户名或密码错误");
        assertThatThrownBy(() -> service.createAdminSession("missing.user", PASSWORD))
                .isInstanceOf(UnauthorizedException.class).hasMessage("用户名或密码错误");
        assertThat(countSessions()).isZero();
    }

    @Test
    void ordinaryAndDisabledUsersCannotCreateAdminSessions() {
        assertThatThrownBy(() -> service.createAdminSession("ordinary", PASSWORD))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.createAdminSession("disabled", PASSWORD))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(countSessions()).isZero();
    }

    @Test
    void idleAndAbsoluteExpiryAreEnforced() {
        var idle = service.createAdminSession("admin.root", PASSWORD);
        clock.advance(Duration.ofMinutes(30));
        assertThatThrownBy(() -> service.validateAdminSession(idle.rawToken()))
                .isInstanceOf(UnauthorizedException.class);

        clock.set(START);
        var absolute = service.createAdminSession("admin.root", PASSWORD);
        for (int i = 0; i < 23; i++) {
            clock.advance(Duration.ofMinutes(20));
            service.validateAdminSession(absolute.rawToken());
        }
        clock.set(START.plus(Duration.ofHours(8)));
        assertThatThrownBy(() -> service.validateAdminSession(absolute.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void currentAndAllSessionRevocationAreImmediateAndIdempotent() {
        var first = service.createAdminSession("admin.root", PASSWORD);
        var second = service.createAdminSession("admin.root", PASSWORD);
        assertThat(service.revokeCurrentSession(first.rawToken(), "USER_LOGOUT")).isTrue();
        assertThat(service.revokeCurrentSession(first.rawToken(), "USER_LOGOUT")).isFalse();
        assertThatThrownBy(() -> service.validateAdminSession(first.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(service.validateAdminSession(second.rawToken()).user().id()).isEqualTo(ADMIN);
        assertThat(service.revokeAllSessions(ADMIN, "LOGOUT_ALL")).isEqualTo(1);
        assertThatThrownBy(() -> service.validateAdminSession(second.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void accountRoleStatusAndCredentialVersionChangesInvalidateExistingSession() {
        var roleSession = service.createAdminSession("admin.root", PASSWORD);
        jdbc.update("UPDATE `user` SET system_role='USER' WHERE id=?", ADMIN);
        assertThatThrownBy(() -> service.validateAdminSession(roleSession.rawToken()))
                .isInstanceOf(UnauthorizedException.class);

        jdbc.update("UPDATE `user` SET system_role='GOVERNANCE_ADMIN' WHERE id=?", ADMIN);
        var statusSession = service.createAdminSession("admin.root", PASSWORD);
        jdbc.update("UPDATE `user` SET account_status='SUSPENDED' WHERE id=?", ADMIN);
        assertThatThrownBy(() -> service.validateAdminSession(statusSession.rawToken()))
                .isInstanceOf(UnauthorizedException.class);

        jdbc.update("UPDATE `user` SET account_status='ACTIVE' WHERE id=?", ADMIN);
        var versionSession = service.createAdminSession("admin.root", PASSWORD);
        jdbc.update("UPDATE admin_password_credential c JOIN external_identity e "
                + "ON e.id=c.external_identity_id SET c.credential_version=c.credential_version+1 "
                + "WHERE e.user_id=?", ADMIN);
        assertThatThrownBy(() -> service.validateAdminSession(versionSession.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void concurrentCreationValidationAndRevocationLeaveNoUsableSession() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AdminAuthenticationService.IssuedAdminSession>> creations = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                creations.add(executor.submit(() -> {
                    start.await();
                    return service.createAdminSession("admin.root", PASSWORD);
                }));
            }
            start.countDown();
            List<AdminAuthenticationService.IssuedAdminSession> sessions = new ArrayList<>();
            for (Future<AdminAuthenticationService.IssuedAdminSession> creation : creations) {
                sessions.add(creation.get());
            }
            assertThat(sessions).extracting(AdminAuthenticationService.IssuedAdminSession::rawToken)
                    .doesNotHaveDuplicates();

            CountDownLatch race = new CountDownLatch(1);
            Future<?> validation = executor.submit(() -> {
                race.await();
                try {
                    service.validateAdminSession(sessions.getFirst().rawToken());
                } catch (UnauthorizedException ignored) {
                    // Revocation may linearize first.
                }
                return null;
            });
            Future<Integer> revocation = executor.submit(() -> {
                race.await();
                return service.revokeAllSessions(ADMIN, "CONCURRENT_REVOKE");
            });
            race.countDown();
            validation.get();
            assertThat(revocation.get()).isBetween(1, 8);
            for (var session : sessions) {
                assertThatThrownBy(() -> service.validateAdminSession(session.rawToken()))
                        .isInstanceOf(UnauthorizedException.class);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void externalIdentityUniquenessAndProvisioningBoundariesAreEnforced() {
        assertThatThrownBy(() -> service.provisionAdminCredential(ADMIN, "admin.root", PASSWORD))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.provisionAdminCredential(ORDINARY, "other-name", PASSWORD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.provisionAdminCredential(ADMIN, "other-name", "too-short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weakerArgon2ParametersUpgradeCredentialAndRevokeOlderSessions() {
        var olderSession = service.createAdminSession("admin.root", PASSWORD);
        var weakerEncoder = new Argon2PasswordEncoder(16, 32, 1, 4_096, 1);
        jdbc.update("UPDATE admin_password_credential c JOIN external_identity e "
                        + "ON e.id=c.external_identity_id SET c.password_hash=? WHERE e.user_id=?",
                weakerEncoder.encode(PASSWORD), ADMIN);

        var upgradedSession = service.createAdminSession("admin.root", PASSWORD);

        assertThat(jdbc.queryForObject("SELECT credential_version FROM admin_password_credential c "
                + "JOIN external_identity e ON e.id=c.external_identity_id WHERE e.user_id=?",
                Integer.class, ADMIN)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT credential_version FROM admin_session WHERE id=?",
                Integer.class, upgradedSession.sessionId())).isEqualTo(2);
        assertThatThrownBy(() -> service.validateAdminSession(olderSession.rawToken()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(service.validateAdminSession(upgradedSession.rawToken()).user().id()).isEqualTo(ADMIN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authentication_audit_event "
                + "WHERE event_type='CREDENTIAL_UPGRADED' AND subject_user_id=?", Integer.class, ADMIN))
                .isOne();
    }

    @Test
    void sessionInstantsRoundTripThroughUtcJdbcConnection() {
        var session = service.createAdminSession("admin.root", PASSWORD);

        assertThat(jdbc.queryForObject("SELECT created_at FROM admin_session WHERE id=?",
                java.sql.Timestamp.class, session.sessionId()).toInstant()).isEqualTo(START);
        assertThat(service.validateAdminSession(session.rawToken()).absoluteExpiresAt())
                .isEqualTo(START.plus(Duration.ofHours(8)));
    }

    @Test
    void normalIdentityContextExcludesBootstrapButRetainsObjectStorageInfrastructure() {
        assertThat(applicationContext.getBeansOfType(AdminBootstrapService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(MinioClient.class)).hasSize(1);
    }
    private void user(String id, String name, String status, String role) {
        jdbc.update("INSERT INTO `user` (id,display_name,account_status,system_role) VALUES (?,?,?,?)",
                id, name, status, role);
    }

    private int countSessions() {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM admin_session", Integer.class);
        return value == null ? 0 : value;
    }

    @TestConfiguration
    static class ClockTestConfiguration {
        @Bean
        @Primary
        MutableAuthenticationClock mutableAuthenticationClock() {
            return new MutableAuthenticationClock();
        }
    }

    static class MutableAuthenticationClock implements AuthenticationClock {
        private Instant value = START;

        synchronized void set(Instant value) {
            this.value = value;
        }

        synchronized void advance(Duration duration) {
            value = value.plus(duration);
        }

        @Override
        public synchronized Instant now() {
            return value;
        }
    }
}
