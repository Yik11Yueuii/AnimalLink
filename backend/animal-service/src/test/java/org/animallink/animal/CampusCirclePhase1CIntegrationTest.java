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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CampusCirclePhase1CIntegrationTest {
    private static final String MEMBER_ID = "00000000-0000-0000-0000-000000000201";
    private static final String NON_MEMBER_ID = "00000000-0000-0000-0000-000000000202";
    private static final String SOCIAL_ID = "00000000-0000-0000-0000-000000000203";
    private static final String FAILURE_ID = "00000000-0000-0000-0000-000000000204";
    private static final String ADMIN_ID = "00000000-0000-0000-0000-000000000299";
    private static final String CAMPUS_ID = "10000000-0000-0000-0000-000000000201";
    private static final String OTHER_CAMPUS_ID = "10000000-0000-0000-0000-000000000202";
    private static final String ANIMAL_ID = "20000000-0000-0000-0000-000000000201";
    private static final String OTHER_ANIMAL_ID = "20000000-0000-0000-0000-000000000202";
    private static final IdentityStub IDENTITY = new IdentityStub();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_circle_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("animallink.identity.base-url", IDENTITY::baseUrl);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM post_like");
        jdbcTemplate.update("DELETE FROM comment");
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM post");
        jdbcTemplate.update("DELETE FROM animal_follow");
        jdbcTemplate.update("DELETE FROM timeline_entry");
        jdbcTemplate.update("DELETE FROM animal_media");
        jdbcTemplate.update("DELETE FROM animal");
        insertAnimal(ANIMAL_ID, CAMPUS_ID, "小橘", "CAT", "ACTIVE");
        insertAnimal(OTHER_ANIMAL_ID, OTHER_CAMPUS_ID, "阿黄", "DOG", "ACTIVE");
    }

    @AfterAll
    static void stopIdentityStub() {
        IDENTITY.stop();
    }

    @Test
    void publicFeedIsCampusIsolatedOrderedAndBatchEnriched() throws Exception {
        String older = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "较早动态", "ACTIVE");
        String newer = insertPost(CAMPUS_ID, null, MEMBER_ID, "较新动态", "ACTIVE");
        insertPost(OTHER_CAMPUS_ID, OTHER_ANIMAL_ID, ADMIN_ID, "其他校园", "ACTIVE");
        insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "隐藏动态", "HIDDEN");
        jdbcTemplate.update("INSERT INTO post_like (user_id, post_id) VALUES (?, ?)", NON_MEMBER_ID, newer);
        jdbcTemplate.update("""
                INSERT INTO comment (id, post_id, author_user_id, content, status)
                VALUES (?, ?, ?, '看到啦', 'ACTIVE')
                """, UUID.randomUUID().toString(), newer, MEMBER_ID);

        mockMvc.perform(get("/api/v1/campuses/{campusId}/feed", CAMPUS_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newer))
                .andExpect(jsonPath("$.items[0].author.displayName").value("成员用户"))
                .andExpect(jsonPath("$.items[0].animal").doesNotExist())
                .andExpect(jsonPath("$.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.items[0].commentCount").value(1))
                .andExpect(jsonPath("$.items[1].id").value(older))
                .andExpect(jsonPath("$.items[1].animal.id").value(ANIMAL_ID));

        mockMvc.perform(get("/api/v1/campuses/{campusId}/feed", CAMPUS_ID)
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newer));
        mockMvc.perform(get("/api/v1/campuses/{campusId}/feed", CAMPUS_ID)
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(older));
        mockMvc.perform(get("/api/v1/posts/{postId}", older))
                .andExpect(status().isOk()).andExpect(jsonPath("$.animal.displayName").value("小橘"));
    }

    @Test
    void publishingRequiresCampusMembershipAndValidSameCampusAnimal() throws Exception {
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, ANIMAL_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campusId").value(CAMPUS_ID))
                .andExpect(jsonPath("$.author.displayName").value("成员用户"))
                .andExpect(jsonPath("$.animal.id").value(ANIMAL_ID));

        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", NON_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", SOCIAL_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON)
                        .content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postRequest(CAMPUS_ID, OTHER_ANIMAL_ID)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postRequest(CAMPUS_ID, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.animal").doesNotExist());
        mockMvc.perform(post("/api/v1/posts").header("X-User-Id", FAILURE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(postRequest(CAMPUS_ID, null)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void commentsRequireMembershipWhileLikesRequireOnlyLoginAndAreUnique() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "可互动动态", "ACTIVE");
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", postId)
                        .header("X-User-Id", MEMBER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"状态看起来不错\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.displayName").value("成员用户"));
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", postId)
                        .header("X-User-Id", NON_MEMBER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"无成员权限\"}"))
                .andExpect(status().isForbidden());
        String hiddenPost = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "隐藏", "HIDDEN");
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", hiddenPost)
                        .header("X-User-Id", MEMBER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"不可评论\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/posts/{postId}/like", postId)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(post("/api/v1/posts/{postId}/like", postId)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/posts/{postId}/like", postId)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(delete("/api/v1/posts/{postId}/like", postId)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void followsAreLoginScopedUniquePaginatedAndHideArchivedAnimals() throws Exception {
        mockMvc.perform(post("/api/v1/animals/{animalId}/follow", ANIMAL_ID)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(post("/api/v1/animals/{animalId}/follow", ANIMAL_ID)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/users/me/animal-follows")
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].animal.id").value(ANIMAL_ID));

        jdbcTemplate.update("UPDATE animal SET identity_status = 'ARCHIVED' WHERE id = ?", ANIMAL_ID);
        mockMvc.perform(get("/api/v1/users/me/animal-follows")
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(delete("/api/v1/animals/{animalId}/follow", ANIMAL_ID)
                        .header("X-User-Id", SOCIAL_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void governanceHideIsAuthoritativeAndNeverWritesTimeline() throws Exception {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "待治理动态", "ACTIVE");
        mockMvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId)
                        .header("X-User-Id", MEMBER_ID).header("X-System-Role", "GOVERNANCE_ADMIN"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId)
                        .header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HIDDEN"));
        mockMvc.perform(get("/api/v1/posts/{postId}", postId)).andExpect(status().isNotFound());
        Long timelineCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM timeline_entry", Long.class);
        assertThat(timelineCount).isZero();
    }

    @Test
    void databaseConstraintsEnforceLikeFollowAndAnimalReferences() {
        String postId = insertPost(CAMPUS_ID, ANIMAL_ID, MEMBER_ID, "约束测试", "ACTIVE");
        jdbcTemplate.update("INSERT INTO post_like (user_id, post_id) VALUES (?, ?)", MEMBER_ID, postId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO post_like (user_id, post_id) VALUES (?, ?)", MEMBER_ID, postId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO post (id, campus_id, animal_id, author_user_id, text_content)
                VALUES (?, ?, ?, ?, '无效关联')
                """, UUID.randomUUID().toString(), CAMPUS_ID, UUID.randomUUID().toString(), MEMBER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("INSERT INTO animal_follow (user_id, animal_id) VALUES (?, ?)",
                MEMBER_ID, ANIMAL_ID);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO animal_follow (user_id, animal_id) VALUES (?, ?)", MEMBER_ID, ANIMAL_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO comment (id, post_id, author_user_id, content, status)
                VALUES (?, ?, ?, '无效 Post', 'ACTIVE')
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), MEMBER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertAnimal(String id, String campusId, String name, String species, String status) {
        jdbcTemplate.update("""
                INSERT INTO animal
                    (id, campus_id, display_name, species, sex, identity_status,
                     adoption_status, current_context)
                VALUES (?, ?, ?, ?, 'UNKNOWN', ?, 'NOT_OPEN', 'CAMPUS')
                """, id, campusId, name, species, status);
    }

    private String insertPost(String campusId, String animalId, String author, String text, String status) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO post
                    (id, campus_id, animal_id, author_user_id, post_type, text_content,
                     visibility, status, created_at)
                VALUES (?, ?, ?, ?, 'CAMPUS_POST', ?, 'PUBLIC', ?, CURRENT_TIMESTAMP(6))
                """, id, campusId, animalId, author, text, status);
        return id;
    }

    private String postRequest(String campusId, String animalId) {
        String animal = animalId == null ? "null" : "\"" + animalId + "\"";
        return """
                {"campusId":"%s","animalId":%s,"textContent":"校园动态","media":[]}
                """.formatted(campusId, animal);
    }

    private static final class IdentityStub {
        private final HttpServer server;

        private IdentityStub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/api/v1/users/me", this::user);
                server.createContext("/internal/v1/users/summaries", this::summaries);
                server.createContext("/internal/v1/users", this::membership);
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
            if (MEMBER_ID.equals(userId)) {
                respond(exchange, 200, userJson(MEMBER_ID, "成员用户", "USER"));
            } else if (NON_MEMBER_ID.equals(userId)) {
                respond(exchange, 200, userJson(NON_MEMBER_ID, "西校成员", "USER"));
            } else if (SOCIAL_ID.equals(userId)) {
                respond(exchange, 200, userJson(SOCIAL_ID, "社会用户", "USER"));
            } else if (FAILURE_ID.equals(userId)) {
                respond(exchange, 200, userJson(FAILURE_ID, "依赖失败用户", "USER"));
            } else if (ADMIN_ID.equals(userId)) {
                respond(exchange, 200, userJson(ADMIN_ID, "治理管理员", "GOVERNANCE_ADMIN"));
            } else {
                respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
            }
        }

        private void membership(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/" + MEMBER_ID + "/campus-memberships/" + CAMPUS_ID)) {
                respond(exchange, 200,
                        "{\"exists\":true,\"membershipType\":\"STUDENT\",\"status\":\"ACTIVE\"}");
            } else if (path.contains("/" + NON_MEMBER_ID + "/campus-memberships/" + OTHER_CAMPUS_ID)) {
                respond(exchange, 200,
                        "{\"exists\":true,\"membershipType\":\"ALUMNI\",\"status\":\"ACTIVE\"}");
            } else if (path.contains("/" + FAILURE_ID + "/campus-memberships/")) {
                respond(exchange, 500, "{\"code\":\"FAILURE\"}");
            } else {
                respond(exchange, 200, "{\"exists\":false,\"membershipType\":null,\"status\":null}");
            }
        }

        private void summaries(HttpExchange exchange) throws IOException {
            respond(exchange, 200, "[" + summaryJson(MEMBER_ID, "成员用户") + ","
                    + summaryJson(NON_MEMBER_ID, "西校成员") + ","
                    + summaryJson(SOCIAL_ID, "社会用户") + ","
                    + summaryJson(ADMIN_ID, "治理管理员") + "]");
        }

        private String userJson(String id, String name, String role) {
            return "{\"id\":\"" + id + "\",\"displayName\":\"" + name
                    + "\",\"accountStatus\":\"ACTIVE\",\"systemRole\":\"" + role + "\"}";
        }

        private String summaryJson(String id, String name) {
            return "{\"id\":\"" + id + "\",\"displayName\":\"" + name + "\"}";
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
