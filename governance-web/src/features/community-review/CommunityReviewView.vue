<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CommunityReviewApiError } from './dataSource'
import { useCommunityReview } from './useCommunityReview'
import { postTypeLabels, reviewStatusLabels, type CommunityReviewComment, type CommunityReviewPost, type CommunityReviewStatus, type ReviewDecision } from './types'

const router = useRouter()
const review = useCommunityReview()
const drawerSize = 'min(670px, 100vw)'
const dialogOpen = ref(false)
const decision = ref<ReviewDecision>('approve')
const reason = ref('')
const submitting = ref(false)
const reasonError = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(review.total.value / review.criteria.size)))
const isPost = computed(() => Boolean(review.detail.value && !('commentId' in review.detail.value)))
const detailTitle = computed(() => isPost.value ? '帖子审核详情' : '评论审核详情')
const formatTime = (value: string | null) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
const compactId = (value: string | null) => value ? `${value.slice(0, 8)}…${value.slice(-4)}` : '—'
const tagType = (status: CommunityReviewStatus) => ({ PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' } as const)[status]
const operationalTag = (status: string) => status === 'ACTIVE' ? 'success' : status === 'HIDDEN' ? 'warning' : 'danger'
const statusMessage = (error: unknown) => review.errorMessage(error)

const handleFailure = async (error: unknown) => {
  const message = statusMessage(error)
  if (error instanceof CommunityReviewApiError && error.kind === 'unauthenticated') {
    ElMessage.warning(message)
    await router.replace({ name: 'login', query: { redirect: '/content' } })
    return
  }
  ElMessage.error(message)
}
const reload = async (reset = false) => {
  await review.load(reset)
  if (review.error.value?.kind === 'unauthenticated') await handleFailure(review.error.value)
}
const switchTab = async (tab: string | number) => {
  await review.changeTab(tab)
  if (review.error.value?.kind === 'unauthenticated') await handleFailure(review.error.value)
}
const startReview = (next: ReviewDecision) => {
  decision.value = next
  reason.value = ''
  reasonError.value = ''
  dialogOpen.value = true
}
const submitReview = async () => {
  const trimmed = reason.value.trim()
  if (trimmed.length < 1 || trimmed.length > 1000) {
    reasonError.value = '审核理由需为 1–1000 个字符。'
    return
  }
  submitting.value = true
  reasonError.value = ''
  try {
    await review.review(decision.value, trimmed)
    dialogOpen.value = false
    ElMessage.success(decision.value === 'approve' ? '内容已批准。' : '内容已拒绝。')
  } catch (error) {
    if (error instanceof CommunityReviewApiError && error.kind === 'conflict') {
      ElMessage.warning('内容已发生变化，已重新读取最新状态。')
      await review.retryDetail()
      await reload()
    } else {
      await handleFailure(error)
    }
  } finally {
    submitting.value = false
  }
}

onMounted(() => { void reload() })
</script>

<template>
  <section class="review-page" aria-labelledby="review-title">
    <header class="review-hero">
      <div><p class="eyebrow">COMMUNITY CONTENT GOVERNANCE</p><h1 id="review-title">内容治理</h1><p>审核 Campus Circle 的帖子与评论。批准仅影响审核状态；隐藏、移除等内容状态仍以服务端规则为准。</p></div>
      <div class="review-summary"><strong>{{ review.total.value }}</strong><span>条当前结果</span></div>
    </header>

    <el-card class="review-filters" shadow="never">
      <el-tabs :model-value="review.activeTab.value" @tab-change="switchTab">
        <el-tab-pane label="帖子审核" name="posts" />
        <el-tab-pane label="评论审核" name="comments" />
      </el-tabs>
      <el-form class="review-filter-grid" label-position="top" @submit.prevent="reload(true)">
        <el-form-item label="审核状态"><el-select :model-value="review.criteria.reviewStatus" @update:model-value="review.changeStatus"><el-option v-for="(label, value) in reviewStatusLabels" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="校区 ID（可选）"><el-input v-model="review.criteria.campusId" clearable placeholder="仅筛选指定校区" @keyup.enter="reload(true)" /></el-form-item>
        <el-form-item label="排序"><el-select v-model="review.criteria.sort" @change="reload(true)"><el-option label="最新创建" value="NEWEST" /><el-option label="最早创建" value="OLDEST" /></el-select></el-form-item>
        <div class="review-filter-actions"><el-button type="primary" @click="reload(true)">查询</el-button><el-button @click="review.criteria.campusId = ''; review.changeStatus('PENDING')">重置</el-button></div>
      </el-form>
    </el-card>

    <div v-if="review.loading.value" class="review-state" v-loading="true"><strong>正在读取审核队列…</strong></div>
    <div v-else-if="review.error.value" class="review-state review-state--error"><strong>审核队列读取失败</strong><span>{{ statusMessage(review.error.value) }}</span><el-button type="primary" @click="reload()">重试</el-button></div>
    <div v-else-if="!review.items.value.length" class="review-state"><span class="review-state-icon">✓</span><strong>当前没有符合条件的内容</strong><span>可调整审核状态或校区筛选后再试。</span></div>

    <template v-else>
      <el-table v-if="review.activeTab.value === 'posts'" class="review-table" :data="review.items.value as CommunityReviewPost[]" @row-click="(row: CommunityReviewPost) => review.openDetail(row.postId)">
        <el-table-column label="帖子内容" min-width="290"><template #default="{ row }: { row: CommunityReviewPost }"><div class="content-cell"><strong>{{ postTypeLabels[row.postType] }}</strong><p>{{ row.textContent }}</p><small>帖子 {{ compactId(row.postId) }}</small></div></template></el-table-column>
        <el-table-column label="校区" min-width="130"><template #default="{ row }: { row: CommunityReviewPost }"><code>{{ compactId(row.campusId) }}</code></template></el-table-column>
        <el-table-column label="内容 / 审核" width="135"><template #default="{ row }: { row: CommunityReviewPost }"><el-tag :type="operationalTag(row.status)">{{ row.status }}</el-tag><el-tag :type="tagType(row.reviewStatus)" effect="plain">{{ reviewStatusLabels[row.reviewStatus] }}</el-tag></template></el-table-column>
        <el-table-column label="互动" width="112"><template #default="{ row }: { row: CommunityReviewPost }"><span>赞 {{ row.likeCount }}</span><small class="table-sub">评论 {{ row.activeCommentCount }}</small></template></el-table-column>
        <el-table-column label="创建时间" min-width="165"><template #default="{ row }: { row: CommunityReviewPost }">{{ formatTime(row.createdAt) }}</template></el-table-column>
        <el-table-column width="76"><template #default="{ row }: { row: CommunityReviewPost }"><el-button link type="primary" @click.stop="review.openDetail(row.postId)">详情</el-button></template></el-table-column>
      </el-table>
      <el-table v-else class="review-table" :data="review.items.value as CommunityReviewComment[]" @row-click="(row: CommunityReviewComment) => review.openDetail(row.commentId)">
        <el-table-column label="评论内容" min-width="310"><template #default="{ row }: { row: CommunityReviewComment }"><div class="content-cell"><p>{{ row.content }}</p><small>评论 {{ compactId(row.commentId) }} · 帖子 {{ compactId(row.postId) }}</small></div></template></el-table-column>
        <el-table-column label="校区" min-width="130"><template #default="{ row }: { row: CommunityReviewComment }"><code>{{ compactId(row.campusId) }}</code></template></el-table-column>
        <el-table-column label="评论 / 父帖" width="140"><template #default="{ row }: { row: CommunityReviewComment }"><el-tag :type="operationalTag(row.status)">{{ row.status }}</el-tag><small class="table-sub">父帖 {{ row.parentPostStatus }}</small></template></el-table-column>
        <el-table-column label="审核状态" width="112"><template #default="{ row }: { row: CommunityReviewComment }"><el-tag :type="tagType(row.reviewStatus)">{{ reviewStatusLabels[row.reviewStatus] }}</el-tag></template></el-table-column>
        <el-table-column label="创建时间" min-width="165"><template #default="{ row }: { row: CommunityReviewComment }">{{ formatTime(row.createdAt) }}</template></el-table-column>
        <el-table-column width="76"><template #default="{ row }: { row: CommunityReviewComment }"><el-button link type="primary" @click.stop="review.openDetail(row.commentId)">详情</el-button></template></el-table-column>
      </el-table>
      <div class="review-pagination"><span>共 {{ review.total.value }} 条，第 {{ review.pageNumber.value }} / {{ totalPages }} 页</span><el-pagination background layout="sizes, prev, pager, next" :total="review.total.value" :page-size="review.criteria.size" :current-page="review.pageNumber.value" :page-sizes="[10,20,50]" @current-change="review.changePage" @size-change="review.changeSize" /></div>
    </template>

    <el-drawer v-model="review.detailOpen.value" :size="drawerSize" :with-header="false" destroy-on-close @closed="review.closeDetail">
      <div v-if="review.detailLoading.value" class="review-state review-drawer-state" v-loading="true"><strong>正在读取内容详情…</strong></div>
      <div v-else-if="review.detailError.value" class="review-state review-state--error review-drawer-state"><strong>详情加载失败</strong><span>{{ statusMessage(review.detailError.value) }}</span><el-button type="primary" @click="review.retryDetail()">重试</el-button></div>
      <template v-else-if="review.detail.value">
        <header class="review-drawer-head"><div><p class="eyebrow">{{ isPost ? 'POST REVIEW' : 'COMMENT REVIEW' }}</p><h2>{{ detailTitle }}</h2><code>{{ isPost ? (review.detail.value as CommunityReviewPost).postId : (review.detail.value as CommunityReviewComment).commentId }}</code></div><el-button circle aria-label="关闭详情" @click="review.detailOpen.value = false">×</el-button></header>
        <section class="review-status-panel"><el-tag size="large" :type="tagType(review.detail.value.reviewStatus)">{{ reviewStatusLabels[review.detail.value.reviewStatus] }}</el-tag><div><strong>内容状态：{{ review.detail.value.status }}</strong><span>审核时间：{{ formatTime(review.detail.value.reviewedAt) }}</span></div></section>
        <section class="review-detail-section"><h3>{{ isPost ? '帖子内容' : '评论内容' }}</h3><p class="review-copy">{{ isPost ? (review.detail.value as CommunityReviewPost).textContent : (review.detail.value as CommunityReviewComment).content }}</p><dl class="review-facts"><div><dt>校区 ID</dt><dd><code>{{ review.detail.value.campusId }}</code></dd></div><div><dt>创建时间</dt><dd>{{ formatTime(review.detail.value.createdAt) }}</dd></div><div v-if="isPost"><dt>帖子类型</dt><dd>{{ postTypeLabels[(review.detail.value as CommunityReviewPost).postType] }}</dd></div><div v-if="isPost"><dt>互动统计</dt><dd>赞 {{ (review.detail.value as CommunityReviewPost).likeCount }} · 活跃评论 {{ (review.detail.value as CommunityReviewPost).activeCommentCount }}</dd></div><div v-if="!isPost"><dt>所属帖子</dt><dd><code>{{ (review.detail.value as CommunityReviewComment).postId }}</code></dd></div><div v-if="!isPost"><dt>父帖状态</dt><dd>{{ (review.detail.value as CommunityReviewComment).parentPostStatus }}</dd></div></dl></section>
        <section class="review-action-panel"><div><strong>人工审核</strong><span>批准或拒绝会提交至服务端；不会通过前端伪造状态。</span></div><div class="review-action-buttons"><el-button type="success" :disabled="review.detail.value.reviewStatus === 'APPROVED'" @click="startReview('approve')">批准</el-button><el-button type="danger" plain :disabled="review.detail.value.reviewStatus === 'REJECTED'" @click="startReview('reject')">拒绝</el-button></div></section>
      </template>
    </el-drawer>

    <el-dialog v-model="dialogOpen" width="min(460px, calc(100vw - 28px))" :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting" :title="decision === 'approve' ? '确认批准内容' : '确认拒绝内容'">
      <p class="dialog-copy">请填写审核理由。该理由会随服务端审核记录保存。</p><el-input v-model="reason" type="textarea" :rows="5" maxlength="1000" show-word-limit placeholder="输入 1–1000 个字符的审核理由" :disabled="submitting" @input="reasonError = ''" /><p v-if="reasonError" class="reason-error">{{ reasonError }}</p>
      <template #footer><el-button :disabled="submitting" @click="dialogOpen = false">取消</el-button><el-button :type="decision === 'approve' ? 'success' : 'danger'" :loading="submitting" @click="submitReview">确认{{ decision === 'approve' ? '批准' : '拒绝' }}</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.review-page{width:min(1180px,calc(100% - 36px));margin:0 auto;padding:28px 0 44px;color:#4f382c}.review-hero{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:28px 30px;border:1px solid #eaded5;border-radius:28px;background:linear-gradient(135deg,#fffdfa 0%,#fff3ea 65%,#eff9f4 100%);box-shadow:0 18px 48px rgb(78 56 44/7%)}.eyebrow{margin:0 0 8px;color:#d76545;font-size:.75rem;font-weight:800;letter-spacing:.13em}.review-hero h1{margin:0;font-size:clamp(1.65rem,3vw,2.35rem)}.review-hero p:last-child{max-width:720px;margin:10px 0 0;color:#806d62;line-height:1.65}.review-summary{min-width:124px;padding:15px 18px;border-radius:20px;background:#fffdfa;text-align:center;box-shadow:inset 0 0 0 1px #f1d9cc}.review-summary strong{display:block;color:#e06f4e;font-size:2rem}.review-summary span{color:#8b7468;font-size:.82rem}.review-filters{margin-top:18px;border:1px solid #eaded5;border-radius:22px;background:#fffdfa}.review-filters :deep(.el-tabs__header){margin-bottom:18px}.review-filter-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr)) auto;gap:14px;align-items:end}.review-filter-grid :deep(.el-form-item){margin-bottom:0}.review-filter-actions{display:flex;padding-bottom:1px}.review-state{min-height:220px;margin-top:18px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:11px;border:1px dashed #dfd1c7;border-radius:22px;background:#fffdfa;color:#806d62}.review-state strong{color:#4f382c}.review-state--error{background:#fff7f3}.review-state-icon{font-size:2rem;color:#63a88b}.review-table{margin-top:18px;border-radius:20px;overflow:hidden;cursor:pointer}.content-cell{display:grid;gap:5px}.content-cell p{max-width:430px;max-height:3.1em;margin:0;overflow:hidden;color:#6f5c52;line-height:1.55}.content-cell small,.table-sub{display:block;color:#9a8478;font-size:.75rem}.review-table :deep(.el-tag)+.el-tag{margin-top:5px}.review-pagination{display:flex;align-items:center;justify-content:space-between;margin-top:18px;color:#806d62;font-size:.86rem}.review-drawer-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding-bottom:20px;border-bottom:1px solid #eaded5}.review-drawer-head h2{margin:0 0 10px}.review-drawer-head code{display:block;margin-top:4px;color:#8b7468;font-size:.72rem;overflow-wrap:anywhere}.review-status-panel{margin:20px 0;padding:17px;display:flex;align-items:center;gap:14px;border-radius:18px;background:#f5faf7}.review-status-panel div{display:grid;gap:4px}.review-status-panel span{color:#77857e;font-size:.78rem}.review-detail-section{padding:20px 0;border-bottom:1px solid #eee4dc}.review-detail-section h3{margin:0 0 13px}.review-copy{margin:0;padding:15px;border-radius:14px;background:#faf7f2;white-space:pre-wrap;line-height:1.7}.review-facts{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin:16px 0 0}.review-facts dt{color:#9a8478;font-size:.75rem}.review-facts dd{margin:4px 0 0;overflow-wrap:anywhere}.review-facts code{font-size:.72rem}.review-action-panel{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:20px;padding:18px;border-radius:18px;background:#fff3e8}.review-action-panel>div:first-child{display:grid;gap:5px}.review-action-panel span,.dialog-copy{color:#806d62;line-height:1.55}.review-action-buttons{display:flex;gap:9px}.review-drawer-state{min-height:300px}.reason-error{margin:7px 0 0;color:#d9534f;font-size:.85rem}
@media(max-width:900px){.review-filter-grid{grid-template-columns:1fr 1fr}.review-filter-actions{grid-column:1/-1}.review-table{font-size:.84rem}.review-table :deep(th:nth-child(2)),.review-table :deep(td:nth-child(2)){display:none}}
@media(max-width:720px){.review-page{width:calc(100% - 24px);padding-top:16px}.review-hero{align-items:flex-start;flex-direction:column;border-radius:22px}.review-summary{width:100%;box-sizing:border-box;display:flex;align-items:baseline;gap:8px;text-align:left}.review-summary strong{font-size:1.6rem}.review-pagination>span{display:none}.review-pagination{justify-content:center}.review-pagination :deep(.el-pagination__sizes){display:none}.review-facts{grid-template-columns:1fr}.review-action-panel{align-items:stretch;flex-direction:column}.review-action-buttons .el-button{flex:1}}
@media(max-width:430px){.review-filter-grid{display:block}.review-filter-grid :deep(.el-form-item){margin-bottom:14px}.review-filter-actions .el-button{flex:1}.review-drawer-head h2{font-size:1.35rem}}
</style>
