package org.animallink.identity.application;

public interface PasswordHasher {
    String algorithm();

    String encode(CharSequence rawPassword);

    boolean matches(CharSequence rawPassword, String encodedPassword, String algorithm);

    boolean needsUpgrade(String encodedPassword, String algorithm);
}
