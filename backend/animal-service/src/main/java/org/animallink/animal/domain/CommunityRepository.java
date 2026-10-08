package org.animallink.animal.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CommunityRepository {
    void insertPost(Post post, List<PostMedia> media);

    void insertPostAndTimeline(Post post, List<PostMedia> media, TimelineEntry timelineEntry);

    void updatePost(Post post);

    Optional<Post> findPostById(String postId);

    Optional<Post> findActivePublicPostById(String postId);

    List<CommunityPostView> findFeed(String campusId, String currentUserId, int limit, int offset);

    long countFeed(String campusId);

    Optional<CommunityPostView> findPostView(String postId, String currentUserId);

    Map<String, List<PostMedia>> findPublicMediaByPostIds(List<String> postIds);

    void insertComment(Comment comment);

    List<Comment> findComments(String postId, int limit, int offset);

    long countComments(String postId);

    void insertLike(String userId, String postId);

    int deleteLike(String userId, String postId);

    long countLikes(String postId);

    void insertFollow(String userId, String animalId);

    int deleteFollow(String userId, String animalId);

    List<FollowedAnimalView> findFollows(String userId, int limit, int offset);

    long countFollows(String userId);

    long countFollowers(String animalId);
}
