package org.animallink.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.animallink.incident.api.SupportExpenseService;
import org.animallink.incident.api.SupportSettlementService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SupportSettlementIntegrationTest {
  private static final String CAMPUS = "75000000-0000-0000-0000-000000000001";
  private static final String OWNER = "75000000-0000-0000-0000-000000000011";
  private static final String CONTRIBUTOR = "75000000-0000-0000-0000-000000000012";
  private static final String ADMIN = "75000000-0000-0000-0000-000000000013";
  private static final String INACTIVE_ADMIN = "75000000-0000-0000-0000-000000000014";
  private static final String REPORTER = "75000000-0000-0000-0000-000000000015";
  private static final Stub STUB = new Stub();

  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
      .withDatabaseName("support_settlement_test").withUsername("test").withPassword("test");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
    registry.add("spring.cloud.nacos.config.enabled", () -> false);
    registry.add("animallink.messaging.enabled", () -> false);
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
  @Autowired SupportSettlementService settlements;
  @Autowired SupportExpenseService expenses;

  @BeforeEach
  void reset() {
    STUB.accounts.clear();
    STUB.accounts.put(OWNER, "ACTIVE");
    STUB.accounts.put(CONTRIBUTOR, "ACTIVE");
    STUB.accounts.put(ADMIN, "ACTIVE");
    STUB.accounts.put(INACTIVE_ADMIN, "SUSPENDED");
    jdbc.update("DELETE FROM support_settlement");
    jdbc.update("DELETE FROM support_expense_media");
    jdbc.update("UPDATE support_expense SET replaces_expense_id=NULL WHERE replaces_expense_id IS NOT NULL");
    jdbc.update("DELETE FROM support_expense");
    jdbc.update("DELETE FROM support_contribution");
    jdbc.update("DELETE FROM support_audit_log");
    jdbc.update("DELETE FROM support_campaign");
    jdbc.update("DELETE FROM outbox_event");
    jdbc.update("DELETE FROM case_action_media");
    jdbc.update("DELETE FROM case_action");
    jdbc.update("DELETE FROM case_participant");
    jdbc.update("DELETE FROM animal_case");
    jdbc.update("DELETE FROM evidence_media");
    jdbc.update("DELETE FROM evidence");
    jdbc.update("DELETE FROM event_media");
    jdbc.update("DELETE FROM event");
    jdbc.update("DELETE FROM event_draft_media");
    jdbc.update("DELETE FROM event_draft");
  }

  @AfterAll
  static void stop() { STUB.server.stop(0); }

  @Test
  void flywayV15CreatesOnlyImmutableSettlementContract() {
    assertEquals(15, integer("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
    assertEquals("phase5d4 support settlement",
        jdbc.queryForObject("SELECT description FROM flyway_schema_history WHERE version='15'", String.class));
    assertEquals(1, integer("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='support_settlement'"));
    assertEquals(11, integer("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='support_settlement'"));
    assertEquals(0, integer("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='support_settlement' AND column_name IN ('updated_at','payment_reference','provider','bank_account')"));
    assertEquals(1, integer("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name='support_settlement' AND referenced_table_name='support_campaign'"));
    assertEquals(0, integer("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name='support_settlement' AND referenced_table_name<>'support_campaign'"));
    assertEquals(1, integer("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='support_settlement' AND index_name='uk_support_settlement_campaign' AND non_unique=0"));
    assertEquals(3, integer("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='support_settlement' AND constraint_type='CHECK'"));
    TransactionTemplate template = (TransactionTemplate) ReflectionTestUtils.getField(settlements, "transactions");
    assertNotNull(template);
    assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, template.getIsolationLevel());
  }

  @Test
  void settlesExactPositiveSnapshotAndExposesSafeProjections() throws Exception {
    Fixture fixture = fixture("positive", "COMPLETED");
    Timestamp completedAt = fixture.completedAt();
    contribution(fixture.campaignId(), "300.30", "RECORDED");
    contribution(fixture.campaignId(), "99.99", "VOIDED");
    String published = expense(fixture.campaignId(), "100.10", "PUBLISHED");
    expense(fixture.campaignId(), "88.88", "REJECTED");
    expense(fixture.campaignId(), "77.77", "VOIDED");

    mvc.perform(get("/api/v1/admin/support-campaigns/{id}", fixture.campaignId()).header("X-User-Id", ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.settlement").isEmpty());
    Call created = settle(fixture.campaignId(), ADMIN, "positive-key", " final summary ", " retain for care ");
    assertEquals(201, created.status());
    JsonNode body = json.readTree(created.body());
    assertMoney("300.30", body.required("totalContributed"));
    assertMoney("100.10", body.required("totalExpenses"));
    assertMoney("200.20", body.required("netBalance"));
    assertEquals("final summary", body.required("summary").asText());
    assertEquals("retain for care", body.required("dispositionNote").asText());
    assertEquals(ADMIN, body.required("settledByUserId").asText());
    assertFalse(body.has("idempotencyKey"));
    assertFalse(body.has("requestHash"));

    assertEquals("SETTLED", text("SELECT status FROM support_campaign WHERE id=?", fixture.campaignId()));
    assertEquals(6, integer("SELECT version FROM support_campaign WHERE id=?", fixture.campaignId()));
    assertEquals(completedAt, jdbc.queryForObject("SELECT completed_at FROM support_campaign WHERE id=?", Timestamp.class, fixture.campaignId()));
    assertEquals(1, integer("SELECT COUNT(*) FROM support_settlement WHERE campaign_id=?", fixture.campaignId()));
    assertEquals(1, integer("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='SETTLE' AND from_state='COMPLETED' AND to_state='SETTLED' AND actor_user_id=? AND reason IS NULL", fixture.campaignId(), ADMIN));
    assertEquals("ACTIVE", text("SELECT status FROM animal_case WHERE id=?", fixture.caseId()));

    String publicBody = mvc.perform(get("/api/v1/cases/{id}/support/settlement", fixture.caseId()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    JsonNode publicJson = json.readTree(publicBody);
    assertEquals(7, publicJson.size());
    for (String hidden : List.of("settledByUserId", "idempotencyKey", "requestHash", "campaignId", "version")) {
      assertFalse(publicJson.has(hidden));
    }
    mvc.perform(get("/api/v1/admin/support-campaigns/{id}", fixture.campaignId()).header("X-User-Id", ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.settlement.settledByUserId").value(ADMIN))
        .andExpect(jsonPath("$.settlement.idempotencyKey").doesNotExist())
        .andExpect(jsonPath("$.settlement.requestHash").doesNotExist());
    String campaignBody = mvc.perform(get("/api/v1/cases/{id}/support", fixture.caseId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.completedAt").isNotEmpty()).andReturn().getResponse().getContentAsString();
    JsonNode campaignJson = json.readTree(campaignBody);
    assertMoney(body.required("totalContributed").decimalValue().toPlainString(), campaignJson.required("recordedContributionAmount"));
    assertMoney(body.required("totalExpenses").decimalValue().toPlainString(), campaignJson.required("publishedExpenseAmount"));
    assertMoney(body.required("netBalance").decimalValue().toPlainString(), campaignJson.required("recordedNetBalance"));
    mvc.perform(get("/api/v1/cases/{id}/support/expenses", fixture.caseId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].expenseId").value(published));
  }

  @Test
  void zeroNegativeStatusRulesAndPendingGuardAreExact() throws Exception {
    Fixture zero = fixture("zero", "COMPLETED");
    contribution(zero.campaignId(), "200.20", "RECORDED");
    expense(zero.campaignId(), "200.20", "PUBLISHED");
    assertMoney("0.00", json.readTree(settle(zero.campaignId(), ADMIN, "zero", "summary", "note").body()).required("netBalance"));

    Fixture negative = fixture("negative", "COMPLETED");
    contribution(negative.campaignId(), "200.20", "RECORDED");
    contribution(negative.campaignId(), "900.00", "VOIDED");
    expense(negative.campaignId(), "250.50", "PUBLISHED");
    expense(negative.campaignId(), "20.00", "REJECTED");
    expense(negative.campaignId(), "30.00", "VOIDED");
    JsonNode negativeBody = json.readTree(settle(negative.campaignId(), ADMIN, "negative", "summary", "note").body());
    assertMoney("200.20", negativeBody.required("totalContributed"));
    assertMoney("250.50", negativeBody.required("totalExpenses"));
    assertMoney("-50.30", negativeBody.required("netBalance"));

    Fixture pending = fixture("pending", "COMPLETED");
    expense(pending.campaignId(), "1.00", "SUBMITTED");
    Call blocked = settle(pending.campaignId(), ADMIN, "pending", "summary", "note");
    assertError(blocked, 409, "SETTLEMENT_PENDING_EXPENSE");
    assertEquals(0, integer("SELECT COUNT(*) FROM support_settlement WHERE campaign_id=?", pending.campaignId()));
    assertEquals("COMPLETED", text("SELECT status FROM support_campaign WHERE id=?", pending.campaignId()));
    assertEquals(0, integer("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='SETTLE'", pending.campaignId()));
  }

  @Test
  void wrongStatesAuthorizationAndValidationAreRejected() throws Exception {
    for (String state : List.of("ACTIVE", "DRAFT", "PENDING_REVIEW", "APPROVED", "REJECTED", "CANCELLED", "SETTLED")) {
      Fixture fixture = fixture("state-" + state, state);
      assertError(settle(fixture.campaignId(), ADMIN, "key-" + state, "summary", "note"), 409, "CAMPAIGN_STATE_CONFLICT");
    }
    Fixture auth = fixture("auth", "COMPLETED");
    assertError(settle(auth.campaignId(), OWNER, "owner-key", "summary", "note"), 403, "FINANCIAL_ACCESS_DENIED");
    assertError(settle(auth.campaignId(), INACTIVE_ADMIN, "inactive-key", "summary", "note"), 401, "UNAUTHORIZED");
    Call anonymous = rawSettlement(auth.campaignId(), null, "anonymous-key", body("summary", "note"));
    assertError(anonymous, 401, "UNAUTHORIZED");

    List<Call> invalid = new ArrayList<>();
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, null, body("summary", "note")));
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, " ", body("summary", "note")));
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, "x".repeat(129), body("summary", "note")));
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, "unsafe\nkey", body("summary", "note")));
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, "missing-summary", "{\"dispositionNote\":\"note\"}"));
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, "blank-summary", body(" ", "note")));
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, "long-summary", body("x".repeat(2001), "note")));
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, "missing-note", "{\"summary\":\"summary\"}"));
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, "blank-note", body("summary", " ")));
    invalid.add(rawSettlement(auth.campaignId(), ADMIN, "long-note", body("summary", "x".repeat(2001))));
    for (Call call : invalid) assertError(call, 400, "VALIDATION_ERROR");
    assertEquals(0, integer("SELECT COUNT(*) FROM support_settlement WHERE campaign_id=?", auth.campaignId()));

    Fixture normalizedBoundary = fixture("normalized-boundary", "COMPLETED");
    Call boundary = settle(normalizedBoundary.campaignId(), ADMIN, "boundary-key",
        " " + "x".repeat(2000) + " ", " " + "y".repeat(2000) + " ");
    assertEquals(201, boundary.status());
    assertEquals(2000, json.readTree(boundary.body()).required("summary").asText().length());
    assertEquals(2000, json.readTree(boundary.body()).required("dispositionNote").asText().length());
  }

  @Test
  void idempotencyUsesNormalizedNarrativeAndNeverMutatesReplay() throws Exception {
    Fixture fixture = fixture("idempotency", "COMPLETED");
    contribution(fixture.campaignId(), "10.00", "RECORDED");
    Call first = settle(fixture.campaignId(), ADMIN, " replay-key ", " final summary ", " disposition ");
    assertEquals(201, first.status());
    JsonNode firstBody = json.readTree(first.body());
    Timestamp settledAt = jdbc.queryForObject("SELECT created_at FROM support_settlement WHERE campaign_id=?", Timestamp.class, fixture.campaignId());
    Call replay = settle(fixture.campaignId(), ADMIN, "replay-key", "final summary", "disposition");
    assertEquals(200, replay.status());
    assertEquals(firstBody.required("settlementId").asText(), json.readTree(replay.body()).required("settlementId").asText());
    assertEquals(settledAt, jdbc.queryForObject("SELECT created_at FROM support_settlement WHERE campaign_id=?", Timestamp.class, fixture.campaignId()));
    assertEquals(6, integer("SELECT version FROM support_campaign WHERE id=?", fixture.campaignId()));
    assertEquals(1, integer("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='SETTLE'", fixture.campaignId()));
    assertError(settle(fixture.campaignId(), ADMIN, "replay-key", "changed", "disposition"), 409, "IDEMPOTENCY_KEY_REUSED");
    assertError(settle(fixture.campaignId(), ADMIN, "different-key", "final summary", "disposition"), 409, "SETTLEMENT_ALREADY_EXISTS");
  }

  @Test
  void concurrentSettlementRequestsSerializeWithoutDuplicateRows() throws Exception {
    Fixture same = fixture("same-concurrent", "COMPLETED");
    List<Call> sameResults = concurrent(
        () -> settle(same.campaignId(), ADMIN, "same-key", "summary", "note"),
        () -> settle(same.campaignId(), ADMIN, "same-key", "summary", "note"));
    assertEquals(Set.of(200, 201), Set.of(sameResults.get(0).status(), sameResults.get(1).status()));
    assertEquals(1, integer("SELECT COUNT(*) FROM support_settlement WHERE campaign_id=?", same.campaignId()));
    assertEquals(1, integer("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='SETTLE'", same.campaignId()));
    assertEquals(6, integer("SELECT version FROM support_campaign WHERE id=?", same.campaignId()));

    Fixture different = fixture("different-concurrent", "COMPLETED");
    List<Call> differentResults = concurrent(
        () -> settle(different.campaignId(), ADMIN, "left-key", "summary", "note"),
        () -> settle(different.campaignId(), ADMIN, "right-key", "summary", "note"));
    assertEquals(1, differentResults.stream().filter(value -> value.status() == 201).count());
    Call differentLoser = differentResults.stream().filter(value -> value.status() == 409).findFirst().orElseThrow();
    assertError(differentLoser, 409, "SETTLEMENT_ALREADY_EXISTS");

    Fixture changed = fixture("changed-concurrent", "COMPLETED");
    List<Call> changedResults = concurrent(
        () -> settle(changed.campaignId(), ADMIN, "shared-key", "left", "note"),
        () -> settle(changed.campaignId(), ADMIN, "shared-key", "right", "note"));
    assertEquals(1, changedResults.stream().filter(value -> value.status() == 201).count());
    assertError(changedResults.stream().filter(value -> value.status() == 409).findFirst().orElseThrow(), 409, "IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void financialMutationRacesProduceOnlySerializedSnapshots() throws Exception {
    Fixture contributionRace = fixture("contribution-race", "COMPLETED");
    String contribution = contribution(contributionRace.campaignId(), "40.00", "RECORDED");
    List<Call> contributionResults = concurrent(
        () -> settle(contributionRace.campaignId(), ADMIN, "contribution-race", "summary", "note"),
        () -> call(post("/api/v1/admin/support-contributions/{id}/void", contribution).header("X-User-Id", ADMIN)
            .contentType("application/json").content("{\"reason\":\"race\"}")));
    assertEquals(201, contributionResults.stream().filter(value -> value.status() == 201).findFirst().orElseThrow().status());
    String contributionStatus = text("SELECT status FROM support_contribution WHERE id=?", contribution);
    BigDecimal contributionSnapshot = jdbc.queryForObject("SELECT total_contributed FROM support_settlement WHERE campaign_id=?", BigDecimal.class, contributionRace.campaignId());
    assertEquals("RECORDED".equals(contributionStatus) ? new BigDecimal("40.00") : new BigDecimal("0.00"), contributionSnapshot);

    Fixture publishRace = fixture("publish-race", "COMPLETED");
    String submitted = expense(publishRace.campaignId(), "25.00", "SUBMITTED");
    List<Call> publishResults = concurrent(
        () -> settle(publishRace.campaignId(), ADMIN, "publish-race", "summary", "note"),
        () -> call(post("/api/v1/admin/support-expenses/{id}/publish", submitted).header("X-User-Id", ADMIN)));
    assertEquals(200, publishResults.stream().filter(value -> value.status() == 200).findFirst().orElseThrow().status());
    Call settlementAttempt = publishResults.get(0);
    if (settlementAttempt.status() == 201) {
      assertEquals(new BigDecimal("25.00"), jdbc.queryForObject("SELECT total_expenses FROM support_settlement WHERE campaign_id=?", BigDecimal.class, publishRace.campaignId()));
    } else {
      assertError(settlementAttempt, 409, "SETTLEMENT_PENDING_EXPENSE");
      assertEquals("COMPLETED", text("SELECT status FROM support_campaign WHERE id=?", publishRace.campaignId()));
    }

    Fixture voidRace = fixture("void-race", "COMPLETED");
    String published = expense(voidRace.campaignId(), "30.00", "PUBLISHED");
    List<Call> voidResults = concurrent(
        () -> settle(voidRace.campaignId(), ADMIN, "void-race", "summary", "note"),
        () -> call(post("/api/v1/admin/support-expenses/{id}/void", published).header("X-User-Id", ADMIN)
            .contentType("application/json").content("{\"reason\":\"race\"}")));
    assertEquals(201, voidResults.get(0).status());
    String expenseStatus = text("SELECT status FROM support_expense WHERE id=?", published);
    BigDecimal expenseSnapshot = jdbc.queryForObject("SELECT total_expenses FROM support_settlement WHERE campaign_id=?", BigDecimal.class, voidRace.campaignId());
    assertEquals("PUBLISHED".equals(expenseStatus) ? new BigDecimal("30.00") : new BigDecimal("0.00"), expenseSnapshot);
  }

  @Test
  void settledCampaignFreezesEveryFinancialMutationAndManualCompletion() throws Exception {
    Fixture fixture = fixture("freeze", "COMPLETED");
    String contribution = contribution(fixture.campaignId(), "12.00", "RECORDED");
    String published = expense(fixture.campaignId(), "3.00", "PUBLISHED");
    assertEquals(201, settle(fixture.campaignId(), ADMIN, "freeze-key", "summary", "note").status());
    String submitted = expense(fixture.campaignId(), "4.00", "SUBMITTED");
    String rejectedCandidate = expense(fixture.campaignId(), "5.00", "SUBMITTED");

    assertError(call(post("/api/v1/cases/{id}/support/contributions", fixture.caseId()).header("X-User-Id", CONTRIBUTOR)
        .header("Idempotency-Key", "late-contribution").contentType("application/json").content("{\"amount\":1.00}")), 409, "CAMPAIGN_NOT_ACCEPTING_SUPPORT");
    assertError(call(post("/api/v1/admin/support-contributions/{id}/void", contribution).header("X-User-Id", ADMIN)
        .contentType("application/json").content("{\"reason\":\"late\"}")), 409, "CONTRIBUTION_STATE_CONFLICT");
    assertError(call(post("/api/v1/cases/{id}/support/expenses/media/upload-url", fixture.caseId()).header("X-User-Id", OWNER)
        .contentType("application/json").content("{\"fileName\":\"x.png\",\"contentType\":\"image/png\",\"sizeBytes\":4}")), 409, "CAMPAIGN_NOT_ACCEPTING_EXPENSE");
    String expenseBody = "{\"item\":\"late\",\"description\":\"late\",\"amount\":1.00,\"occurredAt\":\"2026-10-13T00:00:00Z\",\"media\":[{\"temporaryObjectKey\":\"incident-input/" + OWNER + "/support-expenses/x.png\",\"contentType\":\"image/png\",\"sizeBytes\":4,\"sortOrder\":0}]}";
    assertError(call(post("/api/v1/cases/{id}/support/expenses", fixture.caseId()).header("X-User-Id", OWNER)
        .header("Idempotency-Key", "late-expense").contentType("application/json").content(expenseBody)), 409, "CAMPAIGN_NOT_ACCEPTING_EXPENSE");
    String replacementBody = expenseBody.substring(0, expenseBody.length() - 1) + ",\"replacesExpenseId\":\"" + published + "\"}";
    assertError(call(post("/api/v1/cases/{id}/support/expenses", fixture.caseId()).header("X-User-Id", OWNER)
        .header("Idempotency-Key", "late-replacement").contentType("application/json").content(replacementBody)), 409, "CAMPAIGN_NOT_ACCEPTING_EXPENSE");
    assertError(call(post("/api/v1/admin/support-expenses/{id}/publish", submitted).header("X-User-Id", ADMIN)), 409, "EXPENSE_STATE_CONFLICT");
    assertError(call(post("/api/v1/admin/support-expenses/{id}/reject", rejectedCandidate).header("X-User-Id", ADMIN)
        .contentType("application/json").content("{\"reason\":\"late\"}")), 409, "EXPENSE_STATE_CONFLICT");
    assertError(call(post("/api/v1/admin/support-expenses/{id}/void", published).header("X-User-Id", ADMIN)
        .contentType("application/json").content("{\"reason\":\"late\"}")), 409, "EXPENSE_STATE_CONFLICT");
    assertError(call(post("/api/v1/admin/support-campaigns/{id}/complete", fixture.campaignId()).header("X-User-Id", ADMIN)
        .contentType("application/json").content("{\"reason\":\"again\"}")), 409, "CAMPAIGN_STATE_CONFLICT");
    assertError(settle(fixture.campaignId(), ADMIN, "different-freeze-key", "summary", "note"), 409, "SETTLEMENT_ALREADY_EXISTS");

    assertEquals("RECORDED", text("SELECT status FROM support_contribution WHERE id=?", contribution));
    assertEquals("PUBLISHED", text("SELECT status FROM support_expense WHERE id=?", published));
    assertEquals("SUBMITTED", text("SELECT status FROM support_expense WHERE id=?", submitted));
    assertEquals(1, integer("SELECT COUNT(*) FROM support_settlement WHERE campaign_id=?", fixture.campaignId()));
    assertEquals(1, integer("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='SETTLE'", fixture.campaignId()));
  }

  @Test
  void publicSettlementTracksCurrentPublicLifecycleAndKeepsEvidenceExplicit() throws Exception {
    Fixture fixture = fixture("history", "COMPLETED");
    String published = expense(fixture.campaignId(), "15.00", "PUBLISHED");
    String mediaId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO support_expense_media(id,expense_id,object_key,content_type,size_bytes,sort_order,created_at) VALUES (?,?,?,?,?,?,?)",
        mediaId, published, "incident/support-expenses/evidence.png", "image/png", 4, 0, now());
    mvc.perform(get("/api/v1/cases/{id}/support/settlement", fixture.caseId()))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
    settle(fixture.campaignId(), ADMIN, "history-key", "summary", "note");
    mvc.perform(get("/api/v1/cases/{id}/support/expenses", fixture.caseId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].media[0].mediaId").value(mediaId))
        .andExpect(jsonPath("$[0].media[0].objectKey").doesNotExist())
        .andExpect(jsonPath("$[0].media[0].signedUrl").doesNotExist());
    var access = expenses.evidenceAccess(fixture.caseId(), published, mediaId, null);
    assertFalse(access.volunteerRequired());
    assertEquals("incident/support-expenses/evidence.png", access.objectKey());

    String newer = campaign(fixture.caseId(), "ACTIVE", now().toInstant().plusSeconds(2));
    assertNotNull(newer);
    mvc.perform(get("/api/v1/cases/{id}/support/settlement", fixture.caseId()))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
  }

  @Test
  void failureAfterSettlementInsertRollsBackEverySettlementMutation() throws Exception {
    Fixture fixture = fixture("rollback", "COMPLETED");
    jdbc.execute("ALTER TABLE support_audit_log ADD CONSTRAINT chk_test_fail_settle_audit CHECK (action <> 'SETTLE')");
    try {
      assertEquals(500, settle(fixture.campaignId(), ADMIN, "rollback-key", "summary", "note").status());
    } finally {
      jdbc.execute("ALTER TABLE support_audit_log DROP CHECK chk_test_fail_settle_audit");
    }
    assertEquals(0, integer("SELECT COUNT(*) FROM support_settlement WHERE campaign_id=?", fixture.campaignId()));
    assertEquals("COMPLETED", text("SELECT status FROM support_campaign WHERE id=?", fixture.campaignId()));
    assertEquals(5, integer("SELECT version FROM support_campaign WHERE id=?", fixture.campaignId()));
    assertEquals(0, integer("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='SETTLE'", fixture.campaignId()));
  }

  private List<Call> concurrent(Callable<Call> left, Callable<Call> right) throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch go = new CountDownLatch(1);
      Future<Call> first = pool.submit(() -> { go.await(); return left.call(); });
      Future<Call> second = pool.submit(() -> { go.await(); return right.call(); });
      go.countDown();
      return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    }
  }

  private Fixture fixture(String suffix, String campaignStatus) {
    String caseId = seedCase(suffix);
    String campaignId = campaign(caseId, campaignStatus, now().toInstant());
    Timestamp completed = jdbc.queryForObject("SELECT completed_at FROM support_campaign WHERE id=?", Timestamp.class, campaignId);
    return new Fixture(caseId, campaignId, completed);
  }

  private String seedCase(String suffix) {
    String draft = UUID.randomUUID().toString();
    String event = UUID.randomUUID().toString();
    String caseId = UUID.randomUUID().toString();
    Timestamp timestamp = now();
    jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
        draft, REPORTER, CAMPUS, "draft-" + suffix, timestamp, "public", timestamp, timestamp);
    jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        event, draft, REPORTER, CAMPUS, "event-" + suffix, timestamp, timestamp, "public", "VERIFIED", timestamp, timestamp);
    jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,owner_user_id,status,claim_status,claimed_at,version,created_at,updated_at) VALUES (?,?,?,?, 'ACTIVE','CLAIMED',?,2,?,?)",
        caseId, event, CAMPUS, OWNER, timestamp, timestamp, timestamp);
    return caseId;
  }

  private String campaign(String caseId, String status, Instant createdAt) {
    String id = UUID.randomUUID().toString();
    Timestamp created = Timestamp.from(createdAt);
    Timestamp published = List.of("ACTIVE", "COMPLETED", "SETTLED").contains(status) ? created : null;
    Timestamp completed = List.of("COMPLETED", "SETTLED").contains(status) ? created : null;
    jdbc.update("INSERT INTO support_campaign(id,case_id,status,target_amount,currency,purpose,application_note,requested_by_user_id,published_at,completed_at,version,created_at,updated_at) VALUES (?,?,?,1500.00,'CNY','purpose','private',?,?,?,?,?,?)",
        id, caseId, status, OWNER, published, completed, 5, created, created);
    return id;
  }

  private String contribution(String campaignId, String amount, String status) {
    String id = UUID.randomUUID().toString();
    Timestamp timestamp = now();
    boolean voided = "VOIDED".equals(status);
    jdbc.update("INSERT INTO support_contribution(id,campaign_id,contributor_user_id,amount,status,idempotency_key,request_hash,recorded_at,voided_by_user_id,void_reason,voided_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        id, campaignId, CONTRIBUTOR, new BigDecimal(amount), status, UUID.randomUUID().toString(), "0".repeat(64), timestamp,
        voided ? ADMIN : null, voided ? "voided" : null, voided ? timestamp : null);
    return id;
  }

  private String expense(String campaignId, String amount, String status) {
    String id = UUID.randomUUID().toString();
    Timestamp timestamp = now();
    boolean reviewed = !"SUBMITTED".equals(status);
    boolean rejected = "REJECTED".equals(status);
    boolean voided = "VOIDED".equals(status);
    jdbc.update("INSERT INTO support_expense(id,campaign_id,recorded_by_user_id,item,description,amount,status,idempotency_key,request_hash,occurred_at,created_at,reviewed_by_user_id,review_reason,reviewed_at,voided_by_user_id,void_reason,voided_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id, campaignId, OWNER, "item", "description", new BigDecimal(amount), status, UUID.randomUUID().toString(), "0".repeat(64),
        timestamp, timestamp, reviewed ? ADMIN : null, rejected ? "rejected" : null, reviewed ? timestamp : null,
        voided ? ADMIN : null, voided ? "voided" : null, voided ? timestamp : null);
    return id;
  }

  private Call settle(String campaignId, String actor, String key, String summary, String note) throws Exception {
    return rawSettlement(campaignId, actor, key, body(summary, note));
  }

  private Call rawSettlement(String campaignId, String actor, String key, String content) throws Exception {
    var builder = post("/api/v1/admin/support-campaigns/{id}/settlement", campaignId).contentType("application/json").content(content);
    if (actor != null) builder.header("X-User-Id", actor);
    if (key != null) builder.header("Idempotency-Key", key);
    return call(builder);
  }

  private Call call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) throws Exception {
    var response = mvc.perform(builder).andReturn().getResponse();
    return new Call(response.getStatus(), response.getContentAsString());
  }

  private String body(String summary, String note) throws Exception {
    return json.writeValueAsString(Map.of("summary", summary, "dispositionNote", note));
  }

  private void assertError(Call call, int status, String code) throws Exception {
    assertEquals(status, call.status());
    assertEquals(code, json.readTree(call.body()).required("code").asText());
  }

  private static void assertMoney(String expected, JsonNode actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()));
  }

  private int integer(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private String text(String sql, Object... args) {
    return jdbc.queryForObject(sql, String.class, args);
  }

  private static Timestamp now() { return Timestamp.from(Instant.now()); }

  private record Fixture(String caseId, String campaignId, Timestamp completedAt) {}
  private record Call(int status, String body) {}

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
      String path = exchange.getRequestURI().getPath();
      String header = exchange.getRequestHeaders().getFirst("X-User-Id");
      String body;
      if (path.contains("volunteer-membership")) {
        body = "{\"exists\":true,\"active\":true,\"status\":\"ACTIVE\"}";
      } else {
        String account = accounts.getOrDefault(header, "ACTIVE");
        String role = ADMIN.equals(header) || INACTIVE_ADMIN.equals(header) ? "GOVERNANCE_ADMIN" : "USER";
        body = "{\"id\":\"" + header + "\",\"accountStatus\":\"" + account + "\",\"systemRole\":\"" + role + "\"}";
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}
