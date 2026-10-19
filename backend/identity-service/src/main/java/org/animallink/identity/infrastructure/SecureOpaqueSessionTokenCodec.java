package org.animallink.identity.infrastructure;

import org.animallink.identity.application.OpaqueSessionTokenCodec;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class SecureOpaqueSessionTokenCodec implements OpaqueSessionTokenCodec {
    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        byte[] value = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @Override
    public String hash(String rawToken) {
        if (rawToken == null || rawToken.length() != 43) {
            throw new IllegalArgumentException("Invalid opaque session token");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(rawToken);
            if (decoded.length != TOKEN_BYTES) {
                throw new IllegalArgumentException("Invalid opaque session token");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.US_ASCII)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid opaque session token");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
