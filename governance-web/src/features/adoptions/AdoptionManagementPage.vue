<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { AdoptionGovernanceDataSource } from './dataSource'
import { useAdoptionCatalog } from './useAdoptionCatalog'
import {
  adoptionApplicationStatusLabels,
  adoptionListingStatusLabels,
  type AdoptionApplicationStatus,
  type AdoptionListingStatus,
  type GovernanceAdoptionApplication,
} from './types'

const props = defineProps<{ dataSource: AdoptionGovernanceDataSource }>()
const catalog = useAdoptionCatalog(props.dataSource)
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

onMounted(() => { void catalog.load() })
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

    <section class="stats-grid" aria-label="当前模拟数据状态统计">
      <article v-for="status in statusOrder" :key="status" :class="['stat-card', `stat-${status.toLowerCase()}`]">
        <span>{{ adoptionApplicationStatusLabels[status] }}</span>
        <strong>{{ catalog.statusCounts.value[status] }}</strong>
        <small>当前关键词范围 · 模拟数据</small>
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
        <el-table-column label="申请人" min-width="135"><template #default="{ row }: { row: GovernanceAdoptionApplication }"><span>用户 {{ compactId(row.applicantUserId) }}</span><small class="table-sub">仅展示脱敏标识</small></template></el-table-column>
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
          <dl class="card-facts"><div><dt>申请人</dt><dd>用户 {{ compactId(item.applicantUserId) }}</dd></div><div><dt>申请时间</dt><dd>{{ formatTime(item.createdAt) }}</dd></div><div><dt>审核时间</dt><dd>{{ formatTime(item.reviewedAt) }}</dd></div><div><dt>最近更新</dt><dd>{{ formatTime(item.updatedAt) }}</dd></div></dl>
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
          <dl class="detail-grid"><div><dt>申请人</dt><dd>用户 {{ compactId(catalog.detail.value.applicantUserId) }}</dd></div><div><dt>申请时间</dt><dd>{{ formatTime(catalog.detail.value.createdAt) }}</dd></div><div><dt>最近更新</dt><dd>{{ formatTime(catalog.detail.value.updatedAt) }}</dd></div><div><dt>审核时间</dt><dd>{{ formatTime(catalog.detail.value.reviewedAt) }}</dd></div><div><dt>审核人</dt><dd>{{ catalog.detail.value.reviewerUserId ? `用户 ${compactId(catalog.detail.value.reviewerUserId)}` : '尚未审核' }}</dd></div></dl>
          <div class="detail-copy"><span>申请说明</span><p>{{ catalog.detail.value.message || '申请人未填写说明。' }}</p></div>
          <div class="detail-copy"><span>审核意见</span><p>{{ catalog.detail.value.reviewComment || '暂无审核意见。' }}</p></div>
        </section>
        <section class="detail-section">
          <h3>关联 Listing</h3>
          <dl class="detail-grid"><div><dt>Listing ID</dt><dd><code>{{ catalog.detail.value.listingId }}</code></dd></div><div><dt>Animal ID</dt><dd><code>{{ catalog.detail.value.listing.animalId }}</code></dd></div><div><dt>Listing 状态</dt><dd>{{ adoptionListingStatusLabels[catalog.detail.value.listing.status] }} · {{ catalog.detail.value.listing.status }}</dd></div><div><dt>发布时间</dt><dd>{{ formatTime(catalog.detail.value.listing.publishedAt) }}</dd></div><div><dt>关闭时间</dt><dd>{{ formatTime(catalog.detail.value.listing.closedAt) }}</dd></div></dl>
          <div class="detail-copy"><span>Listing 说明</span><p>{{ catalog.detail.value.listing.description }}</p></div>
        </section>
        <section class="approved-note" v-if="catalog.detail.value.status === 'APPROVED'"><strong>批准不等于完成领养</strong><span>当前治理 DTO 不提供“已选定”字段。候选人选定、交接完成与 AdoptionRelation 激活需要后续独立流程确认。</span></section>
        <section class="pending-note"><strong>后续模块尚未接入</strong><span>本阶段仅提供只读申请预览；交接管理、领养关系与回访不在本页面伪造展示，也不提供批准、拒绝或选定按钮。</span></section>
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
