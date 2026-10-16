package org.animallink.incident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class CaseResultIntegrationTest {
  static final String CAMPUS="30000000-0000-0000-0000-000000000001",OWNER="30000000-0000-0000-0000-000000000011",TARGET="30000000-0000-0000-0000-000000000012",OTHER="30000000-0000-0000-0000-000000000013",ADMIN="30000000-0000-0000-0000-000000000015",REPORTER="30000000-0000-0000-0000-000000000014";
  static final Stub STUB=new Stub();
  @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.41").withDatabaseName("case_result_test").withUsername("test").withPassword("test");
  @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.cloud.nacos.discovery.enabled",()->false);r.add("spring.cloud.nacos.config.enabled",()->false);r.add("animallink.identity.base-url",STUB::url);r.add("animallink.animal.base-url",STUB::url);r.add("animallink.intelligence.base-url",STUB::url);r.add("animallink.minio.endpoint",()->"http://localhost:9000");r.add("animallink.minio.access-key",()->"a");r.add("animallink.minio.secret-key",()->"b");r.add("animallink.minio.bucket",()->"b");}
  @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
  @BeforeEach void clean(){STUB.memberships.clear();STUB.accounts.clear();STUB.memberships.put(OWNER,"ACTIVE");STUB.memberships.put(TARGET,"ACTIVE");STUB.memberships.put(OTHER,"ACTIVE");STUB.unavailable=false;jdbc.update("DELETE FROM support_expense_media");jdbc.update("DELETE FROM support_expense");jdbc.update("DELETE FROM support_contribution");jdbc.update("DELETE FROM support_audit_log");jdbc.update("DELETE FROM support_campaign");jdbc.update("DELETE FROM outbox_event");jdbc.update("DELETE FROM case_action_media");jdbc.update("DELETE FROM case_action");jdbc.update("DELETE FROM case_participant");jdbc.update("DELETE FROM animal_case");jdbc.update("DELETE FROM evidence");jdbc.update("DELETE FROM event");jdbc.update("DELETE FROM event_draft");}
  @AfterAll static void stop(){STUB.stop();}
  @Test void ownerContinueObservationAppendsHistoryAndKeepsCaseActive() throws Exception {String c=seed("continue");result(c,OWNER,"CONTINUE_OBSERVATION","watch").andExpect(status().isOk()).andExpect(jsonPath("$.caseStatus").value("ACTIVE")).andExpect(jsonPath("$.outcome").value("CONTINUE_OBSERVATION"));assertEquals("CONTINUE_OBSERVATION",value(c,"result_code"));assertEquals(1,count("case_action"));assertEquals("CONTINUE_OBSERVATION",jdbc.queryForObject("SELECT result_code FROM case_action WHERE case_id=?",String.class,c));}
  @Test void collaboratorResolvedAndOwnerClosePreservesResult() throws Exception {String c=seed("resolved");participant(c,TARGET,"ACTIVE");result(c,TARGET,"RESOLVED","done").andExpect(status().isOk()).andExpect(jsonPath("$.caseStatus").value("RESOLVED"));mvc.perform(post("/api/v1/cases/{id}/close",c).header("X-User-Id",OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.caseStatus").value("CLOSED")).andExpect(jsonPath("$.resultCode").value("RESOLVED")).andExpect(jsonPath("$.closedAt").isNotEmpty());}
  @Test void terminalOutcomesAndAccessAreEnforced() throws Exception {String unresolved=seed("unresolved");result(unresolved,OWNER,"UNRESOLVED","no").andExpect(status().isOk()).andExpect(jsonPath("$.caseStatus").value("CLOSED_UNRESOLVED"));String cancelled=seed("cancelled");participant(cancelled,TARGET,"ACTIVE");result(cancelled,TARGET,"CANCELLED","cancel").andExpect(status().isOk()).andExpect(jsonPath("$.caseStatus").value("CANCELLED"));String denied=seed("denied");result(denied,OTHER,"RESOLVED","x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CASE_RESULT_ACCESS_REQUIRED"));}
  @Test void inactiveIdentityAndTerminalCaseHaveNoMutation() throws Exception {String c=seed("paused");STUB.memberships.put(OWNER,"PAUSED");result(c,OWNER,"RESOLVED","x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_VOLUNTEER_REQUIRED"));assertEquals(0,count("case_action"));STUB.memberships.put(OWNER,"ACTIVE");result(c,OWNER,"RESOLVED","x").andExpect(status().isOk());result(c,OWNER,"CONTINUE_OBSERVATION","late").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_RESULT_STATE_CONFLICT"));assertEquals(1,count("case_action"));}
  @Test void nonActiveAccountCannotSubmitResultOrCloseDespiteActiveVolunteerMembership() throws Exception {for(String state:java.util.List.of("SUSPENDED","DISABLED")){String resultCase=seed("account-result-"+state);STUB.accounts.put(OWNER,state);result(resultCase,OWNER,"RESOLVED","x").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));assertEquals("ACTIVE",value(resultCase,"status"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM case_action WHERE case_id=?",Integer.class,resultCase));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,resultCase));STUB.accounts.remove(OWNER);String closeCase=seed("account-close-"+state);jdbc.update("UPDATE animal_case SET status='RESOLVED',result_code='RESOLVED' WHERE id=?",closeCase);STUB.accounts.put(OWNER,state);mvc.perform(post("/api/v1/cases/{id}/close",closeCase).header("X-User-Id",OWNER)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));assertEquals("RESOLVED",value(closeCase,"status"));STUB.accounts.remove(OWNER);}}
  @Test void participantStatesValidationIdentityAndIsolationAreEnforced() throws Exception {for(String state:java.util.List.of("INVITED","DECLINED","LEFT","REMOVED")){String c=seed("terminal-"+state);participant(c,TARGET,state);result(c,TARGET,"RESOLVED","x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CASE_RESULT_ACCESS_REQUIRED"));}String revoked=seed("revoked");participant(revoked,TARGET,"ACTIVE");STUB.memberships.put(TARGET,"REVOKED");result(revoked,TARGET,"RESOLVED","x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_VOLUNTEER_REQUIRED"));assertEquals(0,count("case_action"));STUB.memberships.put(TARGET,"ACTIVE");String down=seed("down");STUB.unavailable=true;try{result(down,OWNER,"RESOLVED","x").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDENTITY_SERVICE_UNAVAILABLE"));}finally{STUB.unavailable=false;}result("missing",OWNER,"RESOLVED","x").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));String validation=seed("validation");mvc.perform(post("/api/v1/cases/{id}/result",validation).header("X-User-Id",OWNER).contentType("application/json").content("{\"outcome\":\"BAD\",\"summary\":\" \"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));}
  @Test void continueThenResolvedPreservesTwoActionsAndTerminalBlocksActions() throws Exception {String c=seed("history");result(c,OWNER,"CONTINUE_OBSERVATION","observe").andExpect(status().isOk());mvc.perform(post("/api/v1/cases/{id}/actions",c).header("X-User-Id",OWNER).contentType("application/json").content("{\"description\":\"ordinary\",\"occurredAt\":\"2026-10-03T10:00:00Z\"}")).andExpect(status().isOk());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM case_action WHERE case_id=? AND result_code IS NULL",Integer.class,c));result(c,OWNER,"RESOLVED","resolved").andExpect(status().isOk());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM case_action WHERE case_id=? AND result_code IS NOT NULL",Integer.class,c));mvc.perform(post("/api/v1/cases/{id}/actions",c).header("X-User-Id",OWNER).contentType("application/json").content("{\"description\":\"late\",\"occurredAt\":\"2026-10-03T10:00:00Z\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_NOT_ACTIVE"));assertEquals("VERIFIED",jdbc.queryForObject("SELECT status FROM event WHERE id=?",String.class,"e-history"));assertEquals(0,count("evidence"));}
  @Test void closeAuthorizationAndStateRulesAreEnforced() throws Exception {String c=seed("close-auth");result(c,OWNER,"RESOLVED","done").andExpect(status().isOk());participant(c,TARGET,"ACTIVE");mvc.perform(post("/api/v1/cases/{id}/close",c).header("X-User-Id",TARGET)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CASE_CLOSE_OWNER_REQUIRED"));mvc.perform(post("/api/v1/cases/{id}/close",c).header("X-User-Id",ADMIN)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CASE_CLOSE_OWNER_REQUIRED"));STUB.memberships.put(OWNER,"PAUSED");mvc.perform(post("/api/v1/cases/{id}/close",c).header("X-User-Id",OWNER)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTIVE_VOLUNTEER_REQUIRED"));STUB.memberships.put(OWNER,"ACTIVE");mvc.perform(post("/api/v1/cases/{id}/close",c).header("X-User-Id",OWNER)).andExpect(status().isOk());String active=seed("active-close");mvc.perform(post("/api/v1/cases/{id}/close",active).header("X-User-Id",OWNER)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_NOT_RESOLVED"));}
  @Test void finalResultsCreateOneOutboxButContinueNullAnimalAndCloseDoNot() throws Exception {for(String outcome:java.util.List.of("RESOLVED","UNRESOLVED","CANCELLED")){String c=seed("outbox-"+outcome);jdbc.update("UPDATE animal_case SET animal_id=? WHERE id=?","animal-1",c);result(c,OWNER,outcome,"summary-"+outcome).andExpect(status().isOk());Map<String,Object> row=jdbc.queryForList("SELECT * FROM outbox_event WHERE aggregate_id=?",c).getFirst();var payload=json.readTree(row.get("payload_json").toString());assertEquals("PENDING",row.get("status"));assertEquals(row.get("id"),payload.get("eventId").asText());assertEquals("CASE_RESULT_FINALIZED",payload.get("eventType").asText());assertEquals(1,payload.get("eventVersion").asInt());assertEquals(c,payload.get("caseId").asText());assertEquals("e-outbox-"+outcome,payload.get("incidentEventId").asText());assertEquals("animal-1",payload.get("animalId").asText());assertEquals(CAMPUS,payload.get("campusId").asText());assertEquals(outcome,payload.get("outcome").asText());assertEquals(OWNER,payload.get("submittedByUserId").asText());if("RESOLVED".equals(outcome)){mvc.perform(post("/api/v1/cases/{id}/close",c).header("X-User-Id",OWNER)).andExpect(status().isOk());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,c));}}String cont=seed("no-outbox-continue");jdbc.update("UPDATE animal_case SET animal_id='animal-1' WHERE id=?",cont);result(cont,OWNER,"CONTINUE_OBSERVATION","watch").andExpect(status().isOk());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,cont));String unknown=seed("no-animal");result(unknown,OWNER,"CANCELLED","done").andExpect(status().isOk());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,unknown));assertEquals("VERIFIED",jdbc.queryForObject("SELECT status FROM event WHERE id=?",String.class,"e-no-animal"));}
  @Test void terminalCaseOutcomesCancelPrePublicCampaignsAndContinueIsNoOp() throws Exception {
    for(String outcome:java.util.List.of("RESOLVED","UNRESOLVED","CANCELLED")) {
      String c=seed("terminal-support-"+outcome),campaign=campaign(c,"PENDING_REVIEW");
      result(c,OWNER,outcome,"done").andExpect(status().isOk());
      assertEquals("CANCELLED",campaignStatus(campaign));
      String resulting="UNRESOLVED".equals(outcome)?"CLOSED_UNRESOLVED":outcome;
      assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='CASE_TERMINAL_CANCEL' AND actor_user_id=? AND reason=?",Integer.class,campaign,OWNER,"CASE_STATUS_"+resulting));
    }
    String observed=seed("terminal-support-continue"),campaign=campaign(observed,"ACTIVE");
    result(observed,OWNER,"CONTINUE_OBSERVATION","watch").andExpect(status().isOk());
    assertEquals("ACTIVE",campaignStatus(campaign));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=?",Integer.class,campaign));
  }

  @Test void terminalCampaignMatrixUsesExistenceOfAnyFinancialHistory() throws Exception {
    for(String state:java.util.List.of("DRAFT","PENDING_REVIEW","APPROVED")) {
      String c=seed("prepublic-"+state),id=campaign(c,state);
      result(c,OWNER,"RESOLVED","done").andExpect(status().isOk());
      assertEquals("CANCELLED",campaignStatus(id));
      assertEquals(null,jdbc.queryForObject("SELECT completed_at FROM support_campaign WHERE id=?",Timestamp.class,id));
    }
    String emptyCase=seed("active-empty"),empty=campaign(emptyCase,"ACTIVE");
    result(emptyCase,OWNER,"RESOLVED","done").andExpect(status().isOk());
    assertEquals("CANCELLED",campaignStatus(empty));

    for(String history:java.util.List.of("RECORDED_CONTRIBUTION","VOIDED_CONTRIBUTION","SUBMITTED_EXPENSE","REJECTED_EXPENSE","VOIDED_EXPENSE")) {
      String c=seed("history-"+history),id=campaign(c,"ACTIVE");
      if(history.endsWith("CONTRIBUTION")) contribution(id,history.startsWith("VOIDED")?"VOIDED":"RECORDED");
      else expense(id,history.substring(0,history.indexOf('_')));
      result(c,OWNER,"RESOLVED","done").andExpect(status().isOk());
      assertEquals("COMPLETED",campaignStatus(id));
      org.junit.jupiter.api.Assertions.assertNotNull(jdbc.queryForObject("SELECT completed_at FROM support_campaign WHERE id=?",Timestamp.class,id));
      assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='CASE_TERMINAL_COMPLETE'",Integer.class,id));
    }

    for(String state:java.util.List.of("COMPLETED","SETTLED","REJECTED","CANCELLED")) {
      String c=seed("noop-"+state),id=campaign(c,state);
      Integer version=jdbc.queryForObject("SELECT version FROM support_campaign WHERE id=?",Integer.class,id);
      Timestamp updated=jdbc.queryForObject("SELECT updated_at FROM support_campaign WHERE id=?",Timestamp.class,id);
      result(c,OWNER,"RESOLVED","done").andExpect(status().isOk());
      assertEquals(state,campaignStatus(id));
      assertEquals(version,jdbc.queryForObject("SELECT version FROM support_campaign WHERE id=?",Integer.class,id));
      assertEquals(updated,jdbc.queryForObject("SELECT updated_at FROM support_campaign WHERE id=?",Timestamp.class,id));
      assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=?",Integer.class,id));
    }
  }

  @Test void closeRunsIdempotentBackstopForLegacyResolvedCase() throws Exception {
    String c=seed("legacy-close"),id=campaign(c,"ACTIVE");
    contribution(id,"VOIDED");
    jdbc.update("UPDATE animal_case SET status='RESOLVED',result_code='RESOLVED',result_summary='legacy',result_submitted_by_user_id=?,result_submitted_at=CURRENT_TIMESTAMP(6) WHERE id=?",OWNER,c);
    mvc.perform(post("/api/v1/cases/{id}/close",c).header("X-User-Id",OWNER)).andExpect(status().isOk());
    assertEquals("COMPLETED",campaignStatus(id));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=? AND action='CASE_TERMINAL_COMPLETE' AND reason='CASE_STATUS_CLOSED'",Integer.class,id));

    String normal=seed("normal-close"),normalId=campaign(normal,"ACTIVE");
    contribution(normalId,"RECORDED");
    result(normal,OWNER,"RESOLVED","done").andExpect(status().isOk());
    Timestamp completed=jdbc.queryForObject("SELECT completed_at FROM support_campaign WHERE id=?",Timestamp.class,normalId);
    mvc.perform(post("/api/v1/cases/{id}/close",normal).header("X-User-Id",OWNER)).andExpect(status().isOk());
    assertEquals(completed,jdbc.queryForObject("SELECT completed_at FROM support_campaign WHERE id=?",Timestamp.class,normalId));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM support_audit_log WHERE campaign_id=?",Integer.class,normalId));
  }

  @Test void supportAuditFailureRollsBackCaseActionCampaignAndOutbox() throws Exception {
    String c=seed("atomic"),id=campaign(c,"ACTIVE");
    jdbc.update("UPDATE animal_case SET animal_id='animal-atomic' WHERE id=?",c);
    jdbc.execute("ALTER TABLE support_audit_log ADD CONSTRAINT chk_test_fail_terminal_audit CHECK (action NOT IN ('CASE_TERMINAL_CANCEL','CASE_TERMINAL_COMPLETE'))");
    try { result(c,OWNER,"RESOLVED","done").andExpect(status().isInternalServerError()); }
    finally { jdbc.execute("ALTER TABLE support_audit_log DROP CHECK chk_test_fail_terminal_audit"); }
    assertEquals("ACTIVE",value(c,"status"));
    assertEquals("ACTIVE",campaignStatus(id));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM case_action WHERE case_id=?",Integer.class,c));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,c));
  }

  @Test void contributionAndTerminalizationRaceHasOnlyLegalOutcomes() throws Exception {
    String c=seed("financial-race"),id=campaign(c,"ACTIVE");
    try(java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.CountDownLatch go=new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Integer> contribute=()->{go.await();return mvc.perform(post("/api/v1/cases/{id}/support/contributions",c).header("X-User-Id",OTHER).header("Idempotency-Key","race").contentType("application/json").content("{\"amount\":1.00}")).andReturn().getResponse().getStatus();};
      java.util.concurrent.Callable<Integer> terminal=()->{go.await();return result(c,OWNER,"RESOLVED","done").andReturn().getResponse().getStatus();};
      var left=pool.submit(contribute);var right=pool.submit(terminal);go.countDown();
      int contributionStatus=left.get(),terminalStatus=right.get();
      assertEquals(200,terminalStatus);
      int rows=jdbc.queryForObject("SELECT COUNT(*) FROM support_contribution WHERE campaign_id=?",Integer.class,id);
      if(contributionStatus==201){assertEquals(1,rows);assertEquals("COMPLETED",campaignStatus(id));}
      else {assertEquals(409,contributionStatus);assertEquals(0,rows);assertEquals("CANCELLED",campaignStatus(id));}
    }
  }

  private org.springframework.test.web.servlet.ResultActions result(String c,String u,String outcome,String summary)throws Exception{return mvc.perform(post("/api/v1/cases/{id}/result",c).header("X-User-Id",u).contentType("application/json").content("{\"outcome\":\""+outcome+"\",\"summary\":\""+summary+"\"}"));}
  private String seed(String x){Timestamp n=Timestamp.from(Instant.now());String e="e-"+x,d="d-"+x,c="c-"+x;jdbc.update("INSERT INTO event_draft(id,user_id,campus_id,description,occurred_at,public_location_description,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",d,REPORTER,CAMPUS,"d",n,"p",n,n);jdbc.update("INSERT INTO event(id,source_draft_id,reporter_user_id,campus_id,description,occurred_at,reported_at,public_location_description,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",e,d,REPORTER,CAMPUS,"e",n,n,"p","VERIFIED",n,n);jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,owner_user_id,status,claim_status,claimed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",c,e,CAMPUS,OWNER,"ACTIVE","CLAIMED",n,n,n);return c;}
  private String campaign(String c,String status){String id=UUID.randomUUID().toString();Timestamp n=Timestamp.from(Instant.now());Timestamp published=java.util.List.of("ACTIVE","COMPLETED","SETTLED").contains(status)?n:null;Timestamp completed=java.util.List.of("COMPLETED","SETTLED").contains(status)?n:null;jdbc.update("INSERT INTO support_campaign(id,case_id,status,target_amount,currency,purpose,application_note,requested_by_user_id,published_at,completed_at,created_at,updated_at) VALUES (?,?,?,100,'CNY','purpose','note',?,?,?,?,?)",id,c,status,OWNER,published,completed,n,n);return id;}
  private void contribution(String campaign,String status){Timestamp n=Timestamp.from(Instant.now());boolean voided="VOIDED".equals(status);jdbc.update("INSERT INTO support_contribution(id,campaign_id,contributor_user_id,amount,status,idempotency_key,request_hash,recorded_at,voided_by_user_id,void_reason,voided_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),campaign,OTHER,1.00,status,UUID.randomUUID().toString(),"0".repeat(64),n,voided?ADMIN:null,voided?"voided":null,voided?n:null);}
  private void expense(String campaign,String status){Timestamp n=Timestamp.from(Instant.now());boolean reviewed=!"SUBMITTED".equals(status);boolean voided="VOIDED".equals(status);String reviewReason="REJECTED".equals(status)?"rejected":null;jdbc.update("INSERT INTO support_expense(id,campaign_id,recorded_by_user_id,item,description,amount,status,idempotency_key,request_hash,occurred_at,created_at,reviewed_by_user_id,review_reason,reviewed_at,voided_by_user_id,void_reason,voided_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),campaign,OWNER,"item","description",1.00,status,UUID.randomUUID().toString(),"0".repeat(64),n,n,reviewed?ADMIN:null,reviewReason,reviewed?n:null,voided?ADMIN:null,voided?"voided":null,voided?n:null);}
  private String campaignStatus(String id){return jdbc.queryForObject("SELECT status FROM support_campaign WHERE id=?",String.class,id);}
  private void participant(String c,String u,String status){Timestamp n=Timestamp.from(Instant.now());if("ACTIVE".equals(status))jdbc.update("INSERT INTO case_participant(id,case_id,user_id,status,invited_by_user_id,invited_at,joined_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),c,u,status,OWNER,n,n,n,n);else if("INVITED".equals(status))jdbc.update("INSERT INTO case_participant(id,case_id,user_id,status,invited_by_user_id,invited_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),c,u,status,OWNER,n,n,n);else jdbc.update("INSERT INTO case_participant(id,case_id,user_id,status,invited_by_user_id,invited_at,joined_at,ended_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),c,u,status,OWNER,n,n,n,n,n);}
  private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);} private String value(String c,String column){return jdbc.queryForObject("SELECT "+column+" FROM animal_case WHERE id=?",String.class,c);}
  static class Stub {final HttpServer server;final Map<String,String> memberships=new ConcurrentHashMap<>();final Map<String,String> accounts=new ConcurrentHashMap<>();volatile boolean unavailable;Stub(){try{server=HttpServer.create(new InetSocketAddress(0),0);server.createContext("/",this::handle);server.start();}catch(IOException e){throw new RuntimeException(e);}}String url(){return "http://localhost:"+server.getAddress().getPort();}void stop(){server.stop(0);}void handle(HttpExchange e)throws IOException{String path=e.getRequestURI().getPath(),header=e.getRequestHeaders().getFirst("X-User-Id");if(unavailable&&path.contains("volunteer-membership")){e.sendResponseHeaders(503,-1);e.close();return;}String[] parts=path.split("/");String subject=parts.length>4?parts[4]:header;String json=path.contains("volunteer-membership")?("ACTIVE".equals(memberships.get(subject))?"{\"exists\":true,\"active\":true,\"status\":\"ACTIVE\"}":"{\"exists\":true,\"active\":false,\"status\":\"PAUSED\"}"):"{\"id\":\""+header+"\",\"accountStatus\":\""+accounts.getOrDefault(header,"ACTIVE")+"\",\"systemRole\":\""+(ADMIN.equals(header)?"GOVERNANCE_ADMIN":"USER")+"\"}";byte[] body=json.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();}}
}
