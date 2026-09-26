package org.animallink.animal.application;

import org.animallink.animal.domain.CommunityPostView;
import org.animallink.animal.domain.PostMedia;

import java.util.List;

public record CommunityPostDetail(
        CommunityPostView view,
        AuthorSummary author,
        List<PostMedia> media) {
}
