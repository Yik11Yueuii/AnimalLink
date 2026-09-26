package org.animallink.animal.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.animallink.animal.application.AnimalCandidateQueryService;
import org.animallink.animal.domain.AnimalCandidateSnapshot;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/internal/v1/animals")
public class InternalAnimalCandidateController {
    private final AnimalCandidateQueryService service;

    public InternalAnimalCandidateController(AnimalCandidateQueryService service) {
        this.service = service;
    }

    @PostMapping("/candidates")
    CandidateBatchResponse candidates(@Valid @RequestBody CandidateBatchRequest request) {
        return new CandidateBatchResponse(service.recall(request.campusId(), request.species(), request.limit())
                .stream().map(CandidateSnapshotResponse::from).toList());
    }

    public record CandidateBatchRequest(
            @NotBlank @Size(max = 36) String campusId,
            @Size(max = 16) String species,
            @Min(20) @Max(100) int limit) {
    }

    public record CandidateBatchResponse(List<CandidateSnapshotResponse> candidates) {
    }

    public record CandidateSnapshotResponse(
            String id, String campusId, String displayName, String species, String sex,
            String coatColor, String distinctiveFeatures, String typicalArea,
            List<MediaResponse> media, Instant lastSeenAt, String recentSummary) {
        static CandidateSnapshotResponse from(AnimalCandidateSnapshot value) {
            return new CandidateSnapshotResponse(value.id(), value.campusId(), value.displayName(),
                    value.species().name(), value.sex().name(), value.coatColor(),
                    value.distinctiveFeatures(), value.typicalArea(),
                    value.media().stream().map(MediaResponse::from).toList(),
                    value.lastSeenAt(), value.recentSummary());
        }
    }

    public record MediaResponse(String id, String objectKey, String contentType) {
        static MediaResponse from(AnimalCandidateSnapshot.PublicMedia value) {
            return new MediaResponse(value.id(), value.objectKey(), value.contentType());
        }
    }
}
