package org.animallink.intelligence.domain;

import java.util.Optional;

public interface AiTaskRepository {
    void create(AiTask task);
    boolean markRunning(String taskId, long version);
    void complete(AiTask task, AiResult result);
    void fail(AiTask task);
    Optional<AiTaskBundle> findBundle(String taskId);
    void confirm(AiTask task, AiConfirmation confirmation);
}
