package org.animallink.intelligence;

import org.animallink.intelligence.api.InternalIncidentProvenanceController;
import org.animallink.intelligence.domain.AiTaskRepository;
import org.animallink.intelligence.domain.MatchingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalIncidentProvenanceController.class)
class InternalIncidentProvenanceControllerMvcTest {
    @Autowired MockMvc mvc;
    @MockBean AiTaskRepository tasks;
    @MockBean MatchingRepository matches;

    @Test void aiTaskFactBindsExplicitPathVariable() throws Exception {
        when(tasks.findBundle("task-1")).thenReturn(Optional.empty());
        mvc.perform(get("/internal/v1/incident-facts/ai-tasks/{id}", "task-1").header("X-Internal-Service", "incident-service"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
    }

    @Test void matchingFactBindsExplicitPathVariable() throws Exception {
        when(matches.findById("match-1")).thenReturn(Optional.empty());
        mvc.perform(get("/internal/v1/incident-facts/matches/{id}", "match-1").header("X-Internal-Service", "incident-service"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
    }
}