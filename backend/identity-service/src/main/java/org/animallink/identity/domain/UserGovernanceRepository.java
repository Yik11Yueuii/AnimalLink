package org.animallink.identity.domain;

import java.util.List;
import java.util.Optional;

public interface UserGovernanceRepository {
    Optional<UserAccount> findUserByIdForUpdate(String userId);

    int transitionStatus(String userId, AccountStatus expected, AccountStatus next);

    void insertAudit(UserGovernanceAudit audit);

    List<UserGovernanceAudit> findAudits(String userId, int limit, int offset);

    long countAudits(String userId);
}
