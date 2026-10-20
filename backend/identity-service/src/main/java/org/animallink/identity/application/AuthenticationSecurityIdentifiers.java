package org.animallink.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

final class AuthenticationSecurityIdentifiers {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");
    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private AuthenticationSecurityIdentifiers() {
    }

    static String requireUsername(String username) {
        String normalized = normalizeUsername(username);
        if (normalized == null) {
            throw new IllegalArgumentException(
                    "Username must be 3-64 ASCII letters, digits, dot, underscore or hyphen");
        }
        return normalized;
    }

    static String normalizeUsername(String username) {
        if (username == null) return null;
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return USERNAME.matcher(normalized).matches() ? normalized : null;
    }

    static String usernameForSecurityState(String username) {
        String normalized = normalizeUsername(username);
        if (normalized != null) return normalized;
        if (username == null) return "<invalid:null>";
        String compact = username.trim().toLowerCase(Locale.ROOT);
        return "<invalid:" + sha256(compact).substring(0, 16) + ">";
    }

    static String requireTrustedSource(String trustedSource) {
        if (trustedSource == null || !SOURCE.matcher(trustedSource).matches()) {
            throw new IllegalArgumentException("Trusted source key must be 1-128 safe ASCII characters");
        }
        return sha256("source:" + trustedSource);
    }

    static String usernameKey(String securityUsername) {
        return sha256("username:" + securityUsername);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
