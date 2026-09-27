package org.animallink.animal.domain;

import org.animallink.animal.application.AnimalIdentityProposalDetail;

import java.util.List;
import java.util.Optional;

public interface ObservationFinalizationRepository {
    Optional<ObservationFinalization> findFinalization(String matchingRecordId);

    void insertAggregate(ObservationFinalization finalization, Post post,
                         List<PostMedia> media, AnimalIdentityProposal proposal);

    Optional<AnimalIdentityProposal> findProposal(String proposalId);

    Optional<AnimalIdentityProposal> lockProposal(String proposalId);

    List<AnimalIdentityProposal> findProposals(AnimalIdentityProposalStatus status,
                                               int limit, int offset);

    long countProposals(AnimalIdentityProposalStatus status);

    AnimalIdentityProposalDetail findProposalDetail(String proposalId);

    void updateProposal(AnimalIdentityProposal proposal);

    void bindPostToAnimal(String postId, String animalId);
}
