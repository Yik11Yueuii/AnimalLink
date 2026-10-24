package org.animallink.animal.api;

import org.animallink.animal.application.PageResult;
import org.animallink.animal.domain.*;

import java.time.Instant;
import java.util.List;

public final class PublicCommunityDtos {
    private static final String PUBLIC_AUTHOR = "校园成员";

    private PublicCommunityDtos() {
    }

    public record AnimalCard(
            String id,
            String displayName,
            AnimalSpecies species,
            AdoptionStatus adoptionStatus,
            CurrentContext currentContext) {
        static AnimalCard from(PublicCommunityPostView view) {
            return view.animalId() == null ? null : new AnimalCard(view.animalId(),
                    view.animalDisplayName(), view.animalSpecies(), view.animalAdoptionStatus(),
                    view.animalCurrentContext());
        }
    }

    public record PostResponse(
            String id,
            String campusId,
            PostType postType,
            String textContent,
            String authorDisplayName,
            AnimalCard animal,
            long likeCount,
            long commentCount,
            Instant createdAt,
            Instant updatedAt) {
        static PostResponse from(PublicCommunityPostView view) {
            return new PostResponse(view.id(), view.campusId(), view.postType(), view.textContent(),
                    PUBLIC_AUTHOR, AnimalCard.from(view), view.likeCount(), view.commentCount(),
                    view.createdAt(), view.updatedAt());
        }
    }

    public record CommentResponse(
            String id,
            String postId,
            String content,
            String authorDisplayName,
            Instant createdAt) {
        static CommentResponse from(PublicCommunityCommentView view) {
            return new CommentResponse(view.id(), view.postId(), view.content(), PUBLIC_AUTHOR,
                    view.createdAt());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
        static <S, T> PageResponse<T> from(PageResult<S> page, List<T> items) {
            return new PageResponse<>(items, page.page(), page.size(), page.total());
        }
    }
}
