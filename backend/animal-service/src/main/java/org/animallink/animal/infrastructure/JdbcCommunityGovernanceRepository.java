package org.animallink.animal.infrastructure;

import org.animallink.animal.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCommunityGovernanceRepository implements CommunityGovernanceRepository {
    private static final String POST_COLUMNS = """
            p.id, p.campus_id, p.animal_id, p.relation_id, p.author_user_id, p.post_type,
            p.text_content, p.visibility, p.status, p.review_status, p.reviewed_by_user_id,
            p.reviewed_at, p.version, p.created_at, p.updated_at
            """;
    private static final String GOVERNANCE_POST_SELECT = """
            SELECT %s,
                   (SELECT COUNT(*) FROM post_like pl WHERE pl.post_id = p.id) AS like_count,
                   (SELECT COUNT(*) FROM comment ac
                    WHERE ac.post_id = p.id AND ac.status = 'ACTIVE') AS active_comment_count,
                   (SELECT COUNT(*) FROM comment hc
                    WHERE hc.post_id = p.id AND hc.status = 'HIDDEN') AS hidden_comment_count
            FROM post p
            """.formatted(POST_COLUMNS);
    private static final String GOVERNANCE_COMMENT_SELECT = """
            SELECT c.id, c.post_id, c.author_user_id, c.content, c.status, c.review_status,
                   c.reviewed_by_user_id, c.reviewed_at,
                   c.created_at, c.updated_at, p.campus_id, p.status AS parent_post_status
            FROM comment c
            JOIN post p ON p.id = c.post_id
            """;
    private static final String REPORT_COLUMNS = """
            r.id AS report_id, r.resource_type, r.resource_id, r.reporter_user_id,
            r.description, r.status AS report_status, r.resolution_action, r.resolution_note,
            r.reviewed_by_user_id, r.reviewed_at, r.version AS report_version,
            r.created_at AS report_created_at, r.updated_at AS report_updated_at
            """;
    private static final String GOVERNANCE_REPORT_SELECT = """
            SELECT %s,
                   CASE WHEN r.resource_type = 'POST' THEN rp.author_user_id
                        ELSE rc.author_user_id END AS resource_author_user_id,
                   COALESCE(rp.campus_id, cp.campus_id) AS resource_campus_id,
                   CASE WHEN r.resource_type = 'POST' THEN rp.status
                        ELSE rc.status END AS current_content_status,
                   CASE WHEN r.resource_type = 'POST' THEN rp.text_content
                        ELSE rc.content END AS current_content_text,
                   CASE WHEN r.resource_type = 'COMMENT' THEN cp.status
                        ELSE NULL END AS parent_post_status
            FROM community_content_report r
            LEFT JOIN post rp ON r.resource_type = 'POST' AND rp.id = r.resource_id
            LEFT JOIN comment rc ON r.resource_type = 'COMMENT' AND rc.id = r.resource_id
            LEFT JOIN post cp ON rc.post_id = cp.id
            """.formatted(REPORT_COLUMNS);

    private final JdbcTemplate jdbcTemplate;

    public JdbcCommunityGovernanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Post> lockPost(String postId) {
        return optional("SELECT " + POST_COLUMNS + " FROM post p WHERE p.id = ? FOR UPDATE",
                POST_MAPPER, postId);
    }

    @Override
    public Optional<Comment> lockComment(String commentId) {
        return optional("""
                SELECT id, post_id, author_user_id, content, status, review_status,
                       reviewed_by_user_id, reviewed_at, created_at, updated_at
                FROM comment WHERE id = ? FOR UPDATE
                """, COMMENT_MAPPER, commentId);
    }

    @Override
    public void updatePostStatus(Post post, PostStatus expectedStatus) {
        int updated = jdbcTemplate.update("""
                UPDATE post
                SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND version = ? AND status = ?
                """, post.status().name(), post.id(), post.version(), expectedStatus.name());
        if (updated != 1) {
            throw new StateConflictException("Post 已被其他治理操作修改，请刷新后重试");
        }
    }

    @Override
    public void updateCommentStatus(Comment comment, CommentStatus expectedStatus) {
        int updated = jdbcTemplate.update("""
                UPDATE comment
                SET status = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = ?
                """, comment.status().name(), comment.id(), expectedStatus.name());
        if (updated != 1) {
            throw new StateConflictException("Comment 已被其他治理操作修改，请刷新后重试");
        }
    }

    @Override
    public void updatePostReview(Post post, PublicReviewStatus expectedStatus) {
        int updated = jdbcTemplate.update("""
                UPDATE post
                SET review_status = ?, reviewed_by_user_id = ?, reviewed_at = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND version = ? AND review_status = ?
                """, post.reviewStatus().name(), post.reviewedByUserId(),
                timestamp(post.reviewedAt()), post.id(), post.version(), expectedStatus.name());
        if (updated != 1) {
            throw new StateConflictException("Post 已被其他审核操作修改，请刷新后重试");
        }
    }

    @Override
    public void updateCommentReview(Comment comment, PublicReviewStatus expectedStatus) {
        int updated = jdbcTemplate.update("""
                UPDATE comment
                SET review_status = ?, reviewed_by_user_id = ?, reviewed_at = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND review_status = ?
                """, comment.reviewStatus().name(), comment.reviewedByUserId(),
                timestamp(comment.reviewedAt()), comment.id(), expectedStatus.name());
        if (updated != 1) {
            throw new StateConflictException("Comment 已被其他审核操作修改，请刷新后重试");
        }
    }

    @Override
    public int updatePostAdoptionTimelineVisibility(String postId, Visibility expected,
                                                     Visibility target) {
        return jdbcTemplate.update("""
                UPDATE timeline_entry
                SET visibility = ?
                WHERE source_type = 'POST'
                  AND source_id = ?
                  AND entry_type = 'POST_ADOPTION_PUBLISHED'
                  AND visibility = ?
                """, target.name(), postId, expected.name());
    }

    @Override
    public long countPostAdoptionTimelines(String postId, Visibility visibility) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM timeline_entry
                WHERE source_type = 'POST'
                  AND source_id = ?
                  AND entry_type = 'POST_ADOPTION_PUBLISHED'
                  AND visibility = ?
                """, Long.class, postId, visibility.name());
        return value == null ? 0 : value;
    }

    @Override
    public void insertAudit(CommunityGovernanceAudit audit) {
        jdbcTemplate.update("""
                INSERT INTO community_governance_audit
                    (id, resource_type, resource_id, `action`, from_state, to_state,
                     actor_user_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, audit.id(), audit.resourceType().name(), audit.resourceId(),
                audit.action().name(), audit.fromState(), audit.toState(), audit.actorUserId(),
                audit.reason(), Timestamp.from(audit.createdAt()));
    }

    @Override
    public void insertReport(ContentReport report) {
        jdbcTemplate.update("""
                INSERT INTO community_content_report
                    (id, resource_type, resource_id, reporter_user_id, description, status,
                     resolution_action, resolution_note, reviewed_by_user_id, reviewed_at,
                     version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, report.id(), report.resourceType().name(), report.resourceId(),
                report.reporterUserId(), report.description(), report.status().name(),
                enumName(report.resolutionAction()), report.resolutionNote(),
                report.reviewedByUserId(), timestamp(report.reviewedAt()), report.version(),
                Timestamp.from(report.createdAt()), Timestamp.from(report.updatedAt()));
    }

    @Override
    public Optional<ContentReport> lockReport(String reportId) {
        return optional("SELECT " + REPORT_COLUMNS
                        + " FROM community_content_report r WHERE r.id = ? FOR UPDATE",
                REPORT_MAPPER, reportId);
    }

    @Override
    public void updateReport(ContentReport report, int expectedVersion) {
        int updated = jdbcTemplate.update("""
                UPDATE community_content_report
                SET status = ?, resolution_action = ?, resolution_note = ?,
                    reviewed_by_user_id = ?, reviewed_at = ?, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'OPEN' AND version = ?
                """, report.status().name(), enumName(report.resolutionAction()),
                report.resolutionNote(), report.reviewedByUserId(),
                timestamp(report.reviewedAt()), report.id(), expectedVersion);
        if (updated != 1) {
            throw new StateConflictException("举报已被其他治理操作修改，请刷新后重试");
        }
    }

    @Override
    public List<GovernanceReportView> findReports(ContentReportStatus status,
                                                   GovernanceResourceType resourceType,
                                                   String campusId, int limit, int offset) {
        Sql sql = reportFilter(GOVERNANCE_REPORT_SELECT, status, resourceType, campusId);
        sql.text.append(" ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?");
        sql.args.add(limit);
        sql.args.add(offset);
        return jdbcTemplate.query(sql.text.toString(), REPORT_VIEW_MAPPER, sql.args.toArray());
    }

    @Override
    public long countReports(ContentReportStatus status, GovernanceResourceType resourceType,
                             String campusId) {
        Sql sql = reportFilter("""
                SELECT COUNT(*)
                FROM community_content_report r
                LEFT JOIN post rp ON r.resource_type = 'POST' AND rp.id = r.resource_id
                LEFT JOIN comment rc ON r.resource_type = 'COMMENT' AND rc.id = r.resource_id
                LEFT JOIN post cp ON rc.post_id = cp.id
                """, status, resourceType, campusId);
        return count(sql);
    }

    @Override
    public Optional<GovernanceReportView> findReport(String reportId) {
        return optional(GOVERNANCE_REPORT_SELECT + " WHERE r.id = ?", REPORT_VIEW_MAPPER,
                reportId);
    }

    @Override
    public List<GovernancePostView> findPosts(PostStatus status, String campusId, PostType postType,
                                              int limit, int offset) {
        Sql sql = postFilter(GOVERNANCE_POST_SELECT, status, campusId, postType);
        sql.text.append(" ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?");
        sql.args.add(limit);
        sql.args.add(offset);
        return jdbcTemplate.query(sql.text.toString(), POST_VIEW_MAPPER, sql.args.toArray());
    }

    @Override
    public long countPosts(PostStatus status, String campusId, PostType postType) {
        Sql sql = postFilter("SELECT COUNT(*) FROM post p", status, campusId, postType);
        return count(sql);
    }

    @Override
    public Optional<GovernancePostView> findPost(String postId) {
        Optional<GovernancePostView> result = optional(
                GOVERNANCE_POST_SELECT + " WHERE p.id = ?", POST_VIEW_MAPPER, postId);
        return result.map(view -> new GovernancePostView(view.post(), view.likeCount(),
                view.activeCommentCount(), view.hiddenCommentCount(), findMedia(postId)));
    }

    @Override
    public List<GovernanceCommentView> findComments(CommentStatus status, String postId,
                                                    String campusId, int limit, int offset) {
        Sql sql = commentFilter(GOVERNANCE_COMMENT_SELECT, status, postId, campusId);
        sql.text.append(" ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?");
        sql.args.add(limit);
        sql.args.add(offset);
        return jdbcTemplate.query(sql.text.toString(), COMMENT_VIEW_MAPPER, sql.args.toArray());
    }

    @Override
    public long countComments(CommentStatus status, String postId, String campusId) {
        Sql sql = commentFilter("SELECT COUNT(*) FROM comment c JOIN post p ON p.id = c.post_id",
                status, postId, campusId);
        return count(sql);
    }

    @Override
    public Optional<GovernanceCommentView> findComment(String commentId) {
        return optional(GOVERNANCE_COMMENT_SELECT + " WHERE c.id = ?", COMMENT_VIEW_MAPPER,
                commentId);
    }

    @Override
    public List<GovernancePostView> findReviewPosts(PublicReviewStatus reviewStatus,
                                                    String campusId, PostType postType,
                                                    CommunityReviewSort sort,
                                                    int limit, int offset) {
        Sql sql = postFilter(GOVERNANCE_POST_SELECT, null, campusId, postType);
        sql.text.append(" AND p.review_status = ?");
        sql.args.add(reviewStatus.name());
        appendReviewOrder(sql, sort, "p");
        sql.text.append(" LIMIT ? OFFSET ?");
        sql.args.add(limit);
        sql.args.add(offset);
        return jdbcTemplate.query(sql.text.toString(), POST_VIEW_MAPPER, sql.args.toArray());
    }

    @Override
    public long countReviewPosts(PublicReviewStatus reviewStatus, String campusId,
                                 PostType postType) {
        Sql sql = postFilter("SELECT COUNT(*) FROM post p", null, campusId, postType);
        sql.text.append(" AND p.review_status = ?");
        sql.args.add(reviewStatus.name());
        return count(sql);
    }

    @Override
    public Optional<GovernancePostView> findReviewPost(String postId) {
        return optional(GOVERNANCE_POST_SELECT + " WHERE p.id = ?", POST_VIEW_MAPPER, postId);
    }

    @Override
    public List<GovernanceCommentView> findReviewComments(PublicReviewStatus reviewStatus,
                                                          String postId, String campusId,
                                                          CommunityReviewSort sort,
                                                          int limit, int offset) {
        Sql sql = commentFilter(GOVERNANCE_COMMENT_SELECT, null, postId, campusId);
        sql.text.append(" AND c.review_status = ?");
        sql.args.add(reviewStatus.name());
        appendReviewOrder(sql, sort, "c");
        sql.text.append(" LIMIT ? OFFSET ?");
        sql.args.add(limit);
        sql.args.add(offset);
        return jdbcTemplate.query(sql.text.toString(), COMMENT_VIEW_MAPPER, sql.args.toArray());
    }

    @Override
    public long countReviewComments(PublicReviewStatus reviewStatus, String postId,
                                    String campusId) {
        Sql sql = commentFilter("SELECT COUNT(*) FROM comment c JOIN post p ON p.id = c.post_id",
                null, postId, campusId);
        sql.text.append(" AND c.review_status = ?");
        sql.args.add(reviewStatus.name());
        return count(sql);
    }

    @Override
    public Optional<GovernanceCommentView> findReviewComment(String commentId) {
        return optional(GOVERNANCE_COMMENT_SELECT + " WHERE c.id = ?", COMMENT_VIEW_MAPPER,
                commentId);
    }

    @Override
    public List<CommunityGovernanceAudit> findAudits(GovernanceResourceType resourceType,
                                                     String resourceId, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT id, resource_type, resource_id, `action`, from_state, to_state,
                       actor_user_id, reason, created_at
                FROM community_governance_audit
                WHERE resource_type = ? AND resource_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, AUDIT_MAPPER, resourceType.name(), resourceId, limit, offset);
    }

    @Override
    public long countAudits(GovernanceResourceType resourceType, String resourceId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM community_governance_audit
                WHERE resource_type = ? AND resource_id = ?
                """, Long.class, resourceType.name(), resourceId);
        return value == null ? 0 : value;
    }

    private List<PostMedia> findMedia(String postId) {
        return jdbcTemplate.query("""
                SELECT id, post_id, object_key, content_type, media_type, size_bytes,
                       sort_order, visibility, created_at
                FROM post_media
                WHERE post_id = ?
                ORDER BY sort_order ASC, id ASC
                """, MEDIA_MAPPER, postId);
    }

    private Sql postFilter(String select, PostStatus status, String campusId, PostType postType) {
        Sql sql = new Sql(select);
        sql.text.append(" WHERE 1=1");
        if (status != null) {
            sql.text.append(" AND p.status = ?");
            sql.args.add(status.name());
        }
        if (campusId != null) {
            sql.text.append(" AND p.campus_id = ?");
            sql.args.add(campusId);
        }
        if (postType != null) {
            sql.text.append(" AND p.post_type = ?");
            sql.args.add(postType.name());
        }
        return sql;
    }

    private Sql commentFilter(String select, CommentStatus status, String postId, String campusId) {
        Sql sql = new Sql(select);
        sql.text.append(" WHERE 1=1");
        if (status != null) {
            sql.text.append(" AND c.status = ?");
            sql.args.add(status.name());
        }
        if (postId != null) {
            sql.text.append(" AND c.post_id = ?");
            sql.args.add(postId);
        }
        if (campusId != null) {
            sql.text.append(" AND p.campus_id = ?");
            sql.args.add(campusId);
        }
        return sql;
    }

    private void appendReviewOrder(Sql sql, CommunityReviewSort sort, String alias) {
        String direction = sort == CommunityReviewSort.OLDEST ? " ASC" : " DESC";
        sql.text.append(" ORDER BY ").append(alias).append(".created_at").append(direction)
                .append(", ").append(alias).append(".id").append(direction);
    }

    private Sql reportFilter(String select, ContentReportStatus status,
                             GovernanceResourceType resourceType, String campusId) {
        Sql sql = new Sql(select);
        sql.text.append(" WHERE 1=1");
        if (status != null) {
            sql.text.append(" AND r.status = ?");
            sql.args.add(status.name());
        }
        if (resourceType != null) {
            sql.text.append(" AND r.resource_type = ?");
            sql.args.add(resourceType.name());
        }
        if (campusId != null) {
            sql.text.append(" AND COALESCE(rp.campus_id, cp.campus_id) = ?");
            sql.args.add(campusId);
        }
        return sql;
    }

    private long count(Sql sql) {
        Long value = jdbcTemplate.queryForObject(sql.text.toString(), Long.class,
                sql.args.toArray());
        return value == null ? 0 : value;
    }

    private <T> Optional<T> optional(String sql, RowMapper<T> mapper, Object... args) {
        return jdbcTemplate.query(sql, mapper, args).stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Post post(ResultSet rs) throws SQLException {
        return new Post(rs.getString("id"), rs.getString("campus_id"), rs.getString("animal_id"),
                rs.getString("relation_id"), rs.getString("author_user_id"),
                PostType.valueOf(rs.getString("post_type")), rs.getString("text_content"),
                Visibility.valueOf(rs.getString("visibility")),
                PostStatus.valueOf(rs.getString("status")),
                PublicReviewStatus.valueOf(rs.getString("review_status")),
                rs.getString("reviewed_by_user_id"), instant(rs, "reviewed_at"), rs.getInt("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static final RowMapper<Post> POST_MAPPER = (rs, rowNum) -> post(rs);
    private static final RowMapper<GovernancePostView> POST_VIEW_MAPPER = (rs, rowNum) ->
            new GovernancePostView(post(rs), rs.getLong("like_count"),
                    rs.getLong("active_comment_count"), rs.getLong("hidden_comment_count"),
                    List.of());
    private static final RowMapper<Comment> COMMENT_MAPPER = (rs, rowNum) ->
            new Comment(rs.getString("id"), rs.getString("post_id"),
                    rs.getString("author_user_id"), rs.getString("content"),
                    CommentStatus.valueOf(rs.getString("status")),
                    PublicReviewStatus.valueOf(rs.getString("review_status")),
                    rs.getString("reviewed_by_user_id"), instant(rs, "reviewed_at"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));
    private static final RowMapper<GovernanceCommentView> COMMENT_VIEW_MAPPER = (rs, rowNum) ->
            new GovernanceCommentView(COMMENT_MAPPER.mapRow(rs, rowNum), rs.getString("campus_id"),
                    PostStatus.valueOf(rs.getString("parent_post_status")));
    private static final RowMapper<PostMedia> MEDIA_MAPPER = (rs, rowNum) -> {
        long size = rs.getLong("size_bytes");
        Long sizeBytes = rs.wasNull() ? null : size;
        return new PostMedia(rs.getString("id"), rs.getString("post_id"),
                rs.getString("object_key"), rs.getString("content_type"),
                MediaType.valueOf(rs.getString("media_type")), sizeBytes,
                rs.getInt("sort_order"), Visibility.valueOf(rs.getString("visibility")),
                instant(rs, "created_at"));
    };
    private static final RowMapper<CommunityGovernanceAudit> AUDIT_MAPPER = (rs, rowNum) ->
            new CommunityGovernanceAudit(rs.getString("id"),
                    GovernanceResourceType.valueOf(rs.getString("resource_type")),
                    rs.getString("resource_id"),
                    GovernanceAction.valueOf(rs.getString("action")),
                    rs.getString("from_state"), rs.getString("to_state"),
                    rs.getString("actor_user_id"), rs.getString("reason"),
                    instant(rs, "created_at"));
    private static final RowMapper<ContentReport> REPORT_MAPPER = (rs, rowNum) ->
            new ContentReport(rs.getString("report_id"),
                    GovernanceResourceType.valueOf(rs.getString("resource_type")),
                    rs.getString("resource_id"), rs.getString("reporter_user_id"),
                    rs.getString("description"),
                    ContentReportStatus.valueOf(rs.getString("report_status")),
                    rs.getString("resolution_action") == null ? null
                            : ReportResolutionAction.valueOf(rs.getString("resolution_action")),
                    rs.getString("resolution_note"), rs.getString("reviewed_by_user_id"),
                    instant(rs, "reviewed_at"), rs.getInt("report_version"),
                    instant(rs, "report_created_at"), instant(rs, "report_updated_at"));
    private static final RowMapper<GovernanceReportView> REPORT_VIEW_MAPPER = (rs, rowNum) -> {
        String parentStatus = rs.getString("parent_post_status");
        return new GovernanceReportView(REPORT_MAPPER.mapRow(rs, rowNum),
                rs.getString("resource_author_user_id"), rs.getString("resource_campus_id"),
                rs.getString("current_content_status"), rs.getString("current_content_text"),
                parentStatus == null ? null : PostStatus.valueOf(parentStatus));
    };

    private static final class Sql {
        private final StringBuilder text;
        private final List<Object> args = new ArrayList<>();

        private Sql(String select) {
            this.text = new StringBuilder(select);
        }
    }
}
