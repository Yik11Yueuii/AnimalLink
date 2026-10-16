package org.animallink.animal.api;

import org.animallink.animal.application.PageResult;
import org.animallink.animal.domain.*;

import java.time.Instant;
import java.util.List;

public final class CommunityGovernanceDtos {
    private CommunityGovernanceDtos() {
    }

    public record GovernanceReasonRequest(String reason) {
    }

    public record ReportDismissRequest(String resolutionNote) {
    }

    public record ReportResolveRequest(ReportResolutionAction action, String resolutionNote) {
    }

    public record PostTransitionResponse(String id, PostStatus status, int version,
                                         Instant updatedAt) {
        static PostTransitionResponse from(Post post) {
            return new PostTransitionResponse(post.id(), post.status(), post.version(),
                    post.updatedAt());
        }
    }

    public record CommentTransitionResponse(String id, CommentStatus status, Instant updatedAt) {
        static CommentTransitionResponse from(Comment comment) {
            return new CommentTransitionResponse(comment.id(), comment.status(),
                    comment.updatedAt());
        }
    }

    public record PostQueueItem(
            String postId,
            String campusId,
            String animalId,
            String authorUserId,
            PostType postType,
            String textContent,
            PostStatus status,
            int version,
            long likeCount,
            long activeCommentCount,
            long hiddenCommentCount,
            Instant createdAt,
            Instant updatedAt) {
        static PostQueueItem from(GovernancePostView view) {
            Post post = view.post();
            return new PostQueueItem(post.id(), post.campusId(), post.animalId(),
                    post.authorUserId(), post.postType(), post.textContent(), post.status(),
                    post.version(), view.likeCount(), view.activeCommentCount(),
                    view.hiddenCommentCount(), post.createdAt(), post.updatedAt());
        }
    }

    public record GovernanceMediaResponse(
            String mediaId,
            String contentType,
            MediaType mediaType,
            Long sizeBytes,
            int sortOrder,
            Visibility visibility) {
        static GovernanceMediaResponse from(PostMedia media) {
            return new GovernanceMediaResponse(media.id(), media.contentType(), media.mediaType(),
                    media.sizeBytes(), media.sortOrder(), media.visibility());
        }
    }

    public record PostDetailResponse(
            String postId,
            String campusId,
            String animalId,
            String relationId,
            String authorUserId,
            PostType postType,
            String textContent,
            PostStatus status,
            int version,
            Instant createdAt,
            Instant updatedAt,
            long likeCount,
            long activeCommentCount,
            long hiddenCommentCount,
            List<GovernanceMediaResponse> media) {
        static PostDetailResponse from(GovernancePostView view) {
            Post post = view.post();
            return new PostDetailResponse(post.id(), post.campusId(), post.animalId(),
                    post.relationId(), post.authorUserId(), post.postType(), post.textContent(),
                    post.status(), post.version(), post.createdAt(), post.updatedAt(),
                    view.likeCount(), view.activeCommentCount(), view.hiddenCommentCount(),
                    view.media().stream().map(GovernanceMediaResponse::from).toList());
        }
    }

    public record CommentQueueItem(
            String commentId,
            String postId,
            String campusId,
            String authorUserId,
            String content,
            CommentStatus status,
            PostStatus parentPostStatus,
            Instant createdAt,
            Instant updatedAt) {
        static CommentQueueItem from(GovernanceCommentView view) {
            Comment comment = view.comment();
            return new CommentQueueItem(comment.id(), comment.postId(), view.campusId(),
                    comment.authorUserId(), comment.content(), comment.status(),
                    view.parentPostStatus(), comment.createdAt(), comment.updatedAt());
        }
    }

    public record AuditResponse(
            String auditId,
            GovernanceResourceType resourceType,
            String resourceId,
            GovernanceAction action,
            String fromState,
            String toState,
            String actorUserId,
            String reason,
            Instant createdAt) {
        static AuditResponse from(CommunityGovernanceAudit audit) {
            return new AuditResponse(audit.id(), audit.resourceType(), audit.resourceId(),
                    audit.action(), audit.fromState(), audit.toState(), audit.actorUserId(),
                    audit.reason(), audit.createdAt());
        }
    }

    public record ReportQueueItem(
            String reportId,
            GovernanceResourceType resourceType,
            String resourceId,
            String reporterUserId,
            String description,
            ContentReportStatus status,
            ReportResolutionAction resolutionAction,
            String resolutionNote,
            String reviewedByUserId,
            Instant reviewedAt,
            Instant createdAt,
            Instant updatedAt,
            String authorUserId,
            String campusId,
            String currentContentStatus,
            String contentText,
            PostStatus parentPostStatus) {
        static ReportQueueItem from(GovernanceReportView view) {
            ContentReport report = view.report();
            return new ReportQueueItem(report.id(), report.resourceType(), report.resourceId(),
                    report.reporterUserId(), report.description(), report.status(),
                    report.resolutionAction(), report.resolutionNote(), report.reviewedByUserId(),
                    report.reviewedAt(), report.createdAt(), report.updatedAt(),
                    view.authorUserId(), view.campusId(), view.currentContentStatus(),
                    view.contentText(), view.parentPostStatus());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
        static <S, T> PageResponse<T> from(PageResult<S> result, List<T> items) {
            return new PageResponse<>(items, result.page(), result.size(), result.total());
        }
    }
}
