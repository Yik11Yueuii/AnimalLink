import type { AdoptionGovernanceDataSource } from '@/features/adoptions/dataSource'
import type {
  AdoptionApplicationStatus,
  AdoptionListingStatus,
  AdoptionSearchCriteria,
  AdoptionSearchResult,
  AdoptionStatusCounts,
  GovernanceAdoptionApplication,
} from '@/features/adoptions/types'

type AdoptionSeed = {
  title: string
  message: string
  status: AdoptionApplicationStatus
  listingStatus: AdoptionListingStatus
  reviewComment?: string
}

const seed = (index: number, value: AdoptionSeed): GovernanceAdoptionApplication => {
  const suffix = String(index).padStart(12, '0')
  const day = String((index % 18) + 1).padStart(2, '0')
  const reviewed = value.status === 'APPROVED' || value.status === 'REJECTED'
  const publishedAt = value.listingStatus === 'DRAFT' ? null : `2026-09-${String((index % 20) + 1).padStart(2, '0')}T10:00:00+08:00`
  return {
    applicationId: `71000000-0000-0000-0000-${suffix}`,
    listingId: `72000000-0000-0000-0000-${suffix}`,
    applicantUserId: `73000000-0000-0000-0000-${suffix}`,
    status: value.status,
    message: value.message,
    createdAt: `2026-10-${day}T09:20:00+08:00`,
    updatedAt: `2026-10-${String((index % 18) + 2).padStart(2, '0')}T15:30:00+08:00`,
    reviewerUserId: reviewed ? `74000000-0000-0000-0000-${String((index % 3) + 1).padStart(12, '0')}` : null,
    reviewedAt: reviewed ? `2026-10-${String((index % 18) + 2).padStart(2, '0')}T14:00:00+08:00` : null,
    reviewComment: value.reviewComment ?? null,
    listing: {
      listingId: `72000000-0000-0000-0000-${suffix}`,
      animalId: `20000000-0000-0000-0000-${suffix}`,
      status: value.listingStatus,
      title: value.title,
      description: `${value.title}的领养信息，当前仅用于治理界面的本地交互预览。`,
      publisherUserId: `75000000-0000-0000-0000-${String((index % 4) + 1).padStart(12, '0')}`,
      createdAt: `2026-09-${String((index % 20) + 1).padStart(2, '0')}T08:30:00+08:00`,
      updatedAt: `2026-10-${day}T08:50:00+08:00`,
      publishedAt,
      closedAt: value.listingStatus === 'CLOSED' ? `2026-10-${day}T18:00:00+08:00` : null,
    },
  }
}

export const previewAdoptionApplications: GovernanceAdoptionApplication[] = [
  seed(1, { title: '橘子 · 校园猫领养计划', message: '希望提供稳定的室内环境，并按要求完成后续回访。', status: 'SUBMITTED', listingStatus: 'PUBLISHED' }),
  seed(2, { title: '雪团 · 温柔白猫寻找新家', message: '已有养猫经验，家庭成员已就领养达成一致。', status: 'APPROVED', listingStatus: 'PUBLISHED', reviewComment: '居住条件和照护计划符合当前审核要求。' }),
  seed(3, { title: '阿麦 · 亲人犬只领养信息', message: '工作日白天照护安排仍在协调。', status: 'REJECTED', listingStatus: 'PUBLISHED', reviewComment: '当前照护时间安排不足，建议完善后重新申请。' }),
  seed(4, { title: '墨墨 · 黑猫领养档案', message: '因近期居住计划变化，主动撤回本次申请。', status: 'WITHDRAWN', listingStatus: 'CLOSED' }),
  seed(5, { title: '来福 · 活泼犬只家庭招募', message: '可提供每日户外活动，并接受定期回访。', status: 'SUBMITTED', listingStatus: 'PUBLISHED' }),
  seed(6, { title: '小灰 · 狸花猫领养计划', message: '已准备封窗和基础猫咪用品。', status: 'APPROVED', listingStatus: 'CLOSED', reviewComment: '审核通过；Listing 已关闭，后续状态需由交接模块确认。' }),
  seed(7, { title: '栗子 · 安静猫咪领养信息', message: '目前租住环境无法提供长期稳定住所。', status: 'REJECTED', listingStatus: 'CLOSED', reviewComment: '居住稳定性暂不符合要求。' }),
  seed(8, { title: '团团 · 长毛猫领养计划', message: '申请人已自行撤回，未进入审核。', status: 'WITHDRAWN', listingStatus: 'PUBLISHED' }),
  seed(9, { title: '奶盖 · 校园犬领养计划', message: '希望进一步了解日常活动和健康观察要求。', status: 'SUBMITTED', listingStatus: 'PUBLISHED' }),
  seed(10, { title: '松果 · 长毛猫家庭招募', message: '家庭环境安静，可按约定提交领养后更新。', status: 'APPROVED', listingStatus: 'PUBLISHED', reviewComment: '基础条件符合，批准仅代表申请审核通过。' }),
  seed(11, { title: '斑斑 · 成年猫领养信息', message: '现有宠物隔离方案不够完整。', status: 'REJECTED', listingStatus: 'PUBLISHED', reviewComment: '建议补充新旧动物隔离计划。' }),
  seed(12, { title: '点点 · 小型犬领养计划', message: '个人安排变化，申请人选择撤回。', status: 'WITHDRAWN', listingStatus: 'CLOSED' }),
  seed(13, { title: '豆包 · 青年猫领养信息', message: '可提供长期居所，希望参与正式审核。', status: 'SUBMITTED', listingStatus: 'PUBLISHED' }),
  seed(14, { title: '花卷 · 三花猫领养计划', message: '具备稳定照护条件并接受领养回访。', status: 'APPROVED', listingStatus: 'CLOSED', reviewComment: '申请资料完整，后续是否选定由独立流程决定。' }),
  seed(15, { title: '可可 · 胆小猫咪领养信息', message: '家庭活动空间较为开放，缺少安全隔离区。', status: 'REJECTED', listingStatus: 'PUBLISHED', reviewComment: '当前环境不适合该动物的适应需求。' }),
  seed(16, { title: '星星 · 历史草稿 Listing', message: '申请已撤回；此条仅覆盖治理界面对 DRAFT 枚举的显示。', status: 'WITHDRAWN', listingStatus: 'DRAFT' }),
]

const delay = (ms = 100) => new Promise((resolve) => window.setTimeout(resolve, ms))
const countsFor = (items: GovernanceAdoptionApplication[]): AdoptionStatusCounts => ({
  SUBMITTED: items.filter((item) => item.status === 'SUBMITTED').length,
  APPROVED: items.filter((item) => item.status === 'APPROVED').length,
  REJECTED: items.filter((item) => item.status === 'REJECTED').length,
  WITHDRAWN: items.filter((item) => item.status === 'WITHDRAWN').length,
})

export class MockAdoptionGovernanceDataSource implements AdoptionGovernanceDataSource {
  async search(criteria: AdoptionSearchCriteria): Promise<AdoptionSearchResult> {
    await delay()
    const keyword = criteria.keyword.trim().toLocaleLowerCase()
    const keywordItems = previewAdoptionApplications.filter((item) => {
      const text = [item.applicationId, item.listingId, item.listing.animalId, item.listing.title, item.message].join(' ').toLocaleLowerCase()
      return !keyword || text.includes(keyword)
    })
    let items = keywordItems.filter((item) => !criteria.status || item.status === criteria.status)
    items = [...items].sort((left, right) => {
      const key = criteria.sort === 'UPDATED_DESC' ? 'updatedAt' : 'createdAt'
      const direction = criteria.sort === 'CREATED_ASC' ? 1 : -1
      return (Date.parse(left[key]) - Date.parse(right[key])) * direction
    })
    const total = items.length
    const start = (criteria.page - 1) * criteria.size
    return {
      items: items.slice(start, start + criteria.size),
      page: criteria.page,
      size: criteria.size,
      total,
      statusCounts: countsFor(keywordItems),
    }
  }

  async getDetail(applicationId: string): Promise<GovernanceAdoptionApplication> {
    await delay(70)
    const item = previewAdoptionApplications.find((candidate) => candidate.applicationId === applicationId)
    if (!item) throw new Error('未找到该领养申请')
    return { ...item, listing: { ...item.listing } }
  }
}
