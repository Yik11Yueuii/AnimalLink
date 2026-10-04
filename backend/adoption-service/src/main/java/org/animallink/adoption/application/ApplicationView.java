package org.animallink.adoption.application;

import org.animallink.adoption.domain.AdoptionApplication;

public record ApplicationView(AdoptionApplication application, boolean selected) { }
