package org.animallink.adoption.application;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.animallink.adoption.domain.AdoptionSelection;

public interface AdoptionSelectionRepository {
    void insert(AdoptionSelection selection);
    Optional<AdoptionSelection> findActiveByListingId(String listingId);
    Set<String> findActiveApplicationIds(Collection<String> applicationIds);
}
