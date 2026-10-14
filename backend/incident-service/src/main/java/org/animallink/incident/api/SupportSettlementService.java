package org.animallink.incident.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable recorded-ledger settlement. This service does not perform payment processing. */
@Service
public class SupportSettlementService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public SupportSettlementService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public SettleResult settle(String campaignId, String actor, String rawKey, SettlementInput input) {
    String key = idempotencyKey(rawKey);
    Normalized normalized = normalize(input);
    String hash = requestHash(normalized);
    return transactions.execute(status -> {
      Map<String, Object> campaign = campaign(campaignId, true);
      Map<String, Object> existing = settlement(campaignId);
      if (existing != null) return replay(existing, key, hash);
      if (!"COMPLETED".equals(text(campaign, "status"))) {
        throw SupportCampaignService.conflict("CAMPAIGN_STATE_CONFLICT");
      }
      Integer pending = jdbc.queryForObject(
          "SELECT COUNT(*) FROM support_expense WHERE campaign_id=? AND status='SUBMITTED'", Integer.class, campaignId);
      if (pending != null && pending > 0) throw SupportCampaignService.conflict("SETTLEMENT_PENDING_EXPENSE");

      BigDecimal contributed = money(jdbc.queryForObject(
          "SELECT COALESCE(SUM(amount),0.00) FROM support_contribution WHERE campaign_id=? AND status='RECORDED'",
          BigDecimal.class, campaignId));
      BigDecimal expenses = money(jdbc.queryForObject(
          "SELECT COALESCE(SUM(amount),0.00) FROM support_expense WHERE campaign_id=? AND status='PUBLISHED'",
          BigDecimal.class, campaignId));
      BigDecimal net = contributed.subtract(expenses).setScale(2);
      String settlementId = UUID.randomUUID().toString();
      Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
      jdbc.update("INSERT INTO support_settlement(id,campaign_id,total_contributed,total_expenses,net_balance,summary,disposition_note,settled_by_user_id,idempotency_key,request_hash,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
          settlementId, campaignId, contributed, expenses, net, normalized.summary(), normalized.dispositionNote(), actor, key, hash, now);
      int changed = jdbc.update(
          "UPDATE support_campaign SET status='SETTLED',version=version+1,updated_at=? WHERE id=? AND status='COMPLETED' AND version=?",
          now, campaignId, campaign.get("version"));
      if (changed != 1) throw SupportCampaignService.conflict("CAMPAIGN_STATE_CONFLICT");
      jdbc.update("INSERT INTO support_audit_log(id,campaign_id,resource_type,resource_id,action,from_state,to_state,actor_user_id,reason,created_at) VALUES (?,?,'CAMPAIGN',?,'SETTLE','COMPLETED','SETTLED',?,NULL,?)",
          UUID.randomUUID().toString(), campaignId, campaignId, actor, now);
      return new SettleResult(governance(requireSettlement(campaignId)), true);
    });
  }

  public PublicSettlement publicRead(String caseId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT * FROM support_campaign WHERE case_id=? AND status IN ('ACTIVE','COMPLETED','SETTLED') ORDER BY created_at DESC,id DESC LIMIT 1",
        caseId);
    if (rows.isEmpty() || !"SETTLED".equals(text(rows.getFirst(), "status"))) {
      throw SupportCampaignService.notFound("SETTLEMENT_NOT_FOUND");
    }
    Map<String, Object> row = settlement(text(rows.getFirst(), "id"));
    if (row == null) throw SupportCampaignService.notFound("SETTLEMENT_NOT_FOUND");
    return publicProjection(row);
  }

  public GovernanceSettlement governanceForCampaign(String campaignId) {
    Map<String, Object> row = settlement(campaignId);
    return row == null ? null : governance(row);
  }

  private SettleResult replay(Map<String, Object> row, String key, String hash) {
    if (!key.equals(text(row, "idempotency_key"))) {
      throw SupportCampaignService.conflict("SETTLEMENT_ALREADY_EXISTS");
    }
    if (!hash.equals(text(row, "request_hash"))) {
      throw SupportCampaignService.conflict("IDEMPOTENCY_KEY_REUSED");
    }
    return new SettleResult(governance(row), false);
  }

  private Map<String, Object> campaign(String campaignId, boolean lock) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT * FROM support_campaign WHERE id=?" + (lock ? " FOR UPDATE" : ""), campaignId);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("CAMPAIGN_NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String, Object> settlement(String campaignId) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM support_settlement WHERE campaign_id=?", campaignId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Map<String, Object> requireSettlement(String campaignId) {
    Map<String, Object> row = settlement(campaignId);
    if (row == null) throw new IllegalStateException("settlement insert was not visible");
    return row;
  }

  private static Normalized normalize(SettlementInput input) {
    if (input == null) throw SupportCampaignService.bad("VALIDATION_ERROR");
    return new Normalized(narrative(input.summary()), narrative(input.dispositionNote()));
  }

  private static String narrative(String value) {
    if (value == null) {
      throw SupportCampaignService.bad("VALIDATION_ERROR");
    }
    String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > 2000) {
      throw SupportCampaignService.bad("VALIDATION_ERROR");
    }
    return normalized;
  }

  private static String idempotencyKey(String raw) {
    if (raw == null) throw SupportCampaignService.bad("VALIDATION_ERROR");
    String key = raw.trim();
    if (key.isEmpty() || key.length() > 128 || key.chars().anyMatch(value -> value < 0x21 || value > 0x7e)) {
      throw SupportCampaignService.bad("VALIDATION_ERROR");
    }
    return key;
  }

  private static String requestHash(Normalized value) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(buffer);
      field(output, value.summary());
      field(output, value.dispositionNote());
      output.flush();
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static void field(DataOutputStream output, String value) throws Exception {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static BigDecimal money(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value).setScale(2);
  }

  private static PublicSettlement publicProjection(Map<String, Object> row) {
    return new PublicSettlement(text(row, "id"), money((BigDecimal) row.get("total_contributed")),
        money((BigDecimal) row.get("total_expenses")), money((BigDecimal) row.get("net_balance")),
        text(row, "summary"), text(row, "disposition_note"), instant(row, "created_at"));
  }

  private static GovernanceSettlement governance(Map<String, Object> row) {
    return new GovernanceSettlement(text(row, "id"), money((BigDecimal) row.get("total_contributed")),
        money((BigDecimal) row.get("total_expenses")), money((BigDecimal) row.get("net_balance")),
        text(row, "summary"), text(row, "disposition_note"), instant(row, "created_at"),
        text(row, "settled_by_user_id"));
  }

  private static String text(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : value.toString();
  }

  private static Instant instant(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : ((Timestamp) value).toInstant();
  }

  private record Normalized(String summary, String dispositionNote) {}
  public record SettlementInput(String summary, String dispositionNote) {}
  public record SettleResult(GovernanceSettlement settlement, boolean created) {}
  public record PublicSettlement(String settlementId, BigDecimal totalContributed, BigDecimal totalExpenses,
                                 BigDecimal netBalance, String summary, String dispositionNote, Instant settledAt) {}
  public record GovernanceSettlement(String settlementId, BigDecimal totalContributed, BigDecimal totalExpenses,
                                     BigDecimal netBalance, String summary, String dispositionNote, Instant settledAt,
                                     String settledByUserId) {}
}
