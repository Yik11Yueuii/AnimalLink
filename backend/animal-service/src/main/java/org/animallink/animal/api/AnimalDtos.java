package org.animallink.animal.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.animallink.animal.application.AnimalDetail;
import org.animallink.animal.application.CorrectAnimalCommand;
import org.animallink.animal.application.CreateAnimalCommand;
import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalMedia;
import org.animallink.animal.domain.AnimalSex;
import org.animallink.animal.domain.AnimalSpecies;
import org.animallink.animal.domain.SterilizationStatus;
import org.animallink.animal.domain.TimelineEntry;

import java.time.Instant;
import java.util.List;

public final class AnimalDtos {
    private AnimalDtos() {
    }

    public record CreateAnimalRequest(
            @NotBlank @Size(max = 36) String campusId,
            @NotBlank @Size(max = 80) String displayName,
            @NotNull AnimalSpecies species,
            AnimalSex sex,
            @Size(max = 120) String coatColor,
            @Size(max = 1000) String distinctiveFeatures,
            @Size(max = 2000) String description,
            SterilizationStatus sterilizationStatus,
            @Size(max = 255) String typicalArea) {
        CreateAnimalCommand toCommand() {
            return new CreateAnimalCommand(campusId, displayName, species, sex, coatColor,
                    distinctiveFeatures, description, sterilizationStatus, typicalArea);
        }
    }

    public record CorrectAnimalRequest(
            @Size(max = 80) String displayName,
            AnimalSpecies species,
            AnimalSex sex,
            @Size(max = 120) String coatColor,
            @Size(max = 1000) String distinctiveFeatures,
            @Size(max = 2000) String description,
            SterilizationStatus sterilizationStatus,
            @Size(max = 255) String typicalArea) {
        CorrectAnimalCommand toCommand() {
            return new CorrectAnimalCommand(displayName, species, sex, coatColor,
                    distinctiveFeatures, description, sterilizationStatus, typicalArea);
        }
    }

    public record AnimalSummaryResponse(
            String id,
            String campusId,
            String displayName,
            AnimalSpecies species,
            AnimalSex sex,
            String coatColor,
            String distinctiveFeatures,
            String sterilizationStatus,
            String typicalArea,
            String identityStatus,
            String adoptionStatus,
            String currentContext,
            Instant createdAt,
            Instant updatedAt) {
        static AnimalSummaryResponse from(Animal animal) {
            return new AnimalSummaryResponse(animal.id(), animal.campusId(), animal.displayName(),
                    animal.species(), animal.sex(), animal.coatColor(), animal.distinctiveFeatures(),
                    animal.sterilizationStatus().name(), animal.typicalArea(),
                    animal.identityStatus().name(), animal.adoptionStatus().name(),
                    animal.currentContext().name(), animal.createdAt(), animal.updatedAt());
        }
    }

    public record AnimalResponse(
            String id,
            String campusId,
            String displayName,
            AnimalSpecies species,
            AnimalSex sex,
            String coatColor,
            String distinctiveFeatures,
            String description,
            String sterilizationStatus,
            String typicalArea,
            String identityStatus,
            String adoptionStatus,
            String currentContext,
            List<AnimalMediaResponse> media,
            Instant createdAt,
            Instant updatedAt) {
        static AnimalResponse from(AnimalDetail detail) {
            return from(detail.animal(), detail.media());
        }

        static AnimalResponse from(Animal animal) {
            return from(animal, List.of());
        }

        private static AnimalResponse from(Animal animal, List<AnimalMedia> media) {
            return new AnimalResponse(animal.id(), animal.campusId(), animal.displayName(),
                    animal.species(), animal.sex(), animal.coatColor(), animal.distinctiveFeatures(),
                    animal.description(), animal.sterilizationStatus().name(), animal.typicalArea(),
                    animal.identityStatus().name(), animal.adoptionStatus().name(),
                    animal.currentContext().name(), media.stream().map(AnimalMediaResponse::from).toList(),
                    animal.createdAt(), animal.updatedAt());
        }
    }

    public record AnimalMediaResponse(
            String id,
            String objectKey,
            String contentType,
            String mediaType,
            Long sizeBytes,
            int sortOrder,
            Instant createdAt) {
        static AnimalMediaResponse from(AnimalMedia media) {
            return new AnimalMediaResponse(media.id(), media.objectKey(), media.contentType(),
                    media.mediaType().name(), media.sizeBytes(), media.sortOrder(), media.createdAt());
        }
    }

    public record TimelineEntryResponse(
            String id,
            String animalId,
            String sourceType,
            String sourceId,
            String entryType,
            String title,
            String summary,
            Instant occurredAt,
            Instant createdAt) {
        static TimelineEntryResponse from(TimelineEntry entry) {
            return new TimelineEntryResponse(entry.id(), entry.animalId(), entry.sourceType(),
                    entry.sourceId(), entry.entryType(), entry.title(), entry.summary(),
                    entry.occurredAt(), entry.createdAt());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }
}
