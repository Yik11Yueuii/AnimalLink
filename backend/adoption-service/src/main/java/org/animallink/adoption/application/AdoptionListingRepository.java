package org.animallink.adoption.application;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.animallink.adoption.domain.AdoptionListing;
public interface AdoptionListingRepository {
    void insert(AdoptionListing listing);
    Optional<AdoptionListing> findById(String id);
    Optional<AdoptionListing> findByIdForUpdate(String id);
    List<AdoptionListing> findPublished(int limit, int offset);
    long countPublished();
    boolean publish(String id, Instant updatedAt, Instant publishedAt);
    boolean close(String id, Instant updatedAt, Instant closedAt);
}
