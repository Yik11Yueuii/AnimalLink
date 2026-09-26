package org.animallink.intelligence.application;

import java.util.List;

public interface MediaObjectGateway {
    List<MediaInput> loadAll(List<String> objectKeys);

    record MediaInput(String objectKey, String contentType, byte[] content) {}
}
