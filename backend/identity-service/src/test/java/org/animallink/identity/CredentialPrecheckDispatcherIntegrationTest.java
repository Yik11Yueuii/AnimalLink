package org.animallink.identity;

import org.animallink.identity.application.CampusVerificationReviewService;
import org.animallink.identity.application.CredentialPrecheckClient;
import org.animallink.identity.application.CredentialPrecheckClientException;
import org.animallink.identity.application.CredentialPrecheckDispatcher;
import org.animallink.identity.domain.AccountStatus;
import org.animallink.identity.domain.SystemRole;
import org.animallink.identity.domain.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CredentialPrecheckDispatcherIntegrationTest {
    private static final String USER = "81000000-0000-0000-0000-000000000001";
    private static final String ADMIN = "81000000-0000-0000-0000-000000000002";
    private static final String CAMPUS = "82000000-0000-0000-0000-000000000001";
    private static final String TASK = "83000000-0000-0000-0000-000000000001";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_identity_dispatch_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CredentialPrecheckDispatcher dispatcher;
    @Autowired CampusVerificationReviewService reviewService;
    @Autowired MockMvc mvc;
    @MockBean CredentialPrecheckClient client;

    @BeforeEach
    void resetDatabase() {
        reset(client);
        jdbc.update("DELETE FROM volunteer_membership");
        jdbc.update("DELETE FROM campus_membership");
        jdbc.update("DELETE FROM credential_precheck_attempt");
        jdbc.update("DELETE FROM campus_verification");
        jdbc.update("DELETE FROM credential_material");
        jdbc.update("DELETE FROM campus");
        jdbc.update("DELETE FROM `user`");
        user(USER, "申请人", "USER"); user(ADMIN, "管理员", "GOVERNANCE_ADMIN");
        jdbc.update("INSERT INTO campus(id,name,city,status) VALUES(?,?, '测试市','ACTIVE')", CAMPUS, "测试大学");
    }

    @Test
    void pendingAttemptIsClaimedAndPassedResultWritesBackWithoutFormalMutation() {
        AttemptFixture fixture = pendingAttempt();
        when(client.precheck(any())).thenReturn(result("PASSED", TASK));

        dispatcher.dispatchPending();

        ArgumentCaptor<CredentialPrecheckClient.CredentialPrecheckRequest> command = ArgumentCaptor.forClass(CredentialPrecheckClient.CredentialPrecheckRequest.class);
        verify(client, times(1)).precheck(command.capture());
        assertThat(command.getValue()).extracting(CredentialPrecheckClient.CredentialPrecheckRequest::attemptId,
                        CredentialPrecheckClient.CredentialPrecheckRequest::verificationId,
                        CredentialPrecheckClient.CredentialPrecheckRequest::applicantUserId,
                        CredentialPrecheckClient.CredentialPrecheckRequest::campusId,
                        CredentialPrecheckClient.CredentialPrecheckRequest::campusName,
                        CredentialPrecheckClient.CredentialPrecheckRequest::applicantName,
                        CredentialPrecheckClient.CredentialPrecheckRequest::requestedMembershipType,
                        CredentialPrecheckClient.CredentialPrecheckRequest::expectedGraduationDate)
                .containsExactly(fixture.attemptId(), fixture.verificationId(), USER, CAMPUS, "测试大学", "申请人", "STUDENT", LocalDate.of(2027, 6, 30));
        assertThat(attempt(fixture.attemptId(), "status")).isEqualTo("PASSED");
        assertThat(attempt(fixture.attemptId(), "intelligence_task_id")).isEqualTo(TASK);
        assertThat(attempt(fixture.attemptId(), "provider")).isEqualTo("test-provider");
        assertThat(attempt(fixture.attemptId(), "model_name")).isEqualTo("test-model");
        assertThat((BigDecimal) attempt(fixture.attemptId(), "overall_confidence")).isEqualByComparingTo("0.950");
        assertThat(attempt(fixture.attemptId(), "consistency_flags")).isEqualTo("[]");
        assertThat(attempt(fixture.attemptId(), "completed_at")).isNotNull();
        assertThat(verificationStatus(fixture.verificationId())).isEqualTo("PENDING_REVIEW");
        assertThat(membershipCount()).isZero();
    }

    @Test
    void manualReviewAndUnavailableResultsKeepFormalIdentityStateUntouched() {
        AttemptFixture manual = pendingAttempt();
        when(client.precheck(any())).thenReturn(result("MANUAL_REVIEW_REQUIRED", TASK));
        dispatcher.dispatchPending();
        assertThat(attempt(manual.attemptId(), "status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(verificationStatus(manual.verificationId())).isEqualTo("PENDING_REVIEW");
        assertThat(membershipCount()).isZero();

        reset(client);
        rejectDirect(manual.verificationId());
        AttemptFixture unavailable = pendingAttempt();
        when(client.precheck(any())).thenReturn(result("UNAVAILABLE", UUID.randomUUID().toString()));
        dispatcher.dispatchPending();
        assertThat(attempt(unavailable.attemptId(), "status")).isEqualTo("UNAVAILABLE");
        assertThat(verificationStatus(unavailable.verificationId())).isEqualTo("PENDING_REVIEW");
        assertThat(membershipCount()).isZero();
    }

    @Test
    void transportAndMalformedResultsBecomeUnavailableWithoutLeavingProcessing() {
        AttemptFixture transport = pendingAttempt();
        when(client.precheck(any())).thenThrow(new CredentialPrecheckClientException("INTELLIGENCE_TIMEOUT"));
        dispatcher.dispatchPending();
        assertThat(attempt(transport.attemptId(), "status")).isEqualTo("UNAVAILABLE");
        assertThat(attempt(transport.attemptId(), "error_category")).isEqualTo("INTELLIGENCE_TIMEOUT");
        assertThat(verificationStatus(transport.verificationId())).isEqualTo("PENDING_REVIEW");

        reset(client);
        rejectDirect(transport.verificationId());
        AttemptFixture malformed = pendingAttempt();
        when(client.precheck(any())).thenReturn(new CredentialPrecheckClient.CredentialPrecheckResult(
                null, "UNKNOWN", null, null, null, null, null, null, List.of(), null, null));
        dispatcher.dispatchPending();
        assertThat(attempt(malformed.attemptId(), "status")).isEqualTo("UNAVAILABLE");
        assertThat(attempt(malformed.attemptId(), "error_category")).isEqualTo("INTELLIGENCE_CONTRACT_ERROR");
        assertThat(membershipCount()).isZero();
    }

    @Test
    void atomicClaimPreventsDuplicateDispatchAndCompletedAttemptIsNotSelectedAgain() throws Exception {
        AttemptFixture fixture = pendingAttempt();
        when(client.precheck(any())).thenReturn(result("PASSED", TASK));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(executor.submit(() -> { start.await(); dispatcher.dispatchAttempt(fixture.attemptId()); return null; }),
                    executor.submit(() -> { start.await(); dispatcher.dispatchAttempt(fixture.attemptId()); return null; }));
            start.countDown();
            for (Future<?> future : futures) future.get();
        } finally {
            executor.shutdownNow();
        }
        verify(client, times(1)).precheck(any());
        dispatcher.dispatchPending();
        verify(client, times(1)).precheck(any());
        assertThat(attempt(fixture.attemptId(), "status")).isEqualTo("PASSED");
    }

    @Test
    void submissionCreatesPendingAttemptButNeverCallsIntelligenceSynchronously() throws Exception {
        String materialId = readyMaterial();
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campusId\":\"%s\",\"requestedMembershipType\":\"STUDENT\",\"applicantName\":\"申请人\",\"materialMediaId\":\"%s\"}".formatted(CAMPUS, materialId)))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_precheck_attempt WHERE status='PENDING'", Integer.class)).isEqualTo(1);
        verifyNoInteractions(client);
    }

    @Test
    void approvedAndRejectedFormalReviewsRemainIndependentFromLaterPrecheckCompletion() {
        AttemptFixture approved = pendingAttempt();
        reviewService.approve(approved.verificationId(), admin(), "治理审批");
        when(client.precheck(any())).thenReturn(result("PASSED", TASK));
        dispatcher.dispatchPending();
        assertThat(verificationStatus(approved.verificationId())).isEqualTo("APPROVED");
        assertThat(membershipCount()).isEqualTo(1);
        assertThat(attempt(approved.attemptId(), "status")).isEqualTo("PASSED");

        reset(client);
        AttemptFixture rejected = pendingAttempt();
        reviewService.reject(rejected.verificationId(), admin(), "治理拒绝");
        when(client.precheck(any())).thenReturn(result("MANUAL_REVIEW_REQUIRED", UUID.randomUUID().toString()));
        dispatcher.dispatchPending();
        assertThat(verificationStatus(rejected.verificationId())).isEqualTo("REJECTED");
        assertThat(membershipCount()).isEqualTo(1);
        assertThat(attempt(rejected.attemptId(), "status")).isEqualTo("MANUAL_REVIEW_REQUIRED");
    }

    private AttemptFixture pendingAttempt() {
        String material = attachedMaterial();
        String verification = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO campus_verification(id,user_id,campus_id,requested_membership_type,applicant_name,expected_graduation_date,material_media_id,status)
                VALUES(?,?,?,'STUDENT','申请人','2027-06-30',?,'PENDING_REVIEW')
                """, verification, USER, CAMPUS, material);
        String attempt = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO credential_precheck_attempt(id,verification_id,attempt_no,status) VALUES(?,?,1,'PENDING')", attempt, verification);
        return new AttemptFixture(attempt, verification);
    }

    private String attachedMaterial() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO credential_material(id,owner_user_id,object_key,content_type,size_bytes,status,uploaded_at,attached_at)
                VALUES(?,?,?,'image/png',10,'ATTACHED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, id, USER, "credential-materials/" + UUID.randomUUID());
        return id;
    }

    private String readyMaterial() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO credential_material(id,owner_user_id,object_key,content_type,size_bytes,status,uploaded_at)
                VALUES(?,?,?,'image/png',10,'READY',CURRENT_TIMESTAMP(6))
                """, id, USER, "credential-materials/" + UUID.randomUUID());
        return id;
    }

    private CredentialPrecheckClient.CredentialPrecheckResult result(String status, String taskId) {
        return new CredentialPrecheckClient.CredentialPrecheckResult(taskId, status, "test-provider", "test-model",
                new BigDecimal("0.950"), "测试大学", "申请人", "CAMPUS_CREDENTIAL", List.of(), "safe advisory", null);
    }

    private Object attempt(String id, String column) { return jdbc.queryForObject("SELECT " + column + " FROM credential_precheck_attempt WHERE id=?", Object.class, id); }
    private String verificationStatus(String id) { return jdbc.queryForObject("SELECT status FROM campus_verification WHERE id=?", String.class, id); }
    private int membershipCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM campus_membership", Integer.class); }
    private void rejectDirect(String verificationId) { jdbc.update("UPDATE campus_verification SET status='REJECTED' WHERE id=?", verificationId); }
    private void user(String id, String name, String role) { jdbc.update("INSERT INTO `user`(id,display_name,account_status,system_role) VALUES(?,?, 'ACTIVE', ?)", id, name, role); }
    private UserAccount admin() { Instant now = Instant.now(); return new UserAccount(ADMIN, "管理员", AccountStatus.ACTIVE, SystemRole.GOVERNANCE_ADMIN, now, now); }
    private record AttemptFixture(String attemptId, String verificationId) { }
}
