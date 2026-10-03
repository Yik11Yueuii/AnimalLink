package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/v1/cases/{caseId}")
public class CaseOwnerController {
  private final JdbcTemplate jdbc; private final RestClient identity; private final HttpServletRequest request;
  public CaseOwnerController(JdbcTemplate jdbc,@Qualifier("identityRestClient") RestClient identity,HttpServletRequest request){this.jdbc=jdbc;this.identity=identity;this.request=request;}
  @PostMapping("/owner/transfer") @Transactional public OwnerResponse transfer(@PathVariable("caseId") String caseId,@Valid @RequestBody ChangeRequest body){String caller=user();Map<String,Object> c=one(caseId);if(!caller.equals(s(c,"owner_user_id")))throw new EventController.Forbidden("CASE_OWNER_TRANSFER_OWNER_REQUIRED");volunteer(caller,s(c,"campus_id"),"ACTIVE_VOLUNTEER_REQUIRED");return change(caseId,c,caller,body,"OWNER_TRANSFER");}
  @PostMapping("/owner/adjustment") @Transactional public OwnerResponse adjustment(@PathVariable("caseId") String caseId,@Valid @RequestBody ChangeRequest body){String caller=user();if(!"GOVERNANCE_ADMIN".equals(role()))throw new EventController.Forbidden("GOVERNANCE_REQUIRED");Map<String,Object> c=one(caseId);return change(caseId,c,caller,body,"GOVERNANCE_ADJUSTMENT");}
  @GetMapping("/owner-history") public List<AuditResponse> history(@PathVariable("caseId") String caseId){String caller=user();Map<String,Object> c=one(caseId);if(!caller.equals(s(c,"owner_user_id"))&&!activeParticipant(caseId,caller)&&!"GOVERNANCE_ADMIN".equals(role()))throw new EventController.Forbidden("CASE_OWNER_HISTORY_ACCESS_REQUIRED");return jdbc.queryForList("SELECT * FROM case_owner_audit_log WHERE case_id=? ORDER BY created_at,id",caseId).stream().map(this::audit).toList();}
  private OwnerResponse change(String id,Map<String,Object> c,String caller,ChangeRequest body,String type){state(c);String old=s(c,"owner_user_id");if(old.equals(body.targetUserId()))throw new EventController.Conflict("CASE_OWNER_SAME_OWNER");volunteer(body.targetUserId(),s(c,"campus_id"),"TARGET_ACTIVE_VOLUNTEER_REQUIRED");Instant now=Instant.now();int n=jdbc.update("UPDATE animal_case SET owner_user_id=?,version=version+1,updated_at=? WHERE id=? AND owner_user_id=? AND claim_status='CLAIMED' AND status IN ('ACTIVE','RESOLVED')",body.targetUserId(),Timestamp.from(now),id,old);if(n!=1)throw new EventController.Conflict("CASE_OWNER_CHANGE_CONFLICT");jdbc.update("INSERT INTO case_owner_audit_log(id,case_id,previous_owner_user_id,new_owner_user_id,changed_by_user_id,change_type,reason,created_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,old,body.targetUserId(),caller,type,body.reason(),Timestamp.from(now));return new OwnerResponse(id,body.targetUserId());}
  private void state(Map<String,Object> c){if(!"CLAIMED".equals(s(c,"claim_status"))||!("ACTIVE".equals(s(c,"status"))||"RESOLVED".equals(s(c,"status"))))throw new EventController.Conflict("CASE_OWNER_CHANGE_STATE_CONFLICT");}
  private void volunteer(String u,String campus,String code){try{Map<?,?> f=identity.get().uri("/internal/v1/users/{u}/campuses/{c}/volunteer-membership",u,campus).header("X-Internal-Service","incident-service").retrieve().body(Map.class);if(f==null||!Boolean.TRUE.equals(f.get("exists"))||!Boolean.TRUE.equals(f.get("active"))||!"ACTIVE".equals(f.get("status")))throw new EventController.Forbidden(code);}catch(EventController.Forbidden e){throw e;}catch(Exception e){throw new EventController.Dependency("IDENTITY_SERVICE_UNAVAILABLE");}}
  private String user(){String u=request.getHeader("X-User-Id");if(u==null||u.isBlank())throw new EventController.Unauthorized("需要登录");return u;}
  private String role(){try{var q=identity.get().uri("/api/v1/users/me");String u=request.getHeader("X-User-Id");if(u!=null)q=q.header("X-User-Id",u);Map<?,?> v=q.retrieve().body(Map.class);return v==null?null:String.valueOf(v.get("systemRole"));}catch(Exception e){throw new EventController.Unauthorized("需要登录");}}
  private Map<String,Object> one(String id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM animal_case WHERE id=?",id);if(rows.isEmpty())throw new EventController.NotFound("CASE_NOT_FOUND");return rows.getFirst();}
  private boolean activeParticipant(String c,String u){Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM case_participant WHERE case_id=? AND user_id=? AND status='ACTIVE'",Integer.class,c,u);return n!=null&&n>0;}
  private AuditResponse audit(Map<String,Object> r){return new AuditResponse(s(r,"id"),s(r,"case_id"),s(r,"previous_owner_user_id"),s(r,"new_owner_user_id"),s(r,"changed_by_user_id"),s(r,"change_type"),s(r,"reason"),((Timestamp)r.get("created_at")).toInstant());}
  private static String s(Map<String,Object> r,String k){Object v=r.get(k);return v==null?null:v.toString();}
  public record ChangeRequest(@NotBlank @Size(max=36) String targetUserId,@NotBlank @Size(max=1000) String reason){}
  public record OwnerResponse(String caseId,String ownerUserId){}
  public record AuditResponse(String id,String caseId,String previousOwnerUserId,String newOwnerUserId,String changedByUserId,String changeType,String reason,Instant createdAt){}
}
