package org.animallink.animal.application;

import jakarta.validation.ValidationException;
import org.animallink.animal.domain.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CommunityApplicationService {
    private final CommunityRepository communityRepository;
    private final AnimalRepository animalRepository;
    private final CampusMembershipAuthorization membershipAuthorization;
    private final GovernanceAuthorization governanceAuthorization;
    private final AdoptionRelationGateway adoptionRelationGateway;
    private final TransactionTemplate transactions;

    public CommunityApplicationService(CommunityRepository communityRepository,
                                       AnimalRepository animalRepository,
                                       CampusMembershipAuthorization membershipAuthorization,
                                       GovernanceAuthorization governanceAuthorization,
                                       AdoptionRelationGateway adoptionRelationGateway,
                                       TransactionTemplate transactions) {
        this.communityRepository = communityRepository;
        this.animalRepository = animalRepository;
        this.membershipAuthorization = membershipAuthorization;
        this.governanceAuthorization = governanceAuthorization;
        this.adoptionRelationGateway = adoptionRelationGateway;
        this.transactions = transactions;
    }

    public String publish(CreatePostCommand command) {
        String campusId = IdRules.requireUuid(command.campusId(), "campusId");
        IdentityGateway.CurrentUser user = membershipAuthorization.requireActiveMember(campusId);
        String animalId = command.animalId() == null || command.animalId().isBlank()
                ? null : IdRules.requireUuid(command.animalId(), "animalId");
        if (animalId != null) {
            Animal animal = animalRepository.findActiveById(animalId)
                    .orElseThrow(() -> new ResourceNotFoundException("Animal 不存在或不可用"));
            if (!campusId.equals(animal.campusId())) {
                throw new ValidationException("Post 与 Animal 必须属于同一 Campus");
            }
        }
        Post post = Post.campusPost(campusId, animalId, user.id(), command.textContent());
        List<PostMedia> media = postMedia(post, command.media());
        transactions.executeWithoutResult(status -> communityRepository.insertPost(post, media));
        return post.id();
    }

    public String publishPostAdoption(String animalId, CreatePostAdoptionCommand command) {
        String validAnimalId = IdRules.requireUuid(animalId, "animalId");
        IdentityGateway.CurrentUser user = membershipAuthorization.requireActiveUser();
        Animal animal = animalRepository.findById(validAnimalId)
                .orElseThrow(() -> new ResourceNotFoundException("Animal 不存在"));
        requirePostAdoptionEligible(animal);
        String relationId = adoptionRelationGateway.requireActiveRelation(validAnimalId);
        Post post = Post.postAdoption(animal.campusId(), validAnimalId, user.id(), relationId,
                command.textContent());
        List<PostMedia> media = postMedia(post, command.media());
        TimelineEntry timeline = new TimelineEntry(UUID.randomUUID().toString(), validAnimalId,
                "POST", post.id(), "POST_ADOPTION_PUBLISHED", "领养后动态已发布", null,
                post.createdAt(), Visibility.PUBLIC, post.createdAt());
        transactions.executeWithoutResult(status ->
                communityRepository.insertPostAndTimeline(post, media, timeline));
        return post.id();
    }

    public CommentView comment(String postId, String content) {
        String validPostId = IdRules.requireUuid(postId, "postId");
        Post post = activePost(validPostId);
        IdentityGateway.CurrentUser user = membershipAuthorization.requireActiveMember(post.campusId());
        Comment comment = Comment.create(validPostId, user.id(), content);
        transactions.executeWithoutResult(status -> communityRepository.insertComment(comment));
        return new CommentView(comment, new AuthorSummary(user.id(), user.displayName()));
    }

    public EngagementResult like(String postId) {
        String validPostId = IdRules.requireUuid(postId, "postId");
        String userId = membershipAuthorization.requireActiveUser().id();
        activePost(validPostId);
        try {
            transactions.executeWithoutResult(status -> communityRepository.insertLike(userId, validPostId));
        } catch (DuplicateKeyException exception) {
            throw new StateConflictException("已经点赞该 Post");
        }
        return new EngagementResult(true, communityRepository.countLikes(validPostId));
    }

    public EngagementResult unlike(String postId) {
        String validPostId = IdRules.requireUuid(postId, "postId");
        String userId = membershipAuthorization.requireActiveUser().id();
        int deleted = transactions.execute(status -> communityRepository.deleteLike(userId, validPostId));
        return new EngagementResult(false, communityRepository.countLikes(validPostId));
    }

    public EngagementResult follow(String animalId) {
        String validAnimalId = IdRules.requireUuid(animalId, "animalId");
        String userId = membershipAuthorization.requireActiveUser().id();
        animalRepository.findActiveById(validAnimalId)
                .orElseThrow(() -> new ResourceNotFoundException("Animal 不存在或不可用"));
        try {
            transactions.executeWithoutResult(status -> communityRepository.insertFollow(userId, validAnimalId));
        } catch (DuplicateKeyException exception) {
            throw new StateConflictException("已经关注该 Animal");
        }
        return new EngagementResult(true, communityRepository.countFollowers(validAnimalId));
    }

    public EngagementResult unfollow(String animalId) {
        String validAnimalId = IdRules.requireUuid(animalId, "animalId");
        String userId = membershipAuthorization.requireActiveUser().id();
        transactions.execute(status -> communityRepository.deleteFollow(userId, validAnimalId));
        return new EngagementResult(false, communityRepository.countFollowers(validAnimalId));
    }

    public Post hide(String postId) {
        governanceAuthorization.requireAdministrator();
        String validPostId = IdRules.requireUuid(postId, "postId");
        return transactions.execute(status -> {
            Post existing = communityRepository.findPostById(validPostId)
                    .orElseThrow(() -> new ResourceNotFoundException("Post 不存在"));
            communityRepository.updatePost(existing.hide());
            return communityRepository.findPostById(validPostId).orElseThrow();
        });
    }

    private Post activePost(String postId) {
        return communityRepository.findActivePublicPostById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post 不存在或不可见"));
    }

    private List<PostMedia> postMedia(Post post, List<CreatePostCommand.MediaInput> inputs) {
        List<CreatePostCommand.MediaInput> values = inputs == null ? List.of() : inputs;
        return values.stream().map(input -> new PostMedia(
                UUID.randomUUID().toString(), post.id(), input.objectKey(), input.contentType(),
                input.mediaType(), input.sizeBytes(), input.sortOrder(), Visibility.PUBLIC,
                post.createdAt())).toList();
    }

    private void requirePostAdoptionEligible(Animal animal) {
        if (animal.identityStatus() != IdentityStatus.ACTIVE) {
            throw new StateConflictException("Animal 不处于 ACTIVE 状态");
        }
        if (animal.adoptionStatus() != AdoptionStatus.ADOPTED) {
            throw new StateConflictException("Animal 尚未完成领养");
        }
        if (animal.currentContext() != CurrentContext.ADOPTED_HOME) {
            throw new StateConflictException("Animal 不在领养家庭场景");
        }
        IdRules.requireUuid(animal.campusId(), "animal.campusId");
    }
}
