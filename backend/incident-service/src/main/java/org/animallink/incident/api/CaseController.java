package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.sql.Timestamp; import java.time.Instant; import java.util.*;

@RestController @RequestMapping("/api/v1")
public class CaseController {
 private final JdbcTemplate jdbc; private final RestClient identity; private final HttpServletRequest request;
 public CaseController(JdbcTemplate jdbc,@Qualifier("identityRestClient") RestClient identity,HttpServletRequest request){this.jdbc=jdbc;this.identity=identity;this.request=request;}
 @PostMapping("/events/{eventId}/case/claim") @Transactional public CaseResponse claim(@PathVariable("eventId") String eventId){String user=user();Map<String,Object> e=event(eventId);if(!"VERIFIED".equals(s(e,"status")))throw new EventController.Conflict("EVENT_NOT_ELIGIBLE_FOR_CASE");volunteer(user,s(e,"campus_id"));Instant now=Instant.now();try{jdbc.update("INSERT INTO animal_case(id,event_id,campus_id,animal_id,owner_user_id,status,claim_status,created_at,updated_at) VALUES(?,?,?,?,?,'OPEN','WAITING_CLAIM',?,?)",UUID.randomUUID().toString(),eventId,s(e,"campus_id"),s(e,"animal_id"),null,Timestamp.from(now),Timestamp.from(now));}catch(DuplicateKeyException duplicate){if(!eventUniqueCollision(duplicate))throw duplicate;}Map<String,Object> c=caseForUpdate(eventId);int won=jdbc.update("UPDATE animal_case SET owner_user_id=?,status='ACTIVE',claim_status='CLAIMED',claimed_at=?,version=version+1,updated_at=? WHERE id=? AND claim_status='WAITING_CLAIM'",user,Timestamp.from(now),Timestamp.from(now),s(c,"id"));if(won==0){c=caseForUpdate(eventId);if(!user.equals(s(c,"owner_user_id")))throw new EventController.Conflict("Case 已被其他志愿者认领");}return response(caseForUpdate(eventId));}
 @GetMapping("/cases/{id}") public CaseResponse byId(@PathVariable("id") String id){return response(one("SELECT * FROM animal_case WHERE id=?",id));}
 @GetMapping("/events/{eventId}/case") public CaseResponse byEvent(@PathVariable("eventId") String id){return response(one("SELECT * FROM animal_case WHERE event_id=?",id));}
 private void volunteer(String user,String campus){try{Map<?,?> f=identity.get().uri("/internal/v1/users/{u}/campuses/{c}/volunteer-membership",user,campus).header("X-Internal-Service","incident-service").retrieve().body(Map.class);if(f==null||!Boolean.TRUE.equals(f.get("exists"))||!Boolean.TRUE.equals(f.get("active"))||!"ACTIVE".equals(f.get("status")))throw new EventController.Forbidden("ACTIVE_VOLUNTEER_REQUIRED");}catch(EventController.Forbidden x){throw x;}catch(Exception x){throw new EventController.Dependency("identity-service 暂不可用");}}
 private String user(){String u=request.getHeader("X-User-Id");if(u==null||u.isBlank())throw new EventController.Unauthorized("需要登录");return u;}
 private Map<String,Object> event(String id){return one("SELECT * FROM event WHERE id=?",id);}
 private Map<String,Object> caseForUpdate(String eventId){return one("SELECT * FROM animal_case WHERE event_id=? FOR UPDATE",eventId);}
 private boolean eventUniqueCollision(DuplicateKeyException e){String message=String.valueOf(e.getMostSpecificCause().getMessage());return message.contains("uk_case_event")||message.contains("animal_case.event_id");}
 private Map<String,Object> one(String sql,Object... a){List<Map<String,Object>> r=jdbc.queryForList(sql,a);if(r.isEmpty())throw new EventController.NotFound(sql.contains("animal_case")?"Case 不存在":"Event 不存在");return r.getFirst();}
 private static String s(Map<String,Object> r,String k){Object v=r.get(k);return v==null?null:v.toString();}
 private static Instant instant(Object v){return v==null?null:v instanceof Timestamp t?t.toInstant():(Instant)v;} private static CaseResponse response(Map<String,Object> r){return new CaseResponse(s(r,"id"),s(r,"event_id"),s(r,"campus_id"),s(r,"animal_id"),s(r,"status"),s(r,"claim_status"),s(r,"owner_user_id"),instant(r.get("claimed_at")),instant(r.get("created_at")),instant(r.get("updated_at")),s(r,"result_code"),s(r,"result_summary"),s(r,"result_submitted_by_user_id"),instant(r.get("result_submitted_at")),instant(r.get("closed_at")));}
 public record CaseResponse(String id,String eventId,String campusId,String animalId,String status,String claimStatus,String ownerUserId,Instant claimedAt,Instant createdAt,Instant updatedAt,String resultCode,String resultSummary,String resultSubmittedByUserId,Instant resultSubmittedAt,Instant closedAt){}
}
