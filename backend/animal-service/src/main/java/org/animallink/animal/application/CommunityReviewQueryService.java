package org.animallink.animal.application;

import jakarta.validation.ValidationException;
import org.animallink.animal.domain.*;
import org.springframework.stereotype.Service;

@Service
public class CommunityReviewQueryService {
    private final CommunityGovernanceRepository repository;
    private final CommunityReviewAuthorization authorization;

    public CommunityReviewQueryService(CommunityGovernanceRepository repository,
                                       CommunityReviewAuthorization authorization) {
        this.repository = repository;
        this.authorization = authorization;
    }

    public PageResult<GovernancePostView> posts(PublicReviewStatus reviewStatus, String campusId,
                                                PostType postType, CommunityReviewSort sort,
                                                int page, int size) {
        authorization.requireReader();
        PublicReviewStatus requiredReview = requireReviewStatus(reviewStatus);
        String validCampus = optionalUuid(campusId, "campusId");
        Page spec = page(page, size);
        CommunityReviewSort safeSort = sort == null ? CommunityReviewSort.NEWEST : sort;
        return new PageResult<>(repository.findReviewPosts(requiredReview, validCampus, postType,
                safeSort, spec.size(), spec.offset()), spec.page(), spec.size(),
                repository.countReviewPosts(requiredReview, validCampus, postType));
    }

    public GovernancePostView post(String postId) {
        authorization.requireReader();
        return repository.findReviewPost(IdRules.requireUuid(postId, "postId"))
                .orElseThrow(() -> new ResourceNotFoundException("Post 不存在"));
    }

    public PageResult<GovernanceCommentView> comments(PublicReviewStatus reviewStatus,
                                                      String postId, String campusId,
                                                      CommunityReviewSort sort,
                                                      int page, int size) {
        authorization.requireReader();
        PublicReviewStatus requiredReview = requireReviewStatus(reviewStatus);
        String validPost = optionalUuid(postId, "postId");
        String validCampus = optionalUuid(campusId, "campusId");
        Page spec = page(page, size);
        CommunityReviewSort safeSort = sort == null ? CommunityReviewSort.NEWEST : sort;
        return new PageResult<>(repository.findReviewComments(requiredReview, validPost,
                validCampus, safeSort, spec.size(), spec.offset()), spec.page(), spec.size(),
                repository.countReviewComments(requiredReview, validPost, validCampus));
    }

    public GovernanceCommentView comment(String commentId) {
        authorization.requireReader();
        return repository.findReviewComment(IdRules.requireUuid(commentId, "commentId"))
                .orElseThrow(() -> new ResourceNotFoundException("Comment 不存在"));
    }

    private PublicReviewStatus requireReviewStatus(PublicReviewStatus value) {
        return value == null ? PublicReviewStatus.PENDING : value;
    }

    private String optionalUuid(String value, String field) {
        return value == null ? null : IdRules.requireUuid(value, field);
    }

    private Page page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ValidationException("分页参数超出允许范围");
        }
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) throw new ValidationException("page 超出允许范围");
        return new Page(page, size, (int) offset);
    }

    private record Page(int page, int size, int offset) {
    }
}
