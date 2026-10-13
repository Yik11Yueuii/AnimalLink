package org.animallink.incident.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Focused local lifecycle transitions for an existing Support Campaign. */
@Service
public class SupportLifecycleService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public SupportLifecycleService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
  }

  public void completeManually(String campaignId, String actor, String reason) {
    String normalizedReason = requiredReason(reason);
    transactions.executeWithoutResult(status -> {
      Map<String, Object> campaign = campaign(campaignId);
      if (!"ACTIVE".equals(text(campaign, "status")) || campaign.get("completed_at") != null) {
        throw SupportCampaignService.conflict("CAMPAIGN_STATE_CONFLICT");
      }
      Timestamp now = now();
      int changed = jdbc.update("UPDATE support_campaign SET status='COMPLETED',completed_at=?,version=version+1,updated_at=? WHERE id=? AND status='ACTIVE' AND completed_at IS NULL AND version=?",
          now, now, campaignId, campaign.get("version"));
      if (changed != 1) throw SupportCampaignService.conflict("CAMPAIGN_STATE_CONFLICT");
      audit(campaignId, "COMPLETE", "ACTIVE", "COMPLETED", actor, normalizedReason, now);
    });
  }

  /** Joins the caller's ambient Case transaction; the Case row must already be locked. */
  public void onCaseTerminal(String caseId, String caseStatus, String actor) {
    String reason = switch (caseStatus) {
      case "RESOLVED", "CLOSED_UNRESOLVED", "CANCELLED", "CLOSED" -> "CASE_STATUS_" + caseStatus;
      default -> throw new IllegalArgumentException("caseStatus");
    };
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT * FROM support_campaign WHERE case_id=? AND status IN ('DRAFT','PENDING_REVIEW','APPROVED','ACTIVE','COMPLETED') ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE",
        caseId);
    if (rows.isEmpty()) return;
    Map<String, Object> campaign = rows.getFirst();
    String from = text(campaign, "status");
    if ("COMPLETED".equals(from)) return;

    boolean complete = "ACTIVE".equals(from) && hasFinancialHistory(text(campaign, "id"));
    String to = complete ? "COMPLETED" : "CANCELLED";
    String action = complete ? "CASE_TERMINAL_COMPLETE" : "CASE_TERMINAL_CANCEL";
    Timestamp now = now();
    int changed = complete
        ? jdbc.update("UPDATE support_campaign SET status='COMPLETED',completed_at=?,version=version+1,updated_at=? WHERE id=? AND status='ACTIVE' AND completed_at IS NULL AND version=?",
            now, now, text(campaign, "id"), campaign.get("version"))
        : jdbc.update("UPDATE support_campaign SET status='CANCELLED',version=version+1,updated_at=? WHERE id=? AND status=? AND version=?",
            now, text(campaign, "id"), from, campaign.get("version"));
    if (changed != 1) throw SupportCampaignService.conflict("CAMPAIGN_STATE_CONFLICT");
    audit(text(campaign, "id"), action, from, to, actor, reason, now);
  }

  private boolean hasFinancialHistory(String campaignId) {
    Integer found = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM support_contribution WHERE campaign_id=?) OR EXISTS(SELECT 1 FROM support_expense WHERE campaign_id=?)",
        Integer.class, campaignId, campaignId);
    return found != null && found == 1;
  }

  private Map<String, Object> campaign(String campaignId) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM support_campaign WHERE id=? FOR UPDATE", campaignId);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("CAMPAIGN_NOT_FOUND");
    return rows.getFirst();
  }

  private void audit(String campaignId, String action, String from, String to, String actor, String reason, Timestamp at) {
    jdbc.update("INSERT INTO support_audit_log(id,campaign_id,resource_type,resource_id,action,from_state,to_state,actor_user_id,reason,created_at) VALUES (?,?,'CAMPAIGN',?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), campaignId, campaignId, action, from, to, actor, reason, at);
  }

  private static String requiredReason(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 1000) throw SupportCampaignService.bad("VALIDATION_ERROR");
    return reason.trim();
  }

  private static Timestamp now() { return Timestamp.from(Instant.now()); }
  private static String text(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : value.toString();
  }
}
