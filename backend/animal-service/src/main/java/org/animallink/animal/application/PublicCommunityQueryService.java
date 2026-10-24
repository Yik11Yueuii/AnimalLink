package org.animallink.animal.application;

import jakarta.validation.ValidationException;
import org.animallink.animal.domain.CommunityRepository;
import org.animallink.animal.domain.PublicCommunityCommentView;
import org.animallink.animal.domain.PublicCommunityPostView;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PublicCommunityQueryService {
    private final CommunityRepository repository;

    public PublicCommunityQueryService(CommunityRepository repository) {
        this.repository = repository;
    }

    public PageResult<PublicCommunityPostView> feed(String campusId, int page, int size) {
        String validCampusId = IdRules.requireUuid(campusId, "campusId");
        Page spec = page(page, size, 50);
        return new PageResult<>(repository.findPublicFeed(validCampusId, spec.size(), spec.offset()),
                spec.page(), spec.size(), repository.countPublicFeed(validCampusId));
    }

    public PublicCommunityPostView post(String postId) {
        return repository.findPublicPost(IdRules.requireUuid(postId, "postId"))
                .orElseThrow(() -> new ResourceNotFoundException("公开 Post 不存在"));
    }

    public PageResult<PublicCommunityCommentView> comments(String postId, int page, int size) {
        String validPostId = IdRules.requireUuid(postId, "postId");
        post(validPostId);
        Page spec = page(page, size, 100);
        return new PageResult<>(repository.findPublicComments(validPostId, spec.size(), spec.offset()),
                spec.page(), spec.size(), repository.countPublicComments(validPostId));
    }

    private Page page(int page, int size, int maxSize) {
        if (page < 0 || size < 1 || size > maxSize) {
            throw new ValidationException("分页参数超出允许范围");
        }
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            throw new ValidationException("page 超出允许范围");
        }
        return new Page(page, size, (int) offset);
    }

    private record Page(int page, int size, int offset) {
    }
}
