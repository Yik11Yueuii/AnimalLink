package org.animallink.intelligence.api;

import jakarta.validation.Valid;
import org.animallink.intelligence.application.CandidateMatchingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class AnimalCandidateMatchingController {
    private final CandidateMatchingService service;

    public AnimalCandidateMatchingController(CandidateMatchingService service) {
        this.service = service;
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
}
