package org.animallink.intelligence.domain;

public record CredentialPrecheckTaskBundle(
        AiTaskBundle bundle,
        String idempotencyKey,
        String externalReferenceId) {
}
