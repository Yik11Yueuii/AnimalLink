package org.animallink.animal.domain;

public class SelectedAnimalNotFoundException extends RuntimeException {
    public SelectedAnimalNotFoundException(String message) {
        super(message);
    }
}
