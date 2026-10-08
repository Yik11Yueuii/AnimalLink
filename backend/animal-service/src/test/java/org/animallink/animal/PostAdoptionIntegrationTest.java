package org.animallink.animal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PostAdoptionIntegrationTest {
    private static final String ADOPTER = "00000000-0000-0000-0000-000000000011";
    private static final String INACTIVE = "00000000-0000-0000-0000-000000000013";
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String CAMPUS = "10000000-0000-0000-0000-000000000501";
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000501";
    private static final IdentityStub IDENTITY = new IdentityStub();
    private static final AdoptionStub ADOPTION = new AdoptionStub();
    private static final TestDatabase DATABASE = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.messaging.enabled", () -> false);
        registry.add("animallink.identity.base-url", IDENTITY::url);
        registry.add("animallink.adoption.base-url", ADOPTION::url);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @SpyBean JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        IDENTITY.reset();
        ADOPTION.reset();
        jdbc.update("DELETE FROM post_like");
        jdbc.update("DELETE FROM comment");
        jdbc.update("DELETE FROM post_media");
        jdbc.update("DELETE FROM post");
        jdbc.update("DELETE FROM timeline_entry");
        jdbc.update("DELETE FROM animal_follow");
        jdbc.update("DELETE FROM animal_media");
        jdbc.update("DELETE FROM animal");
        insertAnimal("ACTIVE", "ADOPTED", "ADOPTED_HOME");
    }

    @AfterEach
    void restoreJdbcSpy() {
        org.mockito.Mockito.reset(jdbc);
    }

    @AfterAll
    static void close() {
        IDENTITY.stop();
        ADOPTION.stop();
        DATABASE.close();
    }

    @Test
    void activeAdopterPublishesWithoutCampusMembershipAndRelationStaysPrivate() throws Exception {
        String response = mvc.perform(publish("领养后第一条动态", "[]", ADOPTER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campusId").value(CAMPUS))
                .andExpect(jsonPath("$.postType").value("POST_ADOPTION"))
                .andExpect(jsonPath("$.author.id").value(ADOPTER))
                .andExpect(jsonPath("$.relationId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String postId = jsonValue(response, "id");
        assertThat(jdbc.queryForObject("SELECT relation_id FROM post WHERE id = ?", String.class, postId))
                .isEqualTo(ADOPTION.relationId);
        assertThat(jdbc.queryForMap("SELECT source_type, source_id, entry_type, title, summary, visibility "
                        + "FROM timeline_entry WHERE source_id = ?", postId))
                .containsEntry("source_type", "POST").containsEntry("entry_type", "POST_ADOPTION_PUBLISHED")
                .containsEntry("title", "领养后动态已发布").containsEntry("summary", null)
                .containsEntry("visibility", "PUBLIC");
        assertThat(ADOPTION.calls.get()).isEqualTo(1);
    }

    @Test
    void mediaValidationAndMultiplePostsFollowExistingPostRules() throws Exception {
        String media = "[{\"objectKey\":\"post-adoption/a.jpg\",\"contentType\":\"image/jpeg\",\"mediaType\":\"IMAGE\",\"sizeBytes\":3,\"sortOrder\":0}]";
        String first = jsonValue(mvc.perform(publish("带媒体", media, ADOPTER)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        String second = jsonValue(mvc.perform(publish("第二条", "[]", ADOPTER)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE relation_id = ?", Integer.class,
                ADOPTION.relationId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry WHERE entry_type = 'POST_ADOPTION_PUBLISHED'", Integer.class)).isEqualTo(2);
        String seven = "[" + media.substring(1, media.length() - 1) + "," + media.substring(1, media.length() - 1)
                + "," + media.substring(1, media.length() - 1) + "," + media.substring(1, media.length() - 1)
                + "," + media.substring(1, media.length() - 1) + "," + media.substring(1, media.length() - 1)
                + "," + media.substring(1, media.length() - 1) + "]";
        mvc.perform(publish("过多媒体", seven, ADOPTER)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void relationDenialAndDependencyFailureLeaveNoLocalRows() throws Exception {
        ADOPTION.mode = AdoptionStub.Mode.FORBIDDEN;
        mvc.perform(publish("已结束关系", "[]", ADOPTER)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_ADOPTION_RELATION_REQUIRED"));
        ADOPTION.mode = AdoptionStub.Mode.UNAVAILABLE;
        mvc.perform(publish("依赖不可用", "[]", ADOPTER)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        assertNoPublishedRows();
    }

    @Test
    void localGatesRejectBeforeAdoptionLookupAndHistoricalPostRemainsReadable() throws Exception {
        String postId = jsonValue(mvc.perform(publish("历史动态", "[]", ADOPTER)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        ADOPTION.mode = AdoptionStub.Mode.FORBIDDEN;
        mvc.perform(publish("新动态", "[]", ADOPTER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/posts/{postId}", postId)).andExpect(status().isOk());
        int before = ADOPTION.calls.get();
        jdbc.update("UPDATE animal SET adoption_status = 'OPEN', current_context = 'CAMPUS' WHERE id = ?", ANIMAL);
        mvc.perform(publish("本地状态错误", "[]", ADOPTER)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(ADOPTION.calls.get()).isEqualTo(before);
        mvc.perform(publish("未激活账号", "[]", INACTIVE)).andExpect(status().isForbidden());
    }

    @Test
    void timelineFailureRollsBackPostAndMedia() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("INSERT INTO timeline_entry")) {
                throw new DataAccessResourceFailureException("forced timeline failure");
            }
            return invocation.callRealMethod();
        }).when(jdbc).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
        assertThatThrownBy(() -> mvc.perform(publish("应回滚", "[{\"objectKey\":\"post-adoption/rollback.jpg\",\"contentType\":\"image/jpeg\",\"mediaType\":\"IMAGE\",\"sizeBytes\":1,\"sortOrder\":0}]", ADOPTER)))
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);
        assertNoPublishedRows();
    }

    @Test
    void governanceHidesPostAdoptionAndOrdinaryPostStillRequiresMembership() throws Exception {
        String postId = jsonValue(mvc.perform(publish("待隐藏", "[]", ADOPTER)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        mvc.perform(post("/api/v1/admin/posts/{postId}/hide", postId).header("X-User-Id", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HIDDEN"));
        mvc.perform(get("/api/v1/posts/{postId}", postId)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/posts").header("X-User-Id", ADOPTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campusId\":\"" + CAMPUS + "\",\"textContent\":\"普通动态\",\"media\":[]}"))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publish(
            String text, String media, String user) {
        return post("/api/v1/animals/{animalId}/post-adoption-posts", ANIMAL)
                .header("X-User-Id", user).header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"textContent\":\"" + text + "\",\"media\":" + media + "}");
    }

    private void insertAnimal(String identity, String adoption, String context) {
        jdbc.update("INSERT INTO animal (id,campus_id,display_name,species,sex,identity_status,adoption_status,current_context) VALUES (?,?, '团团','CAT','UNKNOWN',?,?,?)",
                ANIMAL, CAMPUS, identity, adoption, context);
    }

    private void assertNoPublishedRows() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_media", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_entry", Integer.class)).isZero();
    }

    private String jsonValue(String json, String field) throws IOException {
        return objectMapper.readTree(json).path(field).asText();
    }

    private static final class IdentityStub {
        private final HttpServer server;
        private IdentityStub() { try { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/users/me", this::user); server.createContext("/internal/v1/users/summaries", this::summaries); server.createContext("/internal/v1/users", this::membership); server.start(); } catch (IOException e) { throw new IllegalStateException(e); } }
        void reset() { }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void stop() { server.stop(0); }
        private void user(HttpExchange x) throws IOException { String id = x.getRequestHeaders().getFirst("X-User-Id"); if (id == null) { reply(x, 401, "{}"); return; }
            String account = INACTIVE.equals(id) ? "INACTIVE" : "ACTIVE"; String role = ADMIN.equals(id) ? "GOVERNANCE_ADMIN" : "USER";
            reply(x, 200, "{\"id\":\"" + id + "\",\"displayName\":\"用户\",\"accountStatus\":\"" + account + "\",\"systemRole\":\"" + role + "\"}"); }
        private void summaries(HttpExchange x) throws IOException { reply(x, 200, "[{\"id\":\"" + ADOPTER + "\",\"displayName\":\"用户\"}]"); }
        private void membership(HttpExchange x) throws IOException { reply(x, 200, "{\"exists\":false,\"membershipType\":null,\"status\":null}"); }
    }

    private static final class AdoptionStub {
        enum Mode { ACTIVE, FORBIDDEN, UNAVAILABLE }
        private final HttpServer server; final AtomicInteger calls = new AtomicInteger(); final String relationId = "30000000-0000-0000-0000-000000000501"; volatile Mode mode = Mode.ACTIVE;
        private AdoptionStub() { try { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.createContext("/internal/v1/adoption-relations/active", this::active); server.start(); } catch (IOException e) { throw new IllegalStateException(e); } }
        void reset() { calls.set(0); mode = Mode.ACTIVE; }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void stop() { server.stop(0); }
        private void active(HttpExchange x) throws IOException { calls.incrementAndGet(); if (!"animal-service".equals(x.getRequestHeaders().getFirst("X-Internal-Service"))) { reply(x, 500, "{}"); return; }
            if (mode == Mode.FORBIDDEN) { reply(x, 403, "{\"code\":\"ACTIVE_ADOPTION_RELATION_REQUIRED\"}"); return; }
            if (mode == Mode.UNAVAILABLE) { reply(x, 503, "{}"); return; }
            reply(x, 200, "{\"relationId\":\"" + relationId + "\"}"); }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }

    private static final class TestDatabase {
        final String url; final String username; final String password; final MySQLContainer<?> container;
        private TestDatabase(String url, String username, String password, MySQLContainer<?> container) { this.url = url; this.username = username; this.password = password; this.container = container; }
        static TestDatabase create() { if ("true".equalsIgnoreCase(System.getenv("ANIMALLINK_TEST_EXTERNAL_MYSQL"))) return new TestDatabase("jdbc:mysql://127.0.0.1:" + required("ANIMALLINK_TEST_MYSQL_PORT") + "/" + required("ANIMALLINK_TEST_MYSQL_DATABASE") + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC", required("ANIMALLINK_TEST_MYSQL_USERNAME"), required("ANIMALLINK_TEST_MYSQL_PASSWORD"), null);
            MySQLContainer<?> c = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("animal_post_adoption_test").withUsername("test").withPassword("test"); c.start(); return new TestDatabase(c.getJdbcUrl(), c.getUsername(), c.getPassword(), c); }
        private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for external MySQL"); return value; }
        String url() { return url; } String username() { return username; } String password() { return password; } void close() { if (container != null) container.stop(); }
    }
}
