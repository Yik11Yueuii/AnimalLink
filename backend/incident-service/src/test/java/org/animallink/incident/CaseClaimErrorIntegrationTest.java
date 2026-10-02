package org.animallink.incident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CaseClaimErrorIntegrationTest {
  static final String CAMPUS = "10000000-0000-0000-0000-000000000101";
  static final String REPORTER = "00000000-0000-0000-0000-000000000101";
  static final String VOLUNTEER = "00000000-0000-0000-0000-000000000201";
  static final Stub STUB = new Stub();

  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("case_claim_error_test").withUsername("test").withPassword("test");

  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword);
    r.add("spring.cloud.nacos.discovery.enabled", () -> false); r.add("spring.cloud.nacos.config.enabled", () -> false);
    r.add("animallink.identity.base-url", STUB::url); r.add("animallink.animal.base-url", STUB::url); r.add("animallink.intelligence.base-url", STUB::url);
    r.add("animallink.minio.endpoint", () -> "http://localhost:9000"); r.add("animallink.minio.access-key", () -> "minioadmin"); r.add("animallink.minio.secret-key", () -> "minioadmin"); r.add("animallink.minio.bucket", () -> "animallink-incident");
  }

  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;

  @BeforeEach void clean() { STUB.memberships.clear(); STUB.unavailable = false; jdbc.update("DELETE FROM animal_case"); jdbc.update("DELETE FROM evidence_media"); jdbc.update("DELETE FROM evidence"); jdbc.update("DELETE FROM event_media"); jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM event_draft"); }
  @AfterAll static void stop() { STUB.stop(); }

  @Test void requiresActiveVolunteerMembershipAndPreservesActiveClaim() throws Exception {
    List<String> inactive = List.of("PENDING_REVIEW", "PAUSED", "REJECTED", "EXITED", "REVOKED");
    seed("missing-membership", "VERIFIED"); claim("missing-membership").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_VOLUNTEER_REQUIRED"));
    for (String membershipStatus : inactive) { STUB.memberships.put(VOLUNTEER, membershipStatus); String eventId = "membership-" + membershipStatus; seed(eventId, "VERIFIED"); claim(eventId).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_VOLUNTEER_REQUIRED")); }
    STUB.memberships.put(VOLUNTEER, "ACTIVE"); seed("active-membership", "VERIFIED"); claim("active-membership").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.claimStatus").value("CLAIMED"));
  }

  @Test void returnsIdentityUnavailableWhenVolunteerFactCannotBeRead() throws Exception {
    seed("identity-unavailable", "VERIFIED"); STUB.unavailable = true;
    claim("identity-unavailable").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDENTITY_SERVICE_UNAVAILABLE"));
  }

  @Test void requiresVerifiedEventAndKeepsMissingEventCode() throws Exception {
    STUB.memberships.put(VOLUNTEER, "ACTIVE"); seed("verified-event", "VERIFIED"); claim("verified-event").andExpect(status().isOk());
    for (String eventStatus : List.of("REPORTED", "ARCHIVED", "REJECTED", "DUPLICATE")) { String eventId = "event-" + eventStatus; seed(eventId, eventStatus); claim(eventId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_ELIGIBLE_FOR_CASE")); }
    claim("missing-event").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
  }

  @Test void returnsCaseNotFoundForBothCaseReadPaths() throws Exception {
    mvc.perform(get("/api/v1/cases/{id}", "missing-case")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
    seed("event-without-case", "VERIFIED"); mvc.perform(get("/api/v1/events/{eventId}/case", "event-without-case")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
  }

  private org.springframework.test.web.servlet.ResultActions claim(String eventId) throws Exception { return mvc.perform(post("/api/v1/events/{eventId}/case/claim", eventId).header("X-User-Id", VOLUNTEER)); }
  private void seed(String eventId, String eventStatus) { Timestamp time = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")); jdbc.update("INSERT INTO event_draft (id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)", "draft-" + eventId, REPORTER, CAMPUS, "draft", time, "public", time, time); jdbc.update("INSERT INTO event (id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", eventId, "draft-" + eventId, REPORTER, CAMPUS, "event", time, time, "public", eventStatus, time, time); }

  static class Stub {
    final HttpServer server; final Map<String, String> memberships = new ConcurrentHashMap<>(); volatile boolean unavailable;
    Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/", this::handle); server.start(); } catch (IOException e) { throw new RuntimeException(e); } }
    String url() { return "http://localhost:" + server.getAddress().getPort(); } void stop() { server.stop(0); }
    void handle(HttpExchange exchange) throws IOException { String path = exchange.getRequestURI().getPath(); if (unavailable && path.contains("volunteer-membership")) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; } String[] segments = path.split("/"); String user = segments.length > 4 ? segments[4] : ""; String membershipStatus = memberships.get(user); String json = path.contains("volunteer-membership") ? (membershipStatus == null ? "{\"exists\":false,\"active\":false}" : "{\"exists\":true,\"status\":\"" + membershipStatus + "\",\"active\":" + "ACTIVE".equals(membershipStatus) + "}") : "{}"; byte[] bytes = json.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }
  }
}
