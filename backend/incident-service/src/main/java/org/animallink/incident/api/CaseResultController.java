package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/v1/cases/{caseId}")
public class CaseResultController {
  private final JdbcTemplate jdbc; private final RestClient identity; private final HttpServletRequest request; private final ObjectMapper json;
  public CaseResultController(JdbcTemplate jdbc,@Qualifier("identityRestClient") RestClient identity,HttpServletRequest request,ObjectMapper json){this.jdbc=jdbc;this.identity=identity;this.request=request;this.json=json;}

  @PostMapping("/result") @Transactional
  public ResultResponse submit(@PathVariable("caseId") String caseId,@Valid @RequestBody ResultRequest body) {
    String caller=user(); Map<String,Object> c=oneForUpdate(caseId); active(c);
    if(!caller.equals(s(c,"owner_user_id"))&&!activeParticipant(caseId,caller)) throw new EventController.Forbidden("CASE_RESULT_ACCESS_REQUIRED");
    volunteer(caller,s(c,"campus_id")); Instant now=Instant.now(); String next=statusFor(body.outcome());
    String actionId=UUID.randomUUID().toString();
    int updated=jdbc.update("UPDATE animal_case SET status=?,result_code=?,result_summary=?,result_submitted_by_user_id=?,result_submitted_at=?,closed_at=?,version=version+1,updated_at=? WHERE id=? AND status='ACTIVE' AND claim_status='CLAIMED'",
      next,body.outcome(),body.summary(),caller,Timestamp.from(now),terminal(body.outcome())?Timestamp.from(now):null,Timestamp.from(now),caseId);
    if(updated!=1) throw new EventController.Conflict("CASE_RESULT_STATE_CONFLICT");
    jdbc.update("INSERT INTO case_action(id,case_id,actor_user_id,description,occurred_at,created_at,result_code) VALUES(?,?,?,?,?,?,?)",actionId,caseId,caller,body.summary(),Timestamp.from(now),Timestamp.from(now),body.outcome());
    if(finalized(body.outcome()) && s(c,"animal_id")!=null) outbox(c, body, caller, now);
    return result(one(caseId),actionId);
  }

  @PostMapping("/close") @Transactional
  public CloseResponse close(@PathVariable("caseId") String caseId) {
    String caller=user(); Map<String,Object> c=oneForUpdate(caseId);
    if(!caller.equals(s(c,"owner_user_id"))) throw new EventController.Forbidden("CASE_CLOSE_OWNER_REQUIRED");
    volunteer(caller,s(c,"campus_id"));
    if(!"RESOLVED".equals(s(c,"status"))||!"CLAIMED".equals(s(c,"claim_status"))) throw new EventController.Conflict("CASE_NOT_RESOLVED");
    Instant now=Instant.now();
    if(jdbc.update("UPDATE animal_case SET status='CLOSED',closed_at=?,version=version+1,updated_at=? WHERE id=? AND status='RESOLVED' AND claim_status='CLAIMED'",Timestamp.from(now),Timestamp.from(now),caseId)!=1) throw new EventController.Conflict("CASE_NOT_RESOLVED");
    return close(one(caseId));
  }

  private void active(Map<String,Object> c){if(!"ACTIVE".equals(s(c,"status"))||!"CLAIMED".equals(s(c,"claim_status")))throw new EventController.Conflict("CASE_RESULT_STATE_CONFLICT");}
  private boolean activeParticipant(String caseId,String user){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM case_participant WHERE case_id=? AND user_id=? AND status='ACTIVE'",Integer.class,caseId,user);return count!=null&&count>0;}
  private void volunteer(String user,String campus){try{Map<?,?> f=identity.get().uri("/internal/v1/users/{u}/campuses/{c}/volunteer-membership",user,campus).header("X-Internal-Service","incident-service").retrieve().body(Map.class);if(f==null||!Boolean.TRUE.equals(f.get("exists"))||!Boolean.TRUE.equals(f.get("active"))||!"ACTIVE".equals(f.get("status")))throw new EventController.Forbidden("ACTIVE_VOLUNTEER_REQUIRED");}catch(EventController.Forbidden e){throw e;}catch(Exception e){throw new EventController.Dependency("IDENTITY_SERVICE_UNAVAILABLE");}}
  private String user(){String u=request.getHeader("X-User-Id");if(u==null||u.isBlank())throw new EventController.Unauthorized("需要登录");return u;}
  private Map<String,Object> oneForUpdate(String id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM animal_case WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new EventController.NotFound("CASE_NOT_FOUND");return rows.getFirst();}
  private Map<String,Object> one(String id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM animal_case WHERE id=?",id);if(rows.isEmpty())throw new EventController.NotFound("CASE_NOT_FOUND");return rows.getFirst();}
  private ResultResponse result(Map<String,Object> c,String actionId){return new ResultResponse(s(c,"id"),s(c,"result_code"),s(c,"status"),s(c,"result_summary"),s(c,"result_submitted_by_user_id"),instant(c,"result_submitted_at"),actionId);}
  private CloseResponse close(Map<String,Object> c){return new CloseResponse(s(c,"id"),s(c,"status"),s(c,"result_code"),s(c,"result_summary"),s(c,"result_submitted_by_user_id"),instant(c,"result_submitted_at"),instant(c,"closed_at"));}
  private static String statusFor(String outcome){return switch(outcome){case "CONTINUE_OBSERVATION"->"ACTIVE";case "RESOLVED"->"RESOLVED";case "UNRESOLVED"->"CLOSED_UNRESOLVED";case "CANCELLED"->"CANCELLED";default->throw new IllegalArgumentException("outcome");};}
  private static boolean terminal(String outcome){return !"CONTINUE_OBSERVATION".equals(outcome)&&!"RESOLVED".equals(outcome);}
  private static boolean finalized(String outcome){return !"CONTINUE_OBSERVATION".equals(outcome);}
  private void outbox(Map<String,Object> c,ResultRequest body,String caller,Instant now){try{String id=UUID.randomUUID().toString();Map<String,Object> payload=new LinkedHashMap<>();payload.put("eventId",id);payload.put("eventType","CASE_RESULT_FINALIZED");payload.put("eventVersion",1);payload.put("caseId",s(c,"id"));payload.put("incidentEventId",s(c,"event_id"));payload.put("animalId",s(c,"animal_id"));payload.put("campusId",s(c,"campus_id"));payload.put("outcome",body.outcome());payload.put("summary",body.summary());payload.put("submittedByUserId",caller);payload.put("occurredAt",now.toString());jdbc.update("INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,available_at,created_at,updated_at) VALUES(?,?,?,? ,CAST(? AS JSON),'PENDING',?,?,?)",id,"CASE",s(c,"id"),"CASE_RESULT_FINALIZED",json.writeValueAsString(payload),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));}catch(Exception e){throw new IllegalStateException("outbox write failed",e);}}
  private static String s(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:v.toString();}
  private static Instant instant(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:((Timestamp)v).toInstant();}
  public record ResultRequest(@NotBlank @Pattern(regexp="CONTINUE_OBSERVATION|RESOLVED|UNRESOLVED|CANCELLED") String outcome,@NotBlank @Size(max=2000) String summary){}
  public record ResultResponse(String caseId,String outcome,String caseStatus,String resultSummary,String submittedByUserId,Instant submittedAt,String actionId){}
  public record CloseResponse(String caseId,String caseStatus,String resultCode,String resultSummary,String submittedByUserId,Instant submittedAt,Instant closedAt){}
}
