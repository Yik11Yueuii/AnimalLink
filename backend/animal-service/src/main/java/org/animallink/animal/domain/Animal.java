package org.animallink.animal.domain;

import java.time.Instant;
import java.util.UUID;

public record Animal(
        String id,
        String campusId,
        String displayName,
        AnimalSpecies species,
        AnimalSex sex,
        String coatColor,
        String distinctiveFeatures,
        String description,
        SterilizationStatus sterilizationStatus,
        String typicalArea,
        IdentityStatus identityStatus,
        AdoptionStatus adoptionStatus,
        CurrentContext currentContext,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public static Animal create(String campusId, String displayName, AnimalSpecies species,
                                AnimalSex sex, String coatColor, String distinctiveFeatures,
                                String description, SterilizationStatus sterilizationStatus,
                                String typicalArea) {
        Instant now = Instant.now();
        return new Animal(UUID.randomUUID().toString(), campusId, required(displayName), species,
                sex == null ? AnimalSex.UNKNOWN : sex, optional(coatColor),
                optional(distinctiveFeatures), optional(description),
                sterilizationStatus == null ? SterilizationStatus.UNKNOWN : sterilizationStatus,
                optional(typicalArea), IdentityStatus.ACTIVE, AdoptionStatus.NOT_OPEN,
                CurrentContext.CAMPUS, 0, now, now);
    }

    public Animal correct(String newDisplayName, AnimalSpecies newSpecies, AnimalSex newSex,
                          String newCoatColor, String newDistinctiveFeatures, String newDescription,
                          SterilizationStatus newSterilizationStatus, String newTypicalArea) {
        requireActive("只有 ACTIVE Animal 可以修正主档");
        return new Animal(id, campusId,
                newDisplayName == null ? displayName : required(newDisplayName),
                newSpecies == null ? species : newSpecies,
                newSex == null ? sex : newSex,
                newCoatColor == null ? coatColor : optional(newCoatColor),
                newDistinctiveFeatures == null ? distinctiveFeatures : optional(newDistinctiveFeatures),
                newDescription == null ? description : optional(newDescription),
                newSterilizationStatus == null ? sterilizationStatus : newSterilizationStatus,
                newTypicalArea == null ? typicalArea : optional(newTypicalArea),
                identityStatus, adoptionStatus, currentContext, version, createdAt, Instant.now());
    }

    public Animal archive() {
        requireActive("Animal 已归档或不允许归档");
        return new Animal(id, campusId, displayName, species, sex, coatColor,
                distinctiveFeatures, description, sterilizationStatus, typicalArea,
                IdentityStatus.ARCHIVED, adoptionStatus, currentContext, version,
                createdAt, Instant.now());
    }

    private void requireActive(String message) {
        if (identityStatus != IdentityStatus.ACTIVE) {
            throw new StateConflictException(message);
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("displayName 不能为空");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
