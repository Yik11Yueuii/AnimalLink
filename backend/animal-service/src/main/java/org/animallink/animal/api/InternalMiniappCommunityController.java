package org.animallink.animal.api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.animallink.animal.application.*;
import org.animallink.animal.domain.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/internal/v1/miniapp/community/posts")
public class InternalMiniappCommunityController {
 private final CommunityApplicationService actions; private final CommunityQueryService queries;
 private final PublicCommunityQueryService publicQueries; private final CampusMembershipAuthorization authorization;
 public InternalMiniappCommunityController(CommunityApplicationService actions,CommunityQueryService queries,PublicCommunityQueryService publicQueries,CampusMembershipAuthorization authorization){this.actions=actions;this.queries=queries;this.publicQueries=publicQueries;this.authorization=authorization;}
 @PostMapping public Receipt publish(@Valid @RequestBody PostInput input){String id=actions.publish(new CreatePostCommand(input.campusId(),null,input.textContent(),List.of()));return new Receipt(id,"PENDING");}
 @PostMapping("/{postId}/comments") public Receipt comment(@PathVariable("postId") String postId,@Valid @RequestBody CommentInput input){check(postId,input.campusId());return new Receipt(actions.comment(postId,input.content()).comment().id(),"PENDING");}
 @GetMapping("/{postId}/like") public LikeState state(@PathVariable("postId") String postId,@RequestParam("campusId") String campusId){check(postId,campusId);var view=queries.post(postId).view();return new LikeState(view.likedByMe(),view.likeCount());}
 @PostMapping("/{postId}/like") public LikeState like(@PathVariable("postId") String postId,@RequestParam("campusId") String campusId){check(postId,campusId);var result=actions.like(postId);return new LikeState(result.active(),result.count());}
 @DeleteMapping("/{postId}/like") public LikeState unlike(@PathVariable("postId") String postId,@RequestParam("campusId") String campusId){check(postId,campusId);var result=actions.unlike(postId);return new LikeState(result.active(),result.count());}
 private void check(String postId,String campusId){var post=publicQueries.post(postId);if(!post.campusId().equals(campusId))throw new ForbiddenException("动态不属于当前 Campus");authorization.requireActiveMember(campusId);}
 record PostInput(@NotBlank @Size(max=36) String campusId,@NotBlank @Size(max=2000) String textContent) {}
 record CommentInput(@NotBlank @Size(max=36) String campusId,@NotBlank @Size(max=1000) String content) {}
 record Receipt(String id,String reviewStatus) {} record LikeState(boolean active,long count) {}
}
