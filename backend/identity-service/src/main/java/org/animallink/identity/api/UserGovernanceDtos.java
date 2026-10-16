package org.animallink.identity.api;

import org.animallink.identity.application.UserGovernanceHistoryPage;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserGovernanceAudit;

import java.time.Instant;
import java.util.List;

public final class UserGovernanceDtos {
    private UserGovernanceDtos() {
    }

    public record ReasonRequest(String reason) {
    }

    public record AdminUserResponse(
            String userId,
            String displayName,
            String accountStatus,
            String systemRole) {
        public static AdminUserResponse from(UserAccount user) {
            return new AdminUserResponse(user.id(), user.displayName(),
                    user.accountStatus().name(), user.systemRole().name());
        }
    }

    public record GovernanceActionResponse(
            String auditId,
            String userId,
            String action,
            String fromStatus,
            String toStatus,
            String actorUserId,
            String reason,
            Instant createdAt) {
        public static GovernanceActionResponse from(UserGovernanceAudit audit) {
            return new GovernanceActionResponse(audit.id(), audit.userId(), audit.action().name(),
                    audit.fromStatus().name(), audit.toStatus().name(), audit.actorUserId(),
                    audit.reason(), audit.createdAt());
        }
    }

    public record GovernanceHistoryResponse(
            List<GovernanceActionResponse> items,
            int page,
            int size,
            long total) {
        public static GovernanceHistoryResponse from(UserGovernanceHistoryPage page) {
            return new GovernanceHistoryResponse(
                    page.items().stream().map(GovernanceActionResponse::from).toList(),
                    page.page(), page.size(), page.total());
        }
    }
}
