package org.animallink.incident.api;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionFilter;
import org.animallink.incident.infrastructure.IncidentMiniappAssertionVerifier;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/internal/miniapp/cases/{caseId}")
public class InternalMiniappCaseResultController {
 private final CaseResultController cases; private final HttpServletRequest request;
 public InternalMiniappCaseResultController(CaseResultController cases,HttpServletRequest request){this.cases=cases;this.request=request;}
 @PostMapping("/result") public CaseResultController.ResultResponse result(@PathVariable("caseId") String id,@Valid @RequestBody Input body){return cases.submitForMiniapp(id,new CaseResultController.ResultRequest(body.outcome(),body.summary()),actor("incident.case-result.write"),body.campusId());}
 @PostMapping("/close") public CaseResultController.CloseResponse close(@PathVariable("caseId") String id,@Valid @RequestBody Campus body){return cases.closeForMiniapp(id,actor("incident.case-close.write"),body.campusId());}
 private String actor(String scope){Object p=request.getAttribute(IncidentMiniappAssertionFilter.PRINCIPAL);if(!(p instanceof IncidentMiniappAssertionVerifier.AuthenticatedMiniappUser u)||!scope.equals(u.scope()))throw new EventController.Unauthorized("Trusted miniapp identity required");return u.userId();}
 public record Input(@NotBlank String campusId,@NotBlank @Pattern(regexp="CONTINUE_OBSERVATION|RESOLVED|UNRESOLVED|CANCELLED") String outcome,@NotBlank @Size(max=2000) String summary){}
 public record Campus(@NotBlank String campusId){}
}
