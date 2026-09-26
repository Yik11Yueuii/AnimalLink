package org.animallink.animal.application;

import org.animallink.animal.domain.ForbiddenException;
import org.springframework.stereotype.Service;

@Service
public class CampusMembershipAuthorization {
    private final IdentityGateway identityGateway;

    public CampusMembershipAuthorization(IdentityGateway identityGateway) {
        this.identityGateway = identityGateway;
    }

    public IdentityGateway.CurrentUser requireActiveMember(String campusId) {
        IdentityGateway.CurrentUser user = requireActiveUser();
        IdentityGateway.MembershipFact membership = identityGateway.campusMembership(user.id(), campusId);
        boolean eligibleType = "STUDENT".equals(membership.membershipType())
                || "ALUMNI".equals(membership.membershipType());
        if (!membership.exists() || !eligibleType || !"ACTIVE".equals(membership.status())) {
            throw new ForbiddenException("需要该 Campus 的有效学生或校友成员身份");
        }
        return user;
    }

    public IdentityGateway.CurrentUser requireActiveUser() {
        IdentityGateway.CurrentUser user = identityGateway.requireCurrentUser();
        if (!"ACTIVE".equals(user.accountStatus())) {
            throw new ForbiddenException("账号不可用");
        }
        return user;
    }
}
