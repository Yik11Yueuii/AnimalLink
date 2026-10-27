package org.animallink.identity;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class UniversityFoundationIntegrationTest {
    private static final String HANSHAN_ID = "21000000-0000-4000-8000-000000001010";
    private static final String ZHONGSHAN_ID = "21000000-0000-4000-8000-000000001001";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("university_foundation_test")
            .withUsername("animallink_test")
            .withPassword("animallink_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationLoadsCuratedUniversitiesWithoutInventingPhysicalCampuses() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM university", Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='8' AND success=1",
                Integer.class)).isOne();

        mockMvc.perform(get("/api/v1/universities")
                        .param("province", "广东省").param("q", "韩山").param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(HANSHAN_ID))
                .andExpect(jsonPath("$.items[0].moeInstitutionCode").value("4144010578"))
                .andExpect(jsonPath("$.items[0].officialName").value("韩山师范学院"))
                .andExpect(jsonPath("$.items[0].city").value("潮州市"))
                .andExpect(jsonPath("$.items[0].communityAvailable").value(true));

        mockMvc.perform(get("/api/v1/universities/{id}/community", ZHONGSHAN_ID))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_membership", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM campus WHERE university_id <> ?", Integer.class, HANSHAN_ID)).isZero();
    }

    @Test
    void hanshanHasOneStableLogicalCommunityRatherThanPhysicalAreaCommunities() throws Exception {
        mockMvc.perform(get("/api/v1/universities/{id}/community", HANSHAN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("22000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.name").value("韩山师范学院社区"))
                .andExpect(jsonPath("$.campusType").value("UNIVERSITY_COMMUNITY"))
                .andExpect(jsonPath("$.address").doesNotExist());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM campus WHERE university_id=?", Integer.class, HANSHAN_ID)).isOne();
        assertThat(jdbc.queryForList(
                "SELECT name FROM campus WHERE university_id=?", String.class, HANSHAN_ID))
                .containsExactly("韩山师范学院社区");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM campus WHERE name IN ('东区','西区','中区','南区')", Integer.class))
                .isZero();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO campus
                    (id, university_id, name, short_name, city, campus_type, status)
                VALUES (?, ?, '重复学校社区', '重复社区', '潮州市', 'UNIVERSITY_COMMUNITY', 'ACTIVE')
                """, UUID.randomUUID().toString(), HANSHAN_ID))
                .hasMessageContaining("Duplicate entry");
    }

    @Test
    void physicalCampusMetadataDoesNotOpenAUniversityCommunity() throws Exception {
        jdbc.update("""
                INSERT INTO campus
                    (id, university_id, name, short_name, city, campus_type, status)
                VALUES (?, ?, '资料测试校区', '测试校区', '广州市', 'CAMPUS_AREA', 'ACTIVE')
                """, UUID.randomUUID().toString(), ZHONGSHAN_ID);

        mockMvc.perform(get("/api/v1/universities")
                        .param("province", "广东省").param("q", "中山大学"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].communityAvailable").value(false));
        mockMvc.perform(get("/api/v1/universities/{id}/community", ZHONGSHAN_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchSupportsPaginationAndCodes() throws Exception {
        mockMvc.perform(get("/api/v1/universities")
                        .param("province", "广东省").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.total").value(16))
                .andExpect(jsonPath("$.items.length()").value(5));

        mockMvc.perform(get("/api/v1/universities")
                        .param("q", "4144010578"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(HANSHAN_ID));
    }

    @Test
    void legacyCampusIdsAndMembershipScopeRemainCompatible() throws Exception {
        String legacyCampusId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO campus (id, name, short_name, city, address, status)
                VALUES (?, '兼容测试校园', '兼容校园', '测试市', '仅限内部的位置描述', 'ACTIVE')
                """, legacyCampusId);

        mockMvc.perform(get("/api/v1/campuses/{id}", legacyCampusId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(legacyCampusId))
                .andExpect(jsonPath("$.universityId").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.campusType").value("LEGACY"));

        assertThat(jdbc.queryForObject(
                "SELECT id FROM campus WHERE id=?", String.class, legacyCampusId)).isEqualTo(legacyCampusId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO campus (id, name, short_name, city, status)
                VALUES (?, '兼容测试校园', '重复校园', '测试市', 'ACTIVE')
                """, UUID.randomUUID().toString()))
                .hasMessageContaining("Duplicate entry");
    }

    @Test
    void unknownOrMalformedUniversityCannotResolveCommunity() throws Exception {
        mockMvc.perform(get("/api/v1/universities/{id}/community", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/universities/not-a-uuid/community"))
                .andExpect(status().isBadRequest());
    }
}
