package org.animallink.intelligence.application;

import java.util.List;

public interface ImageEmbeddingClient {
    String providerName();
    String modelName();
    String modelVersion();
    List<Double> embed(MediaObjectGateway.MediaInput media);
}
