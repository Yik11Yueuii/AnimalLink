import assert from 'node:assert/strict'
import test, { before, beforeEach, afterEach } from 'node:test'
import { readFile } from 'node:fs/promises'
import { getPublicAnimalDataSource } from '../services/sourceFactory'
import { ApiPublicAnimalDataSource } from '../services/dataSource'
import { OfflinePublicAnimalDataSource } from '../services/offlineDataSource'
import type { UniversityResponse, CampusResponse, UniversitySearchCriteria, PageResponse } from '../types/api'

interface SelectorPage {
  data: {
    q: string; searchTerm: string; activeProvince: string; provincePickerOpen: boolean
    universities: UniversityResponse[]; provinceUniversities: UniversityResponse[]
    universityPage: number; provincePage: number; loading: boolean; loadingMore: boolean
    errorMessage: string; provinceError: string; selectionError: string; selectedUniversityId: string
    returnTo: string; openingUniversityId: string; hasMoreUniversities: boolean; provinceHasMore: boolean
  }
  setData(update: Partial<SelectorPage['data']>): void
  onLoad(options: Record<string, string>): void
  onHide(): void
  onUnload(): void
  onKeywordInput(event: unknown): void
  onSearch(): Promise<void>
  onRetry(): Promise<void>
  openProvincePicker(): Promise<void>
  closeProvincePicker(): void
  onProvinceTap(event: unknown): Promise<void> | undefined
  loadMoreUniversities(): Promise<void> | undefined
  loadMoreProvinceUniversities(): Promise<void> | undefined
  chooseUniversity(event: unknown): Promise<void>
}
let definition: SelectorPage
const source = getPublicAnimalDataSource()
const originalSearch = source.searchUniversities
const originalCommunity = source.getUniversityCommunity
let routes: string[]
let storage: Map<string, unknown>
interface NavigationOptions { url: string; success?: () => void; fail?: () => void }
const university = (id: string, communityAvailable = true): UniversityResponse => ({
  id, officialName: id, moeInstitutionCode: id, shortName: null,
  province: '广东省', city: '潮州市', status: 'ACTIVE', communityAvailable,
})
const community: CampusResponse = {
  id: 'stable-campus', universityId: 'hanshan', name: '韩山师范学院社区', shortName: '韩师社区',
  city: '潮州市', region: null, campusType: 'UNIVERSITY_COMMUNITY', status: 'ACTIVE',
}
const result = (items: UniversityResponse[], page = 0, total = items.length): PageResponse<UniversityResponse> => ({ items, page, size: 8, total })
const event = (id: string, source = '') => ({ currentTarget: { dataset: { id, source } } })
const provinceEvent = (province: string) => ({ currentTarget: { dataset: { province } } })
const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const page = (): SelectorPage => Object.assign({}, definition, {
  data: JSON.parse(JSON.stringify(definition.data)) as SelectorPage['data'],
  setData(update: Partial<SelectorPage['data']>) { Object.assign(this.data, update) },
})

before(async () => {
  ;(globalThis as unknown as { Page: (value: SelectorPage) => void }).Page = value => { definition = value }
  await import('../pages/campus/index')
})
beforeEach(() => {
  routes = []
  storage = new Map()
  ;(globalThis as unknown as { wx: unknown }).wx = {
    hideKeyboard() {},
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: unknown) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    switchTab: ({ url, success }: NavigationOptions) => { routes.push(url); success?.() },
  }
  source.searchUniversities = async () => result([])
  source.getUniversityCommunity = async () => community
})
afterEach(() => {
  source.searchUniversities = originalSearch
  source.getUniversityCommunity = originalCommunity
})

test('national searches ignore selected province and pagination retains the submitted keyword', async () => {
  const calls: UniversitySearchCriteria[] = []
  source.searchUniversities = async criteria => {
    calls.push(criteria)
    return result(criteria.page === 0 ? [university('one')] : [university('one'), university('two')], criteria.page, 16)
  }
  const selector = page()
  selector.setData({ q: '  潮州  ', activeProvince: '江苏省' })
  await selector.onSearch()
  selector.setData({ q: '尚未搜索的输入' })
  const next = selector.loadMoreUniversities()
  await selector.loadMoreUniversities()
  await next
  assert.deepEqual(calls, [
    { province: '', q: '潮州', page: 0, size: 8 },
    { province: '', q: '潮州', page: 1, size: 8 },
  ])
  assert.deepEqual(selector.data.universities.map(item => item.id), ['one', 'two'])
  assert.equal(selector.data.hasMoreUniversities, false)
})

test('new national search wins over late old search responses', async () => {
  const late = deferred<PageResponse<UniversityResponse>>()
  const selector = page()
  source.searchUniversities = async criteria => criteria.q === 'old' ? late.promise : result([university('new')])
  selector.setData({ q: 'old' })
  const first = selector.onSearch()
  selector.setData({ q: 'new' })
  await selector.onSearch()
  late.resolve(result([university('old')]))
  await first
  assert.equal(selector.data.universities[0].id, 'new')
  assert.equal(selector.data.loading, false)
})

test('late pagination cannot append old search results after a fresh search', async () => {
  const pendingPage = deferred<PageResponse<UniversityResponse>>()
  source.searchUniversities = async criteria => criteria.page === 1
    ? pendingPage.promise : result([university(criteria.q)], 0, 16)
  const selector = page()
  selector.setData({ q: 'old' })
  await selector.onSearch()
  const loadMore = selector.loadMoreUniversities()
  selector.setData({ q: 'new' })
  await selector.onSearch()
  pendingPage.resolve(result([university('old-page-1')], 1, 16))
  await loadMore
  assert.deepEqual(selector.data.universities.map(item => item.id), ['new'])
  assert.equal(selector.data.universityPage, 0)
  assert.equal(selector.data.loadingMore, false)
})

test('province pagination blocks duplicates and retries failures without discarding loaded schools', async () => {
  const pendingPage = deferred<PageResponse<UniversityResponse>>()
  const calls: number[] = []
  let retry = false
  source.searchUniversities = async criteria => {
    calls.push(criteria.page)
    return criteria.page === 0 ? result([university('first')], 0, 16)
      : retry ? result([university('second')], 1, 16) : pendingPage.promise
  }
  const selector = page()
  await selector.openProvincePicker()
  const loadMore = selector.loadMoreProvinceUniversities()
  await selector.loadMoreProvinceUniversities()
  pendingPage.reject(new Error('network unavailable'))
  await loadMore
  assert.equal(selector.data.provinceUniversities[0].id, 'first')
  assert.ok(selector.data.provinceError)
  retry = true
  // Retry uses the next page, not a reset of the already loaded page.
  await (selector as SelectorPage & { retryProvince(): Promise<void> }).retryProvince()
  assert.deepEqual(calls, [0, 1, 1])
  assert.deepEqual(selector.data.provinceUniversities.map(item => item.id), ['first', 'second'])
  assert.equal(selector.data.provinceHasMore, false)
})

test('province requests are paged, independent from national search, and reject stale province responses', async () => {
  const oldProvince = deferred<PageResponse<UniversityResponse>>()
  const calls: UniversitySearchCriteria[] = []
  source.searchUniversities = async criteria => {
    calls.push(criteria)
    return criteria.province === '广东省' ? oldProvince.promise : result([university('jiangsu')], criteria.page, 16)
  }
  const selector = page()
  selector.setData({ q: '韩山', universities: [university('national')] })
  const opening = selector.openProvincePicker()
  await selector.onProvinceTap(provinceEvent('江苏省'))
  await selector.loadMoreProvinceUniversities()
  oldProvince.resolve(result([university('guangdong')]))
  await opening
  assert.equal(selector.data.provinceUniversities[0].id, 'jiangsu')
  assert.equal(selector.data.universities[0].id, 'national')
  assert.deepEqual(calls.map(({ province, q, page }) => ({ province, q, page })), [
    { province: '广东省', q: '', page: 0 }, { province: '江苏省', q: '', page: 0 }, { province: '江苏省', q: '', page: 1 },
  ])
  selector.closeProvincePicker()
  await selector.onSearch()
  assert.equal(calls.at(-1)?.province, '')
  assert.equal(calls.at(-1)?.q, '韩山')
})

test('closing overlay or leaving page invalidates pending province and community requests', async () => {
  const pending = deferred<PageResponse<UniversityResponse>>()
  source.searchUniversities = async () => pending.promise
  const selector = page()
  const opening = selector.openProvincePicker()
  selector.closeProvincePicker()
  pending.resolve(result([university('late')]))
  await opening
  assert.deepEqual(selector.data.provinceUniversities, [])
  const openingCommunity = deferred<CampusResponse>()
  source.getUniversityCommunity = async () => openingCommunity.promise
  selector.setData({ universities: [university('hanshan')] })
  const selection = selector.chooseUniversity(event('hanshan'))
  selector.onHide()
  openingCommunity.resolve(community)
  await selection
  assert.equal(storage.size, 0)
  assert.deepEqual(routes, [])
})

test('errors preserve existing search results, retry correct page, and never fake a community selection', async () => {
  let failed = true
  const calls: number[] = []
  source.searchUniversities = async criteria => {
    calls.push(criteria.page)
    if (criteria.page === 1 && failed) throw new Error('service unavailable')
    return result([university(String(criteria.page))], criteria.page, 16)
  }
  const selector = page()
  await selector.onSearch()
  await selector.loadMoreUniversities()
  assert.equal(selector.data.universities.length, 1)
  assert.ok(selector.data.errorMessage)
  failed = false
  await selector.onRetry()
  assert.deepEqual(calls, [0, 1, 1])
  assert.deepEqual(selector.data.universities.map(item => item.id), ['0', '1'])
  source.getUniversityCommunity = async () => { throw new Error('community unavailable') }
  await selector.chooseUniversity(event('0'))
  assert.ok(selector.data.selectionError)
  assert.equal(storage.size, 0)
  assert.deepEqual(routes, [])
})

test('unopened universities cannot enter; open selections persist the stable campus and preserve all returnTo tabs', async () => {
  let communityCalls = 0
  source.getUniversityCommunity = async () => { communityCalls += 1; return community }
  for (const returnTo of ['animals', 'circle', 'rescue']) {
    const selector = page()
    selector.onLoad({ returnTo })
    await Promise.resolve()
    selector.setData({ provincePickerOpen: true, provinceUniversities: [university('closed', false), university('hanshan')] })
    await selector.chooseUniversity(event('closed', 'province'))
    assert.equal(communityCalls, routes.length)
    await selector.chooseUniversity(event('hanshan', 'province'))
    assert.equal(selector.data.provincePickerOpen, false)
    assert.equal(routes.at(-1), '/pages/' + returnTo + '/index')
    assert.deepEqual(storage.get('animallink.selectedCampus.v1'), community)
    assert.equal(selector.data.selectedUniversityId, 'hanshan')
  }
  assert.equal(communityCalls, 3)
})

test('double selection makes one community request; unload prevents late storage or navigation', async () => {
  const pending = deferred<CampusResponse>()
  let calls = 0
  source.getUniversityCommunity = async () => { calls += 1; return pending.promise }
  const selector = page()
  selector.setData({ universities: [university('hanshan')] })
  const selection = selector.chooseUniversity(event('hanshan'))
  await selector.chooseUniversity(event('hanshan'))
  selector.onUnload()
  pending.resolve(community)
  await selection
  assert.equal(calls, 1)
  assert.equal(storage.size, 0)
  assert.deepEqual(routes, [])
})

test('selection stays locked until switchTab completes, including taps on another school and filter actions', async () => {
  let navigation: NavigationOptions | undefined
  ;(wx as unknown as { switchTab: (options: NavigationOptions) => void }).switchTab = options => {
    routes.push(options.url)
    navigation = options
  }
  let calls = 0
  source.getUniversityCommunity = async () => { calls += 1; return community }
  const selector = page()
  selector.setData({ universities: [university('hanshan'), university('another')] })
  const selection = selector.chooseUniversity(event('hanshan'))
  await Promise.resolve()
  assert.ok(navigation)
  assert.equal(selector.data.openingUniversityId, 'hanshan')
  await selector.chooseUniversity(event('hanshan'))
  await selector.chooseUniversity(event('another'))
  selector.closeProvincePicker()
  await selector.openProvincePicker()
  await selector.onSearch()
  selector.onHide()
  await selector.chooseUniversity(event('another'))
  assert.equal(calls, 1)
  assert.equal(routes.length, 1)
  assert.equal(selector.data.openingUniversityId, 'hanshan')
  navigation.success?.()
  await selection
  assert.equal(selector.data.openingUniversityId, '')
  assert.deepEqual(storage.get('animallink.selectedCampus.v1'), community)
})

test('navigation failure restores the previous campus and selected label, releases lock and allows retry', async () => {
  const previousCampus = { ...community, id: 'previous-campus', universityId: 'previous-school' }
  storage.set('animallink.selectedCampus.v1', previousCampus)
  const navigations: NavigationOptions[] = []
  ;(wx as unknown as { switchTab: (options: NavigationOptions) => void }).switchTab = options => {
    routes.push(options.url)
    navigations.push(options)
  }
  const selector = page()
  selector.setData({ universities: [university('hanshan')], selectedUniversityId: 'previous-school' })
  const selection = selector.chooseUniversity(event('hanshan'))
  await Promise.resolve()
  assert.deepEqual(storage.get('animallink.selectedCampus.v1'), community)
  navigations[0].fail?.()
  await selection
  assert.deepEqual(storage.get('animallink.selectedCampus.v1'), previousCampus)
  assert.equal(selector.data.selectedUniversityId, 'previous-school')
  assert.equal(selector.data.openingUniversityId, '')
  assert.match(selector.data.selectionError, /页面跳转失败/)
  const retry = selector.chooseUniversity(event('hanshan'))
  await Promise.resolve()
  assert.equal(navigations.length, 2)
  navigations[1].success?.()
  await retry
  assert.deepEqual(storage.get('animallink.selectedCampus.v1'), community)
  assert.equal(selector.data.selectionError, '')
  assert.equal(selector.data.selectedUniversityId, 'hanshan')
})

test('failed navigation with no previous campus removes the provisional context', async () => {
  ;(wx as unknown as { switchTab: (options: NavigationOptions) => void }).switchTab = options => options.fail?.()
  const selector = page()
  selector.setData({ universities: [university('hanshan')] })
  await selector.chooseUniversity(event('hanshan'))
  assert.equal(storage.has('animallink.selectedCampus.v1'), false)
  assert.equal(selector.data.selectedUniversityId, '')
  assert.equal(selector.data.openingUniversityId, '')
  assert.match(selector.data.selectionError, /页面跳转失败/)
})

test('successful switchTab may unload before its callback without rolling back context or updating the dead page', async () => {
  let navigation: NavigationOptions | undefined
  ;(wx as unknown as { switchTab: (options: NavigationOptions) => void }).switchTab = options => {
    routes.push(options.url)
    navigation = options
  }
  const selector = page()
  selector.setData({ universities: [university('hanshan')] })
  const selection = selector.chooseUniversity(event('hanshan'))
  await Promise.resolve()
  selector.onUnload()
  selector.setData = () => { throw new Error('unloaded page must not be updated') }
  navigation?.success?.()
  await selection
  assert.deepEqual(storage.get('animallink.selectedCampus.v1'), community)
  assert.equal(routes.length, 1)
})

test('late navigation failure rolls back context after unload without altering a newer outside selection', async () => {
  const previousCampus = { ...community, id: 'previous-campus', universityId: 'previous-school' }
  for (const newerSelection of [false, true]) {
    storage.set('animallink.selectedCampus.v1', previousCampus)
    let navigation: NavigationOptions | undefined
    ;(wx as unknown as { switchTab: (options: NavigationOptions) => void }).switchTab = options => { navigation = options }
    const selector = page()
    selector.setData({ universities: [university('hanshan')] })
    const selection = selector.chooseUniversity(event('hanshan'))
    await Promise.resolve()
    selector.onUnload()
    selector.setData = () => { throw new Error('unloaded page must not be updated') }
    const outsideCampus = { ...community, id: 'outside-campus' }
    if (newerSelection) storage.set('animallink.selectedCampus.v1', outsideCampus)
    navigation?.fail?.()
    await selection
    assert.deepEqual(storage.get('animallink.selectedCampus.v1'), newerSelection ? outsideCampus : previousCampus)
  }
})

test('an older cancelled community response cannot unlock a newer navigation', async () => {
  const oldQuery = deferred<CampusResponse>()
  let calls = 0
  source.getUniversityCommunity = async id => { calls += 1; return id === 'old' ? oldQuery.promise : community }
  let navigation: NavigationOptions | undefined
  ;(wx as unknown as { switchTab: (options: NavigationOptions) => void }).switchTab = options => { navigation = options }
  const selector = page()
  selector.setData({ universities: [university('old'), university('hanshan')] })
  const oldSelection = selector.chooseUniversity(event('old'))
  selector.closeProvincePicker()
  const newSelection = selector.chooseUniversity(event('hanshan'))
  await Promise.resolve()
  oldQuery.resolve({ ...community, id: 'old-campus' })
  await oldSelection
  selector.closeProvincePicker()
  await selector.chooseUniversity(event('old'))
  assert.equal(selector.data.openingUniversityId, 'hanshan')
  assert.equal(calls, 2)
  navigation?.success?.()
  await newSelection
  assert.deepEqual(storage.get('animallink.selectedCampus.v1'), community)
})

test('offline national lookup supports school, city and institution code without a province', async () => {
  const offline = new OfflinePublicAnimalDataSource()
  for (const q of ['韩山', '潮州', '4144010578']) {
    const found = await offline.searchUniversities({ province: '', q, page: 0, size: 8 })
    assert.equal(found.items[0].officialName, '韩山师范学院')
  }
  const first = await offline.searchUniversities({ province: '', q: '', page: 0, size: 2 })
  const second = await offline.searchUniversities({ province: '', q: '', page: 1, size: 2 })
  assert.equal(first.total, 5)
  assert.equal(new Set([...first.items, ...second.items].map(item => item.id)).size, 4)
  assert.ok((await offline.searchUniversities({ province: '浙江省', q: '', page: 0, size: 8 })).items.every(item => item.province === '浙江省'))
})

test('API national lookup forwards bounded pagination and does not fall back on failure', async () => {
  const calls: unknown[] = []
  const api = new ApiPublicAnimalDataSource(async (path, query) => { calls.push({ path, query }); throw new Error('503') })
  await assert.rejects(api.searchUniversities({ province: '', q: '4144010578', page: 1, size: 8 }), /503/)
  assert.deepEqual(calls, [{ path: '/identity/api/v1/universities', query: { province: '', q: '4144010578', page: 1, size: 8 } }])
})

test('WXML binds the floating two-scroll selector, outside dismissal, search, and retry handlers', async () => {
  const markup = await readFile('pages/campus/index.wxml', 'utf8')
  const css = await readFile('pages/campus/index.wxss', 'utf8')
  assert.match(markup, />按省找校⌄<\/button>/)
  assert.match(markup, /bindconfirm="onSearch"/)
  assert.match(markup, /class="province-overlay" bindtap="closeProvincePicker"/)
  assert.match(markup, /catchtap="stopPropagation"/)
  assert.match(markup, /class="province-column" scroll-y/)
  assert.match(markup, /class="school-scroll" scroll-y/)
  assert.match(markup, /bindscrolltolower="loadMoreProvinceUniversities"/)
  assert.match(css, /\.province-overlay \{ position: fixed;/)
  assert.doesNotMatch(markup, /<picker/)
  for (const binding of markup.matchAll(/(?:bind|catch)(?:tap|input|confirm|scrolltolower|touchmove)="([^"]+)"/g)) {
    assert.equal(typeof (definition as unknown as Record<string, unknown>)[binding[1]], 'function', binding[1] + ' must have a page handler')
  }
})

test('national search input is present initially, accepts typing before searching, and remains usable after province browsing', async () => {
  const calls: UniversitySearchCriteria[] = []
  source.searchUniversities = async criteria => { calls.push(criteria); return result([]) }
  const selector = page()
  selector.onLoad({ returnTo: 'circle' })
  await Promise.resolve()
  assert.equal(selector.data.provincePickerOpen, false)
  selector.onKeywordInput({ detail: { value: '潮州' } })
  assert.equal(selector.data.q, '潮州')
  assert.equal(calls.length, 1)
  await selector.onSearch()
  assert.deepEqual(calls.at(-1), { province: '', q: '潮州', page: 0, size: 8 })
  await selector.openProvincePicker()
  await selector.onProvinceTap(provinceEvent('浙江省'))
  selector.closeProvincePicker()
  assert.equal(selector.data.q, '潮州')
  selector.onKeywordInput({ detail: { value: '4144010578' } })
  await selector.onSearch()
  assert.deepEqual(calls.at(-1), { province: '', q: '4144010578', page: 0, size: 8 })
})

test('search toolbar always renders its input outside conditionals, with separate mini buttons and explicit width bounds', async () => {
  const markup = await readFile('pages/campus/index.wxml', 'utf8')
  const css = await readFile('pages/campus/index.wxss', 'utf8')
  // Only inspect the initial toolbar, before results or the conditional overlay.
  const toolbar = markup.slice(markup.indexOf('<view class="filters surface">'), markup.indexOf('<view class="result-meta">'))
  assert.match(toolbar, /<view class="search-field">\s*<input class="university-search-input"/)
  assert.match(toolbar, /placeholder="搜索全国高校"/)
  assert.match(toolbar, /bindinput="onKeywordInput" bindconfirm="onSearch"/)
  assert.doesNotMatch(toolbar, /wx:if|wx:elif|wx:else|hidden=|display:\s*none/)
  assert.match(toolbar, /<button size="mini" class="secondary-button search-button" bindtap="onSearch">搜索<\/button>/)
  assert.match(toolbar, /<button size="mini" class="province-button" bindtap="openProvincePicker"/)
  assert.match(css, /\.search-field \{[^}]*min-width: 280rpx;/)
  assert.match(css, /\.search-field \.university-search-input \{[^}]*width: 100%;/)
  assert.match(css, /\.search-row \.search-button \{ width: 104rpx;/)
  assert.match(css, /\.search-row \.province-button \{ width: 168rpx;/)
  assert.match(css, /@media \(max-width: 360px\) \{[^]*?\.province-entry \{ flex-basis: 100%;/)
})
