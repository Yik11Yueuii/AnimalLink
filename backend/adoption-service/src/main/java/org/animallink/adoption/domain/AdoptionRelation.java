package org.animallink.adoption.domain;
import java.time.Instant; import java.util.UUID;
public record AdoptionRelation(String id,String animalId,String adopterUserId,String handoverId,String status,Instant activatedAt,Instant endedAt,String endReason){
 public static AdoptionRelation active(String animal,String adopter,String handover,Instant at){return new AdoptionRelation(UUID.randomUUID().toString(),animal,adopter,handover,"ACTIVE",at,null,null);}
 public boolean isActive(){return "ACTIVE".equals(status);}
 public boolean isEnded(){return "ENDED".equals(status);}
}
