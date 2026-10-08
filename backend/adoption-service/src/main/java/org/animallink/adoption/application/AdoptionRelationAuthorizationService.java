package org.animallink.adoption.application;

import java.util.UUID;
import org.animallink.adoption.domain.ActiveAdoptionRelationRequiredException;
import org.animallink.adoption.domain.AdoptionRelation;
import org.springframework.stereotype.Service;

@Service
public class AdoptionRelationAuthorizationService {
    private final AdoptionHandoverRepository relations;
    private final IdentityGateway identity;

    public AdoptionRelationAuthorizationService(AdoptionHandoverRepository relations,
                                                IdentityGateway identity) {
        this.relations = relations;
        this.identity = identity;
    }

    public String requireActiveRelation(String animalId) {
        validateUuid(animalId, "animalId");
        IdentityGateway.CurrentUser caller = identity.requireCurrentUser();
        if (!"ACTIVE".equals(caller.accountStatus())) {
            throw new ActiveAdoptionRelationRequiredException("需要有效的领养关系");
        }
        AdoptionRelation relation = relations.findActiveRelationByAnimalAndAdopter(animalId, caller.id())
                .orElseThrow(() -> new ActiveAdoptionRelationRequiredException("需要有效的领养关系"));
        return relation.id();
    }

    private static void validateUuid(String value, String name) {
        try {
            UUID.fromString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(name + " 必须是 UUID");
        }
    }
}
