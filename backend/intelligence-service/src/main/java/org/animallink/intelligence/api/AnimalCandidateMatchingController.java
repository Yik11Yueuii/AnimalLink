package org.animallink.intelligence.api;

import jakarta.validation.Valid;
import org.animallink.intelligence.application.CandidateMatchingService;
import org.animallink.intelligence.application.ObservationFinalizationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class AnimalCandidateMatchingController {
    private final CandidateMatchingService service;
    private final ObservationFinalizationService finalizationService;

    public AnimalCandidateMatchingController(CandidateMatchingService service,
                                             ObservationFinalizationService finalizationService) {
        this.service = service;
        this.finalizationService = finalizationService;
    }

    @PostMapping("/api/v1/ai/tasks/{taskId}/match-candidates")
    @ResponseStatus(HttpStatus.CREATED)
    MatchingDtos.MatchResponse match(@PathVariable("taskId") String taskId,
                                     @Valid @RequestBody(required = false) MatchingDtos.MatchRequest request) {
        Integer topK = request == null ? null : request.topK();
        return MatchingDtos.MatchResponse.from(service.match(taskId, topK,
                request == null ? null : request.experimentCode()));
    }

    @GetMapping("/api/v1/ai/matches/{matchingRecordId}")
    MatchingDtos.MatchResponse get(@PathVariable("matchingRecordId") String matchingRecordId) {
        return MatchingDtos.MatchResponse.from(service.getOwned(matchingRecordId));
    }

    @PostMapping("/api/v1/ai/matches/{matchingRecordId}/finalize")
    ObservationFinalizationDtos.FinalizeResponse finalizeObservation(
            @PathVariable("matchingRecordId") String matchingRecordId,
            @Valid @RequestBody ObservationFinalizationDtos.FinalizeRequest request) {
        var decision = finalizationService.finalizeDecision(matchingRecordId, request.toCommand());
        return ObservationFinalizationDtos.FinalizeResponse.from(
                decision, service.getOwned(matchingRecordId));
    }

    @GetMapping("/api/v1/ai/matches/{matchingRecordId}/decision")
    ObservationFinalizationDtos.DecisionResponse decision(
            @PathVariable("matchingRecordId") String matchingRecordId) {
        var decision = finalizationService.getOwnedDecision(matchingRecordId);
        return ObservationFinalizationDtos.DecisionResponse.from(
                decision, service.getOwned(matchingRecordId));
    }
}
