import type { PublicRescueDetail, PublicRescuePage, PublicRescueStatus } from '../types/rescue'
import type { JsonRequester } from './apiClient'

export interface RescueDataSource {
  search(campusId: string, status: PublicRescueStatus | '', page: number, size: number): Promise<PublicRescuePage>
  getDetail(campusId: string, eventId: string): Promise<PublicRescueDetail>
}

export class ApiRescueDataSource implements RescueDataSource {
  constructor(private readonly request: JsonRequester) {}

  search(campusId: string, status: PublicRescueStatus | '', page: number, size: number) {
    return this.request<PublicRescuePage>('/incident/api/v1/public/rescues', { campusId, status, page, size })
  }

  getDetail(campusId: string, eventId: string) {
    return this.request<PublicRescueDetail>(`/incident/api/v1/public/rescues/${encodeURIComponent(eventId)}`, { campusId })
  }
}
