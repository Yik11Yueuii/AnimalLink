package org.animallink.incident.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local Support mutations. Identity checks are performed by the controller before these short transactions. */
@Service
public class SupportCampaignService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final SupportSettlementService settlements;

  public SupportCampaignService(JdbcTemplate jdbc, PlatformTransactionManager manager, SupportSettlementService settlements) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.settlements = settlements;
  }

  public ApplicantApplication create(String caseId, String actor, DraftInput input) {
    validate(input);
    try {
      return transactions.execute(status -> {
        Map<String, Object> caseRow = caseRow(caseId, true);
        eligible(caseRow);
        related(caseRow, actor);
        evidence(input.caseEvidenceId(), caseRow);
        if (!jdbc.queryForList("SELECT id FROM support_campaign WHERE ongoing_case_id=?", caseId).isEmpty()) {
          throw conflict("CAMPAIGN_ALREADY_OPEN");
        }
        String id = UUID.randomUUID().toString();
        Timestamp now = now();
        jdbc.update("INSERT INTO support_campaign(id,case_id,status,target_amount,currency,purpose,application_note,case_evidence_id,requested_by_user_id,created_at,updated_at) VALUES (?,?,'DRAFT',?,'CNY',?,?,?,?,?,?)",
            id, caseId, input.targetAmount(), input.purpose().trim(), input.applicationNote().trim(), input.caseEvidenceId(), actor, now, now);
        audit(id, "CREATE_DRAFT", null, "DRAFT", actor, null);
        return applicant(campaign(id, false));
      });
    } catch (DataIntegrityViolationException ex) {
      throw conflict("CAMPAIGN_ALREADY_OPEN");
    }
  }

  public ApplicantApplication patch(String caseId, String actor, DraftInput input) {
    validate(input);
    return transactions.execute(status -> {
      Map<String, Object> caseRow = caseRow(caseId, true);
      Map<String, Object> row = current(caseId, true);
      requester(row, actor);
      state(row, "DRAFT");
      eligible(caseRow);
      related(caseRow, actor);
      evidence(input.caseEvidenceId(), caseRow);
      Timestamp now = now();
      changed(jdbc.update("UPDATE support_campaign SET target_amount=?,purpose=?,application_note=?,case_evidence_id=?,version=version+1,updated_at=? WHERE id=? AND status='DRAFT' AND version=?",
          input.targetAmount(), input.purpose().trim(), input.applicationNote().trim(), input.caseEvidenceId(), now, str(row, "id"), row.get("version")));
      audit(str(row, "id"), "UPDATE_DRAFT", "DRAFT", "DRAFT", actor, null);
      return applicant(campaign(str(row, "id"), false));
    });
  }

  public ApplicantApplication submit(String caseId, String actor) {
    return transactions.execute(status -> {
      Map<String, Object> caseRow = caseRow(caseId, true);
      Map<String, Object> row = current(caseId, true);
      requester(row, actor);
      state(row, "DRAFT");
      eligible(caseRow);
      related(caseRow, actor);
      change(row, "DRAFT", "PENDING_REVIEW", actor, "SUBMIT_REVIEW", null, null);
      return applicant(campaign(str(row, "id"), false));
    });
  }

  public ApplicantApplication withdraw(String caseId, String actor, String reason) {
    reasonOptional(reason);
    return transactions.execute(status -> {
      caseRow(caseId, false);
      Map<String, Object> row = current(caseId, true);
      requester(row, actor);
      String from = str(row, "status");
      if (!"DRAFT".equals(from) && !"PENDING_REVIEW".equals(from)) throw conflict("CAMPAIGN_STATE_CONFLICT");
      change(row, from, "CANCELLED", actor, "WITHDRAW", reason, null);
      return applicant(campaign(str(row, "id"), false));
    });
  }

  public AdminDetail adminTransition(String id, String actor, String action, String reason) {
    String from = switch (action) {
      case "APPROVE", "REJECT" -> "PENDING_REVIEW";
      case "PUBLISH", "ADMIN_CANCEL" -> "APPROVED";
      default -> throw new IllegalArgumentException("action");
    };
    String to = switch (action) {
      case "APPROVE" -> "APPROVED";
      case "REJECT" -> "REJECTED";
      case "PUBLISH" -> "ACTIVE";
      default -> "CANCELLED";
    };
    if ("REJECT".equals(action) || "ADMIN_CANCEL".equals(action)) reasonRequired(reason);
    else reasonOptional(reason);
    return transactions.execute(status -> {
      Map<String, Object> row = campaign(id, true);
      state(row, from);
      change(row, from, to, actor, action, reason, "PUBLISH".equals(action) ? now() : null);
      return admin(campaign(id, false));
    });
  }

  public PublicCampaign publicRead(String caseId) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM support_campaign WHERE case_id=? AND status IN ('ACTIVE','COMPLETED','SETTLED') ORDER BY created_at DESC,id DESC LIMIT 1", caseId);
    if (rows.isEmpty()) throw notFound("CAMPAIGN_NOT_FOUND");
    Map<String, Object> r = rows.getFirst();
    BigDecimal recorded = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0.00) FROM support_contribution WHERE campaign_id=? AND status='RECORDED'", BigDecimal.class, str(r, "id"));
    long count = jdbc.queryForObject("SELECT COUNT(*) FROM support_contribution WHERE campaign_id=? AND status='RECORDED'", Long.class, str(r, "id"));
    BigDecimal publishedExpense = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0.00) FROM support_expense WHERE campaign_id=? AND status='PUBLISHED'", BigDecimal.class, str(r, "id"));
    long expenseCount = jdbc.queryForObject("SELECT COUNT(*) FROM support_expense WHERE campaign_id=? AND status='PUBLISHED'", Long.class, str(r, "id"));
    BigDecimal target = (BigDecimal) r.get("target_amount");
    BigDecimal progress = recorded.multiply(BigDecimal.valueOf(100)).divide(target, 2, RoundingMode.HALF_UP).min(new BigDecimal("100.00"));
    return new PublicCampaign(str(r, "id"), caseId, str(r, "status"), target, str(r, "currency"), str(r, "purpose"), instant(r, "published_at"), instant(r, "completed_at"), recorded, count, progress,
        publishedExpense, expenseCount, recorded.subtract(publishedExpense));
  }

  public ApplicantApplication applicantRead(String caseId, String actor) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM support_campaign WHERE case_id=? AND requested_by_user_id=? ORDER BY created_at DESC,id DESC LIMIT 1", caseId, actor);
    if (rows.isEmpty()) throw notFound("CAMPAIGN_NOT_FOUND");
    return applicant(rows.getFirst());
  }

  public Page adminQueue(String filter, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw bad("VALIDATION_ERROR");
    if (filter == null || filter.isBlank()) filter = "PENDING_REVIEW";
    if (!List.of("DRAFT", "PENDING_REVIEW", "APPROVED", "ACTIVE", "COMPLETED", "SETTLED", "REJECTED", "CANCELLED").contains(filter)) throw bad("VALIDATION_ERROR");
    long count = jdbc.queryForObject("SELECT COUNT(*) FROM support_campaign WHERE status=?", Long.class, filter);
    List<AdminListItem> items = jdbc.queryForList("SELECT id,case_id,status,target_amount,currency,purpose,requested_by_user_id,created_at FROM support_campaign WHERE status=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", filter, size, (long) page * size)
        .stream().map(r -> new AdminListItem(str(r,"id"), str(r,"case_id"), str(r,"status"), (BigDecimal) r.get("target_amount"), str(r,"currency"), str(r,"purpose"), str(r,"requested_by_user_id"), instant(r,"created_at"))).toList();
    return new Page(items, page, size, count);
  }

  public AdminDetail adminDetail(String id) { return admin(campaign(id, false)); }

  public CaseContext context(String caseId) {
    Map<String, Object> row = caseRow(caseId, false);
    return new CaseContext(str(row,"id"), str(row,"campus_id"));
  }

  public void checkRequesterRelation(String caseId, String actor) { related(caseRow(caseId, false), actor); }

  private void change(Map<String,Object> row, String from, String to, String actor, String action, String reason, Timestamp published) {
    Timestamp now = now();
    int n = jdbc.update("UPDATE support_campaign SET status=?,reviewed_by_user_id=CASE WHEN ? IN ('APPROVE','REJECT') THEN ? ELSE reviewed_by_user_id END,review_reason=CASE WHEN ? IN ('APPROVE','REJECT') THEN ? ELSE review_reason END,published_at=COALESCE(?,published_at),version=version+1,updated_at=? WHERE id=? AND status=? AND version=?",
        to, action, actor, action, reason == null ? null : reason.trim(), published, now, str(row,"id"), from, row.get("version"));
    changed(n);
    audit(str(row,"id"), action, from, to, actor, reason);
  }

  private void audit(String id, String action, String from, String to, String actor, String reason) {
    jdbc.update("INSERT INTO support_audit_log(id,campaign_id,resource_type,resource_id,action,from_state,to_state,actor_user_id,reason,created_at) VALUES (?,?,'CAMPAIGN',?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), id, id, action, from, to, actor, reason == null ? null : reason.trim(), now());
  }

  private void evidence(String evidenceId, Map<String,Object> caseRow) {
    if (evidenceId == null) return;
    if (evidenceId.isBlank() || evidenceId.length() > 36) throw bad("INVALID_CASE_EVIDENCE");
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM evidence WHERE id=? AND event_id=?", Integer.class, evidenceId, str(caseRow,"event_id"));
    if (count == null || count == 0) throw bad("INVALID_CASE_EVIDENCE");
  }

  private void eligible(Map<String,Object> row) {
    if (!"ACTIVE".equals(str(row,"status")) || !"CLAIMED".equals(str(row,"claim_status"))) throw conflict("CASE_NOT_ELIGIBLE_FOR_SUPPORT");
  }

  private void related(Map<String,Object> row, String actor) {
    if (actor.equals(str(row,"owner_user_id"))) return;
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM case_participant WHERE case_id=? AND user_id=? AND status='ACTIVE'", Integer.class, str(row,"id"), actor);
    if (count == null || count == 0) throw forbidden("FINANCIAL_ACCESS_DENIED");
  }

  private void requester(Map<String,Object> row, String actor) {
    if (!actor.equals(str(row,"requested_by_user_id"))) throw forbidden("FINANCIAL_ACCESS_DENIED");
  }

  private Map<String,Object> current(String caseId, boolean lock) {
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM support_campaign WHERE ongoing_case_id=? ORDER BY created_at DESC,id DESC LIMIT 1" + (lock ? " FOR UPDATE" : ""), caseId);
    if (rows.isEmpty()) throw notFound("CAMPAIGN_NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String,Object> campaign(String id, boolean lock) {
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM support_campaign WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw notFound("CAMPAIGN_NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String,Object> caseRow(String id, boolean lock) {
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,event_id,campus_id,owner_user_id,status,claim_status FROM animal_case WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw notFound("CASE_NOT_FOUND");
    return rows.getFirst();
  }

  private void state(Map<String,Object> row, String expected) {
    if (!expected.equals(str(row,"status"))) throw conflict("CAMPAIGN_STATE_CONFLICT");
  }

  private static void validate(DraftInput input) {
    if (input == null || input.targetAmount() == null || input.targetAmount().signum() <= 0
        || input.targetAmount().scale() > 2 || input.targetAmount().precision() - input.targetAmount().scale() > 16) throw bad("INVALID_AMOUNT");
    if (input.purpose() == null || input.purpose().isBlank() || input.purpose().length() > 1000
        || input.applicationNote() == null || input.applicationNote().isBlank() || input.applicationNote().length() > 2000) throw bad("VALIDATION_ERROR");
  }

  private static void reasonRequired(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 1000) throw bad("VALIDATION_ERROR");
  }
  private static void reasonOptional(String reason) { if (reason != null && reason.length() > 1000) throw bad("VALIDATION_ERROR"); }
  private static void changed(int n) { if (n != 1) throw conflict("CAMPAIGN_STATE_CONFLICT"); }
  private static Timestamp now() { return Timestamp.from(Instant.now()); }
  private static String str(Map<String,Object> row, String key) { Object v = row.get(key); return v == null ? null : v.toString(); }
  private static Instant instant(Map<String,Object> row, String key) { Object v = row.get(key); return v == null ? null : ((Timestamp) v).toInstant(); }

  private static ApplicantApplication applicant(Map<String,Object> r) {
    return new ApplicantApplication(str(r,"id"), str(r,"case_id"), str(r,"status"), (BigDecimal) r.get("target_amount"), str(r,"currency"), str(r,"purpose"), str(r,"application_note"), str(r,"case_evidence_id"), str(r,"review_reason"), instant(r,"published_at"), instant(r,"completed_at"), instant(r,"created_at"), instant(r,"updated_at"));
  }
  private AdminDetail admin(Map<String,Object> r) {
    String campaignId = str(r,"id");
    return new AdminDetail(campaignId, str(r,"case_id"), str(r,"status"), (BigDecimal) r.get("target_amount"), str(r,"currency"), str(r,"purpose"), str(r,"application_note"), str(r,"case_evidence_id"), str(r,"requested_by_user_id"), str(r,"reviewed_by_user_id"), str(r,"review_reason"), instant(r,"published_at"), instant(r,"completed_at"), instant(r,"created_at"), instant(r,"updated_at"), settlements.governanceForCampaign(campaignId));
  }

  static Failure bad(String code) { return new Failure(code, 400); }
  static Failure forbidden(String code) { return new Failure(code, 403); }
  static Failure notFound(String code) { return new Failure(code, 404); }
  static Failure conflict(String code) { return new Failure(code, 409); }
  public static class Failure extends RuntimeException {
    private final int status;
    Failure(String code, int status) { super(code); this.status = status; }
    public int status() { return status; }
  }
  public record DraftInput(BigDecimal targetAmount, String purpose, String applicationNote, String caseEvidenceId) {}
  public record ReasonInput(String reason) {}
  public record CaseContext(String caseId, String campusId) {}
  public record PublicCampaign(String campaignId, String caseId, String status, BigDecimal targetAmount, String currency, String purpose, Instant publishedAt, Instant completedAt, BigDecimal recordedContributionAmount, long contributionCount, BigDecimal progressPercent, BigDecimal publishedExpenseAmount, long expenseCount, BigDecimal recordedNetBalance) {}
  public record ApplicantApplication(String campaignId, String caseId, String status, BigDecimal targetAmount, String currency, String purpose, String applicationNote, String caseEvidenceId, String reviewReason, Instant publishedAt, Instant completedAt, Instant createdAt, Instant updatedAt) {}
  public record AdminListItem(String campaignId, String caseId, String status, BigDecimal targetAmount, String currency, String purpose, String requestedByUserId, Instant createdAt) {}
  public record Page(List<AdminListItem> items, int page, int size, long total) {}
  public record AdminDetail(String campaignId, String caseId, String status, BigDecimal targetAmount, String currency, String purpose, String applicationNote, String caseEvidenceId, String requestedByUserId, String reviewedByUserId, String reviewReason, Instant publishedAt, Instant completedAt, Instant createdAt, Instant updatedAt, SupportSettlementService.GovernanceSettlement settlement) {}
}
