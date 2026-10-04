package org.animallink.adoption.domain;

public class ListingAlreadySelectedException extends RuntimeException {
    public ListingAlreadySelectedException(String message) { super(message); }
}
