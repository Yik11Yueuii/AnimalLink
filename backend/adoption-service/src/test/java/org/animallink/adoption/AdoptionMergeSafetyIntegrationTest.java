package org.animallink.adoption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdoptionMergeSafetyIntegrationTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String APPLICANT = "00000000-0000-0000-0000-000000000011";
    private static final AnimalAndIdentityStub STUB = new AnimalAndIdentityStub();
    private static final TestDatabase DATABASE = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.identity.base-url", STUB::url);
        registry.add("animallink.animal.base-url", STUB::url);
        registry.add("animallink.minio.endpoint", () -> "http://localhost:9000");
        registry.add("animallink.minio.access-key", () -> "a");
        registry.add("animallink.minio.secret-key", () -> "b");
        registry.add("animallink.messaging.enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        clearDatabase();
        STUB.reset();
    }

    @AfterAll
    static void close() {
        STUB.stop();
        DATABASE.close();
    }

    @Test
    void eligibilityIsReadOnlyValidatesBothIdsAndRequiresAnimalService() throws Exception {
        String source = id();
        String target = id();

        mvc.perform(eligibility(source, target, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVICE_FORBIDDEN"));
        mvc.perform(eligibility(source, target, "wrong-service"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVICE_FORBIDDEN"));
        mvc.perform(eligibility(source, source, "animal-service"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(eligibility("not-a-uuid", target, "animal-service"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(eligibility(source, target, "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.blockers.length()").value(0));
        assertEquals(0, count("adoption_listing"));
        assertEquals(0, count("adoption_application"));
        assertEquals(0, count("adoption_selection"));
        assertEquals(0, count("adoption_handover"));
        assertEquals(0, count("adoption_relation"));
    }

    @Test
    void singleAnimalEligibilityUsesSameBlockersAndFailsClosedForUnauthorizedCallers()
            throws Exception {
        String animal = id();
        String listing = insertListing(animal, "DRAFT");

        mvc.perform(animalEligibility(animal, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVICE_FORBIDDEN"));
        mvc.perform(animalEligibility("not-a-uuid", "animal-service"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(animalEligibility(animal, "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.blockers.length()").value(1))
                .andExpect(jsonPath("$.blockers[0].animalId").value(animal))
                .andExpect(jsonPath("$.blockers[0].blockerCode").value("ACTIVE_LISTING"));

        jdbc.update("UPDATE adoption_listing SET status='CLOSED',closed_at=NOW(6) WHERE id=?",
                listing);
        mvc.perform(animalEligibility(animal, "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.blockers.length()").value(0));
    }

    @Test
    void internalLifecycleAuthorityReturnsLatestHandoverAndRelation() throws Exception {
        String animal = id();
        String listing = insertListing(animal, "CLOSED");
        String application = insertApplication(listing, "APPROVED");
        String selection = insertSelection(listing, application, "ACTIVE");
        String handover = insertHandover(selection, "COMPLETED");
        String relation = insertRelation(animal, handover, "ACTIVE");

        mvc.perform(get("/internal/v1/adoption-lifecycle/animals/{id}", animal))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/adoption-lifecycle/animals/{id}", animal)
                        .header("X-Internal-Service", "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.animalId").value(animal))
                .andExpect(jsonPath("$.handoverId").value(handover))
                .andExpect(jsonPath("$.handoverStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.relationId").value(relation))
                .andExpect(jsonPath("$.relationStatus").value("ACTIVE"));

        mvc.perform(get("/internal/v1/adoption-lifecycle/animals/{id}", id())
                        .header("X-Internal-Service", "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void draftAndPublishedListingsOnEitherAnimalBlockButClosedListingsDoNot() throws Exception {
        String source = id();
        String target = id();
        String sourceListing = insertListing(source, "DRAFT");
        String targetListing = insertListing(target, "PUBLISHED");

        mvc.perform(eligibility(source, target, "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.blockers[0].animalId").value(source))
                .andExpect(jsonPath("$.blockers[0].blockerCode").value("ACTIVE_LISTING"))
                .andExpect(jsonPath("$.blockers[1].animalId").value(target))
                .andExpect(jsonPath("$.blockers[1].blockerCode").value("ACTIVE_LISTING"));

        jdbc.update("UPDATE adoption_listing SET status='CLOSED',closed_at=NOW(6) WHERE id IN (?,?)",
                sourceListing, targetListing);
        mvc.perform(eligibility(source, target, "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.blockers.length()").value(0));
    }

    @Test
    void publishedApplicationsBlockButClosedHistoricalApplicationsDoNot() throws Exception {
        String source = id();
        String target = id();
        String sourceListing = insertListing(source, "PUBLISHED");
        String targetListing = insertListing(target, "PUBLISHED");
        insertApplication(sourceListing, "SUBMITTED");
        insertApplication(targetListing, "APPROVED");

        mvc.perform(eligibility(source, target, "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.blockers.length()").value(4))
                .andExpect(jsonPath("$.blockers[*].blockerCode", containsInAnyOrder(
                        "ACTIVE_LISTING", "ACTIVE_LISTING", "ACTIVE_APPLICATION", "ACTIVE_APPLICATION")));

        jdbc.update("UPDATE adoption_listing SET status='CLOSED',closed_at=NOW(6) WHERE id IN (?,?)",
                sourceListing, targetListing);
        mvc.perform(eligibility(source, target, "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.blockers.length()").value(0));
    }

    @Test
    void eligibilityTracksSelectionHandoverAndRelationAndFailsClosedForCorruptCompletion() throws Exception {
        String source = id();
        String target = id();
        String listing = insertListing(source, "CLOSED");
        String application = insertApplication(listing, "APPROVED");
        String selection = insertSelection(listing, application, "ACTIVE");

        assertSingleBlocker(source, target, "ACTIVE_SELECTION");

        String handover = insertHandover(selection, "PENDING");
        assertSingleBlocker(source, target, "PENDING_HANDOVER");

        jdbc.update("UPDATE adoption_handover SET status='COMPLETED',completed_by_user_id=?,completed_at=NOW(6),updated_at=NOW(6) WHERE id=?",
                ADMIN, handover);
        assertSingleBlocker(source, target, "ACTIVE_SELECTION");

        String relation = insertRelation(source, handover, "ACTIVE");
        assertSingleBlocker(source, target, "ACTIVE_RELATION");

        jdbc.update("UPDATE adoption_relation SET status='ENDED',ended_at=NOW(6),end_reason='terminal',updated_at=NOW(6) WHERE id=?",
                relation);
        assertEligible(source, target);

        clearDatabase();
        String cancelledListing = insertListing(target, "CLOSED");
        String approvedApplication = insertApplication(cancelledListing, "APPROVED");
        String cancelledSelection = insertSelection(cancelledListing, approvedApplication, "CANCELLED");
        insertHandover(cancelledSelection, "CANCELLED");
        assertEligible(source, target);
    }

    @Test
    void completedEndedHistoricalLifecycleDoesNotBlockMerge() throws Exception {
        String source = id();
        String target = id();
        String listing = insertListing(source, "CLOSED");
        String application = insertApplication(listing, "APPROVED");
        String selection = insertSelection(listing, application, "ACTIVE");
        String handover = insertHandover(selection, "COMPLETED");
        insertRelation(source, handover, "ENDED");

        assertEligible(source, target);
    }

    @Test
    void createAndPublishRejectCanonicalMismatchWithoutMutation() throws Exception {
        String requested = id();
        STUB.alias(requested, id(), "OPEN");

        mvc.perform(post("/api/v1/listings").header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"animalId\":\"" + requested + "\",\"title\":\"title\",\"description\":\"description\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANIMAL_NOT_ELIGIBLE"));
        assertEquals(0, count("adoption_listing"));

        String listing = insertListing(requested, "DRAFT");
        mvc.perform(post("/api/v1/listings/{id}/publish", listing).header("X-User-Id", ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANIMAL_NOT_ELIGIBLE"));
        assertEquals("DRAFT", value("SELECT status FROM adoption_listing WHERE id=?", listing));
    }

    @Test
    void applicationAndSelectionRejectCanonicalMismatchWithoutMutation() throws Exception {
        String requested = id();
        STUB.alias(requested, id(), "OPEN");
        String listing = insertListing(requested, "PUBLISHED");

        mvc.perform(post("/api/v1/listings/{id}/applications", listing).header("X-User-Id", APPLICANT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"申请\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANIMAL_NOT_ELIGIBLE"));
        assertEquals(0, count("adoption_application"));

        String application = insertApplication(listing, "APPROVED");
        mvc.perform(post("/api/v1/governance/listings/{id}/selection", listing)
                        .header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":\"" + application + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANIMAL_NOT_ELIGIBLE"));
        assertEquals(0, count("adoption_selection"));
        assertEquals("PUBLISHED", value("SELECT status FROM adoption_listing WHERE id=?", listing));
    }

    @Test
    void handoverInitiateAndCompleteRejectCanonicalMismatchWithoutSideEffects() throws Exception {
        String requested = id();
        STUB.alias(requested, id(), "OPEN");
        String listing = insertListing(requested, "CLOSED");
        String application = insertApplication(listing, "APPROVED");
        String selection = insertSelection(listing, application, "ACTIVE");

        mvc.perform(initiate(selection))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANIMAL_NOT_ELIGIBLE"));
        assertEquals(0, count("adoption_handover"));

        String handover = insertHandover(selection, "PENDING");
        mvc.perform(post("/api/v1/governance/handovers/{id}/complete", handover)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANIMAL_NOT_ELIGIBLE"));
        assertEquals("PENDING", value("SELECT status FROM adoption_handover WHERE id=?", handover));
        assertEquals(0, count("adoption_relation"));
        assertEquals(0, count("outbox_event"));
    }

    @Test
    void convergenceOperationsAndHistoricalFollowUpDoNotCallAnimalGateway() throws Exception {
        String animal = id();
        STUB.alias(animal, id(), "OPEN");
        String listing = insertListing(animal, "CLOSED");
        String application = insertApplication(listing, "SUBMITTED");
        STUB.animalCalls.set(0);

        mvc.perform(post("/api/v1/applications/{id}/withdraw", application)
                        .header("X-User-Id", APPLICANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        assertEquals(0, STUB.animalCalls.get());

        String approved = insertApplication(listing, "APPROVED");
        String selection = insertSelection(listing, approved, "ACTIVE");
        String handover = insertHandover(selection, "PENDING");
        mvc.perform(post("/api/v1/governance/handovers/{id}/cancel", handover)
                        .header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"close raced history\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertEquals(0, STUB.animalCalls.get());
    }

    @Test
    void canonicalHappyPathUsesOneAnimalCallPerForwardOperation() throws Exception {
        String animal = id();
        STUB.canonical(animal, "OPEN");

        String listing = responseId(mvc.perform(post("/api/v1/listings").header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"animalId\":\"" + animal + "\",\"title\":\"title\",\"description\":\"description\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "listingId");
        mvc.perform(post("/api/v1/listings/{id}/publish", listing).header("X-User-Id", ADMIN))
                .andExpect(status().isOk());
        String application = responseId(mvc.perform(post("/api/v1/listings/{id}/applications", listing)
                        .header("X-User-Id", APPLICANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"申请\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "applicationId");
        mvc.perform(post("/api/v1/governance/applications/{id}/approve", application)
                        .header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        String selection = responseId(mvc.perform(post("/api/v1/governance/listings/{id}/selection", listing)
                        .header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":\"" + application + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "selectionId");
        String handover = responseId(mvc.perform(initiate(selection)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "handoverId");
        mvc.perform(post("/api/v1/governance/handovers/{id}/complete", handover)
                        .header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(6, STUB.animalCalls.get());
        assertEquals(1, count("adoption_relation"));
        assertEquals(2, count("outbox_event"));
    }

    @Test
    void animalDependencyAndInvalidPayloadFailClosed() throws Exception {
        String animal = id();
        STUB.canonical(animal, "OPEN");
        STUB.animalUnavailable.set(true);
        mvc.perform(post("/api/v1/listings").header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"animalId\":\"" + animal + "\",\"title\":\"title\",\"description\":\"description\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        assertEquals(0, count("adoption_listing"));

        STUB.animalUnavailable.set(false);
        STUB.invalidAnimalPayload.set(true);
        mvc.perform(post("/api/v1/listings").header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"animalId\":\"" + animal + "\",\"title\":\"title\",\"description\":\"description\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        assertEquals(0, count("adoption_listing"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder eligibility(
            String source, String target, String caller) {
        var request = post("/internal/v1/adoption-merge-eligibility/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAnimalId\":\"" + source + "\",\"targetAnimalId\":\"" + target + "\"}");
        if (caller != null) request.header("X-Internal-Service", caller);
        return request;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder animalEligibility(
            String animalId, String caller) {
        var request = post("/internal/v1/adoption-merge-eligibility/check-animal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"animalId\":\"" + animalId + "\"}");
        if (caller != null) request.header("X-Internal-Service", caller);
        return request;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder initiate(String selectionId) {
        return post("/api/v1/governance/selections/{id}/handover", selectionId)
                .header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduledAt\":\"2030-01-01T10:00:00Z\"}");
    }

    private void assertSingleBlocker(String source, String target, String blockerCode) throws Exception {
        mvc.perform(eligibility(source, target, "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.blockers.length()").value(1))
                .andExpect(jsonPath("$.blockers[0].animalId").value(source))
                .andExpect(jsonPath("$.blockers[0].blockerCode").value(blockerCode));
    }

    private void assertEligible(String source, String target) throws Exception {
        mvc.perform(eligibility(source, target, "animal-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.blockers.length()").value(0));
    }

    private String insertListing(String animalId, String status) {
        String listing = id();
        jdbc.update("""
                INSERT INTO adoption_listing
                    (id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,published_at,closed_at)
                VALUES (?,?,?,'title','description',?,NOW(6),NOW(6),
                    CASE WHEN ?='PUBLISHED' THEN NOW(6) ELSE NULL END,
                    CASE WHEN ?='CLOSED' THEN NOW(6) ELSE NULL END)
                """, listing, animalId, status, ADMIN, status, status);
        return listing;
    }

    private String insertApplication(String listingId, String status) {
        String application = id();
        jdbc.update("""
                INSERT INTO adoption_application
                    (id,listing_id,applicant_user_id,status,message,created_at,updated_at,withdrawn_at,
                     reviewer_user_id,reviewed_at,review_comment)
                VALUES (?,?,?,?,'message',NOW(6),NOW(6),
                    CASE WHEN ?='WITHDRAWN' THEN NOW(6) ELSE NULL END,
                    CASE WHEN ? IN ('APPROVED','REJECTED') THEN ? ELSE NULL END,
                    CASE WHEN ? IN ('APPROVED','REJECTED') THEN NOW(6) ELSE NULL END,
                    CASE WHEN ?='REJECTED' THEN 'rejected' ELSE NULL END)
                """, application, listingId, APPLICANT, status, status, status, ADMIN, status, status);
        return application;
    }

    private String insertSelection(String listingId, String applicationId, String status) {
        String selection = id();
        jdbc.update("""
                INSERT INTO adoption_selection
                    (id,listing_id,application_id,status,selected_by_user_id,selected_at,
                     cancelled_by_user_id,cancelled_at,cancel_reason)
                VALUES (?,?,?,?,?,NOW(6),
                    CASE WHEN ?='CANCELLED' THEN ? ELSE NULL END,
                    CASE WHEN ?='CANCELLED' THEN NOW(6) ELSE NULL END,
                    CASE WHEN ?='CANCELLED' THEN 'cancelled' ELSE NULL END)
                """, selection, listingId, applicationId, status, ADMIN,
                status, ADMIN, status, status);
        return selection;
    }

    private String insertHandover(String selectionId, String status) {
        String handover = id();
        jdbc.update("""
                INSERT INTO adoption_handover
                    (id,selection_id,status,scheduled_at,initiated_by_user_id,initiated_at,
                     completed_by_user_id,completed_at,cancelled_by_user_id,cancelled_at,cancel_reason,
                     created_at,updated_at)
                VALUES (?,?,?,NOW(6),?,NOW(6),
                    CASE WHEN ?='COMPLETED' THEN ? ELSE NULL END,
                    CASE WHEN ?='COMPLETED' THEN NOW(6) ELSE NULL END,
                    CASE WHEN ?='CANCELLED' THEN ? ELSE NULL END,
                    CASE WHEN ?='CANCELLED' THEN NOW(6) ELSE NULL END,
                    CASE WHEN ?='CANCELLED' THEN 'cancelled' ELSE NULL END,
                    NOW(6),NOW(6))
                """, handover, selectionId, status, ADMIN,
                status, ADMIN, status, status, ADMIN, status, status);
        return handover;
    }

    private String insertRelation(String animalId, String handoverId, String status) {
        String relation = id();
        jdbc.update("""
                INSERT INTO adoption_relation
                    (id,animal_id,adopter_user_id,handover_id,status,activated_at,ended_at,end_reason,created_at,updated_at)
                VALUES (?,?,?,?,?,NOW(6),
                    CASE WHEN ?='ENDED' THEN NOW(6) ELSE NULL END,
                    CASE WHEN ?='ENDED' THEN 'ended' ELSE NULL END,NOW(6),NOW(6))
                """, relation, animalId, APPLICANT, handoverId, status, status, status);
        return relation;
    }

    private void clearDatabase() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM adoption_follow_up");
        jdbc.update("DELETE FROM adoption_relation");
        jdbc.update("DELETE FROM adoption_handover");
        jdbc.update("DELETE FROM adoption_selection");
        jdbc.update("DELETE FROM adoption_application");
        jdbc.update("DELETE FROM adoption_listing");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String value(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private String responseId(String body, String field) throws Exception {
        JsonNode value = json.readTree(body).get(field);
        assertTrue(value != null && value.isTextual());
        return value.asText();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    static class AnimalAndIdentityStub {
        private final HttpServer server;
        private final Map<String, AnimalResponse> animals = new ConcurrentHashMap<>();
        private final AtomicBoolean animalUnavailable = new AtomicBoolean();
        private final AtomicBoolean invalidAnimalPayload = new AtomicBoolean();
        private final AtomicInteger animalCalls = new AtomicInteger();

        AnimalAndIdentityStub() {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
                server.createContext("/api/v1/users/me", this::user);
                server.createContext("/api/v1/animals", this::animal);
                server.start();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        void reset() {
            animals.clear();
            animalUnavailable.set(false);
            invalidAnimalPayload.set(false);
            animalCalls.set(0);
        }

        void canonical(String animalId, String adoptionStatus) {
            animals.put(animalId, new AnimalResponse(animalId, adoptionStatus));
        }

        void alias(String requestedAnimalId, String canonicalAnimalId, String adoptionStatus) {
            animals.put(requestedAnimalId, new AnimalResponse(canonicalAnimalId, adoptionStatus));
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private void user(HttpExchange exchange) throws IOException {
            String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
            if (userId == null || userId.isBlank()) {
                reply(exchange, 401, "{}");
                return;
            }
            if (exchange.getRequestURI().getPath().endsWith("campus-memberships")) {
                reply(exchange, 200, "[{\"status\":\"ACTIVE\"}]");
                return;
            }
            String role = ADMIN.equals(userId) ? "GOVERNANCE_ADMIN" : "USER";
            reply(exchange, 200, "{\"id\":\"" + userId
                    + "\",\"accountStatus\":\"ACTIVE\",\"systemRole\":\"" + role + "\"}");
        }

        private void animal(HttpExchange exchange) throws IOException {
            animalCalls.incrementAndGet();
            if (animalUnavailable.get()) {
                reply(exchange, 503, "{}");
                return;
            }
            if (invalidAnimalPayload.get()) {
                reply(exchange, 200, "{}");
                return;
            }
            String requested = exchange.getRequestURI().getPath().substring("/api/v1/animals/".length());
            AnimalResponse response = animals.get(requested);
            if (response == null) {
                reply(exchange, 404, "{}");
                return;
            }
            reply(exchange, 200, "{\"requestedAnimalId\":\"" + requested
                    + "\",\"id\":\"" + response.canonicalAnimalId()
                    + "\",\"canonicalAnimalId\":\"" + response.canonicalAnimalId()
                    + "\",\"adoptionStatus\":\"" + response.adoptionStatus() + "\"}");
        }

        private void reply(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private record AnimalResponse(String canonicalAnimalId, String adoptionStatus) {
        }
    }

    static class TestDatabase {
        private final String url;
        private final String username;
        private final String password;
        private final MySQLContainer<?> container;

        TestDatabase(String url, String username, String password, MySQLContainer<?> container) {
            this.url = url;
            this.username = username;
            this.password = password;
            this.container = container;
        }

        static TestDatabase create() {
            if ("true".equalsIgnoreCase(System.getenv("ANIMALLINK_TEST_EXTERNAL_MYSQL"))) {
                return new TestDatabase("jdbc:mysql://127.0.0.1:" + required("ANIMALLINK_TEST_MYSQL_PORT")
                        + "/" + required("ANIMALLINK_TEST_MYSQL_DATABASE")
                        + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC",
                        required("ANIMALLINK_TEST_MYSQL_USERNAME"),
                        required("ANIMALLINK_TEST_MYSQL_PASSWORD"), null);
            }
            MySQLContainer<?> container = new MySQLContainer<>("mysql:8.0.41")
                    .withDatabaseName("adoption_merge_safety_test")
                    .withUsername("test")
                    .withPassword("test");
            container.start();
            return new TestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword(), container);
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required for the localhost external MySQL fallback");
            }
            return value;
        }

        String url() { return url; }
        String username() { return username; }
        String password() { return password; }
        void close() { if (container != null) container.stop(); }
    }
}
