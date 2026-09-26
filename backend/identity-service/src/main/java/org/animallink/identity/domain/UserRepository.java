package org.animallink.identity.domain;

import java.util.Optional;

public interface UserRepository {
    Optional<UserAccount> findUserById(String id);
}
