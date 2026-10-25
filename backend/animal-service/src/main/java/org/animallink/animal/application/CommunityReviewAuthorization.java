package org.animallink.animal.application;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.animal.domain.ForbiddenException;
import org.animallink.animal.domain.UnauthorizedException;
import org.animallink.animal.infrastructure.TrustedAdminAssertionFilter;
import org.animallink.animal.infrastructure.TrustedAdminAssertionVerifier;
import org.springframework.stereotype.Service;

@Service
public class CommunityReviewAuthorization {
    private final HttpServletRequest request;

    public CommunityReviewAuthorization(HttpServletRequest request) {
        this.request = request;
    }

    public TrustedAdminAssertionVerifier.AuthenticatedAdmin requireReader() {
        Object value = request.getAttribute(TrustedAdminAssertionFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof TrustedAdminAssertionVerifier.AuthenticatedAdmin principal)) {
            throw new UnauthorizedException("需要可信管理员身份");
        }
        if (!"GOVERNANCE_ADMIN".equals(principal.systemRole())
                || !TrustedAdminAssertionVerifier.REQUIRED_SCOPE.equals(principal.scope())) {
            throw new ForbiddenException("缺少社区审核读取权限");
        }
        return principal;
    }
}
