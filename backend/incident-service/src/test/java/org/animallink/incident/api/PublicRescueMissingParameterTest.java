package org.animallink.incident.api;

import org.animallink.incident.application.PublicRescueReadService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicRescueMissingParameterTest {
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new PublicRescueController(mock(PublicRescueReadService.class)))
            .setControllerAdvice(new MissingParameterExceptionHandler(), new IncidentExceptionHandler())
            .build();

    @Test
    void listAndDetailRejectMissingCampus() throws Exception {
        mvc.perform(get("/api/v1/public/rescues"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/public/rescues/{eventId}", "example"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
