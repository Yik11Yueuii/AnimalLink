package org.animallink.adoption;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.animallink.adoption.infrastructure.AdoptionCompletedOutboxPublisher;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdoptionApplicationIntegrationTest {
    static final String ADMIN = "00000000-0000-0000-0000-000000000002";
    static final String SECOND_ADMIN = "00000000-0000-0000-0000-000000000013";
    static final String APPLICANT = "00000000-0000-0000-0000-000000000011";
    static final String UNRELATED = "00000000-0000-0000-0000-000000000014";
    static final String NO_MEMBERSHIP = "00000000-0000-0000-0000-000000000012";
    static final Stub STUB = new Stub(); static final TestDatabase DATABASE = TestDatabase.create();
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::url); r.add("spring.datasource.username", DATABASE::username); r.add("spring.datasource.password", DATABASE::password);
        r.add("spring.cloud.nacos.discovery.enabled", () -> false); r.add("spring.cloud.nacos.config.enabled", () -> false);
        r.add("animallink.identity.base-url", STUB::url); r.add("animallink.animal.base-url", STUB::url);
        r.add("animallink.minio.endpoint", () -> "http://localhost:9000"); r.add("animallink.minio.access-key", () -> "a"); r.add("animallink.minio.secret-key", () -> "b");
        r.add("animallink.messaging.enabled", () -> false);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager transactions; @Autowired ObjectMapper objectMapper;
    @BeforeEach void clean() { jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM adoption_relation"); jdbc.update("DELETE FROM adoption_handover"); jdbc.update("DELETE FROM adoption_selection"); jdbc.update("DELETE FROM adoption_application"); jdbc.update("DELETE FROM adoption_listing"); STUB.animals.clear(); STUB.unavailable = false; }
    @AfterAll static void stop() { STUB.stop(); DATABASE.close(); }

    @Test void submitEligibilityListingStateAndSelfApplicationAreEnforced() throws Exception {
        String draft = listing(ADMIN, false); String published = listing(ADMIN, true);
        mvc.perform(submit(published, null)).andExpect(status().isForbidden());
        mvc.perform(submit(published, NO_MEMBERSHIP)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("APPLICANT_NOT_ELIGIBLE"));
        mvc.perform(submit(draft, APPLICANT)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LISTING_NOT_OPEN_FOR_APPLICATION"));
        mvc.perform(submit(published, ADMIN)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("APPLICANT_NOT_ELIGIBLE"));
        mvc.perform(submit(published, APPLICANT)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUBMITTED"));
    }
    @Test void duplicateAndConcurrentSubmitAreProtectedAndWithdrawalAllowsReapply() throws Exception {
        String listing = listing(ADMIN, true); String first = applicationId(listing, APPLICANT);
        mvc.perform(submit(listing, APPLICANT)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLICATION_ALREADY_EXISTS"));
        mvc.perform(post("/api/v1/applications/{id}/withdraw", first).header("X-User-Id", APPLICANT)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        mvc.perform(post("/api/v1/applications/{id}/withdraw", first).header("X-User-Id", APPLICANT)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_APPLICATION_TRANSITION"));
        String second = applicationId(listing, APPLICANT); assertNotEquals(first, second);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM adoption_application WHERE listing_id=?", Integer.class, listing));
    }
    @Test void concurrentDuplicateSubmitAllowsOnlyOne() throws Exception {
        String listing = listing(ADMIN, true); CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> first = pool.submit(() -> concurrentSubmit(listing, ready, start)); Future<Integer> second = pool.submit(() -> concurrentSubmit(listing, ready, start));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
        int a = first.get(); int b = second.get(); pool.shutdown();
        assertEquals(1, (a == 201 ? 1 : 0) + (b == 201 ? 1 : 0)); assertEquals(1, (a == 409 ? 1 : 0) + (b == 409 ? 1 : 0));
    }
    @Test void detailHistoryPaginationOwnershipAndCloseHistoryAreEnforced() throws Exception {
        String listing = listing(ADMIN, true); String application = applicationId(listing, APPLICANT);
        mvc.perform(get("/api/v1/applications/{id}", application).header("X-User-Id", APPLICANT)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/applications/{id}", application).header("X-User-Id", UNRELATED)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("APPLICATION_NOT_OWNER"));
        mvc.perform(get("/api/v1/applications/{id}", application)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/applications?page=0&size=1").header("X-User-Id", APPLICANT)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(post("/api/v1/listings/{id}/close", listing).header("X-User-Id", ADMIN)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/applications/{id}", application).header("X-User-Id", APPLICANT)).andExpect(status().isOk());
        mvc.perform(submit(listing, UNRELATED)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LISTING_NOT_OPEN_FOR_APPLICATION"));
    }
    @Test void unavailableIdentityFailsClosed() throws Exception {
        String listing = listing(ADMIN, true); STUB.unavailable = true;
        mvc.perform(submit(listing, APPLICANT)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("APPLICANT_NOT_ELIGIBLE"));
    }
    @Test void governanceQueueDefaultsToSubmittedAndSupportsFiltersPaginationAndLimits() throws Exception {
        String firstListing = listing(ADMIN, true); String secondListing = listing(ADMIN, true);
        String first = applicationId(firstListing, APPLICANT); String withdrawn = applicationId(secondListing, APPLICANT);
        mvc.perform(post("/api/v1/applications/{id}/withdraw", withdrawn).header("X-User-Id", APPLICANT)).andExpect(status().isOk());
        String latest = applicationId(firstListing, UNRELATED);
        mvc.perform(governanceQueue(null, null, 0, 20)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].applicationId").value(latest)).andExpect(jsonPath("$.items[1].applicationId").value(first));
        mvc.perform(governanceQueue(firstListing, "SUBMITTED", 0, 1)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(governanceQueue(secondListing, "WITHDRAWN", 0, 20)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].applicationId").value(withdrawn));
        mvc.perform(governanceQueue(null, "SUBMITTED", 0, 101)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    @Test void governanceQueueAndDetailRequireAdminAndExposeReviewFields() throws Exception {
        String application = applicationId(listing(ADMIN, true), APPLICANT);
        mvc.perform(get("/api/v1/governance/applications").header("X-User-Id", APPLICANT)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
        mvc.perform(get("/api/v1/governance/applications/{id}", application).header("X-User-Id", APPLICANT)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
        mvc.perform(get("/api/v1/governance/applications/{id}", application).header("X-User-Id", ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.applicationId").value(application)).andExpect(jsonPath("$.reviewerUserId").isEmpty()).andExpect(jsonPath("$.reviewedAt").isEmpty());
    }
    @Test void approveSubmittedApplicationDoesNotCallAnimalOrMutateListing() throws Exception {
        String listing = listing(ADMIN, true); String application = applicationId(listing, APPLICANT); STUB.animalCalls = 0;
        mvc.perform(approve(application, "  适合继续推进  ")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.reviewerUserId").value(ADMIN)).andExpect(jsonPath("$.reviewedAt").exists()).andExpect(jsonPath("$.reviewComment").value("适合继续推进"));
        assertEquals(0, STUB.animalCalls);
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM adoption_listing WHERE id=?", String.class, listing));
        mvc.perform(get("/api/v1/applications/{id}", application).header("X-User-Id", APPLICANT)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.reviewerUserId").doesNotExist()).andExpect(jsonPath("$.reviewComment").value("适合继续推进"));
    }
    @Test void rejectSubmittedApplicationRequiresNonBlankReasonAndExposesDecision() throws Exception {
        String application = applicationId(listing(ADMIN, true), APPLICANT);
        mvc.perform(reject(application, " ")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(reject(application, "  材料不完整  ")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.reviewerUserId").value(ADMIN)).andExpect(jsonPath("$.reviewedAt").exists()).andExpect(jsonPath("$.reviewComment").value("材料不完整"));
    }
    @Test void allNonSubmittedReviewTransitionsAreRejected() throws Exception {
        String withdrawn = applicationId(listing(ADMIN, true), APPLICANT); mvc.perform(post("/api/v1/applications/{id}/withdraw", withdrawn).header("X-User-Id", APPLICANT)).andExpect(status().isOk());
        mvc.perform(approve(withdrawn, null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_APPLICATION_TRANSITION"));
        mvc.perform(reject(withdrawn, "x")).andExpect(status().isConflict());
        String approved = applicationId(listing(ADMIN, true), APPLICANT); mvc.perform(approve(approved, null)).andExpect(status().isOk());
        mvc.perform(approve(approved, null)).andExpect(status().isConflict()); mvc.perform(reject(approved, "x")).andExpect(status().isConflict());
        String rejected = applicationId(listing(ADMIN, true), APPLICANT); mvc.perform(reject(rejected, "x")).andExpect(status().isOk());
        mvc.perform(reject(rejected, "x")).andExpect(status().isConflict()); mvc.perform(approve(rejected, null)).andExpect(status().isConflict());
    }
    @Test void concurrentReviewSameApplicationAllowsExactlyOneDecision() throws Exception {
        String application = applicationId(listing(ADMIN, true), APPLICANT); CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> approve = pool.submit(() -> concurrent(approve(application, "approve"), ready, start)); Future<Integer> reject = pool.submit(() -> concurrent(reject(application, "reject"), ready, start));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown(); int a = approve.get(); int b = reject.get(); pool.shutdown();
        assertEquals(1, (a == 200 ? 1 : 0) + (b == 200 ? 1 : 0)); assertEquals(1, (a == 409 ? 1 : 0) + (b == 409 ? 1 : 0));
        String finalStatus = jdbc.queryForObject("SELECT status FROM adoption_application WHERE id=?", String.class, application); assertTrue("APPROVED".equals(finalStatus) || "REJECTED".equals(finalStatus));
        assertEquals(ADMIN, jdbc.queryForObject("SELECT reviewer_user_id FROM adoption_application WHERE id=?", String.class, application)); assertNotNull(jdbc.queryForObject("SELECT reviewed_at FROM adoption_application WHERE id=?", java.sql.Timestamp.class, application));
    }
    @RepeatedTest(10) void withdrawVsApproveRaceAllowsExactlyOneTerminalTransition() throws Exception {
        String application = applicationId(listing(ADMIN, true), APPLICANT); CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> withdraw = pool.submit(() -> concurrent(post("/api/v1/applications/{id}/withdraw", application).header("X-User-Id", APPLICANT), ready, start)); Future<Integer> approve = pool.submit(() -> concurrent(approve(application, null), ready, start));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown(); int a = withdraw.get(); int b = approve.get(); pool.shutdown();
        assertEquals(1, (a == 200 ? 1 : 0) + (b == 200 ? 1 : 0)); assertEquals(1, (a == 409 ? 1 : 0) + (b == 409 ? 1 : 0));
        String finalStatus = jdbc.queryForObject("SELECT status FROM adoption_application WHERE id=?", String.class, application); assertTrue("WITHDRAWN".equals(finalStatus) || "APPROVED".equals(finalStatus));
    }
    @Test void multipleApplicationsMayBeApprovedAndOneReviewDoesNotMutateOthers() throws Exception {
        String listing = listing(ADMIN, true); String first = applicationId(listing, APPLICANT); String second = applicationId(listing, UNRELATED);
        mvc.perform(approve(first, null)).andExpect(status().isOk());
        assertEquals("SUBMITTED", jdbc.queryForObject("SELECT status FROM adoption_application WHERE id=?", String.class, second));
        mvc.perform(approve(second, null)).andExpect(status().isOk());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM adoption_application WHERE listing_id=? AND status='APPROVED'", Integer.class, listing));
    }
    @Test void closedListingExistingApplicationRemainsReviewableAndApplicantBehaviorRemainsIntact() throws Exception {
        String listing = listing(ADMIN, true); String application = applicationId(listing, APPLICANT);
        mvc.perform(post("/api/v1/listings/{id}/close", listing).header("X-User-Id", ADMIN)).andExpect(status().isOk());
        mvc.perform(approve(application, null)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/v1/me/applications?page=0&size=20").header("X-User-Id", APPLICANT)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("APPROVED"));
    }
    @Test void governanceSelectsApprovedApplicationClosesListingAndPreservesOtherApplications() throws Exception {
        String listing = listing(ADMIN, true); String selected = applicationId(listing, APPLICANT); String other = applicationId(listing, UNRELATED);
        mvc.perform(approve(selected, null)).andExpect(status().isOk()); mvc.perform(approve(other, null)).andExpect(status().isOk()); STUB.animalCalls = 0;
        mvc.perform(select(listing, selected, "  最终候选人  ", ADMIN)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.listingId").value(listing)).andExpect(jsonPath("$.applicationId").value(selected))
                .andExpect(jsonPath("$.selectedApplicantUserId").value(APPLICANT)).andExpect(jsonPath("$.selectedByUserId").value(ADMIN))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.note").value("最终候选人"));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM adoption_listing WHERE id=?", String.class, listing));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM adoption_application WHERE id=?", String.class, other));
        assertEquals(0, STUB.animalCalls);
    }
    @Test void selectionEnforcesAuthorizationEligibilityAndListingOwnership() throws Exception {
        String listing = listing(ADMIN, true); String submitted = applicationId(listing, APPLICANT);
        mvc.perform(select(listing, submitted, null, APPLICANT)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
        mvc.perform(select(listing, submitted, null, ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLICATION_NOT_APPROVED"));
        mvc.perform(post("/api/v1/applications/{id}/withdraw", submitted).header("X-User-Id", APPLICANT)).andExpect(status().isOk());
        mvc.perform(select(listing, submitted, null, ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLICATION_NOT_APPROVED"));
        String rejected = applicationId(listing(ADMIN, true), APPLICANT); mvc.perform(reject(rejected, "不完整")).andExpect(status().isOk());
        mvc.perform(select(jdbc.queryForObject("SELECT listing_id FROM adoption_application WHERE id=?", String.class, rejected), rejected, null, ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLICATION_NOT_APPROVED"));
        String approved = applicationId(listing(ADMIN, true), APPLICANT); mvc.perform(approve(approved, null)).andExpect(status().isOk());
        mvc.perform(select(listing, approved, null, ADMIN)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    @Test void selectionIsAllowedForClosedListingButNotDraftAndReadIsGoverned() throws Exception {
        String closed = listing(ADMIN, true); String approved = applicationId(closed, APPLICANT); mvc.perform(approve(approved, null)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/listings/{id}/close", closed).header("X-User-Id", ADMIN)).andExpect(status().isOk());
        mvc.perform(select(closed, approved, null, ADMIN)).andExpect(status().isCreated());
        assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM adoption_listing WHERE id=?", String.class, closed));
        String draft = listing(ADMIN, false); String app = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO adoption_application(id,listing_id,applicant_user_id,status,message,created_at,updated_at,withdrawn_at,reviewer_user_id,reviewed_at,review_comment) VALUES(?,?,?,'APPROVED','x',NOW(6),NOW(6),NULL,?,NOW(6),NULL)", app, draft, APPLICANT, ADMIN);
        mvc.perform(select(draft, app, null, ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LISTING_NOT_SELECTABLE"));
        mvc.perform(get("/api/v1/governance/listings/{id}/selection", draft).header("X-User-Id", ADMIN)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SELECTION_NOT_FOUND"));
        mvc.perform(get("/api/v1/governance/listings/{id}/selection", closed).header("X-User-Id", APPLICANT)).andExpect(status().isForbidden());
    }
    @Test void sameSelectionRetryIsIdempotentAndDifferentSelectionConflicts() throws Exception {
        String listing = listing(ADMIN, true); String first = applicationId(listing, APPLICANT); String second = applicationId(listing, UNRELATED);
        mvc.perform(approve(first, null)).andExpect(status().isOk()); mvc.perform(approve(second, null)).andExpect(status().isOk());
        String firstId = id(mvc.perform(select(listing, first, null, ADMIN)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "selectionId");
        mvc.perform(select(listing, first, null, SECOND_ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.selectionId").value(firstId));
        mvc.perform(select(listing, second, null, ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LISTING_ALREADY_SELECTED"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM adoption_selection WHERE listing_id=? AND status='ACTIVE'", Integer.class, listing));
    }
    @RepeatedTest(10) void concurrentDifferentSelectionsProduceOneActiveSelection() throws Exception {
        String listing = listing(ADMIN, true); String first = applicationId(listing, APPLICANT); String second = applicationId(listing, UNRELATED);
        mvc.perform(approve(first, null)).andExpect(status().isOk()); mvc.perform(approve(second, null)).andExpect(status().isOk());
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> a = pool.submit(() -> concurrent(select(listing, first, null, ADMIN), ready, start)); Future<Integer> b = pool.submit(() -> concurrent(select(listing, second, null, SECOND_ADMIN), ready, start));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown(); int one = a.get(); int two = b.get(); pool.shutdown();
        assertEquals(1, (one == 201 ? 1 : 0) + (two == 201 ? 1 : 0)); assertEquals(1, (one == 409 ? 1 : 0) + (two == 409 ? 1 : 0));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM adoption_selection WHERE listing_id=? AND status='ACTIVE'", Integer.class, listing));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM adoption_listing WHERE id=?", String.class, listing));
    }
    @Test void concurrentSameSelectionConvergesToOneSelectionAndApplicantViewsOnlyExposeFlag() throws Exception {
        String listing = listing(ADMIN, true); String selected = applicationId(listing, APPLICANT); String other = applicationId(listing, UNRELATED);
        mvc.perform(approve(selected, null)).andExpect(status().isOk()); mvc.perform(approve(other, null)).andExpect(status().isOk());
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> a = pool.submit(() -> concurrent(select(listing, selected, null, ADMIN), ready, start)); Future<Integer> b = pool.submit(() -> concurrent(select(listing, selected, null, SECOND_ADMIN), ready, start));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown(); int one = a.get(); int two = b.get(); pool.shutdown();
        assertTrue((one == 201 && two == 200) || (one == 200 && two == 201));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM adoption_selection WHERE listing_id=?", Integer.class, listing));
        mvc.perform(get("/api/v1/applications/{id}", selected).header("X-User-Id", APPLICANT)).andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(true)).andExpect(jsonPath("$.selectionId").doesNotExist()).andExpect(jsonPath("$.selectedByUserId").doesNotExist()).andExpect(jsonPath("$.note").doesNotExist());
        mvc.perform(get("/api/v1/applications/{id}", other).header("X-User-Id", UNRELATED)).andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(false));
        mvc.perform(get("/api/v1/me/applications").header("X-User-Id", APPLICANT)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].selected").value(true));
        mvc.perform(get("/api/v1/governance/listings/{id}/selection", listing).header("X-User-Id", ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.applicationId").value(selected));
    }
    @Test void selectionVsManualCloseRaceLeavesActiveSelectionAndClosedListing() throws Exception {
        String listing = listing(ADMIN, true); String application = applicationId(listing, APPLICANT); mvc.perform(approve(application, null)).andExpect(status().isOk());
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> selection = pool.submit(() -> concurrent(select(listing, application, null, ADMIN), ready, start));
        Future<Integer> close = pool.submit(() -> concurrent(post("/api/v1/listings/{id}/close", listing).header("X-User-Id", SECOND_ADMIN), ready, start));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
        assertEquals(201, selection.get()); assertTrue(close.get() == 200 || close.get() == 409); pool.shutdown();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM adoption_selection WHERE listing_id=? AND status='ACTIVE'", Integer.class, listing));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM adoption_listing WHERE id=?", String.class, listing));
    }
    @Test void v5DatabaseConstraintsEnforceCompositeOwnershipActiveUniquenessAndMetadata() throws Exception {
        String firstListing = listing(ADMIN, true); String first = applicationId(firstListing, APPLICANT); mvc.perform(approve(first, null)).andExpect(status().isOk());
        String secondListing = listing(ADMIN, true); String second = applicationId(secondListing, UNRELATED); mvc.perform(approve(second, null)).andExpect(status().isOk());
        assertThrows(RuntimeException.class, () -> insertActiveSelection(UUID.randomUUID().toString(), firstListing, second));
        insertActiveSelection(UUID.randomUUID().toString(), firstListing, first);
        assertThrows(RuntimeException.class, () -> insertActiveSelection(UUID.randomUUID().toString(), firstListing, first));
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO adoption_selection(id,listing_id,application_id,status,selected_by_user_id,selected_at,cancelled_at,cancel_reason) VALUES(?,?,?,'ACTIVE',?,NOW(6),NOW(6),'invalid')", UUID.randomUUID().toString(), secondListing, second, ADMIN));
    }
    @Test void governanceHandoverCompletionCreatesRelationAndExactOutbox() throws Exception {
        String listing=listing(ADMIN,true), application=applicationId(listing,APPLICANT); mvc.perform(approve(application,null)).andExpect(status().isOk());
        String selection=id(mvc.perform(select(listing,application,null,ADMIN)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"selectionId");
        String handover=id(mvc.perform(initiate(selection,ADMIN)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse().getContentAsString(),"handoverId");
        mvc.perform(initiate(selection,ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.handoverId").value(handover));
        mvc.perform(get("/api/v1/me/handovers").header("X-User-Id",APPLICANT)).andExpect(status().isOk()).andExpect(jsonPath("$[0].handoverId").value(handover)).andExpect(jsonPath("$[0].note").doesNotExist());
        mvc.perform(post("/api/v1/governance/handovers/{id}/complete",handover).header("X-User-Id",ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/v1/governance/handovers/{id}/complete",handover).header("X-User-Id",ADMIN)).andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM adoption_relation WHERE handover_id=? AND status='ACTIVE'",Integer.class,handover));
        Map<String,Object> event=jdbc.queryForMap("SELECT id,payload_json,status FROM outbox_event");assertEquals("PENDING",event.get("status"));Map<String,Object> payload=objectMapper.readValue(event.get("payload_json").toString(),new TypeReference<>(){});assertEquals(java.util.Set.of("eventId","eventType","eventVersion","occurredAt","relationId","handoverId","animalId"),payload.keySet());assertEquals(event.get("id"),payload.get("eventId"));assertEquals("ADOPTION_COMPLETED",payload.get("eventType"));assertEquals(1,payload.get("eventVersion"));assertEquals(handover,payload.get("handoverId"));assertEquals(jdbc.queryForObject("SELECT id FROM adoption_relation WHERE handover_id=?",String.class,handover),payload.get("relationId"));assertEquals(jdbc.queryForObject("SELECT animal_id FROM adoption_listing WHERE id=?",String.class,listing),payload.get("animalId"));
    }
    @RepeatedTest(10) void cancelVsCompleteHasOneTerminalOutcomeAndCancelledSelectionCanReselect() throws Exception {
        String listing=listing(ADMIN,true), first=applicationId(listing,APPLICANT), second=applicationId(listing,UNRELATED);mvc.perform(approve(first,null)).andExpect(status().isOk());mvc.perform(approve(second,null)).andExpect(status().isOk());
        String selection=id(mvc.perform(select(listing,first,null,ADMIN)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"selectionId");String handover=id(mvc.perform(initiate(selection,ADMIN)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"handoverId");
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);Future<Integer> complete=pool.submit(()->concurrent(post("/api/v1/governance/handovers/{id}/complete",handover).header("X-User-Id",ADMIN),ready,start));Future<Integer> cancel=pool.submit(()->concurrent(post("/api/v1/governance/handovers/{id}/cancel",handover).header("X-User-Id",SECOND_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"),ready,start));assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));start.countDown();int a=complete.get(),b=cancel.get();pool.shutdown();assertEquals(1,(a==200?1:0)+(b==200?1:0));assertEquals(1,(a==409?1:0)+(b==409?1:0));String state=jdbc.queryForObject("SELECT status FROM adoption_handover WHERE id=?",String.class,handover);if("CANCELLED".equals(state)){assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM adoption_selection WHERE id=?",String.class,selection));mvc.perform(select(listing,second,null,ADMIN)).andExpect(status().isCreated());}else{assertEquals("COMPLETED",state);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM adoption_relation WHERE handover_id=?",Integer.class,handover));}
    }
    @Test void handoverValidationAuthorizationReadPrivacyAndCancellationIdempotency() throws Exception {
        String listing=listing(ADMIN,true), selected=applicationId(listing,APPLICANT), other=applicationId(listing,UNRELATED);mvc.perform(approve(selected,null)).andExpect(status().isOk());mvc.perform(approve(other,null)).andExpect(status().isOk());
        String selection=id(mvc.perform(select(listing,selected,null,ADMIN)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"selectionId");
        mvc.perform(post("/api/v1/governance/selections/{id}/handover",selection).header("X-User-Id",ADMIN).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/governance/selections/{id}/handover",selection).header("X-User-Id",ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\":\"2020-01-01T00:00:00Z\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(initiate(selection,APPLICANT)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
        mvc.perform(get("/api/v1/governance/selections/{id}/handover",selection).header("X-User-Id",ADMIN)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HANDOVER_NOT_FOUND"));
        String handover=id(mvc.perform(initiate(selection,ADMIN)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"handoverId");
        mvc.perform(get("/api/v1/governance/selections/{id}/handover",selection).header("X-User-Id",ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.selectedApplicantUserId").value(APPLICANT));
        mvc.perform(get("/api/v1/me/handovers").header("X-User-Id",APPLICANT)).andExpect(status().isOk()).andExpect(jsonPath("$[0].handoverId").value(handover)).andExpect(jsonPath("$[0].selectionId").doesNotExist()).andExpect(jsonPath("$[0].note").doesNotExist());
        mvc.perform(get("/api/v1/me/handovers").header("X-User-Id",UNRELATED)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/governance/handovers/{id}/cancel",handover).header("X-User-Id",ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/governance/handovers/{id}/cancel",handover).header("X-User-Id",ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  changed mind  \"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelReason").value("changed mind"));
        mvc.perform(post("/api/v1/governance/handovers/{id}/cancel",handover).header("X-User-Id",SECOND_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"different\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.cancelReason").value("changed mind"));
        assertEquals("CLOSED",jdbc.queryForObject("SELECT status FROM adoption_listing WHERE id=?",String.class,listing));assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM adoption_application WHERE id=?",String.class,other));
        mvc.perform(initiate(selection,ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELECTION_NOT_ACTIVE"));
    }
    @RepeatedTest(10) void concurrentSameSelectionInitiationConvergesToOnePendingHandover() throws Exception {
        String listing=listing(ADMIN,true), application=applicationId(listing,APPLICANT);mvc.perform(approve(application,null)).andExpect(status().isOk());String selection=id(mvc.perform(select(listing,application,null,ADMIN)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"selectionId");
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);Future<Integer> first=pool.submit(()->concurrent(initiate(selection,ADMIN),ready,start));Future<Integer> second=pool.submit(()->concurrent(initiate(selection,SECOND_ADMIN),ready,start));assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));start.countDown();int a=first.get(),b=second.get();pool.shutdown();assertEquals(1,(a==201?1:0)+(b==201?1:0));assertEquals(1,(a==200?1:0)+(b==200?1:0));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM adoption_handover WHERE selection_id=?",Integer.class,selection));
    }
    @Test void outboxPublisherConfirmsPublishesAndRetriesWithoutMutatingDomainState() {
        String id=UUID.randomUUID().toString(), relation=UUID.randomUUID().toString();jdbc.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,available_at,created_at,updated_at) VALUES(?,'ADOPTION_RELATION',?,'ADOPTION_COMPLETED',CAST(? AS JSON),'PENDING',NOW(6),NOW(6),NOW(6))",id,relation,"{\"eventId\":\""+id+"\",\"eventType\":\"ADOPTION_COMPLETED\",\"eventVersion\":1}");
        RabbitTemplate rabbit=org.mockito.Mockito.mock(RabbitTemplate.class);org.mockito.Mockito.doAnswer(i->{CorrelationData c=i.getArgument(4);c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).convertAndSend(org.mockito.ArgumentMatchers.eq("animallink.domain"),org.mockito.ArgumentMatchers.eq("adoption.handover.completed"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(CorrelationData.class));
        assertEquals(1,new AdoptionCompletedOutboxPublisher(jdbc,rabbit,new TransactionTemplate(transactions)).publishBatch());assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,id));
        String retry=UUID.randomUUID().toString();jdbc.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,available_at,created_at,updated_at) VALUES(?,'ADOPTION_RELATION',?,'ADOPTION_COMPLETED',CAST(? AS JSON),'PENDING',NOW(6),NOW(6),NOW(6))",retry,UUID.randomUUID().toString(),"{\"eventId\":\""+retry+"\"}");RabbitTemplate failing=org.mockito.Mockito.mock(RabbitTemplate.class);org.mockito.Mockito.doThrow(new RuntimeException("transient")).when(failing).convertAndSend(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(CorrelationData.class));
        assertEquals(0,new AdoptionCompletedOutboxPublisher(jdbc,failing,new TransactionTemplate(transactions)).publishBatch());assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,retry));assertEquals(1,jdbc.queryForObject("SELECT attempt_count FROM outbox_event WHERE id=?",Integer.class,retry));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM adoption_relation",Integer.class));
    }
    private String listing(String actor, boolean publish) throws Exception { String animal = UUID.randomUUID().toString(); STUB.animals.put(animal, "OPEN"); String body = mvc.perform(post("/api/v1/listings").header("X-User-Id", actor).contentType(MediaType.APPLICATION_JSON).content("{\"animalId\":\"" + animal + "\",\"title\":\"领养信息\",\"description\":\"寻找家庭\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(); String id = id(body, "listingId"); if (publish) mvc.perform(post("/api/v1/listings/{id}/publish", id).header("X-User-Id", actor)).andExpect(status().isOk()); return id; }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(String listing, String actor) { var request = post("/api/v1/listings/{id}/applications", listing).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"我愿意领养\"}"); if (actor != null) request.header("X-User-Id", actor); return request; }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder governanceQueue(String listing, String status, int page, int size) { var request = get("/api/v1/governance/applications").header("X-User-Id", ADMIN).param("page", String.valueOf(page)).param("size", String.valueOf(size)); if (listing != null) request.param("listingId", listing); if (status != null) request.param("status", status); return request; }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder approve(String application, String comment) { return post("/api/v1/governance/applications/{id}/approve", application).header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON).content(comment == null ? "{}" : "{\"comment\":\"" + comment + "\"}"); }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reject(String application, String reason) { return post("/api/v1/governance/applications/{id}/reject", application).header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON).content(reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}"); }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder select(String listing, String application, String note, String actor) { return post("/api/v1/governance/listings/{id}/selection", listing).header("X-User-Id", actor).contentType(MediaType.APPLICATION_JSON).content("{\"applicationId\":\"" + application + "\"" + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}"); }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder initiate(String selection,String actor){return post("/api/v1/governance/selections/{id}/handover",selection).header("X-User-Id",actor).contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\":\"2030-01-01T10:00:00Z\"}");}
    private String applicationId(String listing, String actor) throws Exception { return id(mvc.perform(submit(listing, actor)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "applicationId"); }
    private void insertActiveSelection(String id, String listing, String application) { jdbc.update("INSERT INTO adoption_selection(id,listing_id,application_id,status,selected_by_user_id,selected_at) VALUES(?,?,?,'ACTIVE',?,NOW(6))", id, listing, application, ADMIN); }
    private String id(String body, String field) { Matcher m = Pattern.compile("\\\"" + field + "\\\":\\\"([0-9a-fA-F-]{36})\\\"").matcher(body); assertTrue(m.find()); return m.group(1); }
    private int concurrentSubmit(String listing, CountDownLatch ready, CountDownLatch start) throws Exception { ready.countDown(); start.await(); return mvc.perform(submit(listing, APPLICANT)).andReturn().getResponse().getStatus(); }
    private int concurrent(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, CountDownLatch ready, CountDownLatch start) throws Exception { ready.countDown(); start.await(); return mvc.perform(request).andReturn().getResponse().getStatus(); }
    static class Stub {
        final Map<String, String> animals = new ConcurrentHashMap<>(); volatile boolean unavailable; volatile int animalCalls; final HttpServer server;
        Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/api/v1/users/me", this::user); server.createContext("/api/v1/animals", this::animal); server.start(); } catch (IOException e) { throw new RuntimeException(e); } }
        String url() { return "http://localhost:" + server.getAddress().getPort(); } void stop() { server.stop(0); }
        void user(HttpExchange e) throws IOException { if (unavailable) { reply(e, 503, "{}"); return; } String id = e.getRequestHeaders().getFirst("X-User-Id"); if (id == null) { reply(e, 401, "{}"); return; } if (e.getRequestURI().getPath().endsWith("campus-memberships")) { reply(e, 200, NO_MEMBERSHIP.equals(id) ? "[]" : "[{\"status\":\"ACTIVE\"}]"); return; } reply(e, 200, "{\"id\":\"" + id + "\",\"accountStatus\":\"ACTIVE\",\"systemRole\":\"" + ((ADMIN.equals(id) || SECOND_ADMIN.equals(id)) ? "GOVERNANCE_ADMIN" : "USER") + "\"}"); }
        void animal(HttpExchange e) throws IOException { animalCalls++; String id = e.getRequestURI().getPath().substring("/api/v1/animals/".length()); String status = animals.get(id); if (status == null) { reply(e, 404, "{}"); return; } reply(e, 200, "{\"id\":\"" + id + "\",\"adoptionStatus\":\"" + status + "\"}"); }
        void reply(HttpExchange e, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close(); }
    }
    static class TestDatabase {
        final String url, username, password; final MySQLContainer<?> container;
        TestDatabase(String url, String username, String password, MySQLContainer<?> container) { this.url = url; this.username = username; this.password = password; this.container = container; }
        static TestDatabase create() {
            if ("true".equalsIgnoreCase(System.getenv("ANIMALLINK_TEST_EXTERNAL_MYSQL"))) {
                int port = localPort(); String database = required("ANIMALLINK_TEST_MYSQL_DATABASE");
                return new TestDatabase("jdbc:mysql://127.0.0.1:" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC", required("ANIMALLINK_TEST_MYSQL_USERNAME"), required("ANIMALLINK_TEST_MYSQL_PASSWORD"), null);
            }
            MySQLContainer<?> c = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("adoption_application_test").withUsername("test").withPassword("test"); c.start(); return new TestDatabase(c.getJdbcUrl(), c.getUsername(), c.getPassword(), c);
        }
        private static int localPort() { try { int port = Integer.parseInt(required("ANIMALLINK_TEST_MYSQL_PORT")); if (port < 1 || port > 65535) throw new IllegalArgumentException(); return port; } catch (IllegalArgumentException e) { throw new IllegalStateException("ANIMALLINK_TEST_MYSQL_PORT must be a valid localhost port"); } }
        private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for the localhost external MySQL fallback"); return value; }
        String url() { return url; } String username() { return username; } String password() { return password; } void close() { if (container != null) container.stop(); }
    }
}
