package org.animallink.intelligence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.animallink.intelligence.domain.*;
import org.animallink.intelligence.domain.ApiExceptions.*;
import org.animallink.intelligence.infrastructure.MatchingWeightProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class CandidateMatchingService {
    public static final String ALGORITHM_VERSION = "candidate-fusion-v1";
    public static final String WEIGHT_VERSION = "phase2b-v1";
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.55;

    private final AiTaskRepository taskRepository;
    private final MatchingRepository matchingRepository;
    private final CampusMembershipAuthorization authorization;
    private final AnimalCandidateGateway animalGateway;
    private final MediaObjectGateway observationMediaGateway;
    private final AnimalMediaObjectGateway animalMediaGateway;
    private final ImageEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final MatchingWeightProperties weightProperties;
    private final int recallLimit;

    public CandidateMatchingService(AiTaskRepository taskRepository,
            MatchingRepository matchingRepository,
            CampusMembershipAuthorization authorization,
            AnimalCandidateGateway animalGateway,
            MediaObjectGateway observationMediaGateway,
            AnimalMediaObjectGateway animalMediaGateway,
            ImageEmbeddingClient embeddingClient,
            ObjectMapper objectMapper,
            MatchingWeightProperties weightProperties,
            @Value("${animallink.matching.recall-limit:50}") int recallLimit) {
        if (recallLimit < 20 || recallLimit > 100) {
            throw new IllegalArgumentException("animallink.matching.recall-limit 必须在 20 到 100 之间");
        }
        this.taskRepository = taskRepository;
        this.matchingRepository = matchingRepository;
        this.authorization = authorization;
        this.animalGateway = animalGateway;
        this.observationMediaGateway = observationMediaGateway;
        this.animalMediaGateway = animalMediaGateway;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
        this.weightProperties = weightProperties;
        this.recallLimit = recallLimit;
    }

    public MatchingRecord match(String taskId, Integer requestedTopK, MatchingExperiment experiment) {
        IdentityGateway.CurrentUser user = authorization.requireActiveUser();
        int topK = requestedTopK == null ? 3 : requestedTopK;
        if (topK < 1 || topK > 10) throw new IllegalArgumentException("topK 必须在 1 到 10 之间");
        MatchingExperiment selected = experiment == null ? MatchingExperiment.D : experiment;
        Map<String, Double> weights = weightProperties.weightsFor(selected);
        AiTaskBundle bundle = taskRepository.findBundle(taskId)
                .orElseThrow(() -> new NotFound("AI 任务不存在"));
        if (!bundle.task().userId().equals(user.id())) throw new NotFound("AI 任务不存在");
        if (bundle.task().status() != AiTaskStatus.SUCCEEDED || bundle.result() == null) {
            throw new TaskNotSucceeded("只有 SUCCEEDED 的 AI 任务可以进行候选匹配");
        }
        if (bundle.confirmation() == null || bundle.task().confirmedAt() == null) {
            throw new TaskNotConfirmed("AI 草稿须经用户确认后才能进行候选匹配");
        }
        AnimalObservationDraft draft = readDraft(bundle.confirmation().confirmedStructuredJson());
        List<AnimalCandidateGateway.CandidateSnapshot> recalled = animalGateway.recall(
                bundle.task().campusId(), draft.species().name(), recallLimit);
        List<List<Double>> observationVectors = recalled.isEmpty() ? List.of() : observationVectors(taskId);
        List<Scored> scored = recalled.stream()
                .map(candidate -> score(candidate, draft, observationVectors, weights))
                .sorted(Comparator.comparingDouble(Scored::finalScore).reversed()
                        .thenComparing(value -> value.snapshot().id()))
                .toList();
        boolean lowConfidence = scored.isEmpty() || scored.getFirst().finalScore() < LOW_CONFIDENCE_THRESHOLD;
        List<MatchingCandidate> topCandidates = new ArrayList<>();
        for (int index = 0; index < Math.min(topK, scored.size()); index++) {
            Scored value = scored.get(index);
            List<String> reasons = new ArrayList<>(value.reasons());
            if (lowConfidence) reasons.add("NO_STRONG_MATCH");
            String cover = value.snapshot().media().isEmpty() ? null : value.snapshot().media().getFirst().objectKey();
            topCandidates.add(new MatchingCandidate(index + 1, value.snapshot().id(),
                    value.snapshot().displayName(), value.snapshot().species(), cover,
                    value.image(), value.trait(), value.geo(), value.history(), value.finalScore(),
                    List.copyOf(reasons), value.missing()));
        }
        MatchingRecord record = new MatchingRecord(UUID.randomUUID().toString(), user.id(), taskId,
                bundle.task().campusId(), ALGORITHM_VERSION, WEIGHT_VERSION, selected,
                weights, topK, recalled.size(), lowConfidence, Instant.now(),
                List.copyOf(topCandidates));
        matchingRepository.save(record);
        return record;
    }

    public MatchingRecord getOwned(String recordId) {
        IdentityGateway.CurrentUser user = authorization.requireActiveUser();
        MatchingRecord record = matchingRepository.findById(recordId)
                .orElseThrow(() -> new NotFound("匹配记录不存在"));
        if (!record.userId().equals(user.id())) throw new NotFound("匹配记录不存在");
        return record;
    }

    private List<List<Double>> observationVectors(String taskId) {
        List<String> objectKeys = taskRepository.findMediaObjectKeys(taskId);
        if (objectKeys.isEmpty()) throw new MediaNotFound("AI 任务缺少原始观察媒体");
        return observationMediaGateway.loadAll(objectKeys).stream()
                .map(embeddingClient::embed).map(this::validateVector).toList();
    }

    private Scored score(AnimalCandidateGateway.CandidateSnapshot candidate,
                         AnimalObservationDraft draft, List<List<Double>> observationVectors,
                         Map<String, Double> weights) {
        Double image = imageScore(candidate, observationVectors);
        Double trait = traitScore(candidate, draft);
        Double geo = textSimilarity(draft.locationDescription(), candidate.typicalArea());
        Double history = historyScore(draft.occurredAt(), candidate.lastSeenAt());
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("image", image);
        scores.put("trait", trait);
        scores.put("geo", geo);
        scores.put("history", history);
        double weighted = 0;
        double availableWeight = 0;
        List<String> reasons = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Double> weight : weights.entrySet()) {
            if (weight.getValue() <= 0) continue;
            Double score = scores.get(weight.getKey());
            if (score == null) {
                missing.add(weight.getKey());
            } else {
                weighted += weight.getValue() * score;
                availableWeight += weight.getValue();
                reasons.add(reason(weight.getKey(), score));
            }
        }
        double result = availableWeight == 0 ? 0 : weighted / availableWeight;
        return new Scored(candidate, image, trait, geo, history, clamp(result),
                List.copyOf(reasons), List.copyOf(missing));
    }

    private Double imageScore(AnimalCandidateGateway.CandidateSnapshot candidate,
                              List<List<Double>> observationVectors) {
        if (candidate.media() == null || candidate.media().isEmpty()) return null;
        double best = -1;
        boolean found = false;
        for (AnimalCandidateGateway.PublicMedia media : candidate.media()) {
            AnimalEmbedding cached = matchingRepository.findEmbedding(candidate.id(), media.id(),
                    embeddingClient.modelName(), embeddingClient.modelVersion()).orElse(null);
            List<Double> candidateVector;
            if (cached == null) {
                try {
                    candidateVector = validateVector(embeddingClient.embed(animalMediaGateway.load(media.objectKey())));
                } catch (MediaNotFound | UnsupportedMedia exception) {
                    continue;
                }
                Instant now = Instant.now();
                cached = matchingRepository.saveEmbeddingOrLoadExisting(new AnimalEmbedding(
                        UUID.randomUUID().toString(), candidate.id(), media.id(), media.objectKey(),
                        embeddingClient.providerName(), embeddingClient.modelName(),
                        embeddingClient.modelVersion(), candidateVector, now, now));
            }
            candidateVector = validateVector(cached.vector());
            for (List<Double> observation : observationVectors) {
                best = Math.max(best, (cosine(observation, candidateVector) + 1.0) / 2.0);
                found = true;
            }
        }
        return found ? clamp(best) : null;
    }

    private Double traitScore(AnimalCandidateGateway.CandidateSnapshot candidate,
                              AnimalObservationDraft draft) {
        List<Double> values = new ArrayList<>();
        if (draft.species() != ObservationSpecies.UNKNOWN) {
            values.add(draft.species().name().equals(candidate.species()) ? 1.0 : 0.0);
        }
        if (draft.sex() != null && draft.sex() != ObservationSex.UNKNOWN
                && candidate.sex() != null && !"UNKNOWN".equals(candidate.sex())) {
            values.add(draft.sex().name().equals(candidate.sex()) ? 1.0 : 0.0);
        }
        Double coat = textSimilarity(draft.coatColor(), candidate.coatColor());
        if (coat != null) values.add(coat);
        String draftFeatures = draft.distinctiveFeatures() == null ? null
                : String.join(" ", draft.distinctiveFeatures());
        Double features = textSimilarity(draftFeatures, candidate.distinctiveFeatures());
        if (features != null) values.add(features);
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private Double historyScore(Instant occurredAt, Instant lastSeenAt) {
        if (occurredAt == null || lastSeenAt == null) return null;
        double days = Math.abs(Duration.between(lastSeenAt, occurredAt).toSeconds()) / 86400.0;
        return 1.0 / (1.0 + days / 30.0);
    }

    private Double textSimilarity(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) return null;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return intersection.size() / (double) union.size();
    }

    private Set<String> tokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> tokens = new LinkedHashSet<>();
        normalized.codePoints().forEach(codePoint -> tokens.add(new String(Character.toChars(codePoint))));
        return tokens;
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size()) throw new InvalidEmbeddingVector("Embedding 向量维度不一致");
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            dot += left.get(index) * right.get(index);
            leftNorm += left.get(index) * left.get(index);
            rightNorm += right.get(index) * right.get(index);
        }
        if (leftNorm == 0 || rightNorm == 0) throw new InvalidEmbeddingVector("Embedding 向量范数不能为零");
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private List<Double> validateVector(List<Double> vector) {
        if (vector == null || vector.isEmpty() || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new InvalidEmbeddingVector("Embedding Provider 返回非法向量");
        }
        double norm = vector.stream().mapToDouble(value -> value * value).sum();
        if (norm == 0) throw new InvalidEmbeddingVector("Embedding Provider 返回零向量");
        return List.copyOf(vector);
    }

    private String reason(String dimension, double score) {
        String level = score >= 0.8 ? "HIGH" : score >= 0.5 ? "MEDIUM" : "LOW";
        return dimension.toUpperCase(Locale.ROOT) + "_SIMILARITY_" + level;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private AnimalObservationDraft readDraft(String json) {
        try { return objectMapper.readValue(json, AnimalObservationDraft.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("已确认草稿无法读取", exception); }
    }

    private record Scored(AnimalCandidateGateway.CandidateSnapshot snapshot,
                          Double image, Double trait, Double geo, Double history,
                          double finalScore, List<String> reasons, List<String> missing) {
    }
}
