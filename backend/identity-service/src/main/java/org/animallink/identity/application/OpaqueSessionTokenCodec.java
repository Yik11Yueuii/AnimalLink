package org.animallink.identity.application;

public interface OpaqueSessionTokenCodec {
    String generate();

    String hash(String rawToken);
}
