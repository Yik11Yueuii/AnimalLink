package org.animallink.animal.api;

import org.animallink.animal.application.PageResult;
import org.animallink.animal.domain.*;

import java.time.Instant;
import java.util.List;

public final class CommunityReviewDtos {
    private CommunityReviewDtos() {
    }

    public record PostItem(String postId, String campusId, String animalId, PostType postType,
                           String textContent, PostStatus status, PublicReviewStatus reviewStatus,
                           Instant reviewedAt, int version, long likeCount,
                           long activeCommentCount, long hiddenCommentCount,
                           Instant createdAt, Instant updatedAt) {
        static PostItem from(GovernancePostView view) {
            Post post = view.post();
            return new PostItem(post.id(), post.campusId(), post.animalId(), post.postType(),
                    post.textContent(), post.status(), post.reviewStatus(), post.reviewedAt(),
                    post.version(), view.likeCount(), view.activeCommentCount(),
                    view.hiddenCommentCount(), post.createdAt(), post.updatedAt());
        }
    }

    public record CommentItem(String commentId, String postId, String campusId, String content,
                              CommentStatus status, PublicReviewStatus reviewStatus,
                              Instant reviewedAt, PostStatus parentPostStatus,
                              Instant createdAt, Instant updatedAt) {
        static CommentItem from(GovernanceCommentView view) {
            Comment comment = view.comment();
            return new CommentItem(comment.id(), comment.postId(), view.campusId(),
                    comment.content(), comment.status(), comment.reviewStatus(),
                    comment.reviewedAt(), view.parentPostStatus(), comment.createdAt(),
                    comment.updatedAt());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
        static <S, T> PageResponse<T> from(PageResult<S> result, List<T> items) {
            return new PageResponse<>(items, result.page(), result.size(), result.total());
        }
    }
}
