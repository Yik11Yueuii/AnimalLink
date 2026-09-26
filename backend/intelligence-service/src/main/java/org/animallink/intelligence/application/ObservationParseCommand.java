package org.animallink.intelligence.application;

import java.time.Instant;
import java.util.List;

public record ObservationParseCommand(String campusId, List<String> mediaObjectKeys, String text,
                                      String locationDescription, Instant occurredAt) {}
