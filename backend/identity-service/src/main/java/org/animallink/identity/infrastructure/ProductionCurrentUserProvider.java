package org.animallink.identity.infrastructure;

import org.animallink.identity.application.CurrentUserProvider;
import org.animallink.identity.domain.UnauthorizedException;
import org.animallink.identity.domain.UserAccount;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !dev & !test")
public class ProductionCurrentUserProvider implements CurrentUserProvider {
    @Override
    public UserAccount requireCurrentUser() {
        throw new UnauthorizedException("当前运行环境尚未配置正式认证提供方");
    }
}
