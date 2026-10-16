package org.animallink.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class UserGovernanceIntegrationTest {
    private static final String USER = "00000000-0000-0000-0000-000000000501";
    private static final String OTHER = "00000000-0000-0000-0000-000000000502";
    private static final String DISABLED = "00000000-0000-0000-0000-000000000503";
    private static final String ADMIN_A = "00000000-0000-0000-0000-000000000591";
    private static final String ADMIN_B = "00000000-0000-0000-0000-000000000592";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("user_governance_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM user_governance_audit");
        jdbc.update("DELETE FROM `user`");
        user(USER, "普通用户", "ACTIVE", "USER");
        user(OTHER, "其他用户", "ACTIVE", "USER");
        user(DISABLED, "停用用户", "DISABLED", "USER");
        user(ADMIN_A, "治理管理员甲", "ACTIVE", "GOVERNANCE_ADMIN");
        user(ADMIN_B, "治理管理员乙", "ACTIVE", "GOVERNANCE_ADMIN");
    }

    @Test
    void restrictUnrestrictPreserveCurrentUserSemanticsAndAppendHistory() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("X-User-Id", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountStatus").value("ACTIVE"));

        transition("restrict", USER, "  第一次限制  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER))
                .andExpect(jsonPath("$.accountStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.credentialMaterial").doesNotExist());
        mvc.perform(get("/api/v1/users/me").header("X-User-Id", USER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/users/me/campus-memberships").header("X-User-Id", USER))
                .andExpect(status().isUnauthorized());

        transition("unrestrict", USER, "  恢复访问  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));
        mvc.perform(get("/api/v1/users/me").header("X-User-Id", USER))
                .andExpect(status().isOk());
        transition("restrict", USER, "再次限制").andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/users/{id}/governance-actions", USER)
                        .header("X-User-Id", ADMIN_A).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].action").value("RESTRICT"))
                .andExpect(jsonPath("$.items[0].reason").value("再次限制"))
                .andExpect(jsonPath("$.items[1].action").value("UNRESTRICT"))
                .andExpect(jsonPath("$.items[1].reason").value("恢复访问"));
        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT action, from_status, to_status, actor_user_id, reason
                FROM user_governance_audit WHERE user_id = ? ORDER BY created_at, id
                """, USER);
        assertThat(history).extracting(row -> row.get("action"))
                .containsExactly("RESTRICT", "UNRESTRICT", "RESTRICT");
        assertThat(history).extracting(row -> row.get("from_status") + "->" + row.get("to_status"))
                .containsExactly("ACTIVE->SUSPENDED", "SUSPENDED->ACTIVE", "ACTIVE->SUSPENDED");
        assertThat(history).extracting(row -> row.get("actor_user_id")).containsOnly(ADMIN_A);
    }

    @Test
    void repeatedAndDisabledTransitionsConflictWithoutAudit() throws Exception {
        transition("restrict", USER, "首次限制").andExpect(status().isOk());
        transition("restrict", USER, "重复限制").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        transition("unrestrict", OTHER, "无需恢复").andExpect(status().isConflict());
        transition("restrict", DISABLED, "不可限制").andExpect(status().isConflict());
        transition("unrestrict", DISABLED, "不可恢复").andExpect(status().isConflict());

        assertThat(statusOf(USER)).isEqualTo("SUSPENDED");
        assertThat(statusOf(OTHER)).isEqualTo("ACTIVE");
        assertThat(statusOf(DISABLED)).isEqualTo("DISABLED");
        assertThat(auditCount(USER)).isOne();
        assertThat(auditCount(OTHER)).isZero();
        assertThat(auditCount(DISABLED)).isZero();
    }

    @Test
    void selfRestrictionAndAdminTargetRestrictionAreBlockedButAdminRecoveryIsAllowed() throws Exception {
        transitionAs("restrict", ADMIN_A, "不能限制自己", ADMIN_A)
                .andExpect(status().isConflict());
        transitionAs("restrict", ADMIN_B, "不能限制同级", ADMIN_A)
                .andExpect(status().isConflict());
        assertThat(statusOf(ADMIN_A)).isEqualTo("ACTIVE");
        assertThat(statusOf(ADMIN_B)).isEqualTo("ACTIVE");
        assertThat(auditCount(ADMIN_A) + auditCount(ADMIN_B)).isZero();

        jdbc.update("UPDATE `user` SET account_status='SUSPENDED' WHERE id=?", ADMIN_B);
        transitionAs("unrestrict", ADMIN_B, "恢复历史管理员", ADMIN_A)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.systemRole").value("GOVERNANCE_ADMIN"));
        assertThat(auditCount(ADMIN_B)).isOne();
    }

    @Test
    void allGovernanceRoutesRequireAnActiveGovernanceAdmin() throws Exception {
        jdbc.update("UPDATE `user` SET account_status='SUSPENDED' WHERE id=?", ADMIN_B);
        List<Supplier<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>> routes = List.of(
                () -> get("/api/v1/admin/users/{id}", USER),
                () -> get("/api/v1/admin/users/{id}/governance-actions", USER),
                () -> post("/api/v1/admin/users/{id}/restrict", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"限制\"}"),
                () -> post("/api/v1/admin/users/{id}/unrestrict", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"恢复\"}"));
        for (var route : routes) {
            mvc.perform(route.get()).andExpect(status().isUnauthorized());
            mvc.perform(route.get().header("X-User-Id", OTHER)).andExpect(status().isForbidden());
            mvc.perform(route.get().header("X-User-Id", ADMIN_B)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/admin/users/{id}", USER).header("X-User-Id", ADMIN_A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(USER));
    }

    @Test
    void mutationReasonsUseCodePointValidationAndPersistNormalizedText() throws Exception {
        for (String action : List.of("restrict", "unrestrict")) {
            mvc.perform(post("/api/v1/admin/users/{id}/" + action, USER)
                            .header("X-User-Id", ADMIN_A))
                    .andExpect(status().isBadRequest());
            for (String body : List.of("{}", "{\"reason\":null}", "{\"reason\":\"\"}",
                    "{\"reason\":\"   \"}", "{\"reason\":\"" + "😀".repeat(1001) + "\"}")) {
                mvc.perform(post("/api/v1/admin/users/{id}/" + action, USER)
                                .header("X-User-Id", ADMIN_A)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
        }
        transition("restrict", USER, "  " + "😀".repeat(1000) + "  ").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT reason FROM user_governance_audit WHERE user_id=?",
                String.class, USER)).isEqualTo("😀".repeat(1000));
    }

    @Test
    void adminDetailAndHistoryAreMinimalValidatePagingAndHideUnknownUsers() throws Exception {
        mvc.perform(get("/api/v1/admin/users/{id}", USER).header("X-User-Id", ADMIN_A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(USER))
                .andExpect(jsonPath("$.displayName").value("普通用户"))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.systemRole").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.memberships").doesNotExist());
        mvc.perform(get("/api/v1/admin/users/{id}", "00000000-0000-0000-0000-000000000599")
                        .header("X-User-Id", ADMIN_A))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        for (String size : List.of("0", "101")) {
            mvc.perform(get("/api/v1/admin/users/{id}/governance-actions", USER)
                            .header("X-User-Id", ADMIN_A).param("size", size))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/admin/users/{id}/governance-actions", USER)
                        .header("X-User-Id", ADMIN_A).param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRestrictAllowsExactlyOneTransitionAndAudit() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = List.of(
                    executor.submit(() -> concurrentTransition("restrict", USER, "并发一", start)),
                    executor.submit(() -> concurrentTransition("restrict", USER, "并发二", start)));
            start.countDown();
            assertThat(statuses(futures)).containsExactly(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(statusOf(USER)).isEqualTo("SUSPENDED");
        assertThat(auditCount(USER)).isOne();
    }

    @Test
    void concurrentRestrictAndUnrestrictProduceOnlyLegalSerializedOutcome() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Integer> outcomes;
        try {
            List<Future<Integer>> futures = List.of(
                    executor.submit(() -> concurrentTransition("restrict", USER, "并发限制", start)),
                    executor.submit(() -> concurrentTransition("unrestrict", USER, "并发恢复", start)));
            start.countDown();
            outcomes = statuses(futures);
        } finally {
            executor.shutdownNow();
        }
        assertThat(outcomes).doesNotContain(500).allMatch(status -> status == 200 || status == 409);
        List<String> actions = jdbc.queryForList("""
                SELECT action FROM user_governance_audit
                WHERE user_id=? ORDER BY created_at, id
                """, String.class, USER);
        if (outcomes.equals(List.of(200, 200))) {
            assertThat(statusOf(USER)).isEqualTo("ACTIVE");
            assertThat(actions).containsExactly("RESTRICT", "UNRESTRICT");
        } else {
            assertThat(outcomes).containsExactly(200, 409);
            assertThat(statusOf(USER)).isEqualTo("SUSPENDED");
            assertThat(actions).containsExactly("RESTRICT");
        }
    }

    @Test
    void auditConstraintFailureRollsBackStatusMutation() throws Exception {
        jdbc.execute("ALTER TABLE user_governance_audit ADD CONSTRAINT chk_test_audit_failure "
                + "CHECK (reason <> 'force-audit-failure')");
        try {
            assertThatThrownBy(() -> transition("restrict", USER, "force-audit-failure"))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThat(statusOf(USER)).isEqualTo("ACTIVE");
            assertThat(auditCount(USER)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE user_governance_audit DROP CHECK chk_test_audit_failure");
        }
    }

    private org.springframework.test.web.servlet.ResultActions transition(
            String action, String target, String reason) throws Exception {
        return transitionAs(action, target, reason, ADMIN_A);
    }

    private org.springframework.test.web.servlet.ResultActions transitionAs(
            String action, String target, String reason, String actor) throws Exception {
        return mvc.perform(post("/api/v1/admin/users/{id}/" + action, target)
                .header("X-User-Id", actor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}"));
    }

    private int concurrentTransition(String action, String target, String reason,
                                     CountDownLatch start) throws Exception {
        start.await();
        return transition(action, target, reason).andReturn().getResponse().getStatus();
    }

    private List<Integer> statuses(List<Future<Integer>> futures) {
        return futures.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }).sorted().toList();
    }

    private void user(String id, String name, String status, String role) {
        jdbc.update("""
                INSERT INTO `user` (id, display_name, account_status, system_role)
                VALUES (?, ?, ?, ?)
                """, id, name, status, role);
    }

    private String statusOf(String userId) {
        return jdbc.queryForObject("SELECT account_status FROM `user` WHERE id=?", String.class, userId);
    }

    private int auditCount(String userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_governance_audit WHERE user_id=?",
                Integer.class, userId);
    }
}
