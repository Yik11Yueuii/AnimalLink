package org.animallink.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CaseParticipantTransitionConcurrencyIntegrationTest {
  static final String CAMPUS = "10000000-0000-0000-0000-000000000101";
  static final String OWNER = "00000000-0000-0000-0000-000000000201";
  static final String TARGET = "00000000-0000-0000-0000-000000000202";
  static final String REPORTER = "00000000-0000-0000-0000-000000000204";
  static final Stub STUB = new Stub();
  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("case_participant_transition_race_test").withUsername("test").withPassword("test");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword); r.add("spring.cloud.nacos.discovery.enabled", () -> false); r.add("spring.cloud.nacos.config.enabled", () -> false); r.add("animallink.identity.base-url", STUB::url); r.add("animallink.animal.base-url", STUB::url); r.add("animallink.intelligence.base-url", STUB::url); r.add("animallink.minio.endpoint", () -> "http://localhost:9000"); r.add("animallink.minio.access-key", () -> "minioadmin"); r.add("animallink.minio.secret-key", () -> "minioadmin"); r.add("animallink.minio.bucket", () -> "animallink-incident"); }
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @BeforeEach void clean() { STUB.memberships.clear(); STUB.memberships.put(OWNER, "ACTIVE"); STUB.memberships.put(TARGET, "ACTIVE"); jdbc.update("DELETE FROM case_participant"); jdbc.update("DELETE FROM animal_case"); jdbc.update("DELETE FROM evidence"); jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM event_draft"); }
  @AfterAll static void stop() { STUB.stop(); }

  @Test void concurrentLeaveAndRemoveAllowExactlyOneTerminalTransitionAcrossFiveRounds() throws Exception {
    for (int round = 1; round <= 5; round++) {
      String caseId = seed(round); String participantId = inviteAndAccept(caseId); List<RaceResult> results = race(caseId, participantId);
      assertEquals(1, results.stream().filter(result -> result.status == 200).count()); assertEquals(1, results.stream().filter(result -> result.status == 409).count());
      assertTrue(results.stream().filter(result -> result.status == 409).allMatch(result -> "CASE_PARTICIPANT_STATE_CONFLICT".equals(result.code)));
      assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM case_participant WHERE id=?", Integer.class, participantId));
      Map<String, Object> row = jdbc.queryForMap("SELECT status,joined_at,ended_at FROM case_participant WHERE id=?", participantId);
      assertTrue(Set.of("LEFT", "REMOVED").contains(row.get("status"))); assertNotNull(row.get("joined_at")); assertNotNull(row.get("ended_at"));
    }
  }

  private List<RaceResult> race(String caseId, String participantId) throws Exception { CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2); try { var leave = executor.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return request(post("/api/v1/cases/{caseId}/participants/{participantId}/leave", caseId, participantId).header("X-User-Id", TARGET), "leave"); }); var remove = executor.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return request(post("/api/v1/cases/{caseId}/participants/{participantId}/remove", caseId, participantId).header("X-User-Id", OWNER), "remove"); }); assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown(); return List.of(leave.get(20, TimeUnit.SECONDS), remove.get(20, TimeUnit.SECONDS)); } finally { executor.shutdownNow(); } }
  private RaceResult request(MockHttpServletRequestBuilder request, String operation) throws Exception { var result = mvc.perform(request).andReturn(); return new RaceResult(operation, result.getResponse().getStatus(), mapper.readTree(result.getResponse().getContentAsString()).path("code").asText(null)); }
  private String seed(int round) { Timestamp time = Timestamp.from(Instant.now()); String event = "race-event-" + round, draft = "race-draft-" + round, caseId = "race-case-" + round; jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", draft, REPORTER, CAMPUS, "d", time, "p", time, time); jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)", event, draft, REPORTER, CAMPUS, "e", time, time, "p", "VERIFIED", time, time); jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,owner_user_id,status,claim_status,claimed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)", caseId, event, CAMPUS, OWNER, "ACTIVE", "CLAIMED", time, time, time); return caseId; }
  private String inviteAndAccept(String caseId) throws Exception { var invitation = mvc.perform(post("/api/v1/cases/{id}/participants/invitations", caseId).header("X-User-Id", OWNER).contentType("application/json").content("{\"userId\":\"" + TARGET + "\"}")).andReturn(); assertEquals(200, invitation.getResponse().getStatus(), invitation.getResponse().getContentAsString()); String participantId = mapper.readTree(invitation.getResponse().getContentAsString()).required("id").asText(); mvc.perform(post("/api/v1/cases/{caseId}/participants/{participantId}/accept", caseId, participantId).header("X-User-Id", TARGET)).andExpect(status().isOk()); return participantId; }
  record RaceResult(String operation, int status, String code) {}
  static class Stub { final HttpServer server; final Map<String, String> memberships = new ConcurrentHashMap<>(); Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/", this::handle); server.start(); } catch (IOException e) { throw new RuntimeException(e); } } String url() { return "http://localhost:" + server.getAddress().getPort(); } void stop() { server.stop(0); } void handle(HttpExchange exchange) throws IOException { String path = exchange.getRequestURI().getPath(), user = exchange.getRequestHeaders().getFirst("X-User-Id"); String[] segments = path.split("/"); String subject = segments.length > 4 ? segments[4] : user, state = memberships.get(subject); String json = path.contains("volunteer-membership") ? ("ACTIVE".equals(state) ? "{\"exists\":true,\"active\":true,\"status\":\"ACTIVE\"}" : "{\"exists\":false,\"active\":false}") : "{\"id\":\"" + user + "\",\"systemRole\":\"USER\"}"; byte[] bytes = json.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); } }
}
