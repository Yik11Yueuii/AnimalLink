package org.animallink.animal;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.animallink.animal.application.AdoptionMergeEligibilityGateway;
import org.animallink.animal.application.AnimalMergeService;
import org.animallink.animal.application.IdentityGateway;
import org.animallink.animal.domain.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Isolated fixtures + real MySQL/MinIO; not a media creation business API E2E. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AnimalMediaMergeMinioIntegrationTest {
    private static final String CAMPUS = "10000000-0000-0000-0000-000000000001";
    private static final String BUCKET = "phase5gd2-media";
    private static final String ADMIN = "00000000-0000-0000-0000-000000000002";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("animal_media_merge_test").withUsername("test").withPassword("test");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "animallink-minio:RELEASE.2025-10-15T17-29-55Z"))
            .withEnv("MINIO_ROOT_USER", "isolated-test-admin")
            .withEnv("MINIO_ROOT_PASSWORD", "isolated-test-secret-5gd2")
            .withExposedPorts(9000)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
        registry.add("animallink.messaging.enabled", () -> false);
        registry.add("animallink.minio.endpoint", AnimalMediaMergeMinioIntegrationTest::endpoint);
        registry.add("animallink.minio.access-key", () -> "isolated-test-admin");
        registry.add("animallink.minio.secret-key", () -> "isolated-test-secret-5gd2");
        registry.add("animallink.minio.bucket", () -> BUCKET);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MinioClient minio;
    @Autowired MockMvc mvc;
    @Autowired AnimalMergeService merges;
    @MockBean IdentityGateway identity;
    @MockBean AdoptionMergeEligibilityGateway adoption;

    @BeforeEach
    void authorize() {
        when(identity.requireCurrentUser()).thenReturn(
                new IdentityGateway.CurrentUser(ADMIN, "admin", "ACTIVE", "GOVERNANCE_ADMIN"));
        when(adoption.check(anyString(), anyString())).thenReturn(
                new AdoptionMergeEligibilityGateway.Eligibility(true));
    }

    @Test
    void mergePreservesObjectsMetadataPrivacyAndUniqueAssociations() throws Exception {
        // Only disposable DB/object-store fixtures are written here.
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        String source = animal("source");
        String target = animal("target");
        String unrelated = animal("unrelated");
        String sourceKey = "animals/" + source + "/public.png";
        String restrictedKey = "animals/" + source + "/restricted.png";
        String targetKey = "animals/" + target + "/public.png";
        String unrelatedKey = "animals/" + unrelated + "/public.png";
        byte[] sourceBytes = "source-public-fixture".getBytes(StandardCharsets.UTF_8);
        byte[] restrictedBytes = "source-restricted-fixture".getBytes(StandardCharsets.UTF_8);
        byte[] targetBytes = "target-public-fixture".getBytes(StandardCharsets.UTF_8);
        byte[] unrelatedBytes = "unrelated-fixture".getBytes(StandardCharsets.UTF_8);
        String sourceMedia = media(source, sourceKey, "PUBLIC", 2, sourceBytes);
        String restrictedMedia = media(source, restrictedKey, "RESTRICTED", 2, restrictedBytes);
        String targetMedia = media(target, targetKey, "PUBLIC", 2, targetBytes);
        String unrelatedMedia = media(unrelated, unrelatedKey, "PUBLIC", 0, unrelatedBytes);
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM animal_media ORDER BY id");
        assertThat(read(sourceKey)).isEqualTo(sourceBytes);
        assertThat(read(targetKey)).isEqualTo(targetBytes);
        assertPublic(source, source, List.of(sourceKey));
        assertPublic(target, target, List.of(targetKey));

        merges.merge(source, target, "isolated media verification");

        assertThat(jdbc.queryForList("SELECT id FROM animal_media WHERE animal_id=?",
                String.class, target)).containsExactlyInAnyOrder(sourceMedia, restrictedMedia, targetMedia);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM animal_media WHERE animal_id=?",
                Integer.class, source)).isZero();
        for (Map<String, Object> old : before) {
            Map<String, Object> after = jdbc.queryForMap("SELECT * FROM animal_media WHERE id=?", old.get("id"));
            assertThat(after.get("animal_id")).isEqualTo(
                    source.equals(old.get("animal_id")) ? target : old.get("animal_id"));
            after.remove("animal_id");
            old.remove("animal_id");
            assertThat(after).isEqualTo(old);
        }
        assertPublic(source, target, List.of(sourceKey, targetKey));
        assertPublic(target, target, List.of(sourceKey, targetKey));
        assertPublic(unrelated, unrelated, List.of(unrelatedKey));
        assertThat(jdbc.queryForObject("SELECT animal_id FROM animal_media WHERE id=?",
                String.class, unrelatedMedia)).isEqualTo(unrelated);

        List<Map<String, Object>> merged = jdbc.queryForList("SELECT * FROM animal_media ORDER BY id");
        assertThatThrownBy(() -> merges.merge(source, target, "retry"))
                .isInstanceOf(StateConflictException.class);
        assertThat(jdbc.queryForList("SELECT * FROM animal_media ORDER BY id")).isEqualTo(merged);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM animal_merge_record",
                Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO animal_media(id,animal_id,object_key,content_type,media_type,visibility)
                VALUES(?,?,?,'image/png','IMAGE','PUBLIC')
                """, UUID.randomUUID().toString(), target, sourceKey))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(read(sourceKey)).isEqualTo(sourceBytes);
        assertThat(read(restrictedKey)).isEqualTo(restrictedBytes);
        assertThat(read(targetKey)).isEqualTo(targetBytes);
        assertThat(read(unrelatedKey)).isEqualTo(unrelatedBytes);
        int objects = 0;
        for (var object : minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build())) {
            object.get();
            objects++;
        }
        assertThat(objects).isEqualTo(4);
        // Even a known object key does not grant anonymous access to the private bucket.
        try (HttpClient http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(
                    URI.create(endpoint() + "/" + BUCKET + "/" + restrictedKey)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(403);
        }
        System.out.printf("D2_MEDIA_EVIDENCE source=%s target=%s unrelated=%s objects=4 media=4 mergeRecords=1 anonymousRestrictedGet=403%n",
                source, target, unrelated);
    }

    private String animal(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO animal(id,campus_id,display_name,species,sex,identity_status,adoption_status,current_context)
                VALUES(?,?,?,'CAT','UNKNOWN','ACTIVE','NOT_OPEN','CAMPUS')
                """, id, CAMPUS, name);
        return id;
    }

    private String media(String animal, String key, String visibility, int order, byte[] bytes) throws Exception {
        minio.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key)
                .contentType("image/png").stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO animal_media(id,animal_id,object_key,content_type,media_type,size_bytes,sort_order,visibility)
                VALUES(?,?,?,'image/png','IMAGE',?,?,?)
                """, id, animal, key, bytes.length, order, visibility);
        return id;
    }

    private byte[] read(String key) throws Exception {
        try (var input = minio.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) {
            return input.readAllBytes();
        }
    }

    private void assertPublic(String requested, String canonical, List<String> keys) throws Exception {
        var result = mvc.perform(get("/api/v1/animals/{id}", requested))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalAnimalId").value(canonical))
                .andReturn();
        var payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString());
        java.util.ArrayList<String> actual = new java.util.ArrayList<>();
        payload.path("media").forEach(item -> actual.add(item.path("objectKey").asText()));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(keys);
    }

    private static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
