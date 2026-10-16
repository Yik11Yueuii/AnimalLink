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
class UserGovernanceMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("user_governance_migration_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v5MigratesFromV4AndEnforcesGovernanceAuditMetadata() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("4").load().migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO `user` (id,display_name,account_status,system_role) VALUES
                      ('00000000-0000-0000-0000-000000000701','目标','ACTIVE','USER'),
                      ('00000000-0000-0000-0000-000000000799','管理员','ACTIVE','GOVERNANCE_ADMIN')
                    """);
        }

        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();

        assertThat(value("SELECT COUNT(*) FROM flyway_schema_history WHERE version='5' AND success=1"))
                .isEqualTo("1");
        assertThat(value("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name='user_governance_audit'")).isEqualTo("1");
        assertThat(value("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='user_governance_audit'")).isEqualTo("8");
        assertThat(value("SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='user_governance_audit' "
                + "AND constraint_type='CHECK'")).isEqualTo("3");
        assertThat(value("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='user_governance_audit' "
                + "AND delete_rule <> 'CASCADE'")).isEqualTo("2");
        assertThat(value("SELECT GROUP_CONCAT(CONCAT(column_name,':',collation) "
                + "ORDER BY seq_in_index) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='user_governance_audit' "
                + "AND index_name='idx_user_governance_history'"))
                .isEqualTo("user_id:A,created_at:D,id:D");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO user_governance_audit
                      (id,user_id,action,from_status,to_status,actor_user_id,reason)
                    VALUES
                      ('10000000-0000-0000-0000-000000000701',
                       '00000000-0000-0000-0000-000000000701','RESTRICT','ACTIVE','SUSPENDED',
                       '00000000-0000-0000-0000-000000000799','治理限制')
                    """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO user_governance_audit
                      (id,user_id,action,from_status,to_status,actor_user_id,reason)
                    VALUES
                      ('10000000-0000-0000-0000-000000000702',
                       '00000000-0000-0000-0000-000000000701','RESTRICT','SUSPENDED','ACTIVE',
                       '00000000-0000-0000-0000-000000000799','非法迁移')
                    """)).isInstanceOf(SQLException.class);
        }
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
