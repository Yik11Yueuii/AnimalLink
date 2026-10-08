package org.animallink.animal.api;

import jakarta.validation.Valid;
import org.animallink.animal.application.*;
import org.animallink.animal.domain.FollowedAnimalView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CommunityController {
    private final CommunityApplicationService applicationService;
    private final CommunityQueryService queryService;

    public CommunityController(CommunityApplicationService applicationService,
                               CommunityQueryService queryService) {
        this.applicationService = applicationService;
        this.queryService = queryService;
    }

    @GetMapping("/campuses/{campusId}/feed")
    public CommunityDtos.PageResponse<CommunityDtos.PostResponse> feed(
            @PathVariable("campusId") String campusId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<CommunityPostDetail> result = queryService.feed(campusId, page, size);
        return CommunityDtos.PageResponse.from(result,
                result.items().stream().map(CommunityDtos.PostResponse::from).toList());
    }

    @GetMapping("/posts/{postId}")
    public CommunityDtos.PostResponse post(@PathVariable("postId") String postId) {
        return CommunityDtos.PostResponse.from(queryService.post(postId));
    }

    @GetMapping("/posts/{postId}/comments")
    public CommunityDtos.PageResponse<CommunityDtos.CommentResponse> comments(
            @PathVariable("postId") String postId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        PageResult<CommentView> result = queryService.comments(postId, page, size);
        return CommunityDtos.PageResponse.from(result,
                result.items().stream().map(CommunityDtos.CommentResponse::from).toList());
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityDtos.PostResponse publish(
            @Valid @RequestBody CommunityDtos.CreatePostRequest request) {
        String postId = applicationService.publish(request.toCommand());
        return CommunityDtos.PostResponse.from(queryService.post(postId));
    }

    @PostMapping("/animals/{animalId}/post-adoption-posts")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityDtos.PostResponse publishPostAdoption(
            @PathVariable("animalId") String animalId,
            @Valid @RequestBody CommunityDtos.CreatePostAdoptionRequest request) {
        String postId = applicationService.publishPostAdoption(animalId, request.toCommand());
        return CommunityDtos.PostResponse.from(queryService.post(postId));
    }

    @PostMapping("/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityDtos.CommentResponse comment(
            @PathVariable("postId") String postId,
            @Valid @RequestBody CommunityDtos.CreateCommentRequest request) {
        return CommunityDtos.CommentResponse.from(applicationService.comment(postId, request.content()));
    }

    @PostMapping("/posts/{postId}/like")
    public CommunityDtos.EngagementResponse like(@PathVariable("postId") String postId) {
        return CommunityDtos.EngagementResponse.from(applicationService.like(postId));
    }

    @DeleteMapping("/posts/{postId}/like")
    public CommunityDtos.EngagementResponse unlike(@PathVariable("postId") String postId) {
        return CommunityDtos.EngagementResponse.from(applicationService.unlike(postId));
    }

    @PostMapping("/animals/{animalId}/follow")
    public CommunityDtos.EngagementResponse follow(@PathVariable("animalId") String animalId) {
        return CommunityDtos.EngagementResponse.from(applicationService.follow(animalId));
    }

    @DeleteMapping("/animals/{animalId}/follow")
    public CommunityDtos.EngagementResponse unfollow(@PathVariable("animalId") String animalId) {
        return CommunityDtos.EngagementResponse.from(applicationService.unfollow(animalId));
    }

    @GetMapping("/users/me/animal-follows")
    public CommunityDtos.PageResponse<CommunityDtos.FollowedAnimalResponse> myFollows(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<FollowedAnimalView> result = queryService.myFollows(page, size);
        return CommunityDtos.PageResponse.from(result,
                result.items().stream().map(CommunityDtos.FollowedAnimalResponse::from).toList());
    }
}
