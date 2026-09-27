package org.animallink.intelligence.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.domain.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcMatchingRepository implements MatchingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcMatchingRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AnimalEmbedding> findEmbedding(String animalId, String mediaId,
                                                    String modelName, String modelVersion) {
        return jdbc.query("""
                SELECT * FROM animal_embedding
                WHERE animal_id = ? AND media_id = ? AND model_name = ? AND model_version = ?
                """, this::mapEmbedding, animalId, mediaId, modelName, modelVersion).stream().findFirst();
    }

    @Override
    public AnimalEmbedding saveEmbeddingOrLoadExisting(AnimalEmbedding embedding) {
        try {
            jdbc.update("""
                    INSERT INTO animal_embedding (id, animal_id, media_id, media_object_key,
                        model_provider, model_name, model_version, vector_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, embedding.id(), embedding.animalId(), embedding.mediaId(),
                    embedding.mediaObjectKey(), embedding.modelProvider(), embedding.modelName(),
                    embedding.modelVersion(), json(embedding.vector()), Timestamp.from(embedding.createdAt()),
                    Timestamp.from(embedding.updatedAt()));
            return embedding;
        } catch (DuplicateKeyException exception) {
            return findEmbedding(embedding.animalId(), embedding.mediaId(),
                    embedding.modelName(), embedding.modelVersion()).orElseThrow(() -> exception);
        }
    }

    @Override
    @Transactional
    public void save(MatchingRecord record) {
        jdbc.update("""
                INSERT INTO matching_record (id, user_id, ai_task_id, campus_id, algorithm_version,
                    weight_version, experiment_code, weights_json, top_k, candidate_count,
                    low_confidence, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id(), record.userId(), record.aiTaskId(), record.campusId(),
                record.algorithmVersion(), record.weightVersion(), record.experiment().name(),
                json(record.weights()), record.topK(), record.candidateCount(), record.lowConfidence(),
                Timestamp.from(record.createdAt()));
        for (MatchingCandidate candidate : record.candidates()) {
            jdbc.update("""
                    INSERT INTO matching_candidate (id, matching_record_id, rank_number, animal_id,
                        display_name, species, cover_media_object_key, image_score, trait_score,
                        geo_score, history_score, final_score, reasons_json,
                        missing_dimensions_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), record.id(), candidate.rank(), candidate.animalId(),
                    candidate.displayName(), candidate.species(), candidate.coverMediaObjectKey(),
                    candidate.imageScore(), candidate.traitScore(), candidate.geoScore(),
                    candidate.historyScore(), candidate.finalScore(), json(candidate.reasons()),
                    json(candidate.missingDimensions()), Timestamp.from(record.createdAt()));
        }
    }

    @Override
    public Optional<MatchingRecord> findById(String recordId) {
        return findRecord(recordId, false);
    }

    @Override
    public Optional<MatchingRecord> lockById(String recordId) {
        return findRecord(recordId, true);
    }

    private Optional<MatchingRecord> findRecord(String recordId, boolean forUpdate) {
        String sql = "SELECT * FROM matching_record WHERE id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        List<MatchingRecord> records = jdbc.query(sql,
                (rs, rowNum) -> new MatchingRecord(rs.getString("id"), rs.getString("user_id"),
                        rs.getString("ai_task_id"), rs.getString("campus_id"),
                        rs.getString("algorithm_version"), rs.getString("weight_version"),
                        MatchingExperiment.valueOf(rs.getString("experiment_code")),
                        readMap(rs.getString("weights_json")), rs.getInt("top_k"),
                        rs.getInt("candidate_count"), rs.getBoolean("low_confidence"),
                        instant(rs, "created_at"), List.of()),
                recordId);
        if (records.isEmpty()) return Optional.empty();
        MatchingRecord base = records.getFirst();
        List<MatchingCandidate> candidates = jdbc.query("""
                SELECT * FROM matching_candidate WHERE matching_record_id = ? ORDER BY rank_number
                """, this::mapCandidate, recordId);
        return Optional.of(new MatchingRecord(base.id(), base.userId(), base.aiTaskId(), base.campusId(),
                base.algorithmVersion(), base.weightVersion(), base.experiment(), base.weights(),
                base.topK(), base.candidateCount(), base.lowConfidence(), base.createdAt(), candidates));
    }

    @Override
    public Optional<MatchingDecision> findDecisionByRecordId(String recordId) {
        return jdbc.query("""
                SELECT id, matching_record_id, user_id, decision_type, selected_animal_id,
                       selected_rank, selected_score, post_id, proposal_id, decided_at
                FROM matching_decision WHERE matching_record_id = ?
                """, this::mapDecision, recordId).stream().findFirst();
    }

    @Override
    public void saveDecision(MatchingDecision decision) {
        jdbc.update("""
                INSERT INTO matching_decision
                    (id, matching_record_id, user_id, decision_type, selected_animal_id,
                     selected_rank, selected_score, post_id, proposal_id, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, decision.id(), decision.matchingRecordId(), decision.userId(),
                decision.decisionType().name(), decision.selectedAnimalId(),
                decision.selectedRank(), decision.selectedScore(), decision.postId(),
                decision.proposalId(), Timestamp.from(decision.decidedAt()));
    }

    private AnimalEmbedding mapEmbedding(ResultSet rs, int rowNum) throws SQLException {
        return new AnimalEmbedding(rs.getString("id"), rs.getString("animal_id"),
                rs.getString("media_id"), rs.getString("media_object_key"),
                rs.getString("model_provider"), rs.getString("model_name"),
                rs.getString("model_version"), readList(rs.getString("vector_json")),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private MatchingCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new MatchingCandidate(rs.getInt("rank_number"), rs.getString("animal_id"),
                rs.getString("display_name"), rs.getString("species"),
                rs.getString("cover_media_object_key"), nullableDouble(rs, "image_score"),
                nullableDouble(rs, "trait_score"), nullableDouble(rs, "geo_score"),
                nullableDouble(rs, "history_score"), rs.getDouble("final_score"),
                readStringList(rs.getString("reasons_json")),
                readStringList(rs.getString("missing_dimensions_json")));
    }

    private MatchingDecision mapDecision(ResultSet rs, int rowNum) throws SQLException {
        int rank = rs.getInt("selected_rank");
        Integer selectedRank = rs.wasNull() ? null : rank;
        return new MatchingDecision(rs.getString("id"), rs.getString("matching_record_id"),
                rs.getString("user_id"),
                MatchingDecisionType.valueOf(rs.getString("decision_type")),
                rs.getString("selected_animal_id"), selectedRank,
                nullableDouble(rs, "selected_score"), rs.getString("post_id"),
                rs.getString("proposal_id"), instant(rs, "decided_at"));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法序列化匹配数据", exception); }
    }

    private List<Double> readList(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法读取 embedding", exception); }
    }

    private List<String> readStringList(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法读取匹配解释", exception); }
    }

    private Map<String, Double> readMap(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法读取匹配权重", exception); }
    }

    private Double nullableDouble(ResultSet rs, String name) throws SQLException {
        double value = rs.getDouble(name);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
