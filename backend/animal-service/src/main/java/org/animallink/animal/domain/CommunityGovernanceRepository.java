package org.animallink.animal.domain;

import java.util.List;
import java.util.Optional;

public interface CommunityGovernanceRepository {
    Optional<Post> lockPost(String postId);

    Optional<Comment> lockComment(String commentId);

    void updatePostStatus(Post post, PostStatus expectedStatus);

    void updateCommentStatus(Comment comment, CommentStatus expectedStatus);

    int updatePostAdoptionTimelineVisibility(String postId, Visibility expected, Visibility target);

    void insertAudit(CommunityGovernanceAudit audit);

    List<GovernancePostView> findPosts(PostStatus status, String campusId, PostType postType,
                                       int limit, int offset);

    long countPosts(PostStatus status, String campusId, PostType postType);

    Optional<GovernancePostView> findPost(String postId);

    List<GovernanceCommentView> findComments(CommentStatus status, String postId, String campusId,
                                             int limit, int offset);

    long countComments(CommentStatus status, String postId, String campusId);

    Optional<GovernanceCommentView> findComment(String commentId);

    List<CommunityGovernanceAudit> findAudits(GovernanceResourceType resourceType,
                                              String resourceId, int limit, int offset);

    long countAudits(GovernanceResourceType resourceType, String resourceId);
}
