package org.animallink.animal;

import org.animallink.animal.api.InternalIncidentFactController;
import org.animallink.animal.application.InternalServiceAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalIncidentFactController.class)
class InternalIncidentFactControllerMvcTest {
    @Autowired MockMvc mvc;
    @MockBean JdbcTemplate jdbc;
    @MockBean InternalServiceAuthorization authorization;

    @Test void animalFactBindsExplicitPathVariable() throws Exception {
        when(jdbc.queryForList(anyString(), eq("animal-1"))).thenReturn(List.of(Map.of("animalId", "animal-1", "campusId", "campus-1", "identityStatus", "ACTIVE")));
        mvc.perform(get("/internal/v1/incident-facts/animals/{id}", "animal-1").header("X-Internal-Service", "incident-service"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.animalId").value("animal-1"));
    }

    @Test void postFactBindsExplicitPathVariable() throws Exception {
        when(jdbc.queryForList(anyString(), eq("post-1"))).thenReturn(List.of(Map.of("postId", "post-1", "campusId", "campus-1", "authorUserId", "user-1", "status", "ACTIVE")));
        mvc.perform(get("/internal/v1/incident-facts/posts/{id}", "post-1").header("X-Internal-Service", "incident-service"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.postId").value("post-1"));
    }
}