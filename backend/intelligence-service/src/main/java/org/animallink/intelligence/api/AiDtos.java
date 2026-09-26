package org.animallink.intelligence.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.animallink.intelligence.application.AiTaskView;
import org.animallink.intelligence.application.ObservationParseCommand;
import org.animallink.intelligence.domain.AiTaskStatus;
import org.animallink.intelligence.domain.AnimalObservationDraft;

import java.time.Instant;
import java.util.List;

public final class AiDtos {
    private AiDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ParseRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String campusId,
            @NotNull @Size(min = 1, max = 6)
            List<@NotBlank @Size(max = 512) String> mediaObjectKeys,
            @Size(max = 2000) String text,
            @Size(max = 255) String locationDescription,
            @PastOrPresent Instant occurredAt,
            @Null(message = "modelProvider 由服务端决定") String modelProvider,
            @Null(message = "modelName 由服务端决定") String modelName,
            @Null(message = "status 由服务端决定") String status) {
        ObservationParseCommand toCommand() {
            return new ObservationParseCommand(campusId, mediaObjectKeys, blankToNull(text),
                    blankToNull(locationDescription), occurredAt);
        }

        private String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConfirmRequest(@NotNull @Valid AnimalObservationDraft draft) {}

    public record TaskResponse(
            String taskId,
            String taskType,
            AiTaskStatus status,
            String campusId,
            String modelProvider,
            String modelName,
            String promptVersion,
            AnimalObservationDraft originalDraft,
            AnimalObservationDraft confirmedDraft,
            Boolean wasModified,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant failedAt,
            String errorCode,
            String errorMessage,
            Instant confirmedAt) {
        static TaskResponse from(AiTaskView view) {
            var task = view.task();
            return new TaskResponse(task.id(), task.taskType().name(), task.status(), task.campusId(),
                    task.modelProvider(), task.modelName(), task.promptVersion(), view.originalDraft(),
                    view.confirmedDraft(), view.wasModified(), task.createdAt(), task.startedAt(),
                    task.completedAt(), task.failedAt(), task.errorCode(), task.errorMessage(), task.confirmedAt());
        }
    }
}
