import type { RescueGovernanceDataSource } from '@/features/rescues/dataSource'
import type {
  CaseStatus, ClaimStatus, RescueDetail, RescueResultCode, RescueSearchCriteria,
  RescueSearchResult, RescueStatus,
} from '@/features/rescues/types'
import { previewCampusOptions } from './mockAnimalDataSource'

type RescueSeed = {
  summary: string
  description: string
  location: string
  animalName?: string
  rescueStatus: RescueStatus
  caseStatus?: CaseStatus
  claimStatus?: ClaimStatus
  resultCode?: RescueResultCode
  resultSummary?: string
  evidenceCount?: number
  progress?: string[]
}

const seed = (index: number, value: RescueSeed): RescueDetail => {
  const suffix = String(index).padStart(12, '0')
  const eventId = `40000000-0000-0000-0000-${suffix}`
  const hasCase = value.caseStatus !== undefined
  const caseId = hasCase ? `50000000-0000-0000-0000-${suffix}` : null
  const hasOwner = value.claimStatus === 'CLAIMED'
  const progress = (value.progress ?? []).map((description, actionIndex) => ({
    id: `60000000-0000-0000-${String(index).padStart(4, '0')}-${String(actionIndex + 1).padStart(12, '0')}`,
    description,
    occurredAt: `2026-10-${String((index % 18) + 2).padStart(2, '0')}T${String(9 + actionIndex).padStart(2, '0')}:20:00+08:00`,
    createdAt: `2026-10-${String((index % 18) + 2).padStart(2, '0')}T${String(9 + actionIndex).padStart(2, '0')}:25:00+08:00`,
    resultCode: actionIndex === (value.progress?.length ?? 0) - 1 ? value.resultCode ?? null : null,
  }))
  const day = String((index % 18) + 1).padStart(2, '0')
  const animalId = value.animalName ? `20000000-0000-0000-0000-${suffix}` : null
  return {
    eventId,
    caseId,
    campusId: previewCampusOptions[index % previewCampusOptions.length].id,
    animalId,
    rescueStatus: value.rescueStatus,
    eventStatus: 'VERIFIED',
    caseStatus: value.caseStatus ?? null,
    claimStatus: value.claimStatus ?? null,
    hasOwner,
    abnormalitySummary: value.summary,
    description: value.description,
    publicLocationDescription: value.location,
    occurredAt: `2026-10-${day}T08:15:00+08:00`,
    reportedAt: `2026-10-${day}T08:40:00+08:00`,
    evidenceCount: value.evidenceCount ?? 0,
    actionCount: progress.length,
    resultCode: value.resultCode ?? null,
    resultSummary: value.resultSummary ?? null,
    updatedAt: `2026-10-${String((index % 18) + 2).padStart(2, '0')}T16:10:00+08:00`,
    animal: animalId ? { id: animalId, displayName: value.animalName ?? null, species: index % 4 === 0 ? 'DOG' : 'CAT', identityStatus: 'ACTIVE' } : null,
    claimedAt: hasOwner ? `2026-10-${day}T10:00:00+08:00` : null,
    resultSubmittedAt: value.resultCode ? `2026-10-${String((index % 18) + 2).padStart(2, '0')}T15:30:00+08:00` : null,
    closedAt: ['RESOLVED', 'CLOSED', 'CLOSED_UNRESOLVED', 'CANCELLED'].includes(value.caseStatus ?? '')
      ? `2026-10-${String((index % 18) + 2).padStart(2, '0')}T16:00:00+08:00`
      : null,
    progress,
  }
}

export const previewRescues: RescueDetail[] = [
  seed(1, { summary: '后腿疑似受伤', description: '学生发现橘猫步态异常，已放置饮水并保持距离观察。', location: '图书馆东侧花坛附近', animalName: '橘子', rescueStatus: 'WAITING_CLAIM', evidenceCount: 2 }),
  seed(2, { summary: '持续蜷缩且精神不佳', description: '白猫连续两小时停留在避雨棚内，呼唤反应较弱。', location: '明德食堂后门公共区域', animalName: '雪团', rescueStatus: 'WAITING_CLAIM', caseStatus: 'OPEN', claimStatus: 'WAITING_CLAIM', evidenceCount: 3 }),
  seed(3, { summary: '项圈卡住灌木', description: '犬只项圈被低矮灌木挂住，志愿者已到场处理。', location: '体育场北门绿化带', animalName: '阿麦', rescueStatus: 'IN_PROGRESS', caseStatus: 'ACTIVE', claimStatus: 'CLAIMED', evidenceCount: 1, progress: ['到场确认环境安全', '已解除项圈缠绕并观察活动状态'] }),
  seed(4, { summary: '眼部有分泌物', description: '黑猫左眼出现明显分泌物，已安排志愿者持续观察。', location: '博雅楼一层外廊', animalName: '墨墨', rescueStatus: 'IN_PROGRESS', caseStatus: 'ACTIVE', claimStatus: 'CLAIMED', evidenceCount: 4, progress: ['领取救助物资', '现场观察并记录状态变化'] }),
  seed(5, { summary: '轻微擦伤', description: '犬只前肢有轻微擦伤，清洁处理后活动恢复正常。', location: '校友之家西侧步道', animalName: '来福', rescueStatus: 'COMPLETED', caseStatus: 'RESOLVED', claimStatus: 'CLAIMED', resultCode: 'RESOLVED', resultSummary: '完成基础清洁和短时观察，确认可正常行走。', evidenceCount: 3, progress: ['现场检查伤口', '完成清洁并观察', '确认状态稳定'] }),
  seed(6, { summary: '多日食欲不佳', description: '狸花猫进食量持续下降，已完成现场排查。', location: '湖畔宿舍区公共投喂点', animalName: '小灰', rescueStatus: 'COMPLETED', caseStatus: 'CLOSED_UNRESOLVED', claimStatus: 'CLAIMED', resultCode: 'UNRESOLVED', resultSummary: '未能再次找到目标动物，案件关闭但问题未确认解决。', evidenceCount: 5, progress: ['核对近期观察记录', '两次巡查未发现目标动物'] }),
  seed(7, { summary: '误报被困', description: '复核后确认动物可自行进出，不构成被困。', location: '湖畔超市后侧连廊', animalName: '栗子', rescueStatus: 'COMPLETED', caseStatus: 'CANCELLED', claimStatus: 'CLAIMED', resultCode: 'CANCELLED', resultSummary: '现场核实为可自由通行区域，取消本次 Case。', evidenceCount: 1, progress: ['现场核实通道结构'] }),
  seed(8, { summary: '叫声异常', description: '夜间连续叫声异常，待志愿者认领核实。', location: '艺术中心北侧台阶', rescueStatus: 'WAITING_CLAIM', evidenceCount: 1 }),
  seed(9, { summary: '羽翼疑似受伤', description: '发现一只无法正常起飞的小型鸟类，事件已核实但尚未建案。', location: '湖畔花园主入口', rescueStatus: 'WAITING_CLAIM', evidenceCount: 2 }),
  seed(10, { summary: '皮肤局部脱毛', description: '猫咪背部出现局部脱毛，志愿者正在补充观察证据。', location: '研究生公寓公共庭院', animalName: '团团', rescueStatus: 'IN_PROGRESS', caseStatus: 'ACTIVE', claimStatus: 'CLAIMED', evidenceCount: 2, progress: [] }),
  seed(11, { summary: '受惊躲入设备间', description: '犬只进入开放设备间，工作人员协助引导离开。', location: '明德西门公共设备间外', animalName: '奶盖', rescueStatus: 'COMPLETED', caseStatus: 'CLOSED', claimStatus: 'CLAIMED', resultCode: 'RESOLVED', resultSummary: '已安全引导至室外并关闭入口。', evidenceCount: 2, progress: ['确认现场无运转设备', '完成安全引导'] }),
  seed(12, { summary: '疑似脱水', description: '长毛猫在高温时段长时间趴卧，已补水观察。', location: '博雅图书角外侧', animalName: '松果', rescueStatus: 'IN_PROGRESS', caseStatus: 'ACTIVE', claimStatus: 'CLAIMED', evidenceCount: 4, progress: ['放置清水', '精神状态有所恢复'] }),
  seed(13, { summary: '无法确认关联动物', description: '上报者仅听到灌木内异常叫声，尚未识别具体动物。', location: '实验楼南侧绿化带', rescueStatus: 'WAITING_CLAIM', caseStatus: 'OPEN', claimStatus: 'WAITING_CLAIM', evidenceCount: 0 }),
  seed(14, { summary: '短时跛行', description: '持续观察后确认行走恢复，Case 已解决。', location: '湖畔南门广场', animalName: '斑斑', rescueStatus: 'COMPLETED', caseStatus: 'RESOLVED', claimStatus: 'CLAIMED', resultCode: 'RESOLVED', resultSummary: '观察期间未再出现跛行，记录后结案。', evidenceCount: 3, progress: ['完成初次步态观察', '次日复查状态正常'] }),
  seed(15, { summary: '疑似吞食异物', description: '犬只在垃圾分类点附近徘徊，志愿者正在排查。', location: '博雅操场东侧分类点', animalName: '点点', rescueStatus: 'IN_PROGRESS', caseStatus: 'ACTIVE', claimStatus: 'CLAIMED', evidenceCount: 2, progress: ['清理现场可疑物品'] }),
]

const delay = (ms = 100) => new Promise((resolve) => window.setTimeout(resolve, ms))

export class MockRescueGovernanceDataSource implements RescueGovernanceDataSource {
  async search(criteria: RescueSearchCriteria): Promise<RescueSearchResult> {
    await delay()
    const keyword = criteria.keyword.trim().toLocaleLowerCase()
    let items = previewRescues.filter((item) => {
      const text = [item.eventId, item.caseId, item.abnormalitySummary, item.description, item.publicLocationDescription, item.animal?.displayName].join(' ').toLocaleLowerCase()
      return (!keyword || text.includes(keyword))
        && (!criteria.campusId || item.campusId === criteria.campusId)
        && (!criteria.rescueStatus || item.rescueStatus === criteria.rescueStatus)
    })
    const key = criteria.sort === 'REPORTED_DESC' ? 'reportedAt' : criteria.sort === 'OCCURRED_DESC' ? 'occurredAt' : 'updatedAt'
    items = [...items].sort((left, right) => Date.parse(right[key]) - Date.parse(left[key]))
    const total = items.length
    const start = (criteria.page - 1) * criteria.size
    return { items: items.slice(start, start + criteria.size), page: criteria.page, size: criteria.size, total }
  }

  async getDetail(eventId: string): Promise<RescueDetail> {
    await delay(70)
    const item = previewRescues.find((candidate) => candidate.eventId === eventId)
    if (!item) throw new Error('未找到该救助事件')
    return { ...item, progress: item.progress.map((entry) => ({ ...entry })) }
  }
}
