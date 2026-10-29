<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { CampusOption } from '@/features/animals/types'
import type { RescueGovernanceDataSource } from './dataSource'
import { useRescueCatalog } from './useRescueCatalog'
import {
  caseStatusLabels, claimStatusLabels, eventStatusLabels, rescueStatusLabels, resultCodeLabels,
  type RescueListItem, type RescueStatus,
} from './types'

const props = defineProps<{ dataSource: RescueGovernanceDataSource; campusOptions: CampusOption[]; initialCampusId?: string }>()
const catalog = useRescueCatalog(props.dataSource, props.initialCampusId)
const drawerSize = 'min(660px, 100vw)'
const totalPages = computed(() => Math.max(1, Math.ceil(catalog.total.value / catalog.criteria.size)))
const formatTime = (value: string | null) => value
  ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : '—'
const campusLabel = (id: string) => props.campusOptions.find((campus) => campus.id === id)?.label ?? '未知校区'
const compactId = (value: string | null) => value ? `${value.slice(0, 8)}…${value.slice(-4)}` : '—'
const statusType = (status: RescueStatus) => {
  if (status === 'REPORTED' || status === 'WAITING_CLAIM') return 'warning'
  if (status === 'IN_PROGRESS') return 'primary'
  if (status === 'RESOLVED') return 'success'
  if (status === 'REJECTED' || status === 'CLOSED_UNRESOLVED') return 'danger'
  return 'info'
}
const animalName = (item: RescueListItem) => item.animal?.displayName ?? (item.animalId ? `关联 Animal ${compactId(item.animalId)}` : '未关联 Animal')
const terminalLabel = (item: RescueListItem) => {
  if (item.caseStatus === 'CANCELLED' || item.resultCode === 'CANCELLED') return '已取消'
  if (item.caseStatus === 'CLOSED_UNRESOLVED' || item.resultCode === 'UNRESOLVED') return '已关闭 · 未解决'
  if (item.caseStatus === 'RESOLVED' || item.resultCode === 'RESOLVED') return '已解决'
  if (item.caseStatus === 'CLOSED') return '已关闭'
  return item.caseStatus ? caseStatusLabels[item.caseStatus] : '尚未建案'
}
const summaryStep = (status: RescueStatus) => status === 'WAITING_CLAIM' ? 0 : status === 'IN_PROGRESS' ? 1
  : ['RESOLVED', 'CLOSED', 'CLOSED_UNRESOLVED', 'CANCELLED'].includes(status) ? 2 : -1
onMounted(() => { if (catalog.criteria.campusId) void catalog.load() })
</script>

<template>
  <section class="rescue-page" aria-labelledby="rescue-title">
    <header class="hero-card">
      <div>
        <p class="eyebrow">EVENT / CASE GOVERNANCE</p>
        <h1 id="rescue-title">救助事件与 Case</h1>
        <p>集中查看已核实事件、Case 处理进度与结案结果。页面进度为汇总展示，原始状态始终单独保留。</p>
      </div>
      <div class="result-summary" aria-live="polite"><strong>{{ catalog.total.value }}</strong><span>条匹配事件</span></div>
    </header>

    <el-card class="filter-card" shadow="never">
      <el-form class="filter-grid" label-position="top" @submit.prevent="catalog.load(true)">
        <el-form-item label="关键词" class="keyword-field">
          <el-input v-model="catalog.criteria.keyword" clearable placeholder="摘要、Event / Case ID、Animal 或公开位置" @keyup.enter="catalog.load(true)" />
        </el-form-item>
        <el-form-item label="Campus">
          <el-select v-model="catalog.criteria.campusId" clearable placeholder="全部校区" @change="catalog.load(true)">
            <el-option v-for="campus in campusOptions" :key="campus.id" :label="campus.label" :value="campus.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Event / Case 状态">
          <el-select v-model="catalog.criteria.rescueStatus" clearable placeholder="全部进度" @change="catalog.load(true)">
            <el-option v-for="(label, value) in rescueStatusLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="catalog.criteria.sort" @change="catalog.load(true)">
            <el-option label="最近更新" value="UPDATED_DESC" />
            <el-option label="最近上报" value="REPORTED_DESC" />
            <el-option label="最近发生" value="OCCURRED_DESC" />
          </el-select>
        </el-form-item>
        <div class="filter-actions">
          <el-button type="primary" @click="catalog.load(true)">查询</el-button>
          <el-button @click="catalog.reset()">重置</el-button>
        </div>
      </el-form>
    </el-card>

    <div v-if="catalog.loading.value" class="state-card" v-loading="true"><strong>正在整理救助记录…</strong></div>
    <div v-else-if="catalog.errorMessage.value" class="state-card state-error">
      <span class="state-icon">↻</span><strong>救助事件加载失败</strong><span>{{ catalog.errorMessage.value }}</span>
      <el-button type="primary" @click="catalog.load()">重试</el-button>
    </div>
    <div v-else-if="!catalog.items.value.length" class="state-card">
      <span class="state-icon">🕊️</span><strong>没有符合条件的救助事件</strong><span>调整关键词或筛选条件后再试。</span>
      <el-button @click="catalog.reset()">清除筛选</el-button>
    </div>

    <template v-else>
      <el-table class="rescue-table" :data="catalog.items.value" @row-click="(row: RescueListItem) => catalog.openDetail(row.eventId)">
        <el-table-column label="事件" min-width="260">
          <template #default="{ row }: { row: RescueListItem }">
            <div class="event-cell"><span class="event-icon">✚</span><div><strong>{{ row.abnormalitySummary || '救助事件' }}</strong><small>{{ compactId(row.eventId) }} · {{ animalName(row) }}</small></div></div>
          </template>
        </el-table-column>
        <el-table-column label="Campus / 公开位置" min-width="170">
          <template #default="{ row }: { row: RescueListItem }"><strong>{{ campusLabel(row.campusId) }}</strong><small class="table-sub">{{ row.publicLocationDescription || '未提供公开位置' }}</small></template>
        </el-table-column>
        <el-table-column label="救助进度" width="125">
          <template #default="{ row }: { row: RescueListItem }"><el-tag :type="statusType(row.rescueStatus)">{{ rescueStatusLabels[row.rescueStatus] }}</el-tag><small class="table-sub">{{ terminalLabel(row) }}</small></template>
        </el-table-column>
        <el-table-column label="认领" width="100"><template #default="{ row }: { row: RescueListItem }">{{ row.hasOwner ? '已认领' : '未认领' }}</template></el-table-column>
        <el-table-column label="上报 / 更新" min-width="165">
          <template #default="{ row }: { row: RescueListItem }"><span>{{ formatTime(row.reportedAt) }}</span><small class="table-sub">更新 {{ formatTime(row.updatedAt) }}</small></template>
        </el-table-column>
        <el-table-column label="" width="86"><template #default="{ row }: { row: RescueListItem }"><el-button link type="primary" @click.stop="catalog.openDetail(row.eventId)">详情</el-button></template></el-table-column>
      </el-table>

      <div class="rescue-cards">
        <article v-for="item in catalog.items.value" :key="item.eventId" class="rescue-card" @click="catalog.openDetail(item.eventId)">
          <div class="card-heading"><span class="event-icon">✚</span><div><strong>{{ item.abnormalitySummary || '救助事件' }}</strong><small>{{ animalName(item) }}</small></div><el-button link type="primary">详情</el-button></div>
          <p>{{ item.description }}</p>
          <div class="tag-row"><el-tag :type="statusType(item.rescueStatus)">{{ rescueStatusLabels[item.rescueStatus] }}</el-tag><span>{{ terminalLabel(item) }}</span></div>
          <dl class="card-facts"><div><dt>Campus</dt><dd>{{ campusLabel(item.campusId) }}</dd></div><div><dt>公开位置</dt><dd>{{ item.publicLocationDescription || '未提供' }}</dd></div><div><dt>认领</dt><dd>{{ item.hasOwner ? '已认领' : '未认领' }}</dd></div><div><dt>更新</dt><dd>{{ formatTime(item.updatedAt) }}</dd></div></dl>
        </article>
      </div>

      <div class="pagination-bar">
        <span>共 {{ catalog.total.value }} 条，第 {{ catalog.criteria.page }} / {{ totalPages }} 页</span>
        <el-pagination background layout="sizes, prev, pager, next" :total="catalog.total.value" :page-size="catalog.criteria.size" :current-page="catalog.criteria.page" :page-sizes="[6, 9, 12]" @current-change="catalog.changePage" @size-change="catalog.changePageSize" />
        <div class="mobile-pagination"><el-button :disabled="catalog.criteria.page <= 1" @click="catalog.changePage(catalog.criteria.page - 1)">上一页</el-button><span>{{ catalog.criteria.page }} / {{ totalPages }}</span><el-button :disabled="catalog.criteria.page >= totalPages" @click="catalog.changePage(catalog.criteria.page + 1)">下一页</el-button></div>
      </div>
    </template>

    <el-drawer v-model="catalog.detailVisible.value" :size="drawerSize" :with-header="false" destroy-on-close @closed="catalog.closeDetail">
      <div v-if="catalog.detailLoading.value" class="state-card drawer-state" v-loading="true"><strong>正在加载救助详情…</strong></div>
      <div v-else-if="catalog.detailError.value" class="state-card state-error drawer-state"><strong>详情加载失败</strong><span>{{ catalog.detailError.value }}</span><el-button type="primary" @click="catalog.retryDetail()">重试</el-button></div>
      <template v-else-if="catalog.detail.value">
        <header class="drawer-head"><div><p class="eyebrow">RESCUE DETAIL</p><h2>{{ catalog.detail.value.abnormalitySummary || '救助事件' }}</h2><code>Event {{ catalog.detail.value.eventId }}</code><code>Case {{ catalog.detail.value.caseId || '尚未建立' }}</code></div><el-button circle aria-label="关闭详情" @click="catalog.detailVisible.value = false">×</el-button></header>
        <section v-if="summaryStep(catalog.detail.value.rescueStatus) >= 0" class="progress-panel">
          <div class="summary-progress">
            <template v-for="(label, index) in ['等待认领', '处理中', '已完成']" :key="label"><span :class="{ active: index <= summaryStep(catalog.detail.value.rescueStatus) }">{{ index + 1 }}</span><strong :class="{ active: index <= summaryStep(catalog.detail.value.rescueStatus) }">{{ label }}</strong><i v-if="index < 2" :class="{ active: index < summaryStep(catalog.detail.value.rescueStatus) }" /></template>
          </div>
          <small>此进度由 Event / Case 状态汇总生成，不替代底层状态机。</small>
        </section>
        <section v-else class="status-panel"><el-tag :type="statusType(catalog.detail.value.rescueStatus)">{{ rescueStatusLabels[catalog.detail.value.rescueStatus] }}</el-tag><span>该 Event 尚未进入可认领或处理中的 Case 流程，页面保留原始 Event 状态。</span></section>
        <section class="detail-section">
          <h3>事件信息</h3>
          <dl class="detail-grid"><div><dt>Campus</dt><dd>{{ campusLabel(catalog.detail.value.campusId) }}</dd></div><div><dt>关联 Animal</dt><dd>{{ animalName(catalog.detail.value) }}</dd></div><div><dt>Event 原始状态</dt><dd>{{ eventStatusLabels[catalog.detail.value.eventStatus] }} · {{ catalog.detail.value.eventStatus }}</dd></div><div><dt>发生时间</dt><dd>{{ formatTime(catalog.detail.value.occurredAt) }}</dd></div><div><dt>上报时间</dt><dd>{{ formatTime(catalog.detail.value.reportedAt) }}</dd></div><div><dt>公开位置</dt><dd>{{ catalog.detail.value.publicLocationDescription || '未提供' }}</dd></div></dl>
          <div class="detail-copy"><span>事件描述</span><p>{{ catalog.detail.value.description }}</p></div>
        </section>
        <section class="detail-section">
          <h3>Case 与结果</h3>
          <div v-if="!catalog.detail.value.caseId" class="inline-empty"><strong>尚未建立 Case</strong><span>该已核实事件仍处于等待建案或认领阶段。</span></div>
          <template v-else>
            <dl class="detail-grid"><div><dt>Case 原始状态</dt><dd>{{ catalog.detail.value.caseStatus ? caseStatusLabels[catalog.detail.value.caseStatus] : '—' }} · {{ catalog.detail.value.caseStatus }}</dd></div><div><dt>认领状态</dt><dd>{{ catalog.detail.value.claimStatus ? claimStatusLabels[catalog.detail.value.claimStatus] : '—' }} · {{ catalog.detail.value.claimStatus }}</dd></div><div><dt>Evidence 数量</dt><dd>{{ catalog.detail.value.evidenceCount }}</dd></div><div><dt>Case Action 数量</dt><dd>{{ catalog.detail.value.actionCount }}</dd></div><div><dt>认领时间</dt><dd>{{ formatTime(catalog.detail.value.claimedAt) }}</dd></div><div><dt>关闭时间</dt><dd>{{ formatTime(catalog.detail.value.closedAt) }}</dd></div></dl>
            <div class="result-box" :class="{ unresolved: catalog.detail.value.resultCode === 'UNRESOLVED' || catalog.detail.value.resultCode === 'CANCELLED' }"><strong>{{ catalog.detail.value.resultCode ? resultCodeLabels[catalog.detail.value.resultCode] : '暂无处理结果' }}</strong><span>{{ catalog.detail.value.resultSummary || '尚未提交结果总结。' }}</span></div>
          </template>
        </section>
        <section class="detail-section">
          <h3>进度记录</h3>
          <el-timeline v-if="catalog.detail.value.progress.length"><el-timeline-item v-for="entry in catalog.detail.value.progress" :key="entry.id" :timestamp="formatTime(entry.occurredAt)" color="#ef7d5b"><strong>{{ entry.resultCode ? resultCodeLabels[entry.resultCode] : '处理记录' }}</strong><p>{{ entry.description }}</p></el-timeline-item></el-timeline>
          <div v-else class="inline-empty"><strong>暂无进度记录</strong><span>当前 Case 尚未提交 Case Action。</span></div>
        </section>
        <section class="pending-note"><strong>媒体与治理操作尚未接入</strong><span>本阶段仅展示公开位置和只读契约，不提供审核、认领、处理或结案按钮。</span></section>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.rescue-page{width:min(1180px,calc(100% - 36px));margin:0 auto;padding:28px 0 48px;color:#49372e}.hero-card{display:flex;justify-content:space-between;align-items:center;gap:28px;padding:clamp(24px,4vw,40px);border:1px solid rgb(108 79 64/10%);border-radius:28px;background:linear-gradient(130deg,#fffaf4,#fff 58%,#eff9f5);box-shadow:0 20px 55px rgb(78 56 44/8%)}.eyebrow{margin:0 0 7px;color:#ca6243;font-size:.72rem;font-weight:800;letter-spacing:.18em}.hero-card h1,.drawer-head h2{margin:0;color:#402f27}.hero-card h1{font-size:clamp(1.8rem,4vw,2.8rem)}.hero-card p:last-child{max-width:660px;margin:12px 0 0;color:#806d62;line-height:1.7}.result-summary{flex:0 0 auto;min-width:126px;padding:18px;display:grid;text-align:center;border-radius:22px;color:#8b3e27;background:#fff0e9}.result-summary strong{font-size:2rem}.result-summary span{margin-top:7px;font-size:.82rem}.filter-card{margin-top:20px;border:0;border-radius:24px;box-shadow:0 15px 44px rgb(78 56 44/7%)}.filter-grid{display:grid;grid-template-columns:2fr repeat(3,minmax(140px,1fr));gap:4px 14px}.filter-grid :deep(.el-form-item){margin-bottom:10px}.filter-grid :deep(.el-select){width:100%}.filter-grid :deep(.el-input__wrapper),.filter-grid :deep(.el-select__wrapper){min-height:42px;border-radius:13px}.filter-actions{display:flex;align-items:end;gap:8px;padding-bottom:10px}.filter-actions .el-button{min-height:42px;border-radius:13px}.state-card{min-height:280px;margin-top:20px;padding:32px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;text-align:center;border:1px dashed #decfc4;border-radius:24px;background:#fffdfa;color:#806d62}.state-error{border-color:#f0b6a3;background:#fff6f2}.state-icon{font-size:2.4rem}.rescue-table{margin-top:20px;border-radius:22px;overflow:hidden;--el-table-header-bg-color:#f8f2eb;--el-table-row-hover-bg-color:#fff7f1}.event-cell,.card-heading{display:flex;align-items:center;gap:11px}.event-cell div,.card-heading div{min-width:0;display:grid;gap:4px}.event-cell small,.card-heading small,.table-sub{color:#927f74}.event-icon{width:42px;height:42px;flex:0 0 auto;display:grid;place-items:center;border-radius:14px;background:linear-gradient(145deg,#ffe6da,#edf8f3);color:#d66647;font-size:1.2rem}.table-sub{display:block;margin-top:4px}.rescue-cards{display:none}.pagination-bar{margin-top:20px;display:flex;justify-content:space-between;align-items:center;color:#8a756a}.mobile-pagination{display:none}.drawer-head{display:flex;justify-content:space-between;gap:20px;padding-bottom:20px;border-bottom:1px solid #eee3da}.drawer-head code{display:block;margin-top:8px;color:#8d776a;font-size:.75rem;overflow-wrap:anywhere}.progress-panel{margin:22px 0;padding:20px;border-radius:20px;background:#f8f4ee}.summary-progress{display:grid;grid-template-columns:auto auto 1fr auto auto 1fr auto auto;align-items:center;gap:8px}.summary-progress span{width:27px;height:27px;display:grid;place-items:center;border-radius:50%;background:#ded6d0;color:#fff;font-size:.78rem}.summary-progress strong{color:#9a8c84;font-size:.82rem;white-space:nowrap}.summary-progress i{height:2px;background:#ddd3cc}.summary-progress .active{color:#a04a32}.summary-progress span.active,.summary-progress i.active{background:#ef7d5b;color:#fff}.progress-panel small{display:block;margin-top:14px;color:#8a786f}.detail-section{margin-top:20px;padding:21px;border:1px solid #eee1d7;border-radius:20px;background:#fffdfa}.detail-section h3{margin:0 0 16px}.detail-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:17px;margin:0}.detail-grid dt,.detail-copy span{color:#9a8478;font-size:.76rem}.detail-grid dd{margin:5px 0 0;overflow-wrap:anywhere}.detail-copy{margin-top:17px}.detail-copy p,.el-timeline p{margin:6px 0 0;line-height:1.65}.result-box,.inline-empty{margin-top:18px;padding:16px;display:grid;gap:6px;border-radius:16px;background:#eef8f4}.result-box span,.inline-empty span{color:#718078;line-height:1.55}.result-box.unresolved{background:#fff2ec}.inline-empty{margin:0;text-align:center}.pending-note{margin-top:20px;padding:18px;display:grid;gap:7px;border-radius:18px;background:#fff3e8}.pending-note span{color:#806d62}.drawer-state{min-height:300px}.rescue-card{cursor:pointer}.card-facts{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:0}.card-facts dt{color:#9a8478;font-size:.72rem}.card-facts dd{margin:3px 0 0}.tag-row{display:flex;align-items:center;gap:8px;margin:12px 0}.tag-row span{color:#77655b;font-size:.82rem}
@media(max-width:900px){.rescue-table{display:none}.rescue-cards{margin-top:18px;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:13px}.rescue-card{padding:18px;border:1px solid #eaded5;border-radius:20px;background:#fffdfa;box-shadow:0 10px 30px rgb(78 56 44/6%)}.card-heading .el-button{margin-left:auto}.rescue-card>p{margin:15px 0;line-height:1.55}.filter-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.keyword-field{grid-column:1/-1}}
@media(max-width:720px){.rescue-page{width:calc(100% - 24px);padding-top:16px}.hero-card{align-items:flex-start;flex-direction:column;border-radius:22px}.result-summary{width:100%;grid-template-columns:auto 1fr;align-items:baseline;text-align:left}.result-summary span{margin:0 0 0 8px}.filter-actions{grid-column:1/-1}.filter-actions .el-button{flex:1}.rescue-cards{grid-template-columns:1fr}.pagination-bar>span,.pagination-bar>.el-pagination{display:none}.mobile-pagination{width:100%;display:flex;align-items:center;justify-content:space-between}.detail-grid{grid-template-columns:1fr}.card-facts{grid-template-columns:1fr}}
@media(max-width:430px){.filter-grid{display:block}.filter-actions{margin-top:4px}.rescue-card{padding:16px}.drawer-head h2{font-size:1.35rem}}
.status-panel{margin:22px 0;padding:20px;display:flex;align-items:center;gap:12px;border-radius:20px;background:#f8f4ee;color:#806d62;line-height:1.55}
</style>
