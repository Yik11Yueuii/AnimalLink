package org.animallink.animal.api;

import org.animallink.animal.application.CommunityReviewQueryService;
import org.animallink.animal.application.PageResult;
import org.animallink.animal.domain.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/community-review")
public class AdminCommunityReviewController {
    private final CommunityReviewQueryService queryService;

    public AdminCommunityReviewController(CommunityReviewQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/posts")
    public CommunityReviewDtos.PageResponse<CommunityReviewDtos.PostItem> posts(
            @RequestParam(name = "reviewStatus", defaultValue = "PENDING")
            PublicReviewStatus reviewStatus,
            @RequestParam(name = "campusId", required = false) String campusId,
            @RequestParam(name = "postType", required = false) PostType postType,
            @RequestParam(name = "sort", defaultValue = "NEWEST") CommunityReviewSort sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<GovernancePostView> result = queryService.posts(reviewStatus, campusId,
                postType, sort, page, size);
        return CommunityReviewDtos.PageResponse.from(result,
                result.items().stream().map(CommunityReviewDtos.PostItem::from).toList());
    }

    @GetMapping("/posts/{postId}")
    public CommunityReviewDtos.PostItem post(@PathVariable("postId") String postId) {
        return CommunityReviewDtos.PostItem.from(queryService.post(postId));
    }

    @GetMapping("/comments")
    public CommunityReviewDtos.PageResponse<CommunityReviewDtos.CommentItem> comments(
            @RequestParam(name = "reviewStatus", defaultValue = "PENDING")
            PublicReviewStatus reviewStatus,
            @RequestParam(name = "postId", required = false) String postId,
            @RequestParam(name = "campusId", required = false) String campusId,
            @RequestParam(name = "sort", defaultValue = "NEWEST") CommunityReviewSort sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<GovernanceCommentView> result = queryService.comments(reviewStatus, postId,
                campusId, sort, page, size);
        return CommunityReviewDtos.PageResponse.from(result,
                result.items().stream().map(CommunityReviewDtos.CommentItem::from).toList());
    }

    @GetMapping("/comments/{commentId}")
    public CommunityReviewDtos.CommentItem comment(@PathVariable("commentId") String commentId) {
        return CommunityReviewDtos.CommentItem.from(queryService.comment(commentId));
    }
}
