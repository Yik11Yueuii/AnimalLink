package org.animallink.animal.domain;

import java.time.Instant;

public record FollowedAnimalView(Animal animal, Instant followedAt, long followerCount) {
}
