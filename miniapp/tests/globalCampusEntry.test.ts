import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test, { after, before, beforeEach } from 'node:test'
import { getPublicAnimalDataSource, getRescueDataSource } from '../services/sourceFactory'
import type { AnimalSearchCriteria, CampusResponse, PageResponse, AnimalSummaryResponse } from '../types/api'
import type { PublicRescuePage, PublicRescueStatus } from '../types/rescue'

interface CampusAwarePage {
  data: { campus: CampusResponse | null }
  setData(update: Record<string, unknown>): void
  onShow(): void
}

const animalSource = getPublicAnimalDataSource()
const rescueSource = getRescueDataSource()
const originalAnimalSearch = animalSource.searchAnimals
const originalRescueSearch = rescueSource.search
let animalDefinition: CampusAwarePage
let rescueDefinition: CampusAwarePage
let storage: Map<string, unknown>
let routes: string[]
let animalCalls: AnimalSearchCriteria[]
let rescueCalls: Array<{ campusId: string; status: PublicRescueStatus | ''; page: number; size: number }>

const campus = (id: string, name: string): CampusResponse => ({
  id,
  universityId: `university-${id}`,
  name,
  shortName: name,
  city: '潮州市',
  region: null,
  campusType: 'UNIVERSITY_COMMUNITY',
  status: 'ACTIVE',
})

const createPage = (definition: CampusAwarePage): CampusAwarePage => Object.assign({}, definition, {
  data: JSON.parse(JSON.stringify(definition.data)) as CampusAwarePage['data'],
  setData(update: Record<string, unknown>) { Object.assign(this.data, update) },
})

const flushPageRefresh = async () => {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

before(async () => {
  ;(globalThis as unknown as { Page: (value: CampusAwarePage) => void }).Page = value => { animalDefinition = value }
  await import('../pages/animals/index')
  ;(globalThis as unknown as { Page: (value: CampusAwarePage) => void }).Page = value => { rescueDefinition = value }
  await import('../pages/rescue/index')
})

beforeEach(() => {
  storage = new Map()
  routes = []
  animalCalls = []
  rescueCalls = []
  ;(globalThis as unknown as { wx: unknown }).wx = {
    getStorageSync: (key: string) => storage.get(key),
    navigateTo: ({ url }: { url: string }) => routes.push(url),
  }
  animalSource.searchAnimals = async criteria => {
    animalCalls.push(criteria)
    return { items: [], page: criteria.page, size: criteria.size, total: 0 } as PageResponse<AnimalSummaryResponse>
  }
  rescueSource.search = async (campusId, status, page, size) => {
    rescueCalls.push({ campusId, status, page, size })
    return { items: [], page, size, total: 0 } as PublicRescuePage
  }
})

after(() => {
  animalSource.searchAnimals = originalAnimalSearch
  rescueSource.search = originalRescueSearch
})

test('Circle keeps the only daily campus switch while Animals and Rescue render passive campus labels', async () => {
  const [circleMarkup, circleScript, animalMarkup, animalScript, animalStyles, rescueMarkup, rescueScript, rescueStyles] = await Promise.all([
    readFile('pages/circle/index.wxml', 'utf8'),
    readFile('pages/circle/index.ts', 'utf8'),
    readFile('pages/animals/index.wxml', 'utf8'),
    readFile('pages/animals/index.ts', 'utf8'),
    readFile('pages/animals/index.wxss', 'utf8'),
    readFile('pages/rescue/index.wxml', 'utf8'),
    readFile('pages/rescue/index.ts', 'utf8'),
    readFile('pages/rescue/index.wxss', 'utf8'),
  ])

  assert.match(circleMarkup, /<button class="campus-button" bindtap="changeCampus">/)
  assert.match(circleScript, /changeCampus\(\)/)
  for (const markup of [animalMarkup, rescueMarkup]) {
    assert.match(markup, /<view class="campus-label"/)
    assert.match(markup, /class="campus-label-prefix">当前高校/)
    assert.doesNotMatch(markup, /bindtap="changeCampus"|class="campus-button"/)
  }
  for (const script of [animalScript, rescueScript]) assert.doesNotMatch(script, /changeCampus\(\)/)
  for (const styles of [animalStyles, rescueStyles]) {
    assert.match(styles, /\.campus-label \{/)
    assert.doesNotMatch(styles, /\.campus-button|\.campus-label:active/)
  }
})

test('Animals search button reserves a no-wrap content box without squeezing the search input', async () => {
  const [markup, styles] = await Promise.all([
    readFile('pages/animals/index.wxml', 'utf8'),
    readFile('pages/animals/index.wxss', 'utf8'),
  ])

  assert.match(markup, /<input value="\{\{q\}\}"[^>]*bindconfirm="onSearch"[^>]*\/>\s*<button class="search-button" bindtap="onSearch">搜索<\/button>/)
  assert.match(styles, /\.search-row input \{[^}]*box-sizing: border-box;[^}]*flex: 1;[^}]*min-width: 0;/)
  assert.match(styles, /\.search-row \.search-button \{[^}]*flex: 0 0 144rpx;[^}]*padding: 0;[^}]*white-space: nowrap;/)
  assert.match(styles, /\.search-row \.search-button \{[^}]*align-items: center;[^}]*justify-content: center;/)
})

test('Animals and Rescue retain the first-use campus selection guide', () => {
  const animals = createPage(animalDefinition)
  const rescue = createPage(rescueDefinition)

  animals.onShow()
  rescue.onShow()

  assert.deepEqual(routes, [
    '/pages/campus/index?returnTo=animals',
    '/pages/campus/index?returnTo=rescue',
  ])
  assert.deepEqual(animalCalls, [])
  assert.deepEqual(rescueCalls, [])
})

test('Animals and Rescue reread the shared Campus Context and refresh after a Circle campus switch', async () => {
  const animals = createPage(animalDefinition)
  const rescue = createPage(rescueDefinition)
  const first = campus('campus-a', '高校 A')
  const second = campus('campus-b', '高校 B')

  storage.set('animallink.selectedCampus.v1', first)
  animals.onShow()
  rescue.onShow()
  await flushPageRefresh()
  assert.equal(animals.data.campus?.name, '高校 A')
  assert.equal(rescue.data.campus?.name, '高校 A')

  storage.set('animallink.selectedCampus.v1', second)
  animals.onShow()
  rescue.onShow()
  await flushPageRefresh()

  assert.equal(animals.data.campus?.name, '高校 B')
  assert.equal(rescue.data.campus?.name, '高校 B')
  assert.deepEqual(animalCalls.map(call => call.campusId), ['campus-a', 'campus-b'])
  assert.deepEqual(rescueCalls.map(call => call.campusId), ['campus-a', 'campus-b'])
  assert.deepEqual(routes, [])
})
