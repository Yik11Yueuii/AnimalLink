package org.animallink.identity.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.animallink.identity.application.CurrentUserProvider;
import org.animallink.identity.application.IdRules;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.UserAccount;
import org.animallink.identity.domain.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "dev", "test"})
public class DevelopmentHeaderCurrentUserProvider implements CurrentUserProvider {
    public static final String USER_ID_HEADER = "X-User-Id";

    private final HttpServletRequest request;
    private final UserRepository userRepository;

    public DevelopmentHeaderCurrentUserProvider(HttpServletRequest request, UserRepository userRepository) {
        this.request = request;
        this.userRepository = userRepository;
    }

    @Override
    public UserAccount requireCurrentUser() {
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("本地开发请求必须提供 X-User-Id");
        }
        try {
            IdRules.requireUuid(userId, "X-User-Id");
        } catch (RuntimeException exception) {
            throw new UnauthorizedException("X-User-Id 必须是已存在用户的 UUID");
        }
        UserAccount user = userRepository.findUserById(userId)
                .orElseThrow(() -> new UnauthorizedException("X-User-Id 对应的用户不存在"));
        if (!user.isActive()) {
            throw new UnauthorizedException("当前用户账号不可用");
        }
        return user;
    }
}
