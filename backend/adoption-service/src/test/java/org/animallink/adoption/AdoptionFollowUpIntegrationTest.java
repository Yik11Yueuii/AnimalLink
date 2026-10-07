package org.animallink.adoption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdoptionFollowUpIntegrationTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String ADOPTER = "00000000-0000-0000-0000-000000000011";
    private static final String OTHER = "00000000-0000-0000-0000-000000000014";
    private static final String INACTIVE = "00000000-0000-0000-0000-000000000013";
    private static final Stub IDENTITY = new Stub();
    private static final TestDatabase DATABASE = TestDatabase.create();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.identity.base-url", IDENTITY::url);
        registry.add("animallink.minio.endpoint", () -> "http://localhost:9000");
        registry.add("animallink.minio.access-key", () -> "a");
        registry.add("animallink.minio.secret-key", () -> "b");
        registry.add("animallink.messaging.enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM adoption_follow_up");
        jdbc.update("DELETE FROM adoption_relation");
        jdbc.update("DELETE FROM adoption_handover");
        jdbc.update("DELETE FROM adoption_selection");
        jdbc.update("DELETE FROM adoption_application");
        jdbc.update("DELETE FROM adoption_listing");
    }

    @AfterAll static void stop() { IDENTITY.stop(); DATABASE.close(); }

    @Test
    void activeAdopterCreatesTrimmedFollowUpWithNormalizedOffsetTimestamp() throws Exception {
        String relation = relation("ACTIVE");
        String response = mvc.perform(create(relation, ADOPTER, "  状态良好  ", "2026-10-01T20:30:00+08:00"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.relationId").value(relation))
                .andExpect(jsonPath("$.content").value("状态良好")).andExpect(jsonPath("$.followedUpAt").value("2026-10-01T12:30:00Z"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty()).andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(content().string(not(containsString("adopterUserId")))).andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(response);
        assertEquals("状态良好", jdbc.queryForObject("SELECT content FROM adoption_follow_up WHERE id=?", String.class, body.path("id").asText()));
        assertEquals(Instant.parse("2026-10-01T12:30:00Z"), jdbc.queryForObject("SELECT followed_up_at FROM adoption_follow_up WHERE id=?", (rs, row) -> rs.getTimestamp(1).toInstant(), body.path("id").asText()));
    }

    @Test
    void inactiveAndNonOwnerCannotCreateOrRead() throws Exception {
        String relation = relation("ACTIVE");
        mvc.perform(create(relation, INACTIVE, "x", past())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FOLLOW_UP_ACCESS_DENIED"));
        mvc.perform(create(relation, OTHER, "x", past())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FOLLOW_UP_ACCESS_DENIED"));
        mvc.perform(get("/api/v1/me/relations/{id}/follow-ups", relation).header("X-User-Id", OTHER)).andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM adoption_follow_up", Integer.class));
    }

    @Test
    void invalidCreateRequestsAndMissingRelationUseExistingErrorContract() throws Exception {
        String relation = relation("ACTIVE");
        mvc.perform(create(relation, ADOPTER, " ", past())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(create(relation, ADOPTER, "x".repeat(2001), past())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/me/relations/{id}/follow-ups", relation).header("X-User-Id", ADOPTER).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(create(relation, ADOPTER, "x", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString())).andExpect(status().isBadRequest());
        mvc.perform(create(UUID.randomUUID().toString(), ADOPTER, "x", past())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ADOPTION_RELATION_NOT_FOUND"));
        mvc.perform(create("not-a-uuid", ADOPTER, "x", past())).andExpect(status().isBadRequest());
    }

    @Test
    void endedRelationRejectsNewRecordButOwnerAndGovernanceReadHistory() throws Exception {
        String relation = relation("ACTIVE");
        mvc.perform(create(relation, ADOPTER, "历史记录", past())).andExpect(status().isCreated());
        jdbc.update("UPDATE adoption_relation SET status='ENDED',ended_at=NOW(6),end_reason='结束',updated_at=NOW(6) WHERE id=?", relation);
        mvc.perform(create(relation, ADOPTER, "新记录", past())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADOPTION_RELATION_NOT_ACTIVE"));
        mvc.perform(get("/api/v1/me/relations/{id}/follow-ups", relation).header("X-User-Id", ADOPTER)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/governance/relations/{id}/follow-ups", relation).header("X-User-Id", ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void governanceReadsFollowUpsForActiveRelation() throws Exception {
        String relation = relation("ACTIVE");
        String followUpId = id(createResult(relation, ADOPTER, "治理读取", past()));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM adoption_relation WHERE id=?", String.class, relation));
        mvc.perform(get("/api/v1/governance/relations/{id}/follow-ups", relation).header("X-User-Id", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(followUpId))
                .andExpect(content().string(not(containsString("adopterUserId")))).andExpect(content().string(not(containsString("animalId"))));
    }

    @Test
    void multipleAndIdenticalRecordsAreAllowedAndReadsUseFollowedUpOrdering() throws Exception {
        String relation = relation("ACTIVE");
        String same = "2026-10-01T00:00:00Z";
        String first = id(createResult(relation, ADOPTER, "same", same));
        String second = id(createResult(relation, ADOPTER, "same", same));
        mvc.perform(create(relation, ADOPTER, "later", "2026-10-02T00:00:00Z")).andExpect(status().isCreated());
        assertNotEquals(first, second);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM adoption_follow_up WHERE relation_id=?", Integer.class, relation));
        mvc.perform(get("/api/v1/me/relations/{id}/follow-ups?page=0&size=20", relation).header("X-User-Id", ADOPTER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items[0].content").value("later"));
    }

    @Test
    void governanceRequiresActiveAdmin() throws Exception {
        String relation = relation("ACTIVE");
        mvc.perform(get("/api/v1/governance/relations/{id}/follow-ups", relation).header("X-User-Id", OTHER)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
        mvc.perform(get("/api/v1/governance/relations/{id}/follow-ups", relation).header("X-User-Id", INACTIVE)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
    }

    private String relation(String status) {
        String listing = UUID.randomUUID().toString(), application = UUID.randomUUID().toString(), selection = UUID.randomUUID().toString(), handover = UUID.randomUUID().toString(), relation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,closed_at) VALUES(?,?, 'CLOSED','title','description',?,NOW(6),NOW(6),NOW(6))", listing, UUID.randomUUID().toString(), ADMIN);
        jdbc.update("INSERT INTO adoption_application(id,listing_id,applicant_user_id,status,message,created_at,updated_at,reviewer_user_id,reviewed_at) VALUES(?,?,?,'APPROVED','message',NOW(6),NOW(6),?,NOW(6))", application, listing, ADOPTER, ADMIN);
        jdbc.update("INSERT INTO adoption_selection(id,listing_id,application_id,status,selected_by_user_id,selected_at) VALUES(?,?,?,'ACTIVE',?,NOW(6))", selection, listing, application, ADMIN);
        jdbc.update("INSERT INTO adoption_handover(id,selection_id,status,scheduled_at,initiated_by_user_id,initiated_at,completed_by_user_id,completed_at,created_at,updated_at) VALUES(?,?, 'COMPLETED',NOW(6),?,NOW(6),?,NOW(6),NOW(6),NOW(6))", handover, selection, ADMIN, ADMIN);
        if ("ENDED".equals(status)) jdbc.update("INSERT INTO adoption_relation(id,animal_id,adopter_user_id,handover_id,status,activated_at,ended_at,end_reason,created_at,updated_at) VALUES(?,?,?,?,'ENDED',NOW(6),NOW(6),'结束',NOW(6),NOW(6))", relation, UUID.randomUUID().toString(), ADOPTER, handover);
        else jdbc.update("INSERT INTO adoption_relation(id,animal_id,adopter_user_id,handover_id,status,activated_at,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',NOW(6),NOW(6),NOW(6))", relation, UUID.randomUUID().toString(), ADOPTER, handover);
        return relation;
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(String relation, String actor, String content, String followedUpAt) { return post("/api/v1/me/relations/{id}/follow-ups", relation).header("X-User-Id", actor).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\",\"followedUpAt\":\"" + followedUpAt + "\"}"); }
    private String createResult(String relation, String actor, String content, String followedUpAt) throws Exception { return mvc.perform(create(relation, actor, content, followedUpAt)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(); }
    private String id(String body) throws Exception { return json.readTree(body).path("id").asText(); }
    private static String past() { return "2026-10-01T00:00:00Z"; }

    static class Stub {
        final HttpServer server;
        Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/api/v1/users/me", this::user); server.start(); } catch (IOException exception) { throw new RuntimeException(exception); } }
        String url() { return "http://localhost:" + server.getAddress().getPort(); }
        void stop() { server.stop(0); }
        void user(HttpExchange exchange) throws IOException { String id = exchange.getRequestHeaders().getFirst("X-User-Id"); if (id == null) { reply(exchange, 401, "{}"); return; } String account = INACTIVE.equals(id) ? "INACTIVE" : "ACTIVE"; String role = (ADMIN.equals(id) || INACTIVE.equals(id)) ? "GOVERNANCE_ADMIN" : "USER"; reply(exchange, 200, "{\"id\":\"" + id + "\",\"accountStatus\":\"" + account + "\",\"systemRole\":\"" + role + "\"}"); }
        void reply(HttpExchange exchange, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }
    }
    static class TestDatabase {
        final String url, username, password; final MySQLContainer<?> container;
        TestDatabase(String url, String username, String password, MySQLContainer<?> container) { this.url = url; this.username = username; this.password = password; this.container = container; }
        static TestDatabase create() { if ("true".equalsIgnoreCase(System.getenv("ANIMALLINK_TEST_EXTERNAL_MYSQL"))) return new TestDatabase("jdbc:mysql://127.0.0.1:" + required("ANIMALLINK_TEST_MYSQL_PORT") + "/" + required("ANIMALLINK_TEST_MYSQL_DATABASE") + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC", required("ANIMALLINK_TEST_MYSQL_USERNAME"), required("ANIMALLINK_TEST_MYSQL_PASSWORD"), null); MySQLContainer<?> container = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("adoption_follow_up_test").withUsername("test").withPassword("test"); container.start(); return new TestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword(), container); }
        static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for the localhost external MySQL fallback"); return value; }
        String url() { return url; } String username() { return username; } String password() { return password; } void close() { if (container != null) container.stop(); }
    }
}
