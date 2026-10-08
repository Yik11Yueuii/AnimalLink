package org.animallink.animal.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.animallink.animal.application.*;
import org.animallink.animal.domain.*;

import java.time.Instant;
import java.util.List;

public final class CommunityDtos {
    private CommunityDtos() {
    }

    public record CreatePostRequest(
            @NotBlank @Size(max = 36) String campusId,
            @Size(max = 36) String animalId,
            @NotBlank @Size(max = 2000) String textContent,
            @Size(max = 6) List<@Valid MediaRequest> media) {
        CreatePostCommand toCommand() {
            List<CreatePostCommand.MediaInput> values = media == null ? List.of() : media.stream()
                    .map(MediaRequest::toInput).toList();
            return new CreatePostCommand(campusId, animalId, textContent, values);
        }
    }

    public record CreatePostAdoptionRequest(
            @NotBlank @Size(max = 2000) String textContent,
            @Size(max = 6) List<@Valid MediaRequest> media) {
        CreatePostAdoptionCommand toCommand() {
            List<CreatePostCommand.MediaInput> values = media == null ? List.of() : media.stream()
                    .map(MediaRequest::toInput).toList();
            return new CreatePostAdoptionCommand(textContent, values);
        }
    }

    public record MediaRequest(
            @NotBlank @Size(max = 512) String objectKey,
            @NotBlank @Size(max = 120) String contentType,
            @NotNull MediaType mediaType,
            @PositiveOrZero Long sizeBytes,
            @PositiveOrZero int sortOrder) {
        CreatePostCommand.MediaInput toInput() {
            return new CreatePostCommand.MediaInput(objectKey, contentType, mediaType,
                    sizeBytes, sortOrder);
        }
    }

    public record CreateCommentRequest(@NotBlank @Size(max = 1000) String content) {
    }

    public record AuthorResponse(String id, String displayName) {
        static AuthorResponse from(AuthorSummary author) {
            return new AuthorResponse(author.id(), author.displayName());
        }
    }

    public record AnimalCardResponse(
            String id,
            String displayName,
            AnimalSpecies species,
            String identityStatus,
            String adoptionStatus,
            String coverObjectKey) {
        static AnimalCardResponse from(CommunityPostView view) {
            if (view.post().animalId() == null) {
                return null;
            }
            return new AnimalCardResponse(view.post().animalId(), view.animalDisplayName(),
                    view.animalSpecies(), view.animalIdentityStatus(), view.animalAdoptionStatus(),
                    view.animalCoverObjectKey());
        }
    }

    public record MediaResponse(
            String id,
            String objectKey,
            String contentType,
            MediaType mediaType,
            Long sizeBytes,
            int sortOrder) {
        static MediaResponse from(PostMedia media) {
            return new MediaResponse(media.id(), media.objectKey(), media.contentType(),
                    media.mediaType(), media.sizeBytes(), media.sortOrder());
        }
    }

    public record PostResponse(
            String id,
            String campusId,
            PostType postType,
            String textContent,
            AuthorResponse author,
            AnimalCardResponse animal,
            List<MediaResponse> media,
            long likeCount,
            long commentCount,
            boolean likedByMe,
            Instant createdAt,
            Instant updatedAt) {
        static PostResponse from(CommunityPostDetail detail) {
            CommunityPostView view = detail.view();
            Post post = view.post();
            return new PostResponse(post.id(), post.campusId(), post.postType(), post.textContent(),
                    AuthorResponse.from(detail.author()), AnimalCardResponse.from(view),
                    detail.media().stream().map(MediaResponse::from).toList(), view.likeCount(),
                    view.commentCount(), view.likedByMe(), post.createdAt(), post.updatedAt());
        }
    }

    public record CommentResponse(
            String id,
            String postId,
            String content,
            AuthorResponse author,
            Instant createdAt) {
        static CommentResponse from(CommentView view) {
            Comment comment = view.comment();
            return new CommentResponse(comment.id(), comment.postId(), comment.content(),
                    AuthorResponse.from(view.author()), comment.createdAt());
        }
    }

    public record EngagementResponse(boolean active, long count) {
        static EngagementResponse from(EngagementResult result) {
            return new EngagementResponse(result.active(), result.count());
        }
    }

    public record FollowedAnimalResponse(
            AnimalDtos.AnimalSummaryResponse animal,
            Instant followedAt,
            long followerCount) {
        static FollowedAnimalResponse from(FollowedAnimalView view) {
            return new FollowedAnimalResponse(AnimalDtos.AnimalSummaryResponse.from(view.animal()),
                    view.followedAt(), view.followerCount());
        }
    }

    public record HiddenPostResponse(String id, String status, Instant updatedAt) {
        static HiddenPostResponse from(Post post) {
            return new HiddenPostResponse(post.id(), post.status().name(), post.updatedAt());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
        static <T> PageResponse<T> from(PageResult<?> page, List<T> items) {
            return new PageResponse<>(items, page.page(), page.size(), page.total());
        }
    }
}
