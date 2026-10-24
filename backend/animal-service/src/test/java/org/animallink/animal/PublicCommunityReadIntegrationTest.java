package org.animallink.animal;

import org.animallink.animal.application.CommunityGovernanceService;
import org.animallink.animal.application.IdentityGateway;
import org.animallink.animal.domain.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PublicCommunityReadIntegrationTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000901";
    private static final String CAMPUS = "10000000-0000-0000-0000-000000000901";
    private static final String ANIMAL = "20000000-0000-0000-0000-000000000901";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.41")
            .withDatabaseName("public_community_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommunityGovernanceService governance;
    @MockBean IdentityGateway identityGateway;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM community_governance_audit");
        jdbc.update("DELETE FROM comment");
        jdbc.update("DELETE FROM post_like");
        jdbc.update("DELETE FROM post_media");
        jdbc.update("DELETE FROM timeline_entry");
        jdbc.update("DELETE FROM post");
        jdbc.update("DELETE FROM animal_merge_record");
        jdbc.update("DELETE FROM animal");
        jdbc.update("""
                INSERT INTO animal
                  (id,campus_id,display_name,species,sex,identity_status,adoption_status,current_context)
                VALUES (?,?,'公开动物','CAT','UNKNOWN','ACTIVE','OPEN','CAMPUS')
                """, ANIMAL, CAMPUS);
        when(identityGateway.requireCurrentUser()).thenReturn(
                new IdentityGateway.CurrentUser(ADMIN, "治理员", "ACTIVE", "GOVERNANCE_ADMIN"));
    }

    @Test
    void onlyApprovedActivePublicPostsAndCommentsAreAnonymous() throws Exception {
        String approved = post("已审核公开正文", "ACTIVE", "PUBLIC", "PENDING");
        String pending = post("待审核正文", "ACTIVE", "PUBLIC", "PENDING");
        String hidden = post("隐藏正文", "HIDDEN", "PUBLIC", "PENDING");
        governance.approvePost(approved, "人工审核通过");
        governance.approvePost(hidden, "人工审核通过但内容仍隐藏");

        String visibleComment = comment(approved, "已审核评论", "ACTIVE", "PENDING");
        comment(approved, "待审核评论", "ACTIVE", "PENDING");
        String hiddenComment = comment(approved, "隐藏评论", "HIDDEN", "PENDING");
        governance.approveComment(visibleComment, "人工审核通过");
        governance.approveComment(hiddenComment, "审核通过但状态隐藏");

        mockMvc.perform(get("/api/v1/public/campuses/{campusId}/feed", CAMPUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(approved))
                .andExpect(jsonPath("$.items[0].textContent").value("已审核公开正文"))
                .andExpect(jsonPath("$.items[0].authorDisplayName").value("校园成员"))
                .andExpect(jsonPath("$.items[0].commentCount").value(1))
                .andExpect(jsonPath("$.items[0].animal.identityStatus").doesNotExist())
                .andExpect(jsonPath("$.items[0].animal.coverObjectKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].authorUserId").doesNotExist())
                .andExpect(jsonPath("$.items[0].media").doesNotExist());

        mockMvc.perform(get("/api/v1/public/posts/{postId}", pending))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/posts/{postId}", hidden))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/posts/{postId}/comments", approved))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(visibleComment))
                .andExpect(jsonPath("$.items[0].authorDisplayName").value("校园成员"))
                .andExpect(jsonPath("$.items[0].authorUserId").doesNotExist());
    }

    @Test
    void parentVisibilityAndReviewAlwaysOverrideApprovedComments() throws Exception {
        String post = post("公开正文", "ACTIVE", "PUBLIC", "PENDING");
        governance.approvePost(post, "审核通过");
        String comment = comment(post, "公开评论", "ACTIVE", "PENDING");
        governance.approveComment(comment, "审核通过");
        governance.hidePost(post, "风险内容隐藏");

        mockMvc.perform(get("/api/v1/public/posts/{postId}/comments", post))
                .andExpect(status().isNotFound());
        governance.restorePost(post, "复核恢复");
        mockMvc.perform(get("/api/v1/public/posts/{postId}/comments", post))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        governance.rejectPost(post, "后续人工复核拒绝");
        mockMvc.perform(get("/api/v1/public/posts/{postId}", post))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_governance_audit "
                + "WHERE resource_id=? AND `action` IN ('APPROVE','REJECT')", Long.class, post))
                .isEqualTo(2L);
    }

    @Test
    void reviewRequiresActiveGovernanceAdministratorAndPaginationIsStrict() throws Exception {
        String post = post("待审正文", "ACTIVE", "PUBLIC", "PENDING");
        when(identityGateway.requireCurrentUser()).thenReturn(
                new IdentityGateway.CurrentUser(ADMIN, "普通用户", "ACTIVE", "USER"));
        assertThatThrownBy(() -> governance.approvePost(post, "越权审核"))
                .isInstanceOf(ForbiddenException.class);
        assertThat(jdbc.queryForObject("SELECT review_status FROM post WHERE id=?", String.class, post))
                .isEqualTo("PENDING");

        mockMvc.perform(get("/api/v1/public/campuses/{campusId}/feed", CAMPUS)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/public/posts/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    private String post(String text, String status, String visibility, String review) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO post
                  (id,campus_id,animal_id,author_user_id,post_type,text_content,visibility,status,
                   review_status,reviewed_by_user_id,reviewed_at)
                VALUES (?,?,?,?,?,?,?, ?, ?, NULL, NULL)
                """, id, CAMPUS, ANIMAL, "00000000-0000-0000-0000-000000000902",
                "CAMPUS_POST", text, visibility, status, review);
        return id;
    }

    private String comment(String postId, String text, String status, String review) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO comment
                  (id,post_id,author_user_id,content,status,review_status,reviewed_by_user_id,reviewed_at)
                VALUES (?,?,?,?,?,?,NULL,NULL)
                """, id, postId, "00000000-0000-0000-0000-000000000903", text, status, review);
        return id;
    }
}
