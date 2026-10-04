package org.animallink.adoption.application;

import java.util.UUID;
import org.animallink.adoption.domain.AdoptionListing;
import org.animallink.adoption.domain.AnimalNotEligibleException;
import org.animallink.adoption.domain.InvalidListingTransitionException;
import org.animallink.adoption.domain.ListingAccessDeniedException;
import org.animallink.adoption.domain.ListingNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdoptionListingService {
    private static final int MAX_PAGE_SIZE = 100;
    private final AdoptionListingRepository repository;
    private final AnimalGateway animalGateway;
    private final IdentityGateway identityGateway;

    public AdoptionListingService(AdoptionListingRepository repository, AnimalGateway animalGateway, IdentityGateway identityGateway) {
        this.repository = repository; this.animalGateway = animalGateway; this.identityGateway = identityGateway;
    }

    @Transactional
    public AdoptionListing create(String animalId, String title, String description) {
        IdentityGateway.CurrentUser publisher = requireGovernanceAdmin();
        validUuid(animalId, "animalId");
        requireEligibleAnimal(animalId);
        AdoptionListing listing = AdoptionListing.draft(animalId, title.trim(), description.trim(), publisher.id());
        repository.insert(listing);
        return listing;
    }

    public AdoptionListing detail(String listingId) {
        AdoptionListing listing = find(listingId);
        if (listing.status().name().equals("DRAFT")) requireGovernanceAdmin();
        return listing;
    }

    public PageResult<AdoptionListing> publicListings(int page, int size) {
        int safePage = Math.max(0, page); int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return new PageResult<>(repository.findPublished(safeSize, safePage * safeSize), safePage, safeSize, repository.countPublished());
    }

    @Transactional
    public AdoptionListing publish(String listingId) {
        requireGovernanceAdmin();
        AdoptionListing listing = find(listingId);
        if (listing.status().name().equals("DRAFT")) requireEligibleAnimal(listing.animalId());
        AdoptionListing published = listing.publish();
        if (!repository.publish(published.id(), published.updatedAt(), published.publishedAt())) {
            throw new InvalidListingTransitionException("领养信息状态已变化，请刷新后重试");
        }
        return published;
    }

    @Transactional
    public AdoptionListing close(String listingId) {
        requireGovernanceAdmin();
        AdoptionListing closed = find(listingId).close();
        if (!repository.close(closed.id(), closed.updatedAt(), closed.closedAt())) {
            throw new InvalidListingTransitionException("领养信息状态已变化，请刷新后重试");
        }
        return closed;
    }

    private AdoptionListing find(String listingId) {
        validUuid(listingId, "listingId");
        return repository.findById(listingId).orElseThrow(() -> new ListingNotFoundException("领养信息不存在"));
    }
    private void requireEligibleAnimal(String animalId) {
        if (!animalGateway.requireAnimal(animalId).isEligibleForListing()) throw new AnimalNotEligibleException("当前动物状态不允许发布领养信息");
    }
    private IdentityGateway.CurrentUser requireGovernanceAdmin() {
        IdentityGateway.CurrentUser user = identityGateway.requireCurrentUser();
        if (!user.isActiveGovernanceAdmin()) throw new ListingAccessDeniedException("仅治理管理员可以管理领养信息");
        return user;
    }
    private static void validUuid(String value, String field) {
        try { UUID.fromString(value); } catch (RuntimeException e) { throw new IllegalArgumentException(field + " 必须是 UUID"); }
    }
}
