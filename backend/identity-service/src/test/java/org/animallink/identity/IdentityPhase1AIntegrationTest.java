package org.animallink.identity;

import org.animallink.identity.application.CampusVerificationReviewService;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.ConflictException;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.infrastructure.DevelopmentHeaderCurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
class IdentityPhase1AIntegrationTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000101";
    private static final String OTHER_USER_ID = "00000000-0000-0000-0000-000000000102";
    private static final String ADMIN_ID = "00000000-0000-0000-0000-000000000199";
    private static final String CAMPUS_ID = "10000000-0000-0000-0000-000000000101";
    private static final String OTHER_CAMPUS_ID = "10000000-0000-0000-0000-000000000102";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_identity_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CampusVerificationReviewService reviewService;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM volunteer_membership");
        jdbcTemplate.update("DELETE FROM campus_membership");
        jdbcTemplate.update("DELETE FROM campus_verification");
        jdbcTemplate.update("DELETE FROM campus");
        jdbcTemplate.update("DELETE FROM `user`");
        insertUser(USER_ID, "普通用户", "USER");
        insertUser(OTHER_USER_ID, "其他用户", "USER");
        insertUser(ADMIN_ID, "治理管理员", "GOVERNANCE_ADMIN");
        insertCampus(CAMPUS_ID, "测试大学", "测试大学", "测试市");
        insertCampus(OTHER_CAMPUS_ID, "另一所大学", "另一大学", "另一市");
    }

    @Test
    void currentUserAndCampusQueriesRespectPublicAndAuthenticatedBoundaries() throws Exception {
        mockMvc.perform(get("/api/v1/campuses").param("q", "测试"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CAMPUS_ID));

        mockMvc.perform(get("/api/v1/campuses/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        jdbcTemplate.update("UPDATE campus SET status = 'INACTIVE' WHERE id = ?", OTHER_CAMPUS_ID);
        mockMvc.perform(get("/api/v1/campuses/{id}", OTHER_CAMPUS_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/internal/v1/campuses/{id}", OTHER_CAMPUS_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/users/me")
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID))
                .andExpect(jsonPath("$.systemRole").value("USER"));
    }

    @Test
    void internalMembershipAndBatchUserSummaryEndpointsExposeAuthoritativeFacts() throws Exception {
        String verificationId = insertVerification(USER_ID, CAMPUS_ID, "APPROVED");
        insertMembership(USER_ID, CAMPUS_ID, verificationId);

        mockMvc.perform(get("/internal/v1/users/{userId}/campus-memberships/{campusId}",
                        USER_ID, CAMPUS_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.membershipType").value("STUDENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/internal/v1/users/{userId}/campus-memberships/{campusId}",
                        OTHER_USER_ID, CAMPUS_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));

        mockMvc.perform(post("/internal/v1/users/summaries")
                        .contentType("application/json")
                        .content("{\"userIds\":[\"" + USER_ID + "\",\"" + ADMIN_ID + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == '%s')].displayName".formatted(USER_ID))
                        .value("普通用户"));
    }

    @Test
    void submitRejectsDuplicatePendingAndExistingMembership() throws Exception {
        String request = verificationRequest(CAMPUS_ID, "STUDENT");
        mockMvc.perform(post("/api/v1/campus-verifications")
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, USER_ID)
                        .contentType("application/json").content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/v1/campus-verifications")
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, USER_ID)
                        .contentType("application/json").content(request))
                .andExpect(status().isConflict());

        String approvedId = insertVerification(USER_ID, OTHER_CAMPUS_ID, "APPROVED");
        insertMembership(USER_ID, OTHER_CAMPUS_ID, approvedId);
        mockMvc.perform(post("/api/v1/campus-verifications")
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, USER_ID)
                        .contentType("application/json")
                        .content(verificationRequest(OTHER_CAMPUS_ID, "ALUMNI")))
                .andExpect(status().isConflict());
    }

    @Test
    void userCanOnlyReadOwnVerification() throws Exception {
        String ownId = insertVerification(USER_ID, CAMPUS_ID, "PENDING_REVIEW");
        String otherId = insertVerification(OTHER_USER_ID, OTHER_CAMPUS_ID, "PENDING_REVIEW");

        mockMvc.perform(get("/api/v1/campus-verifications/{id}", ownId)
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownId));

        mockMvc.perform(get("/api/v1/campus-verifications/{id}", otherId)
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminCanApproveAndApprovalCreatesOneMembership() throws Exception {
        String verificationId = insertVerification(USER_ID, CAMPUS_ID, "PENDING_REVIEW");

        mockMvc.perform(post("/api/v1/admin/campus-verifications/{id}/approve", verificationId)
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, USER_ID)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/campus-verifications/{id}/approve", verificationId)
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, ADMIN_ID)
                        .contentType("application/json").content("{\"reviewReason\":\"材料符合要求\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(countMemberships(USER_ID, CAMPUS_ID)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/users/me/campus-memberships")
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].membershipType").value("STUDENT"));

        mockMvc.perform(post("/api/v1/admin/campus-verifications/{id}/reject", verificationId)
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, ADMIN_ID)
                        .contentType("application/json").content("{\"reviewReason\":\"不能反向变更\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectionIsTerminal() throws Exception {
        String verificationId = insertVerification(USER_ID, CAMPUS_ID, "PENDING_REVIEW");
        mockMvc.perform(post("/api/v1/admin/campus-verifications/{id}/reject", verificationId)
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, ADMIN_ID)
                        .contentType("application/json").content("{\"reviewReason\":\"信息不足\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(post("/api/v1/admin/campus-verifications/{id}/approve", verificationId)
                        .header(DevelopmentHeaderCurrentUserProvider.USER_ID_HEADER, ADMIN_ID)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
        assertThat(countMemberships(USER_ID, CAMPUS_ID)).isZero();
    }

    @Test
    void concurrentApprovalCreatesExactlyOneMembership() throws Exception {
        String verificationId = insertVerification(USER_ID, CAMPUS_ID, "PENDING_REVIEW");
        UserAccount admin = admin();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        reviewService.approve(verificationId, admin, "并发审核");
                        return "SUCCESS";
                    } catch (ConflictException exception) {
                        return "CONFLICT";
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get());
            }
            assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
            assertThat(countMemberships(USER_ID, CAMPUS_ID)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void approvalRollsBackWhenMembershipWriteFails() {
        String verificationId = insertVerification(USER_ID, CAMPUS_ID, "PENDING_REVIEW");
        insertMembership(OTHER_USER_ID, OTHER_CAMPUS_ID, verificationId);

        assertThatThrownBy(() -> reviewService.approve(verificationId, admin(), "触发事务回滚"))
                .isInstanceOf(DataIntegrityViolationException.class);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM campus_verification WHERE id = ?", String.class, verificationId);
        assertThat(status).isEqualTo("PENDING_REVIEW");
        assertThat(countMemberships(USER_ID, CAMPUS_ID)).isZero();
    }

    private void insertUser(String id, String displayName, String role) {
        jdbcTemplate.update("""
                INSERT INTO `user` (id, display_name, account_status, system_role)
                VALUES (?, ?, 'ACTIVE', ?)
                """, id, displayName, role);
    }

    private void insertCampus(String id, String name, String shortName, String city) {
        jdbcTemplate.update("""
                INSERT INTO campus (id, name, short_name, city, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, id, name, shortName, city);
    }

    private String insertVerification(String userId, String campusId, String status) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO campus_verification
                    (id, user_id, campus_id, requested_membership_type, applicant_name, status,
                     reviewed_by, reviewed_at)
                VALUES (?, ?, ?, 'STUDENT', '测试申请人', ?,
                        CASE WHEN ? = 'PENDING_REVIEW' THEN NULL ELSE ? END,
                        CASE WHEN ? = 'PENDING_REVIEW' THEN NULL ELSE CURRENT_TIMESTAMP(6) END)
                """, id, userId, campusId, status, status, ADMIN_ID, status);
        return id;
    }

    private void insertMembership(String userId, String campusId, String verificationId) {
        jdbcTemplate.update("""
                INSERT INTO campus_membership
                    (id, user_id, campus_id, membership_type, status, approved_at, last_verification_id)
                VALUES (?, ?, ?, 'STUDENT', 'ACTIVE', CURRENT_TIMESTAMP(6), ?)
                """, UUID.randomUUID().toString(), userId, campusId, verificationId);
    }

    private int countMemberships(String userId, String campusId) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM campus_membership WHERE user_id = ? AND campus_id = ?
                """, Integer.class, userId, campusId);
        return value == null ? 0 : value;
    }

    private UserAccount admin() {
        Instant now = Instant.now();
        return new UserAccount(ADMIN_ID, "治理管理员", AccountStatus.ACTIVE,
                SystemRole.GOVERNANCE_ADMIN, now, now);
    }

    private String verificationRequest(String campusId, String membershipType) {
        return """
                {
                  "campusId": "%s",
                  "requestedMembershipType": "%s",
                  "applicantName": "测试申请人",
                  "affiliationNote": "用于自动化测试"
                }
                """.formatted(campusId, membershipType);
    }
}
