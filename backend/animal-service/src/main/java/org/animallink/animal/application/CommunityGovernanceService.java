package org.animallink.animal.application;

import jakarta.validation.ValidationException;
import org.animallink.animal.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class CommunityGovernanceService {
    private final CommunityGovernanceRepository repository;
    private final GovernanceAuthorization authorization;
    private final TransactionTemplate transactions;

    public CommunityGovernanceService(CommunityGovernanceRepository repository,
                                      GovernanceAuthorization authorization,
                                      TransactionTemplate transactions) {
        this.repository = repository;
        this.authorization = authorization;
        this.transactions = transactions;
    }

    public Post hidePost(String postId, String reason) {
        return changePost(postId, reason, GovernanceAction.HIDE);
    }

    public Post restorePost(String postId, String reason) {
        return changePost(postId, reason, GovernanceAction.RESTORE);
    }

    public Post removePost(String postId, String reason) {
        return changePost(postId, reason, GovernanceAction.REMOVE);
    }

    public Comment hideComment(String commentId, String reason) {
        return changeComment(commentId, reason, GovernanceAction.HIDE);
    }

    public Comment restoreComment(String commentId, String reason) {
        return changeComment(commentId, reason, GovernanceAction.RESTORE);
    }

    public Comment removeComment(String commentId, String reason) {
        return changeComment(commentId, reason, GovernanceAction.REMOVE);
    }

    public Post approvePost(String postId, String reason) {
        return reviewPost(postId, reason, PublicReviewStatus.APPROVED,
                authorization.requireAdministrator());
    }

    public Post rejectPost(String postId, String reason) {
        return reviewPost(postId, reason, PublicReviewStatus.REJECTED,
                authorization.requireAdministrator());
    }

    public Comment approveComment(String commentId, String reason) {
        return reviewComment(commentId, reason, PublicReviewStatus.APPROVED,
                authorization.requireAdministrator());
    }

    public Comment rejectComment(String commentId, String reason) {
        return reviewComment(commentId, reason, PublicReviewStatus.REJECTED,
                authorization.requireAdministrator());
    }

    Post approvePostAsTrustedAdmin(String postId, String reason, IdentityGateway.CurrentUser actor) {
        return reviewPost(postId, reason, PublicReviewStatus.APPROVED, actor);
    }

    Post rejectPostAsTrustedAdmin(String postId, String reason, IdentityGateway.CurrentUser actor) {
        return reviewPost(postId, reason, PublicReviewStatus.REJECTED, actor);
    }

    Comment approveCommentAsTrustedAdmin(String commentId, String reason,
                                         IdentityGateway.CurrentUser actor) {
        return reviewComment(commentId, reason, PublicReviewStatus.APPROVED, actor);
    }

    Comment rejectCommentAsTrustedAdmin(String commentId, String reason,
                                        IdentityGateway.CurrentUser actor) {
        return reviewComment(commentId, reason, PublicReviewStatus.REJECTED, actor);
    }

    public PageResult<GovernancePostView> posts(PostStatus status, String campusId,
                                                PostType postType, int page, int size) {
        authorization.requireAdministrator();
        String validCampusId = optionalUuid(campusId, "campusId");
        Page pageSpec = page(page, size);
        return new PageResult<>(repository.findPosts(status, validCampusId, postType,
                pageSpec.size(), pageSpec.offset()), pageSpec.page(), pageSpec.size(),
                repository.countPosts(status, validCampusId, postType));
    }

    public GovernancePostView post(String postId) {
        authorization.requireAdministrator();
        return requiredPost(IdRules.requireUuid(postId, "postId"));
    }

    public PageResult<GovernanceCommentView> comments(CommentStatus status, String postId,
                                                      String campusId, int page, int size) {
        authorization.requireAdministrator();
        String validPostId = optionalUuid(postId, "postId");
        String validCampusId = optionalUuid(campusId, "campusId");
        Page pageSpec = page(page, size);
        return new PageResult<>(repository.findComments(status, validPostId, validCampusId,
                pageSpec.size(), pageSpec.offset()), pageSpec.page(), pageSpec.size(),
                repository.countComments(status, validPostId, validCampusId));
    }

    public GovernanceCommentView comment(String commentId) {
        authorization.requireAdministrator();
        return requiredComment(IdRules.requireUuid(commentId, "commentId"));
    }

    public PageResult<CommunityGovernanceAudit> postAudits(String postId, int page, int size) {
        authorization.requireAdministrator();
        String validId = IdRules.requireUuid(postId, "postId");
        requiredPost(validId);
        return audits(GovernanceResourceType.POST, validId, page, size);
    }

    public PageResult<CommunityGovernanceAudit> commentAudits(String commentId, int page, int size) {
        authorization.requireAdministrator();
        String validId = IdRules.requireUuid(commentId, "commentId");
        requiredComment(validId);
        return audits(GovernanceResourceType.COMMENT, validId, page, size);
    }

    private Post changePost(String postId, String rawReason, GovernanceAction action) {
        IdentityGateway.CurrentUser actor = authorization.requireAdministrator();
        String validId = IdRules.requireUuid(postId, "postId");
        String reason = reason(rawReason);
        return Objects.requireNonNull(transactions.execute(status ->
                changePostLocked(validId, reason, action, actor)));
    }

    private Comment changeComment(String commentId, String rawReason, GovernanceAction action) {
        IdentityGateway.CurrentUser actor = authorization.requireAdministrator();
        String validId = IdRules.requireUuid(commentId, "commentId");
        String reason = reason(rawReason);
        return Objects.requireNonNull(transactions.execute(status ->
                changeCommentLocked(validId, reason, action, actor)));
    }

    private Post reviewPost(String postId, String rawReason, PublicReviewStatus target,
                            IdentityGateway.CurrentUser actor) {
        String validId = IdRules.requireUuid(postId, "postId");
        String reason = reason(rawReason);
        return Objects.requireNonNull(transactions.execute(status -> {
            Post current = repository.lockPost(validId)
                    .orElseThrow(() -> new ResourceNotFoundException("Post 不存在"));
            Post changed = current.review(target, actor.id(), Instant.now());
            repository.updatePostReview(changed, current.reviewStatus());
            if (current.postType() == PostType.POST_ADOPTION) {
                Visibility timelineTarget = target == PublicReviewStatus.APPROVED
                        && current.status() == PostStatus.ACTIVE
                        && current.visibility() == Visibility.PUBLIC
                        ? Visibility.PUBLIC : Visibility.RESTRICTED;
                ensureTimelineVisibility(current.id(), timelineTarget);
            }
            GovernanceAction action = target == PublicReviewStatus.APPROVED
                    ? GovernanceAction.APPROVE : GovernanceAction.REJECT;
            repository.insertAudit(audit(GovernanceResourceType.POST, validId, action,
                    current.reviewStatus().name(), target.name(), actor.id(), reason));
            return repository.lockPost(validId).orElseThrow();
        }));
    }

    private Comment reviewComment(String commentId, String rawReason, PublicReviewStatus target,
                                  IdentityGateway.CurrentUser actor) {
        String validId = IdRules.requireUuid(commentId, "commentId");
        String reason = reason(rawReason);
        return Objects.requireNonNull(transactions.execute(status -> {
            Comment current = repository.lockComment(validId)
                    .orElseThrow(() -> new ResourceNotFoundException("Comment 不存在"));
            Comment changed = current.review(target, actor.id(), Instant.now());
            repository.updateCommentReview(changed, current.reviewStatus());
            GovernanceAction action = target == PublicReviewStatus.APPROVED
                    ? GovernanceAction.APPROVE : GovernanceAction.REJECT;
            repository.insertAudit(audit(GovernanceResourceType.COMMENT, validId, action,
                    current.reviewStatus().name(), target.name(), actor.id(), reason));
            return repository.lockComment(validId).orElseThrow();
        }));
    }

    Post changePostLocked(String postId, String reason, GovernanceAction action,
                          IdentityGateway.CurrentUser actor) {
        Post current = repository.lockPost(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post 不存在"));
        Post changed = switch (action) {
            case HIDE -> current.hide();
            case RESTORE -> current.restore();
            case REMOVE -> current.remove();
            case APPROVE, REJECT -> throw new IllegalArgumentException("审核动作必须使用审核流程");
        };
        repository.updatePostStatus(changed, current.status());
        synchronizeTimeline(changed, action);
        repository.insertAudit(audit(GovernanceResourceType.POST, postId, action,
                current.status().name(), changed.status().name(), actor.id(), reason));
        return repository.lockPost(postId).orElseThrow();
    }

    Comment changeCommentLocked(String commentId, String reason, GovernanceAction action,
                                IdentityGateway.CurrentUser actor) {
        Comment current = repository.lockComment(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment 不存在"));
        Comment changed = switch (action) {
            case HIDE -> current.hide();
            case RESTORE -> current.restore();
            case REMOVE -> current.remove();
            case APPROVE, REJECT -> throw new IllegalArgumentException("审核动作必须使用审核流程");
        };
        repository.updateCommentStatus(changed, current.status());
        repository.insertAudit(audit(GovernanceResourceType.COMMENT, commentId, action,
                current.status().name(), changed.status().name(), actor.id(), reason));
        return repository.lockComment(commentId).orElseThrow();
    }

    private void synchronizeTimeline(Post changed, GovernanceAction action) {
        if (changed.postType() != PostType.POST_ADOPTION) {
            return;
        }
        Visibility target = action == GovernanceAction.RESTORE
                && changed.reviewStatus() == PublicReviewStatus.APPROVED
                ? Visibility.PUBLIC : Visibility.RESTRICTED;
        ensureTimelineVisibility(changed.id(), target);
    }

    private void ensureTimelineVisibility(String postId, Visibility target) {
        if (repository.countPostAdoptionTimelines(postId, target) == 1) {
            return;
        }
        Visibility expected = target == Visibility.PUBLIC
                ? Visibility.RESTRICTED : Visibility.PUBLIC;
        if (repository.updatePostAdoptionTimelineVisibility(postId, expected, target) != 1
                || repository.countPostAdoptionTimelines(postId, target) != 1) {
            throw new StateConflictException("POST_ADOPTION Timeline 状态不一致");
        }
    }

    private PageResult<CommunityGovernanceAudit> audits(GovernanceResourceType resourceType,
                                                         String resourceId, int page, int size) {
        Page pageSpec = page(page, size);
        return new PageResult<>(repository.findAudits(resourceType, resourceId, pageSpec.size(),
                pageSpec.offset()), pageSpec.page(), pageSpec.size(),
                repository.countAudits(resourceType, resourceId));
    }

    private GovernancePostView requiredPost(String postId) {
        return repository.findPost(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post 不存在"));
    }

    private GovernanceCommentView requiredComment(String commentId) {
        return repository.findComment(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment 不存在"));
    }

    private CommunityGovernanceAudit audit(GovernanceResourceType type, String resourceId,
                                            GovernanceAction action, String fromState,
                                            String toState, String actor, String reason) {
        return new CommunityGovernanceAudit(UUID.randomUUID().toString(), type, resourceId, action,
                fromState, toState, actor, reason, Instant.now());
    }

    private String reason(String value) {
        if (value == null) {
            throw new ValidationException("reason 不能为空");
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new ValidationException("reason 不能为空");
        }
        if (normalized.codePointCount(0, normalized.length()) > 1000) {
            throw new ValidationException("reason 最多 1000 个字符");
        }
        return normalized;
    }

    private String optionalUuid(String value, String field) {
        return value == null ? null : IdRules.requireUuid(value, field);
    }

    private Page page(int page, int size) {
        if (page < 0) {
            throw new ValidationException("page 不能小于 0");
        }
        if (size < 1 || size > 100) {
            throw new ValidationException("size 必须在 1 到 100 之间");
        }
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            throw new ValidationException("page 超出允许范围");
        }
        return new Page(page, size, (int) offset);
    }

    private record Page(int page, int size, int offset) {
    }
}
