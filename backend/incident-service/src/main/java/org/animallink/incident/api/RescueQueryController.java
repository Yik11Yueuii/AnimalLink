package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Page-oriented views backed directly by the incident source tables; no read-model copy is kept. */
@RestController
@RequestMapping("/api/v1")
public class RescueQueryController {
  private static final Set<String> COMPLETED = Set.of("RESOLVED", "CLOSED", "CLOSED_UNRESOLVED", "CANCELLED");
  private final JdbcTemplate jdbc;
  private final RestClient identity;
  private final RestClient animal;
  private final HttpServletRequest request;

  public RescueQueryController(JdbcTemplate jdbc, @Qualifier("identityRestClient") RestClient identity,
      @Qualifier("animalRestClient") RestClient animal, HttpServletRequest request) {
    this.jdbc = jdbc; this.identity = identity; this.animal = animal; this.request = request;
  }

  @GetMapping("/rescues")
  public PageResponse<RescueItem> rescues(@RequestParam("campusId") String campusId,
      @RequestParam(value="status", required = false) String status, @RequestParam(value="animalId", required = false) String animalId,
      @RequestParam(value="page", defaultValue = "0") @Min(0) int page, @RequestParam(value="size", defaultValue = "20") @Min(1) @Max(100) int size) {
    if (status != null && !Set.of("WAITING_CLAIM", "IN_PROGRESS", "COMPLETED").contains(status)) throw new IllegalArgumentException("rescue status");
    Query q = base("e.campus_id=? AND e.status='VERIFIED'", List.of(campusId));
    if (animalId != null) q.add("e.animal_id=?", animalId);
    if (status != null) q.add(statusSql(status));
    QueryPage result=query(q,page,size); Map<String,Animal> animals=animals(result.rows.stream().map(r->s(r,"animal_id")).toList());
    return new PageResponse<>(result.rows.stream().map(r->rescueItem(r,animals)).toList(),page,size,result.total);
  }

  @GetMapping("/rescues/{eventId}")
  public RescueDetail detail(@PathVariable("eventId") String eventId) {
    Map<String, Object> e = one("SELECT * FROM event WHERE id=?", eventId, "EVENT_NOT_FOUND");
    Map<String, Object> c = optional("SELECT * FROM animal_case WHERE event_id=?", eventId);
    String userId = headerUser();
    String role = userId == null ? null : role(userId);
    boolean owner = c != null && userId != null && userId.equals(s(c, "owner_user_id"));
    boolean participant = c != null && userId != null && exists("SELECT 1 FROM case_participant WHERE case_id=? AND user_id=? AND status='ACTIVE'", s(c, "id"), userId);
    boolean admin = "GOVERNANCE_ADMIN".equals(role);
    boolean related = owner || participant;
    boolean exact = userId != null && (userId.equals(s(e, "reporter_user_id")) || admin || related);
    boolean activeCase = c != null && "ACTIVE".equals(s(c, "status")) && "CLAIMED".equals(s(c, "claim_status"));
    boolean volunteer = userId != null && activeVolunteerQuiet(userId, s(e, "campus_id"));
    boolean canClaim = userId != null && volunteer && "VERIFIED".equals(s(e, "status")) && (c == null || "WAITING_CLAIM".equals(s(c, "claim_status")));
    Viewer viewer = new Viewer(exact, canClaim, owner, participant,
        activeCase && related && volunteer, activeCase && related && volunteer, activeCase && owner && volunteer);
    List<Evidence> evidence = jdbc.queryForList("SELECT id,description,occurred_at,public_location_description,created_at FROM evidence WHERE event_id=? ORDER BY created_at,id", eventId).stream().map(this::evidence).toList();
    List<Action> progress = (related || admin) && c != null ? actions(s(c, "id")) : List.of();
    return new RescueDetail(event(e, exact), animal(animalsFor(s(e, "animal_id")), s(e, "animal_id")), evidence,
        caseDetail(c), viewer, progress, count("SELECT COUNT(*) FROM evidence WHERE event_id=?", eventId), c == null ? 0 : count("SELECT COUNT(*) FROM case_action WHERE case_id=?", s(c, "id")));
  }

  @GetMapping("/volunteer/cases")
  public PageResponse<WorkspaceItem> workspace(@RequestParam("campusId") String campusId, @RequestParam("scope") String scope,
      @RequestParam(value="page", defaultValue = "0") @Min(0) int page, @RequestParam(value="size", defaultValue = "20") @Min(1) @Max(100) int size) {
    if (!Set.of("AVAILABLE", "ACTIVE", "HISTORY").contains(scope)) throw new IllegalArgumentException("workspace scope");
    String user = requiredUser(); activeVolunteer(user, campusId);
    Query q;
    if ("AVAILABLE".equals(scope)) {
      q = base("e.campus_id=? AND e.status='VERIFIED' AND (c.id IS NULL OR c.claim_status='WAITING_CLAIM')", List.of(campusId));
    } else if ("ACTIVE".equals(scope)) {
      q = base("e.campus_id=? AND c.status='ACTIVE' AND (c.owner_user_id=? OR EXISTS (SELECT 1 FROM case_participant p WHERE p.case_id=c.id AND p.user_id=? AND p.status='ACTIVE'))", List.of(campusId, user, user));
    } else {
      q = base("e.campus_id=? AND c.status IN ('RESOLVED','CLOSED','CLOSED_UNRESOLVED','CANCELLED') AND (c.owner_user_id=? OR EXISTS (SELECT 1 FROM case_participant p WHERE p.case_id=c.id AND p.user_id=?) OR EXISTS (SELECT 1 FROM case_owner_audit_log o WHERE o.case_id=c.id AND (o.previous_owner_user_id=? OR o.new_owner_user_id=?)))", List.of(campusId, user, user, user, user));
    }
    QueryPage result=query(q,page,size); Map<String,Animal> animals=animals(result.rows.stream().map(r->s(r,"animal_id")).toList());
    Set<String> formerOwners = "HISTORY".equals(scope) ? formerOwnerCases(result.rows.stream().map(r -> s(r, "case_id")).filter(Objects::nonNull).toList(), user) : Set.of();
    return new PageResponse<>(result.rows.stream().map(r->workspaceItem(r,user,scope,animals,formerOwners)).toList(),page,size,result.total);
  }

  private Query base(String where, List<Object> args) {
    String select = "SELECT e.id event_id,e.campus_id,e.animal_id,e.description,e.abnormality_summary,e.occurred_at,e.reported_at,e.public_location_description,e.status event_status,e.updated_at event_updated_at," +
        "c.id case_id,c.status case_status,c.claim_status,c.owner_user_id,c.claimed_at,c.updated_at case_updated_at,c.result_code,c.result_summary,c.result_submitted_at,c.closed_at," +
        "COALESCE(ev.evidence_count,0) evidence_count,COALESCE(ac.action_count,0) action_count,ac.latest_action_at " +
        "FROM event e LEFT JOIN animal_case c ON c.event_id=e.id " +
        "LEFT JOIN (SELECT event_id,COUNT(*) evidence_count FROM evidence GROUP BY event_id) ev ON ev.event_id=e.id " +
        "LEFT JOIN (SELECT case_id,COUNT(*) action_count,MAX(occurred_at) latest_action_at FROM case_action GROUP BY case_id) ac ON ac.case_id=c.id ";
    return new Query(select, where, new ArrayList<>(args));
  }
  private String statusSql(String status) {
    return switch (status) {
      case "WAITING_CLAIM" -> "(c.id IS NULL OR c.claim_status='WAITING_CLAIM')";
      case "IN_PROGRESS" -> "c.status='ACTIVE' AND c.claim_status='CLAIMED'";
      default -> "c.status IN ('RESOLVED','CLOSED','CLOSED_UNRESOLVED','CANCELLED')";
    };
  }
  private QueryPage query(Query q, int page, int size) {
    String from = q.select + " WHERE " + q.where;
    Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + from + ") rescue_page", Long.class, q.args.toArray());
    List<Object> args = new ArrayList<>(q.args); args.add(size); args.add((long) page * size);
    List<Map<String,Object>> rows = jdbc.queryForList(from + " ORDER BY COALESCE(c.updated_at,e.updated_at) DESC,e.id DESC LIMIT ? OFFSET ?", args.toArray());
    return new QueryPage(rows,total == null ? 0 : total);
  }
  private RescueItem rescueItem(Map<String,Object> r,Map<String,Animal> animals) { return new RescueItem(s(r,"event_id"),s(r,"case_id"),s(r,"campus_id"),s(r,"animal_id"),rescueStatus(r),s(r,"event_status"),s(r,"case_status"),s(r,"claim_status"),s(r,"owner_user_id") != null,s(r,"abnormality_summary"),s(r,"description"),s(r,"public_location_description"),instant(r,"occurred_at"),instant(r,"reported_at"),number(r,"evidence_count"),number(r,"action_count"),s(r,"result_code"),s(r,"result_summary"),instant(r,"case_updated_at") == null ? instant(r,"event_updated_at") : instant(r,"case_updated_at"),animal(animals,s(r,"animal_id"))); }
  private WorkspaceItem workspaceItem(Map<String,Object> r, String user, String scope,Map<String,Animal> animals, Set<String> formerOwners) { String relation = "AVAILABLE".equals(scope) ? "AVAILABLE" : user.equals(s(r,"owner_user_id")) ? "OWNER" : exists("SELECT 1 FROM case_participant WHERE case_id=? AND user_id=? AND status='ACTIVE'",s(r,"case_id"),user) ? "ACTIVE_PARTICIPANT" : formerOwners.contains(s(r,"case_id")) ? "FORMER_OWNER" : "FORMER_PARTICIPANT"; return new WorkspaceItem(s(r,"case_id"),s(r,"event_id"),s(r,"animal_id"),animal(animals,s(r,"animal_id")),s(r,"case_status"),s(r,"claim_status"),relation,s(r,"abnormality_summary"),s(r,"public_location_description"),instant(r,"reported_at"),instant(r,"claimed_at"),instant(r,"latest_action_at"),number(r,"action_count"),s(r,"result_code"),s(r,"result_summary")); }
  private Set<String> formerOwnerCases(List<String> caseIds, String user) { if (caseIds.isEmpty()) return Set.of(); String marks = String.join(",", Collections.nCopies(caseIds.size(), "?")); List<Object> args = new ArrayList<>(caseIds); args.add(user); args.add(user); Set<String> ids = new java.util.HashSet<>(); for (Map<String,Object> row : jdbc.queryForList("SELECT DISTINCT case_id FROM case_owner_audit_log WHERE case_id IN (" + marks + ") AND (previous_owner_user_id=? OR new_owner_user_id=?)", args.toArray())) { String id = s(row, "case_id"); if (id != null) ids.add(id); } return ids; }
  private String rescueStatus(Map<String,Object> r) { String caseStatus=s(r,"case_status"); if (caseStatus != null && COMPLETED.contains(caseStatus)) return "COMPLETED"; if ("ACTIVE".equals(caseStatus) && "CLAIMED".equals(s(r,"claim_status"))) return "IN_PROGRESS"; return "WAITING_CLAIM"; }
  private Event event(Map<String,Object> e, boolean exact) { return new Event(s(e,"id"),s(e,"campus_id"),s(e,"animal_id"),s(e,"description"),s(e,"abnormality_summary"),instant(e,"occurred_at"),instant(e,"reported_at"),s(e,"public_location_description"),s(e,"status"),exact?s(e,"exact_location_description"):null,exact?decimal(e,"latitude"):null,exact?decimal(e,"longitude"):null); }
  private Case caseDetail(Map<String,Object> c) { return c == null ? null : new Case(s(c,"id"),s(c,"status"),s(c,"claim_status"),s(c,"owner_user_id")!=null,instant(c,"claimed_at"),s(c,"result_code"),s(c,"result_summary"),instant(c,"result_submitted_at"),instant(c,"closed_at")); }
  private Evidence evidence(Map<String,Object> r) { return new Evidence(s(r,"id"),s(r,"description"),instant(r,"occurred_at"),s(r,"public_location_description"),instant(r,"created_at")); }
  private List<Action> actions(String caseId) { return jdbc.queryForList("SELECT id,case_id,actor_user_id,description,occurred_at,created_at,result_code FROM case_action WHERE case_id=? ORDER BY occurred_at,created_at,id",caseId).stream().map(r -> new Action(s(r,"id"),s(r,"case_id"),s(r,"actor_user_id"),s(r,"description"),instant(r,"occurred_at"),instant(r,"created_at"),s(r,"result_code"))).toList(); }
  private Map<String,Animal> animalsFor(String id) { return animals(Collections.singletonList(id)); }
  private Animal animal(Map<String,Animal> animals, String id) { return id == null ? null : animals.get(id); }
  private Map<String,Animal> animals(Collection<String> rawIds) { List<String> ids=rawIds.stream().filter(Objects::nonNull).distinct().toList(); if(ids.isEmpty()) return Map.of(); Map<String,Animal> fallback=new HashMap<>(); ids.forEach(id->fallback.put(id,new Animal(id,null,null,null))); try { List<?> body=animal.post().uri("/internal/v1/incident-facts/animals/batch").header("X-Internal-Service","incident-service").body(Map.of("animalIds",ids)).retrieve().body(List.class); if(body!=null) for(Object value:body) if(value instanceof Map<?,?> m){String id=String.valueOf(m.get("id"));fallback.put(id,new Animal(id,string(m.get("displayName")),string(m.get("species")),string(m.get("identityStatus"))));} } catch(Exception ignored) { } return fallback; }
  private String requiredUser(){String id=headerUser();if(id==null)throw new EventController.Unauthorized("需要登录");return id;}
  private String headerUser(){String id=request.getHeader("X-User-Id");return id==null||id.isBlank()?null:id;}
  private String role(String user){try{Map<?,?> r=identity.get().uri("/api/v1/users/me").header("X-User-Id",user).retrieve().body(Map.class);return r==null?null:string(r.get("systemRole"));}catch(Exception ignored){return null;}}
  private boolean activeVolunteerQuiet(String user,String campus){try{activeVolunteer(user,campus);return true;}catch(RuntimeException ignored){return false;}}
  private void activeVolunteer(String user,String campus){try{Map<?,?> r=identity.get().uri("/internal/v1/users/{u}/campuses/{c}/volunteer-membership",user,campus).header("X-Internal-Service","incident-service").retrieve().body(Map.class);if(r==null||!Boolean.TRUE.equals(r.get("exists"))||!Boolean.TRUE.equals(r.get("active"))||!"ACTIVE".equals(r.get("status")))throw new EventController.Forbidden("ACTIVE_VOLUNTEER_REQUIRED");}catch(EventController.Forbidden e){throw e;}catch(Exception e){throw new EventController.Dependency("IDENTITY_SERVICE_UNAVAILABLE");}}
  private int count(String sql,Object...args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}
  private boolean exists(String sql,Object...args){return !jdbc.queryForList(sql,args).isEmpty();}
  private Map<String,Object> one(String sql,Object arg,String code){Map<String,Object> row=optional(sql,arg);if(row==null)throw new EventController.NotFound(code);return row;}
  private Map<String,Object> optional(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
  private static String s(Map<?,?> row,String key){return string(row.get(key));} private static String string(Object value){return value==null?null:String.valueOf(value);} private static Instant instant(Map<?,?> row,String key){Object v=row.get(key);return v==null?null:v instanceof Timestamp t?t.toInstant():(Instant)v;} private static int number(Map<?,?> row,String key){Object v=row.get(key);return v==null?0:((Number)v).intValue();} private static Double decimal(Map<?,?> row,String key){Object v=row.get(key);return v==null?null:((Number)v).doubleValue();}
  private static final class Query { final String select; String where; final List<Object> args; Query(String select,String where,List<Object> args){this.select=select;this.where=where;this.args=args;} void add(String clause,Object...values){where+=" AND "+clause;Collections.addAll(args,values);} }
  private record QueryPage(List<Map<String,Object>> rows,long total){}
  public record PageResponse<T>(List<T> items,int page,int size,long total){}
  public record Animal(String id,String displayName,String species,String identityStatus){}
  public record RescueItem(String eventId,String caseId,String campusId,String animalId,String rescueStatus,String eventStatus,String caseStatus,String claimStatus,boolean hasOwner,String abnormalitySummary,String description,String publicLocationDescription,Instant occurredAt,Instant reportedAt,int evidenceCount,int actionCount,String resultCode,String resultSummary,Instant updatedAt,Animal animal){}
  public record Event(String eventId,String campusId,String animalId,String description,String abnormalitySummary,Instant occurredAt,Instant reportedAt,String publicLocationDescription,String eventStatus,String exactLocationDescription,Double latitude,Double longitude){}
  public record Evidence(String id,String description,Instant occurredAt,String publicLocationDescription,Instant createdAt){}
  public record Case(String caseId,String status,String claimStatus,boolean hasOwner,Instant claimedAt,String resultCode,String resultSummary,Instant resultSubmittedAt,Instant closedAt){}
  public record Viewer(boolean canViewPreciseLocation,boolean canClaim,boolean isCaseOwner,boolean isActiveParticipant,boolean canAddCaseAction,boolean canSubmitResult,boolean canManageParticipants){}
  public record Action(String id,String caseId,String actorUserId,String description,Instant occurredAt,Instant createdAt,String resultCode){}
  public record RescueDetail(Event event,Animal animal,List<Evidence> evidence,Case caseInfo,Viewer viewer,List<Action> progress,int evidenceCount,int actionCount){}
  public record WorkspaceItem(String caseId,String eventId,String animalId,Animal animal,String caseStatus,String claimStatus,String relationship,String abnormalitySummary,String publicLocationDescription,Instant reportedAt,Instant claimedAt,Instant latestActionAt,int actionCount,String resultCode,String resultSummary){}
}
