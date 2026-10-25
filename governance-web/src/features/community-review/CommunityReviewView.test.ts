import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const catalog = vi.hoisted(() => ({
  activeTab: { value: 'posts' },
  criteria: { reviewStatus: 'PENDING', campusId: '', page: 0, size: 20, sort: 'NEWEST' },
  items: { value: [{ postId: 'post-1', campusId: 'campus-1', animalId: null, postType: 'CAMPUS_POST', textContent: '等待人工审核的真实 DTO 内容', status: 'ACTIVE', reviewStatus: 'PENDING', reviewedAt: null, version: 0, likeCount: 2, activeCommentCount: 1, hiddenCommentCount: 0, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }] },
  total: { value: 1 }, loading: { value: false }, error: { value: null }, detail: { value: null }, detailOpen: { value: false }, detailLoading: { value: false }, detailError: { value: null }, pageNumber: { value: 1 },
  load: vi.fn().mockResolvedValue(undefined), changeTab: vi.fn().mockResolvedValue(undefined), changeStatus: vi.fn().mockResolvedValue(undefined), changePage: vi.fn().mockResolvedValue(undefined), changeSize: vi.fn().mockResolvedValue(undefined), openDetail: vi.fn().mockResolvedValue(undefined), retryDetail: vi.fn().mockResolvedValue(undefined), closeDetail: vi.fn(), review: vi.fn().mockResolvedValue(undefined), errorMessage: vi.fn(() => '错误'),
}))

vi.mock('./useCommunityReview', () => ({ useCommunityReview: () => catalog }))

import CommunityReviewView from './CommunityReviewView.vue'

describe('CommunityReviewView', () => {
  beforeEach(() => {
    catalog.activeTab.value = 'posts'
    catalog.load.mockClear()
    catalog.openDetail.mockClear()
  })

  it('renders real review DTO fields without inventing author or media data', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/content', component: CommunityReviewView }, { path: '/login', component: { template: '<div>login</div>' } }] })
    await router.push('/content')
    await router.isReady()
    const wrapper = mount(CommunityReviewView, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('内容治理')
    expect(wrapper.text()).toContain('等待人工审核的真实 DTO 内容')
    expect(wrapper.text()).toContain('校园动态')
    expect(wrapper.text()).not.toContain('作者')
    expect(wrapper.text()).not.toContain('媒体预览')
  })
})
