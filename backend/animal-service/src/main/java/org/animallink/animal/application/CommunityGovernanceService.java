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

    Post changePostLocked(String postId, String reason, GovernanceAction action,
                          IdentityGateway.CurrentUser actor) {
        Post current = repository.lockPost(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post 不存在"));
        Post changed = switch (action) {
            case HIDE -> current.hide();
            case RESTORE -> current.restore();
            case REMOVE -> current.remove();
        };
        repository.updatePostStatus(changed, current.status());
        synchronizeTimeline(current, action);
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
        };
        repository.updateCommentStatus(changed, current.status());
        repository.insertAudit(audit(GovernanceResourceType.COMMENT, commentId, action,
                current.status().name(), changed.status().name(), actor.id(), reason));
        return repository.lockComment(commentId).orElseThrow();
    }

    private void synchronizeTimeline(Post current, GovernanceAction action) {
        if (current.postType() != PostType.POST_ADOPTION) {
            return;
        }
        if (action == GovernanceAction.REMOVE && current.status() == PostStatus.HIDDEN) {
            if (repository.countPostAdoptionTimelines(current.id(), Visibility.RESTRICTED) != 1) {
                throw new StateConflictException("POST_ADOPTION Timeline 状态不一致");
            }
            return;
        }
        Visibility expected = action == GovernanceAction.RESTORE
                ? Visibility.RESTRICTED : Visibility.PUBLIC;
        Visibility target = action == GovernanceAction.RESTORE
                ? Visibility.PUBLIC : Visibility.RESTRICTED;
        if (repository.updatePostAdoptionTimelineVisibility(current.id(), expected, target) != 1) {
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
