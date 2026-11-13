import { gatewayClient } from '@/api/client'
import { authenticatedAdminMutation } from '@/api/authentication'
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
  async review(id, decision, comment) {
    await authenticatedAdminMutation(`/adoption/api/v1/admin/applications/${encodeURIComponent(id)}/${decision}`,
      decision === 'approve' ? { comment } : { reason: comment })
  },
  async selection(id) {
    try { const { data } = await gatewayClient.get(`/adoption/api/v1/admin/listings/${encodeURIComponent(id)}/selection`); const x = record(data)
      return { selectionId: text(x.selectionId, 'selectionId'), applicationId: text(x.applicationId, 'applicationId'), status: text(x.status, 'status') }
    } catch (error) { if ((error as { response?: { status?: number } }).response?.status === 404) return null; throw error }
  },
  async select(id, applicationId) { await authenticatedAdminMutation(`/adoption/api/v1/admin/listings/${encodeURIComponent(id)}/selection`, { applicationId }) },
  async handover(id) {
    try { const { data } = await gatewayClient.get(`/adoption/api/v1/admin/selections/${encodeURIComponent(id)}/handover`); const x = record(data)
      return { handoverId: text(x.handoverId, 'handoverId'), status: text(x.status, 'status'), scheduledAt: text(x.scheduledAt, 'scheduledAt'), completedAt: nullableText(x.completedAt, 'completedAt') }
    } catch (error) { if ((error as { response?: { status?: number } }).response?.status === 404) return null; throw error }
  },
  async initiate(id, scheduledAt) { await authenticatedAdminMutation(`/adoption/api/v1/admin/selections/${encodeURIComponent(id)}/handover`, { scheduledAt }) },
  async complete(id) { await authenticatedAdminMutation(`/adoption/api/v1/admin/handovers/${encodeURIComponent(id)}/complete`, undefined) },
  async cancel(id, reason) { await authenticatedAdminMutation(`/adoption/api/v1/admin/handovers/${encodeURIComponent(id)}/cancel`, { reason }) },
  async listings(status, page, size) {
    const { data } = await gatewayClient.get('/adoption/api/v1/admin/listings', { params: { status: status || undefined, page, size } })
    const source = record(data); if (!Array.isArray(source.items)) throw new Error('领养信息列表响应无效。')
    return { items: source.items.map(listing), page: number(source.page, 'page'), size: number(source.size, 'size'), total: number(source.total, 'total') }
  },
  async listing(id) { const { data } = await gatewayClient.get(`/adoption/api/v1/admin/listings/${encodeURIComponent(id)}`); return listing(data) },
  async animals(campusId, keyword) {
    const { data } = await gatewayClient.get('/animal/api/v1/animals', { params: { campusId, q: keyword.trim(), page: 0, size: 20 } })
    const source = record(data); if (!Array.isArray(source.items)) throw new Error('Animal 查询响应无效。')
    return source.items.map(value => { const x = record(value); return { id: text(x.id, 'animal.id'), campusId: text(x.campusId, 'animal.campusId'), displayName: text(x.displayName, 'animal.displayName'), species: text(x.species, 'animal.species') } })
  },
  async createListing(animalId, title, description) {
    const data = await authenticatedAdminMutation('/adoption/api/v1/admin/listings', { animalId, title, description }); return listing(data)
  },
  async publishListing(id) {
    const data = await authenticatedAdminMutation(`/adoption/api/v1/admin/listings/${encodeURIComponent(id)}/publish`, undefined); return listing(data)
  },
}
