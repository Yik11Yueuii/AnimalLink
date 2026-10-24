package org.animallink.animal.api;

import org.animallink.animal.application.PageResult;
import org.animallink.animal.application.PublicCommunityQueryService;
import org.animallink.animal.domain.PublicCommunityCommentView;
import org.animallink.animal.domain.PublicCommunityPostView;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public")
public class PublicCommunityController {
    private final PublicCommunityQueryService queryService;

    public PublicCommunityController(PublicCommunityQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/campuses/{campusId}/feed")
    public PublicCommunityDtos.PageResponse<PublicCommunityDtos.PostResponse> feed(
            @PathVariable("campusId") String campusId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<PublicCommunityPostView> result = queryService.feed(campusId, page, size);
        return PublicCommunityDtos.PageResponse.from(result,
                result.items().stream().map(PublicCommunityDtos.PostResponse::from).toList());
    }

    @GetMapping("/posts/{postId}")
    public PublicCommunityDtos.PostResponse post(@PathVariable("postId") String postId) {
        return PublicCommunityDtos.PostResponse.from(queryService.post(postId));
    }

    @GetMapping("/posts/{postId}/comments")
    public PublicCommunityDtos.PageResponse<PublicCommunityDtos.CommentResponse> comments(
            @PathVariable("postId") String postId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        PageResult<PublicCommunityCommentView> result = queryService.comments(postId, page, size);
        return PublicCommunityDtos.PageResponse.from(result,
                result.items().stream().map(PublicCommunityDtos.CommentResponse::from).toList());
    }
}
