package org.animallink.identity;

import org.animallink.identity.application.CredentialObjectStorage;
import org.animallink.identity.application.CredentialPrecheckClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CredentialPrecheckProjectionIntegrationTest {
    private static final String OWNER = "91000000-0000-0000-0000-000000000001";
    private static final String OTHER = "91000000-0000-0000-0000-000000000002";
    private static final String ADMIN = "91000000-0000-0000-0000-000000000003";
    private static final String CAMPUS = "92000000-0000-0000-0000-000000000001";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_identity_projection_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean CredentialPrecheckClient precheckClient;
    @MockBean CredentialObjectStorage storage;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM volunteer_membership");
        jdbc.update("DELETE FROM campus_membership");
        jdbc.update("DELETE FROM credential_precheck_attempt");
        jdbc.update("DELETE FROM campus_verification");
        jdbc.update("DELETE FROM credential_material");
        jdbc.update("DELETE FROM campus");
        jdbc.update("DELETE FROM `user`");
        user(OWNER, "申请人", "USER");
        user(OTHER, "无关用户", "USER");
        user(ADMIN, "治理管理员", "GOVERNANCE_ADMIN");
        jdbc.update("INSERT INTO campus(id,name,city,status) VALUES(?,?, '测试市','ACTIVE')", CAMPUS, "测试大学");
    }

    @Test
    void applicantProjectionMapsEverySafeStateWithoutSensitiveFields() throws Exception {
        assertApplicant(verification(OWNER, "PENDING_REVIEW", false), "NOT_REQUESTED");

        String pending = verification(OWNER, "PENDING_REVIEW", true);
        attempt(pending, 1, "PENDING", null);
        assertApplicant(pending, "CHECKING");

        String processing = verification(OWNER, "PENDING_REVIEW", true);
        attempt(processing, 1, "PROCESSING", null);
        assertApplicant(processing, "CHECKING");

        String passed = verification(OWNER, "PENDING_REVIEW", true);
        attempt(passed, 1, "PASSED", "[]");
        assertApplicant(passed, "COMPLETED");

        String manual = verification(OWNER, "PENDING_REVIEW", true);
        attempt(manual, 1, "MANUAL_REVIEW_REQUIRED", "[\"NAME_MISMATCH\"]");
        assertApplicant(manual, "COMPLETED");

        String unavailable = verification(OWNER, "PENDING_REVIEW", true);
        attempt(unavailable, 1, "UNAVAILABLE", "[]");
        assertApplicant(unavailable, "TEMPORARILY_UNAVAILABLE");
    }

    @Test
    void applicantAuthorizationKeepsOtherAndAnonymousCallersOut() throws Exception {
        String verificationId = verification(OWNER, "PENDING_REVIEW", true);
        attempt(verificationId, 1, "PASSED", "[]");

        mvc.perform(get("/api/v1/campus-verifications/{id}", verificationId).header("X-User-Id", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.credentialPrecheckStatus").value("COMPLETED"));
        mvc.perform(get("/api/v1/campus-verifications/{id}", verificationId).header("X-User-Id", OTHER))
                .andExpect(status().isNotFound()).andExpect(content().string(not(containsString("test-provider"))));
        mvc.perform(get("/api/v1/campus-verifications/{id}", verificationId))
                .andExpect(status().isUnauthorized()).andExpect(content().string(not(containsString("test-provider"))));
    }

    @Test
    void adminDetailProjectsCompletedStatusesAndNoAttemptAbsence() throws Exception {
        String passed = verification(OWNER, "PENDING_REVIEW", true);
        attempt(passed, 1, "PASSED", "[\"NAME_MATCH\",\"SCHOOL_MATCH\"]");
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", passed).header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precheck.status").value("PASSED"))
                .andExpect(jsonPath("$.precheck.attemptNo").value(1))
                .andExpect(jsonPath("$.precheck.intelligenceTaskId").isNotEmpty())
                .andExpect(jsonPath("$.precheck.provider").value("test-provider"))
                .andExpect(jsonPath("$.precheck.modelName").value("test-model"))
                .andExpect(jsonPath("$.precheck.overallConfidence").value(0.95))
                .andExpect(jsonPath("$.precheck.extractedSchoolName").value("测试大学"))
                .andExpect(jsonPath("$.precheck.extractedPersonName").value("申请人"))
                .andExpect(jsonPath("$.precheck.credentialType").value("CAMPUS_CREDENTIAL"))
                .andExpect(jsonPath("$.precheck.consistencyFlags[0]").value("NAME_MATCH"))
                .andExpect(jsonPath("$.precheck.summary").value("AI advisory only; governance review remains authoritative."))
                .andExpect(jsonPath("$.precheck.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.precheck.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.precheck.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.readUrl").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist());

        String manual = verification(OWNER, "PENDING_REVIEW", true);
        attempt(manual, 1, "MANUAL_REVIEW_REQUIRED", "[\"NAME_MISMATCH\"]");
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", manual).header("X-User-Id", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.precheck.status").value("MANUAL_REVIEW_REQUIRED"));

        String unavailable = verification(OWNER, "PENDING_REVIEW", true);
        attempt(unavailable, 1, "UNAVAILABLE", "[]");
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", unavailable).header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precheck.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.precheck.errorCategory").value("INTELLIGENCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.precheck.summary").value("Credential precheck is temporarily unavailable."));

        String absent = verification(OWNER, "PENDING_REVIEW", false);
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", absent).header("X-User-Id", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.precheck").value(nullValue()));
    }

    @Test
    void adminDetailHandlesInProgressAndMalformedFlagsSafely() throws Exception {
        String pending = verification(OWNER, "PENDING_REVIEW", true);
        attempt(pending, 1, "PENDING", null);
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", pending).header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precheck.status").value("PENDING"))
                .andExpect(jsonPath("$.precheck.intelligenceTaskId").value(nullValue()))
                .andExpect(jsonPath("$.precheck.provider").value(nullValue()))
                .andExpect(jsonPath("$.precheck.consistencyFlags").isEmpty());

        String processing = verification(OWNER, "PENDING_REVIEW", true);
        attempt(processing, 1, "PROCESSING", null);
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", processing).header("X-User-Id", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.precheck.status").value("PROCESSING"));

        String malformed = verification(OWNER, "PENDING_REVIEW", true);
        attempt(malformed, 1, "PASSED", "{\"raw\":\"must-not-leak\"}");
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", malformed).header("X-User-Id", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.precheck.consistencyFlags").isEmpty())
                .andExpect(content().string(not(containsString("must-not-leak"))));
    }

    @Test
    void adminListUsesCompactProjectionWithoutAiDetailsOrNPlusOneLookup() throws Exception {
        String verificationId = verification(OWNER, "PENDING_REVIEW", true);
        attempt(verificationId, 1, "PASSED", "[\"NAME_MISMATCH\"]");

        mvc.perform(get("/api/v1/admin/campus-verifications").header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(verificationId))
                .andExpect(jsonPath("$[0].credentialMaterialPresent").value(true))
                .andExpect(jsonPath("$[0].precheck").doesNotExist())
                .andExpect(jsonPath("$[0].overallConfidence").doesNotExist())
                .andExpect(jsonPath("$[0].extractedPersonName").doesNotExist())
                .andExpect(jsonPath("$[0].consistencyFlags").doesNotExist())
                .andExpect(jsonPath("$[0].summary").doesNotExist())
                .andExpect(jsonPath("$[0].provider").doesNotExist());
    }

    @Test
    void onlyGovernanceAdminCanReadDetailedProjection() throws Exception {
        String verificationId = verification(OWNER, "PENDING_REVIEW", true);
        attempt(verificationId, 1, "PASSED", "[]");

        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", verificationId).header("X-User-Id", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.precheck.status").value("PASSED"));
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", verificationId).header("X-User-Id", OTHER))
                .andExpect(status().isForbidden()).andExpect(content().string(not(containsString("test-provider"))));
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", verificationId))
                .andExpect(status().isUnauthorized()).andExpect(content().string(not(containsString("test-provider"))));
    }

    @Test
    void latestAttemptWinsWithoutChangingEarlierAttemptOrFormalState() throws Exception {
        String verificationId = verification(OWNER, "APPROVED", true);
        attempt(verificationId, 1, "PASSED", "[]");
        attempt(verificationId, 2, "UNAVAILABLE", "[]");

        mvc.perform(get("/api/v1/campus-verifications/{id}", verificationId).header("X-User-Id", OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.credentialPrecheckStatus").value("TEMPORARILY_UNAVAILABLE"));
        mvc.perform(get("/api/v1/admin/campus-verifications/{id}", verificationId).header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.precheck.attemptNo").value(2))
                .andExpect(jsonPath("$.precheck.status").value("UNAVAILABLE"));

        assertThat(jdbc.queryForObject("SELECT status FROM campus_verification WHERE id=?", String.class, verificationId))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_precheck_attempt WHERE verification_id=?", Integer.class, verificationId))
                .isEqualTo(2);
    }

    @Test
    void projectionsPreserveFormalStateAndRemainReadOnly() throws Exception {
        String pending = verification(OWNER, "PENDING_REVIEW", true);
        attempt(pending, 1, "PASSED", "[]");
        String approved = verification(OWNER, "APPROVED", true);
        attempt(approved, 1, "PASSED", "[]");
        String rejected = verification(OWNER, "REJECTED", true);
        attempt(rejected, 1, "MANUAL_REVIEW_REQUIRED", "[\"NAME_MISMATCH\"]");

        for (String id : new String[]{pending, approved, rejected}) {
            mvc.perform(get("/api/v1/admin/campus-verifications/{id}", id).header("X-User-Id", ADMIN))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/campus-verifications/{id}", rejected).header("X-User-Id", OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.credentialPrecheckStatus").value("COMPLETED"));

        assertThat(formalStatus(pending)).isEqualTo("PENDING_REVIEW");
        assertThat(formalStatus(approved)).isEqualTo("APPROVED");
        assertThat(formalStatus(rejected)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_membership", Integer.class)).isZero();
        verifyNoInteractions(precheckClient, storage);
    }

    private void assertApplicant(String verificationId, String safeStatus) throws Exception {
        mvc.perform(get("/api/v1/campus-verifications/{id}", verificationId).header("X-User-Id", OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialPrecheckStatus").value(safeStatus))
                .andExpect(jsonPath("$.precheck").doesNotExist())
                .andExpect(jsonPath("$.attemptId").doesNotExist())
                .andExpect(jsonPath("$.intelligenceTaskId").doesNotExist())
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.modelName").doesNotExist())
                .andExpect(jsonPath("$.overallConfidence").doesNotExist())
                .andExpect(jsonPath("$.extractedSchoolName").doesNotExist())
                .andExpect(jsonPath("$.extractedPersonName").doesNotExist())
                .andExpect(jsonPath("$.credentialType").doesNotExist())
                .andExpect(jsonPath("$.consistencyFlags").doesNotExist())
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.errorCategory").doesNotExist())
                .andExpect(jsonPath("$.startedAt").doesNotExist())
                .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    private String verification(String userId, String status, boolean withMaterial) {
        String materialId = withMaterial ? attachedMaterial(userId) : null;
        String id = UUID.randomUUID().toString();
        String campusId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO campus(id,name,city,status) VALUES(?,?, '测试市','ACTIVE')",
                campusId, "测试大学-" + campusId.substring(0, 8));
        Instant reviewedAt = "PENDING_REVIEW".equals(status) ? null : Instant.now();
        jdbc.update("""
                INSERT INTO campus_verification
                    (id,user_id,campus_id,requested_membership_type,applicant_name,affiliation_note,
                     expected_graduation_date,material_media_id,status,review_reason,reviewed_by,reviewed_at)
                VALUES(?,?,?,'STUDENT','申请人','投影测试','2027-06-30',?,?,?, ?,?)
                """, id, userId, campusId, materialId, status,
                reviewedAt == null ? null : "人工审核", reviewedAt == null ? null : ADMIN,
                reviewedAt == null ? null : Timestamp.from(reviewedAt));
        return id;
    }

    private String attachedMaterial(String owner) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO credential_material
                    (id,owner_user_id,object_key,content_type,size_bytes,status,uploaded_at,attached_at)
                VALUES(?,?,?,'image/png',8,'ATTACHED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, id, owner, "credential-materials/" + UUID.randomUUID());
        return id;
    }

    private void attempt(String verificationId, int attemptNo, String status, String flags) {
        boolean completed = "PASSED".equals(status) || "MANUAL_REVIEW_REQUIRED".equals(status) || "UNAVAILABLE".equals(status);
        boolean unavailable = "UNAVAILABLE".equals(status);
        String taskId = completed && !unavailable ? UUID.randomUUID().toString() : null;
        jdbc.update("""
                INSERT INTO credential_precheck_attempt
                    (id,verification_id,attempt_no,intelligence_task_id,status,provider,model_name,
                     overall_confidence,extracted_school_name,extracted_person_name,credential_type,
                     consistency_flags,summary,error_category,created_at,started_at,completed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?,?)
                """, UUID.randomUUID().toString(), verificationId, attemptNo, taskId, status,
                completed && !unavailable ? "test-provider" : null,
                completed && !unavailable ? "test-model" : null,
                completed && !unavailable ? new java.math.BigDecimal("0.950") : null,
                completed && !unavailable ? "测试大学" : null,
                completed && !unavailable ? "申请人" : null,
                completed && !unavailable ? "CAMPUS_CREDENTIAL" : null,
                flags,
                unavailable ? "Credential precheck is temporarily unavailable."
                        : completed ? "AI advisory only; governance review remains authoritative." : null,
                unavailable ? "INTELLIGENCE_UNAVAILABLE" : null,
                "PENDING".equals(status) ? null : Timestamp.from(Instant.now()),
                completed ? Timestamp.from(Instant.now()) : null);
    }

    private String formalStatus(String verificationId) {
        return jdbc.queryForObject("SELECT status FROM campus_verification WHERE id=?", String.class, verificationId);
    }

    private void user(String id, String displayName, String role) {
        jdbc.update("INSERT INTO `user`(id,display_name,account_status,system_role) VALUES(?,?, 'ACTIVE', ?)",
                id, displayName, role);
    }
}
