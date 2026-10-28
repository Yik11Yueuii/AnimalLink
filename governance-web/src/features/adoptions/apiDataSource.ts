import { gatewayClient } from '@/api/client'
import type { AdoptionGovernanceDataSource } from './dataSource'
import type { AdoptionListingSummary, AdoptionSearchResult, AdoptionStatusCounts, GovernanceAdoptionApplication } from './types'

type UnknownRecord = Record<string, unknown>
const record = (value: unknown): UnknownRecord => { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('领养服务返回了无法验证的响应。'); return value as UnknownRecord }
const text = (value: unknown, field: string): string => { if (typeof value !== 'string') throw new Error(`领养响应缺少 ${field}。`); return value }
const nullableText = (value: unknown, field: string): string | null => value === null || value === undefined ? null : text(value, field)
const number = (value: unknown, field: string): number => { if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`领养响应中的 ${field} 无效。`); return value }
const listing = (value: unknown): AdoptionListingSummary => { const source = record(value); return { listingId: text(source.listingId, 'listingId'),
  animalId: text(source.animalId, 'animalId'), status: text(source.status, 'listing.status') as AdoptionListingSummary['status'], title: text(source.title, 'listing.title'),
  description: text(source.description, 'listing.description'), createdAt: text(source.createdAt, 'listing.createdAt'), updatedAt: text(source.updatedAt, 'listing.updatedAt'),
  publishedAt: nullableText(source.publishedAt, 'listing.publishedAt'), closedAt: nullableText(source.closedAt, 'listing.closedAt') } }
const application = (value: unknown): GovernanceAdoptionApplication => { const source = record(value); return {
  applicationId: text(source.applicationId, 'applicationId'), listingId: text(source.listingId, 'listingId'), applicantReference: text(source.applicantReference, 'applicantReference'),
  status: text(source.status, 'status') as GovernanceAdoptionApplication['status'], message: text(source.message, 'message'), createdAt: text(source.createdAt, 'createdAt'),
  updatedAt: text(source.updatedAt, 'updatedAt'), reviewerReference: nullableText(source.reviewerReference, 'reviewerReference'), reviewedAt: nullableText(source.reviewedAt, 'reviewedAt'),
  reviewComment: nullableText(source.reviewComment, 'reviewComment'), listing: listing(source.listing) } }
const counts = (value: unknown): AdoptionStatusCounts => { const source = record(value); return { SUBMITTED: number(source.SUBMITTED, 'statusCounts.SUBMITTED'),
  APPROVED: number(source.APPROVED, 'statusCounts.APPROVED'), REJECTED: number(source.REJECTED, 'statusCounts.REJECTED'), WITHDRAWN: number(source.WITHDRAWN, 'statusCounts.WITHDRAWN') } }

export const adoptionApiDataSource: AdoptionGovernanceDataSource = {
  async search(criteria): Promise<AdoptionSearchResult> {
    const { data } = await gatewayClient.get('/adoption/api/v1/admin/applications', { params: { status: criteria.status || undefined,
      keyword: criteria.keyword.trim() || undefined, sort: criteria.sort, page: criteria.page - 1, size: criteria.size } })
    const source = record(data); if (!Array.isArray(source.items)) throw new Error('领养申请列表响应无效。')
    return { items: source.items.map(application), page: number(source.page, 'page') + 1, size: number(source.size, 'size'),
      total: number(source.total, 'total'), statusCounts: counts(source.statusCounts) }
  },
  async getDetail(applicationId): Promise<GovernanceAdoptionApplication> {
    const { data } = await gatewayClient.get(`/adoption/api/v1/admin/applications/${encodeURIComponent(applicationId)}`)
    return application(data)
  },
}
