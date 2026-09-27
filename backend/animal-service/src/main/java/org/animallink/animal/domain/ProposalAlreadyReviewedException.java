package org.animallink.animal.domain;

public class ProposalAlreadyReviewedException extends RuntimeException {
    public ProposalAlreadyReviewedException(String message) {
        super(message);
    }
}
