import assert from 'node:assert/strict'
import test from 'node:test'
import { toCommunityPostView } from '../utils/communityPresentation'
import type { CommunityPostResponse } from '../types/community'

const basePost: CommunityPostResponse = {
  id: 'post-visual-1',
  campusId: '22000000-0000-4000-8000-000000000001',
  postType: 'CAMPUS_POST',
  textContent: '【虚构演示】小橘在花坛边晒太阳。',
  authorDisplayName: '校园成员',
  animal: null,
  likeCount: 0,
  commentCount: 0,
  createdAt: '2026-10-01T08:30:00Z',
  updatedAt: '2026-10-01T08:30:00Z',
}

test('community presentation preserves the backend PostType semantics', () => {
  const cases = [
    ['CAMPUS_POST', '校园日常'],
    ['RESCUE_UPDATE', '救助进展'],
    ['ADOPTION', '领养信息'],
    ['POST_ADOPTION', '领养后近况'],
  ] as const

  for (const [postType, expectedLabel] of cases) {
    const view = toCommunityPostView({ ...basePost, postType })
    assert.equal(view.typeText, expectedLabel)
    assert.equal(view.likeCount, 0)
    assert.equal(view.commentCount, 0)
  }
})

test('community presentation keeps an optional Animal relation optional', () => {
  const view = toCommunityPostView(basePost)
  assert.equal(view.animal, null)
  assert.equal(view.animalSpeciesText, '')
  assert.equal(view.authorDisplayName, '校园成员')
})
