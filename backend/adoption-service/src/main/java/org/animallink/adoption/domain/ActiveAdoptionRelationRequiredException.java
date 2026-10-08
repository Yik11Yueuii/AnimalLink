package org.animallink.adoption.domain;

public class ActiveAdoptionRelationRequiredException extends RuntimeException {
    public ActiveAdoptionRelationRequiredException(String message) {
        super(message);
    }
}
