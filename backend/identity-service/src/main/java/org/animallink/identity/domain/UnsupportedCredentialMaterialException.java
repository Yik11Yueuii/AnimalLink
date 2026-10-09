package org.animallink.identity.domain;

public class UnsupportedCredentialMaterialException extends RuntimeException {
    public UnsupportedCredentialMaterialException(String message) {
        super(message);
    }
}
