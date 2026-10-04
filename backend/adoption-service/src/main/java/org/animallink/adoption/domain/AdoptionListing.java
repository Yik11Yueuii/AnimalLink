package org.animallink.adoption.domain;

import java.time.Instant;
import java.util.UUID;

public record AdoptionListing(String id, String animalId, ListingStatus status, String title,
                              String description, String publisherUserId, Instant createdAt,
                              Instant updatedAt, Instant publishedAt, Instant closedAt) {
    public static AdoptionListing draft(String animalId, String title, String description, String publisherUserId) {
        Instant now = Instant.now();
        return new AdoptionListing(UUID.randomUUID().toString(), animalId, ListingStatus.DRAFT, title,
                description, publisherUserId, now, now, null, null);
    }
    public AdoptionListing publish() {
        if (status != ListingStatus.DRAFT) throw new InvalidListingTransitionException("只有草稿领养信息可以发布");
        Instant now = Instant.now();
        return new AdoptionListing(id, animalId, ListingStatus.PUBLISHED, title, description, publisherUserId,
                createdAt, now, now, null);
    }
    public AdoptionListing close() {
        if (status == ListingStatus.CLOSED) throw new InvalidListingTransitionException("领养信息已经关闭");
        Instant now = Instant.now();
        return new AdoptionListing(id, animalId, ListingStatus.CLOSED, title, description, publisherUserId,
                createdAt, now, publishedAt, now);
    }
}
