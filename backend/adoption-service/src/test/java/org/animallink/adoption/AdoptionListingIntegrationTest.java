package org.animallink.adoption;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
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
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdoptionListingIntegrationTest {
    static final String ADMIN = "00000000-0000-0000-0000-000000000002";
    static final String USER = "00000000-0000-0000-0000-000000000003";
    static final Stub STUB = new Stub();
    static final TestDatabase DATABASE = TestDatabase.create();
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::url); r.add("spring.datasource.username", DATABASE::username); r.add("spring.datasource.password", DATABASE::password);
        r.add("spring.cloud.nacos.discovery.enabled", () -> false); r.add("spring.cloud.nacos.config.enabled", () -> false);
        r.add("animallink.identity.base-url", STUB::url); r.add("animallink.animal.base-url", STUB::url);
        r.add("animallink.minio.endpoint", () -> "http://localhost:9000"); r.add("animallink.minio.access-key", () -> "a"); r.add("animallink.minio.secret-key", () -> "b");
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean() { jdbc.update("DELETE FROM adoption_selection"); jdbc.update("DELETE FROM adoption_application"); jdbc.update("DELETE FROM adoption_listing"); STUB.animals.clear(); }
    @AfterAll static void stop() { STUB.stop(); DATABASE.close(); }

    @Test void governanceAdminCreatesDraftAndOrdinaryUserIsDenied() throws Exception {
        String animal = animal("OPEN");
        mvc.perform(create(animal, ADMIN)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.publisherUserId").value(ADMIN));
        mvc.perform(create(animal("OPEN"), USER)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
        assertEquals(1, count());
    }
    @Test void missingAndIneligibleAnimalsAreRejected() throws Exception {
        mvc.perform(create(UUID.randomUUID().toString(), ADMIN)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ANIMAL_NOT_FOUND"));
        mvc.perform(create(animal("ADOPTED"), ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANIMAL_NOT_ELIGIBLE"));
        mvc.perform(create(animal("NOT_OPEN"), ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANIMAL_NOT_ELIGIBLE"));
    }
    @Test void duplicateActiveListingIsRejectedByDatabaseConstraint() throws Exception {
        String animal = animal("OPEN"); mvc.perform(create(animal, ADMIN)).andExpect(status().isCreated());
        mvc.perform(create(animal, ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVE_LISTING_EXISTS"));
        assertEquals(1, count());
    }
    @Test void concurrentCreatesAllowExactlyOneActiveListing() throws Exception {
        String animal = animal("OPEN"); CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> a = pool.submit(() -> concurrentCreate(animal, ready, start)); Future<Integer> b = pool.submit(() -> concurrentCreate(animal, ready, start));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
        int first = a.get(); int second = b.get(); pool.shutdown();
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0));
        assertEquals(1, (first == 409 ? 1 : 0) + (second == 409 ? 1 : 0)); assertEquals(1, count());
    }
    @Test void draftIsHiddenUntilPublishedThenPublicAndClosedDetailRemainsPublic() throws Exception {
        String id = createId(animal("OPEN"));
        mvc.perform(get("/api/v1/listings/{id}", id)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/listings")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/v1/listings/{id}/publish", id).header("X-User-Id", ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(get("/api/v1/listings/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(post("/api/v1/listings/{id}/close", id).header("X-User-Id", ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(get("/api/v1/listings")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/listings/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }
    @Test void invalidTransitionAndUnauthorizedLifecycleAreRejected() throws Exception {
        String id = createId(animal("OPEN"));
        mvc.perform(post("/api/v1/listings/{id}/publish", id).header("X-User-Id", USER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/listings/{id}/publish", id).header("X-User-Id", ADMIN)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/listings/{id}/publish", id).header("X-User-Id", ADMIN)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_LISTING_TRANSITION"));
    }
    @Test void publicPaginationIsStableAndExcludesDrafts() throws Exception {
        String first = createId(animal("OPEN")); String second = createId(animal("OPEN"));
        mvc.perform(post("/api/v1/listings/{id}/publish", first).header("X-User-Id", ADMIN)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/listings/{id}/publish", second).header("X-User-Id", ADMIN)).andExpect(status().isOk());
        createId(animal("OPEN"));
        mvc.perform(get("/api/v1/listings?page=0&size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.items", hasSize(1)));
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(String animalId, String actor) { return post("/api/v1/listings").header("X-User-Id", actor).contentType(MediaType.APPLICATION_JSON).content("{\"animalId\":\"" + animalId + "\",\"title\":\"领养信息\",\"description\":\"寻找合适家庭\"}"); }
    private String createId(String animalId) throws Exception { String body = mvc.perform(create(animalId, ADMIN)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(); Matcher matcher = Pattern.compile("\\\"listingId\\\":\\\"([0-9a-fA-F-]{36})\\\"").matcher(body); assertTrue(matcher.find(), "listingId missing from response"); return matcher.group(1); }
    private int concurrentCreate(String animalId, CountDownLatch ready, CountDownLatch start) throws Exception { ready.countDown(); start.await(); return mvc.perform(create(animalId, ADMIN)).andReturn().getResponse().getStatus(); }
    private String animal(String status) { String id = UUID.randomUUID().toString(); STUB.animals.put(id, status); return id; }
    private int count() { Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM adoption_listing", Integer.class); return value == null ? 0 : value; }
    static class Stub {
        final Map<String, String> animals = new ConcurrentHashMap<>(); final HttpServer server;
        Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/api/v1/users/me", this::user); server.createContext("/api/v1/animals", this::animal); server.start(); } catch (IOException e) { throw new RuntimeException(e); } }
        String url() { return "http://localhost:" + server.getAddress().getPort(); } void stop() { server.stop(0); }
        void user(HttpExchange e) throws IOException { String id = e.getRequestHeaders().getFirst("X-User-Id"); if (id == null) { reply(e, 401, "{}"); return; } String role = ADMIN.equals(id) ? "GOVERNANCE_ADMIN" : "USER"; reply(e, 200, "{\"id\":\"" + id + "\",\"accountStatus\":\"ACTIVE\",\"systemRole\":\"" + role + "\"}"); }
        void animal(HttpExchange e) throws IOException { String id = e.getRequestURI().getPath().substring("/api/v1/animals/".length()); String status = animals.get(id); if (status == null) { reply(e, 404, "{}"); return; } reply(e, 200, "{\"id\":\"" + id + "\",\"adoptionStatus\":\"" + status + "\"}"); }
        void reply(HttpExchange e, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close(); }
    }
    static class TestDatabase {
        private final String url; private final String username; private final String password; private final MySQLContainer<?> container;
        private TestDatabase(String url, String username, String password, MySQLContainer<?> container) { this.url = url; this.username = username; this.password = password; this.container = container; }
        static TestDatabase create() {
            if ("true".equalsIgnoreCase(System.getenv("ANIMALLINK_TEST_EXTERNAL_MYSQL"))) {
                int port = localPort();
                String database = required("ANIMALLINK_TEST_MYSQL_DATABASE");
                return new TestDatabase("jdbc:mysql://127.0.0.1:" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC",
                        required("ANIMALLINK_TEST_MYSQL_USERNAME"), required("ANIMALLINK_TEST_MYSQL_PASSWORD"), null);
            }
            MySQLContainer<?> container = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("adoption_listing_test").withUsername("test").withPassword("test");
            container.start();
            return new TestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword(), container);
        }
        private static int localPort() { try { int port = Integer.parseInt(required("ANIMALLINK_TEST_MYSQL_PORT")); if (port < 1 || port > 65535) throw new IllegalArgumentException(); return port; } catch (IllegalArgumentException e) { throw new IllegalStateException("ANIMALLINK_TEST_MYSQL_PORT must be a valid localhost port"); } }
        private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for the localhost external MySQL fallback"); return value; }
        String url() { return url; } String username() { return username; } String password() { return password; }
        void close() { if (container != null) container.stop(); }
    }
}
