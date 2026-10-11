package org.animallink.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SupportExpenseIntegrationTest {
  private static final String CAMPUS = "73000000-0000-0000-0000-000000000001";
  private static final String OWNER = "73000000-0000-0000-0000-000000000011";
  private static final String PARTICIPANT = "73000000-0000-0000-0000-000000000012";
  private static final String UNRELATED = "73000000-0000-0000-0000-000000000013";
  private static final String NO_VOLUNTEER = "73000000-0000-0000-0000-000000000014";
  private static final String INACTIVE = "73000000-0000-0000-0000-000000000015";
  private static final String ADMIN = "73000000-0000-0000-0000-000000000016";
  private static final String REPORTER = "73000000-0000-0000-0000-000000000017";
  private static final String BUCKET = "animallink-incident";
  private static final Stub STUB = new Stub();

  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
      .withDatabaseName("support_expense_test").withUsername("test").withPassword("test")
      .withCommand("--log-bin-trust-function-creators=1");
  @Container static final GenericContainer<?> MINIO = new GenericContainer<>("animallink-minio:RELEASE.2025-10-15T17-29-55Z")
      .withExposedPorts(9000).withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
      .withCommand("server /data");

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
    registry.add("animallink.minio.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
    registry.add("animallink.minio.access-key", () -> "minioadmin");
    registry.add("animallink.minio.secret-key", () -> "minioadmin");
    registry.add("animallink.minio.bucket", () -> BUCKET);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MinioClient minio;

  @BeforeEach
  void reset() throws Exception {
    STUB.accounts.clear();
    STUB.volunteers.clear();
    for (String user : List.of(OWNER, PARTICIPANT, UNRELATED, NO_VOLUNTEER, ADMIN)) STUB.accounts.put(user, "ACTIVE");
    STUB.accounts.put(INACTIVE, "SUSPENDED");
    STUB.volunteers.put(OWNER, true);
    STUB.volunteers.put(PARTICIPANT, true);
    jdbc.execute("DROP TRIGGER IF EXISTS fail_support_expense_insert");
    jdbc.update("DELETE FROM support_expense_media");
    jdbc.update("UPDATE support_expense SET replaces_expense_id=NULL WHERE replaces_expense_id IS NOT NULL");
    jdbc.update("DELETE FROM support_expense");
    jdbc.update("DELETE FROM support_contribution");
    jdbc.update("DELETE FROM support_audit_log");
    jdbc.update("DELETE FROM support_campaign");
    jdbc.update("DELETE FROM case_participant");
    jdbc.update("DELETE FROM animal_case");
    jdbc.update("DELETE FROM evidence_media");
    jdbc.update("DELETE FROM evidence");
    jdbc.update("DELETE FROM event_media");
    jdbc.update("DELETE FROM event");
    jdbc.update("DELETE FROM event_draft_media");
    jdbc.update("DELETE FROM event_draft");
    if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
      minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }
    for (Result<Item> result : minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build())) {
      minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(result.get().objectName()).build());
    }
  }

  @AfterAll static void stop() { STUB.server.stop(0); }

  @Test
  void flywayV14CreatesOnlyFrozenExpenseTablesAndConstraints() throws Exception {
    assertEquals(14, count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
    assertEquals("phase5d3 support expense", jdbc.queryForObject("SELECT description FROM flyway_schema_history WHERE version='14'", String.class));
    assertEquals(2, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('support_expense','support_expense_media')"));
    assertEquals(0, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='support_settlement'"));
    Fixture fixture = activeFixture("schema");
    MediaRef media = image(OWNER, "schema.png", "image/png", png());
    String expense = create(fixture.caseId(), OWNER, "schema-key", request("food", "10.25", null, List.of(media)), 201).required("expenseId").asText();
    assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE support_expense SET amount=0 WHERE id=?", expense));
    assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE support_expense SET status='PUBLISHED' WHERE id=?", expense));
    assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE support_expense_media SET content_type='image/gif' WHERE expense_id=?", expense));
  }

  @Test
  void acceptsOneTwoSixAndAllSupportedImageTypes() throws Exception {
    Fixture fixture = activeFixture("cardinality");
    List<MediaRef> one = List.of(image(OWNER, "one.jpg", "image/jpeg", jpeg()));
    assertEquals(1, create(fixture.caseId(), OWNER, "one", request("one", "1.00", null, one), 201).required("media").size());
    List<MediaRef> two = List.of(image(OWNER, "two.png", "image/png", png(), 0), image(OWNER, "two.webp", "image/webp", webp(), 1));
    assertEquals(2, create(fixture.caseId(), OWNER, "two", request("two", "2.00", null, two), 201).required("media").size());
    List<MediaRef> six = new ArrayList<>();
    for (int index = 0; index < 6; index++) six.add(image(OWNER, "six-" + index + ".png", "image/png", png(), index));
    assertEquals(6, create(fixture.caseId(), OWNER, "six", request("six", "6.00", null, six), 201).required("media").size());
    assertEquals(9, count("SELECT COUNT(*) FROM support_expense_media WHERE expense_id IN (SELECT id FROM support_expense WHERE campaign_id=?)", fixture.campaignId()));
  }

  @Test
  void rejectsInvalidCardinalityMetadataSignaturesAndDecode() throws Exception {
    Fixture fixture = activeFixture("validation");
    expectCreate(fixture.caseId(), OWNER, "zero", request("zero", "1.00", null, List.of()), 400, "EXPENSE_EVIDENCE_REQUIRED");
    List<MediaRef> seven = new ArrayList<>();
    for (int i = 0; i < 7; i++) seven.add(new MediaRef("missing-" + i, "image/png", 1, i));
    expectCreate(fixture.caseId(), OWNER, "seven", request("seven", "1.00", null, seven), 400, "EXPENSE_EVIDENCE_INVALID");
    MediaRef valid = image(OWNER, "valid.png", "image/png", png());
    expectCreate(fixture.caseId(), OWNER, "duplicate-order", request("x", "1.00", null,
        List.of(valid, new MediaRef(valid.key(), valid.type(), valid.size(), 0))), 400, "EXPENSE_EVIDENCE_INVALID");
    expectCreate(fixture.caseId(), OWNER, "gap", request("x", "1.00", null,
        List.of(new MediaRef(valid.key(), valid.type(), valid.size(), 1))), 400, "EXPENSE_EVIDENCE_INVALID");
    expectCreate(fixture.caseId(), OWNER, "too-large", request("x", "1.00", null,
        List.of(new MediaRef(valid.key(), valid.type(), 10_485_761, 0))), 400, "EXPENSE_EVIDENCE_INVALID");
    List<MediaRef> total = new ArrayList<>();
    for (int i = 0; i < 4; i++) total.add(new MediaRef("absent-" + i, "image/png", 8_000_000, i));
    expectCreate(fixture.caseId(), OWNER, "total", request("x", "1.00", null, total), 400, "EXPENSE_EVIDENCE_INVALID");

    byte[] arbitrary = "not-an-image".getBytes(StandardCharsets.UTF_8);
    MediaRef badMagic = image(OWNER, "bad.jpg", "image/jpeg", arbitrary);
    expectCreate(fixture.caseId(), OWNER, "bad-magic", request("x", "1.00", null, List.of(badMagic)), 400, "EXPENSE_EVIDENCE_INVALID");
    byte[] corruptPng = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0};
    MediaRef corrupt = image(OWNER, "corrupt.png", "image/png", corruptPng);
    expectCreate(fixture.caseId(), OWNER, "corrupt", request("x", "1.00", null, List.of(corrupt)), 400, "EXPENSE_EVIDENCE_INVALID");
    MediaRef mismatch = image(OWNER, "metadata.png", "image/png", png());
    expectCreate(fixture.caseId(), OWNER, "metadata", request("x", "1.00", null,
        List.of(new MediaRef(mismatch.key(), "image/jpeg", mismatch.size(), 0))), 400, "EXPENSE_EVIDENCE_INVALID");
    MediaRef wrongOwner = image(PARTICIPANT, "wrong-owner.png", "image/png", png());
    expectCreate(fixture.caseId(), OWNER, "wrong-owner", request("x", "1.00", null,
        List.of(wrongOwner)), 400, "EXPENSE_EVIDENCE_INVALID");

    for (String type : List.of("video/mp4", "image/gif", "image/svg+xml", "application/pdf", "image/*")) {
      mvc.perform(post("/api/v1/cases/{id}/support/expenses/media/upload-url", fixture.caseId()).header("X-User-Id", OWNER)
              .contentType(MediaType.APPLICATION_JSON).content("{\"fileName\":\"x\",\"contentType\":\"" + type + "\",\"sizeBytes\":10}"))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXPENSE_EVIDENCE_INVALID"));
    }
  }

  @Test
  void authorizationAndEligibilityAreScopedToRelatedActiveVolunteers() throws Exception {
    Fixture fixture = activeFixture("authorization");
    participant(fixture.caseId(), PARTICIPANT);
    participant(fixture.caseId(), NO_VOLUNTEER);
    MediaRef ownerMedia = image(OWNER, "owner.png", "image/png", png());
    create(fixture.caseId(), OWNER, "owner", request("owner", "1.00", null, List.of(ownerMedia)), 201);
    MediaRef participantMedia = image(PARTICIPANT, "participant.png", "image/png", png());
    create(fixture.caseId(), PARTICIPANT, "participant", request("participant", "1.00", null, List.of(participantMedia)), 201);
    for (String user : List.of(UNRELATED, NO_VOLUNTEER)) {
      MediaRef media = image(user, user + ".png", "image/png", png());
      mvc.perform(post("/api/v1/cases/{id}/support/expenses", fixture.caseId()).header("X-User-Id", user).header("Idempotency-Key", user)
              .contentType(MediaType.APPLICATION_JSON).content(request("denied", "1.00", null, List.of(media))))
          .andExpect(status().isForbidden());
    }
    mvc.perform(post("/api/v1/cases/{id}/support/expenses", fixture.caseId()).header("Idempotency-Key", "anonymous")
            .contentType(MediaType.APPLICATION_JSON).content(request("x", "1.00", null, List.of(ownerMedia))))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/cases/{id}/support/expenses", fixture.caseId()).header("X-User-Id", INACTIVE).header("Idempotency-Key", "inactive")
            .contentType(MediaType.APPLICATION_JSON).content(request("x", "1.00", null, List.of(ownerMedia))))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/support-expenses").header("X-User-Id", OWNER)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/support-expenses").header("X-User-Id", ADMIN)).andExpect(status().isOk());

    Fixture approved = fixture("approved", "ACTIVE", "CLAIMED", "APPROVED");
    MediaRef approvedMedia = image(OWNER, "approved.png", "image/png", png());
    expectCreate(approved.caseId(), OWNER, "approved", request("x", "1.00", null, List.of(approvedMedia)), 409, "CAMPAIGN_NOT_ACCEPTING_EXPENSE");
    Fixture terminal = activeFixture("terminal");
    jdbc.update("UPDATE animal_case SET status='RESOLVED' WHERE id=?", terminal.caseId());
    MediaRef terminalMedia = image(OWNER, "terminal.png", "image/png", png());
    expectCreate(terminal.caseId(), OWNER, "terminal", request("x", "1.00", null, List.of(terminalMedia)), 409, "CAMPAIGN_NOT_ACCEPTING_EXPENSE");
  }

  @Test
  void idempotencyConflictAndConcurrentReplayDoNotDuplicateMedia() throws Exception {
    Fixture fixture = activeFixture("idempotency");
    MediaRef media = image(OWNER, "same.png", "image/png", png());
    String body = request("food", "100", null, List.of(media));
    JsonNode first = create(fixture.caseId(), OWNER, "same-key", body, 201);
    JsonNode replay = create(fixture.caseId(), OWNER, "same-key", request(" food ", "100.00", null, List.of(media)), 200);
    assertEquals(first.required("expenseId").asText(), replay.required("expenseId").asText());
    assertEquals(first.required("media").get(0).required("mediaId").asText(), replay.required("media").get(0).required("mediaId").asText());
    assertEquals(1, count("SELECT COUNT(*) FROM support_expense WHERE campaign_id=?", fixture.campaignId()));
    assertEquals(1, permanentCount(fixture.campaignId()));
    expectCreate(fixture.caseId(), OWNER, "same-key", request("food", "101.00", null, List.of(media)), 409, "IDEMPOTENCY_KEY_REUSED");
    expectCreate(fixture.caseId(), OWNER, "same-key", request("medicine", "100.00", null, List.of(media)), 409, "IDEMPOTENCY_KEY_REUSED");
    MediaRef differentMedia = image(OWNER, "different.png", "image/png", png());
    expectCreate(fixture.caseId(), OWNER, "same-key", request("food", "100.00", null, List.of(differentMedia)), 409, "IDEMPOTENCY_KEY_REUSED");

    Fixture concurrent = activeFixture("concurrent");
    MediaRef concurrentMedia = image(OWNER, "concurrent.png", "image/png", png());
    String concurrentBody = request("concurrent", "5.00", null, List.of(concurrentMedia));
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch go = new CountDownLatch(1);
      Callable<MvcResult> call = () -> {
        go.await();
        return mvc.perform(post("/api/v1/cases/{id}/support/expenses", concurrent.caseId()).header("X-User-Id", OWNER)
            .header("Idempotency-Key", "race").contentType(MediaType.APPLICATION_JSON).content(concurrentBody)).andReturn();
      };
      Future<MvcResult> a = pool.submit(call), b = pool.submit(call);
      go.countDown();
      MvcResult left = a.get(), right = b.get();
      assertEquals(List.of(200, 201), List.of(left.getResponse().getStatus(), right.getResponse().getStatus()).stream().sorted().toList());
      assertEquals(json.readTree(left.getResponse().getContentAsString()).required("expenseId").asText(),
          json.readTree(right.getResponse().getContentAsString()).required("expenseId").asText());
    }
    assertEquals(1, count("SELECT COUNT(*) FROM support_expense WHERE campaign_id=?", concurrent.campaignId()));
    assertEquals(1, count("SELECT COUNT(*) FROM support_expense_media WHERE expense_id IN (SELECT id FROM support_expense WHERE campaign_id=?)", concurrent.campaignId()));
    assertEquals(1, permanentCount(concurrent.campaignId()));
  }

  @Test
  void lifecycleReplacementPrivacyEvidenceAndTerminalReviewRemainImmutable() throws Exception {
    Fixture fixture = activeFixture("lifecycle");
    participant(fixture.caseId(), PARTICIPANT);
    MediaRef firstMedia = image(OWNER, "first.png", "image/png", png());
    JsonNode first = create(fixture.caseId(), OWNER, "first", request("first", "50.10", null, List.of(firstMedia)), 201);
    String firstId = first.required("expenseId").asText();
    String firstMediaId = first.required("media").get(0).required("mediaId").asText();
    mvc.perform(get("/api/v1/cases/{c}/support/expenses/{e}/evidence/{m}/access", fixture.caseId(), firstId, firstMediaId))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/cases/{c}/support/expenses/{e}/evidence/{m}/access", fixture.caseId(), firstId, firstMediaId).header("X-User-Id", OWNER))
        .andExpect(status().isOk()).andExpect(jsonPath("$.expiresInSeconds").value(600)).andExpect(jsonPath("$.objectKey").doesNotExist());
    mvc.perform(get("/api/v1/cases/{c}/support/expenses/{e}/private", fixture.caseId(), firstId).header("X-User-Id", PARTICIPANT))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
    mvc.perform(get("/api/v1/cases/{c}/support/expenses/{e}/private", fixture.caseId(), firstId).header("X-User-Id", UNRELATED))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/support-expenses/{e}/evidence/{m}/access", firstId, firstMediaId).header("X-User-Id", ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.expiresInSeconds").value(600));

    jdbc.update("UPDATE animal_case SET status='RESOLVED' WHERE id=?", fixture.caseId());
    mvc.perform(post("/api/v1/admin/support-expenses/{id}/reject", firstId).header("X-User-Id", ADMIN)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"bad receipt\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    assertEquals(1, count("SELECT COUNT(*) FROM support_expense_media WHERE expense_id=?", firstId));
    MediaRef replacementMedia = image(OWNER, "replacement.png", "image/png", png());
    JsonNode replacement = createAfterCaseRestore(fixture, "replacement", request("corrected", "50.10", firstId, List.of(replacementMedia)));
    String replacementId = replacement.required("expenseId").asText();
    assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM support_expense WHERE id=?", String.class, firstId));
    MediaRef branch = image(OWNER, "branch.png", "image/png", png());
    expectCreate(fixture.caseId(), OWNER, "branch", request("branch", "1.00", firstId, List.of(branch)), 409, "INVALID_EXPENSE_REPLACEMENT");
    mvc.perform(post("/api/v1/admin/support-expenses/{id}/publish", replacementId).header("X-User-Id", ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
    mvc.perform(post("/api/v1/admin/support-expenses/{id}/reject", replacementId).header("X-User-Id", ADMIN)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"late\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPENSE_STATE_CONFLICT"));
    mvc.perform(get("/api/v1/cases/{c}/support/expenses", fixture.caseId())).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].expenseId").value(replacementId)).andExpect(jsonPath("$[0].recordedByUserId").doesNotExist())
        .andExpect(jsonPath("$[0].media[0].readUrl").doesNotExist());
    mvc.perform(get("/api/v1/cases/{c}/support/expenses/{e}/evidence/{m}/access", fixture.caseId(), replacementId,
            replacement.required("media").get(0).required("mediaId").asText()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.expiresInSeconds").value(600));
    mvc.perform(post("/api/v1/admin/support-expenses/{id}/void", replacementId).header("X-User-Id", ADMIN)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"corrected again\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED")).andExpect(jsonPath("$.amount").value(50.10));
    assertEquals(1, count("SELECT COUNT(*) FROM support_expense_media WHERE expense_id=?", replacementId));
    assertEquals(5, count("SELECT COUNT(*) FROM support_audit_log WHERE resource_type='EXPENSE' AND campaign_id=?", fixture.campaignId()));
  }

  @Test
  void publicAggregateUsesOnlyPublishedExpensesAndAllowsNegativeNet() throws Exception {
    Fixture fixture = activeFixture("aggregate");
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("INSERT INTO support_contribution(id,campaign_id,contributor_user_id,amount,status,idempotency_key,request_hash,recorded_at) VALUES (?,?,?,200.20,'RECORDED','aggregate-contribution',?,?)",
        UUID.randomUUID().toString(), fixture.campaignId(), UNRELATED, "0".repeat(64), now);
    String first = create(fixture.caseId(), OWNER, "expense-a", request("food", "50.10", null,
        List.of(image(OWNER, "aggregate-a.png", "image/png", png()))), 201).required("expenseId").asText();
    publish(first);
    mvc.perform(get("/api/v1/cases/{id}/support", fixture.caseId())).andExpect(status().isOk())
        .andExpect(jsonPath("$.publishedExpenseAmount").value(50.10)).andExpect(jsonPath("$.expenseCount").value(1))
        .andExpect(jsonPath("$.recordedNetBalance").value(150.10));
    String second = create(fixture.caseId(), OWNER, "expense-b", request("surgery", "300.30", null,
        List.of(image(OWNER, "aggregate-b.png", "image/png", png()))), 201).required("expenseId").asText();
    publish(second);
    mvc.perform(get("/api/v1/cases/{id}/support", fixture.caseId())).andExpect(status().isOk())
        .andExpect(jsonPath("$.publishedExpenseAmount").value(350.40)).andExpect(jsonPath("$.expenseCount").value(2))
        .andExpect(jsonPath("$.recordedNetBalance").value(-150.20));
    voidExpense(first);
    mvc.perform(get("/api/v1/cases/{id}/support", fixture.caseId())).andExpect(status().isOk())
        .andExpect(jsonPath("$.publishedExpenseAmount").value(300.30)).andExpect(jsonPath("$.expenseCount").value(1))
        .andExpect(jsonPath("$.recordedNetBalance").value(-100.10))
        .andExpect(jsonPath("$.recordedContributionAmount").value(200.20));
  }

  @Test
  void partialFormalizationAndDatabaseFailureLeaveNoCommittedOrExposedExpense() throws Exception {
    Fixture fixture = activeFixture("compensation");
    MediaRef first = image(OWNER, "first-valid.png", "image/png", png());
    MediaRef missing = new MediaRef("incident-input/" + OWNER + "/support-expenses/missing.png", "image/png", png().length, 1);
    expectCreate(fixture.caseId(), OWNER, "missing-second", request("x", "1.00", null,
        List.of(first, missing)), 503, "MINIO_UNAVAILABLE");
    assertEquals(0, count("SELECT COUNT(*) FROM support_expense WHERE campaign_id=?", fixture.campaignId()));
    assertEquals(0, permanentCount(fixture.campaignId()));

    MediaRef dbFailure = image(OWNER, "db-failure.png", "image/png", png());
    jdbc.execute("CREATE TRIGGER fail_support_expense_insert BEFORE INSERT ON support_expense FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced test failure'");
    try {
      mvc.perform(post("/api/v1/cases/{id}/support/expenses", fixture.caseId()).header("X-User-Id", OWNER)
              .header("Idempotency-Key", "db-failure").contentType(MediaType.APPLICATION_JSON)
              .content(request("x", "1.00", null, List.of(dbFailure))))
          .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS fail_support_expense_insert");
    }
    assertEquals(0, count("SELECT COUNT(*) FROM support_expense WHERE campaign_id=?", fixture.campaignId()));
    assertEquals(0, count("SELECT COUNT(*) FROM support_expense_media"));
    assertEquals(0, permanentCount(fixture.campaignId()));
    mvc.perform(get("/api/v1/cases/{id}/support/expenses", fixture.caseId())).andExpect(status().isOk()).andExpect(content().json("[]"));
  }

  private JsonNode createAfterCaseRestore(Fixture fixture, String key, String body) throws Exception {
    jdbc.update("UPDATE animal_case SET status='ACTIVE' WHERE id=?", fixture.caseId());
    return create(fixture.caseId(), OWNER, key, body, 201);
  }

  private void publish(String expenseId) throws Exception {
    mvc.perform(post("/api/v1/admin/support-expenses/{id}/publish", expenseId).header("X-User-Id", ADMIN))
        .andExpect(status().isOk());
  }

  private void voidExpense(String expenseId) throws Exception {
    mvc.perform(post("/api/v1/admin/support-expenses/{id}/void", expenseId).header("X-User-Id", ADMIN)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"correction\"}"))
        .andExpect(status().isOk());
  }

  private JsonNode create(String caseId, String user, String key, String body, int status) throws Exception {
    String response = mvc.perform(post("/api/v1/cases/{id}/support/expenses", caseId).header("X-User-Id", user)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    return json.readTree(response);
  }

  private void expectCreate(String caseId, String user, String key, String body, int status, String code) throws Exception {
    mvc.perform(post("/api/v1/cases/{id}/support/expenses", caseId).header("X-User-Id", user)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
  }

  private String request(String item, String amount, String replacement, List<MediaRef> media) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("item", item);
    body.put("description", "expense description");
    body.put("amount", new java.math.BigDecimal(amount));
    body.put("occurredAt", "2026-10-10T01:02:03.123456789Z");
    body.put("replacesExpenseId", replacement);
    List<Map<String, Object>> items = new ArrayList<>();
    for (int index = 0; index < media.size(); index++) {
      MediaRef ref = media.get(index);
      items.add(Map.of("temporaryObjectKey", ref.key(), "contentType", ref.type(), "sizeBytes", ref.size(), "sortOrder", ref.order()));
    }
    body.put("media", items);
    return json.writeValueAsString(body);
  }

  private MediaRef image(String owner, String name, String type, byte[] bytes) throws Exception {
    return image(owner, name, type, bytes, 0);
  }

  private MediaRef image(String owner, String name, String type, byte[] bytes, int order) throws Exception {
    String key = "incident-input/" + owner + "/support-expenses/" + UUID.randomUUID() + "-" + name;
    minio.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key)
        .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType(type).build());
    return new MediaRef(key, type, bytes.length, order);
  }

  private static byte[] png() throws IOException { return imageBytes("png", BufferedImage.TYPE_INT_ARGB); }
  private static byte[] jpeg() throws IOException { return imageBytes("jpg", BufferedImage.TYPE_INT_RGB); }
  private static byte[] imageBytes(String format, int type) throws IOException {
    BufferedImage image = new BufferedImage(2, 2, type);
    image.setRGB(0, 0, 0xff336699);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, format, output));
    return output.toByteArray();
  }
  private static byte[] webp() {
    ByteBuffer bytes = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22).put("WEBP".getBytes(StandardCharsets.US_ASCII));
    bytes.put("VP8 ".getBytes(StandardCharsets.US_ASCII)).putInt(10);
    bytes.put(new byte[]{0, 0, 0, (byte) 0x9d, 0x01, 0x2a, 0x01, 0, 0x01, 0});
    return bytes.array();
  }

  private int permanentCount(String campaignId) throws Exception {
    int count = 0;
    String prefix = "support-expenses/" + campaignId + "/";
    for (Result<Item> result : minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(prefix).recursive(true).build())) {
      result.get();
      count++;
    }
    return count;
  }

  private Fixture activeFixture(String suffix) { return fixture(suffix, "ACTIVE", "CLAIMED", "ACTIVE"); }
  private Fixture fixture(String suffix, String caseStatus, String claimStatus, String campaignStatus) {
    String draft = "draft-" + suffix, event = "event-" + suffix, caseId = "case-" + suffix, campaign = "campaign-" + suffix;
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
        draft, REPORTER, CAMPUS, "draft", now, "public", now, now);
    jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        event, draft, REPORTER, CAMPUS, "event", now, now, "public", "VERIFIED", now, now);
    jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,owner_user_id,status,claim_status,claimed_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        caseId, event, CAMPUS, OWNER, caseStatus, claimStatus, "CLAIMED".equals(claimStatus) ? now : null, now, now);
    jdbc.update("INSERT INTO support_campaign(id,case_id,status,target_amount,currency,purpose,application_note,requested_by_user_id,published_at,created_at,updated_at) VALUES (?,?,?,?, 'CNY','support','private',?,?,?,?)",
        campaign, caseId, campaignStatus, new java.math.BigDecimal("1000.00"), OWNER, "ACTIVE".equals(campaignStatus) ? now : null, now, now);
    return new Fixture(caseId, campaign);
  }

  private void participant(String caseId, String user) {
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("INSERT INTO case_participant(id,case_id,user_id,status,invited_by_user_id,invited_at,joined_at,created_at,updated_at) VALUES (?,?,?,'ACTIVE',?,?,?,?,?)",
        UUID.randomUUID().toString(), caseId, user, OWNER, now, now, now, now);
  }

  private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
  private record Fixture(String caseId, String campaignId) {}
  private record MediaRef(String key, String type, long size, int order) {}

  private static class Stub {
    final HttpServer server;
    final Map<String, String> accounts = new ConcurrentHashMap<>();
    final Map<String, Boolean> volunteers = new ConcurrentHashMap<>();

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
      String user = exchange.getRequestHeaders().getFirst("X-User-Id");
      String body;
      if (path.contains("volunteer-membership")) {
        int start = path.indexOf("/users/") + "/users/".length();
        int end = path.indexOf("/campuses/", start);
        user = path.substring(start, end);
        boolean active = Boolean.TRUE.equals(volunteers.get(user));
        body = "{\"exists\":" + active + ",\"active\":" + active + ",\"status\":\"" + (active ? "ACTIVE" : "PAUSED") + "\"}";
      } else {
        body = "{\"id\":\"" + user + "\",\"accountStatus\":\"" + accounts.getOrDefault(user, "SUSPENDED")
            + "\",\"systemRole\":\"" + (ADMIN.equals(user) ? "GOVERNANCE_ADMIN" : "USER") + "\"}";
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}
