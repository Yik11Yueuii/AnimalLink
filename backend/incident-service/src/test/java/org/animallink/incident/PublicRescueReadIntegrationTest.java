package org.animallink.incident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies the anonymous projection against MySQL and the real Flyway schema. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PublicRescueReadIntegrationTest {
    static final String CAMPUS = "72000000-0000-4000-8000-000000000001";
    static final String OTHER_CAMPUS = "72000000-0000-4000-8000-000000000002";
    static final String EVENT = "72000000-0000-4000-8000-000000000011";
    static final String OTHER_EVENT = "72000000-0000-4000-8000-000000000012";
    static final String REPORTED_EVENT = "72000000-0000-4000-8000-000000000013";
    static final String ANIMAL = "72000000-0000-4000-8000-000000000021";
    static final AnimalStub ANIMAL_STUB = new AnimalStub();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("public_rescue_read").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.messaging.enabled", () -> false);
        registry.add("animallink.minio.access-key", () -> "test");
        registry.add("animallink.minio.secret-key", () -> "test-secret");
        registry.add("animallink.animal.base-url", ANIMAL_STUB::baseUrl);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM case_action");
        jdbc.update("DELETE FROM case_participant");
        jdbc.update("DELETE FROM animal_case");
        jdbc.update("DELETE FROM evidence");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM event_draft");
        insertEvent(EVENT, CAMPUS, "VERIFIED", "ACTIVE", "CLAIMED", ANIMAL);
        insertEvent(OTHER_EVENT, OTHER_CAMPUS, "VERIFIED", null, null, null);
        insertEvent(REPORTED_EVENT, CAMPUS, "REPORTED", null, null, null);
    }

    @AfterAll
    static void stopStub() {
        ANIMAL_STUB.stop();
    }

    @Test
    void listIsCampusScopedVerifiedOnlyAndHasNoPrivateProjection() throws Exception {
        mvc.perform(get("/api/v1/public/rescues")
                        .param("campusId", CAMPUS).param("status", "IN_PROGRESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value(EVENT))
                .andExpect(jsonPath("$.items[0].rescueStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[0].animal.displayName").value("小橘"))
                .andExpect(jsonPath("$.items[0].animal.species").value("CAT"))
                .andExpect(jsonPath("$.items[0].description").doesNotExist())
                .andExpect(jsonPath("$.items[0].abnormalitySummary").doesNotExist())
                .andExpect(jsonPath("$.items[0].publicLocationDescription").doesNotExist())
                .andExpect(jsonPath("$.items[0].exactLocationDescription").doesNotExist())
                .andExpect(jsonPath("$.items[0].reporterUserId").doesNotExist())
                .andExpect(jsonPath("$.items[0].ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.items[0].media").doesNotExist());
    }

    @Test
    void detailRequiresTheCurrentCampusAndDoesNotDiscloseUnverifiedEvents() throws Exception {
        mvc.perform(get("/api/v1/public/rescues/{eventId}", EVENT)
                        .param("campusId", CAMPUS).header("X-User-Id", "forged-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rescue.eventId").value(EVENT))
                .andExpect(jsonPath("$.progress[0].label").value("事件已完成核查"))
                .andExpect(jsonPath("$.event").doesNotExist())
                .andExpect(jsonPath("$.evidence").doesNotExist())
                .andExpect(jsonPath("$.caseActions").doesNotExist())
                .andExpect(jsonPath("$.rescue.exactLocationDescription").doesNotExist());
        mvc.perform(get("/api/v1/public/rescues/{eventId}", OTHER_EVENT).param("campusId", CAMPUS))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/rescues/{eventId}", REPORTED_EVENT).param("campusId", CAMPUS))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/rescues/not-a-uuid").param("campusId", CAMPUS))
                .andExpect(status().isBadRequest());
    }

    private void insertEvent(String eventId, String campusId, String status, String caseStatus,
                             String claimStatus, String animalId) {
        Instant now = Instant.parse("2026-10-29T01:00:00Z");
        String draftId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,exact_location_description,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                draftId, UUID.randomUUID().toString(), campusId, "private draft", Timestamp.from(now),
                "校内区域", "精确现场", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,animal_id,description,abnormality_summary,occurred_at,reported_at,public_location_description,exact_location_description,latitude,longitude,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                eventId, draftId, UUID.randomUUID().toString(), campusId, animalId, "敏感事件描述", "敏感异常摘要",
                Timestamp.from(now), Timestamp.from(now), "校内区域", "精确现场", 23.1, 116.6, status,
                Timestamp.from(now), Timestamp.from(now));
        if (caseStatus != null) {
            jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,animal_id,owner_user_id,status,claim_status,claimed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), eventId, campusId, animalId, UUID.randomUUID().toString(),
                    caseStatus, claimStatus, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
    }

    static final class AnimalStub {
        private final HttpServer server;

        AnimalStub() {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
                server.createContext("/api/v1/public/animals/", this::animal);
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

        private void animal(HttpExchange exchange) throws IOException {
            String id = exchange.getRequestURI().getPath().substring("/api/v1/public/animals/".length());
            if (!ANIMAL.equals(id)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = ("{\"id\":\"" + ANIMAL + "\",\"campusId\":\"" + CAMPUS
                    + "\",\"displayName\":\"小橘\",\"species\":\"CAT\",\"description\":\"must not be copied\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
    }
}
