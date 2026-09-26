package org.animallink.intelligence.infrastructure;

import org.animallink.intelligence.domain.MatchingExperiment;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "animallink.matching.weights")
public class MatchingWeightProperties {
    private Map<String, DimensionWeights> experiments = new LinkedHashMap<>();

    public Map<String, DimensionWeights> getExperiments() {
        return experiments;
    }

    public void setExperiments(Map<String, DimensionWeights> experiments) {
        this.experiments = experiments;
    }

    public Map<String, Double> weightsFor(MatchingExperiment experiment) {
        DimensionWeights value = experiments.get(experiment.name());
        if (value == null) throw new IllegalStateException("缺少实验组 " + experiment + " 的匹配权重");
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("image", valid(value.image, "image"));
        weights.put("trait", valid(value.trait, "trait"));
        weights.put("geo", valid(value.geo, "geo"));
        weights.put("history", valid(value.history, "history"));
        if (weights.values().stream().mapToDouble(Double::doubleValue).sum() <= 0) {
            throw new IllegalStateException("实验组 " + experiment + " 至少需要一个正权重");
        }
        return Map.copyOf(weights);
    }

    private double valid(double value, String dimension) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalStateException("匹配权重 " + dimension + " 必须是非负有限数");
        }
        return value;
    }

    public static class DimensionWeights {
        private double image;
        private double trait;
        private double geo;
        private double history;

        public double getImage() { return image; }
        public void setImage(double image) { this.image = image; }
        public double getTrait() { return trait; }
        public void setTrait(double trait) { this.trait = trait; }
        public double getGeo() { return geo; }
        public void setGeo(double geo) { this.geo = geo; }
        public double getHistory() { return history; }
        public void setHistory(double history) { this.history = history; }
    }
}
