package org.animallink.adoption.application;

import org.animallink.adoption.domain.AdoptionSelection;

public record SelectionView(AdoptionSelection selection, String selectedApplicantUserId) { }
