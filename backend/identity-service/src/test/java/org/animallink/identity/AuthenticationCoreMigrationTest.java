package org.animallink.identity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AuthenticationCoreMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("authentication_core_migration_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v6MigratesFromV5AndEnforcesIdentityCredentialAndSessionConstraints() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("5").load().migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO `user` (id, display_name, account_status, system_role)
                    VALUES ('00000000-0000-0000-0000-000000000801', '管理员', 'ACTIVE', 'GOVERNANCE_ADMIN')
                    """);
        }

        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();

        assertThat(value("SELECT COUNT(*) FROM flyway_schema_history WHERE version='6' AND success=1"))
                .isEqualTo("1");
        assertThat(value("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name IN ('external_identity','admin_password_credential','admin_session')"))
                .isEqualTo("3");
        assertThat(value("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='external_identity' AND column_name='provider_subject'"))
                .isEqualTo("utf8mb4_bin");
        assertThat(value("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='external_identity' AND column_name='provider'"))
                .isEqualTo("ascii_bin");
        assertThat(value("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name='admin_session' AND index_name='uk_admin_session_token_hash' AND non_unique=0"))
                .isEqualTo("1");
        assertThat(value("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name='admin_session' AND index_name='idx_admin_session_user_state'"))
                .isEqualTo("user_id,revoked_at,absolute_expires_at");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO external_identity (id,user_id,provider,provider_tenant,provider_subject)
                    VALUES ('10000000-0000-0000-0000-000000000801',
                            '00000000-0000-0000-0000-000000000801','ADMIN_PASSWORD','','admin')
                    """);
            statement.executeUpdate("""
                    INSERT INTO admin_password_credential
                      (external_identity_id,password_hash,password_algorithm,credential_version)
                    VALUES ('10000000-0000-0000-0000-000000000801',
                            '$argon2id$v=19$m=19456,t=2,p=1$test$test','ARGON2ID_V1',1)
                    """);
            statement.executeUpdate(validSessionSql("20000000-0000-0000-0000-000000000801", "aa"));

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO external_identity (id,user_id,provider,provider_tenant,provider_subject)
                    VALUES ('10000000-0000-0000-0000-000000000802',
                            '00000000-0000-0000-0000-000000000801','ADMIN_PASSWORD','','admin')
                    """)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO external_identity (id,user_id,provider,provider_tenant,provider_subject)
                    VALUES ('10000000-0000-0000-0000-000000000804',
                            '00000000-0000-0000-0000-000000000801','ADMIN_PASSWORD','','Admin')
                    """)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO external_identity (id,user_id,provider,provider_tenant,provider_subject)
                    VALUES ('10000000-0000-0000-0000-000000000805',
                            '00000000-0000-0000-0000-000000000801',
                            'ADMIN_PASSWORD','unexpected-tenant','admin.other')
                    """)).isInstanceOf(SQLException.class);
            statement.executeUpdate("""
                    INSERT INTO external_identity (id,user_id,provider,provider_tenant,provider_subject)
                    VALUES ('10000000-0000-0000-0000-000000000803',
                            '00000000-0000-0000-0000-000000000801',
                            'WECHAT_MINIPROGRAM','wx-app-id','openid-1')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO admin_password_credential
                      (external_identity_id,password_hash,password_algorithm,credential_version)
                    VALUES ('10000000-0000-0000-0000-000000000803',
                            '$argon2id$v=19$m=19456,t=2,p=1$test$test','ARGON2ID_V1',1)
                    """)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    validSessionSql("20000000-0000-0000-0000-000000000802", "aa")))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO admin_session
                      (id,user_id,client_type,token_hash,credential_version,created_at,last_seen_at,
                       idle_expires_at,absolute_expires_at)
                    VALUES ('20000000-0000-0000-0000-000000000803',
                            '00000000-0000-0000-0000-000000000801','ADMIN_WEB',UNHEX(REPEAT('bb',32)),1,
                            '2026-01-01 00:00:00','2026-01-01 00:00:00',
                            '2026-01-01 09:00:00','2026-01-01 08:00:00')
                    """)).isInstanceOf(SQLException.class);
        }
    }

    private String validSessionSql(String id, String hashByte) {
        return """
                INSERT INTO admin_session
                  (id,user_id,client_type,token_hash,credential_version,created_at,last_seen_at,
                   idle_expires_at,absolute_expires_at)
                VALUES ('%s','00000000-0000-0000-0000-000000000801','ADMIN_WEB',
                        UNHEX(REPEAT('%s',32)),1,'2026-01-01 00:00:00','2026-01-01 00:00:00',
                        '2026-01-01 00:30:00','2026-01-01 08:00:00')
                """.formatted(id, hashByte);
    }

    private Connection connection() throws SQLException {
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
