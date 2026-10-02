package org.animallink.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class VolunteerMembershipIntegrationTest {
    private static final String USER = "00000000-0000-0000-0000-000000000301";
    private static final String OTHER = "00000000-0000-0000-0000-000000000302";
    private static final String ADMIN = "00000000-0000-0000-0000-000000000399";
    private static final String CAMPUS = "10000000-0000-0000-0000-000000000301";
    private static final String OTHER_CAMPUS = "10000000-0000-0000-0000-000000000302";
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_volunteer_test").withUsername("animallink_test").withPassword("animallink_test_password");
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl); registry.add("spring.datasource.username", MYSQL::getUsername); registry.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM volunteer_membership"); jdbc.update("DELETE FROM campus_membership"); jdbc.update("DELETE FROM campus_verification"); jdbc.update("DELETE FROM campus"); jdbc.update("DELETE FROM `user`");
        user(USER, "USER"); user(OTHER, "USER"); user(ADMIN, "GOVERNANCE_ADMIN"); campus(CAMPUS); campus(OTHER_CAMPUS);
    }

    @Test void activeStudentAndAlumniCanApplyWhileMissingOrInactiveCampusMembershipIsDenied() throws Exception {
        membership(USER, CAMPUS, "STUDENT", "ACTIVE");
        mvc.perform(post("/api/v1/volunteer-memberships").header("X-User-Id", USER).contentType("application/json").content(request(CAMPUS)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        mvc.perform(post("/api/v1/volunteer-memberships").header("X-User-Id", USER).contentType("application/json").content(request(CAMPUS)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VOLUNTEER_APPLICATION_EXISTS"));
        mvc.perform(post("/api/v1/volunteer-memberships").header("X-User-Id", OTHER).contentType("application/json").content(request(CAMPUS)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CAMPUS_MEMBERSHIP_REQUIRED"));
        membership(OTHER, OTHER_CAMPUS, "ALUMNI", "ACTIVE");
        mvc.perform(post("/api/v1/volunteer-memberships").header("X-User-Id", OTHER).contentType("application/json").content(request(OTHER_CAMPUS)))
                .andExpect(status().isCreated());
        String third = "00000000-0000-0000-0000-000000000303"; user(third, "USER"); membership(third, CAMPUS, "STUDENT", "REVERIFY_REQUIRED");
        mvc.perform(post("/api/v1/volunteer-memberships").header("X-User-Id", third).contentType("application/json").content(request(CAMPUS)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CAMPUS_MEMBERSHIP_REQUIRED"));
    }

    @Test void governanceReviewAndUserStateMachineEnforceTransitionsAndPermissions() throws Exception {
        membership(USER, CAMPUS, "STUDENT", "ACTIVE"); String id = apply(USER, CAMPUS);
        mvc.perform(post("/api/v1/admin/volunteer-memberships/{id}/approve", id).header("X-User-Id", USER).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GOVERNANCE_REQUIRED"));
        mvc.perform(post("/api/v1/admin/volunteer-memberships/{id}/approve", id).header("X-User-Id", ADMIN).contentType("application/json").content("{\"reviewReason\":\"approved\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.activatedAt").exists());
        mvc.perform(get("/api/v1/admin/volunteer-memberships").header("X-User-Id", ADMIN).param("campusId", CAMPUS).param("status", "ACTIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/v1/volunteer-memberships").header("X-User-Id", USER).param("campusId", CAMPUS))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(post("/api/v1/volunteer-memberships/{id}/pause", id).header("X-User-Id", USER)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
        mvc.perform(post("/api/v1/volunteer-memberships/{id}/resume", id).header("X-User-Id", USER)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/volunteer-memberships/{id}/exit", id).header("X-User-Id", USER)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXITED"));
        mvc.perform(post("/api/v1/volunteer-memberships/{id}/resume", id).header("X-User-Id", USER)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_VOLUNTEER_TRANSITION"));
        mvc.perform(post("/api/v1/volunteer-memberships/{id}/pause", UUID.randomUUID()).header("X-User-Id", USER))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VOLUNTEER_MEMBERSHIP_NOT_FOUND"));
    }

    @Test void rejectAndRevokeSupportBothAllowedGovernanceTransitions() throws Exception {
        membership(USER, CAMPUS, "STUDENT", "ACTIVE"); String rejected = apply(USER, CAMPUS);
        mvc.perform(post("/api/v1/admin/volunteer-memberships/{id}/reject", rejected).header("X-User-Id", ADMIN).contentType("application/json").content("{\"reviewReason\":\"insufficient\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        membership(OTHER, OTHER_CAMPUS, "STUDENT", "ACTIVE"); String active = apply(OTHER, OTHER_CAMPUS); approve(active);
        mvc.perform(post("/api/v1/admin/volunteer-memberships/{id}/revoke", active).header("X-User-Id", ADMIN).contentType("application/json").content("{\"reviewReason\":\"policy\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        String paused = insertVolunteer(USER, OTHER_CAMPUS, "PAUSED");
        mvc.perform(post("/api/v1/admin/volunteer-memberships/{id}/revoke", paused).header("X-User-Id", ADMIN).contentType("application/json").content("{\"reviewReason\":\"policy\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        String fourth = "00000000-0000-0000-0000-000000000304"; user(fourth, "USER");
        String pausable = insertVolunteer(fourth, OTHER_CAMPUS, "ACTIVE");
        mvc.perform(post("/api/v1/volunteer-memberships/{id}/pause", pausable).header("X-User-Id", fourth)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/volunteer-memberships/{id}/exit", pausable).header("X-User-Id", fourth)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXITED"));
    }

    @Test void internalFactHasExplicitMvcBindingAndDoesNotExposePrivateFields() throws Exception {
        String active = insertVolunteer(USER, CAMPUS, "ACTIVE");
        mvc.perform(get("/internal/v1/users/{user}/campuses/{campus}/volunteer-membership", USER, CAMPUS))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/users/{user}/campuses/{campus}/volunteer-membership", USER, CAMPUS).header("X-Internal-Service", "incident-service"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(true)).andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.applicationNote").doesNotExist()).andExpect(jsonPath("$.reviewReason").doesNotExist());
        jdbc.update("UPDATE volunteer_membership SET status = 'PAUSED' WHERE id = ?", active);
        mvc.perform(get("/internal/v1/users/{user}/campuses/{campus}/volunteer-membership", USER, CAMPUS).header("X-Internal-Service", "incident-service"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/internal/v1/users/{user}/campuses/{campus}/volunteer-membership", OTHER, CAMPUS).header("X-Internal-Service", "incident-service"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
    }

    @Test void databaseUniqueCheckAndForeignKeysAreEnforced() {
        insertVolunteer(USER, CAMPUS, "ACTIVE");
        assertThatThrownBy(() -> insertVolunteer(USER, CAMPUS, "PAUSED")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO volunteer_membership (id,user_id,campus_id,status,version) VALUES (?,?,?,?,0)", UUID.randomUUID().toString(), UUID.randomUUID().toString(), CAMPUS, "ACTIVE")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO volunteer_membership (id,user_id,campus_id,status,reviewed_by,version) VALUES (?,?,?,?,?,0)", UUID.randomUUID().toString(), USER, OTHER_CAMPUS, "ACTIVE", UUID.randomUUID().toString())).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO volunteer_membership (id,user_id,campus_id,status,version) VALUES (?,?,?,?,0)", UUID.randomUUID().toString(), USER, CAMPUS, "INVALID")).isInstanceOf(Exception.class);
    }

    private String apply(String user, String campus) throws Exception { mvc.perform(post("/api/v1/volunteer-memberships").header("X-User-Id", user).contentType("application/json").content(request(campus))).andExpect(status().isCreated()); return jdbc.queryForObject("SELECT id FROM volunteer_membership WHERE user_id=? AND campus_id=?", String.class, user, campus); }
    private void approve(String id) throws Exception { mvc.perform(post("/api/v1/admin/volunteer-memberships/{id}/approve", id).header("X-User-Id", ADMIN).contentType("application/json").content("{}")).andExpect(status().isOk()); }
    private void user(String id, String role) { jdbc.update("INSERT INTO `user` (id,display_name,account_status,system_role) VALUES (?,?,'ACTIVE',?)", id, id, role); }
    private void campus(String id) { jdbc.update("INSERT INTO campus (id,name,city,status) VALUES (?,?,'city','ACTIVE')", id, id); }
    private void membership(String user, String campus, String type, String status) { String verification = UUID.randomUUID().toString(); jdbc.update("INSERT INTO campus_verification (id,user_id,campus_id,requested_membership_type,applicant_name,status) VALUES (?,?,?,?,?,'APPROVED')", verification,user,campus,type,"test"); jdbc.update("INSERT INTO campus_membership (id,user_id,campus_id,membership_type,status,approved_at,last_verification_id) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(6),?)",UUID.randomUUID().toString(),user,campus,type,status,verification); }
    private String insertVolunteer(String user, String campus, String status) { String id=UUID.randomUUID().toString(); jdbc.update("INSERT INTO volunteer_membership (id,user_id,campus_id,status,version) VALUES (?,?,?,?,0)",id,user,campus,status); return id; }
    private static String request(String campus) { return "{\"campusId\":\"" + campus + "\",\"applicationNote\":\"help\"}"; }
}
