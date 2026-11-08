package org.animallink.intelligence.api;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.animallink.intelligence.application.*;
import org.animallink.intelligence.domain.*;
import org.animallink.intelligence.infrastructure.IntelligenceMiniappAssertionFilter;
import org.animallink.intelligence.infrastructure.IntelligenceMiniappAssertionVerifier;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/internal/v1/miniapp")
public class InternalMiniappMatchingController {
 private final CandidateMatchingService matching; private final ObservationFinalizationService finalize; private final ObservationParsingService observations; private final CampusMembershipAuthorization authorization; private final MatchingRepository repository; private final HttpServletRequest request;
 public InternalMiniappMatchingController(CandidateMatchingService matching,ObservationFinalizationService finalize,ObservationParsingService observations,CampusMembershipAuthorization authorization,MatchingRepository repository,HttpServletRequest request){this.matching=matching;this.finalize=finalize;this.observations=observations;this.authorization=authorization;this.repository=repository;this.request=request;}
 @PostMapping("/observations/{taskId}/match-candidates") public MatchingDtos.MatchResponse match(@PathVariable("taskId") String id,@Valid @RequestBody Campus body){actor("intelligence.match.write");checkTask(id,body.campusId());return MatchingDtos.MatchResponse.from(matching.match(id,null,null));}
 @GetMapping("/observations/{taskId}/decision") @org.springframework.transaction.annotation.Transactional(readOnly=true) public State taskDecision(@PathVariable("taskId") String id,@RequestParam("campusId") String campus){actor("intelligence.match.read");checkTask(id,campus);var done=repository.findDecisionByAiTaskId(id);if(done.isPresent()){var d=done.get();return new State("FINALIZED",d.matchingRecordId(),ObservationFinalizationDtos.FinalizeResponse.from(d,matching.getOwned(d.matchingRecordId())),null);}var pending=repository.findIntentByAiTaskId(id);return pending.map(p->new State("PENDING",p.matchingRecordId(),null,new Intent(p.decisionType(),p.selectedAnimalId(),p.postText()))).orElseGet(()->new State("NONE",null,null,null));}
 @GetMapping("/matches/{id}") public MatchingDtos.MatchResponse get(@PathVariable("id") String id,@RequestParam("campusId") String campus){actor("intelligence.match.read");return MatchingDtos.MatchResponse.from(checkMatch(id,campus));}
 @GetMapping("/matches/{id}/decision") public ObservationFinalizationDtos.FinalizeResponse decision(@PathVariable("id") String id,@RequestParam("campusId") String campus){actor("intelligence.match.read");var record=checkMatch(id,campus);return ObservationFinalizationDtos.FinalizeResponse.from(finalize.getOwnedDecision(id),record);}
 @PostMapping("/matches/{id}/finalize") public ObservationFinalizationDtos.FinalizeResponse finish(@PathVariable("id") String id,@Valid @RequestBody Input body){actor("intelligence.match.write");var record=checkMatch(id,body.campusId());var decision=finalize.finalizeDecision(id,new FinalizeObservationCommand(body.decisionType(),body.selectedAnimalId(),body.postText()));return ObservationFinalizationDtos.FinalizeResponse.from(decision,record);}
 private void checkTask(String id,String campus){var view=observations.getOwned(id);if(!view.task().campusId().equals(campus))throw new ApiExceptions.Forbidden("CAMPUS_MISMATCH");authorization.requireActiveMember(campus);}
 private MatchingRecord checkMatch(String id,String campus){var record=matching.getOwned(id);if(!record.campusId().equals(campus))throw new ApiExceptions.Forbidden("CAMPUS_MISMATCH");authorization.requireActiveMember(campus);return record;}
 private void actor(String scope){Object v=request.getAttribute(IntelligenceMiniappAssertionFilter.PRINCIPAL);if(!(v instanceof IntelligenceMiniappAssertionVerifier.AuthenticatedMiniappUser u)||!scope.equals(u.scope()))throw new ApiExceptions.Unauthorized("需要可信用户身份");}
 record Campus(@NotBlank String campusId){} record Input(@NotBlank String campusId,@NotNull MatchingDecisionType decisionType,@Size(max=36) String selectedAnimalId,@Size(max=2000) String postText){} record Intent(MatchingDecisionType decisionType,String selectedAnimalId,String postText){} record State(String state,String matchingRecordId,ObservationFinalizationDtos.FinalizeResponse result,Intent intent){}
}
