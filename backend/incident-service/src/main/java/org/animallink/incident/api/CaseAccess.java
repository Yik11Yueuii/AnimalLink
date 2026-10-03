package org.animallink.incident.api;

record CaseAccess(boolean owner, boolean activeParticipant) { boolean related(){return owner||activeParticipant;} }
