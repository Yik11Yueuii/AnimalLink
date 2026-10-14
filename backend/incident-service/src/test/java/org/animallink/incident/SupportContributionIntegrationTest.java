package org.animallink.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SupportContributionIntegrationTest {
  private static final String CAMPUS = "72000000-0000-0000-0000-000000000001";
  private static final String OWNER = "72000000-0000-0000-0000-000000000011";
  private static final String CONTRIBUTOR = "72000000-0000-0000-0000-000000000012";
  private static final String OTHER = "72000000-0000-0000-0000-000000000013";
  private static final String INACTIVE = "72000000-0000-0000-0000-000000000014";
  private static final String ADMIN = "72000000-0000-0000-0000-000000000015";
  private static final String REPORTER = "72000000-0000-0000-0000-000000000016";
  private static final Stub STUB = new Stub();

  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
      .withDatabaseName("support_contribution_test").withUsername("test").withPassword("test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
    registry.add("spring.cloud.nacos.config.enabled", () -> false);
    registry.add("animallink.identity.base-url", STUB::url);
    registry.add("animallink.animal.base-url", STUB::url);
    registry.add("animallink.intelligence.base-url", STUB::url);
    registry.add("animallink.minio.endpoint", () -> "http://localhost:9000");
    registry.add("animallink.minio.access-key", () -> "a");
    registry.add("animallink.minio.secret-key", () -> "b");
    registry.add("animallink.minio.bucket", () -> "b");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @BeforeEach
  void reset() {
    STUB.accounts.clear();
    STUB.accounts.put(CONTRIBUTOR, "ACTIVE");
    STUB.accounts.put(OTHER, "ACTIVE");
    STUB.accounts.put(ADMIN, "ACTIVE");
    STUB.accounts.put(INACTIVE, "SUSPENDED");
    jdbc.update("DELETE FROM support_contribution");
    jdbc.update("DELETE FROM support_audit_log");
    jdbc.update("DELETE FROM support_campaign");
    jdbc.update("DELETE FROM case_participant");
    jdbc.update("DELETE FROM animal_case");
    jdbc.update("DELETE FROM evidence_media");
    jdbc.update("DELETE FROM evidence");
    jdbc.update("DELETE FROM event_media");
    jdbc.update("DELETE FROM event");
    jdbc.update("DELETE FROM event_draft");
  }

  @AfterAll
  static void stop() { STUB.server.stop(0); }

  @Test
  void flywaySchemaAndLedgerConstraints() throws Exception {
    assertEquals(15, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL", Integer.class));
    assertEquals("phase5d2 support contribution", jdbc.queryForObject("SELECT description FROM flyway_schema_history WHERE version='13'", String.class));
    assertEquals(1, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='support_contribution'"));
    assertEquals(1, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='support_expense'"));
    assertEquals(1, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='support_settlement'"));
    Fixture fixture = activeFixture("schema", "250.00");
    String contributionId = contribute(fixture.caseId(), CONTRIBUTOR, "schema-key", "10.25", 201);
    assertEquals("10.25", jdbc.queryForObject("SELECT CAST(amount AS CHAR) FROM support_contribution WHERE id=?", String.class, contributionId));
    Timestamp now = Timestamp.from(Instant.now());
    assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO support_contribution(id,campaign_id,contributor_user_id,amount,status,idempotency_key,request_hash,recorded_at) VALUES (?,?,?,0,'RECORDED','bad-amount',?,?)",
        UUID.randomUUID().toString(), fixture.campaignId(), OTHER, "0".repeat(64), now));
    assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE support_contribution SET status='VOIDED' WHERE id=?", contributionId));
  }

  @Test
  void ordinaryActiveUserCanContributeWithoutCaseOrVolunteerRelationship() throws Exception {
    Fixture fixture = activeFixture("authorization", "250.00");
    mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", fixture.caseId())
            .header("Idempotency-Key", "ordinary-user").contentType("application/json").content("{\"amount\":12.34}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", fixture.caseId()).header("X-User-Id", INACTIVE)
            .header("Idempotency-Key", "inactive-user").contentType("application/json").content("{\"amount\":12.34}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", fixture.caseId()).header("X-User-Id", CONTRIBUTOR)
            .contentType("application/json").content("{\"amount\":12.34}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", fixture.caseId()).header("X-User-Id", CONTRIBUTOR)
            .header("Idempotency-Key", " ").contentType("application/json").content("{\"amount\":12.34}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", fixture.caseId()).header("X-User-Id", CONTRIBUTOR)
            .header("Idempotency-Key", "ordinary-user").contentType("application/json").content("{\"amount\":12.34}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(12.34))
        .andExpect(jsonPath("$.status").value("RECORDED"));
    assertEquals(0, count("SELECT COUNT(*) FROM case_participant WHERE case_id=? AND user_id=?", fixture.caseId(), CONTRIBUTOR));
  }

  @Test
  void campaignAndCurrentCaseMustBothAcceptSupport() throws Exception {
    Fixture approved = fixture("approved", "ACTIVE", "CLAIMED", "APPROVED", "250.00");
    rejectedContribution(approved.caseId(), "not-published", "CAMPAIGN_NOT_ACCEPTING_SUPPORT");
    Fixture terminal = activeFixture("terminal", "250.00");
    jdbc.update("UPDATE animal_case SET status='RESOLVED' WHERE id=?", terminal.caseId());
    rejectedContribution(terminal.caseId(), "terminal-case", "CAMPAIGN_NOT_ACCEPTING_SUPPORT");
    assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM support_campaign WHERE id=?", String.class, terminal.campaignId()));
  }

  @Test
  void exactMoneyAndIdempotencySemantics() throws Exception {
    Fixture fixture = activeFixture("money", "250.00");
    for (String amount : new String[]{"0", "-1", "1.001", "10000000000000000.00"}) {
      mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", fixture.caseId()).header("X-User-Id", CONTRIBUTOR)
              .header("Idempotency-Key", "invalid-" + amount).contentType("application/json").content("{\"amount\":" + amount + "}"))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }
    String first = contribute(fixture.caseId(), CONTRIBUTOR, "same-key", "100", 201);
    Timestamp recordedAt = jdbc.queryForObject("SELECT recorded_at FROM support_contribution WHERE id=?", Timestamp.class, first);
    String replay = contribute(fixture.caseId(), CONTRIBUTOR, "same-key", "100.00", 200);
    assertEquals(first, replay);
    assertEquals(recordedAt, jdbc.queryForObject("SELECT recorded_at FROM support_contribution WHERE id=?", Timestamp.class, first));
    assertEquals(1, count("SELECT COUNT(*) FROM support_contribution WHERE campaign_id=?", fixture.campaignId()));
    mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", fixture.caseId()).header("X-User-Id", CONTRIBUTOR)
            .header("Idempotency-Key", "same-key").contentType("application/json").content("{\"amount\":101.00}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    contribute(fixture.caseId(), CONTRIBUTOR, "different-key", "100.00", 201);
    contribute(fixture.caseId(), OTHER, "same-key", "100.00", 201);
    assertEquals(3, count("SELECT COUNT(*) FROM support_contribution WHERE campaign_id=?", fixture.campaignId()));
    assertEquals(0, count("SELECT COUNT(*) FROM support_contribution WHERE request_hash IS NULL OR CHAR_LENGTH(request_hash)<>64"));
  }

  @Test
  void concurrentReplayCreatesOneLogicalRecordAndDifferentKeysRemainValid() throws Exception {
    Fixture fixture = activeFixture("concurrency", "250.00");
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch go = new CountDownLatch(1);
      Callable<MvcResult> replay = () -> {
        go.await();
        return mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", fixture.caseId()).header("X-User-Id", CONTRIBUTOR)
            .header("Idempotency-Key", "concurrent-key").contentType("application/json").content("{\"amount\":50.50}")).andReturn();
      };
      Future<MvcResult> first = pool.submit(replay);
      Future<MvcResult> second = pool.submit(replay);
      go.countDown();
      MvcResult a = first.get();
      MvcResult b = second.get();
      assertTrue((a.getResponse().getStatus() == 201 && b.getResponse().getStatus() == 200)
          || (a.getResponse().getStatus() == 200 && b.getResponse().getStatus() == 201));
      assertEquals(json.readTree(a.getResponse().getContentAsString()).get("contributionId").asText(),
          json.readTree(b.getResponse().getContentAsString()).get("contributionId").asText());
      assertEquals(1, count("SELECT COUNT(*) FROM support_contribution WHERE campaign_id=?", fixture.campaignId()));

      CountDownLatch differentGo = new CountDownLatch(1);
      Callable<Integer> left = () -> concurrentCreate(fixture.caseId(), "left-key", differentGo);
      Callable<Integer> right = () -> concurrentCreate(fixture.caseId(), "right-key", differentGo);
      Future<Integer> leftResult = pool.submit(left);
      Future<Integer> rightResult = pool.submit(right);
      differentGo.countDown();
      assertEquals(201, leftResult.get());
      assertEquals(201, rightResult.get());
      assertEquals(3, count("SELECT COUNT(*) FROM support_contribution WHERE campaign_id=?", fixture.campaignId()));
    }
  }

  @Test
  void publicAggregatesOnlyRecordedRowsAndOwnHistoryIsIsolated() throws Exception {
    Fixture fixture = activeFixture("aggregate", "250.00");
    contribute(fixture.caseId(), CONTRIBUTOR, "aggregate-a", "100.10", 201);
    contribute(fixture.caseId(), OTHER, "aggregate-b", "200.20", 201);
    String body = mvc.perform(get("/api/v1/cases/{caseId}/support", fixture.caseId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.recordedContributionAmount").value(300.30))
        .andExpect(jsonPath("$.contributionCount").value(2)).andExpect(jsonPath("$.progressPercent").value(100.00))
        .andReturn().getResponse().getContentAsString();
    for (String hidden : new String[]{"contributorUserId", "contributions", "idempotencyKey", "requestHash", "voidReason", "voidedByUserId"}) {
      assertFalse(body.contains(hidden));
    }
    String own = mvc.perform(get("/api/v1/cases/{caseId}/support/contributions/me", fixture.caseId()).header("X-User-Id", CONTRIBUTOR))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].amount").value(100.10)).andReturn().getResponse().getContentAsString();
    assertEquals(1, json.readTree(own).size());
    assertFalse(own.contains(OTHER));
    assertFalse(own.contains("idempotencyKey"));
    assertFalse(own.contains("requestHash"));
  }

  @Test
  void governanceCanAuditAndVoidWithoutDeletingOrChangingAmount() throws Exception {
    Fixture fixture = activeFixture("void", "250.00");
    String contributionId = contribute(fixture.caseId(), CONTRIBUTOR, "void-a", "100.10", 201);
    contribute(fixture.caseId(), OTHER, "void-b", "200.20", 201);
    mvc.perform(get("/api/v1/admin/support-campaigns/{id}/contributions", fixture.campaignId()))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/support-campaigns/{id}/contributions", fixture.campaignId()).header("X-User-Id", CONTRIBUTOR))
        .andExpect(status().isForbidden());
    String ledger = mvc.perform(get("/api/v1/admin/support-campaigns/{id}/contributions", fixture.campaignId()).header("X-User-Id", ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].contributorUserId").exists()).andReturn().getResponse().getContentAsString();
    assertFalse(ledger.contains("requestHash"));
    assertFalse(ledger.contains("idempotencyKey"));
    mvc.perform(post("/api/v1/admin/support-contributions/{id}/void", contributionId).header("X-User-Id", ADMIN)
            .contentType("application/json").content("{\"reason\":\" \"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(post("/api/v1/admin/support-contributions/{id}/void", contributionId).header("X-User-Id", CONTRIBUTOR)
            .contentType("application/json").content("{\"reason\":\"correction\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/admin/support-contributions/{id}/void", contributionId).header("X-User-Id", ADMIN)
            .contentType("application/json").content("{\"reason\":\"correction\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"))
        .andExpect(jsonPath("$.amount").value(100.10)).andExpect(jsonPath("$.voidReason").value("correction"));
    mvc.perform(post("/api/v1/admin/support-contributions/{id}/void", contributionId).header("X-User-Id", ADMIN)
            .contentType("application/json").content("{\"reason\":\"again\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTRIBUTION_STATE_CONFLICT"));
    assertEquals(1, count("SELECT COUNT(*) FROM support_contribution WHERE id=? AND status='VOIDED' AND amount=100.10", contributionId));
    assertEquals(1, count("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND resource_type='CONTRIBUTION' AND resource_id=? AND action='VOID_CONTRIBUTION' AND from_state='RECORDED' AND to_state='VOIDED' AND reason='correction'",
        fixture.campaignId(), contributionId));
    mvc.perform(get("/api/v1/cases/{caseId}/support/contributions/me", fixture.caseId()).header("X-User-Id", CONTRIBUTOR))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("VOIDED"))
        .andExpect(jsonPath("$[0].voidReason").value("correction"));
    mvc.perform(get("/api/v1/admin/support-campaigns/{id}/contributions", fixture.campaignId()).header("X-User-Id", ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.contributionId=='" + contributionId + "')].status").value("VOIDED"));
    mvc.perform(get("/api/v1/cases/{caseId}/support", fixture.caseId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.recordedContributionAmount").value(200.20))
        .andExpect(jsonPath("$.contributionCount").value(1));
    assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM support_campaign WHERE id=?", String.class, fixture.campaignId()));

    jdbc.update("UPDATE support_campaign SET status='SETTLED' WHERE id=?", fixture.campaignId());
    String remaining = jdbc.queryForObject("SELECT id FROM support_contribution WHERE campaign_id=? AND status='RECORDED'", String.class, fixture.campaignId());
    mvc.perform(post("/api/v1/admin/support-contributions/{id}/void", remaining).header("X-User-Id", ADMIN)
            .contentType("application/json").content("{\"reason\":\"too late\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTRIBUTION_STATE_CONFLICT"));
  }

  private int concurrentCreate(String caseId, String key, CountDownLatch go) throws Exception {
    go.await();
    return mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", caseId).header("X-User-Id", CONTRIBUTOR)
        .header("Idempotency-Key", key).contentType("application/json").content("{\"amount\":1.00}")).andReturn().getResponse().getStatus();
  }

  private void rejectedContribution(String caseId, String key, String code) throws Exception {
    mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", caseId).header("X-User-Id", CONTRIBUTOR)
            .header("Idempotency-Key", key).contentType("application/json").content("{\"amount\":10.00}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
  }

  private String contribute(String caseId, String user, String key, String amount, int status) throws Exception {
    String body = mvc.perform(post("/api/v1/cases/{caseId}/support/contributions", caseId).header("X-User-Id", user)
            .header("Idempotency-Key", key).contentType("application/json").content("{\"amount\":" + amount + "}"))
        .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    return json.readTree(body).required("contributionId").asText();
  }

  private Fixture activeFixture(String suffix, String target) {
    return fixture(suffix, "ACTIVE", "CLAIMED", "ACTIVE", target);
  }

  private Fixture fixture(String suffix, String caseStatus, String claimStatus, String campaignStatus, String target) {
    String event = "event-" + suffix;
    String draft = "draft-" + suffix;
    String caseId = "case-" + suffix;
    String campaignId = "campaign-" + suffix;
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
        draft, REPORTER, CAMPUS, "draft", now, "public", now, now);
    jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        event, draft, REPORTER, CAMPUS, "event", now, now, "public", "VERIFIED", now, now);
    jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,owner_user_id,status,claim_status,claimed_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        caseId, event, CAMPUS, "CLAIMED".equals(claimStatus) ? OWNER : null, caseStatus, claimStatus,
        "CLAIMED".equals(claimStatus) ? now : null, now, now);
    jdbc.update("INSERT INTO support_campaign(id,case_id,status,target_amount,currency,purpose,application_note,requested_by_user_id,published_at,created_at,updated_at) VALUES (?,?,?,?, 'CNY','recorded support','private',?,?,?,?)",
        campaignId, caseId, campaignStatus, target, OWNER, "ACTIVE".equals(campaignStatus) ? now : null, now, now);
    return new Fixture(caseId, campaignId);
  }

  private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
  private record Fixture(String caseId, String campaignId) {}

  private static class Stub {
    final HttpServer server;
    final Map<String, String> accounts = new ConcurrentHashMap<>();

    Stub() {
      try {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    String url() { return "http://localhost:" + server.getAddress().getPort(); }

    void handle(HttpExchange exchange) throws IOException {
      String user = exchange.getRequestHeaders().getFirst("X-User-Id");
      String accountStatus = accounts.getOrDefault(user, "SUSPENDED");
      String role = ADMIN.equals(user) ? "GOVERNANCE_ADMIN" : "USER";
      String body = "{\"id\":\"" + user + "\",\"accountStatus\":\"" + accountStatus + "\",\"systemRole\":\"" + role + "\"}";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}
