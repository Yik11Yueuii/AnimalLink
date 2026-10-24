import { ApiRequestError } from './apiClient'
import type { PublicAnimalDataSource } from './dataSource'
import type { PublicAnimalResponse, TimelineEntryResponse } from '../types/api'

export type TimelineLoadStatus = 'loaded' | 'protected' | 'failed'

export interface AnimalDetailLoadResult {
  animal: PublicAnimalResponse
  timeline: TimelineEntryResponse[]
  timelineStatus: TimelineLoadStatus
  timelineError: unknown | null
}

export const loadAnimalDetail = async (
  source: PublicAnimalDataSource,
  animalId: string,
): Promise<AnimalDetailLoadResult> => {
  const animal = await source.getAnimal(animalId)
  try {
    const timeline = await source.getTimeline(animalId, 0, 20)
    return { animal, timeline: timeline.items, timelineStatus: 'loaded', timelineError: null }
  } catch (error) {
    const protectedTimeline = error instanceof ApiRequestError
      && (error.statusCode === 401 || error.statusCode === 403)
    return {
      animal,
      timeline: [],
      timelineStatus: protectedTimeline ? 'protected' : 'failed',
      timelineError: protectedTimeline ? null : error,
    }
  }
}
