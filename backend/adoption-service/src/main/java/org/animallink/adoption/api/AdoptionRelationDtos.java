package org.animallink.adoption.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.animallink.adoption.application.AdoptionRelationService;

public final class AdoptionRelationDtos {
    private AdoptionRelationDtos() { }

    public record EndRelationRequest(@NotBlank @Size(max = 500) String reason) { }

    public record GovernanceResponse(String relationId, String animalId, String status, Instant activatedAt, Instant endedAt, String endReason) {
        public static GovernanceResponse from(AdoptionRelationService.Result result) {
            var relation = result.relation();
            return new GovernanceResponse(relation.id(), relation.animalId(), relation.status(), relation.activatedAt(), relation.endedAt(), relation.endReason());
        }
    }
}
