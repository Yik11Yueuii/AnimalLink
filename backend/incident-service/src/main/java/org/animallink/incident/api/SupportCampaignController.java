package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.animallink.incident.api.SupportContributionService.ContributionInput;
import org.animallink.incident.api.SupportContributionService.ContributionPage;
import org.animallink.incident.api.SupportContributionService.ContributorRecord;
import org.animallink.incident.api.SupportContributionService.GovernanceRecord;
import org.animallink.incident.api.SupportContributionService.VoidInput;
import org.springframework.http.ResponseEntity;
import org.animallink.incident.api.SupportCampaignService.AdminDetail;
import org.animallink.incident.api.SupportCampaignService.ApplicantApplication;
import org.animallink.incident.api.SupportCampaignService.DraftInput;
import org.animallink.incident.api.SupportCampaignService.Page;
import org.animallink.incident.api.SupportCampaignService.PublicCampaign;
import org.animallink.incident.api.SupportCampaignService.ReasonInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@RestController
@RequestMapping("/api/v1")
public class SupportCampaignController {
  private final SupportCampaignService service;
  private final SupportLifecycleService lifecycle;
  private final SupportContributionService contributions;
  private final RestClient identity;
  private final HttpServletRequest request;

  public SupportCampaignController(SupportCampaignService service, SupportLifecycleService lifecycle, SupportContributionService contributions, @Qualifier("identityRestClient") RestClient identity, HttpServletRequest request) {
    this.service = service;
    this.lifecycle = lifecycle;
    this.contributions = contributions;
    this.identity = identity;
    this.request = request;
  }

  @PostMapping("/cases/{caseId}/support")
  public ApplicantApplication create(@PathVariable("caseId") String caseId, @RequestBody DraftInput body) {
    String actor = user().id();
    volunteer(actor, service.context(caseId).campusId());
    return service.create(caseId, actor, body);
  }

  @PatchMapping("/cases/{caseId}/support/draft")
  public ApplicantApplication patch(@PathVariable("caseId") String caseId, @RequestBody DraftInput body) {
    String actor = user().id();
    volunteer(actor, service.context(caseId).campusId());
    return service.patch(caseId, actor, body);
  }

  @PostMapping("/cases/{caseId}/support/submit")
  public ApplicantApplication submit(@PathVariable("caseId") String caseId) {
    String actor = user().id();
    volunteer(actor, service.context(caseId).campusId());
    return service.submit(caseId, actor);
  }

  @PostMapping("/cases/{caseId}/support/cancel")
  public ApplicantApplication withdraw(@PathVariable("caseId") String caseId, @RequestBody(required = false) ReasonInput body) {
    return service.withdraw(caseId, user().id(), body == null ? null : body.reason());
  }

  @GetMapping("/cases/{caseId}/support")
  public PublicCampaign publicRead(@PathVariable("caseId") String caseId) { return service.publicRead(caseId); }

  @GetMapping("/cases/{caseId}/support/application")
  public ApplicantApplication applicantRead(@PathVariable("caseId") String caseId) { return service.applicantRead(caseId, user().id()); }

  @PostMapping("/cases/{caseId}/support/contributions")
  public ResponseEntity<?> contribute(@PathVariable("caseId") String caseId,
                                      @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @RequestBody ContributionInput body) {
    var result = contributions.create(caseId, user().id(), key, body);
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.contribution());
  }

  @GetMapping("/cases/{caseId}/support/contributions/me")
  public java.util.List<ContributorRecord> ownContributions(@PathVariable("caseId") String caseId) {
    return contributions.ownHistory(caseId, user().id());
  }

  @GetMapping("/admin/support-campaigns")
  public Page queue(@RequestParam(name = "status", defaultValue = "PENDING_REVIEW") String status,
                    @RequestParam(name = "page", defaultValue = "0") int page,
                    @RequestParam(name = "size", defaultValue = "20") int size) {
    admin();
    return service.adminQueue(status, page, size);
  }

  @GetMapping("/admin/support-campaigns/{id}")
  public AdminDetail detail(@PathVariable("id") String id) { admin(); return service.adminDetail(id); }

  @GetMapping("/admin/support-campaigns/{id}/contributions")
  public ContributionPage contributions(@PathVariable("id") String id,
                                        @RequestParam(name = "page", defaultValue = "0") int page,
                                        @RequestParam(name = "size", defaultValue = "20") int size) {
    admin();
    return contributions.governanceLedger(id, page, size);
  }

  @PostMapping("/admin/support-contributions/{id}/void")
  public GovernanceRecord voidContribution(@PathVariable("id") String id, @RequestBody VoidInput body) {
    return contributions.voidContribution(id, admin(), body);
  }

  @PostMapping("/admin/support-campaigns/{id}/approve")
  public AdminDetail approve(@PathVariable("id") String id, @RequestBody(required = false) ReasonInput body) {
    String actor = admin();
    return service.adminTransition(id, actor, "APPROVE", body == null ? null : body.reason());
  }

  @PostMapping("/admin/support-campaigns/{id}/reject")
  public AdminDetail reject(@PathVariable("id") String id, @RequestBody(required = false) ReasonInput body) {
    String actor = admin();
    return service.adminTransition(id, actor, "REJECT", body == null ? null : body.reason());
  }

  @PostMapping("/admin/support-campaigns/{id}/publish")
  public AdminDetail publish(@PathVariable("id") String id) { return service.adminTransition(id, admin(), "PUBLISH", null); }

  @PostMapping("/admin/support-campaigns/{id}/cancel")
  public AdminDetail adminCancel(@PathVariable("id") String id, @RequestBody(required = false) ReasonInput body) {
    String actor = admin();
    return service.adminTransition(id, actor, "ADMIN_CANCEL", body == null ? null : body.reason());
  }

  @PostMapping("/admin/support-campaigns/{id}/complete")
  public AdminDetail complete(@PathVariable("id") String id, @RequestBody(required = false) ReasonInput body) {
    String actor = admin();
    lifecycle.completeManually(id, actor, body == null ? null : body.reason());
    return service.adminDetail(id);
  }

  private String admin() {
    Principal p = user();
    if (!"GOVERNANCE_ADMIN".equals(p.role())) throw SupportCampaignService.forbidden("FINANCIAL_ACCESS_DENIED");
    return p.id();
  }

  private Principal user() {
    String header = request.getHeader("X-User-Id");
    if (header == null || header.isBlank()) throw new EventController.Unauthorized("需要登录");
    try {
      Map<?, ?> body = identity.get().uri("/api/v1/users/me").header("X-User-Id", header).retrieve().body(Map.class);
      if (body == null || body.get("id") == null || !header.equals(body.get("id"))) throw new EventController.Unauthorized("需要登录");
      if (!"ACTIVE".equals(body.get("accountStatus"))) throw new EventController.Unauthorized("需要登录");
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

  private record Principal(String id, String role) {}
}
