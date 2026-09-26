package org.animallink.intelligence.application;

import org.animallink.intelligence.domain.ApiExceptions.Forbidden;
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
        boolean eligible = "STUDENT".equals(membership.membershipType())
                || "ALUMNI".equals(membership.membershipType());
        if (!membership.exists() || !eligible || !"ACTIVE".equals(membership.status())) {
            throw new Forbidden("需要该 Campus 的有效学生或校友成员身份");
        }
        return user;
    }

    public IdentityGateway.CurrentUser requireActiveUser() {
        IdentityGateway.CurrentUser user = identityGateway.requireCurrentUser();
        if (!"ACTIVE".equals(user.accountStatus())) {
            throw new Forbidden("账号不可用");
        }
        return user;
    }
}
