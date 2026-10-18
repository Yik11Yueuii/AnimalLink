package org.animallink.animal.domain;

public class IdempotencyKeyReusedException extends StateConflictException {
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
