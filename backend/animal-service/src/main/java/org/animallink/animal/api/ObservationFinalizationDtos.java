package org.animallink.animal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.animallink.animal.application.*;
import org.animallink.animal.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ObservationFinalizationDtos {
    private ObservationFinalizationDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InternalFinalizeRequest(
            @NotBlank @Size(max = 36) String userId,
            @NotBlank @Size(max = 36) String campusId,
            @NotBlank @Size(max = 36) String sourceAiTaskId,
            @NotBlank @Size(max = 36) String sourceMatchingRecordId,
            @NotBlank @Size(max = 32) String decisionType,
            @Size(max = 36) String selectedAnimalId,
            @NotBlank @Size(max = 2000) String postText,
            @NotEmpty @Size(max = 6) List<@NotBlank @Size(max = 512) String> mediaObjectKeys,
            @NotNull @Valid ConfirmedDraftRequest confirmedDraft) {
        ObservationFinalizationCommand toCommand() {
            return new ObservationFinalizationCommand(userId, campusId, sourceAiTaskId,
                    sourceMatchingRecordId, decisionType, selectedAnimalId, postText,
                    mediaObjectKeys, confirmedDraft.toCommand());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConfirmedDraftRequest(
            String species,
            String sex,
            @Size(max = 120) String coatColor,
            @Size(max = 20) List<@Size(max = 200) String> distinctiveFeatures,
            @Size(max = 500) String visibleCondition,
            @Size(max = 500) String behavior,
            Integer estimatedCount,
            Boolean possibleAbnormality,
            List<String> abnormalFlags,
            @Size(max = 255) String locationDescription,
            Instant occurredAt,
            Double confidence,
            Map<String, Double> fieldConfidence,
            List<String> warnings,
            List<String> unknownFields) {
        ObservationFinalizationCommand.ConfirmedDraft toCommand() {
            return new ObservationFinalizationCommand.ConfirmedDraft(species, sex, coatColor,
                    distinctiveFeatures, visibleCondition, behavior, estimatedCount,
                    possibleAbnormality, abnormalFlags, locationDescription, occurredAt,
                    confidence, fieldConfidence, warnings, unknownFields);
        }
    }

    public record InternalFinalizeResponse(String postId, String proposalId, String animalId) {
        static InternalFinalizeResponse from(ObservationFinalizationResult result) {
            return new InternalFinalizeResponse(result.postId(), result.proposalId(),
                    result.animalId());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ApproveCreateRequest(
            @NotBlank @Size(max = 80) String displayName,
            @NotNull AnimalSpecies species,
            AnimalSex sex,
            @Size(max = 120) String coatColor,
            @Size(max = 1000) String distinctiveFeatures,
            @Size(max = 2000) String description,
            @Size(max = 255) String typicalArea) {
        ApproveProposalCommand toCommand() {
            return new ApproveProposalCommand(displayName, species, sex, coatColor,
                    distinctiveFeatures, description, typicalArea);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record LinkExistingRequest(
            @NotBlank @Size(max = 36) String animalId,
            @Size(max = 1000) String reviewReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RejectRequest(@NotBlank @Size(max = 1000) String reviewReason) {
    }

    public record ProposalResponse(
            String id,
            String campusId,
            String createdByUserId,
            String sourceAiTaskId,
            String sourceMatchingRecordId,
            String postId,
            String proposedSpecies,
            String proposedSex,
            String proposedCoatColor,
            String proposedDistinctiveFeatures,
            String proposedDescription,
            AnimalIdentityProposalStatus status,
            Instant reviewedAt,
            String reviewedBy,
            String resolutionAnimalId,
            String reviewReason,
            List<CommunityDtos.MediaResponse> media,
            Instant createdAt,
            Instant updatedAt) {
        static ProposalResponse from(AnimalIdentityProposalDetail detail) {
            AnimalIdentityProposal value = detail.proposal();
            return from(value, detail.media().stream()
                    .map(CommunityDtos.MediaResponse::from).toList());
        }

        static ProposalResponse summary(AnimalIdentityProposal value) {
            return from(value, List.of());
        }

        private static ProposalResponse from(AnimalIdentityProposal value,
                                             List<CommunityDtos.MediaResponse> media) {
            return new ProposalResponse(value.id(), value.campusId(),
                    value.createdByUserId(), value.sourceAiTaskId(),
                    value.sourceMatchingRecordId(), value.postId(),
                    value.proposedSpecies(), value.proposedSex(),
                    value.proposedCoatColor(), value.proposedDistinctiveFeatures(),
                    value.proposedDescription(), value.status(), value.reviewedAt(),
                    value.reviewedBy(), value.resolutionAnimalId(),
                    value.reviewReason(), media, value.createdAt(), value.updatedAt());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }
}
