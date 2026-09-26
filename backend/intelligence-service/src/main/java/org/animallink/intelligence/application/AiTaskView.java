package org.animallink.intelligence.application;

import org.animallink.intelligence.domain.AiTask;
import org.animallink.intelligence.domain.AnimalObservationDraft;

public record AiTaskView(AiTask task, AnimalObservationDraft originalDraft,
                         AnimalObservationDraft confirmedDraft, Boolean wasModified) {}
