import { computed, reactive, ref } from 'vue'
import {
  CommunityReviewApiError,
  getReviewComment,
  getReviewPost,
  listReviewComments,
  listReviewPosts,
  reviewComment,
  reviewPost,
} from './dataSource'
import type {
  CommunityReviewComment,
  CommunityReviewCriteria,
  CommunityReviewPost,
  CommunityReviewStatus,
  ReviewDecision,
} from './types'

type ReviewKind = 'posts' | 'comments'
type Detail = CommunityReviewPost | CommunityReviewComment

const defaults = (): CommunityReviewCriteria => ({ reviewStatus: 'PENDING', campusId: '', page: 0, size: 20, sort: 'NEWEST' })
const errorMessage = (error: unknown) => {
  if (!(error instanceof CommunityReviewApiError)) return '审核数据加载失败，请稍后重试。'
  if (error.kind === 'unauthenticated') return '管理员会话已失效，请重新登录。'
  if (error.kind === 'forbidden') return '当前会话没有审核权限，或请求未通过安全校验。'
  if (error.kind === 'conflict') return '内容已被其他治理操作更新，请重新读取。'
  if (error.kind === 'invalid-response') return '审核服务返回了无法验证的数据。'
  return '审核服务暂时不可用，请稍后重试。'
}

export const useCommunityReview = () => {
  const activeTab = ref<ReviewKind>('posts')
  const criteria = reactive(defaults())
  const posts = ref<CommunityReviewPost[]>([])
  const comments = ref<CommunityReviewComment[]>([])
  const total = ref(0)
  const loading = ref(false)
  const error = ref<CommunityReviewApiError | null>(null)
  const detail = ref<Detail | null>(null)
  const detailOpen = ref(false)
  const detailLoading = ref(false)
  const detailError = ref<CommunityReviewApiError | null>(null)
  const selectedId = ref('')
  let loadGeneration = 0
  let detailGeneration = 0

  const items = computed(() => activeTab.value === 'posts' ? posts.value : comments.value)
  const pageNumber = computed(() => criteria.page + 1)

  const load = async (resetPage = false) => {
    if (resetPage) criteria.page = 0
    const generation = ++loadGeneration
    loading.value = true
    error.value = null
    try {
      const result = activeTab.value === 'posts'
        ? await listReviewPosts({ ...criteria })
        : await listReviewComments({ ...criteria })
      if (generation !== loadGeneration) return
      if (activeTab.value === 'posts') posts.value = result.items as CommunityReviewPost[]
      else comments.value = result.items as CommunityReviewComment[]
      total.value = result.total
      criteria.page = result.page
      criteria.size = result.size
    } catch (caught) {
      if (generation !== loadGeneration) return
      error.value = caught instanceof CommunityReviewApiError ? caught : new CommunityReviewApiError('unavailable')
      if (activeTab.value === 'posts') posts.value = []
      else comments.value = []
      total.value = 0
    } finally {
      if (generation === loadGeneration) loading.value = false
    }
  }

  const changeTab = async (tab: string | number) => {
    activeTab.value = tab === 'comments' ? 'comments' : 'posts'
    Object.assign(criteria, defaults())
    closeDetail()
    await load()
  }
  const changeStatus = async (status: CommunityReviewStatus) => { criteria.reviewStatus = status; await load(true) }
  const changePage = async (page: number) => { criteria.page = Math.max(0, page - 1); await load() }
  const changeSize = async (size: number) => { criteria.size = size; await load(true) }

  const openDetail = async (id: string) => {
    selectedId.value = id
    const generation = ++detailGeneration
    detailOpen.value = true
    detailLoading.value = true
    detailError.value = null
    detail.value = null
    try {
      const result = activeTab.value === 'posts' ? await getReviewPost(id) : await getReviewComment(id)
      if (generation === detailGeneration) detail.value = result
    } catch (caught) {
      if (generation === detailGeneration) detailError.value = caught instanceof CommunityReviewApiError ? caught : new CommunityReviewApiError('unavailable')
    } finally {
      if (generation === detailGeneration) detailLoading.value = false
    }
  }
  const retryDetail = async () => { if (selectedId.value) await openDetail(selectedId.value) }
  const closeDetail = () => {
    detailGeneration += 1
    detailOpen.value = false
    detail.value = null
    detailError.value = null
    detailLoading.value = false
    selectedId.value = ''
  }

  const review = async (decision: ReviewDecision, reason: string) => {
    if (!detail.value) return
    const value = detail.value
    const result = 'commentId' in value
      ? await reviewComment(value.commentId, decision, reason)
      : await reviewPost(value.postId, decision, reason)
    detail.value = result
    await load()
    return result
  }

  return {
    activeTab, criteria, items, total, loading, error, detail, detailOpen, detailLoading, detailError,
    pageNumber, load, changeTab, changeStatus, changePage, changeSize, openDetail, retryDetail, closeDetail,
    review, errorMessage,
  }
}
