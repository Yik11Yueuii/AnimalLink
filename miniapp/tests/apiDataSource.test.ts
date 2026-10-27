import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiPublicAnimalDataSource } from '../services/dataSource'
import { OfflinePublicAnimalDataSource } from '../services/offlineDataSource'
import type { JsonRequester, QueryValue } from '../services/apiClient'

test('API data source uses the documented public paths and zero-based pagination', async () => {
  const calls: Array<{ path: string; query?: Record<string, QueryValue> }> = []
  const requester: JsonRequester = async <T>(path: string, query?: Record<string, QueryValue>) => {
    calls.push({ path, query })
    if (path.endsWith('/universities')) return { items: [], page: 0, size: 20, total: 0 } as T
    if (path.includes('/timeline')) return { items: [], page: 0, size: 20, total: 0 } as T
    if (path.endsWith('/animals')) return { items: [], page: 0, size: 20, total: 0 } as T
    return [] as T
  }
  const source = new ApiPublicAnimalDataSource(requester)

  await source.listCampuses('杭州', 0, 50)
  await source.searchUniversities({ province: '广东省', q: '韩山', page: 0, size: 20 })
  await source.getUniversityCommunity('university/a')
  await source.searchAnimals({ campusId: 'campus-1', q: '橘子', species: 'CAT', page: 0, size: 20 })
  await source.getAnimal('animal/a')
  await source.getTimeline('animal/a', 0, 20)

  assert.deepEqual(calls, [
    { path: '/identity/api/v1/campuses', query: { q: '杭州', page: 0, size: 50 } },
    { path: '/identity/api/v1/universities', query: { province: '广东省', q: '韩山', page: 0, size: 20 } },
    { path: '/identity/api/v1/universities/university%2Fa/community', query: undefined },
    { path: '/animal/api/v1/public/animals', query: { campusId: 'campus-1', q: '橘子', species: 'CAT', page: 0, size: 20 } },
    { path: '/animal/api/v1/public/animals/animal%2Fa', query: undefined },
    { path: '/animal/api/v1/animals/animal%2Fa/timeline', query: { page: 0, size: 20 } },
  ])
})

test('offline university selection is paged and distinguishes unopened communities', async () => {
  const source = new OfflinePublicAnimalDataSource()
  const guangdong = await source.searchUniversities({ province: '广东省', q: '', page: 0, size: 10 })
  const hanshan = guangdong.items.find((item) => item.moeInstitutionCode === '4144010578')
  const unopened = guangdong.items.find((item) => !item.communityAvailable)

  assert.equal(guangdong.total, 2)
  assert.equal(hanshan?.officialName, '韩山师范学院')
  assert.equal(unopened?.officialName, '尚未开放高校（离线演示）')

  const community = await source.getUniversityCommunity(hanshan?.id ?? '')
  assert.equal(community.name, '韩山师范学院社区')
  assert.equal(community.campusType, 'UNIVERSITY_COMMUNITY')
  await assert.rejects(() => source.getUniversityCommunity(unopened?.id ?? ''), /社区尚未开放/)
})

test('offline demo search honors campus, species, keyword and zero-based pages', async () => {
  const source = new OfflinePublicAnimalDataSource()
  const first = await source.searchAnimals({ campusId: 'campus-mingde', q: '', species: '', page: 0, size: 3 })
  const second = await source.searchAnimals({ campusId: 'campus-mingde', q: '', species: '', page: 1, size: 3 })
  const cats = await source.searchAnimals({ campusId: 'campus-mingde', q: '橘白', species: 'CAT', page: 0, size: 10 })
  assert.equal(first.items.length, 3)
  assert.equal(first.total, 4)
  assert.equal(second.items.length, 1)
  assert.ok([...first.items, ...second.items].every((item) => item.campusId === 'campus-mingde'))
  assert.deepEqual(cats.items.map((item) => item.displayName), ['橘子'])
})
