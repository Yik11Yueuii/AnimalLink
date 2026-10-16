package org.animallink.animal.application;

import jakarta.validation.ValidationException;
import org.animallink.animal.domain.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Service
public class ContentReportService {
    private final CommunityGovernanceRepository repository;
    private final CommunityGovernanceService governanceService;
    private final CampusMembershipAuthorization userAuthorization;
    private final GovernanceAuthorization governanceAuthorization;
    private final TransactionTemplate transactions;

    public ContentReportService(CommunityGovernanceRepository repository,
                                CommunityGovernanceService governanceService,
                                CampusMembershipAuthorization userAuthorization,
                                GovernanceAuthorization governanceAuthorization,
                                TransactionTemplate transactions) {
        this.repository = repository;
        this.governanceService = governanceService;
        this.userAuthorization = userAuthorization;
        this.governanceAuthorization = governanceAuthorization;
        this.transactions = transactions;
    }

    public ContentReport reportPost(String postId, String rawDescription) {
        return create(GovernanceResourceType.POST, IdRules.requireUuid(postId, "postId"),
                description(rawDescription));
    }

    public ContentReport reportComment(String commentId, String rawDescription) {
        return create(GovernanceResourceType.COMMENT, IdRules.requireUuid(commentId, "commentId"),
                description(rawDescription));
    }

    public PageResult<GovernanceReportView> reports(ContentReportStatus status,
                                                     GovernanceResourceType resourceType,
                                                     String campusId, int page, int size) {
        governanceAuthorization.requireAdministrator();
        String validCampusId = campusId == null ? null : IdRules.requireUuid(campusId, "campusId");
        Page pageSpec = page(page, size);
        return new PageResult<>(repository.findReports(status, resourceType, validCampusId,
                pageSpec.size(), pageSpec.offset()), pageSpec.page(), pageSpec.size(),
                repository.countReports(status, resourceType, validCampusId));
    }

    public GovernanceReportView report(String reportId) {
        governanceAuthorization.requireAdministrator();
        return requiredView(IdRules.requireUuid(reportId, "reportId"));
    }

    public GovernanceReportView dismiss(String reportId, String rawNote) {
        IdentityGateway.CurrentUser actor = governanceAuthorization.requireAdministrator();
        String validId = IdRules.requireUuid(reportId, "reportId");
        String note = note(rawNote);
        return Objects.requireNonNull(transactions.execute(status -> {
            ContentReport current = requiredLocked(validId);
            ContentReport changed = current.dismiss(actor.id(), note);
            repository.updateReport(changed, current.version());
            return requiredView(validId);
        }));
    }

    public GovernanceReportView resolve(String reportId, ReportResolutionAction action,
                                        String rawNote) {
        IdentityGateway.CurrentUser actor = governanceAuthorization.requireAdministrator();
        String validId = IdRules.requireUuid(reportId, "reportId");
        if (action == null) {
            throw new ValidationException("action 不能为空");
        }
        String note = note(rawNote);
        return Objects.requireNonNull(transactions.execute(status -> {
            ContentReport current = requiredLocked(validId);
            ContentReport changed = current.resolve(action, actor.id(), note);
            if (action == ReportResolutionAction.HIDE) {
                changeContent(current, GovernanceAction.HIDE, actor, note);
            } else if (action == ReportResolutionAction.REMOVE) {
                changeContent(current, GovernanceAction.REMOVE, actor, note);
            }
            repository.updateReport(changed, current.version());
            return requiredView(validId);
        }));
    }

    private ContentReport create(GovernanceResourceType resourceType, String resourceId,
                                 String description) {
        IdentityGateway.CurrentUser reporter = userAuthorization.requireActiveUser();
        ContentReport report = ContentReport.open(resourceType, resourceId, reporter.id(),
                description);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                repository.insertReport(report);
                requireReportable(resourceType, resourceId);
                return report;
            }));
        } catch (DuplicateKeyException exception) {
            throw new StateConflictException("已存在尚未处理的相同内容举报");
        }
    }

    private void requireReportable(GovernanceResourceType type, String resourceId) {
        if (type == GovernanceResourceType.POST) {
            Post post = repository.lockPost(resourceId)
                    .orElseThrow(() -> unavailable(type));
            if (post.status() != PostStatus.ACTIVE || post.visibility() != Visibility.PUBLIC) {
                throw unavailable(type);
            }
            return;
        }
        Comment comment = repository.lockComment(resourceId)
                .orElseThrow(() -> unavailable(type));
        if (comment.status() != CommentStatus.ACTIVE) {
            throw unavailable(type);
        }
        Post parent = repository.lockPost(comment.postId())
                .orElseThrow(() -> unavailable(type));
        if (parent.status() != PostStatus.ACTIVE || parent.visibility() != Visibility.PUBLIC) {
            throw unavailable(type);
        }
    }

    private void changeContent(ContentReport report, GovernanceAction action,
                               IdentityGateway.CurrentUser actor, String reason) {
        if (report.resourceType() == GovernanceResourceType.POST) {
            governanceService.changePostLocked(report.resourceId(), reason, action, actor);
        } else {
            governanceService.changeCommentLocked(report.resourceId(), reason, action, actor);
        }
    }

    private ContentReport requiredLocked(String reportId) {
        return repository.lockReport(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("举报不存在"));
    }

    private GovernanceReportView requiredView(String reportId) {
        return repository.findReport(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("举报不存在"));
    }

    private ResourceNotFoundException unavailable(GovernanceResourceType type) {
        return new ResourceNotFoundException(type == GovernanceResourceType.POST
                ? "Post 不存在或不可见" : "Comment 不存在或不可见");
    }

    private String description(String value) {
        return requiredText(value, "description");
    }

    private String note(String value) {
        return requiredText(value, "resolutionNote");
    }

    private String requiredText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.codePointCount(0, normalized.length()) > 1000) {
            throw new ValidationException(field + " 最多 1000 个字符");
        }
        return normalized;
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
