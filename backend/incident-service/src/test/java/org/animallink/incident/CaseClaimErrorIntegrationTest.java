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
    r.add("animallink.authentication.incident-miniapp-assertion.keys", () -> "test=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
    r.add("animallink.identity.base-url", STUB::url); r.add("animallink.animal.base-url", STUB::url); r.add("animallink.intelligence.base-url", STUB::url);
    r.add("animallink.minio.endpoint", () -> "http://localhost:9000"); r.add("animallink.minio.access-key", () -> "minioadmin"); r.add("animallink.minio.secret-key", () -> "minioadmin"); r.add("animallink.minio.bucket", () -> "animallink-incident");
  }

  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;

  @BeforeEach void clean() { STUB.memberships.clear(); STUB.accounts.clear(); STUB.accounts.put(VOLUNTEER, "ACTIVE"); STUB.unavailable = false; jdbc.update("DELETE FROM animal_case"); jdbc.update("DELETE FROM evidence_media"); jdbc.update("DELETE FROM evidence"); jdbc.update("DELETE FROM event_media"); jdbc.update("DELETE FROM event"); jdbc.update("DELETE FROM event_draft"); }
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

  @Test void suspendedAndDisabledAccountsCannotClaimDespiteActiveVolunteerMembership() throws Exception {
    STUB.memberships.put(VOLUNTEER, "ACTIVE");
    for (String status : List.of("SUSPENDED", "DISABLED")) {
      String eventId = "account-" + status;
      seed(eventId, "VERIFIED");
      STUB.accounts.put(VOLUNTEER, status);
      claim(eventId).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
      Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM animal_case WHERE event_id=?", Integer.class, eventId));
    }
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

  @Test void signedMiniappClaimIsCampusScopedIdempotentAndPrivate() throws Exception {
    String event = "21000000-0000-4000-8000-000000000001", path = "/api/v1/internal/miniapp/rescues/" + event + "/claim";
    String other = "00000000-0000-0000-0000-000000000202";
    STUB.memberships.put(VOLUNTEER, "ACTIVE"); STUB.memberships.put(other, "ACTIVE"); seed(event, "VERIFIED");
    String good = "{\"campusId\":\"" + CAMPUS + "\"}", wrong = "{\"campusId\":\"other-campus\"}";
    mvc.perform(post(path).contentType("application/json").content(good).header("X-User-Id", VOLUNTEER)).andExpect(status().isUnauthorized());
    STUB.memberships.remove(VOLUNTEER);
    mvc.perform(post(path).contentType("application/json").content(good).header("Authorization", miniappAssertion(VOLUNTEER, path))).andExpect(status().isForbidden());
    STUB.memberships.put(VOLUNTEER, "ACTIVE");
    mvc.perform(post(path).contentType("application/json").content(wrong).header("Authorization", miniappAssertion(VOLUNTEER, path))).andExpect(status().isForbidden());
    Assertions.assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM animal_case WHERE event_id=?", Integer.class, event));
    mvc.perform(post(path).contentType("application/json").content(good).header("Authorization", miniappAssertion(VOLUNTEER, path))).andExpect(status().isOk()).andExpect(jsonPath("$.eventId").value(event)).andExpect(jsonPath("$.ownerUserId").doesNotExist()).andExpect(jsonPath("$.resultSummary").doesNotExist());
    mvc.perform(post(path).contentType("application/json").content(good).header("Authorization", miniappAssertion(VOLUNTEER, path))).andExpect(status().isOk());
    mvc.perform(post(path).contentType("application/json").content(good).header("Authorization", miniappAssertion(other, path))).andExpect(status().isConflict());
    Assertions.assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM animal_case WHERE event_id=?", Integer.class, event));
  }
  private static String miniappAssertion(String actor, String path) throws Exception {
    Instant now = Instant.now(); var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("api-gateway").subject("api-gateway").audience("incident-service").claim("target", "incident-service").claim("actor_id", actor).claim("system_role", "USER").claim("scope", List.of("incident.case.claim")).claim("request_method", "POST").claim("request_path", path).issueTime(java.util.Date.from(now)).expirationTime(java.util.Date.from(now.plusSeconds(15))).jwtID(java.util.UUID.randomUUID().toString()).build();
    var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.HS256).type(com.nimbusds.jose.JOSEObjectType.JWT).keyID("test").build(), claims);
    jwt.sign(new com.nimbusds.jose.crypto.MACSigner("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8))); return "Bearer " + jwt.serialize();
  }
  private org.springframework.test.web.servlet.ResultActions claim(String eventId) throws Exception { return mvc.perform(post("/api/v1/events/{eventId}/case/claim", eventId).header("X-User-Id", VOLUNTEER)); }
  private void seed(String eventId, String eventStatus) { Timestamp time = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")); jdbc.update("INSERT INTO event_draft (id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)", (eventId.length()>30 ? "draft-mini" : "draft-" + eventId), REPORTER, CAMPUS, "draft", time, "public", time, time); jdbc.update("INSERT INTO event (id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", eventId, (eventId.length()>30 ? "draft-mini" : "draft-" + eventId), REPORTER, CAMPUS, "event", time, time, "public", eventStatus, time, time); }

  static class Stub {
    final HttpServer server; final Map<String, String> memberships = new ConcurrentHashMap<>(); final Map<String, String> accounts = new ConcurrentHashMap<>(); volatile boolean unavailable;
    Stub() { try { server = HttpServer.create(new InetSocketAddress(0), 0); server.createContext("/", this::handle); server.start(); } catch (IOException e) { throw new RuntimeException(e); } }
    String url() { return "http://localhost:" + server.getAddress().getPort(); } void stop() { server.stop(0); }
    void handle(HttpExchange exchange) throws IOException { String path = exchange.getRequestURI().getPath(); if (unavailable && path.contains("volunteer-membership")) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; } String header = exchange.getRequestHeaders().getFirst("X-User-Id"); String[] segments = path.split("/"); String user = path.contains("volunteer-membership") && segments.length > 4 ? segments[4] : header; String membershipStatus = memberships.get(user); String json = path.contains("volunteer-membership") ? (membershipStatus == null ? "{\"exists\":false,\"active\":false}" : "{\"exists\":true,\"status\":\"" + membershipStatus + "\",\"active\":" + "ACTIVE".equals(membershipStatus) + "}") : "{\"id\":\"" + header + "\",\"accountStatus\":\"" + accounts.getOrDefault(header, "ACTIVE") + "\",\"systemRole\":\"USER\"}"; byte[] bytes = json.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }
  }
}
