package org.animallink.adoption.application;
import java.time.Instant; import java.util.List; import java.util.Optional; import org.animallink.adoption.domain.AdoptionHandover; import org.animallink.adoption.domain.AdoptionRelation;
public interface AdoptionHandoverRepository {
 void insert(AdoptionHandover value); Optional<AdoptionHandover> findById(String id); Optional<AdoptionHandover> findBySelectionId(String id);
 boolean complete(String id,String user,Instant at); boolean cancel(String id,String user,Instant at,String reason);
 void insertRelation(AdoptionRelation relation); Optional<AdoptionRelation> relationByHandover(String handoverId);
 void insertOutbox(String id,String aggregateId,String payload,Instant at); List<ApplicantHandover> findByApplicant(String userId);
 record ApplicantHandover(AdoptionHandover handover,String listingId,String applicationId){}
}
