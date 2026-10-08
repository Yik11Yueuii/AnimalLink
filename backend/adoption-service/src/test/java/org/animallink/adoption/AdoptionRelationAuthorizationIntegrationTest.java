package org.animallink.adoption;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdoptionRelationAuthorizationIntegrationTest {
    private static final String ADOPTER = "00000000-0000-0000-0000-000000000011";
    private static final String OTHER_USER = "00000000-0000-0000-0000-000000000014";
    private static final String INACTIVE_USER = "00000000-0000-0000-0000-000000000013";
    private static final String GOVERNANCE_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final IdentityStub IDENTITY = new IdentityStub();
    private static final TestDatabase DATABASE = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
    void reset() {
        IDENTITY.reset();
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM adoption_follow_up");
        jdbc.update("DELETE FROM adoption_relation");
        jdbc.update("DELETE FROM adoption_handover");
        jdbc.update("DELETE FROM adoption_selection");
        jdbc.update("DELETE FROM adoption_application");
        jdbc.update("DELETE FROM adoption_listing");
    }

    @AfterAll
    static void close() {
        IDENTITY.stop();
        DATABASE.close();
    }

    @Test
    void activeAdopterGetsOnlyActiveRelationId() throws Exception {
        String animalId = UUID.randomUUID().toString();
        String relationId = insertRelation(animalId, ADOPTER, "ACTIVE");

        String body = mvc.perform(active(animalId, ADOPTER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationId").value(relationId))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> response = json.readValue(body, new TypeReference<>() { });
        assertEquals(java.util.Set.of("relationId"), response.keySet());
    }

    @Test
    void endedRelationAndDifferentAdopterUseTheSameGenericDenial() throws Exception {
        String endedAnimal = UUID.randomUUID().toString();
        String endedRelation = insertRelation(endedAnimal, ADOPTER, "ENDED");
        String activeAnimal = UUID.randomUUID().toString();
        String otherRelation = insertRelation(activeAnimal, ADOPTER, "ACTIVE");

        String endedBody = mvc.perform(active(endedAnimal, ADOPTER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_ADOPTION_RELATION_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        String otherBody = mvc.perform(active(activeAnimal, OTHER_USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_ADOPTION_RELATION_REQUIRED"))
                .andReturn().getResponse().getContentAsString();

        assertNoRelationLeak(endedBody, endedRelation, endedAnimal, ADOPTER);
        assertNoRelationLeak(otherBody, otherRelation, activeAnimal, ADOPTER);
    }

    @Test
    void noRelationAndGovernanceAdminWithoutRelationAreDenied() throws Exception {
        String animalId = UUID.randomUUID().toString();
        String body = mvc.perform(active(animalId, ADOPTER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_ADOPTION_RELATION_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        assertNoRelationLeak(body, null, animalId, ADOPTER);

        insertRelation(UUID.randomUUID().toString(), ADOPTER, "ACTIVE");
        mvc.perform(active(animalId, GOVERNANCE_ADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_ADOPTION_RELATION_REQUIRED"));
    }

    @Test
    void inactiveCallerIsDeniedBeforeRelationInformationIsReturned() throws Exception {
        String animalId = UUID.randomUUID().toString();
        String relationId = insertRelation(animalId, INACTIVE_USER, "ACTIVE");

        String body = mvc.perform(active(animalId, INACTIVE_USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_ADOPTION_RELATION_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        assertNoRelationLeak(body, relationId, animalId, INACTIVE_USER);
    }

    @Test
    void missingCallerAndInvalidInternalServiceAreRejectedBeforeLookup() throws Exception {
        String animalId = UUID.randomUUID().toString();
        mvc.perform(get("/internal/v1/adoption-relations/active").param("animalId", animalId)
                        .header("X-Internal-Service", "animal-service"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/internal/v1/adoption-relations/active").param("animalId", animalId)
                        .header("X-User-Id", ADOPTER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVICE_FORBIDDEN"));
        mvc.perform(get("/internal/v1/adoption-relations/active").param("animalId", animalId)
                        .header("X-Internal-Service", "wrong-service").header("X-User-Id", ADOPTER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVICE_FORBIDDEN"));
        assertEquals(0, IDENTITY.calls.get());
    }

    @Test
    void malformedAnimalIdIsRejectedBeforeIdentityOrDatabaseLookup() throws Exception {
        mvc.perform(active("not-a-uuid", ADOPTER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertEquals(0, IDENTITY.calls.get());
    }

    @Test
    void identityDependencyFailureReturns503() throws Exception {
        IDENTITY.unavailable.set(true);
        mvc.perform(active(UUID.randomUUID().toString(), ADOPTER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder active(
            String animalId, String caller) {
        return get("/internal/v1/adoption-relations/active")
                .param("animalId", animalId)
                .header("X-Internal-Service", "animal-service")
                .header("X-User-Id", caller);
    }

    private String insertRelation(String animalId, String adopterUserId, String status) {
        String listing = UUID.randomUUID().toString();
        String application = UUID.randomUUID().toString();
        String selection = UUID.randomUUID().toString();
        String handover = UUID.randomUUID().toString();
        String relation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,closed_at) VALUES(?,?, 'CLOSED','title','description',?,NOW(6),NOW(6),NOW(6))", listing, animalId, GOVERNANCE_ADMIN);
        jdbc.update("INSERT INTO adoption_application(id,listing_id,applicant_user_id,status,message,created_at,updated_at,reviewer_user_id,reviewed_at) VALUES(?,?,?,'APPROVED','message',NOW(6),NOW(6),?,NOW(6))", application, listing, adopterUserId, GOVERNANCE_ADMIN);
        jdbc.update("INSERT INTO adoption_selection(id,listing_id,application_id,status,selected_by_user_id,selected_at) VALUES(?,?,?,'ACTIVE',?,NOW(6))", selection, listing, application, GOVERNANCE_ADMIN);
        jdbc.update("INSERT INTO adoption_handover(id,selection_id,status,scheduled_at,initiated_by_user_id,initiated_at,completed_by_user_id,completed_at,created_at,updated_at) VALUES(?,?, 'COMPLETED',NOW(6),?,NOW(6),?,NOW(6),NOW(6),NOW(6))", handover, selection, GOVERNANCE_ADMIN, GOVERNANCE_ADMIN);
        if ("ENDED".equals(status)) {
            jdbc.update("INSERT INTO adoption_relation(id,animal_id,adopter_user_id,handover_id,status,activated_at,ended_at,end_reason,created_at,updated_at) VALUES(?,?,?,?,'ENDED',NOW(6),NOW(6),'ended',NOW(6),NOW(6))", relation, animalId, adopterUserId, handover);
        } else {
            jdbc.update("INSERT INTO adoption_relation(id,animal_id,adopter_user_id,handover_id,status,activated_at,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',NOW(6),NOW(6),NOW(6))", relation, animalId, adopterUserId, handover);
        }
        return relation;
    }

    private void assertNoRelationLeak(String body, String relationId, String animalId, String adopterUserId) {
        assertFalse(body.contains("relationId"));
        assertFalse(body.contains("adopterUserId"));
        assertFalse(body.contains("endedAt"));
        assertFalse(body.contains("endReason"));
        assertFalse(body.contains("\"status\":\"ENDED\""));
        assertFalse(body.contains(animalId));
        assertFalse(body.contains(adopterUserId));
        if (relationId != null) {
            assertFalse(body.contains(relationId));
        }
    }

    static class IdentityStub {
        final HttpServer server;
        final AtomicBoolean unavailable = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();

        IdentityStub() {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
                server.createContext("/api/v1/users/me", this::currentUser);
                server.start();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        void reset() {
            unavailable.set(false);
            calls.set(0);
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        void currentUser(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            if (unavailable.get()) {
                reply(exchange, 503, "{}");
                return;
            }
            String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
            if (userId == null || userId.isBlank()) {
                reply(exchange, 401, "{}");
                return;
            }
            String accountStatus = INACTIVE_USER.equals(userId) ? "INACTIVE" : "ACTIVE";
            String systemRole = GOVERNANCE_ADMIN.equals(userId) ? "GOVERNANCE_ADMIN" : "USER";
            reply(exchange, 200, "{\"id\":\"" + userId + "\",\"accountStatus\":\""
                    + accountStatus + "\",\"systemRole\":\"" + systemRole + "\"}");
        }

        private void reply(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    static class TestDatabase {
        final String url;
        final String username;
        final String password;
        final MySQLContainer<?> container;

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
                    .withDatabaseName("adoption_relation_authorization_test")
                    .withUsername("test")
                    .withPassword("test");
            container.start();
            return new TestDatabase(container.getJdbcUrl(), container.getUsername(),
                    container.getPassword(), container);
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
