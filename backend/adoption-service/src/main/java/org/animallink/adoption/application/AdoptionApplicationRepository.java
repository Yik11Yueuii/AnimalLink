package org.animallink.adoption.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.animallink.adoption.domain.AdoptionApplication;
import org.animallink.adoption.domain.ApplicationStatus;

public interface AdoptionApplicationRepository {
    boolean insertIfListingPublished(AdoptionApplication application);
    Optional<AdoptionApplication> findById(String id);
    List<AdoptionApplication> findByApplicant(String applicantUserId, int limit, int offset);
    long countByApplicant(String applicantUserId);
    boolean withdraw(String id, String applicantUserId, Instant updatedAt, Instant withdrawnAt);
    List<AdoptionApplication> findForGovernance(String listingId, ApplicationStatus status, int limit, int offset);
    long countForGovernance(String listingId, ApplicationStatus status);
    boolean review(String id, ApplicationStatus status, String reviewerUserId, Instant reviewedAt, String reviewComment, Instant updatedAt);
}
