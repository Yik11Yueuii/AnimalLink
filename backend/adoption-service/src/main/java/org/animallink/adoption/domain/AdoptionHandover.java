package org.animallink.adoption.domain;
import java.time.Instant; import java.util.UUID;
public record AdoptionHandover(String id,String selectionId,HandoverStatus status,Instant scheduledAt,String initiatedByUserId,Instant initiatedAt,String note,String completedByUserId,Instant completedAt,String cancelledByUserId,Instant cancelledAt,String cancelReason,Instant createdAt,Instant updatedAt){
 public static AdoptionHandover pending(String selectionId,Instant scheduledAt,String user,String note){Instant n=Instant.now();return new AdoptionHandover(UUID.randomUUID().toString(),selectionId,HandoverStatus.PENDING,scheduledAt,user,n,note,null,null,null,null,null,n,n);}
}
