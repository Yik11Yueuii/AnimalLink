package org.animallink.animal.application;

import org.animallink.animal.domain.CommunityGovernanceRepository;
import org.animallink.animal.domain.GovernanceCommentView;
import org.animallink.animal.domain.GovernancePostView;
import org.animallink.animal.domain.ResourceNotFoundException;
import org.animallink.animal.infrastructure.TrustedAdminAssertionVerifier;
import org.springframework.stereotype.Service;

@Service
public class CommunityReviewCommandService {
    private final CommunityReviewAuthorization authorization;
    private final CommunityGovernanceService governanceService;
    private final CommunityGovernanceRepository repository;

    public CommunityReviewCommandService(CommunityReviewAuthorization authorization,
                                         CommunityGovernanceService governanceService,
                                         CommunityGovernanceRepository repository) {
        this.authorization = authorization;
        this.governanceService = governanceService;
        this.repository = repository;
    }

    public GovernancePostView approvePost(String postId, String reason) {
        return post(governanceService.approvePostAsTrustedAdmin(postId, reason, actor()).id());
    }

    public GovernancePostView rejectPost(String postId, String reason) {
        return post(governanceService.rejectPostAsTrustedAdmin(postId, reason, actor()).id());
    }

    public GovernanceCommentView approveComment(String commentId, String reason) {
        return comment(governanceService.approveCommentAsTrustedAdmin(commentId, reason, actor()).id());
    }

    public GovernanceCommentView rejectComment(String commentId, String reason) {
        return comment(governanceService.rejectCommentAsTrustedAdmin(commentId, reason, actor()).id());
    }

    private GovernancePostView post(String postId) {
        return repository.findReviewPost(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post 不存在"));
    }

    private GovernanceCommentView comment(String commentId) {
        return repository.findReviewComment(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment 不存在"));
    }

    private IdentityGateway.CurrentUser actor() {
        TrustedAdminAssertionVerifier.AuthenticatedAdmin principal = authorization.requireWriter();
        return new IdentityGateway.CurrentUser(principal.userId(), null, "ACTIVE",
                principal.systemRole());
    }
}
