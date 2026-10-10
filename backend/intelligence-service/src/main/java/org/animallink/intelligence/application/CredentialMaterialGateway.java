package org.animallink.intelligence.application;

public interface CredentialMaterialGateway {
    CredentialMaterial load(String verificationId);

    record CredentialMaterial(byte[] content, String contentType) {
    }
}
