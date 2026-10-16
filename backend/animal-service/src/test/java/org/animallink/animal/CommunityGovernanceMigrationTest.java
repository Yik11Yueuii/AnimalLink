package org.animallink.animal;

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

@Testcontainers
class CommunityGovernanceMigrationTest {
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000801";
    private static final String HIDDEN_POST = "40000000-0000-0000-0000-000000000801";
    private static final String ACTIVE_POST = "40000000-0000-0000-0000-000000000802";
    private static final String ORDINARY_POST = "40000000-0000-0000-0000-000000000803";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("community_governance_migration_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v8BackfillAndV9ContentGovernanceSchemaMigrateFromFreshMysql() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("7").load().migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO animal
                      (id,campus_id,display_name,species,sex,identity_status,adoption_status,current_context)
                    VALUES
                      ('20000000-0000-0000-0000-000000000801',
                       '10000000-0000-0000-0000-000000000801','迁移动物','CAT','UNKNOWN',
                       'ACTIVE','ADOPTED','ADOPTED_HOME')
                    """);
            statement.executeUpdate(postSql(HIDDEN_POST, "POST_ADOPTION", "HIDDEN",
                    "30000000-0000-0000-0000-000000000801"));
            statement.executeUpdate(postSql(ACTIVE_POST, "POST_ADOPTION", "ACTIVE",
                    "30000000-0000-0000-0000-000000000802"));
            statement.executeUpdate(postSql(ORDINARY_POST, "CAMPUS_POST", "HIDDEN", null));
            statement.executeUpdate(timelineSql("50000000-0000-0000-0000-000000000801",
                    HIDDEN_POST, "POST", "POST_ADOPTION_PUBLISHED"));
            statement.executeUpdate(timelineSql("50000000-0000-0000-0000-000000000802",
                    ACTIVE_POST, "POST", "POST_ADOPTION_PUBLISHED"));
            statement.executeUpdate(timelineSql("50000000-0000-0000-0000-000000000803",
                    ORDINARY_POST, "EVENT", "EVENT_CONFIRMED"));
        }

        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();

        assertThat(value("SELECT visibility FROM timeline_entry WHERE source_id='" + HIDDEN_POST + "'"))
                .isEqualTo("RESTRICTED");
        assertThat(value("SELECT visibility FROM timeline_entry WHERE source_id='" + ACTIVE_POST + "'"))
                .isEqualTo("PUBLIC");
        assertThat(value("SELECT visibility FROM timeline_entry WHERE source_id='" + ORDINARY_POST + "'"))
                .isEqualTo("PUBLIC");
        assertThat(value("SELECT COUNT(*) FROM community_governance_audit")).isEqualTo("0");
        assertThat(value("SELECT COUNT(*) FROM flyway_schema_history WHERE version='8' AND success=1"))
                .isEqualTo("1");
        assertThat(value("SELECT COUNT(*) FROM flyway_schema_history WHERE version='9' AND success=1"))
                .isEqualTo("1");
        assertThat(value("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name='community_content_report'")).isEqualTo("1");
        assertThat(value("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='community_content_report' AND column_name='open_marker' "
                + "AND generation_expression IS NOT NULL")).isEqualTo("1");
        assertThat(value("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='community_content_report' "
                + "AND index_name IN ('uk_content_report_open_reporter_resource',"
                + "'idx_content_report_status_created','idx_content_report_resource_created')"))
                .isEqualTo("3");
        assertThat(value("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='community_content_report'"))
                .isEqualTo("0");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE post SET status='REMOVED' WHERE id='" + ACTIVE_POST + "'");
            statement.executeUpdate("INSERT INTO community_governance_audit "
                    + "(id,resource_type,resource_id,`action`,from_state,to_state,actor_user_id,reason) "
                    + "VALUES ('60000000-0000-0000-0000-000000000801','POST','" + ACTIVE_POST
                    + "','REMOVE','ACTIVE','REMOVED','00000000-0000-0000-0000-000000000899','治理移除')");
            statement.executeUpdate(reportSql("70000000-0000-0000-0000-000000000801", "OPEN"));
            statement.executeUpdate("UPDATE community_content_report SET status='DISMISSED',"
                    + "resolution_note='已复核',reviewed_by_user_id='00000000-0000-0000-0000-000000000899',"
                    + "reviewed_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id="
                    + "'70000000-0000-0000-0000-000000000801'");
            statement.executeUpdate(reportSql("70000000-0000-0000-0000-000000000802", "OPEN"));
        }
        assertThat(value("SELECT COUNT(*) FROM community_content_report")).isEqualTo("2");
        assertThat(value("SELECT COUNT(*) FROM community_governance_audit WHERE `action`='REMOVE'"))
                .isEqualTo("1");
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private String value(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private String postSql(String id, String type, String status, String relationId) {
        String relation = relationId == null ? "NULL" : "'" + relationId + "'";
        return "INSERT INTO post (id,campus_id,animal_id,relation_id,author_user_id,post_type,"
                + "text_content,visibility,status) VALUES ('" + id
                + "','10000000-0000-0000-0000-000000000801','" + ANIMAL + "'," + relation
                + ",'00000000-0000-0000-0000-000000000801','" + type
                + "','迁移内容','PUBLIC','" + status + "')";
    }

    private String timelineSql(String id, String sourceId, String sourceType, String entryType) {
        return "INSERT INTO timeline_entry (id,animal_id,source_type,source_id,entry_type,title,"
                + "occurred_at,visibility) VALUES ('" + id + "','" + ANIMAL + "','" + sourceType
                + "','" + sourceId + "','" + entryType + "','迁移事件',CURRENT_TIMESTAMP(6),'PUBLIC')";
    }

    private String reportSql(String id, String status) {
        return "INSERT INTO community_content_report "
                + "(id,resource_type,resource_id,reporter_user_id,description,status) VALUES ('"
                + id + "','POST','" + HIDDEN_POST
                + "','00000000-0000-0000-0000-000000000888','举报内容','" + status + "')";
    }
}
