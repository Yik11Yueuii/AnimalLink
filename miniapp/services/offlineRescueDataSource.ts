import type { PublicRescueDetail, PublicRescueItem, PublicRescuePage, PublicRescueStatus } from '../types/rescue'
import type { RescueDataSource } from './rescueDataSource'

const HANSHAN_CAMPUS_ID = '22000000-0000-4000-8000-000000000001'
const wait = () => new Promise<void>((resolve) => setTimeout(resolve, 120))

const rescues: PublicRescueItem[] = [
  { eventId: '34000000-0000-4000-8000-000000000001', campusId: HANSHAN_CAMPUS_ID, rescueStatus: 'IN_PROGRESS', occurredAt: '2026-10-20T07:40:00+08:00', reportedAt: '2026-10-20T08:10:00+08:00', updatedAt: '2026-10-20T11:00:00+08:00', publicSummary: '【虚构演示】事件已核查，志愿者正在进行基础观察。', animal: { id: '23000000-0000-4000-8000-000000000001', displayName: '小橘', species: 'CAT' } },
  { eventId: '34000000-0000-4000-8000-000000000002', campusId: HANSHAN_CAMPUS_ID, rescueStatus: 'WAITING_CLAIM', occurredAt: '2026-10-19T16:20:00+08:00', reportedAt: '2026-10-19T16:40:00+08:00', updatedAt: '2026-10-19T18:00:00+08:00', publicSummary: '【虚构演示】事件已核查，正在等待志愿者处理。', animal: { id: '23000000-0000-4000-8000-000000000003', displayName: '阿黄', species: 'DOG' } },
  { eventId: '34000000-0000-4000-8000-000000000003', campusId: HANSHAN_CAMPUS_ID, rescueStatus: 'CLOSED_UNRESOLVED', occurredAt: '2026-10-15T09:00:00+08:00', reportedAt: '2026-10-15T09:20:00+08:00', updatedAt: '2026-10-16T15:00:00+08:00', publicSummary: '【虚构演示】该救助已结束，未形成已解决结果。', animal: null },
]

const progress: Record<string, PublicRescueDetail['progress']> = {
  '34000000-0000-4000-8000-000000000001': [
    { stage: 'VERIFIED', label: '事件已完成核查', occurredAt: '2026-10-20T09:00:00+08:00' },
    { stage: 'CLAIMED', label: '已由志愿者接手处理', occurredAt: '2026-10-20T10:10:00+08:00' },
  ],
  '34000000-0000-4000-8000-000000000002': [
    { stage: 'VERIFIED', label: '事件已完成核查', occurredAt: '2026-10-19T18:00:00+08:00' },
  ],
  '34000000-0000-4000-8000-000000000003': [
    { stage: 'VERIFIED', label: '事件已完成核查', occurredAt: '2026-10-15T12:00:00+08:00' },
    { stage: 'UNRESOLVED', label: '处理结果：未解决', occurredAt: '2026-10-16T14:30:00+08:00' },
    { stage: 'CLOSED_UNRESOLVED', label: '救助已结束，未解决', occurredAt: '2026-10-16T15:00:00+08:00' },
  ],
}

export class OfflineRescueDataSource implements RescueDataSource {
  async search(campusId: string, status: PublicRescueStatus | '', page: number, size: number): Promise<PublicRescuePage> {
    await wait()
    const filtered = rescues.filter((item) => item.campusId === campusId && (!status || item.rescueStatus === status))
    const start = page * size
    return { items: filtered.slice(start, start + size).map((item) => ({ ...item, animal: item.animal ? { ...item.animal } : null })), page, size, total: filtered.length }
  }

  async getDetail(campusId: string, eventId: string): Promise<PublicRescueDetail> {
    await wait()
    const rescue = rescues.find((item) => item.campusId === campusId && item.eventId === eventId)
    if (!rescue) throw new Error('未找到公开救助事件。')
    return { rescue: { ...rescue, animal: rescue.animal ? { ...rescue.animal } : null }, progress: [...(progress[eventId] ?? [])] }
  }
}
