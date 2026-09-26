package org.animallink.animal.application;

import org.animallink.animal.domain.ForbiddenException;
import org.springframework.stereotype.Service;

@Service
public class GovernanceAuthorization {
    private final IdentityGateway identityGateway;

    public GovernanceAuthorization(IdentityGateway identityGateway) {
        this.identityGateway = identityGateway;
    }

    public IdentityGateway.CurrentUser requireAdministrator() {
        IdentityGateway.CurrentUser user = identityGateway.requireCurrentUser();
        if (!"ACTIVE".equals(user.accountStatus()) || !"GOVERNANCE_ADMIN".equals(user.systemRole())) {
            throw new ForbiddenException("需要治理管理员权限");
        }
        return user;
    }
}
