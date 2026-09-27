package org.animallink.animal.infrastructure;

import org.animallink.animal.application.AnimalIdentityProposalDetail;
import org.animallink.animal.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcObservationFinalizationRepository
        implements ObservationFinalizationRepository {
    private final JdbcTemplate jdbc;

    public JdbcObservationFinalizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ObservationFinalization> findFinalization(String matchingRecordId) {
        return jdbc.query("""
                SELECT id, source_matching_record_id, source_ai_task_id, user_id, campus_id,
                       decision_type, selected_animal_id, post_id, proposal_id,
                       decision_hash, created_at
                FROM observation_finalization
                WHERE source_matching_record_id = ?
                """, this::mapFinalization, matchingRecordId).stream().findFirst();
    }

    @Override
    public void insertAggregate(ObservationFinalization finalization, Post post,
                                List<PostMedia> media, AnimalIdentityProposal proposal) {
        jdbc.update("""
                INSERT INTO post
                    (id, campus_id, animal_id, source_ai_task_id, source_matching_record_id,
                     source_proposal_id, author_user_id, post_type, text_content,
                     visibility, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, post.id(), post.campusId(), post.animalId(),
                finalization.sourceAiTaskId(), finalization.sourceMatchingRecordId(),
                proposal == null ? null : proposal.id(), post.authorUserId(),
                post.postType().name(), post.textContent(), post.visibility().name(),
                post.status().name(), post.version(), Timestamp.from(post.createdAt()),
                Timestamp.from(post.updatedAt()));
        for (PostMedia item : media) {
            jdbc.update("""
                    INSERT INTO post_media
                        (id, post_id, object_key, content_type, media_type, size_bytes,
                         sort_order, visibility, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, item.id(), item.postId(), item.objectKey(), item.contentType(),
                    item.mediaType().name(), item.sizeBytes(), item.sortOrder(),
                    item.visibility().name(), Timestamp.from(item.createdAt()));
        }
        if (proposal != null) {
            jdbc.update("""
                    INSERT INTO animal_identity_proposal
                        (id, campus_id, created_by_user_id, source_ai_task_id,
                         source_matching_record_id, post_id, proposed_species, proposed_sex,
                         proposed_coat_color, proposed_distinctive_features,
                         proposed_description, status, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, proposal.id(), proposal.campusId(), proposal.createdByUserId(),
                    proposal.sourceAiTaskId(), proposal.sourceMatchingRecordId(),
                    proposal.postId(), proposal.proposedSpecies(), proposal.proposedSex(),
                    proposal.proposedCoatColor(), proposal.proposedDistinctiveFeatures(),
                    proposal.proposedDescription(), proposal.status().name(), proposal.version(),
                    Timestamp.from(proposal.createdAt()), Timestamp.from(proposal.updatedAt()));
        }
        jdbc.update("""
                INSERT INTO observation_finalization
                    (id, source_matching_record_id, source_ai_task_id, user_id, campus_id,
                     decision_type, selected_animal_id, post_id, proposal_id,
                     decision_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, finalization.id(), finalization.sourceMatchingRecordId(),
                finalization.sourceAiTaskId(), finalization.userId(), finalization.campusId(),
                finalization.decisionType(), finalization.selectedAnimalId(),
                finalization.postId(), finalization.proposalId(),
                finalization.decisionHash(), Timestamp.from(finalization.createdAt()));
    }

    @Override
    public Optional<AnimalIdentityProposal> findProposal(String proposalId) {
        return jdbc.query(PROPOSAL_SELECT + " WHERE id = ?", this::mapProposal, proposalId)
                .stream().findFirst();
    }

    @Override
    public Optional<AnimalIdentityProposal> lockProposal(String proposalId) {
        return jdbc.query(PROPOSAL_SELECT + " WHERE id = ? FOR UPDATE",
                this::mapProposal, proposalId).stream().findFirst();
    }

    @Override
    public List<AnimalIdentityProposal> findProposals(AnimalIdentityProposalStatus status,
                                                       int limit, int offset) {
        if (status == null) {
            return jdbc.query(PROPOSAL_SELECT + """
                    ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                    """, this::mapProposal, limit, offset);
        }
        return jdbc.query(PROPOSAL_SELECT + """
                WHERE status = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, this::mapProposal, status.name(), limit, offset);
    }

    @Override
    public long countProposals(AnimalIdentityProposalStatus status) {
        String sql = "SELECT COUNT(*) FROM animal_identity_proposal";
        Long value = status == null
                ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql + " WHERE status = ?", Long.class, status.name());
        return value == null ? 0 : value;
    }

    @Override
    public AnimalIdentityProposalDetail findProposalDetail(String proposalId) {
        AnimalIdentityProposal proposal = findProposal(proposalId)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal 不存在"));
        List<PostMedia> media = jdbc.query("""
                SELECT id, post_id, object_key, content_type, media_type, size_bytes,
                       sort_order, visibility, created_at
                FROM post_media WHERE post_id = ?
                ORDER BY sort_order, id
                """, this::mapPostMedia, proposal.postId());
        return new AnimalIdentityProposalDetail(proposal, media);
    }

    @Override
    public void updateProposal(AnimalIdentityProposal proposal) {
        int changed = jdbc.update("""
                UPDATE animal_identity_proposal
                SET status = ?, reviewed_at = ?, reviewed_by = ?, resolution_animal_id = ?,
                    review_reason = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND version = ? AND status = 'PENDING_REVIEW'
                """, proposal.status().name(), timestamp(proposal.reviewedAt()),
                proposal.reviewedBy(), proposal.resolutionAnimalId(), proposal.reviewReason(),
                Timestamp.from(proposal.updatedAt()), proposal.id(), proposal.version());
        if (changed != 1) {
            throw new ProposalAlreadyReviewedException("Proposal 已被其他治理人员审核");
        }
    }

    @Override
    public void bindPostToAnimal(String postId, String animalId) {
        int changed = jdbc.update("""
                UPDATE post SET animal_id = ?, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND animal_id IS NULL
                """, animalId, postId);
        if (changed != 1) {
            throw new StateConflictException("Proposal 来源 Post 已绑定或不存在");
        }
    }

    private ObservationFinalization mapFinalization(ResultSet rs, int rowNum)
            throws SQLException {
        return new ObservationFinalization(rs.getString("id"),
                rs.getString("source_matching_record_id"),
                rs.getString("source_ai_task_id"), rs.getString("user_id"),
                rs.getString("campus_id"), rs.getString("decision_type"),
                rs.getString("selected_animal_id"), rs.getString("post_id"),
                rs.getString("proposal_id"), rs.getString("decision_hash"),
                instant(rs, "created_at"));
    }

    private AnimalIdentityProposal mapProposal(ResultSet rs, int rowNum)
            throws SQLException {
        return new AnimalIdentityProposal(rs.getString("id"), rs.getString("campus_id"),
                rs.getString("created_by_user_id"), rs.getString("source_ai_task_id"),
                rs.getString("source_matching_record_id"), rs.getString("post_id"),
                rs.getString("proposed_species"), rs.getString("proposed_sex"),
                rs.getString("proposed_coat_color"),
                rs.getString("proposed_distinctive_features"),
                rs.getString("proposed_description"),
                AnimalIdentityProposalStatus.valueOf(rs.getString("status")),
                instant(rs, "reviewed_at"), rs.getString("reviewed_by"),
                rs.getString("resolution_animal_id"), rs.getString("review_reason"),
                rs.getInt("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private PostMedia mapPostMedia(ResultSet rs, int rowNum) throws SQLException {
        long size = rs.getLong("size_bytes");
        boolean sizeWasNull = rs.wasNull();
        return new PostMedia(rs.getString("id"), rs.getString("post_id"),
                rs.getString("object_key"), rs.getString("content_type"),
                MediaType.valueOf(rs.getString("media_type")),
                sizeWasNull ? null : size, rs.getInt("sort_order"),
                Visibility.valueOf(rs.getString("visibility")), instant(rs, "created_at"));
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final String PROPOSAL_SELECT = """
            SELECT id, campus_id, created_by_user_id, source_ai_task_id,
                   source_matching_record_id, post_id, proposed_species, proposed_sex,
                   proposed_coat_color, proposed_distinctive_features, proposed_description,
                   status, reviewed_at, reviewed_by, resolution_animal_id, review_reason,
                   version, created_at, updated_at
            FROM animal_identity_proposal
            """;
}
