<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { AdoptionGovernanceDataSource } from './dataSource'
import type { CampusOption } from '@/features/animals/types'
import { useAdoptionCatalog } from './useAdoptionCatalog'
import {
  adoptionApplicationStatusLabels,
  adoptionListingStatusLabels,
  type AdoptionApplicationStatus,
  type AdoptionListingStatus,
  type GovernanceAdoptionApplication,
  type AdoptionListingSummary,
  type AdoptionAnimalOption,
} from './types'

const props = defineProps<{ dataSource: AdoptionGovernanceDataSource; campusOptions?: CampusOption[] }>()
const catalog = useAdoptionCatalog(props.dataSource)
const selection = ref<{ selectionId: string; applicationId: string; status: string } | null>(null)
const handover = ref<{ handoverId: string; status: string; scheduledAt: string; completedAt: string | null } | null>(null)
const workflowError = ref(''), workflowMessage = ref(''), busy = ref(false)
const reviewComment = ref(''), scheduledLocal = ref(''), cancelReason = ref('')
const listingItems = ref<AdoptionListingSummary[]>([]), listingStatus = ref(''), listingError = ref(''), listingBusy = ref(false)
const listingDetail = ref<AdoptionListingSummary | null>(null), listingPage = ref(0), listingTotal = ref(0)
const campusId = ref(''), animalKeyword = ref(''), animalOptions = ref<AdoptionAnimalOption[]>([]), selectedAnimalId = ref('')
const listingTitle = ref(''), listingDescription = ref('')
const loadListings = async () => { if (!props.dataSource.listings) return; try { const result=await props.dataSource.listings(listingStatus.value,listingPage.value,20); listingItems.value=result.items; listingTotal.value=result.total } catch { listingError.value='领养信息加载失败。' } }
const openListing = async (id: string) => { if (!props.dataSource.listing) return; try { listingDetail.value=await props.dataSource.listing(id); listingError.value='' } catch { listingError.value='领养信息详情暂不可用。' } }
const findAnimals = async () => { if (!props.dataSource.animals || !campusId.value) return; listingError.value=''; try { animalOptions.value=await props.dataSource.animals(campusId.value,animalKeyword.value) } catch { listingError.value='Animal 查询失败。' } }
const createListing = async () => { if (!props.dataSource.createListing || listingBusy.value) return; listingBusy.value=true; listingError.value=''; try { const created=await props.dataSource.createListing(selectedAnimalId.value,listingTitle.value,listingDescription.value); listingDetail.value=created; listingTitle.value=''; listingDescription.value=''; listingStatus.value=''; listingPage.value=0; await loadListings() } catch { await loadListings(); listingError.value='创建结果未确认，请核对刷新后的列表；请勿盲目重试。' } finally { listingBusy.value=false } }
const publishListing = async (id: string) => { if (!props.dataSource.publishListing || listingBusy.value) return; listingBusy.value=true; listingError.value=''; try { listingDetail.value=await props.dataSource.publishListing(id); await loadListings() } catch { await loadListings(); await openListing(id); listingError.value='发布结果未确认，请核对刷新后的状态。' } finally { listingBusy.value=false } }
let workflowGeneration = 0
const refreshWorkflow = async (applicationId: string) => {
  const generation = ++workflowGeneration
  const current = catalog.detail.value
  if (!current || current.applicationId !== applicationId || !props.dataSource.selection) return
  workflowError.value = ''; selection.value = null; handover.value = null
  try {
    const selected = await props.dataSource.selection(current.listingId)
    const currentHandover = selected && props.dataSource.handover ? await props.dataSource.handover(selected.selectionId) : null
    if (generation !== workflowGeneration) return
    selection.value = selected; handover.value = currentHandover
  } catch { if (generation === workflowGeneration) workflowError.value = '选定或交接状态暂不可用，请刷新后核对。' }
}
watch(() => catalog.detail.value?.applicationId, (id) => { workflowGeneration++; selection.value = null; handover.value = null; workflowMessage.value = ''; workflowError.value = ''; if (id) void refreshWorkflow(id) })
const act = async (operation: () => Promise<void>) => {
  const id = catalog.detail.value?.applicationId
  if (!id || busy.value) return
  busy.value = true; workflowError.value = ''; workflowMessage.value = ''
  let succeeded = false
  try { await operation(); succeeded = true }
  catch { /* The result may have committed; read it again before another action. */ }
  finally {
    if (catalog.detail.value?.applicationId === id) {
      await catalog.openDetail(id)
      await refreshWorkflow(id)
      if (catalog.detail.value?.applicationId === id) {
        if (succeeded) workflowMessage.value = '操作已提交，状态已从服务端刷新。'
        else workflowError.value = '操作结果未确认，请核对刷新后的状态；请勿盲目重试。'
      }
    }
    await catalog.load()
    busy.value = false
  }
}
const drawerSize = 'min(660px, 100vw)'
const totalPages = computed(() => Math.max(1, Math.ceil(catalog.total.value / catalog.criteria.size)))
const statusOrder: AdoptionApplicationStatus[] = ['SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN']
const formatTime = (value: string | null) => value
  ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : '—'
const compactId = (value: string | null) => value ? `${value.slice(0, 8)}…${value.slice(-4)}` : '—'
const applicationStatusType = (status: AdoptionApplicationStatus) => ({
  SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger', WITHDRAWN: 'info',
} as const)[status]
const listingStatusType = (status: AdoptionListingStatus) => ({
  DRAFT: 'info', PUBLISHED: 'primary', CLOSED: 'info',
} as const)[status]
const statusDescription = (status: AdoptionApplicationStatus) => ({
  SUBMITTED: '等待治理人员审核',
  APPROVED: '审核通过，尚不代表已选定或完成领养',
  REJECTED: '本次申请未通过审核',
  WITHDRAWN: '申请人已撤回本次申请',
})[status]

onMounted(() => { void catalog.load(); void loadListings() })
</script>

<template>
  <section class="adoption-page" aria-labelledby="adoption-title">
    <header class="hero-card">
      <div>
        <p class="eyebrow">ADOPTION APPLICATION GOVERNANCE</p>
        <h1 id="adoption-title">领养申请治理</h1>
        <p>查看申请与关联 Listing 的审核状态。申请批准、候选人选定、交接完成和领养关系激活是不同业务环节。</p>
      </div>
      <div class="result-summary" aria-live="polite"><strong>{{ catalog.total.value }}</strong><span>条匹配申请</span></div>
    </header>

    <el-card v-if="dataSource.listings" class="filter-card" shadow="never">
      <template #header><strong>领养信息创建与发布</strong></template>
      <el-alert v-if="listingError" type="warning" :title="listingError" :closable="false" />
      <el-form class="filter-grid" label-position="top" @submit.prevent="findAnimals">
        <el-form-item label="Campus"><el-select v-model="campusId" placeholder="选择 Campus" @change="selectedAnimalId='';animalOptions=[]"><el-option v-for="campus in campusOptions || []" :key="campus.id" :label="campus.label" :value="campus.id" /></el-select></el-form-item>
        <el-form-item label="Animal 关键词"><el-input v-model="animalKeyword" @keyup.enter="findAnimals" /></el-form-item>
        <div class="filter-actions"><el-button @click="findAnimals" :disabled="!campusId">查询 Animal</el-button></div>
        <el-form-item label="Animal"><el-select v-model="selectedAnimalId" filterable placeholder="选择已有 Animal"><el-option v-for="animal in animalOptions" :key="animal.id" :label="`${animal.displayName} · ${animal.species} · ${animal.id}`" :value="animal.id" /></el-select></el-form-item>
        <el-form-item label="标题"><el-input v-model="listingTitle" :maxlength="160" /></el-form-item>
        <el-form-item label="描述" class="keyword-field"><el-input v-model="listingDescription" type="textarea" :maxlength="4000" /></el-form-item>
        <div class="filter-actions"><el-button type="primary" :loading="listingBusy" :disabled="!selectedAnimalId || !listingTitle.trim() || !listingDescription.trim()" @click="createListing">创建草稿</el-button></div>
      </el-form>
      <div class="filter-actions"><el-select v-model="listingStatus" clearable placeholder="全部 Listing 状态" @change="listingPage=0;loadListings()"><el-option v-for="(label,value) in adoptionListingStatusLabels" :key="value" :label="label" :value="value" /></el-select><el-button @click="loadListings">刷新</el-button></div>
      <el-table :data="listingItems" empty-text="暂无领养信息">
        <el-table-column prop="title" label="标题" min-width="160" /><el-table-column prop="animalId" label="Animal ID" min-width="240" />
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="listingStatusType(row.status)">{{ adoptionListingStatusLabels[row.status as AdoptionListingStatus] }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="180"><template #default="{ row }"><el-button link @click="openListing(row.listingId)">查看</el-button><el-popconfirm v-if="row.status === 'DRAFT'" title="确认发布此领养信息？" @confirm="publishListing(row.listingId)"><template #reference><el-button link type="primary" :disabled="listingBusy">发布</el-button></template></el-popconfirm></template></el-table-column>
      </el-table>
      <el-pagination v-if="listingTotal > 20" :current-page="listingPage+1" :page-size="20" :total="listingTotal" layout="prev, pager, next" @current-change="(page: number) => { listingPage=page-1; loadListings() }" />
      <section v-if="listingDetail" class="detail-section"><h3>领养信息详情</h3><p>{{ listingDetail.title }} · {{ adoptionListingStatusLabels[listingDetail.status] }}</p><p>{{ listingDetail.description }}</p><p>Animal ID：{{ listingDetail.animalId }}</p><p>Listing ID：{{ listingDetail.listingId }}</p><el-popconfirm v-if="listingDetail.status === 'DRAFT'" title="确认发布此领养信息？" @confirm="publishListing(listingDetail.listingId)"><template #reference><el-button type="primary" :disabled="listingBusy">确认发布</el-button></template></el-popconfirm></section>
    </el-card>

    <section class="stats-grid" aria-label="当前查询范围状态统计">
      <article v-for="status in statusOrder" :key="status" :class="['stat-card', `stat-${status.toLowerCase()}`]">
        <span>{{ adoptionApplicationStatusLabels[status] }}</span>
        <strong>{{ catalog.statusCounts.value[status] }}</strong>
        <small>当前关键词范围 · 实时查询</small>
      </article>
    </section>

    <el-card class="filter-card" shadow="never">
      <el-form class="filter-grid" label-position="top" @submit.prevent="catalog.load(true)">
        <el-form-item label="关键词" class="keyword-field">
          <el-input v-model="catalog.criteria.keyword" clearable placeholder="申请 / Listing / Animal ID、标题或申请说明" @keyup.enter="catalog.load(true)" />
        </el-form-item>
        <el-form-item label="申请状态">
          <el-select v-model="catalog.criteria.status" clearable placeholder="全部状态" @change="catalog.load(true)">
            <el-option v-for="(label, value) in adoptionApplicationStatusLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="catalog.criteria.sort" @change="catalog.load(true)">
            <el-option label="最近更新" value="UPDATED_DESC" />
            <el-option label="最新申请" value="CREATED_DESC" />
            <el-option label="最早申请" value="CREATED_ASC" />
          </el-select>
        </el-form-item>
        <div class="filter-actions">
          <el-button type="primary" @click="catalog.load(true)">查询</el-button>
          <el-button @click="catalog.reset()">重置</el-button>
        </div>
      </el-form>
    </el-card>

    <div v-if="catalog.loading.value" class="state-card" v-loading="true"><strong>正在整理领养申请…</strong></div>
    <div v-else-if="catalog.errorMessage.value" class="state-card state-error">
      <span class="state-icon">↻</span><strong>领养申请加载失败</strong><span>{{ catalog.errorMessage.value }}</span>
      <el-button type="primary" @click="catalog.load()">重试</el-button>
    </div>
    <div v-else-if="!catalog.items.value.length" class="state-card">
      <span class="state-icon">🧺</span><strong>没有符合条件的领养申请</strong><span>调整关键词或状态筛选后再试。</span>
      <el-button @click="catalog.reset()">清除筛选</el-button>
    </div>

    <template v-else>
      <el-table class="adoption-table" :data="catalog.items.value" @row-click="(row: GovernanceAdoptionApplication) => catalog.openDetail(row.applicationId)">
        <el-table-column label="申请 / Listing" min-width="270">
          <template #default="{ row }: { row: GovernanceAdoptionApplication }">
            <div class="application-cell"><span class="application-icon">♡</span><div><strong>{{ row.listing.title }}</strong><small>申请 {{ compactId(row.applicationId) }} · Listing {{ compactId(row.listingId) }}</small></div></div>
          </template>
        </el-table-column>
        <el-table-column label="申请人" min-width="135"><template #default="{ row }: { row: GovernanceAdoptionApplication }"><span>{{ row.applicantReference }}</span><small class="table-sub">服务端脱敏引用</small></template></el-table-column>
        <el-table-column label="申请状态" width="125"><template #default="{ row }: { row: GovernanceAdoptionApplication }"><el-tag :type="applicationStatusType(row.status)">{{ adoptionApplicationStatusLabels[row.status] }}</el-tag><small class="table-sub">{{ row.status }}</small></template></el-table-column>
        <el-table-column label="Listing" width="115"><template #default="{ row }: { row: GovernanceAdoptionApplication }"><el-tag effect="plain" :type="listingStatusType(row.listing.status)">{{ adoptionListingStatusLabels[row.listing.status] }}</el-tag></template></el-table-column>
        <el-table-column label="申请 / 更新" min-width="175"><template #default="{ row }: { row: GovernanceAdoptionApplication }"><span>{{ formatTime(row.createdAt) }}</span><small class="table-sub">更新 {{ formatTime(row.updatedAt) }}</small></template></el-table-column>
        <el-table-column label="" width="82"><template #default="{ row }: { row: GovernanceAdoptionApplication }"><el-button link type="primary" @click.stop="catalog.openDetail(row.applicationId)">详情</el-button></template></el-table-column>
      </el-table>

      <div class="adoption-cards">
        <article v-for="item in catalog.items.value" :key="item.applicationId" class="adoption-card" @click="catalog.openDetail(item.applicationId)">
          <div class="card-heading"><span class="application-icon">♡</span><div><strong>{{ item.listing.title }}</strong><small>Animal {{ compactId(item.listing.animalId) }}</small></div><el-button link type="primary">详情</el-button></div>
          <p>{{ item.message }}</p>
          <div class="tag-row"><el-tag :type="applicationStatusType(item.status)">{{ adoptionApplicationStatusLabels[item.status] }}</el-tag><el-tag effect="plain" :type="listingStatusType(item.listing.status)">{{ adoptionListingStatusLabels[item.listing.status] }}</el-tag></div>
          <dl class="card-facts"><div><dt>申请人</dt><dd>{{ item.applicantReference }}</dd></div><div><dt>申请时间</dt><dd>{{ formatTime(item.createdAt) }}</dd></div><div><dt>审核时间</dt><dd>{{ formatTime(item.reviewedAt) }}</dd></div><div><dt>最近更新</dt><dd>{{ formatTime(item.updatedAt) }}</dd></div></dl>
        </article>
      </div>

      <div class="pagination-bar">
        <span>共 {{ catalog.total.value }} 条，第 {{ catalog.criteria.page }} / {{ totalPages }} 页</span>
        <el-pagination background layout="sizes, prev, pager, next" :total="catalog.total.value" :page-size="catalog.criteria.size" :current-page="catalog.criteria.page" :page-sizes="[6, 9, 12]" @current-change="catalog.changePage" @size-change="catalog.changePageSize" />
        <div class="mobile-pagination"><el-button :disabled="catalog.criteria.page <= 1" @click="catalog.changePage(catalog.criteria.page - 1)">上一页</el-button><span>{{ catalog.criteria.page }} / {{ totalPages }}</span><el-button :disabled="catalog.criteria.page >= totalPages" @click="catalog.changePage(catalog.criteria.page + 1)">下一页</el-button></div>
      </div>
    </template>

    <el-drawer v-model="catalog.detailVisible.value" :size="drawerSize" :with-header="false" destroy-on-close @closed="catalog.closeDetail">
      <div v-if="catalog.detailLoading.value" class="state-card drawer-state" v-loading="true"><strong>正在加载申请详情…</strong></div>
      <div v-else-if="catalog.detailError.value" class="state-card state-error drawer-state"><strong>详情加载失败</strong><span>{{ catalog.detailError.value }}</span><el-button type="primary" @click="catalog.retryDetail()">重试</el-button></div>
      <template v-else-if="catalog.detail.value">
        <header class="drawer-head"><div><p class="eyebrow">APPLICATION DETAIL</p><h2>{{ catalog.detail.value.listing.title }}</h2><code>Application {{ catalog.detail.value.applicationId }}</code></div><el-button circle aria-label="关闭详情" @click="catalog.detailVisible.value = false">×</el-button></header>
        <section class="status-panel">
          <el-tag size="large" :type="applicationStatusType(catalog.detail.value.status)">{{ adoptionApplicationStatusLabels[catalog.detail.value.status] }}</el-tag>
          <div><strong>{{ statusDescription(catalog.detail.value.status) }}</strong><span>{{ catalog.detail.value.status }}</span></div>
        </section>
        <section class="detail-section">
          <h3>申请信息</h3>
          <dl class="detail-grid"><div><dt>申请人</dt><dd>{{ catalog.detail.value.applicantReference }}</dd></div><div><dt>申请时间</dt><dd>{{ formatTime(catalog.detail.value.createdAt) }}</dd></div><div><dt>最近更新</dt><dd>{{ formatTime(catalog.detail.value.updatedAt) }}</dd></div><div><dt>审核时间</dt><dd>{{ formatTime(catalog.detail.value.reviewedAt) }}</dd></div><div><dt>审核人</dt><dd>{{ catalog.detail.value.reviewerReference || '尚未审核' }}</dd></div></dl>
          <div class="detail-copy"><span>申请说明</span><p>{{ catalog.detail.value.message || '申请人未填写说明。' }}</p></div>
          <div class="detail-copy"><span>审核意见</span><p>{{ catalog.detail.value.reviewComment || '暂无审核意见。' }}</p></div>
        </section>
        <section class="detail-section">
          <h3>关联 Listing</h3>
          <dl class="detail-grid"><div><dt>Listing ID</dt><dd><code>{{ catalog.detail.value.listingId }}</code></dd></div><div><dt>Animal ID</dt><dd><code>{{ catalog.detail.value.listing.animalId }}</code></dd></div><div><dt>Listing 状态</dt><dd>{{ adoptionListingStatusLabels[catalog.detail.value.listing.status] }} · {{ catalog.detail.value.listing.status }}</dd></div><div><dt>发布时间</dt><dd>{{ formatTime(catalog.detail.value.listing.publishedAt) }}</dd></div><div><dt>关闭时间</dt><dd>{{ formatTime(catalog.detail.value.listing.closedAt) }}</dd></div></dl>
          <div class="detail-copy"><span>Listing 说明</span><p>{{ catalog.detail.value.listing.description }}</p></div>
        </section>
        <section v-if="dataSource.review" class="detail-section">
          <h3>管理员处理</h3>
          <el-alert v-if="workflowError" type="warning" :title="workflowError" :closable="false" />
          <el-alert v-if="workflowMessage" type="success" :title="workflowMessage" :closable="false" />
          <template v-if="catalog.detail.value.status === 'SUBMITTED'">
            <el-input v-model="reviewComment" type="textarea" :maxlength="500" placeholder="审核意见；拒绝时必须填写原因" />
            <el-popconfirm title="确认批准此申请？" @confirm="act(() => dataSource.review!(catalog.detail.value!.applicationId, 'approve', reviewComment))"><template #reference><el-button type="primary" :disabled="busy">批准</el-button></template></el-popconfirm>
            <el-popconfirm title="确认拒绝此申请？" @confirm="act(() => dataSource.review!(catalog.detail.value!.applicationId, 'reject', reviewComment))"><template #reference><el-button type="danger" :disabled="busy || !reviewComment.trim()">拒绝</el-button></template></el-popconfirm>
          </template>
          <template v-if="catalog.detail.value.status === 'APPROVED'">
            <p v-if="selection">最终候选：{{ selection.applicationId === catalog.detail.value.applicationId ? '本申请' : '同 Listing 的其他申请' }}；选定状态：{{ selection.status }}</p>
            <p v-else-if="!workflowError">尚无最终候选人；审核通过不代表已选定。</p>
            <el-popconfirm v-if="!selection && catalog.detail.value.listing.status === 'PUBLISHED' && dataSource.select" title="确认将本申请选为最终候选人？" @confirm="act(() => dataSource.select!(catalog.detail.value!.listingId, catalog.detail.value!.applicationId))"><template #reference><el-button :disabled="busy || !!workflowError">选定本申请</el-button></template></el-popconfirm>
          </template>
          <template v-if="selection && selection.applicationId === catalog.detail.value.applicationId">
            <p v-if="handover">交接状态：{{ handover.status }}；计划时间：{{ formatTime(handover.scheduledAt) }}；完成时间：{{ formatTime(handover.completedAt) }}</p>
            <template v-else-if="selection.status === 'ACTIVE' && dataSource.initiate && !workflowError"><el-input v-model="scheduledLocal" type="datetime-local" /><el-popconfirm title="确认发起交接？" @confirm="act(() => dataSource.initiate!(selection!.selectionId, new Date(scheduledLocal).toISOString()))"><template #reference><el-button :disabled="busy || !scheduledLocal">发起交接</el-button></template></el-popconfirm></template>
            <template v-if="handover?.status === 'PENDING'"><el-popconfirm title="确认交接已经实际完成？" @confirm="act(() => dataSource.complete!(handover!.handoverId))"><template #reference><el-button type="success" :disabled="busy">确认完成交接</el-button></template></el-popconfirm><el-input v-model="cancelReason" :maxlength="500" placeholder="取消原因" /><el-popconfirm title="确认取消待交接记录？" @confirm="act(() => dataSource.cancel!(handover!.handoverId, cancelReason))"><template #reference><el-button type="danger" :disabled="busy || !cancelReason.trim()">取消交接</el-button></template></el-popconfirm></template>
            <p v-if="handover?.status === 'COMPLETED'">交接已完成，领养关系由服务端事务创建。</p>
            <p v-if="handover?.status === 'CANCELLED'">交接已取消。</p>
          </template>
        </section>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.adoption-page{width:min(1180px,calc(100% - 36px));margin:0 auto;padding:28px 0 44px;color:#4f382c}.hero-card{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:28px 30px;border:1px solid #eaded5;border-radius:28px;background:linear-gradient(135deg,#fffdfa 0%,#fff3ea 65%,#eff9f4 100%);box-shadow:0 18px 48px rgb(78 56 44/7%)}.eyebrow{margin:0 0 8px;color:#d76545;font-size:.75rem;font-weight:800;letter-spacing:.13em}.hero-card h1{margin:0;font-size:clamp(1.65rem,3vw,2.35rem)}.hero-card p:last-child{max-width:720px;margin:10px 0 0;color:#806d62;line-height:1.65}.result-summary{min-width:124px;padding:15px 18px;border-radius:20px;background:#fffdfa;text-align:center;box-shadow:inset 0 0 0 1px #f1d9cc}.result-summary strong{display:block;color:#e06f4e;font-size:2rem}.result-summary span{color:#8b7468;font-size:.82rem}.stats-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:18px 0}.stat-card{padding:17px 19px;border:1px solid #eaded5;border-radius:19px;background:#fffdfa;box-shadow:0 8px 22px rgb(78 56 44/5%)}.stat-card span,.stat-card small{display:block;color:#89746a}.stat-card strong{display:block;margin:5px 0;color:#4f382c;font-size:1.65rem}.stat-submitted{border-top:3px solid #e6a23c}.stat-approved{border-top:3px solid #67c23a}.stat-rejected{border-top:3px solid #f56c6c}.stat-withdrawn{border-top:3px solid #909399}.filter-card{border:1px solid #eaded5;border-radius:22px;background:#fffdfa}.filter-grid{display:grid;grid-template-columns:minmax(280px,2fr) minmax(150px,1fr) minmax(150px,1fr) auto;gap:14px;align-items:end}.filter-grid :deep(.el-form-item){margin-bottom:0}.filter-actions{display:flex;padding-bottom:1px}.state-card{min-height:220px;margin-top:18px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:11px;border:1px dashed #dfd1c7;border-radius:22px;background:#fffdfa;color:#806d62}.state-card strong{color:#4f382c}.state-icon{font-size:2rem}.state-error{background:#fff7f3}.adoption-table{margin-top:18px;border-radius:20px;overflow:hidden;cursor:pointer}.application-cell,.card-heading{display:flex;align-items:center;gap:11px}.application-cell div,.card-heading div{display:grid;gap:4px}.application-cell small,.card-heading small,.table-sub{display:block;color:#9a8478;font-size:.75rem}.application-icon{width:38px;height:38px;display:grid;place-items:center;flex:none;border-radius:13px;background:#fff0e9;color:#e06f4e;font-size:1.2rem}.adoption-cards{display:none}.pagination-bar{margin-top:18px;display:flex;align-items:center;justify-content:space-between;color:#806d62;font-size:.86rem}.mobile-pagination{display:none}.drawer-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding-bottom:20px;border-bottom:1px solid #eaded5}.drawer-head h2{margin:0 0 10px}.drawer-head code{display:block;margin-top:4px;color:#8b7468;font-size:.72rem;overflow-wrap:anywhere}.status-panel{margin:20px 0;padding:17px;display:flex;align-items:center;gap:14px;border-radius:18px;background:#f5faf7}.status-panel div{display:grid;gap:4px}.status-panel span{color:#77857e;font-size:.78rem}.detail-section{padding:20px 0;border-bottom:1px solid #eee4dc}.detail-section h3{margin:0 0 15px}.detail-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin:0}.detail-grid div{min-width:0}.detail-grid dt{color:#9a8478;font-size:.75rem}.detail-grid dd{margin:4px 0 0;line-height:1.45;overflow-wrap:anywhere}.detail-grid code{font-size:.72rem}.detail-copy{margin-top:15px;padding:14px;border-radius:14px;background:#faf7f2}.detail-copy span{color:#9a8478;font-size:.75rem}.detail-copy p{margin:6px 0 0;line-height:1.6}.approved-note,.pending-note{margin-top:18px;padding:17px;display:grid;gap:7px;border-radius:18px}.approved-note{background:#eef8f2}.pending-note{background:#fff3e8}.approved-note span,.pending-note span{color:#718078;line-height:1.55}.drawer-state{min-height:300px}.adoption-card{cursor:pointer}.adoption-card>p{margin:15px 0;color:#6f5c52;line-height:1.55}.card-heading .el-button{margin-left:auto}.tag-row{display:flex;gap:8px;margin:12px 0}.card-facts{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:0}.card-facts dt{color:#9a8478;font-size:.72rem}.card-facts dd{margin:3px 0 0}.stat-card small{font-size:.7rem}
@media(max-width:900px){.adoption-table{display:none}.adoption-cards{margin-top:18px;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:13px}.adoption-card{padding:18px;border:1px solid #eaded5;border-radius:20px;background:#fffdfa;box-shadow:0 10px 30px rgb(78 56 44/6%)}.filter-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.keyword-field{grid-column:1/-1}.stats-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:720px){.adoption-page{width:calc(100% - 24px);padding-top:16px}.hero-card{align-items:flex-start;flex-direction:column;border-radius:22px}.result-summary{width:100%;box-sizing:border-box;display:flex;align-items:baseline;gap:8px;text-align:left}.result-summary strong{font-size:1.6rem}.adoption-cards{grid-template-columns:1fr}.pagination-bar>span,.pagination-bar>.el-pagination{display:none}.mobile-pagination{width:100%;display:flex;align-items:center;justify-content:space-between}.detail-grid{grid-template-columns:1fr}}
@media(max-width:430px){.filter-grid{display:block}.filter-grid :deep(.el-form-item){margin-bottom:14px}.filter-actions .el-button{flex:1}.stats-grid{gap:8px}.stat-card{padding:13px}.stat-card strong{font-size:1.4rem}.card-facts{grid-template-columns:1fr}.drawer-head h2{font-size:1.35rem}}
</style>
