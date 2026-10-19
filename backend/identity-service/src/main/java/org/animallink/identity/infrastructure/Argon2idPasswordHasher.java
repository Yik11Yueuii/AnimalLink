package org.animallink.identity.infrastructure;

import org.animallink.identity.application.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Argon2idPasswordHasher implements PasswordHasher {
    public static final String ALGORITHM = "ARGON2ID_V1";
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);

    @Override
    public String algorithm() {
        return ALGORITHM;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword, String algorithm) {
        return ALGORITHM.equals(algorithm) && encoder.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean needsUpgrade(String encodedPassword, String algorithm) {
        return !ALGORITHM.equals(algorithm) || encoder.upgradeEncoding(encodedPassword);
    }
}
