package org.animallink.adoption.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.animallink.adoption.domain.AdoptionApplication;

public interface AdoptionApplicationRepository {
    boolean insertIfListingPublished(AdoptionApplication application);
    Optional<AdoptionApplication> findById(String id);
    List<AdoptionApplication> findByApplicant(String applicantUserId, int limit, int offset);
    long countByApplicant(String applicantUserId);
    boolean withdraw(String id, Instant updatedAt, Instant withdrawnAt);
}
