package org.animallink.adoption;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.animallink.adoption.infrastructure.AdoptionCompletedOutboxPublisher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdoptionRelationTerminationIntegrationTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String INACTIVE_ADMIN = "00000000-0000-0000-0000-000000000013";
    private static final String USER = "00000000-0000-0000-0000-000000000014";
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
    @SpyBean JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
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

    @AfterAll
    static void stop() {
        IDENTITY.stop();
        DATABASE.close();
    }

    @Test
    void governanceEndsActiveRelationAndWritesExactOutbox() throws Exception {
        String relation = activeRelation();
        String body = mvc.perform(end(relation, ADMIN, "  家庭情况变化  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.relationId").value(relation)).andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("家庭情况变化")).andExpect(jsonPath("$.endedAt").isNotEmpty()).andReturn().getResponse().getContentAsString();
        assertNotNull(json.readValue(body, new TypeReference<java.util.Map<String, Object>>() { }).get("activatedAt"));
        var row = jdbc.queryForMap("SELECT id,aggregate_type,aggregate_id,event_type,payload_json FROM outbox_event WHERE event_type='ADOPTION_RELATION_ENDED'");
        assertEquals("ADOPTION_RELATION", row.get("aggregate_type"));
        assertEquals(relation, row.get("aggregate_id"));
        var payload = json.readValue(row.get("payload_json").toString(), new TypeReference<java.util.Map<String, Object>>() { });
        assertEquals(java.util.Set.of("eventId", "eventType", "eventVersion", "occurredAt", "relationId", "animalId"), payload.keySet());
        assertEquals(row.get("id"), payload.get("eventId"));
        assertEquals("ADOPTION_RELATION_ENDED", payload.get("eventType"));
        assertEquals(1, payload.get("eventVersion"));
        assertEquals(relation, payload.get("relationId"));
        assertFalse(payload.containsKey("adopterUserId"));
        assertFalse(payload.containsKey("endReason"));
    }

    @Test
    void inactiveAndOrdinaryCallersAreRejected() throws Exception {
        String relation = activeRelation();
        mvc.perform(end(relation, INACTIVE_ADMIN, "reason")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
        mvc.perform(end(relation, USER, "reason")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM adoption_relation WHERE id=?", String.class, relation));
    }

    @Test
    void missingAndInvalidRequestsUseExistingErrors() throws Exception {
        mvc.perform(end(UUID.randomUUID().toString(), ADMIN, "reason")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ADOPTION_RELATION_NOT_FOUND"));
        String relation = activeRelation();
        mvc.perform(end(relation, ADMIN, " ")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(end(relation, ADMIN, "x".repeat(501))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void repeatWithDifferentReasonPreservesOriginalMetadata() throws Exception {
        String relation = activeRelation();
        String first = mvc.perform(end(relation, ADMIN, "first reason")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(end(relation, ADMIN, "different reason")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var firstBody = json.readValue(first, new TypeReference<java.util.Map<String, Object>>() { });
        var secondBody = json.readValue(second, new TypeReference<java.util.Map<String, Object>>() { });
        assertEquals("first reason", secondBody.get("endReason"));
        assertEquals(firstBody.get("endedAt"), secondBody.get("endedAt"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ADOPTION_RELATION_ENDED' AND aggregate_id=?", Integer.class, relation));
    }

    @RepeatedTest(10)
    void concurrentTerminationConvergesToOneEvent() throws Exception {
        String relation = activeRelation();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = pool.submit(() -> concurrent(relation, "first", ready, start));
        Future<Integer> second = pool.submit(() -> concurrent(relation, "second", ready, start));
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        assertEquals(200, first.get());
        assertEquals(200, second.get());
        pool.shutdown();
        assertEquals("ENDED", jdbc.queryForObject("SELECT status FROM adoption_relation WHERE id=?", String.class, relation));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ADOPTION_RELATION_ENDED' AND aggregate_id=?", Integer.class, relation));
    }

    @Test
    void outboxFailureRollsBackThenRetrySucceeds() throws Exception {
        String relation = activeRelation();
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (fail.get() && invocation.getArgument(0, String.class).startsWith("INSERT INTO outbox_event")) throw new DataAccessResourceFailureException("outbox unavailable");
            return invocation.callRealMethod();
        }).when(jdbc).update(anyString(), any(Object[].class));
        assertThrows(Exception.class, () -> mvc.perform(end(relation, ADMIN, "reason")));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM adoption_relation WHERE id=?", String.class, relation));
        assertNull(jdbc.queryForObject("SELECT ended_at FROM adoption_relation WHERE id=?", java.sql.Timestamp.class, relation));
        assertNull(jdbc.queryForObject("SELECT end_reason FROM adoption_relation WHERE id=?", String.class, relation));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ADOPTION_RELATION_ENDED'", Integer.class));
        fail.set(false);
        mvc.perform(end(relation, ADMIN, "reason")).andExpect(status().isOk());
        assertEquals("ENDED", jdbc.queryForObject("SELECT status FROM adoption_relation WHERE id=?", String.class, relation));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ADOPTION_RELATION_ENDED'", Integer.class));
    }

    @Test
    void publisherRoutesBothKnownEventsAndLeavesUnknownPending() {
        String completed = pendingOutbox("ADOPTION_COMPLETED");
        String ended = pendingOutbox("ADOPTION_RELATION_ENDED");
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> { invocation.getArgument(4, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null)); return null; })
                .when(rabbit).convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        assertEquals(2, new AdoptionCompletedOutboxPublisher(jdbc, rabbit, new TransactionTemplate(transactions)).publishBatch());
        ArgumentCaptor<String> routing = ArgumentCaptor.forClass(String.class);
        verify(rabbit, times(2)).convertAndSend(eq("animallink.domain"), routing.capture(), any(), any(), any(CorrelationData.class));
        assertEquals(java.util.Set.of("adoption.handover.completed", "adoption.relation.ended"), java.util.Set.copyOf(routing.getAllValues()));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?", String.class, completed));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?", String.class, ended));

        String unknown = pendingOutbox("UNKNOWN_EVENT");
        assertEquals(0, new AdoptionCompletedOutboxPublisher(jdbc, rabbit, new TransactionTemplate(transactions)).publishBatch());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?", String.class, unknown));
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM outbox_event WHERE id=?", Integer.class, unknown));
    }

    private String activeRelation() {
        String listing = UUID.randomUUID().toString();
        String application = UUID.randomUUID().toString();
        String selection = UUID.randomUUID().toString();
        String handover = UUID.randomUUID().toString();
        String relation = UUID.randomUUID().toString();
        String animal = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO adoption_listing(id,animal_id,status,title,description,publisher_user_id,created_at,updated_at,closed_at) VALUES(?,?, 'CLOSED','title','description',?,NOW(6),NOW(6),NOW(6))", listing, animal, ADMIN);
        jdbc.update("INSERT INTO adoption_application(id,listing_id,applicant_user_id,status,message,created_at,updated_at,reviewer_user_id,reviewed_at) VALUES(?,?,?,'APPROVED','message',NOW(6),NOW(6),?,NOW(6))", application, listing, USER, ADMIN);
        jdbc.update("INSERT INTO adoption_selection(id,listing_id,application_id,status,selected_by_user_id,selected_at) VALUES(?,?,?,'ACTIVE',?,NOW(6))", selection, listing, application, ADMIN);
        jdbc.update("INSERT INTO adoption_handover(id,selection_id,status,scheduled_at,initiated_by_user_id,initiated_at,completed_by_user_id,completed_at,created_at,updated_at) VALUES(?,?, 'COMPLETED',NOW(6),?,NOW(6),?,NOW(6),NOW(6),NOW(6))", handover, selection, ADMIN, ADMIN);
        jdbc.update("INSERT INTO adoption_relation(id,animal_id,adopter_user_id,handover_id,status,activated_at,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',NOW(6),NOW(6),NOW(6))", relation, animal, USER, handover);
        return relation;
    }

    private String pendingOutbox(String eventType) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,available_at,created_at,updated_at) VALUES(?,'ADOPTION_RELATION',?,?,CAST(? AS JSON),'PENDING',NOW(6),NOW(6),NOW(6))", id, UUID.randomUUID().toString(), eventType, "{\"eventId\":\"" + id + "\"}");
        return id;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder end(String relation, String actor, String reason) {
        return post("/api/v1/governance/relations/{relationId}/end", relation).header("X-User-Id", actor).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}");
    }

    private int concurrent(String relation, String reason, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return mvc.perform(end(relation, ADMIN, reason)).andReturn().getResponse().getStatus();
    }

    static class Stub {
        final HttpServer server;
        Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/api/v1/users/me", this::user); server.start(); } catch (IOException exception) { throw new RuntimeException(exception); } }
        String url() { return "http://localhost:" + server.getAddress().getPort(); }
        void stop() { server.stop(0); }
        void user(HttpExchange exchange) throws IOException {
            String id = exchange.getRequestHeaders().getFirst("X-User-Id");
            if (id == null) { reply(exchange, 401, "{}"); return; }
            String account = INACTIVE_ADMIN.equals(id) ? "INACTIVE" : "ACTIVE";
            String role = (ADMIN.equals(id) || INACTIVE_ADMIN.equals(id)) ? "GOVERNANCE_ADMIN" : "USER";
            reply(exchange, 200, "{\"id\":\"" + id + "\",\"accountStatus\":\"" + account + "\",\"systemRole\":\"" + role + "\"}");
        }
        void reply(HttpExchange exchange, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }
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
                int port = localPort();
                return new TestDatabase("jdbc:mysql://127.0.0.1:" + port + "/" + required("ANIMALLINK_TEST_MYSQL_DATABASE") + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC", required("ANIMALLINK_TEST_MYSQL_USERNAME"), required("ANIMALLINK_TEST_MYSQL_PASSWORD"), null);
            }
            MySQLContainer<?> container = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("adoption_relation_termination_test").withUsername("test").withPassword("test");
            container.start();
            return new TestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword(), container);
        }

        private static int localPort() {
            try {
                int port = Integer.parseInt(required("ANIMALLINK_TEST_MYSQL_PORT"));
                if (port < 1 || port > 65535) throw new IllegalArgumentException();
                return port;
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("ANIMALLINK_TEST_MYSQL_PORT must be a valid localhost port");
            }
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for the localhost external MySQL fallback");
            return value;
        }

        String url() { return url; }
        String username() { return username; }
        String password() { return password; }
        void close() { if (container != null) container.stop(); }
    }
}
