package org.animallink.identity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AuthenticationProtectionMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("authentication_protection_migration_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v7MigratesFromV6AndEnforcesBootstrapLimiterAndAuditConstraints() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("6").load().migrate();
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();

        assertThat(value("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=1"))
                .isEqualTo("1");
        assertThat(value("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name IN ('admin_bootstrap_control','admin_login_attempt_state',"
                + "'authentication_audit_event')")).isEqualTo("3");
        assertThat(value("SELECT status FROM admin_bootstrap_control WHERE singleton_id=1")).isEqualTo("OPEN");
        assertThat(value("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name='admin_login_attempt_state' AND index_name='PRIMARY' AND non_unique=0"))
                .isEqualTo("scope_type,scope_key");
        assertThat(value("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name='authentication_audit_event' AND index_name='idx_auth_audit_created'"))
                .isEqualTo("created_at,id");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO admin_bootstrap_control(singleton_id,status) VALUES(2,'OPEN')"))
                    .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO authentication_audit_event
                      (id,event_type,outcome,actor_type,source_fingerprint,reason_code,created_at)
                    VALUES ('10000000-0000-0000-0000-000000000001','ADMIN_LOGIN_SUCCESS','SUCCESS',
                            'ADMIN_CREDENTIAL','raw-client-address','CREDENTIALS_ACCEPTED',CURRENT_TIMESTAMP(6))
                    """)).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO admin_login_attempt_state
                      (scope_type,scope_key,normalized_username,failure_count,in_flight_count,window_started_at,
                       last_source_fingerprint,updated_at)
                    VALUES ('USERNAME',UNHEX(REPEAT('aa',32)),'admin.root',-1,0,CURRENT_TIMESTAMP(6),
                            UNHEX(REPEAT('bb',32)),CURRENT_TIMESTAMP(6))
                    """)).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO admin_login_attempt_state
                      (scope_type,scope_key,failure_count,in_flight_count,window_started_at,
                       last_source_fingerprint,updated_at)
                    VALUES ('SOURCE',UNHEX(REPEAT('cc',32)),0,-1,CURRENT_TIMESTAMP(6),
                            UNHEX(REPEAT('cc',32)),CURRENT_TIMESTAMP(6))
                    """)).isInstanceOf(Exception.class);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private String value(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
