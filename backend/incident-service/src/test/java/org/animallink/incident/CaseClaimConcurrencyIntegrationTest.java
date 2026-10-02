package org.animallink.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CaseClaimConcurrencyIntegrationTest {
  static final String CAMPUS = "10000000-0000-0000-0000-000000000101";
  static final String REPORTER = "00000000-0000-0000-0000-000000000101";
  static final String VOLUNTEER_A = "00000000-0000-0000-0000-000000000201";
  static final String VOLUNTEER_B = "00000000-0000-0000-0000-000000000202";
  static final Stub STUB = new Stub();

  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41").withDatabaseName("case_claim_concurrency_test").withUsername("test").withPassword("test");

  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword);
    r.add("spring.cloud.nacos.discovery.enabled", () -> false); r.add("spring.cloud.nacos.config.enabled", () -> false);
    r.add("animallink.identity.base-url", STUB::url); r.add("animallink.animal.base-url", STUB::url); r.add("animallink.intelligence.base-url", STUB::url);
    r.add("animallink.minio.endpoint", () -> "http://localhost:9000"); r.add("animallink.minio.access-key", () -> "minioadmin"); r.add("animallink.minio.secret-key", () -> "minioadmin"); r.add("animallink.minio.bucket", () -> "animallink-incident");
  }

  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;

  @BeforeEach void clean() { jdbc.update("DELETE FROM animal_case"); jdbc.update("DELETE FROM evidence_media"); jdbc.update("DELETE FROM evidence"); jdbc.update("DELETE FROM event_media"); jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM event_draft"); }
  @AfterAll static void stop() { STUB.stop(); }

  @Test void concurrentFirstClaimsCreateOneCaseAndOneWinnerAcrossFiveRounds() throws Exception {
    for (int round = 1; round <= 5; round++) {
      String eventId = "concurrent-event-" + round; seedVerified(eventId); Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM animal_case WHERE event_id=?", Integer.class, eventId));
      List<ClaimResult> results = concurrentlyClaim(eventId); System.out.printf("case-claim-attempt=%d A=%d/%s B=%d/%s%n", round, resultFor(results, VOLUNTEER_A).status, resultFor(results, VOLUNTEER_A).code, resultFor(results, VOLUNTEER_B).status, resultFor(results, VOLUNTEER_B).code);
      ClaimResult winner = results.stream().filter(result -> result.status == 200).findFirst().orElseThrow();
      ClaimResult loser = results.stream().filter(result -> result.status == 409).findFirst().orElseThrow();
      Assertions.assertEquals(1, results.stream().filter(result -> result.status == 200).count()); Assertions.assertEquals(1, results.stream().filter(result -> result.status == 409).count());
      Assertions.assertEquals("CASE_ALREADY_CLAIMED", loser.code); Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM animal_case WHERE event_id=?", Integer.class, eventId));
      Map<String, Object> row = jdbc.queryForMap("SELECT id,owner_user_id,status,claim_status,claimed_at FROM animal_case WHERE event_id=?", eventId);
      Assertions.assertEquals(winner.userId, row.get("owner_user_id")); Assertions.assertEquals("ACTIVE", row.get("status")); Assertions.assertEquals("CLAIMED", row.get("claim_status")); Assertions.assertNotNull(row.get("claimed_at")); Assertions.assertEquals(row.get("id"), winner.caseId);
      System.out.printf("case-claim-round=%d A=%d B=%d winner=%s loser=%s loserCode=%s caseId=%s owner=%s%n", round, resultFor(results, VOLUNTEER_A).status, resultFor(results, VOLUNTEER_B).status, winner.userId, loser.userId, loser.code, row.get("id"), row.get("owner_user_id"));
      jdbc.update("DELETE FROM animal_case WHERE event_id=?", eventId); jdbc.update("DELETE FROM event WHERE id=?", eventId); jdbc.update("DELETE FROM event_draft WHERE id=?", "draft-" + eventId);
    }
  }

  @Test void sameVolunteerCanClaimTheSameEventIdempotently() throws Exception {
    String eventId = "same-user-event"; seedVerified(eventId);
    ClaimResult first = claim(eventId, VOLUNTEER_A); ClaimResult second = claim(eventId, VOLUNTEER_A);
    Assertions.assertEquals(200, first.status); Assertions.assertEquals(200, second.status); Assertions.assertEquals(first.caseId, second.caseId); Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM animal_case WHERE event_id=?", Integer.class, eventId)); Assertions.assertEquals(VOLUNTEER_A, jdbc.queryForObject("SELECT owner_user_id FROM animal_case WHERE event_id=?", String.class, eventId));
  }

  private List<ClaimResult> concurrentlyClaim(String eventId) throws Exception {
    CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1); ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ClaimResult> a = executor.submit(() -> { ready.countDown(); Assertions.assertTrue(start.await(10, TimeUnit.SECONDS)); return claim(eventId, VOLUNTEER_A); });
      Future<ClaimResult> b = executor.submit(() -> { ready.countDown(); Assertions.assertTrue(start.await(10, TimeUnit.SECONDS)); return claim(eventId, VOLUNTEER_B); });
      Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown(); return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    } finally { executor.shutdownNow(); }
  }

  private ClaimResult claim(String eventId, String userId) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/events/{eventId}/case/claim", eventId).header("X-User-Id", userId)).andReturn(); JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
    return new ClaimResult(userId, result.getResponse().getStatus(), body.path("id").isMissingNode() ? null : body.path("id").asText(), body.path("code").isMissingNode() ? null : body.path("code").asText());
  }

  private ClaimResult resultFor(List<ClaimResult> results, String userId) { return results.stream().filter(result -> result.userId.equals(userId)).findFirst().orElseThrow(); }
  private void seedVerified(String eventId) { Timestamp time = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")); jdbc.update("INSERT INTO event_draft (id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)", "draft-" + eventId, REPORTER, CAMPUS, "draft", time, "public", time, time); jdbc.update("INSERT INTO event (id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", eventId, "draft-" + eventId, REPORTER, CAMPUS, "event", time, time, "public", "VERIFIED", time, time); }
  record ClaimResult(String userId, int status, String caseId, String code) {}

  static class Stub {
    final HttpServer server;
    Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/", this::handle); server.start(); } catch (IOException e) { throw new RuntimeException(e); } }
    String url() { return "http://localhost:" + server.getAddress().getPort(); } void stop() { server.stop(0); }
    void handle(HttpExchange exchange) throws IOException { String path = exchange.getRequestURI().getPath(); String user = exchange.getRequestHeaders().getFirst("X-User-Id"); String json = path.contains("volunteer-membership") ? "{\"exists\":true,\"active\":true,\"status\":\"ACTIVE\"}" : "{\"id\":\"" + user + "\",\"systemRole\":\"USER\"}"; byte[] bytes = json.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }
  }
}
