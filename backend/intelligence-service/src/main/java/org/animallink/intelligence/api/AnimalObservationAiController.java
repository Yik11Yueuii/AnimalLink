package org.animallink.intelligence.api;

import jakarta.validation.Valid;
import org.animallink.intelligence.application.ObservationParsingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class AnimalObservationAiController {
    private final ObservationParsingService service;

    public AnimalObservationAiController(ObservationParsingService service) { this.service = service; }

    @PostMapping("/api/v1/ai/animal-observation/parse")
    @ResponseStatus(HttpStatus.CREATED)
    AiDtos.TaskResponse parse(@Valid @RequestBody AiDtos.ParseRequest request) {
        return AiDtos.TaskResponse.from(service.parse(request.toCommand()));
    }

    @GetMapping("/api/v1/ai/tasks/{taskId}")
    AiDtos.TaskResponse task(@PathVariable("taskId") String taskId) {
        return AiDtos.TaskResponse.from(service.getOwned(taskId));
    }

    @PostMapping("/api/v1/ai/tasks/{taskId}/confirm")
    AiDtos.TaskResponse confirm(@PathVariable("taskId") String taskId,
                                @Valid @RequestBody AiDtos.ConfirmRequest request) {
        return AiDtos.TaskResponse.from(service.confirm(taskId, request.draft()));
    }
}
