package org.animallink.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CampusVerificationRepository {
    void insert(CampusVerification verification);

    Optional<CampusVerification> findVerificationById(String id);

    Optional<CampusVerification> findByIdForUpdate(String id);

    Optional<CampusVerification> findByIdAndUserId(String id, String userId);

    List<CampusVerification> findVerificationsByUserId(String userId);

    List<CampusVerification> findByStatus(VerificationStatus status, int limit, int offset);

    boolean existsPendingByUserAndCampus(String userId, String campusId);

    int updateReview(String id, int expectedVersion, VerificationStatus status,
                     String reason, String reviewerId, Instant reviewedAt);
}
