package org.animallink.animal.application;

import org.animallink.animal.domain.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CommunityQueryService {
    private final CommunityRepository repository;
    private final IdentityGateway identityGateway;
    private final CampusMembershipAuthorization authorization;

    public CommunityQueryService(CommunityRepository repository, IdentityGateway identityGateway,
                                 CampusMembershipAuthorization authorization) {
        this.repository = repository;
        this.identityGateway = identityGateway;
        this.authorization = authorization;
    }

    public PageResult<CommunityPostDetail> feed(String campusId, int page, int size) {
        String validCampusId = IdRules.requireUuid(campusId, "campusId");
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(size, 1), 50);
        String userId = identityGateway.currentUserIfPresent().map(IdentityGateway.CurrentUser::id)
                .orElse(null);
        List<CommunityPostView> views = repository.findFeed(validCampusId, userId, safeSize,
                safePage * safeSize);
        return new PageResult<>(details(views), safePage, safeSize, repository.countFeed(validCampusId));
    }

    public CommunityPostDetail post(String postId) {
        String validPostId = IdRules.requireUuid(postId, "postId");
        String userId = identityGateway.currentUserIfPresent().map(IdentityGateway.CurrentUser::id)
                .orElse(null);
        CommunityPostView view = repository.findPostView(validPostId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Post 不存在或不可见"));
        return details(List.of(view)).getFirst();
    }

    public PageResult<CommentView> comments(String postId, int page, int size) {
        String validPostId = IdRules.requireUuid(postId, "postId");
        repository.findActivePublicPostById(validPostId)
                .orElseThrow(() -> new ResourceNotFoundException("Post 不存在或不可见"));
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Comment> comments = repository.findComments(validPostId, safeSize, safePage * safeSize);
        Map<String, IdentityGateway.UserSummary> authors = authorMap(comments.stream()
                .map(Comment::authorUserId).collect(Collectors.toSet()));
        List<CommentView> views = comments.stream()
                .map(comment -> new CommentView(comment, author(comment.authorUserId(), authors)))
                .toList();
        return new PageResult<>(views, safePage, safeSize, repository.countComments(validPostId));
    }

    public PageResult<FollowedAnimalView> myFollows(int page, int size) {
        String userId = authorization.requireActiveUser().id();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return new PageResult<>(repository.findFollows(userId, safeSize, safePage * safeSize),
                safePage, safeSize, repository.countFollows(userId));
    }

    private List<CommunityPostDetail> details(List<CommunityPostView> views) {
        if (views.isEmpty()) {
            return List.of();
        }
        Map<String, IdentityGateway.UserSummary> authors = authorMap(views.stream()
                .map(view -> view.post().authorUserId()).collect(Collectors.toSet()));
        Map<String, List<PostMedia>> media = repository.findPublicMediaByPostIds(views.stream()
                .map(view -> view.post().id()).toList());
        return views.stream().map(view -> new CommunityPostDetail(view,
                        author(view.post().authorUserId(), authors),
                        media.getOrDefault(view.post().id(), List.of())))
                .toList();
    }

    private Map<String, IdentityGateway.UserSummary> authorMap(Set<String> userIds) {
        return identityGateway.userSummaries(userIds).values().stream()
                .collect(Collectors.toMap(IdentityGateway.UserSummary::id, Function.identity()));
    }

    private AuthorSummary author(String userId, Map<String, IdentityGateway.UserSummary> authors) {
        IdentityGateway.UserSummary summary = authors.get(userId);
        return summary == null ? new AuthorSummary(userId, "未知用户")
                : new AuthorSummary(summary.id(), summary.displayName());
    }
}
