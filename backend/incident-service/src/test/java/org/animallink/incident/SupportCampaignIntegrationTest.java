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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class SupportCampaignIntegrationTest {
  private static final String CAMPUS="71000000-0000-0000-0000-000000000001";
  private static final String OWNER="71000000-0000-0000-0000-000000000011";
  private static final String PARTICIPANT="71000000-0000-0000-0000-000000000012";
  private static final String OTHER="71000000-0000-0000-0000-000000000013";
  private static final String ADMIN="71000000-0000-0000-0000-000000000014";
  private static final String REPORTER="71000000-0000-0000-0000-000000000015";
  private static final Stub STUB=new Stub();
  @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.41")
      .withDatabaseName("support_campaign_test").withUsername("test").withPassword("test");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",MYSQL::getJdbcUrl);
    r.add("spring.datasource.username",MYSQL::getUsername);
    r.add("spring.datasource.password",MYSQL::getPassword);
    r.add("spring.cloud.nacos.discovery.enabled",()->false);
    r.add("spring.cloud.nacos.config.enabled",()->false);
    r.add("animallink.identity.base-url",STUB::url);
    r.add("animallink.animal.base-url",STUB::url);
    r.add("animallink.intelligence.base-url",STUB::url);
    r.add("animallink.minio.endpoint",()->"http://localhost:9000");
    r.add("animallink.minio.access-key",()->"a");
    r.add("animallink.minio.secret-key",()->"b");
    r.add("animallink.minio.bucket",()->"b");
  }
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @BeforeEach void reset() {
    STUB.members.clear();
    STUB.members.put(OWNER,"ACTIVE");
    STUB.members.put(PARTICIPANT,"ACTIVE");
    STUB.members.put(OTHER,"ACTIVE");
    jdbc.update("DELETE FROM support_expense_media");
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
    jdbc.update("DELETE FROM event_draft");
  }
  @AfterAll static void stop() { STUB.server.stop(0); }

  @Test void freshFlywayAndDatabaseInvariants() {
    assertEquals(14,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL",Integer.class));
    assertEquals("phase5d1 support campaign",jdbc.queryForObject("SELECT description FROM flyway_schema_history WHERE version='12'",String.class));
    String c=seed("db","ACTIVE","CLAIMED");
    String id=create(c, OWNER, null);
    assertEquals(c,jdbc.queryForObject("SELECT ongoing_case_id FROM support_campaign WHERE id=?",String.class,id));
    assertThrows(RuntimeException.class,()->jdbc.update("UPDATE support_campaign SET target_amount=0 WHERE id=?",id));
    assertThrows(RuntimeException.class,()->jdbc.update("UPDATE support_campaign SET currency='USD' WHERE id=?",id));
    Timestamp t=Timestamp.from(Instant.now());
    assertThrows(RuntimeException.class,()->jdbc.update("INSERT INTO support_campaign(id,case_id,status,target_amount,currency,purpose,application_note,requested_by_user_id,created_at,updated_at) VALUES (?,?, 'DRAFT',10,'CNY','p','n',?,?,?)",UUID.randomUUID().toString(),c,OTHER,t,t));
  }

  @Test void requesterEligibilityAmountsAndEvidence() throws Exception {
    String c=seed("elig","ACTIVE","CLAIMED");
    mvc.perform(post("/api/v1/cases/{c}/support",c).header("X-User-Id",OTHER).contentType("application/json").content(draft("10.00",null)))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FINANCIAL_ACCESS_DENIED"));
    STUB.members.put(OWNER,"PAUSED");
    mvc.perform(post("/api/v1/cases/{c}/support",c).header("X-User-Id",OWNER).contentType("application/json").content(draft("10.00",null)))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_VOLUNTEER_REQUIRED"));
    STUB.members.put(OWNER,"ACTIVE");
    for(String amount:new String[]{"0","-1","1.001","10000000000000000.00"}) {
      mvc.perform(post("/api/v1/cases/{c}/support",c).header("X-User-Id",OWNER).contentType("application/json").content(draft(amount,null)))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }
    String e=evidence(c,"good");
    String otherCase=seed("other","ACTIVE","CLAIMED");
    String wrong=evidence(otherCase,"wrong");
    mvc.perform(post("/api/v1/cases/{c}/support",c).header("X-User-Id",OWNER).contentType("application/json").content(draft("10.00",wrong)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CASE_EVIDENCE"));
    String id=create(c,OWNER,e);
    assertEquals(e,jdbc.queryForObject("SELECT case_evidence_id FROM support_campaign WHERE id=?",String.class,id));
    mvc.perform(post("/api/v1/cases/{c}/support",c).header("X-User-Id",OWNER).contentType("application/json").content(draft("11.00",null)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPAIGN_ALREADY_OPEN"));
    for(String state:new String[]{"OPEN","RESOLVED","CLOSED","CLOSED_UNRESOLVED","CANCELLED"}) {
      String blocked=seed("blocked-"+state,state,"CLAIMED");
      mvc.perform(post("/api/v1/cases/{c}/support",blocked).header("X-User-Id",OWNER).contentType("application/json").content(draft("10.00",null)))
          .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_NOT_ELIGIBLE_FOR_SUPPORT"));
    }
    String unclaimed=seed("unclaimed","ACTIVE","WAITING_CLAIM");
    mvc.perform(post("/api/v1/cases/{c}/support",unclaimed).header("X-User-Id",OWNER).contentType("application/json").content(draft("10.00",null)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_NOT_ELIGIBLE_FOR_SUPPORT"));
  }

  @Test void participantCanApplyButOnlyOriginalRequesterCanEditOrRead() throws Exception {
    String c=seed("participant","ACTIVE","CLAIMED");
    participant(c,PARTICIPANT);
    String id=create(c,PARTICIPANT,null);
    mvc.perform(patch("/api/v1/cases/{c}/support/draft",c).header("X-User-Id",OWNER).contentType("application/json").content(draft("20.00",null)))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FINANCIAL_ACCESS_DENIED"));
    mvc.perform(get("/api/v1/cases/{c}/support/application",c).header("X-User-Id",OWNER)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/cases/{c}/support/application",c).header("X-User-Id",PARTICIPANT))
        .andExpect(status().isOk()).andExpect(jsonPath("$.campaignId").value(id));
    mvc.perform(patch("/api/v1/cases/{c}/support/draft",c).header("X-User-Id",PARTICIPANT).contentType("application/json").content(draft("20.00",null)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.targetAmount").value(20.00));
    assertEquals(1,count("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='UPDATE_DRAFT'",id));
    jdbc.update("UPDATE case_participant SET status='LEFT',ended_at=? WHERE case_id=? AND user_id=?",Timestamp.from(Instant.now()),c,PARTICIPANT);
    mvc.perform(post("/api/v1/cases/{c}/support/submit",c).header("X-User-Id",PARTICIPANT))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FINANCIAL_ACCESS_DENIED"));
  }

  @Test void draftSubmitReviewPublishAndPrivacy() throws Exception {
    String c=seed("flow","ACTIVE","CLAIMED");
    String id=create(c,OWNER,null);
    mvc.perform(get("/api/v1/cases/{c}/support",c)).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/cases/{c}/support/submit",c).header("X-User-Id",OWNER))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    mvc.perform(post("/api/v1/cases/{c}/support/submit",c).header("X-User-Id",OWNER)).andExpect(status().isConflict());
    mvc.perform(get("/api/v1/cases/{c}/support",c)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/admin/support-campaigns")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/support-campaigns").header("X-User-Id",OWNER)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/support-campaigns").header("X-User-Id",ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].campaignId").value(id));
    mvc.perform(get("/api/v1/admin/support-campaigns/{id}",id).header("X-User-Id",ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.applicationNote").value("private note"));
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/approve",id).header("X-User-Id",OWNER)).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/approve",id).header("X-User-Id",ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    mvc.perform(get("/api/v1/cases/{c}/support",c)).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/publish",id).header("X-User-Id",ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    String publicBody=mvc.perform(get("/api/v1/cases/{c}/support",c)).andExpect(status().isOk())
        .andExpect(jsonPath("$.purpose").value("support purpose")).andReturn().getResponse().getContentAsString();
    JsonNode visible=json.readTree(publicBody);
    assertEquals(14,visible.size());
    assertTrue(visible.has("completedAt"));
    assertTrue(visible.get("completedAt").isNull());
    assertEquals(0,visible.required("publishedExpenseAmount").decimalValue().compareTo(BigDecimal.ZERO));
    assertEquals(0,visible.required("expenseCount").asLong());
    assertEquals(0,visible.required("recordedNetBalance").decimalValue().compareTo(BigDecimal.ZERO));
    for(String field:new String[]{"applicationNote","requestedByUserId","reviewedByUserId","reviewReason","caseEvidenceId","auditLog","objectKey","signedUrl"}) assertFalse(visible.has(field));
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/cancel",id).header("X-User-Id",ADMIN).contentType("application/json").content("{\"reason\":\"no\"}"))
        .andExpect(status().isConflict());
    assertEquals(4,count("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=?",id));
  }

  @Test void withdrawalRejectionAndAdminCancelAllowHistory() throws Exception {
    String c=seed("history","ACTIVE","CLAIMED");
    String first=create(c,OWNER,null);
    mvc.perform(post("/api/v1/cases/{c}/support/cancel",c).header("X-User-Id",OWNER).contentType("application/json").content("{\"reason\":\"withdrawn\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    assertNull(jdbc.queryForObject("SELECT ongoing_case_id FROM support_campaign WHERE id=?",String.class,first));
    String second=create(c,OWNER,null);
    submit(c,OWNER);
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/reject",second).header("X-User-Id",ADMIN))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/reject",second).header("X-User-Id",ADMIN).contentType("application/json").content("{\"reason\":\"insufficient evidence\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    mvc.perform(get("/api/v1/cases/{c}/support/application",c).header("X-User-Id",OWNER))
        .andExpect(status().isOk()).andExpect(jsonPath("$.reviewReason").value("insufficient evidence"));
    String third=create(c,OWNER,null);
    submit(c,OWNER);
    mvc.perform(post("/api/v1/cases/{c}/support/cancel",c).header("X-User-Id",OWNER)).andExpect(status().isOk());
    String fourth=create(c,OWNER,null);
    submit(c,OWNER);
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/approve",fourth).header("X-User-Id",ADMIN)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/cancel",fourth).header("X-User-Id",ADMIN).contentType("application/json").content("{\"reason\":\"withdraw approval\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    assertEquals(4,count("SELECT COUNT(*) FROM support_campaign WHERE case_id=?",c));
    assertEquals(0,count("SELECT COUNT(*) FROM support_campaign WHERE ongoing_case_id=?",c));
    assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM support_campaign WHERE id=?",String.class,third));
  }

  @Test void competingCreationAndReviewHaveOneWinner() throws Exception {
    String c=seed("race","ACTIVE","CLAIMED");
    try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
      CountDownLatch go=new CountDownLatch(1);
      Callable<Integer> create=()->{go.await();return mvc.perform(post("/api/v1/cases/{c}/support",c).header("X-User-Id",OWNER).contentType("application/json").content(draft("10.00",null))).andReturn().getResponse().getStatus();};
      Future<Integer> a=pool.submit(create),b=pool.submit(create);go.countDown();
      assertEquals(1,(a.get()==200?1:0)+(b.get()==200?1:0));
      assertEquals(1,count("SELECT COUNT(*) FROM support_campaign WHERE ongoing_case_id=?",c));
      String id=jdbc.queryForObject("SELECT id FROM support_campaign WHERE ongoing_case_id=?",String.class,c);
      submit(c,OWNER);
      CountDownLatch reviewGo=new CountDownLatch(1);
      Callable<Integer> approve=()->{reviewGo.await();return mvc.perform(post("/api/v1/admin/support-campaigns/{id}/approve",id).header("X-User-Id",ADMIN)).andReturn().getResponse().getStatus();};
      Callable<Integer> reject=()->{reviewGo.await();return mvc.perform(post("/api/v1/admin/support-campaigns/{id}/reject",id).header("X-User-Id",ADMIN).contentType("application/json").content("{\"reason\":\"no\"}")).andReturn().getResponse().getStatus();};
      Future<Integer> x=pool.submit(approve),y=pool.submit(reject);reviewGo.countDown();
      assertEquals(1,(x.get()==200?1:0)+(y.get()==200?1:0));
      assertEquals(1,count("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action IN ('APPROVE','REJECT')",id));
    }
  }

  @Test void governanceCanCompleteActiveCampaignExactlyOnceAndCompletedLedgerRulesApply() throws Exception {
    String c=seed("complete","ACTIVE","CLAIMED");
    String id=create(c,OWNER,null);
    submit(c,OWNER);
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/approve",id).header("X-User-Id",ADMIN)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/publish",id).header("X-User-Id",ADMIN)).andExpect(status().isOk());
    String contribution=json.readTree(mvc.perform(post("/api/v1/cases/{c}/support/contributions",c).header("X-User-Id",OTHER)
        .header("Idempotency-Key","before-complete").contentType("application/json").content("{\"amount\":12.34}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).required("contributionId").asText();
    Timestamp now=Timestamp.from(Instant.now());
    String submitted="expense-submitted",published="expense-published";
    insertExpense(id,submitted,"SUBMITTED",now);
    insertExpense(id,published,"PUBLISHED",now);

    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/complete",id).header("X-User-Id",ADMIN))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/complete",id).header("X-User-Id",OWNER)
        .contentType("application/json").content("{\"reason\":\"done\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FINANCIAL_ACCESS_DENIED"));
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/complete",id).contentType("application/json").content("{\"reason\":\"done\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/complete",id).header("X-User-Id",ADMIN)
        .contentType("application/json").content("{\"reason\":\" fundraising finished \"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.completedAt").isNotEmpty());
    Timestamp completed=jdbc.queryForObject("SELECT completed_at FROM support_campaign WHERE id=?",Timestamp.class,id);
    assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM animal_case WHERE id=?",String.class,c));
    assertEquals(1,count("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='COMPLETE' AND from_state='ACTIVE' AND to_state='COMPLETED' AND actor_user_id=? AND reason='fundraising finished'",id,ADMIN));

    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/complete",id).header("X-User-Id",ADMIN)
        .contentType("application/json").content("{\"reason\":\"again\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPAIGN_STATE_CONFLICT"));
    assertEquals(completed,jdbc.queryForObject("SELECT completed_at FROM support_campaign WHERE id=?",Timestamp.class,id));
    assertEquals(1,count("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='COMPLETE'",id));

    mvc.perform(post("/api/v1/cases/{c}/support/contributions",c).header("X-User-Id",OTHER)
        .header("Idempotency-Key","after-complete").contentType("application/json").content("{\"amount\":1.00}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_ACCEPTING_SUPPORT"));
    mvc.perform(post("/api/v1/admin/support-contributions/{id}/void",contribution).header("X-User-Id",ADMIN)
        .contentType("application/json").content("{\"reason\":\"correction\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
    mvc.perform(post("/api/v1/cases/{c}/support/expenses/media/upload-url",c).header("X-User-Id",OWNER)
        .contentType("application/json").content("{\"fileName\":\"x.png\",\"contentType\":\"image/png\",\"sizeBytes\":4}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_ACCEPTING_EXPENSE"));
    mvc.perform(post("/api/v1/cases/{c}/support/expenses",c).header("X-User-Id",OWNER).header("Idempotency-Key","late-expense")
        .contentType("application/json").content("{\"item\":\"x\",\"description\":\"x\",\"amount\":1.00,\"occurredAt\":\"2026-10-11T00:00:00Z\",\"media\":[{\"temporaryObjectKey\":\"incident-input/"+OWNER+"/support-expenses/x.png\",\"contentType\":\"image/png\",\"sizeBytes\":4,\"sortOrder\":0}]}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_ACCEPTING_EXPENSE"));
    mvc.perform(post("/api/v1/admin/support-expenses/{id}/publish",submitted).header("X-User-Id",ADMIN))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
    mvc.perform(post("/api/v1/admin/support-expenses/{id}/void",published).header("X-User-Id",ADMIN)
        .contentType("application/json").content("{\"reason\":\"correction\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
    mvc.perform(get("/api/v1/cases/{c}/support",c)).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.completedAt").isNotEmpty());
  }

  @Test void publicReadsSelectLatestPublicHistoryInsteadOfLaterPrivateCampaign() throws Exception {
    String c=seed("public-history","ACTIVE","CLAIMED");
    Timestamp first=Timestamp.from(Instant.now().minusSeconds(2));
    Timestamp second=Timestamp.from(Instant.now());
    String historical="historical-settled",privateId="new-private";
    jdbc.update("INSERT INTO support_campaign(id,case_id,status,target_amount,currency,purpose,application_note,requested_by_user_id,published_at,completed_at,created_at,updated_at) VALUES (?,?,'SETTLED',100,'CNY','history','private',?,?,?,?,?)",
        historical,c,OWNER,first,first,first,first);
    jdbc.update("INSERT INTO support_campaign(id,case_id,status,target_amount,currency,purpose,application_note,requested_by_user_id,created_at,updated_at) VALUES (?,?,'DRAFT',100,'CNY','new private','private',?,?,?)",
        privateId,c,OWNER,second,second);
    insertExpense(historical,"historical-expense","PUBLISHED",first);
    mvc.perform(get("/api/v1/cases/{c}/support",c)).andExpect(status().isOk())
        .andExpect(jsonPath("$.campaignId").value(historical)).andExpect(jsonPath("$.status").value("SETTLED"));
    mvc.perform(get("/api/v1/cases/{c}/support/expenses",c)).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].expenseId").value("historical-expense"));
  }

  @Test void manualCompletionAndContributionRaceHasOnlyLegalOutcomes() throws Exception {
    String c=seed("manual-race","ACTIVE","CLAIMED");
    String id=create(c,OWNER,null);
    submit(c,OWNER);
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/approve",id).header("X-User-Id",ADMIN)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/admin/support-campaigns/{id}/publish",id).header("X-User-Id",ADMIN)).andExpect(status().isOk());
    try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
      CountDownLatch go=new CountDownLatch(1);
      Callable<Integer> contribution=()->{go.await();return mvc.perform(post("/api/v1/cases/{c}/support/contributions",c).header("X-User-Id",OTHER)
          .header("Idempotency-Key","manual-race").contentType("application/json").content("{\"amount\":1.00}"))
          .andReturn().getResponse().getStatus();};
      Callable<Integer> complete=()->{go.await();return mvc.perform(post("/api/v1/admin/support-campaigns/{id}/complete",id).header("X-User-Id",ADMIN)
          .contentType("application/json").content("{\"reason\":\"race complete\"}"))
          .andReturn().getResponse().getStatus();};
      Future<Integer> left=pool.submit(contribution),right=pool.submit(complete);go.countDown();
      int contributionStatus=left.get();
      assertEquals(200,right.get());
      assertTrue(contributionStatus==201||contributionStatus==409);
      assertEquals(contributionStatus==201?1:0,count("SELECT COUNT(*) FROM support_contribution WHERE campaign_id=?",id));
    }
    assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM support_campaign WHERE id=?",String.class,id));
    assertEquals(1,count("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='COMPLETE'",id));
  }

  private String seed(String suffix,String status,String claim) {
    String event="event-"+suffix, draft="draft-"+suffix, c="case-"+suffix;
    Timestamp t=Timestamp.from(Instant.now());
    jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",draft,REPORTER,CAMPUS,"draft",t,"public",t,t);
    jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",event,draft,REPORTER,CAMPUS,"event",t,t,"public","VERIFIED",t,t);
    jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,owner_user_id,status,claim_status,claimed_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        c,event,CAMPUS,"CLAIMED".equals(claim)?OWNER:null,status,claim,"CLAIMED".equals(claim)?t:null,t,t);
    return c;
  }
  private String evidence(String c,String suffix) {
    String id="evidence-"+suffix;
    String event=jdbc.queryForObject("SELECT event_id FROM animal_case WHERE id=?",String.class,c);
    Timestamp t=Timestamp.from(Instant.now());
    jdbc.update("INSERT INTO evidence(id,event_id,submitter_user_id,description,occurred_at,created_at) VALUES (?,?,?,?,?,?)",id,event,REPORTER,"evidence",t,t);
    return id;
  }
  private void participant(String c,String user) {
    Timestamp t=Timestamp.from(Instant.now());
    jdbc.update("INSERT INTO case_participant(id,case_id,user_id,status,invited_by_user_id,invited_at,joined_at,created_at,updated_at) VALUES (?,?,?,'ACTIVE',?,?,?,?,?)",
        UUID.randomUUID().toString(),c,user,OWNER,t,t,t,t);
  }
  private static String draft(String amount,String evidence) {
    return "{\"targetAmount\":"+amount+",\"purpose\":\"support purpose\",\"applicationNote\":\"private note\",\"caseEvidenceId\":"+(evidence==null?"null":"\""+evidence+"\"")+"}";
  }
  private String create(String c,String user,String evidence) {
    try {
      ResultActions result=mvc.perform(post("/api/v1/cases/{c}/support",c).header("X-User-Id",user).contentType("application/json").content(draft("10.00",evidence)));
      var response=result.andReturn();
      if(response.getResponse().getStatus()!=200) throw new AssertionError("create status="+response.getResponse().getStatus()+" cause="+response.getResolvedException());
      return json.readTree(result.andExpect(jsonPath("$.status").value("DRAFT")).andReturn().getResponse().getContentAsString()).required("campaignId").asText();
    } catch (Exception ex) { throw new AssertionError(ex); }
  }
  private void submit(String c,String user) throws Exception {
    mvc.perform(post("/api/v1/cases/{c}/support/submit",c).header("X-User-Id",user)).andExpect(status().isOk());
  }
  private void insertExpense(String campaignId,String id,String status,Timestamp at) {
    String reviewer="SUBMITTED".equals(status)?null:ADMIN;
    Timestamp reviewed="SUBMITTED".equals(status)?null:at;
    jdbc.update("INSERT INTO support_expense(id,campaign_id,recorded_by_user_id,item,description,amount,status,idempotency_key,request_hash,occurred_at,created_at,reviewed_by_user_id,reviewed_at) VALUES (?,?,?,?,?,1.00,?,?,?,?,?,?,?)",
        id,campaignId,OWNER,"item","description",status,"key-"+id,"0".repeat(64),at,at,reviewer,reviewed);
  }
  private int count(String sql,Object... args) { return jdbc.queryForObject(sql,Integer.class,args); }

  private static class Stub {
    final HttpServer server;
    final Map<String,String> members=new ConcurrentHashMap<>();
    Stub() {
      try { server=HttpServer.create(new InetSocketAddress(0),0); server.createContext("/",this::handle); server.start(); }
      catch (IOException ex) { throw new IllegalStateException(ex); }
    }
    String url() { return "http://localhost:"+server.getAddress().getPort(); }
    void handle(HttpExchange e) throws IOException {
      String path=e.getRequestURI().getPath();
      String header=e.getRequestHeaders().getFirst("X-User-Id");
      String[] pieces=path.split("/");
      String subject=pieces.length>4?pieces[4]:header;
      String state=members.get(subject);
      String body=path.contains("volunteer-membership")
          ? "{\"exists\":"+(state!=null)+",\"active\":"+"ACTIVE".equals(state)+",\"status\":\""+state+"\"}"
          : "{\"id\":\""+header+"\",\"accountStatus\":\"ACTIVE\",\"systemRole\":\""+(ADMIN.equals(header)?"GOVERNANCE_ADMIN":"USER")+"\"}";
      byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
      e.getResponseHeaders().set("Content-Type","application/json");
      e.sendResponseHeaders(200,bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    }
  }
}
