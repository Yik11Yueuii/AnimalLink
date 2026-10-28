import { gatewayClient } from '@/api/client'
import type { CampusOption } from '@/features/animals/types'
import type { RescueGovernanceDataSource } from './dataSource'
import type { RescueDetail, RescueListItem, RescueProgress, RescueSearchResult } from './types'

type UnknownRecord = Record<string, unknown>
const record = (value: unknown): UnknownRecord => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('救助服务返回了无法验证的响应。')
  return value as UnknownRecord
}
const text = (value: unknown, field: string): string => { if (typeof value !== 'string') throw new Error(`救助响应缺少 ${field}。`); return value }
const nullableText = (value: unknown, field: string): string | null => value === null || value === undefined ? null : text(value, field)
const number = (value: unknown, field: string): number => { if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`救助响应中的 ${field} 无效。`); return value }
const boolean = (value: unknown, field: string): boolean => { if (typeof value !== 'boolean') throw new Error(`救助响应中的 ${field} 无效。`); return value }
const item = (value: unknown): RescueListItem => {
  const source = record(value)
  return { eventId: text(source.eventId, 'eventId'), caseId: nullableText(source.caseId, 'caseId'), campusId: text(source.campusId, 'campusId'),
    animalId: nullableText(source.animalId, 'animalId'), rescueStatus: text(source.rescueStatus, 'rescueStatus') as RescueListItem['rescueStatus'],
    eventStatus: text(source.eventStatus, 'eventStatus') as RescueListItem['eventStatus'], caseStatus: nullableText(source.caseStatus, 'caseStatus') as RescueListItem['caseStatus'],
    claimStatus: nullableText(source.claimStatus, 'claimStatus') as RescueListItem['claimStatus'], hasOwner: boolean(source.hasOwner, 'hasOwner'),
    abnormalitySummary: nullableText(source.abnormalitySummary, 'abnormalitySummary'), description: text(source.description, 'description'),
    publicLocationDescription: nullableText(source.publicLocationDescription, 'publicLocationDescription'), occurredAt: text(source.occurredAt, 'occurredAt'),
    reportedAt: text(source.reportedAt, 'reportedAt'), evidenceCount: number(source.evidenceCount, 'evidenceCount'), actionCount: number(source.actionCount, 'actionCount'),
    resultCode: nullableText(source.resultCode, 'resultCode') as RescueListItem['resultCode'], resultSummary: nullableText(source.resultSummary, 'resultSummary'),
    updatedAt: text(source.updatedAt, 'updatedAt'), animal: null }
}
const progress = (value: unknown): RescueProgress => { const source = record(value); return { id: text(source.id, 'progress.id'),
  description: text(source.description, 'progress.description'), occurredAt: text(source.occurredAt, 'progress.occurredAt'),
  createdAt: text(source.createdAt, 'progress.createdAt'), resultCode: nullableText(source.resultCode, 'progress.resultCode') as RescueProgress['resultCode'] } }

export const rescueApiDataSource: RescueGovernanceDataSource = {
  async search(criteria): Promise<RescueSearchResult> {
    if (!criteria.campusId) throw new Error('请选择 Campus 后查询救助事件。')
    const { data } = await gatewayClient.get('/incident/api/v1/admin/rescues', { params: { campusId: criteria.campusId,
      status: criteria.rescueStatus || undefined, keyword: criteria.keyword.trim() || undefined, sort: criteria.sort,
      page: criteria.page - 1, size: criteria.size } })
    const source = record(data); if (!Array.isArray(source.items)) throw new Error('救助列表响应无效。')
    return { items: source.items.map(item), page: number(source.page, 'page') + 1, size: number(source.size, 'size'), total: number(source.total, 'total') }
  },
  async getDetail(eventId): Promise<RescueDetail> {
    const { data } = await gatewayClient.get(`/incident/api/v1/admin/rescues/${encodeURIComponent(eventId)}`)
    const source = record(data); const base = item(source.rescue); if (!Array.isArray(source.progress)) throw new Error('救助详情进度响应无效。')
    return { ...base, claimedAt: nullableText(source.claimedAt, 'claimedAt'), resultSubmittedAt: nullableText(source.resultSubmittedAt, 'resultSubmittedAt'),
      closedAt: nullableText(source.closedAt, 'closedAt'), progress: source.progress.map(progress) }
  },
}

export const loadCampusOptions = async (): Promise<CampusOption[]> => {
  const { data } = await gatewayClient.get('/identity/api/v1/campuses', { params: { page: 0, size: 100 } })
  if (!Array.isArray(data)) throw new Error('Campus 服务返回了无法验证的响应。')
  return data.map((value) => { const source = record(value); return { id: text(source.id, 'campus.id'), label: text(source.name, 'campus.name') } })
}
