package org.animallink.incident.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.animallink.incident.infrastructure.SupportExpenseMediaStore;
import org.animallink.incident.infrastructure.SupportExpenseMediaStore.Candidate;
import org.animallink.incident.infrastructure.SupportExpenseMediaStore.Formalized;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SupportExpenseService {
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final SupportExpenseMediaStore mediaStore;

  public SupportExpenseService(JdbcTemplate jdbc, PlatformTransactionManager manager, SupportExpenseMediaStore mediaStore) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.mediaStore = mediaStore;
  }

  public SubmissionContext submissionContext(String caseId, String actor) {
    Map<String, Object> caseRow = caseRow(caseId, false);
    related(caseRow, actor);
    Map<String, Object> campaign = latestCampaign(caseId, false);
    accepting(caseRow, campaign);
    return new SubmissionContext(str(campaign, "id"), str(caseRow, "campus_id"));
  }

  public CreateResult create(String caseId, String actor, String rawKey, ExpenseInput input) {
    Normalized normalized = normalize(input);
    String key = idempotencyKey(rawKey);
    String hash = requestHash(normalized);
    @SuppressWarnings("unchecked")
    List<Formalized>[] copied = new List[]{null};
    try {
      return transactions.execute(status -> {
        Map<String, Object> caseRow = caseRow(caseId, true);
        related(caseRow, actor);
        Map<String, Object> campaign = latestCampaign(caseId, true);
        accepting(caseRow, campaign);
        String campaignId = str(campaign, "id");
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT * FROM support_expense WHERE campaign_id=? AND recorded_by_user_id=? AND idempotency_key=?",
            campaignId, actor, key);
        if (!existing.isEmpty()) return replay(existing.getFirst(), hash);

        validateReplacement(campaignId, normalized.replacesExpenseId());
        String expenseId = UUID.randomUUID().toString();
        List<Candidate> candidates = normalized.media().stream()
            .map(value -> new Candidate(UUID.randomUUID().toString(), value.temporaryObjectKey(), value.contentType(), value.sizeBytes(), value.sortOrder()))
            .toList();
        copied[0] = mediaStore.formalizeStrict(actor, campaignId, expenseId, candidates);
        Timestamp now = timestamp(Instant.now());
        jdbc.update("INSERT INTO support_expense(id,campaign_id,recorded_by_user_id,item,description,amount,status,idempotency_key,request_hash,occurred_at,created_at,replaces_expense_id) VALUES (?,?,?,?,?,?,'SUBMITTED',?,?,?,?,?)",
            expenseId, campaignId, actor, normalized.item(), normalized.description(), normalized.amount(), key, hash,
            timestamp(normalized.occurredAt()), now, normalized.replacesExpenseId());
        for (Formalized media : copied[0]) {
          jdbc.update("INSERT INTO support_expense_media(id,expense_id,object_key,content_type,size_bytes,sort_order,created_at) VALUES (?,?,?,?,?,?,?)",
              media.mediaId(), expenseId, media.objectKey(), media.contentType(), media.sizeBytes(), media.sortOrder(), now);
        }
        audit(campaignId, expenseId, "SUBMIT_EXPENSE", null, "SUBMITTED", actor, null, now);
        return new CreateResult(privateExpense(expense(expenseId, false)), true);
      });
    } catch (RuntimeException ex) {
      if (copied[0] != null) mediaStore.removeFormalized(copied[0]);
      if (ex instanceof SupportExpenseMediaStore.EvidenceInvalid) throw SupportCampaignService.bad("EXPENSE_EVIDENCE_INVALID");
      if (ex instanceof SupportExpenseMediaStore.StorageUnavailable) throw new EventController.Dependency("MinIO unavailable");
      throw ex;
    }
  }

  public List<PublicExpense> publicExpenses(String caseId) {
    Map<String, Object> campaign = latestCampaign(caseId, false);
    if (!"ACTIVE".equals(str(campaign, "status"))) throw SupportCampaignService.notFound("CAMPAIGN_NOT_FOUND");
    return jdbc.queryForList("SELECT * FROM support_expense WHERE campaign_id=? AND status='PUBLISHED' ORDER BY occurred_at DESC,id DESC",
        str(campaign, "id")).stream().map(this::publicExpense).toList();
  }

  public PrivateResult privateDetail(String caseId, String expenseId, String actor) {
    Map<String, Object> caseRow = caseRow(caseId, false);
    Map<String, Object> row = expenseForCase(caseId, expenseId);
    boolean submitter = actor.equals(str(row, "recorded_by_user_id"));
    if (!submitter) related(caseRow, actor);
    return new PrivateResult(privateExpense(row), str(caseRow, "campus_id"), !submitter);
  }

  public ExpensePage governanceQueue(String filter, int page, int size) {
    page(page, size);
    String status = filter == null || filter.isBlank() ? "SUBMITTED" : filter.trim();
    if (!List.of("SUBMITTED", "PUBLISHED", "REJECTED", "VOIDED").contains(status)) throw SupportCampaignService.bad("VALIDATION_ERROR");
    long total = jdbc.queryForObject("SELECT COUNT(*) FROM support_expense WHERE status=?", Long.class, status);
    List<GovernanceExpense> items = jdbc.queryForList("SELECT * FROM support_expense WHERE status=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
        status, size, (long) page * size).stream().map(this::governanceExpense).toList();
    return new ExpensePage(items, page, size, total);
  }

  public GovernanceExpense governanceDetail(String expenseId) {
    return governanceExpense(expense(expenseId, false));
  }

  public GovernanceExpense publish(String expenseId, String actor, ReasonInput input) {
    String reason = optionalReason(input);
    return transition(expenseId, actor, "SUBMITTED", "PUBLISHED", "PUBLISH_EXPENSE", reason);
  }

  public GovernanceExpense reject(String expenseId, String actor, ReasonInput input) {
    return transition(expenseId, actor, "SUBMITTED", "REJECTED", "REJECT_EXPENSE", requiredReason(input));
  }

  public GovernanceExpense voidExpense(String expenseId, String actor, ReasonInput input) {
    return transition(expenseId, actor, "PUBLISHED", "VOIDED", "VOID_EXPENSE", requiredReason(input));
  }

  public EvidenceAccess evidenceAccess(String caseId, String expenseId, String mediaId, String actor) {
    Map<String, Object> caseRow = caseRow(caseId, false);
    Map<String, Object> row = evidenceRow(caseId, expenseId, mediaId);
    boolean publicAccess = "PUBLISHED".equals(str(row, "status"))
        && List.of("ACTIVE", "COMPLETED", "SETTLED").contains(str(row, "campaign_status"));
    if (publicAccess) return new EvidenceAccess(str(row, "object_key"), str(caseRow, "campus_id"), false);
    if (actor == null) throw new EventController.Unauthorized("需要登录");
    boolean submitter = actor.equals(str(row, "recorded_by_user_id"));
    if (!submitter) related(caseRow, actor);
    return new EvidenceAccess(str(row, "object_key"), str(caseRow, "campus_id"), !submitter);
  }

  public String adminEvidenceObjectKey(String expenseId, String mediaId) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT object_key FROM support_expense_media WHERE expense_id=? AND id=?", expenseId, mediaId);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("EXPENSE_MEDIA_NOT_FOUND");
    return str(rows.getFirst(), "object_key");
  }

  private GovernanceExpense transition(String expenseId, String actor, String from, String to, String action, String reason) {
    List<Map<String, Object>> lookup = jdbc.queryForList("SELECT campaign_id FROM support_expense WHERE id=?", expenseId);
    if (lookup.isEmpty()) throw SupportCampaignService.notFound("EXPENSE_NOT_FOUND");
    String campaignId = str(lookup.getFirst(), "campaign_id");
    return transactions.execute(status -> {
      Map<String, Object> campaign = campaign(campaignId, true);
      if (!List.of("ACTIVE", "COMPLETED").contains(str(campaign, "status"))) {
        throw SupportCampaignService.conflict("EXPENSE_STATE_CONFLICT");
      }
      Map<String, Object> row = expense(expenseId, true);
      if (!from.equals(str(row, "status"))) throw SupportCampaignService.conflict("EXPENSE_STATE_CONFLICT");
      Timestamp now = timestamp(Instant.now());
      int changed;
      if ("VOIDED".equals(to)) {
        changed = jdbc.update("UPDATE support_expense SET status='VOIDED',voided_by_user_id=?,void_reason=?,voided_at=? WHERE id=? AND status='PUBLISHED'",
            actor, reason, now, expenseId);
      } else {
        changed = jdbc.update("UPDATE support_expense SET status=?,reviewed_by_user_id=?,review_reason=?,reviewed_at=? WHERE id=? AND status='SUBMITTED'",
            to, actor, reason, now, expenseId);
      }
      if (changed != 1) throw SupportCampaignService.conflict("EXPENSE_STATE_CONFLICT");
      audit(campaignId, expenseId, action, from, to, actor, reason, now);
      return governanceExpense(expense(expenseId, false));
    });
  }

  private CreateResult replay(Map<String, Object> row, String hash) {
    if (!hash.equals(str(row, "request_hash"))) throw SupportCampaignService.conflict("IDEMPOTENCY_KEY_REUSED");
    return new CreateResult(privateExpense(row), false);
  }

  private void validateReplacement(String campaignId, String replacementId) {
    if (replacementId == null) return;
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM support_expense WHERE id=? FOR UPDATE", replacementId);
    if (rows.isEmpty()) throw SupportCampaignService.bad("INVALID_EXPENSE_REPLACEMENT");
    Map<String, Object> replacement = rows.getFirst();
    if (!campaignId.equals(str(replacement, "campaign_id")) || !List.of("REJECTED", "VOIDED").contains(str(replacement, "status"))) {
      throw SupportCampaignService.bad("INVALID_EXPENSE_REPLACEMENT");
    }
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM support_expense WHERE replaces_expense_id=?", Integer.class, replacementId);
    if (count != null && count > 0) throw SupportCampaignService.conflict("INVALID_EXPENSE_REPLACEMENT");
  }

  private Normalized normalize(ExpenseInput input) {
    if (input == null || input.item() == null || input.item().isBlank() || input.item().length() > 200
        || input.description() == null || input.description().isBlank() || input.description().length() > 2000
        || input.occurredAt() == null) throw SupportCampaignService.bad("VALIDATION_ERROR");
    BigDecimal amount = amount(input.amount());
    List<MediaInput> values = input.media();
    if (values == null || values.isEmpty()) throw SupportCampaignService.bad("EXPENSE_EVIDENCE_REQUIRED");
    if (values.size() > 6) throw SupportCampaignService.bad("EXPENSE_EVIDENCE_INVALID");
    List<MediaInput> sorted = new ArrayList<>(values);
    sorted.sort(Comparator.comparingInt(MediaInput::sortOrder));
    Set<String> keys = new HashSet<>();
    long total = 0;
    for (int index = 0; index < sorted.size(); index++) {
      MediaInput value = sorted.get(index);
      if (value == null || value.temporaryObjectKey() == null || value.temporaryObjectKey().isBlank()
          || value.temporaryObjectKey().length() > 512 || !keys.add(value.temporaryObjectKey()) || value.sortOrder() != index
          || value.sizeBytes() < 1 || value.sizeBytes() > SupportExpenseMediaStore.MAX_IMAGE_BYTES
          || !SupportExpenseMediaStore.ALLOWED_TYPES.contains(normalizeType(value.contentType()))) {
        throw SupportCampaignService.bad("EXPENSE_EVIDENCE_INVALID");
      }
      total += value.sizeBytes();
      if (total > SupportExpenseMediaStore.MAX_TOTAL_BYTES) throw SupportCampaignService.bad("EXPENSE_EVIDENCE_INVALID");
    }
    List<MediaInput> normalizedMedia = sorted.stream()
        .map(value -> new MediaInput(value.temporaryObjectKey(), normalizeType(value.contentType()), value.sizeBytes(), value.sortOrder())).toList();
    String replacement = blankToNull(input.replacesExpenseId());
    if (replacement != null && replacement.length() > 36) throw SupportCampaignService.bad("INVALID_EXPENSE_REPLACEMENT");
    return new Normalized(input.item().trim(), input.description().trim(), amount,
        input.occurredAt().truncatedTo(ChronoUnit.MICROS), replacement, normalizedMedia);
  }

  private static BigDecimal amount(BigDecimal value) {
    if (value == null || value.signum() <= 0 || value.scale() > 2 || value.compareTo(MAX_AMOUNT) > 0) {
      throw SupportCampaignService.bad("INVALID_AMOUNT");
    }
    return value.setScale(2);
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
      field(output, value.item());
      field(output, value.description());
      field(output, value.amount().toPlainString());
      output.writeLong(value.occurredAt().getEpochSecond());
      output.writeInt(value.occurredAt().getNano());
      field(output, value.replacesExpenseId() == null ? "<null>" : value.replacesExpenseId());
      output.writeInt(value.media().size());
      for (MediaInput media : value.media()) {
        field(output, media.temporaryObjectKey());
        field(output, media.contentType());
        output.writeLong(media.sizeBytes());
        output.writeInt(media.sortOrder());
      }
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

  private void accepting(Map<String, Object> caseRow, Map<String, Object> campaign) {
    if (!"ACTIVE".equals(str(campaign, "status")) || !"ACTIVE".equals(str(caseRow, "status"))
        || !"CLAIMED".equals(str(caseRow, "claim_status"))) {
      throw SupportCampaignService.conflict("CAMPAIGN_NOT_ACCEPTING_EXPENSE");
    }
  }

  private void related(Map<String, Object> caseRow, String actor) {
    if (actor.equals(str(caseRow, "owner_user_id"))) return;
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM case_participant WHERE case_id=? AND user_id=? AND status='ACTIVE'",
        Integer.class, str(caseRow, "id"), actor);
    if (count == null || count == 0) throw SupportCampaignService.forbidden("FINANCIAL_ACCESS_DENIED");
  }

  private Map<String, Object> caseRow(String id, boolean lock) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,campus_id,owner_user_id,status,claim_status FROM animal_case WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
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

  private Map<String, Object> expense(String id, boolean lock) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM support_expense WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("EXPENSE_NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String, Object> expenseForCase(String caseId, String expenseId) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT se.* FROM support_expense se JOIN support_campaign sc ON sc.id=se.campaign_id WHERE se.id=? AND sc.case_id=?", expenseId, caseId);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("EXPENSE_NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String, Object> evidenceRow(String caseId, String expenseId, String mediaId) {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT se.status,se.recorded_by_user_id,sc.status campaign_status,sem.object_key FROM support_expense_media sem JOIN support_expense se ON se.id=sem.expense_id JOIN support_campaign sc ON sc.id=se.campaign_id WHERE sem.id=? AND se.id=? AND sc.case_id=?",
        mediaId, expenseId, caseId);
    if (rows.isEmpty()) throw SupportCampaignService.notFound("EXPENSE_MEDIA_NOT_FOUND");
    return rows.getFirst();
  }

  private PublicExpense publicExpense(Map<String, Object> row) {
    return new PublicExpense(str(row, "id"), str(row, "item"), str(row, "description"), (BigDecimal) row.get("amount"),
        instant(row, "occurred_at"), instant(row, "reviewed_at"), media(str(row, "id")));
  }

  private PrivateExpense privateExpense(Map<String, Object> row) {
    return new PrivateExpense(str(row, "id"), str(row, "campaign_id"), str(row, "recorded_by_user_id"), str(row, "item"),
        str(row, "description"), (BigDecimal) row.get("amount"), str(row, "status"), instant(row, "occurred_at"), instant(row, "created_at"),
        str(row, "review_reason"), instant(row, "reviewed_at"), str(row, "void_reason"), instant(row, "voided_at"),
        str(row, "replaces_expense_id"), media(str(row, "id")));
  }

  private GovernanceExpense governanceExpense(Map<String, Object> row) {
    String campaignId = str(row, "campaign_id");
    String caseId = jdbc.queryForObject("SELECT case_id FROM support_campaign WHERE id=?", String.class, campaignId);
    return new GovernanceExpense(str(row, "id"), campaignId, caseId, str(row, "recorded_by_user_id"), str(row, "item"),
        str(row, "description"), (BigDecimal) row.get("amount"), str(row, "status"), instant(row, "occurred_at"), instant(row, "created_at"),
        str(row, "reviewed_by_user_id"), str(row, "review_reason"), instant(row, "reviewed_at"), str(row, "voided_by_user_id"),
        str(row, "void_reason"), instant(row, "voided_at"), str(row, "replaces_expense_id"), media(str(row, "id")));
  }

  private List<MediaSummary> media(String expenseId) {
    return jdbc.queryForList("SELECT id,content_type,size_bytes,sort_order FROM support_expense_media WHERE expense_id=? ORDER BY sort_order,id", expenseId)
        .stream().map(row -> new MediaSummary(str(row, "id"), str(row, "content_type"), ((Number) row.get("size_bytes")).longValue(),
            ((Number) row.get("sort_order")).intValue())).toList();
  }

  private void audit(String campaignId, String expenseId, String action, String from, String to, String actor, String reason, Timestamp at) {
    jdbc.update("INSERT INTO support_audit_log(id,campaign_id,resource_type,resource_id,action,from_state,to_state,actor_user_id,reason,created_at) VALUES (?,?,'EXPENSE',?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), campaignId, expenseId, action, from, to, actor, reason, at);
  }

  private static String requiredReason(ReasonInput input) {
    if (input == null || input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000) {
      throw SupportCampaignService.bad("VALIDATION_ERROR");
    }
    return input.reason().trim();
  }

  private static String optionalReason(ReasonInput input) {
    if (input == null || input.reason() == null || input.reason().isBlank()) return null;
    if (input.reason().length() > 1000) throw SupportCampaignService.bad("VALIDATION_ERROR");
    return input.reason().trim();
  }

  private static void page(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw SupportCampaignService.bad("VALIDATION_ERROR");
  }

  private static String normalizeType(String value) {
    return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
  }

  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private static Timestamp timestamp(Instant value) { return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }
  private static String str(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : value.toString(); }
  private static Instant instant(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? null : ((Timestamp) value).toInstant(); }

  private record Normalized(String item, String description, BigDecimal amount, Instant occurredAt, String replacesExpenseId, List<MediaInput> media) {}
  public record ExpenseInput(String item, String description, BigDecimal amount, Instant occurredAt, String replacesExpenseId, List<MediaInput> media) {}
  public record MediaInput(String temporaryObjectKey, String contentType, long sizeBytes, int sortOrder) {}
  public record ReasonInput(String reason) {}
  public record SubmissionContext(String campaignId, String campusId) {}
  public record MediaSummary(String mediaId, String contentType, long sizeBytes, int sortOrder) {}
  public record PublicExpense(String expenseId, String item, String description, BigDecimal amount, Instant occurredAt, Instant publishedAt, List<MediaSummary> media) {}
  public record PrivateExpense(String expenseId, String campaignId, String recordedByUserId, String item, String description, BigDecimal amount,
                               String status, Instant occurredAt, Instant createdAt, String reviewReason, Instant reviewedAt,
                               String voidReason, Instant voidedAt, String replacesExpenseId, List<MediaSummary> media) {}
  public record GovernanceExpense(String expenseId, String campaignId, String caseId, String recordedByUserId, String item, String description,
                                  BigDecimal amount, String status, Instant occurredAt, Instant createdAt, String reviewedByUserId,
                                  String reviewReason, Instant reviewedAt, String voidedByUserId, String voidReason, Instant voidedAt,
                                  String replacesExpenseId, List<MediaSummary> media) {}
  public record CreateResult(PrivateExpense expense, boolean created) {}
  public record PrivateResult(PrivateExpense expense, String campusId, boolean volunteerRequired) {}
  public record ExpensePage(List<GovernanceExpense> items, int page, int size, long total) {}
  public record EvidenceAccess(String objectKey, String campusId, boolean volunteerRequired) {}
}
