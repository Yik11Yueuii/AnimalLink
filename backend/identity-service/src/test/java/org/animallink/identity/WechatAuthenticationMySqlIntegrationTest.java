package org.animallink.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.animallink.identity.application.AuthenticationClock;
import org.animallink.identity.application.WechatAuthenticationService;
import org.animallink.identity.application.WechatCodeExchangeClient;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.infrastructure.JdbcWechatAuthenticationRepository;
import org.animallink.identity.infrastructure.SecureOpaqueSessionTokenCodec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Testcontainers
class WechatAuthenticationMySqlIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-31T00:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("wechat_authentication_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @AfterEach
    void cleanData() {
        jdbc.update("DELETE FROM wechat_user_session");
        jdbc.update("DELETE FROM external_identity WHERE provider = 'WECHAT_MINIPROGRAM'");
        jdbc.update("DELETE FROM `user` WHERE system_role = 'USER'");
    }

    @Test
    void flywayV9PersistsOneUserPerWechatIdentityAndRevokesExpiredSessions() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '9' AND success = 1", Integer.class)).isOne();
        var clock = new MutableClock();
        var service = service(clock);

        var first = service.login("valid-code-123");
        var second = service.login("valid-code-123");

        assertThat(first.userId()).isEqualTo(second.userId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identity WHERE provider = 'WECHAT_MINIPROGRAM'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE system_role = 'USER'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT system_role FROM `user` WHERE id = ?", String.class, first.userId())).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT LOWER(HEX(token_hash)) FROM wechat_user_session WHERE user_id = ? LIMIT 1", String.class, first.userId()))
                .isNotEqualTo(first.token());
        assertThat(service.validate(first.token()).userId()).isEqualTo(first.userId());
        assertThat(service.logout(first.token())).isTrue();
        assertThatThrownBy(() -> service.validate(first.token())).isInstanceOf(UnauthorizedException.class);

        var expiring = service.login("valid-code-123");
        clock.value = START.plus(Duration.ofDays(8));
        assertThatThrownBy(() -> service.validate(expiring.token())).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void concurrentCodeExchangesCannotCreateDuplicateExternalIdentities() throws Exception {
        var clock = new MutableClock();
        var service = service(clock);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<WechatAuthenticationService.IssuedSession>> calls = List.of(
                    () -> service.login("valid-code-123"), () -> service.login("valid-code-123"));
            var sessions = executor.invokeAll(calls).stream().map(future -> {
                try { return future.get(); } catch (Exception failure) { throw new AssertionError(failure); }
            }).toList();
            assertThat(sessions).extracting(WechatAuthenticationService.IssuedSession::userId).hasSize(2).containsOnly(sessions.getFirst().userId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_identity WHERE provider = 'WECHAT_MINIPROGRAM'", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE system_role = 'USER'", Integer.class)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private static WechatAuthenticationService service(MutableClock clock) {
        WechatCodeExchangeClient exchange = code -> new WechatCodeExchangeClient.ExchangeResult("open-id-123456", null);
        return new WechatAuthenticationService(exchange, new JdbcWechatAuthenticationRepository(jdbc),
                new SecureOpaqueSessionTokenCodec(), clock, "wx-test-app", Duration.ofDays(7), Duration.ofDays(30));
    }

    private static final class MutableClock implements AuthenticationClock {
        private Instant value = START;
        @Override public Instant now() { return value; }
    }
}
