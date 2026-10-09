package org.animallink.identity;

import org.animallink.identity.application.CredentialObjectStorage;
import org.animallink.identity.domain.CredentialObjectMissingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CredentialMaterialIntegrationTest {
    private static final String OWNER = "51000000-0000-0000-0000-000000000011";
    private static final String OTHER = "51000000-0000-0000-0000-000000000012";
    private static final String ADMIN = "51000000-0000-0000-0000-000000000013";
    private static final String CAMPUS = "52000000-0000-0000-0000-000000000001";
    private static final String OTHER_CAMPUS = "52000000-0000-0000-0000-000000000002";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animallink_identity_credential_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean CredentialObjectStorage storage;

    @BeforeEach
    void resetDatabase() {
        reset(storage);
        jdbc.update("DELETE FROM volunteer_membership");
        jdbc.update("DELETE FROM campus_membership");
        jdbc.update("DELETE FROM credential_precheck_attempt");
        jdbc.update("DELETE FROM campus_verification");
        jdbc.update("DELETE FROM credential_material");
        jdbc.update("DELETE FROM campus");
        jdbc.update("DELETE FROM `user`");
        user(OWNER, "申请人", "USER"); user(OTHER, "其他用户", "USER"); user(ADMIN, "管理员", "GOVERNANCE_ADMIN");
        campus(CAMPUS, "测试大学"); campus(OTHER_CAMPUS, "另一大学");
    }

    @Test
    void uploadIntentCompletionAndControlledReadKeepObjectKeyPrivate() throws Exception {
        when(storage.createUploadUrl(anyString(), anyString(), anyInt()))
                .thenReturn(new CredentialObjectStorage.SignedUrl("https://storage.test/upload", Instant.parse("2026-10-09T01:00:00Z")));
        String materialId = mvc.perform(post("/api/v1/credential-materials/upload-intents")
                        .header("X-User-Id", OWNER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.materialId").isNotEmpty())
                .andExpect(jsonPath("$.uploadUrl").value("https://storage.test/upload"))
                .andExpect(jsonPath("$.objectKey").doesNotExist()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\\\"materialId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        when(storage.read(anyString())).thenReturn(image("image/jpeg", jpeg()));
        mvc.perform(post("/api/v1/credential-materials/{id}/complete", materialId).header("X-User-Id", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.objectKey").doesNotExist());
        mvc.perform(post("/api/v1/credential-materials/{id}/complete", materialId).header("X-User-Id", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));

        when(storage.createReadUrl(anyString(), anyInt()))
                .thenReturn(new CredentialObjectStorage.SignedUrl("https://storage.test/read", Instant.parse("2026-10-09T01:10:00Z")));
        mvc.perform(get("/api/v1/credential-materials/{id}/read-url", materialId).header("X-User-Id", OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readUrl").value("https://storage.test/read"))
                .andExpect(jsonPath("$.objectKey").doesNotExist());
        mvc.perform(get("/api/v1/credential-materials/{id}/read-url", materialId).header("X-User-Id", ADMIN))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/credential-materials/{id}/read-url", materialId).header("X-User-Id", OTHER))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/credential-materials/upload-intents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void completionAcceptsSupportedSignaturesAndInvalidatesMismatches() throws Exception {
        for (var sample : new Object[][]{{"image/jpeg", jpeg()}, {"image/png", png()}, {"image/webp", webp()}}) {
            String id = pendingMaterial(OWNER);
            when(storage.read(anyString())).thenReturn(image((String) sample[0], (byte[]) sample[1]));
            mvc.perform(post("/api/v1/credential-materials/{id}/complete", id).header("X-User-Id", OWNER))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        }
        String invalid = pendingMaterial(OWNER);
        when(storage.read(anyString())).thenReturn(image("image/jpeg", png()));
        mvc.perform(post("/api/v1/credential-materials/{id}/complete", invalid).header("X-User-Id", OWNER))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_CREDENTIAL_MATERIAL"));
        assertThat(materialStatus(invalid)).isEqualTo("INVALID");

        String missing = pendingMaterial(OWNER);
        when(storage.read(anyString())).thenThrow(new CredentialObjectMissingException());
        mvc.perform(post("/api/v1/credential-materials/{id}/complete", missing).header("X-User-Id", OWNER))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CREDENTIAL_OBJECT_MISSING"));
        assertThat(materialStatus(missing)).isEqualTo("PENDING_UPLOAD");
    }

    @Test
    void verificationBindsReadyMaterialAndCreatesPendingAttemptWithoutBlockingReview() throws Exception {
        String material = readyMaterial(OWNER);
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(verification(CAMPUS, material)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        String verificationId = jdbc.queryForObject("SELECT id FROM campus_verification WHERE material_media_id=?", String.class, material);
        assertThat(materialStatus(material)).isEqualTo("ATTACHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_precheck_attempt WHERE verification_id=? AND attempt_no=1 AND status='PENDING'", Integer.class, verificationId)).isEqualTo(1);

        mvc.perform(post("/api/v1/admin/campus-verifications/{id}/approve", verificationId).header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_membership WHERE user_id=? AND campus_id=?", Integer.class, OWNER, CAMPUS)).isEqualTo(1);
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", OTHER)
                        .contentType(MediaType.APPLICATION_JSON).content(verification(OTHER_CAMPUS, material)))
                .andExpect(status().isNotFound());
    }

    @Test
    void verificationWithoutMaterialRemainsCompatibleAndCreatesNoPrecheckAttempt() throws Exception {
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(verification(CAMPUS, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.materialMediaId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credential_precheck_attempt", Integer.class)).isZero();
    }

    @Test
    void onlyOwnerReadyAndUnusedMaterialCanBeBound() throws Exception {
        String otherOwned = readyMaterial(OTHER);
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(verification(CAMPUS, otherOwned)))
                .andExpect(status().isNotFound());

        String pending = pendingMaterial(OWNER);
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(verification(CAMPUS, pending)))
                .andExpect(status().isConflict());

        String invalid = pendingMaterial(OWNER);
        jdbc.update("UPDATE credential_material SET status='INVALID' WHERE id=?", invalid);
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(verification(CAMPUS, invalid)))
                .andExpect(status().isConflict());

        String reusable = readyMaterial(OWNER);
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(verification(CAMPUS, reusable)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(verification(OTHER_CAMPUS, reusable)))
                .andExpect(status().isConflict());
    }

    @Test
    void pendingPrecheckDoesNotBlockGovernanceRejection() throws Exception {
        String material = readyMaterial(OWNER);
        mvc.perform(post("/api/v1/campus-verifications").header("X-User-Id", OWNER)
                        .contentType(MediaType.APPLICATION_JSON).content(verification(CAMPUS, material)))
                .andExpect(status().isCreated());
        String verificationId = jdbc.queryForObject("SELECT id FROM campus_verification WHERE material_media_id=?", String.class, material);
        mvc.perform(post("/api/v1/admin/campus-verifications/{id}/reject", verificationId).header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewReason\":\"人工审核拒绝\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_membership WHERE user_id=?", Integer.class, OWNER)).isZero();
    }

    @Test
    void internalCredentialContentRequiresIntelligenceServiceAndAttachedOwnerBinding() throws Exception {
        String material = readyMaterial(OWNER);
        String verificationId = verificationRow(OWNER, CAMPUS, material);
        attach(material);
        when(storage.read(anyString())).thenReturn(image("image/png", png()));
        mvc.perform(get("/internal/v1/campus-verifications/{id}/credential-material/content", verificationId))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/campus-verifications/{id}/credential-material/content", verificationId)
                        .header("X-Internal-Service", "incident-service"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/campus-verifications/{id}/credential-material/content", verificationId)
                        .header("X-Internal-Service", "intelligence-service"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(png()));
    }

    private void user(String id, String name, String role) {
        jdbc.update("INSERT INTO `user`(id,display_name,account_status,system_role) VALUES(?,?, 'ACTIVE', ?)", id, name, role);
    }

    private void campus(String id, String name) {
        jdbc.update("INSERT INTO campus(id,name,city,status) VALUES(?,?, '测试市','ACTIVE')", id, name);
    }

    private String pendingMaterial(String owner) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO credential_material(id,owner_user_id,object_key,status) VALUES(?,?,?,'PENDING_UPLOAD')", id, owner, "credential-materials/" + UUID.randomUUID());
        return id;
    }

    private String readyMaterial(String owner) {
        String id = pendingMaterial(owner);
        jdbc.update("UPDATE credential_material SET status='READY',content_type='image/jpeg',size_bytes=3,uploaded_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        return id;
    }

    private void attach(String material) {
        jdbc.update("UPDATE credential_material SET status='ATTACHED',attached_at=CURRENT_TIMESTAMP(6) WHERE id=?", material);
    }

    private String verificationRow(String owner, String campus, String material) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO campus_verification(id,user_id,campus_id,requested_membership_type,applicant_name,material_media_id,status) VALUES(?,?,?,'STUDENT','申请人',?,'PENDING_REVIEW')", id, owner, campus, material);
        return id;
    }

    private String verification(String campus, String material) {
        String materialField = material == null ? "" : ",\"materialMediaId\":\"%s\"".formatted(material);
        return "{\"campusId\":\"%s\",\"requestedMembershipType\":\"STUDENT\",\"applicantName\":\"申请人\"%s}".formatted(campus, materialField);
    }

    private String materialStatus(String material) {
        return jdbc.queryForObject("SELECT status FROM credential_material WHERE id=?", String.class, material);
    }

    private static CredentialObjectStorage.StoredObject image(String type, byte[] bytes) {
        return new CredentialObjectStorage.StoredObject(type, bytes.length, bytes);
    }

    private static byte[] jpeg() { return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}; }
    private static byte[] png() { return new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}; }
    private static byte[] webp() { return new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}; }
}
