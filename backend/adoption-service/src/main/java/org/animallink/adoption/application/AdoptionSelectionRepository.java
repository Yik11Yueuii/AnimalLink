package org.animallink.adoption.application;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.animallink.adoption.domain.AdoptionSelection;

public interface AdoptionSelectionRepository {
    void insert(AdoptionSelection selection);
    Optional<AdoptionSelection> findById(String id);
    Optional<AdoptionSelection> findByIdForUpdate(String id);
    Optional<AdoptionSelection> findActiveByListingId(String listingId);
    boolean cancel(String id, String userId, java.time.Instant at, String reason);
    Set<String> findActiveApplicationIds(Collection<String> applicationIds);
}
