package org.animallink.animal.api;

import org.animallink.animal.application.CommunityGovernanceService;
import org.animallink.animal.application.PageResult;
import org.animallink.animal.domain.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminCommunityController {
    private final CommunityGovernanceService governanceService;

    public AdminCommunityController(CommunityGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @PostMapping("/posts/{postId}/hide")
    public CommunityGovernanceDtos.PostTransitionResponse hidePost(
            @PathVariable("postId") String postId,
            @RequestBody CommunityGovernanceDtos.GovernanceReasonRequest request) {
        return CommunityGovernanceDtos.PostTransitionResponse.from(
                governanceService.hidePost(postId, request.reason()));
    }

    @PostMapping("/posts/{postId}/restore")
    public CommunityGovernanceDtos.PostTransitionResponse restorePost(
            @PathVariable("postId") String postId,
            @RequestBody CommunityGovernanceDtos.GovernanceReasonRequest request) {
        return CommunityGovernanceDtos.PostTransitionResponse.from(
                governanceService.restorePost(postId, request.reason()));
    }

    @PostMapping("/comments/{commentId}/hide")
    public CommunityGovernanceDtos.CommentTransitionResponse hideComment(
            @PathVariable("commentId") String commentId,
            @RequestBody CommunityGovernanceDtos.GovernanceReasonRequest request) {
        return CommunityGovernanceDtos.CommentTransitionResponse.from(
                governanceService.hideComment(commentId, request.reason()));
    }

    @PostMapping("/comments/{commentId}/restore")
    public CommunityGovernanceDtos.CommentTransitionResponse restoreComment(
            @PathVariable("commentId") String commentId,
            @RequestBody CommunityGovernanceDtos.GovernanceReasonRequest request) {
        return CommunityGovernanceDtos.CommentTransitionResponse.from(
                governanceService.restoreComment(commentId, request.reason()));
    }

    @GetMapping("/posts")
    public CommunityGovernanceDtos.PageResponse<CommunityGovernanceDtos.PostQueueItem> posts(
            @RequestParam(name = "status", required = false) PostStatus status,
            @RequestParam(name = "campusId", required = false) String campusId,
            @RequestParam(name = "postType", required = false) PostType postType,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<GovernancePostView> result = governanceService.posts(status, campusId, postType,
                page, size);
        return CommunityGovernanceDtos.PageResponse.from(result,
                result.items().stream().map(CommunityGovernanceDtos.PostQueueItem::from).toList());
    }

    @GetMapping("/posts/{postId}")
    public CommunityGovernanceDtos.PostDetailResponse post(
            @PathVariable("postId") String postId) {
        return CommunityGovernanceDtos.PostDetailResponse.from(governanceService.post(postId));
    }

    @GetMapping("/posts/{postId}/governance-actions")
    public CommunityGovernanceDtos.PageResponse<CommunityGovernanceDtos.AuditResponse> postAudits(
            @PathVariable("postId") String postId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<CommunityGovernanceAudit> result = governanceService.postAudits(postId, page, size);
        return CommunityGovernanceDtos.PageResponse.from(result,
                result.items().stream().map(CommunityGovernanceDtos.AuditResponse::from).toList());
    }

    @GetMapping("/comments")
    public CommunityGovernanceDtos.PageResponse<CommunityGovernanceDtos.CommentQueueItem> comments(
            @RequestParam(name = "status", required = false) CommentStatus status,
            @RequestParam(name = "postId", required = false) String postId,
            @RequestParam(name = "campusId", required = false) String campusId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<GovernanceCommentView> result = governanceService.comments(status, postId,
                campusId, page, size);
        return CommunityGovernanceDtos.PageResponse.from(result,
                result.items().stream().map(CommunityGovernanceDtos.CommentQueueItem::from).toList());
    }

    @GetMapping("/comments/{commentId}")
    public CommunityGovernanceDtos.CommentQueueItem comment(
            @PathVariable("commentId") String commentId) {
        return CommunityGovernanceDtos.CommentQueueItem.from(governanceService.comment(commentId));
    }

    @GetMapping("/comments/{commentId}/governance-actions")
    public CommunityGovernanceDtos.PageResponse<CommunityGovernanceDtos.AuditResponse> commentAudits(
            @PathVariable("commentId") String commentId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<CommunityGovernanceAudit> result = governanceService.commentAudits(commentId,
                page, size);
        return CommunityGovernanceDtos.PageResponse.from(result,
                result.items().stream().map(CommunityGovernanceDtos.AuditResponse::from).toList());
    }
}
