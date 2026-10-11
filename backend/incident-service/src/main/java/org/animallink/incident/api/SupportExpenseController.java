package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.animallink.incident.api.SupportExpenseService.ExpenseInput;
import org.animallink.incident.api.SupportExpenseService.ExpensePage;
import org.animallink.incident.api.SupportExpenseService.GovernanceExpense;
import org.animallink.incident.api.SupportExpenseService.PrivateExpense;
import org.animallink.incident.api.SupportExpenseService.PublicExpense;
import org.animallink.incident.api.SupportExpenseService.ReasonInput;
import org.animallink.incident.infrastructure.SupportExpenseMediaStore;
import org.animallink.incident.infrastructure.SupportExpenseMediaStore.AccessUrl;
import org.animallink.incident.infrastructure.SupportExpenseMediaStore.UploadIntent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@RestController
@RequestMapping("/api/v1")
public class SupportExpenseController {
  private final SupportExpenseService expenses;
  private final SupportExpenseMediaStore media;
  private final RestClient identity;
  private final HttpServletRequest request;

  public SupportExpenseController(SupportExpenseService expenses, SupportExpenseMediaStore media,
                                  @Qualifier("identityRestClient") RestClient identity, HttpServletRequest request) {
    this.expenses = expenses;
    this.media = media;
    this.identity = identity;
    this.request = request;
  }

  @PostMapping("/cases/{caseId}/support/expenses/media/upload-url")
  public UploadIntent upload(@PathVariable("caseId") String caseId, @RequestBody UploadRequest body) {
    Principal principal = user();
    if (body == null || body.fileName() == null || body.fileName().isBlank() || body.fileName().length() > 128
        || body.contentType() == null || !SupportExpenseMediaStore.ALLOWED_TYPES.contains(body.contentType().trim().toLowerCase())
        || body.sizeBytes() < 1 || body.sizeBytes() > SupportExpenseMediaStore.MAX_IMAGE_BYTES) {
      throw SupportCampaignService.bad("EXPENSE_EVIDENCE_INVALID");
    }
    var context = expenses.submissionContext(caseId, principal.id());
    volunteer(principal.id(), context.campusId());
    try {
      return media.issueUpload(principal.id(), body.fileName(), body.contentType());
    } catch (SupportExpenseMediaStore.EvidenceInvalid ex) {
      throw SupportCampaignService.bad("EXPENSE_EVIDENCE_INVALID");
    } catch (SupportExpenseMediaStore.StorageUnavailable ex) {
      throw new EventController.Dependency("MinIO unavailable");
    }
  }

  @PostMapping("/cases/{caseId}/support/expenses")
  public ResponseEntity<PrivateExpense> create(@PathVariable("caseId") String caseId,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestBody ExpenseInput body) {
    Principal principal = user();
    var context = expenses.submissionContext(caseId, principal.id());
    volunteer(principal.id(), context.campusId());
    var result = expenses.create(caseId, principal.id(), key, body);
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.expense());
  }

  @GetMapping("/cases/{caseId}/support/expenses")
  public List<PublicExpense> publicList(@PathVariable("caseId") String caseId) {
    return expenses.publicExpenses(caseId);
  }

  @GetMapping("/cases/{caseId}/support/expenses/{expenseId}/private")
  public PrivateExpense privateDetail(@PathVariable("caseId") String caseId, @PathVariable("expenseId") String expenseId) {
    Principal principal = user();
    var result = expenses.privateDetail(caseId, expenseId, principal.id());
    if (result.volunteerRequired()) volunteer(principal.id(), result.campusId());
    return result.expense();
  }

  @GetMapping("/cases/{caseId}/support/expenses/{expenseId}/evidence/{mediaId}/access")
  public AccessUrl evidenceAccess(@PathVariable("caseId") String caseId, @PathVariable("expenseId") String expenseId,
                                  @PathVariable("mediaId") String mediaId) {
    String header = request.getHeader("X-User-Id");
    Principal principal = header == null || header.isBlank() ? null : user();
    var access = expenses.evidenceAccess(caseId, expenseId, mediaId, principal == null ? null : principal.id());
    if (access.volunteerRequired()) volunteer(principal.id(), access.campusId());
    return readUrl(access.objectKey());
  }

  @GetMapping("/admin/support-expenses")
  public ExpensePage queue(@RequestParam(name = "status", defaultValue = "SUBMITTED") String status,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           @RequestParam(name = "size", defaultValue = "20") int size) {
    admin();
    return expenses.governanceQueue(status, page, size);
  }

  @GetMapping("/admin/support-expenses/{expenseId}")
  public GovernanceExpense adminDetail(@PathVariable("expenseId") String expenseId) {
    admin();
    return expenses.governanceDetail(expenseId);
  }

  @PostMapping("/admin/support-expenses/{expenseId}/publish")
  public GovernanceExpense publish(@PathVariable("expenseId") String expenseId, @RequestBody(required = false) ReasonInput body) {
    return expenses.publish(expenseId, admin(), body);
  }

  @PostMapping("/admin/support-expenses/{expenseId}/reject")
  public GovernanceExpense reject(@PathVariable("expenseId") String expenseId, @RequestBody ReasonInput body) {
    return expenses.reject(expenseId, admin(), body);
  }

  @PostMapping("/admin/support-expenses/{expenseId}/void")
  public GovernanceExpense voidExpense(@PathVariable("expenseId") String expenseId, @RequestBody ReasonInput body) {
    return expenses.voidExpense(expenseId, admin(), body);
  }

  @GetMapping("/admin/support-expenses/{expenseId}/evidence/{mediaId}/access")
  public AccessUrl adminEvidenceAccess(@PathVariable("expenseId") String expenseId, @PathVariable("mediaId") String mediaId) {
    admin();
    return readUrl(expenses.adminEvidenceObjectKey(expenseId, mediaId));
  }

  private AccessUrl readUrl(String objectKey) {
    try {
      return media.readUrl(objectKey);
    } catch (SupportExpenseMediaStore.StorageUnavailable ex) {
      throw new EventController.Dependency("MinIO unavailable");
    }
  }

  private String admin() {
    Principal principal = user();
    if (!"GOVERNANCE_ADMIN".equals(principal.role())) throw SupportCampaignService.forbidden("FINANCIAL_ACCESS_DENIED");
    return principal.id();
  }

  private Principal user() {
    String header = request.getHeader("X-User-Id");
    if (header == null || header.isBlank()) throw new EventController.Unauthorized("需要登录");
    try {
      Map<?, ?> body = identity.get().uri("/api/v1/users/me").header("X-User-Id", header).retrieve().body(Map.class);
      if (body == null || !header.equals(body.get("id")) || !"ACTIVE".equals(body.get("accountStatus"))) {
        throw new EventController.Unauthorized("需要登录");
      }
      return new Principal(header, String.valueOf(body.get("systemRole")));
    } catch (EventController.Unauthorized ex) {
      throw ex;
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode().is4xxClientError()) throw new EventController.Unauthorized("需要登录");
      throw new EventController.Dependency("IDENTITY_SERVICE_UNAVAILABLE");
    } catch (Exception ex) {
      throw new EventController.Dependency("IDENTITY_SERVICE_UNAVAILABLE");
    }
  }

  private void volunteer(String actor, String campusId) {
    try {
      Map<?, ?> body = identity.get().uri("/internal/v1/users/{u}/campuses/{c}/volunteer-membership", actor, campusId)
          .header("X-Internal-Service", "incident-service").retrieve().body(Map.class);
      if (body == null || !Boolean.TRUE.equals(body.get("exists")) || !Boolean.TRUE.equals(body.get("active"))
          || !"ACTIVE".equals(body.get("status"))) throw SupportCampaignService.forbidden("ACTIVE_VOLUNTEER_REQUIRED");
    } catch (SupportCampaignService.Failure ex) {
      throw ex;
    } catch (Exception ex) {
      throw new EventController.Dependency("IDENTITY_SERVICE_UNAVAILABLE");
    }
  }

  public record UploadRequest(String fileName, String contentType, long sizeBytes) {}
  private record Principal(String id, String role) {}
}
