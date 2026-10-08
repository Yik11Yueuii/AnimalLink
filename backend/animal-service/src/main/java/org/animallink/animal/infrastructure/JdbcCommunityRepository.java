package org.animallink.animal.infrastructure;

import org.animallink.animal.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcCommunityRepository implements CommunityRepository {
    private static final String POST_COLUMNS = """
            p.id, p.campus_id, p.animal_id, p.relation_id, p.author_user_id, p.post_type, p.text_content,
            p.visibility, p.status, p.version, p.created_at, p.updated_at
            """;
    private static final String POST_VIEW_SELECT = """
            SELECT %s,
                   a.display_name AS animal_display_name,
                   a.species AS animal_species,
                   a.identity_status AS animal_identity_status,
                   a.adoption_status AS animal_adoption_status,
                   (SELECT am.object_key FROM animal_media am
                    WHERE am.animal_id = a.id AND am.visibility = 'PUBLIC'
                    ORDER BY am.sort_order ASC, am.id ASC LIMIT 1) AS animal_cover_object_key,
                   (SELECT COUNT(*) FROM post_like pl WHERE pl.post_id = p.id) AS like_count,
                   (SELECT COUNT(*) FROM comment c
                    WHERE c.post_id = p.id AND c.status = 'ACTIVE') AS comment_count,
                   EXISTS(SELECT 1 FROM post_like mine
                          WHERE mine.post_id = p.id AND mine.user_id = ?) AS liked_by_me
            FROM post p
            LEFT JOIN animal a ON a.id = p.animal_id
            """.formatted(POST_COLUMNS);

    private final JdbcTemplate jdbcTemplate;

    public JdbcCommunityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertPost(Post post, List<PostMedia> media) {
        insertPostRows(post, media);
    }

    @Override
    public void insertPostAndTimeline(Post post, List<PostMedia> media, TimelineEntry timelineEntry) {
        insertPostRows(post, media);
        jdbcTemplate.update("""
                INSERT INTO timeline_entry
                    (id, animal_id, source_type, source_id, entry_type, title, summary,
                     occurred_at, visibility, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, timelineEntry.id(), timelineEntry.animalId(), timelineEntry.sourceType(),
                timelineEntry.sourceId(), timelineEntry.entryType(), timelineEntry.title(),
                timelineEntry.summary(), Timestamp.from(timelineEntry.occurredAt()),
                timelineEntry.visibility().name(), Timestamp.from(timelineEntry.createdAt()));
    }

    private void insertPostRows(Post post, List<PostMedia> media) {
        jdbcTemplate.update("""
                INSERT INTO post
                    (id, campus_id, animal_id, relation_id, author_user_id, post_type, text_content,
                     visibility, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, post.id(), post.campusId(), post.animalId(), post.relationId(), post.authorUserId(),
                post.postType().name(), post.textContent(), post.visibility().name(),
                post.status().name(), post.version(), Timestamp.from(post.createdAt()),
                Timestamp.from(post.updatedAt()));
        for (PostMedia item : media) {
            jdbcTemplate.update("""
                    INSERT INTO post_media
                        (id, post_id, object_key, content_type, media_type, size_bytes,
                         sort_order, visibility, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, item.id(), item.postId(), item.objectKey(), item.contentType(),
                    item.mediaType().name(), item.sizeBytes(), item.sortOrder(),
                    item.visibility().name(), Timestamp.from(item.createdAt()));
        }
    }

    @Override
    public void updatePost(Post post) {
        int updated = jdbcTemplate.update("""
                UPDATE post
                SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND version = ?
                """, post.status().name(), post.id(), post.version());
        if (updated != 1) {
            throw new StateConflictException("Post 已被其他操作修改，请刷新后重试");
        }
    }

    @Override
    public Optional<Post> findPostById(String postId) {
        return optional("SELECT " + POST_COLUMNS + " FROM post p WHERE p.id = ?", POST_MAPPER, postId);
    }

    @Override
    public Optional<Post> findActivePublicPostById(String postId) {
        return optional("SELECT " + POST_COLUMNS + " FROM post p"
                + " WHERE p.id = ? AND p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'",
                POST_MAPPER, postId);
    }

    @Override
    public List<CommunityPostView> findFeed(String campusId, String currentUserId,
                                            int limit, int offset) {
        return jdbcTemplate.query(POST_VIEW_SELECT + """
                WHERE p.campus_id = ? AND p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT ? OFFSET ?
                """, POST_VIEW_MAPPER, nullableUser(currentUserId), campusId, limit, offset);
    }

    @Override
    public long countFeed(String campusId) {
        return count("""
                SELECT COUNT(*) FROM post
                WHERE campus_id = ? AND status = 'ACTIVE' AND visibility = 'PUBLIC'
                """, campusId);
    }

    @Override
    public Optional<CommunityPostView> findPostView(String postId, String currentUserId) {
        return optional(POST_VIEW_SELECT
                        + " WHERE p.id = ? AND p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'",
                POST_VIEW_MAPPER, nullableUser(currentUserId), postId);
    }

    @Override
    public Map<String, List<PostMedia>> findPublicMediaByPostIds(List<String> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(postIds.size(), "?"));
        List<PostMedia> media = jdbcTemplate.query("""
                SELECT id, post_id, object_key, content_type, media_type, size_bytes,
                       sort_order, visibility, created_at
                FROM post_media
                WHERE visibility = 'PUBLIC' AND post_id IN (%s)
                ORDER BY post_id, sort_order ASC, id ASC
                """.formatted(placeholders), MEDIA_MAPPER, postIds.toArray());
        Map<String, List<PostMedia>> grouped = new HashMap<>();
        media.forEach(item -> grouped.computeIfAbsent(item.postId(), ignored -> new ArrayList<>())
                .add(item));
        return grouped;
    }

    @Override
    public void insertComment(Comment comment) {
        jdbcTemplate.update("""
                INSERT INTO comment
                    (id, post_id, author_user_id, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, comment.id(), comment.postId(), comment.authorUserId(), comment.content(),
                comment.status().name(), Timestamp.from(comment.createdAt()),
                Timestamp.from(comment.updatedAt()));
    }

    @Override
    public List<Comment> findComments(String postId, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT id, post_id, author_user_id, content, status, created_at, updated_at
                FROM comment
                WHERE post_id = ? AND status = 'ACTIVE'
                ORDER BY created_at ASC, id ASC
                LIMIT ? OFFSET ?
                """, COMMENT_MAPPER, postId, limit, offset);
    }

    @Override
    public long countComments(String postId) {
        return count("SELECT COUNT(*) FROM comment WHERE post_id = ? AND status = 'ACTIVE'", postId);
    }

    @Override
    public void insertLike(String userId, String postId) {
        jdbcTemplate.update("INSERT INTO post_like (user_id, post_id) VALUES (?, ?)", userId, postId);
    }

    @Override
    public int deleteLike(String userId, String postId) {
        return jdbcTemplate.update("DELETE FROM post_like WHERE user_id = ? AND post_id = ?",
                userId, postId);
    }

    @Override
    public long countLikes(String postId) {
        return count("SELECT COUNT(*) FROM post_like WHERE post_id = ?", postId);
    }

    @Override
    public void insertFollow(String userId, String animalId) {
        jdbcTemplate.update("INSERT INTO animal_follow (user_id, animal_id) VALUES (?, ?)",
                userId, animalId);
    }

    @Override
    public int deleteFollow(String userId, String animalId) {
        return jdbcTemplate.update("DELETE FROM animal_follow WHERE user_id = ? AND animal_id = ?",
                userId, animalId);
    }

    @Override
    public List<FollowedAnimalView> findFollows(String userId, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT a.id, a.campus_id, a.display_name, a.species, a.sex, a.coat_color,
                       a.distinctive_features, a.description, a.sterilization_status, a.typical_area,
                       a.identity_status, a.adoption_status, a.current_context, a.version,
                       a.created_at, a.updated_at, af.created_at AS followed_at,
                       (SELECT COUNT(*) FROM animal_follow all_follow
                        WHERE all_follow.animal_id = a.id) AS follower_count
                FROM animal_follow af
                JOIN animal a ON a.id = af.animal_id
                WHERE af.user_id = ? AND a.identity_status = 'ACTIVE'
                ORDER BY af.created_at DESC, a.id DESC
                LIMIT ? OFFSET ?
                """, FOLLOW_MAPPER, userId, limit, offset);
    }

    @Override
    public long countFollows(String userId) {
        return count("""
                SELECT COUNT(*) FROM animal_follow af
                JOIN animal a ON a.id = af.animal_id
                WHERE af.user_id = ? AND a.identity_status = 'ACTIVE'
                """, userId);
    }

    @Override
    public long countFollowers(String animalId) {
        return count("SELECT COUNT(*) FROM animal_follow WHERE animal_id = ?", animalId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String nullableUser(String userId) {
        return userId == null ? "" : userId;
    }

    private <T> Optional<T> optional(String sql, RowMapper<T> mapper, Object... args) {
        return jdbcTemplate.query(sql, mapper, args).stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Post post(ResultSet rs) throws SQLException {
        return new Post(rs.getString("id"), rs.getString("campus_id"), rs.getString("animal_id"),
                rs.getString("relation_id"), rs.getString("author_user_id"), PostType.valueOf(rs.getString("post_type")),
                rs.getString("text_content"), Visibility.valueOf(rs.getString("visibility")),
                PostStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Animal animal(ResultSet rs) throws SQLException {
        return new Animal(rs.getString("id"), rs.getString("campus_id"),
                rs.getString("display_name"), AnimalSpecies.valueOf(rs.getString("species")),
                AnimalSex.valueOf(rs.getString("sex")), rs.getString("coat_color"),
                rs.getString("distinctive_features"), rs.getString("description"),
                SterilizationStatus.valueOf(rs.getString("sterilization_status")),
                rs.getString("typical_area"), IdentityStatus.valueOf(rs.getString("identity_status")),
                AdoptionStatus.valueOf(rs.getString("adoption_status")),
                CurrentContext.valueOf(rs.getString("current_context")), rs.getInt("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static final RowMapper<Post> POST_MAPPER = (rs, rowNum) -> post(rs);
    private static final RowMapper<CommunityPostView> POST_VIEW_MAPPER = (rs, rowNum) ->
            new CommunityPostView(post(rs), rs.getString("animal_display_name"),
                    rs.getString("animal_species") == null ? null
                            : AnimalSpecies.valueOf(rs.getString("animal_species")),
                    rs.getString("animal_identity_status"), rs.getString("animal_adoption_status"),
                    rs.getString("animal_cover_object_key"), rs.getLong("like_count"),
                    rs.getLong("comment_count"), rs.getBoolean("liked_by_me"));
    private static final RowMapper<PostMedia> MEDIA_MAPPER = (rs, rowNum) -> {
        long size = rs.getLong("size_bytes");
        Long sizeBytes = rs.wasNull() ? null : size;
        return new PostMedia(rs.getString("id"), rs.getString("post_id"),
                rs.getString("object_key"), rs.getString("content_type"),
                MediaType.valueOf(rs.getString("media_type")), sizeBytes,
                rs.getInt("sort_order"), Visibility.valueOf(rs.getString("visibility")),
                instant(rs, "created_at"));
    };
    private static final RowMapper<Comment> COMMENT_MAPPER = (rs, rowNum) ->
            new Comment(rs.getString("id"), rs.getString("post_id"),
                    rs.getString("author_user_id"), rs.getString("content"),
                    CommentStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
                    instant(rs, "updated_at"));
    private static final RowMapper<FollowedAnimalView> FOLLOW_MAPPER = (rs, rowNum) ->
            new FollowedAnimalView(animal(rs), instant(rs, "followed_at"),
                    rs.getLong("follower_count"));
}
