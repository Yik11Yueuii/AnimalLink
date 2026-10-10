package org.animallink.intelligence.domain;

import java.util.List;
import java.util.Optional;

public interface AiTaskRepository {
    void create(AiTask task, List<String> mediaObjectKeys);
    boolean markRunning(String taskId, long version);
    void complete(AiTask task, AiResult result);
    void fail(AiTask task);
    Optional<AiTaskBundle> findBundle(String taskId);
    Optional<CredentialPrecheckTaskBundle> findCredentialPrecheckByIdempotencyKey(String idempotencyKey);
    void createCredentialPrecheck(AiTask task, String idempotencyKey, String externalReferenceId);
    void confirm(AiTask task, AiConfirmation confirmation);

    List<String> findMediaObjectKeys(String taskId);
}
