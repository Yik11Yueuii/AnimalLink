package org.animallink.incident.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable recorded simulated-support ledger. This service does not perform payment processing. */
@Service
public class SupportContributionService {
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public SupportContributionService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
  }

  public CreateResult create(String caseId, String contributor, String rawKey, ContributionInput input) {
    BigDecimal amount = amount(input);
    String key = idempotencyKey(rawKey);
    String hash = requestHash(amount);
    try {
      return transactions.execute(status -> {
        Map<String, Object> caseRow = caseRow(caseId, true);
        Map<String, Object> campaign = latestCampaign(caseId, true);
        accepting(caseRow, campaign);
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT * FROM support_contribution WHERE campaign_id=? AND contributor_user_id=? AND idempotency_key=?",
            str(campaign, "id"), contributor, key);
        if (!existing.isEmpty()) return replay(existing.getFirst(), hash);

        String id = UUID.randomUUID().toString();
        Timestamp recordedAt = now();
        jdbc.update("INSERT INTO support_contribution(id,campaign_id,contributor_user_id,amount,status,idempotency_key,request_hash,recorded_at) VALUES (?,?,?,?,'RECORDED',?,?,?)",
            id, str(campaign, "id"), contributor, amount, key, hash, recordedAt);
        return new CreateResult(record(existing(id, false)), true);
      });
    } catch (DataIntegrityViolationException ex) {
      throw SupportCampaignService.conflict("IDEMPOTENCY_KEY_REUSED");
    }
  }

  public List<ContributorRecord> ownHistory(String caseId, String contributor) {
    Map<String, Object> campaign = latestCampaign(caseId, false);
    return jdbc.queryForList("SELECT * FROM support_contribution WHERE campaign_id=? AND contributor_user_id=? ORDER BY recorded_at DESC,id DESC",
        str(campaign, "id"), contributor).stream().map(SupportContributionService::contributorRecord).toList();
  }

  public ContributionPage governanceLedger(String campaignId, int page, int size) {
    page(page, size);
    campaign(campaignId, false);
    long total = jdbc.queryForObject("SELECT COUNT(*) FROM support_contribution WHERE campaign_id=?", Long.class, campaignId);
    List<GovernanceRecord> items = jdbc.queryForList("SELECT * FROM support_contribution WHERE campaign_id=? ORDER BY recorded_at DESC,id DESC LIMIT ? OFFSET ?",
        campaignId, size, (long) page * size).stream().map(SupportContributionService::governanceRecord).toList();
    return new ContributionPage(items, page, size, total);
  }

  public GovernanceRecord voidContribution(String contributionId, String actor, VoidInput input) {
    String reason = reason(input);
    List<Map<String, Object>> lookup = jdbc.queryForList("SELECT campaign_id FROM support_contribution WHERE id=?", contributionId);
    if (lookup.isEmpty()) throw SupportCampaignService.notFound("CONTRIBUTION_NOT_FOUND");
    String campaignId = str(lookup.getFirst(), "campaign_id");
    return transactions.execute(status -> {
      Map<String, Object> campaign = campaign(campaignId, true);
      if (!List.of("ACTIVE", "COMPLETED").contains(str(campaign, "status"))) {
        throw SupportCampaignService.conflict("CONTRIBUTION_STATE_CONFLICT");
      }
      Map<String, Object> contribution = existing(contributionId, true);
      if (!"RECORDED".equals(str(contribution, "status"))) {
        throw SupportCampaignService.conflict("CONTRIBUTION_STATE_CONFLICT");
      }
      Timestamp voidedAt = now();
      int changed = jdbc.update("UPDATE support_contribution SET status='VOIDED',voided_by_user_id=?,void_reason=?,voided_at=? WHERE id=? AND status='RECORDED'",
          actor, reason, voidedAt, contributionId);
      if (changed != 1) throw SupportCampaignService.conflict("CONTRIBUTION_STATE_CONFLICT");
      jdbc.update("INSERT INTO support_audit_log(id,campaign_id,resource_type,resource_id,action,from_state,to_state,actor_user_id,reason,created_at) VALUES (?,?,'CONTRIBUTION',?,'VOID_CONTRIBUTION','RECORDED','VOIDED',?,?,?)",
          UUID.randomUUID().toString(), campaignId, contributionId, actor, reason, voidedAt);
      return governanceRecord(existing(contributionId, false));
    });
  }

  private CreateResult replay(Map<String, Object> existing, String hash) {
    if (!hash.equals(str(existing, "request_hash"))) {
      throw SupportCampaignService.conflict("IDEMPOTENCY_KEY_REUSED");
    }
    return new CreateResult(record(existing), false);
  }

  private void accepting(Map<String, Object> caseRow, Map<String, Object> campaign) {
    if (!"ACTIVE".equals(str(campaign, "status")) || !"ACTIVE".equals(str(caseRow, "status"))
        || !"CLAIMED".equals(str(caseRow, "claim_status"))) {
      throw SupportCampaignService.conflict("CAMPAIGN_NOT_ACCEPTING_SUPPORT");
    }
  }

  private Map<String, Object> caseRow(String id, boolean lock) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,status,claim_status FROM animal_case WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("CASE_NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String, Object> latestCampaign(String caseId, boolean lock) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM support_campaign WHERE case_id=? ORDER BY created_at DESC,id DESC LIMIT 1" + (lock ? " FOR UPDATE" : ""), caseId);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("CAMPAIGN_NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String, Object> campaign(String id, boolean lock) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM support_campaign WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("CAMPAIGN_NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String, Object> existing(String id, boolean lock) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM support_contribution WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("CONTRIBUTION_NOT_FOUND");
    return rows.getFirst();
  }

  private static BigDecimal amount(ContributionInput input) {
    if (input == null || input.amount() == null || input.amount().signum() <= 0 || input.amount().scale() > 2
        || input.amount().compareTo(MAX_AMOUNT) > 0) throw SupportCampaignService.bad("INVALID_AMOUNT");
    return input.amount().setScale(2);
  }

  private static String idempotencyKey(String raw) {
    if (raw == null) throw SupportCampaignService.bad("VALIDATION_ERROR");
    String key = raw.trim();
    if (key.isEmpty() || key.length() > 128 || key.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
      throw SupportCampaignService.bad("VALIDATION_ERROR");
    }
    return key;
  }

  private static String reason(VoidInput input) {
    if (input == null || input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000) {
      throw SupportCampaignService.bad("VALIDATION_ERROR");
    }
    return input.reason().trim();
  }

  private static String requestHash(BigDecimal amount) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(("amount=" + amount.toPlainString()).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static void page(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw SupportCampaignService.bad("VALIDATION_ERROR");
  }

  private static ContributionRecord record(Map<String, Object> row) {
    return new ContributionRecord(str(row, "id"), str(row, "campaign_id"), (BigDecimal) row.get("amount"), str(row, "status"), instant(row, "recorded_at"));
  }

  private static ContributorRecord contributorRecord(Map<String, Object> row) {
    return new ContributorRecord(str(row, "id"), str(row, "campaign_id"), (BigDecimal) row.get("amount"), str(row, "status"),
        instant(row, "recorded_at"), str(row, "void_reason"), instant(row, "voided_at"));
  }

  private static GovernanceRecord governanceRecord(Map<String, Object> row) {
    return new GovernanceRecord(str(row, "id"), str(row, "campaign_id"), str(row, "contributor_user_id"),
        (BigDecimal) row.get("amount"), str(row, "status"), instant(row, "recorded_at"), str(row, "voided_by_user_id"),
        str(row, "void_reason"), instant(row, "voided_at"));
  }

  private static Timestamp now() { return Timestamp.from(Instant.now()); }
  private static String str(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : value.toString(); }
  private static Instant instant(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : ((Timestamp) value).toInstant(); }

  public record ContributionInput(BigDecimal amount) {}
  public record VoidInput(String reason) {}
  public record CreateResult(ContributionRecord contribution, boolean created) {}
  public record ContributionRecord(String contributionId, String campaignId, BigDecimal amount, String status, Instant recordedAt) {}
  public record ContributorRecord(String contributionId, String campaignId, BigDecimal amount, String status, Instant recordedAt, String voidReason, Instant voidedAt) {}
  public record GovernanceRecord(String contributionId, String campaignId, String contributorUserId, BigDecimal amount, String status, Instant recordedAt, String voidedByUserId, String voidReason, Instant voidedAt) {}
  public record ContributionPage(List<GovernanceRecord> items, int page, int size, long total) {}
}
