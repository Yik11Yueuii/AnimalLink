package org.animallink.identity.domain;

import java.util.List;
import java.util.Optional;

public interface UserRepository {
    Optional<UserAccount> findUserById(String id);

    List<UserAccount> findUsersByIds(List<String> ids);
}
