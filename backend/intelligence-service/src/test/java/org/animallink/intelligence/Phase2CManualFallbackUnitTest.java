package org.animallink.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.animallink.intelligence.application.*;
import org.animallink.intelligence.domain.*;
import org.animallink.intelligence.domain.ApiExceptions.CandidateServiceUnavailable;
import org.animallink.intelligence.domain.ApiExceptions.TaskNotConfirmed;
import org.animallink.intelligence.infrastructure.MatchingWeightProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Phase2CManualFallbackUnitTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000501";
    private static final String CAMPUS_ID = "10000000-0000-0000-0000-000000000501";

    @Test
    void manualDraftDoesNotInvokeAiAndStillRequiresNormalConfirmation() {
        AiTaskRepository tasks = mock(AiTaskRepository.class);
        MediaObjectGateway media = mock(MediaObjectGateway.class);
        MultimodalModelClient model = mock(MultimodalModelClient.class);
        IdentityGateway identity = activeIdentity();
        when(tasks.markRunning(anyString(), eq(0L))).thenReturn(true);
        when(media.loadAll(anyList())).thenReturn(List.of());
        AnimalObservationDraft manualDraft = draft();
        ObservationParsingService service = new ObservationParsingService(tasks,
                new CampusMembershipAuthorization(identity), media, model,
                new AnimalObservationDraftValidator(), mock(VersionedObservationPrompt.class),
                objectMapper());

        AiTaskView view = service.createManualDraft(new ManualObservationCommand(CAMPUS_ID,
                List.of("uploads/observation.jpg"), "手工填写", "东门", Instant.now(), manualDraft));

        assertThat(view.task().status()).isEqualTo(AiTaskStatus.SUCCEEDED);
        assertThat(view.task().modelProvider()).isEqualTo("MANUAL");
        assertThat(view.originalDraft()).isEqualTo(manualDraft);
        assertThat(view.confirmedDraft()).isNull();
        ArgumentCaptor<AiResult> result = ArgumentCaptor.forClass(AiResult.class);
        verify(tasks).complete(any(AiTask.class), result.capture());
        assertThat(result.getValue().rawResponse()).isEqualTo("MANUAL_ENTRY");
        verifyNoInteractions(model);
    }

    @Test
    void matchingInfrastructureFailureCreatesAnAuditableFallbackRecord() throws Exception {
        MatchingRepository matching = mock(MatchingRepository.class);
        AiTaskRepository tasks = mock(AiTaskRepository.class);
        IdentityGateway identity = mock(IdentityGateway.class);
        when(identity.requireCurrentUser()).thenReturn(
                new IdentityGateway.CurrentUser(USER_ID, "测试用户", "ACTIVE", "USER"));
        AnimalCandidateGateway candidates = mock(AnimalCandidateGateway.class);
        CandidateMatchingService service = new CandidateMatchingService(tasks, matching,
                new CampusMembershipAuthorization(identity), candidates,
                mock(MediaObjectGateway.class), mock(AnimalMediaObjectGateway.class),
                mock(ImageEmbeddingClient.class), objectMapper(), weights(), 50);
        AiTask task = succeededTask();
        AnimalObservationDraft confirmedDraft = draft();
        String confirmedJson = objectMapper().writeValueAsString(confirmedDraft);
        AiResult result = new AiResult(UUID.randomUUID().toString(), task.id(),
                "ANIMAL_OBSERVATION_DRAFT", "MANUAL_ENTRY", confirmedJson, "1.0", Instant.now());
        AiConfirmation confirmation = new AiConfirmation(UUID.randomUUID().toString(), task.id(),
                UUID.randomUUID().toString(), confirmedJson, true, USER_ID, Instant.now());
        when(tasks.findBundle(task.id())).thenReturn(Optional.of(new AiTaskBundle(task, result, confirmation)));

        when(candidates.recall(anyString(), anyString(), anyInt()))
                .thenThrow(new CandidateServiceUnavailable("Animal 候选服务暂不可用", null));
        MatchingRecord record = service.match(task.id(), 3, MatchingExperiment.D);

        assertThat(record.matchingMode()).isEqualTo(MatchingMode.MANUAL_FALLBACK);
        assertThat(record.fallbackReason()).isEqualTo("CANDIDATE_SERVICE_UNAVAILABLE");
        assertThat(record.candidates()).isEmpty();
        assertThat(record.lowConfidence()).isTrue();
        verify(matching).save(record);

        when(tasks.findBundle(task.id())).thenReturn(Optional.of(new AiTaskBundle(task, result, null)));
        assertThatThrownBy(() -> service.match(task.id(), 3, MatchingExperiment.D))
                .isInstanceOf(TaskNotConfirmed.class);
    }

    private IdentityGateway activeIdentity() {
        IdentityGateway identity = mock(IdentityGateway.class);
        IdentityGateway.CurrentUser user = new IdentityGateway.CurrentUser(USER_ID, "测试用户", "ACTIVE", "USER");
        when(identity.requireCurrentUser()).thenReturn(user);
        when(identity.campusMembership(USER_ID, CAMPUS_ID))
                .thenReturn(new IdentityGateway.MembershipFact(true, "STUDENT", "ACTIVE"));
        return identity;
    }

    private AiTask succeededTask() {
        Instant now = Instant.now();
        return new AiTask(UUID.randomUUID().toString(), USER_ID, CAMPUS_ID,
                AiTaskType.ANIMAL_OBSERVATION_PARSE, AiTaskStatus.SUCCEEDED,
                "MANUAL", "MANUAL", "manual-v1", "{}", now, now, now,
                null, null, null, now, USER_ID, 2);
    }

    private MatchingWeightProperties weights() {
        MatchingWeightProperties.DimensionWeights dimension = new MatchingWeightProperties.DimensionWeights();
        dimension.setImage(1);
        MatchingWeightProperties properties = new MatchingWeightProperties();
        properties.setExperiments(Map.of("D", dimension));
        return properties;
    }

    private AnimalObservationDraft draft() {
        return new AnimalObservationDraft(ObservationSpecies.CAT, ObservationSex.UNKNOWN,
                "橘白", List.of("尾部环纹"), null, null, 1, false, List.of(),
                "东门", Instant.now(), 0.8, Map.of(), List.of(), List.of());
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
