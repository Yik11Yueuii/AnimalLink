package org.animallink.intelligence.domain;

/** Identifies whether candidates were produced by the matching algorithm or deliberately skipped. */
public enum MatchingMode {
    AUTOMATED,
    MANUAL_FALLBACK
}
