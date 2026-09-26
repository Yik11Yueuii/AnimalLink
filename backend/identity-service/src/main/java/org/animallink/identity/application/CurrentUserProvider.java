package org.animallink.identity.application;

import org.animallink.identity.domain.UserAccount;

public interface CurrentUserProvider {
    UserAccount requireCurrentUser();
}
