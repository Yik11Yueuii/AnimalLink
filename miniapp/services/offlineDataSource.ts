import type { PublicAnimalDataSource } from './dataSource'
import type {
  AnimalSearchCriteria,
  AnimalSummaryResponse,
  CampusResponse,
  PageResponse,
  PublicAnimalResponse,
  TimelineEntryResponse,
} from '../types/api'

export const offlineCampuses: CampusResponse[] = [
  { id: 'campus-mingde', name: '明德大学', shortName: '明德', city: '杭州', region: '浙江', status: 'ACTIVE' },
  { id: 'campus-boyan', name: '博雅学院', shortName: '博雅', city: '南京', region: '江苏', status: 'ACTIVE' },
  { id: 'campus-hupan', name: '湖畔大学城', shortName: '湖畔', city: '苏州', region: '江苏', status: 'ACTIVE' },
]

type AnimalSeed = PublicAnimalResponse

const seeds: AnimalSeed[] = [
  { id: 'animal-orange', campusId: 'campus-mingde', displayName: '橘子', species: 'CAT', sex: 'MALE', coatColor: '橘白', adoptionStatus: 'NOT_OPEN', currentContext: 'CAMPUS', createdAt: '2026-03-10T09:00:00+08:00', updatedAt: '2026-10-18T15:20:00+08:00' },
  { id: 'animal-snow', campusId: 'campus-mingde', displayName: '雪团', species: 'CAT', sex: 'FEMALE', coatColor: '白色', adoptionStatus: 'OPEN', currentContext: 'CAMPUS', createdAt: '2026-04-02T10:30:00+08:00', updatedAt: '2026-10-17T11:00:00+08:00' },
  { id: 'animal-wheat', campusId: 'campus-mingde', displayName: '阿麦', species: 'DOG', sex: 'MALE', coatColor: '浅棕白', adoptionStatus: 'HANDOVER_PENDING', currentContext: 'CAMPUS', createdAt: '2026-05-14T08:00:00+08:00', updatedAt: '2026-10-16T18:10:00+08:00' },
  { id: 'animal-ink', campusId: 'campus-mingde', displayName: '墨墨', species: 'CAT', sex: 'UNKNOWN', coatColor: '黑色', adoptionStatus: 'NOT_OPEN', currentContext: 'CAMPUS', createdAt: '2026-06-01T12:00:00+08:00', updatedAt: '2026-10-15T09:40:00+08:00' },
  { id: 'animal-lucky', campusId: 'campus-boyan', displayName: '来福', species: 'DOG', sex: 'MALE', coatColor: '黄白', adoptionStatus: 'ADOPTED', currentContext: 'ADOPTED_HOME', createdAt: '2026-02-18T08:00:00+08:00', updatedAt: '2026-10-20T20:00:00+08:00' },
  { id: 'animal-grey', campusId: 'campus-boyan', displayName: '小灰', species: 'CAT', sex: 'FEMALE', coatColor: '狸花灰', adoptionStatus: 'OPEN', currentContext: 'CAMPUS', createdAt: '2026-03-22T09:00:00+08:00', updatedAt: '2026-10-12T14:00:00+08:00' },
  { id: 'animal-chestnut', campusId: 'campus-boyan', displayName: '栗子', species: 'CAT', sex: 'FEMALE', coatColor: '棕黑', adoptionStatus: 'NOT_OPEN', currentContext: 'CAMPUS', createdAt: '2026-07-11T09:00:00+08:00', updatedAt: '2026-10-11T10:00:00+08:00' },
  { id: 'animal-milk', campusId: 'campus-boyan', displayName: '奶盖', species: 'DOG', sex: 'FEMALE', coatColor: '奶油色', adoptionStatus: 'OPEN', currentContext: 'CAMPUS', createdAt: '2026-08-01T08:00:00+08:00', updatedAt: '2026-10-10T16:00:00+08:00' },
  { id: 'animal-pine', campusId: 'campus-hupan', displayName: '松果', species: 'CAT', sex: 'MALE', coatColor: '长毛棕色', adoptionStatus: 'NOT_OPEN', currentContext: 'CAMPUS', createdAt: '2026-01-15T08:00:00+08:00', updatedAt: '2026-10-19T09:00:00+08:00' },
  { id: 'animal-spots', campusId: 'campus-hupan', displayName: '斑斑', species: 'CAT', sex: 'FEMALE', coatColor: '三花', adoptionStatus: 'OPEN', currentContext: 'CAMPUS', createdAt: '2026-05-20T08:00:00+08:00', updatedAt: '2026-10-18T12:00:00+08:00' },
  { id: 'animal-dot', campusId: 'campus-hupan', displayName: '点点', species: 'DOG', sex: 'MALE', coatColor: '白色带黑斑', adoptionStatus: 'NOT_OPEN', currentContext: 'CAMPUS', createdAt: '2026-06-12T08:00:00+08:00', updatedAt: '2026-10-14T13:00:00+08:00' },
  { id: 'animal-bird', campusId: 'campus-hupan', displayName: '小羽', species: 'OTHER', sex: 'UNKNOWN', coatColor: '灰褐色', adoptionStatus: 'NOT_OPEN', currentContext: 'CAMPUS', createdAt: '2026-09-03T08:00:00+08:00', updatedAt: '2026-10-09T11:00:00+08:00' },
]

const details: PublicAnimalResponse[] = seeds.map((animal) => ({ ...animal }))

const timelineByAnimal: Record<string, TimelineEntryResponse[]> = {
  'animal-orange': [
    { id: 'timeline-orange-2', animalId: 'animal-orange', sourceType: 'CASE', sourceId: 'case-orange', entryType: 'CASE_RESOLVED', title: '救助观察完成', summary: '轻微擦伤经观察后状态稳定。', occurredAt: '2026-10-18T14:30:00+08:00', createdAt: '2026-10-18T15:00:00+08:00' },
    { id: 'timeline-orange-1', animalId: 'animal-orange', sourceType: 'ANIMAL', sourceId: 'animal-orange', entryType: 'PROFILE_CREATED', title: '建立长期档案', summary: '完成首次公开档案记录。', occurredAt: '2026-03-10T09:00:00+08:00', createdAt: '2026-03-10T09:00:00+08:00' },
  ],
  'animal-lucky': [
    { id: 'timeline-lucky-2', animalId: 'animal-lucky', sourceType: 'ADOPTION_RELATION', sourceId: 'relation-lucky', entryType: 'ADOPTION_COMPLETED', title: '完成领养交接', summary: '已进入领养家庭，长期档案继续保留。', occurredAt: '2026-10-12T10:00:00+08:00', createdAt: '2026-10-12T10:05:00+08:00' },
    { id: 'timeline-lucky-1', animalId: 'animal-lucky', sourceType: 'ANIMAL', sourceId: 'animal-lucky', entryType: 'PROFILE_CREATED', title: '建立长期档案', summary: '完成首次公开档案记录。', occurredAt: '2026-02-18T08:00:00+08:00', createdAt: '2026-02-18T08:00:00+08:00' },
  ],
}

const wait = () => new Promise<void>((resolve) => setTimeout(resolve, 120))

export class OfflinePublicAnimalDataSource implements PublicAnimalDataSource {
  async listCampuses(q = '', page = 0, size = 50): Promise<CampusResponse[]> {
    await wait()
    const keyword = q.trim().toLocaleLowerCase()
    return offlineCampuses
      .filter((campus) => !keyword || [campus.name, campus.shortName, campus.city, campus.region].join(' ').toLocaleLowerCase().includes(keyword))
      .slice(page * size, page * size + size)
  }

  async searchAnimals(criteria: AnimalSearchCriteria): Promise<PageResponse<AnimalSummaryResponse>> {
    await wait()
    const keyword = criteria.q.trim().toLocaleLowerCase()
    const filtered = details.filter((animal) => animal.campusId === criteria.campusId
      && (!criteria.species || animal.species === criteria.species)
      && (!keyword || [animal.displayName, animal.coatColor].join(' ').toLocaleLowerCase().includes(keyword)))
    const start = criteria.page * criteria.size
    const items = filtered.slice(start, start + criteria.size).map(({ createdAt: _createdAt, ...summary }) => summary)
    return { items, page: criteria.page, size: criteria.size, total: filtered.length }
  }

  async getAnimal(animalId: string): Promise<PublicAnimalResponse> {
    await wait()
    const animal = details.find((item) => item.id === animalId)
    if (!animal) throw new Error('未找到该 Animal 档案。')
    return { ...animal }
  }

  async getTimeline(animalId: string, page: number, size: number): Promise<PageResponse<TimelineEntryResponse>> {
    await wait()
    const entries = timelineByAnimal[animalId] ?? []
    const start = page * size
    return { items: entries.slice(start, start + size), page, size, total: entries.length }
  }
}
