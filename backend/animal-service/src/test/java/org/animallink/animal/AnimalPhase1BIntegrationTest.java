package org.animallink.animal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AnimalPhase1BIntegrationTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000101";
    private static final String ADMIN_ID = "00000000-0000-0000-0000-000000000199";
    private static final String IDENTITY_FAILURE_USER_ID = "00000000-0000-0000-0000-000000000198";
    private static final String CAMPUS_ID = "10000000-0000-0000-0000-000000000101";
    private static final String OTHER_CAMPUS_ID = "10000000-0000-0000-0000-000000000102";
    private static final String INVALID_CAMPUS_ID = "10000000-0000-0000-0000-000000000404";
    private static final String INACTIVE_CAMPUS_ID = "10000000-0000-0000-0000-000000000409";
    private static final String FAILURE_CAMPUS_ID = "10000000-0000-0000-0000-000000000503";
    private static final String ANIMAL_ONE = "20000000-0000-0000-0000-000000000101";
    private static final String ANIMAL_TWO = "20000000-0000-0000-0000-000000000102";
    private static final String ANIMAL_OTHER = "20000000-0000-0000-0000-000000000103";
    private static final IdentityStub IDENTITY = new IdentityStub();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_animal_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("animallink.identity.base-url", IDENTITY::baseUrl);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM timeline_entry");
        jdbcTemplate.update("DELETE FROM animal_media");
        jdbcTemplate.update("DELETE FROM animal");
        insertAnimal(ANIMAL_ONE, CAMPUS_ID, "小橘", "CAT", "ACTIVE");
        insertAnimal(ANIMAL_TWO, CAMPUS_ID, "黑豆", "CAT", "ARCHIVED");
        insertAnimal(ANIMAL_OTHER, OTHER_CAMPUS_ID, "阿黄", "DOG", "ACTIVE");
    }

    @AfterAll
    static void stopIdentityStub() {
        IDENTITY.stop();
    }

    @Test
    void publicQueriesFilterSearchPaginateAndHideArchivedAnimals() throws Exception {
        insertAnimal(UUID.randomUUID().toString(), CAMPUS_ID, "小白", "DOG", "ACTIVE");

        mockMvc.perform(get("/api/v1/animals").param("campusId", CAMPUS_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].displayName").value("小橘"))
                .andExpect(jsonPath("$.items[1].displayName").value("小白"));

        mockMvc.perform(get("/api/v1/animals").param("campusId", CAMPUS_ID)
                        .param("species", "DOG").param("q", "白"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].species").value("DOG"));

        mockMvc.perform(get("/api/v1/animals").param("campusId", CAMPUS_ID)
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/v1/animals/{id}", ANIMAL_TWO))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/animals/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailReturnsOnlyPublicMediaMetadata() throws Exception {
        insertMedia(ANIMAL_ONE, "PUBLIC", 2);
        insertMedia(ANIMAL_ONE, "RESTRICTED", 1);

        mockMvc.perform(get("/api/v1/animals/{id}", ANIMAL_ONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ANIMAL_ONE))
                .andExpect(jsonPath("$.media.length()").value(1))
                .andExpect(jsonPath("$.media[0].sortOrder").value(2));
    }

    @Test
    void ordinaryUserCannotMutateAndForgedRoleHeaderIsIgnored() throws Exception {
        String create = createRequest(CAMPUS_ID, "新成员");
        mockMvc.perform(post("/api/v1/admin/animals")
                        .header("X-User-Id", USER_ID)
                        .header("X-System-Role", "GOVERNANCE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/admin/animals/{id}", ANIMAL_ONE)
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"伪造修改\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/animals/{id}/archive", ANIMAL_ONE)
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCreatesCorrectsAndArchivesWithControlledLifecycle() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/animals")
                        .header("X-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(CAMPUS_ID, "新成员")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.identityStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.adoptionStatus").value("NOT_OPEN"))
                .andExpect(jsonPath("$.currentContext").value("CAMPUS"))
                .andReturn().getResponse().getContentAsString();
        String createdId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("id").asText();

        mockMvc.perform(patch("/api/v1/admin/animals/{id}", createdId)
                        .header("X-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"新名字\",\"sex\":\"FEMALE\",\"identityStatus\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("新名字"))
                .andExpect(jsonPath("$.sex").value("FEMALE"))
                .andExpect(jsonPath("$.identityStatus").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/admin/animals/{id}/archive", createdId)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityStatus").value("ARCHIVED"));

        mockMvc.perform(post("/api/v1/admin/animals/{id}/archive", createdId)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/animals").param("campusId", CAMPUS_ID).param("q", "新名字"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void campusAndIdentityFailuresAreMappedWithoutBypass() throws Exception {
        mockMvc.perform(post("/api/v1/admin/animals")
                        .header("X-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(INVALID_CAMPUS_ID, "无效校园动物")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/admin/animals")
                        .header("X-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(INACTIVE_CAMPUS_ID, "停用校园动物")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mockMvc.perform(post("/api/v1/admin/animals")
                        .header("X-User-Id", ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(FAILURE_CAMPUS_ID, "依赖失败")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));

        mockMvc.perform(post("/api/v1/admin/animals")
                        .header("X-User-Id", IDENTITY_FAILURE_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(CAMPUS_ID, "权限依赖失败")))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/api/v1/admin/animals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(CAMPUS_ID, "未登录")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void timelineIsOrderedPaginatedIsolatedAndRequiresExistingActiveAnimal() throws Exception {
        insertTimeline(ANIMAL_ONE, "2026-09-20T10:00:00Z");
        String newestId = insertTimeline(ANIMAL_ONE, "2026-09-22T10:00:00Z");
        insertTimeline(ANIMAL_OTHER, "2026-09-23T10:00:00Z");

        mockMvc.perform(get("/api/v1/animals/{id}/timeline", ANIMAL_ONE)
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(newestId));

        mockMvc.perform(get("/api/v1/animals/{id}/timeline", ANIMAL_TWO))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/animals/{id}/timeline", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void databaseEnforcesIdentityForeignKeysAndProjectionIdempotency() {
        assertThatThrownBy(() -> insertAnimal(ANIMAL_ONE, CAMPUS_ID, "重复", "CAT", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertMedia(UUID.randomUUID().toString(), "PUBLIC", 0))
                .isInstanceOf(DataIntegrityViolationException.class);

        String sourceId = UUID.randomUUID().toString();
        insertTimeline(ANIMAL_ONE, sourceId, "2026-09-21T10:00:00Z");
        assertThatThrownBy(() -> insertTimeline(ANIMAL_OTHER, sourceId, "2026-09-22T10:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertTimeline(UUID.randomUUID().toString(), "2026-09-22T10:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void internalCandidateRecallIsCampusScopedActiveSpeciesAwareAndBatched() throws Exception {
        String activeDog = "20000000-0000-0000-0000-000000000104";
        insertAnimal(activeDog, CAMPUS_ID, "校内犬", "DOG", "ACTIVE");
        insertMedia(ANIMAL_ONE, "PUBLIC", 0);
        insertMedia(ANIMAL_ONE, "RESTRICTED", 1);
        insertTimeline(ANIMAL_ONE, "2026-09-22T10:00:00Z");

        String catRequest = """
                {"campusId":"%s","species":"CAT","limit":20}
                """.formatted(CAMPUS_ID);
        mockMvc.perform(post("/internal/v1/animals/candidates")
                        .header("X-Internal-Service", "intelligence-service")
                        .contentType(MediaType.APPLICATION_JSON).content(catRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].id").value(ANIMAL_ONE))
                .andExpect(jsonPath("$.candidates[0].media.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].lastSeenAt").value("2026-09-22T10:00:00Z"));

        String unknownRequest = """
                {"campusId":"%s","species":"UNKNOWN","limit":20}
                """.formatted(CAMPUS_ID);
        mockMvc.perform(post("/internal/v1/animals/candidates")
                        .header("X-Internal-Service", "intelligence-service")
                        .contentType(MediaType.APPLICATION_JSON).content(unknownRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(2));

        mockMvc.perform(post("/internal/v1/animals/candidates")
                        .header("X-Internal-Service", "intelligence-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campusId\":\"10000000-0000-0000-0000-000000000999\",\"species\":\"CAT\",\"limit\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    private void insertAnimal(String id, String campusId, String name, String species, String status) {
        jdbcTemplate.update("""
                INSERT INTO animal
                    (id, campus_id, display_name, species, sex, identity_status,
                     adoption_status, current_context)
                VALUES (?, ?, ?, ?, 'UNKNOWN', ?, 'NOT_OPEN', 'CAMPUS')
                """, id, campusId, name, species, status);
    }

    private void insertMedia(String animalId, String visibility, int sortOrder) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO animal_media
                    (id, animal_id, object_key, content_type, media_type, sort_order, visibility)
                VALUES (?, ?, ?, 'image/jpeg', 'IMAGE', ?, ?)
                """, id, animalId, "animals/" + id + ".jpg", sortOrder, visibility);
    }

    private String insertTimeline(String animalId, String occurredAt) {
        return insertTimeline(animalId, UUID.randomUUID().toString(), occurredAt);
    }

    private String insertTimeline(String animalId, String sourceId, String occurredAt) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO timeline_entry
                    (id, animal_id, source_type, source_id, entry_type, title, occurred_at)
                VALUES (?, ?, 'TEST_SOURCE', ?, 'TEST_ENTRY', '测试节点', ?)
                """, id, animalId, sourceId, java.sql.Timestamp.from(Instant.parse(occurredAt)));
        return id;
    }

    private String createRequest(String campusId, String displayName) {
        return """
                {"campusId":"%s","displayName":"%s","species":"CAT",
                 "coatColor":"橘白","typicalArea":"教学楼附近"}
                """.formatted(campusId, displayName);
    }

    private static final class IdentityStub {
        private final HttpServer server;

        private IdentityStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/api/v1/users/me", this::user);
                server.createContext("/internal/v1/campuses", this::campus);
                server.start();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private void user(HttpExchange exchange) throws IOException {
            String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
            if (IDENTITY_FAILURE_USER_ID.equals(userId)) {
                respond(exchange, 500, "{\"code\":\"FAILURE\"}");
            } else if (ADMIN_ID.equals(userId)) {
                respond(exchange, 200, userJson(ADMIN_ID, "GOVERNANCE_ADMIN"));
            } else if (USER_ID.equals(userId)) {
                respond(exchange, 200, userJson(USER_ID, "USER"));
            } else {
                respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
            }
        }

        private void campus(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/" + FAILURE_CAMPUS_ID)) {
                respond(exchange, 500, "{\"code\":\"FAILURE\"}");
            } else if (path.endsWith("/" + INACTIVE_CAMPUS_ID)) {
                respond(exchange, 200, "{\"id\":\"" + INACTIVE_CAMPUS_ID
                        + "\",\"status\":\"INACTIVE\"}");
            } else if (path.endsWith("/" + CAMPUS_ID) || path.endsWith("/" + OTHER_CAMPUS_ID)) {
                String id = path.substring(path.lastIndexOf('/') + 1);
                respond(exchange, 200, "{\"id\":\"" + id + "\",\"status\":\"ACTIVE\"}");
            } else {
                respond(exchange, 404, "{\"code\":\"RESOURCE_NOT_FOUND\"}");
            }
        }

        private String userJson(String id, String role) {
            return "{\"id\":\"" + id + "\",\"accountStatus\":\"ACTIVE\",\"systemRole\":\""
                    + role + "\"}";
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
