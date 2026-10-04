package org.animallink.adoption.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.animallink.adoption.application.SelectionView;
import org.animallink.adoption.domain.AdoptionSelection;

public final class AdoptionSelectionDtos {
    private AdoptionSelectionDtos() { }
    public record SelectApplicationRequest(@NotBlank String applicationId, @Size(max = 500) String note) { }
    public record SelectionResponse(String selectionId, String listingId, String applicationId,
                                    String selectedApplicantUserId, String selectedByUserId,
                                    Instant selectedAt, String note, String status) {
        public static SelectionResponse from(SelectionView view) {
            AdoptionSelection value = view.selection();
            return new SelectionResponse(value.id(), value.listingId(), value.applicationId(), view.selectedApplicantUserId(),
                    value.selectedByUserId(), value.selectedAt(), value.note(), value.status().name());
        }
    }
}
