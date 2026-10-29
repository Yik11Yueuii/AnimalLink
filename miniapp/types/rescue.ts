import type { AnimalSpecies, PageResponse } from './api'

export type PublicRescueStatus = 'WAITING_CLAIM' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED' | 'CLOSED_UNRESOLVED' | 'CANCELLED'

export interface PublicRescueAnimal {
  id: string
  displayName: string
  species: AnimalSpecies
}

export interface PublicRescueItem {
  eventId: string
  campusId: string
  rescueStatus: PublicRescueStatus
  occurredAt: string
  reportedAt: string
  updatedAt: string
  publicSummary: string
  animal: PublicRescueAnimal | null
}

export interface PublicRescueProgress {
  stage: string
  label: string
  occurredAt: string
}

export interface PublicRescueDetail {
  rescue: PublicRescueItem
  progress: PublicRescueProgress[]
}

export type PublicRescuePage = PageResponse<PublicRescueItem>
