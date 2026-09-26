package org.animallink.animal.application;

import org.animallink.animal.domain.Animal;
import org.animallink.animal.domain.AnimalMedia;

import java.util.List;

public record AnimalDetail(Animal animal, List<AnimalMedia> media) {
}
