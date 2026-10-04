package org.animallink.adoption;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
    static final String APPLICANT = "00000000-0000-0000-0000-000000000011";
    static final String UNRELATED = "00000000-0000-0000-0000-000000000014";
    static final String NO_MEMBERSHIP = "00000000-0000-0000-0000-000000000012";
    static final Stub STUB = new Stub(); static final TestDatabase DATABASE = TestDatabase.create();
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::url); r.add("spring.datasource.username", DATABASE::username); r.add("spring.datasource.password", DATABASE::password);
        r.add("spring.cloud.nacos.discovery.enabled", () -> false); r.add("spring.cloud.nacos.config.enabled", () -> false);
        r.add("animallink.identity.base-url", STUB::url); r.add("animallink.animal.base-url", STUB::url);
        r.add("animallink.minio.endpoint", () -> "http://localhost:9000"); r.add("animallink.minio.access-key", () -> "a"); r.add("animallink.minio.secret-key", () -> "b");
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean() { jdbc.update("DELETE FROM adoption_application"); jdbc.update("DELETE FROM adoption_listing"); STUB.animals.clear(); STUB.unavailable = false; }
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
    private String listing(String actor, boolean publish) throws Exception { String animal = UUID.randomUUID().toString(); STUB.animals.put(animal, "OPEN"); String body = mvc.perform(post("/api/v1/listings").header("X-User-Id", actor).contentType(MediaType.APPLICATION_JSON).content("{\"animalId\":\"" + animal + "\",\"title\":\"领养信息\",\"description\":\"寻找家庭\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(); String id = id(body, "listingId"); if (publish) mvc.perform(post("/api/v1/listings/{id}/publish", id).header("X-User-Id", actor)).andExpect(status().isOk()); return id; }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(String listing, String actor) { var request = post("/api/v1/listings/{id}/applications", listing).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"我愿意领养\"}"); if (actor != null) request.header("X-User-Id", actor); return request; }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder governanceQueue(String listing, String status, int page, int size) { var request = get("/api/v1/governance/applications").header("X-User-Id", ADMIN).param("page", String.valueOf(page)).param("size", String.valueOf(size)); if (listing != null) request.param("listingId", listing); if (status != null) request.param("status", status); return request; }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder approve(String application, String comment) { return post("/api/v1/governance/applications/{id}/approve", application).header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON).content(comment == null ? "{}" : "{\"comment\":\"" + comment + "\"}"); }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reject(String application, String reason) { return post("/api/v1/governance/applications/{id}/reject", application).header("X-User-Id", ADMIN).contentType(MediaType.APPLICATION_JSON).content(reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}"); }
    private String applicationId(String listing, String actor) throws Exception { return id(mvc.perform(submit(listing, actor)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "applicationId"); }
    private String id(String body, String field) { Matcher m = Pattern.compile("\\\"" + field + "\\\":\\\"([0-9a-fA-F-]{36})\\\"").matcher(body); assertTrue(m.find()); return m.group(1); }
    private int concurrentSubmit(String listing, CountDownLatch ready, CountDownLatch start) throws Exception { ready.countDown(); start.await(); return mvc.perform(submit(listing, APPLICANT)).andReturn().getResponse().getStatus(); }
    private int concurrent(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, CountDownLatch ready, CountDownLatch start) throws Exception { ready.countDown(); start.await(); return mvc.perform(request).andReturn().getResponse().getStatus(); }
    static class Stub {
        final Map<String, String> animals = new ConcurrentHashMap<>(); volatile boolean unavailable; volatile int animalCalls; final HttpServer server;
        Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/api/v1/users/me", this::user); server.createContext("/api/v1/animals", this::animal); server.start(); } catch (IOException e) { throw new RuntimeException(e); } }
        String url() { return "http://localhost:" + server.getAddress().getPort(); } void stop() { server.stop(0); }
        void user(HttpExchange e) throws IOException { if (unavailable) { reply(e, 503, "{}"); return; } String id = e.getRequestHeaders().getFirst("X-User-Id"); if (id == null) { reply(e, 401, "{}"); return; } if (e.getRequestURI().getPath().endsWith("campus-memberships")) { reply(e, 200, NO_MEMBERSHIP.equals(id) ? "[]" : "[{\"status\":\"ACTIVE\"}]"); return; } reply(e, 200, "{\"id\":\"" + id + "\",\"accountStatus\":\"ACTIVE\",\"systemRole\":\"" + (ADMIN.equals(id) ? "GOVERNANCE_ADMIN" : "USER") + "\"}"); }
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
