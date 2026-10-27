package org.animallink.identity;

import io.minio.MinioClient;
import org.animallink.identity.api.CredentialMaterialController;
import org.animallink.identity.application.AdminBootstrapService;
import org.animallink.identity.domain.AdminBootstrapRejectedException;
import org.animallink.identity.infrastructure.MinioCredentialObjectStorage;
import org.animallink.identity.infrastructure.ServiceAssertionVerifier;
import org.animallink.identity.operations.AdminBootstrapApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AdminBootstrapApplicationContextIntegrationTest {
    private static final String PASSWORD = "correct horse battery staple";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("admin_bootstrap_context_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @Test
    void minimalContextStartsWithoutUnrelatedCredentialsAndInitializesFirstAdministrator() {
        try (var context = new SpringApplicationBuilder(AdminBootstrapApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("admin-bootstrap")
                .properties("spring.main.banner-mode=off")
                .run(
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC",
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword())) {
            assertThat(context).isNotInstanceOf(WebApplicationContext.class);
            assertThat(context.getBeansOfType(MinioClient.class)).isEmpty();
            assertThat(context.getBeansOfType(MinioCredentialObjectStorage.class)).isEmpty();
            assertThat(context.getBeansOfType(CredentialMaterialController.class)).isEmpty();
            assertThat(context.getBeansOfType(ServiceAssertionVerifier.class)).isEmpty();
            assertThat(context.containsBean("credentialPrecheckDispatcher")).isFalse();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class)).isEqualTo(8);
            assertThat(jdbc.queryForObject(
                    "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",
                    Integer.class)).isEqualTo(8);

            AdminBootstrapService service = context.getBean(AdminBootstrapService.class);
            var authorization = AdminBootstrapService.LocalBootstrapAuthorization
                    .confirmedInteractive("local:integration-test");
            var user = service.initialize(authorization, "Bootstrap Administrator", "Bootstrap.Admin", PASSWORD);

            assertThat(user.isActive()).isTrue();
            assertThat(user.isGovernanceAdmin()).isTrue();
            assertThat(jdbc.queryForObject("SELECT account_status FROM `user` WHERE id=?",
                    String.class, user.id())).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT system_role FROM `user` WHERE id=?",
                    String.class, user.id())).isEqualTo("GOVERNANCE_ADMIN");
            assertThat(jdbc.queryForObject("SELECT provider_subject FROM external_identity WHERE user_id=?",
                    String.class, user.id())).isEqualTo("bootstrap.admin");
            assertThat(jdbc.queryForObject("SELECT password_algorithm FROM admin_password_credential c "
                            + "JOIN external_identity e ON e.id=c.external_identity_id WHERE e.user_id=?",
                    String.class, user.id())).isEqualTo("ARGON2ID_V1");
            assertThat(jdbc.queryForObject("SELECT password_hash FROM admin_password_credential c "
                            + "JOIN external_identity e ON e.id=c.external_identity_id WHERE e.user_id=?",
                    String.class, user.id())).startsWith("$argon2id$").doesNotContain(PASSWORD);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM authentication_audit_event "
                            + "WHERE event_type='ADMIN_INITIALIZATION_SUCCESS' AND outcome='SUCCESS' "
                            + "AND subject_user_id=?",
                    Integer.class, user.id())).isOne();
            assertThat(jdbc.queryForObject("SELECT status FROM admin_bootstrap_control WHERE singleton_id=1",
                    String.class)).isEqualTo("COMPLETED");

            assertThatThrownBy(() -> service.initialize(authorization, "Second Administrator",
                    "second.admin", PASSWORD))
                    .isInstanceOfSatisfying(AdminBootstrapRejectedException.class,
                            rejected -> assertThat(rejected.reasonCode())
                                    .isEqualTo("BOOTSTRAP_ALREADY_COMPLETED"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_password_credential",
                    Integer.class)).isOne();
        }
    }
}
