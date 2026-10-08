package org.animallink.animal.domain;

public class ActiveAdoptionRelationRequiredException extends RuntimeException {
    public ActiveAdoptionRelationRequiredException(String message) {
        super(message);
    }
}
