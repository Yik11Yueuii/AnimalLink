package org.animallink.animal.domain;

import java.util.List;
import java.util.Optional;

public interface CommunityGovernanceRepository {
    Optional<Post> lockPost(String postId);

    Optional<Comment> lockComment(String commentId);

    void updatePostStatus(Post post, PostStatus expectedStatus);

    void updateCommentStatus(Comment comment, CommentStatus expectedStatus);

    void updatePostReview(Post post, PublicReviewStatus expectedStatus);

    void updateCommentReview(Comment comment, PublicReviewStatus expectedStatus);

    int updatePostAdoptionTimelineVisibility(String postId, Visibility expected, Visibility target);

    long countPostAdoptionTimelines(String postId, Visibility visibility);

    void insertAudit(CommunityGovernanceAudit audit);

    void insertReport(ContentReport report);

    Optional<ContentReport> lockReport(String reportId);

    void updateReport(ContentReport report, int expectedVersion);

    List<GovernanceReportView> findReports(ContentReportStatus status,
                                           GovernanceResourceType resourceType,
                                           String campusId, int limit, int offset);

    long countReports(ContentReportStatus status, GovernanceResourceType resourceType,
                      String campusId);

    Optional<GovernanceReportView> findReport(String reportId);

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
