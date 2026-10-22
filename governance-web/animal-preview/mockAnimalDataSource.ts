import type {
  AnimalGovernanceDataSource,
} from '@/features/animals/dataSource'
import type {
  AnimalSearchCriteria,
  AnimalSearchResult,
  CampusOption,
  GovernanceAnimal,
} from '@/features/animals/types'

export const previewCampusOptions: CampusOption[] = [
  { id: '10000000-0000-0000-0000-000000000001', label: '明德校区' },
  { id: '10000000-0000-0000-0000-000000000002', label: '博雅校区' },
  { id: '10000000-0000-0000-0000-000000000003', label: '湖畔校区' },
]

const record = (
  index: number,
  overrides: Partial<GovernanceAnimal> & Pick<GovernanceAnimal, 'displayName' | 'species'>,
): GovernanceAnimal => {
  const campusId = overrides.campusId ?? previewCampusOptions[index % previewCampusOptions.length].id
  return {
    id: `20000000-0000-0000-0000-${String(index).padStart(12, '0')}`,
    requestedAnimalId: `20000000-0000-0000-0000-${String(index).padStart(12, '0')}`,
    canonicalAnimalId: `20000000-0000-0000-0000-${String(index).padStart(12, '0')}`,
    displayName: overrides.displayName,
    species: overrides.species,
    sex: 'UNKNOWN',
    coatColor: '待补充',
    distinctiveFeatures: '暂无显著特征记录',
    description: '由校园志愿者持续维护的动物档案。',
    sterilizationStatus: 'UNKNOWN',
    typicalArea: '校园公共区域',
    identityStatus: 'ACTIVE',
    adoptionStatus: 'NOT_OPEN',
    currentContext: 'CAMPUS',
    version: 1,

    mergeId: null,
    mergedAt: null,
    createdAt: `2026-09-${String((index % 20) + 1).padStart(2, '0')}T08:30:00+08:00`,
    updatedAt: `2026-10-${String((index % 20) + 1).padStart(2, '0')}T16:20:00+08:00`,
    ...overrides,
    campusId,
  }
}

export const previewAnimals: GovernanceAnimal[] = [
  record(1, { displayName: '橘子', species: 'CAT', sex: 'MALE', coatColor: '橘白', distinctiveFeatures: '右耳有小缺口', sterilizationStatus: 'STERILIZED', typicalArea: '图书馆东侧', adoptionStatus: 'OPEN', version: 4 }),
  record(2, { displayName: '雪团', species: 'CAT', sex: 'FEMALE', coatColor: '纯白', distinctiveFeatures: '蓝黄异瞳', typicalArea: '明德食堂后门', version: 2 }),
  record(3, { displayName: '阿麦', species: 'DOG', sex: 'MALE', coatColor: '浅黄', distinctiveFeatures: '佩戴绿色项圈', sterilizationStatus: 'NOT_STERILIZED', typicalArea: '体育场北门', adoptionStatus: 'HANDOVER_PENDING', version: 5 }),
  record(4, { displayName: '墨墨', species: 'CAT', sex: 'FEMALE', coatColor: '黑色', distinctiveFeatures: '胸口有白色月牙毛', sterilizationStatus: 'STERILIZED', typicalArea: '博雅楼下', adoptionStatus: 'OPEN', version: 3 }),
  record(5, { displayName: '豆包', species: 'DOG', sex: 'FEMALE', coatColor: '棕白', distinctiveFeatures: '左前脚白袜', sterilizationStatus: 'STERILIZED', typicalArea: '校友之家', adoptionStatus: 'ADOPTED', currentContext: 'ADOPTED_HOME', version: 8 }),
  record(6, { displayName: '小灰', species: 'CAT', coatColor: '灰狸花', distinctiveFeatures: '尾尖弯曲', typicalArea: '湖畔宿舍区', version: 2 }),
  record(7, { displayName: '栗子', species: 'CAT', sex: 'MALE', coatColor: '棕色狸花', sterilizationStatus: 'STERILIZED', typicalArea: '湖畔超市', adoptionStatus: 'OPEN', version: 6 }),
  record(8, { displayName: '点点', species: 'DOG', sex: 'FEMALE', coatColor: '白底黑斑', distinctiveFeatures: '鼻梁中央黑色圆斑', typicalArea: '博雅操场', identityStatus: 'ARCHIVED', version: 7 }),
  record(9, { displayName: '旧档案 A09', species: 'CAT', coatColor: '橘色', distinctiveFeatures: '与橘子重复建档', typicalArea: '图书馆东侧', identityStatus: 'MERGED', canonicalAnimalId: '20000000-0000-0000-0000-000000000001', mergeId: '30000000-0000-0000-0000-000000000009', mergedAt: '2026-10-09T14:25:00+08:00', version: 3 }),
  record(10, { displayName: '花卷', species: 'CAT', sex: 'FEMALE', coatColor: '三花', distinctiveFeatures: '鼻头左侧橘色', sterilizationStatus: 'STERILIZED', typicalArea: '艺术中心', adoptionStatus: 'ADOPTED', currentContext: 'ADOPTED_HOME', version: 9 }),
  record(11, { displayName: '来福', species: 'DOG', sex: 'MALE', coatColor: '黑棕', distinctiveFeatures: '垂耳、体型偏大', typicalArea: '明德西门', adoptionStatus: 'OPEN', version: 3 }),
  record(12, { displayName: '斑斑', species: 'CAT', sex: 'MALE', coatColor: '奶牛色', distinctiveFeatures: '背部两块黑斑', sterilizationStatus: 'STERILIZED', typicalArea: '实验楼连廊', version: 4 }),
  record(13, { displayName: '小满', species: 'OTHER', sex: 'UNKNOWN', coatColor: '灰白', distinctiveFeatures: '右耳略短', typicalArea: '湖畔花园', identityStatus: 'ARCHIVED', version: 2 }),
  record(14, { displayName: '团团', species: 'CAT', sex: 'FEMALE', coatColor: '玳瑁', distinctiveFeatures: '左眼上方浅色斑', sterilizationStatus: 'STERILIZED', typicalArea: '研究生公寓', adoptionStatus: 'OPEN', version: 5 }),
  record(15, { displayName: '奶盖', species: 'DOG', sex: 'FEMALE', coatColor: '米白', distinctiveFeatures: '耳尖浅棕', typicalArea: '湖畔南门', adoptionStatus: 'HANDOVER_PENDING', version: 3 }),
  record(16, { displayName: '松果', species: 'CAT', sex: 'MALE', coatColor: '长毛灰白', distinctiveFeatures: '绿色眼睛', sterilizationStatus: 'STERILIZED', typicalArea: '博雅图书角', version: 6 }),
]

const delay = (ms = 120) => new Promise((resolve) => window.setTimeout(resolve, ms))

export class MockAnimalGovernanceDataSource implements AnimalGovernanceDataSource {
  async search(criteria: AnimalSearchCriteria): Promise<AnimalSearchResult> {
    await delay()
    const keyword = criteria.keyword.trim().toLocaleLowerCase()
    let items = previewAnimals.filter((animal) => {
      const text = [animal.id, animal.displayName, animal.species, animal.coatColor, animal.distinctiveFeatures, animal.typicalArea]
        .join(' ')
        .toLocaleLowerCase()
      return (!keyword || text.includes(keyword))
        && (!criteria.campusId || animal.campusId === criteria.campusId)
        && (!criteria.species || animal.species === criteria.species)
        && (!criteria.identityStatus || animal.identityStatus === criteria.identityStatus)
        && (!criteria.adoptionStatus || animal.adoptionStatus === criteria.adoptionStatus)
        && (!criteria.currentContext || animal.currentContext === criteria.currentContext)
    })

    items = [...items].sort((left, right) => {
      if (criteria.sort === 'NAME_ASC') return left.displayName.localeCompare(right.displayName, 'zh-CN')
      if (criteria.sort === 'NAME_DESC') return right.displayName.localeCompare(left.displayName, 'zh-CN')
      const compared = Date.parse(left.updatedAt) - Date.parse(right.updatedAt)
      return criteria.sort === 'UPDATED_ASC' ? compared : -compared
    })

    const total = items.length
    const start = (criteria.page - 1) * criteria.size
    return { items: items.slice(start, start + criteria.size), page: criteria.page, size: criteria.size, total }
  }

  async getDetail(animalId: string): Promise<GovernanceAnimal> {
    await delay(80)
    const animal = previewAnimals.find((item) => item.id === animalId)
    if (!animal) throw new Error('未找到该 Animal 档案')
    return { ...animal }
  }
}
