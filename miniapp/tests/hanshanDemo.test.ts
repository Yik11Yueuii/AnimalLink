import assert from 'node:assert/strict'
import test from 'node:test'
import { OfflineCommunityDataSource } from '../services/offlineCommunityDataSource'
import { OfflinePublicAnimalDataSource } from '../services/offlineDataSource'

const HANSHAN_UNIVERSITY_ID = '21000000-0000-4000-8000-000000001010'
const HANSHAN_CAMPUS_ID = '22000000-0000-4000-8000-000000000001'
const XIAO_JU_ID = '23000000-0000-4000-8000-000000000001'

test('Hanshan offline demo keeps official school context and stable cross-feature IDs', async () => {
  const animalSource = new OfflinePublicAnimalDataSource()
  const communitySource = new OfflineCommunityDataSource()

  const universities = await animalSource.searchUniversities({
    province: '广东省',
    q: '4144010578',
    page: 0,
    size: 20,
  })
  assert.equal(universities.total, 1)
  assert.equal(universities.items[0].id, HANSHAN_UNIVERSITY_ID)
  assert.equal(universities.items[0].officialName, '韩山师范学院')
  assert.equal(universities.items[0].communityAvailable, true)

  const community = await animalSource.getUniversityCommunity(HANSHAN_UNIVERSITY_ID)
  assert.equal(community.id, HANSHAN_CAMPUS_ID)
  assert.equal(community.name, '韩山师范学院社区')

  const animals = await animalSource.searchAnimals({
    campusId: HANSHAN_CAMPUS_ID,
    q: '',
    species: '',
    page: 0,
    size: 20,
  })
  assert.equal(animals.total, 3)
  assert.deepEqual(animals.items.map((item) => item.displayName), ['小橘', '墨墨', '阿黄'])

  const feed = await communitySource.getFeed(HANSHAN_CAMPUS_ID, 0, 20)
  assert.equal(feed.total, 4)
  assert.ok(feed.items.every((item) => item.textContent.startsWith('【虚构演示】')))
  assert.ok(feed.items.every((item) => item.id !== '33000000-0000-4000-8000-000000000002'))

  const relatedAnimalIds = feed.items.flatMap((item) => item.animal ? [item.animal.id] : [])
  assert.ok(relatedAnimalIds.includes(XIAO_JU_ID))
  for (const animalId of new Set(relatedAnimalIds)) {
    const detail = await animalSource.getAnimal(animalId)
    assert.equal(detail.campusId, HANSHAN_CAMPUS_ID)
  }

  const comments = await communitySource.getComments(
    '33000000-0000-4000-8000-000000000001',
    0,
    20,
  )
  assert.equal(comments.total, 2)
  assert.ok(comments.items.every((item) => item.content.startsWith('【虚构演示】')))
  assert.ok(comments.items.every((item) => item.authorDisplayName === '校园成员'))
})

test('Hanshan offline demo remains isolated from existing campuses', async () => {
  const animalSource = new OfflinePublicAnimalDataSource()
  const communitySource = new OfflineCommunityDataSource()

  const hanshanAnimals = await animalSource.searchAnimals({
    campusId: HANSHAN_CAMPUS_ID,
    q: '',
    species: 'DOG',
    page: 0,
    size: 20,
  })
  const legacyAnimals = await animalSource.searchAnimals({
    campusId: 'campus-mingde',
    q: '',
    species: '',
    page: 0,
    size: 20,
  })
  const legacyFeed = await communitySource.getFeed('campus-mingde', 0, 20)

  assert.deepEqual(hanshanAnimals.items.map((item) => item.displayName), ['阿黄'])
  assert.equal(legacyAnimals.total, 4)
  assert.equal(legacyFeed.total, 5)
  assert.ok(legacyFeed.items.every((item) => item.campusId === 'campus-mingde'))
})
