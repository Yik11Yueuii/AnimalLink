<script setup lang="ts">
import { computed, onMounted } from 'vue'
import {
  adoptionStatusLabels,
  currentContextLabels,
  speciesLabels,
  identityStatusLabels,
  sexLabels,
  sterilizationLabels,
  type CampusOption,
  type GovernanceAnimal,
} from './types'
import type { AnimalGovernanceDataSource } from './dataSource'
import { useAnimalCatalog } from './useAnimalCatalog'

const props = defineProps<{
  dataSource: AnimalGovernanceDataSource
  campusOptions: CampusOption[]
}>()

const catalog = useAnimalCatalog(props.dataSource)
const speciesOptions = Object.entries(speciesLabels)
const drawerSize = 'min(620px, 100vw)'

const identityTagType = (status: GovernanceAnimal['identityStatus']) =>
  status === 'ACTIVE' ? 'success' : status === 'MERGED' ? 'warning' : 'info'
const adoptionTagType = (status: GovernanceAnimal['adoptionStatus']) =>
  status === 'OPEN' ? 'success' : status === 'HANDOVER_PENDING' ? 'warning' : status === 'ADOPTED' ? 'primary' : 'info'
const formatTime = (value: string | null) =>
  value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
const campusLabel = (campusId: string) => props.campusOptions.find((campus) => campus.id === campusId)?.label ?? '未知校区'
const compactId = (value: string) => `${value.slice(0, 8)}…${value.slice(-4)}`
const totalPages = computed(() => Math.max(1, Math.ceil(catalog.total.value / catalog.criteria.size)))
onMounted(() => { void catalog.load() })
</script>

<template>
  <section class="animal-page" aria-labelledby="animal-title">
    <header class="hero-card">
      <div>
        <p class="eyebrow">ANIMAL GOVERNANCE</p>
        <h1 id="animal-title">Animal 档案</h1>
        <p>集中检索校园动物的身份、领养与当前场景，快速核对档案信息。</p>
      </div>
      <div class="result-summary" aria-live="polite">
        <strong>{{ catalog.total.value }}</strong>
        <span>条匹配档案</span>
      </div>
    </header>

    <el-card class="filter-card" shadow="never">
      <el-form class="filter-grid" label-position="top" @submit.prevent="catalog.load(true)">
        <el-form-item label="关键词" class="keyword-field">
          <el-input
            v-model="catalog.criteria.keyword"
            clearable
            placeholder="名称、档案 ID、毛色或常驻区域"
            @keyup.enter="catalog.load(true)"
          />
        </el-form-item>
        <el-form-item label="Campus">
          <el-select v-model="catalog.criteria.campusId" clearable placeholder="全部校区" @change="catalog.load(true)">
            <el-option v-for="campus in campusOptions" :key="campus.id" :label="campus.label" :value="campus.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="物种">
          <el-select v-model="catalog.criteria.species" clearable placeholder="全部物种" @change="catalog.load(true)">
            <el-option v-for="species in speciesOptions" :key="species[0]" :label="species[1]" :value="species[0]" />
          </el-select>
        </el-form-item>
        <el-form-item label="身份状态">
          <el-select v-model="catalog.criteria.identityStatus" clearable placeholder="全部状态" @change="catalog.load(true)">
            <el-option v-for="(label, value) in identityStatusLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="领养状态">
          <el-select v-model="catalog.criteria.adoptionStatus" clearable placeholder="全部状态" @change="catalog.load(true)">
            <el-option v-for="(label, value) in adoptionStatusLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="当前场景">
          <el-select v-model="catalog.criteria.currentContext" clearable placeholder="全部场景" @change="catalog.load(true)">
            <el-option v-for="(label, value) in currentContextLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="catalog.criteria.sort" @change="catalog.load(true)">
            <el-option label="最近更新" value="UPDATED_DESC" />
            <el-option label="最早更新" value="UPDATED_ASC" />
            <el-option label="名称 A–Z" value="NAME_ASC" />
          </el-select>
        </el-form-item>
        <div class="filter-actions">
          <el-button type="primary" :loading="catalog.loading.value" @click="catalog.load(true)">查询</el-button>
          <el-button @click="catalog.reset">重置</el-button>
        </div>
      </el-form>
    </el-card>

    <div v-if="catalog.errorMessage.value" class="state-card state-error">
      <strong>档案暂时加载失败</strong>
      <span>{{ catalog.errorMessage.value }}</span>
      <el-button type="primary" plain @click="catalog.load">重试</el-button>
    </div>

    <el-skeleton v-else-if="catalog.loading.value && !catalog.items.value.length" class="state-card" :rows="6" animated />

    <div v-else-if="!catalog.items.value.length" class="state-card">
      <span class="state-icon">🐾</span>
      <strong>没有符合条件的档案</strong>
      <span>尝试调整关键词或筛选条件。</span>
      <el-button @click="catalog.reset">清除筛选</el-button>
    </div>

    <template v-else>
      <el-table class="animal-table" :data="catalog.items.value" row-key="id">
        <el-table-column label="Animal" min-width="190">
          <template #default="{ row }">
            <div class="animal-cell">
              <span class="animal-avatar">{{ row.species === 'DOG' ? '🐕' : row.species === 'CAT' ? '🐈' : '🐾' }}</span>
              <div><strong>{{ row.displayName }}</strong><small :title="row.id">{{ compactId(row.id) }}</small></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Campus" min-width="130"><template #default="{ row }">{{ campusLabel(row.campusId) }}</template></el-table-column>
        <el-table-column label="特征" min-width="170">
          <template #default="{ row }"><span>{{ speciesLabels[row.species] }} · {{ row.coatColor }}</span><small class="table-sub">{{ sexLabels[row.sex] }}</small></template>
        </el-table-column>
        <el-table-column label="身份" min-width="110">
          <template #default="{ row }"><el-tag :type="identityTagType(row.identityStatus)" effect="light">{{ identityStatusLabels[row.identityStatus] }}</el-tag></template>
        </el-table-column>
        <el-table-column label="领养" min-width="110">
          <template #default="{ row }"><el-tag :type="adoptionTagType(row.adoptionStatus)" effect="plain">{{ adoptionStatusLabels[row.adoptionStatus] }}</el-tag></template>
        </el-table-column>
        <el-table-column label="当前场景" min-width="120">
          <template #default="{ row }">{{ currentContextLabels[row.currentContext] }}</template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="155">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="92" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="catalog.openDetail(row.id)">查看详情</el-button></template>
        </el-table-column>
      </el-table>

      <div class="animal-cards">
        <article v-for="animal in catalog.items.value" :key="animal.id" class="animal-card" @click="catalog.openDetail(animal.id)">
          <div class="card-heading">
            <span class="animal-avatar">{{ animal.species === 'DOG' ? '🐕' : animal.species === 'CAT' ? '🐈' : '🐾' }}</span>
            <div><strong>{{ animal.displayName }}</strong><small>{{ campusLabel(animal.campusId) }}</small></div>
            <el-button link type="primary">详情</el-button>
          </div>
          <p>{{ speciesLabels[animal.species] }} · {{ animal.coatColor }} · {{ sexLabels[animal.sex] }}</p>
          <div class="tag-row">
            <el-tag :type="identityTagType(animal.identityStatus)">{{ identityStatusLabels[animal.identityStatus] }}</el-tag>
            <el-tag :type="adoptionTagType(animal.adoptionStatus)" effect="plain">{{ adoptionStatusLabels[animal.adoptionStatus] }}</el-tag>
            <span>{{ currentContextLabels[animal.currentContext] }}</span>
          </div>
          <small>更新于 {{ formatTime(animal.updatedAt) }}</small>
        </article>
      </div>
    </template>

    <footer v-if="catalog.total.value" class="pagination-bar">
      <span>第 {{ catalog.criteria.page }} / {{ totalPages }} 页</span>
      <el-pagination
        background
        layout="total, sizes, prev, pager, next"
        :total="catalog.total.value"
        :current-page="catalog.criteria.page"
        :page-size="catalog.criteria.size"
        :page-sizes="[5, 8, 10]"
        @current-change="catalog.changePage"
        @size-change="catalog.changePageSize"
      />
      <div class="mobile-pagination">
        <el-button :disabled="catalog.criteria.page <= 1" @click="catalog.changePage(catalog.criteria.page - 1)">上一页</el-button>
        <span>{{ catalog.criteria.page }} / {{ totalPages }}</span>
        <el-button :disabled="catalog.criteria.page >= totalPages" @click="catalog.changePage(catalog.criteria.page + 1)">下一页</el-button>
      </div>
    </footer>

    <el-drawer
      v-model="catalog.detailVisible.value"
      :size="drawerSize"
      :with-header="false"
      destroy-on-close
      class="animal-drawer"
      @closed="catalog.closeDetail"
    >
      <div class="drawer-head">
        <div>
          <p class="eyebrow">ANIMAL PROFILE</p>
          <h2>{{ catalog.detail.value?.displayName ?? '档案详情' }}</h2>
          <code v-if="catalog.detail.value">{{ catalog.detail.value.id }}</code>
        </div>
        <el-button circle aria-label="关闭详情" @click="catalog.closeDetail">×</el-button>
      </div>

      <div v-if="catalog.detailLoading.value" class="drawer-state"><el-skeleton :rows="8" animated /></div>
      <div v-else-if="catalog.detailError.value" class="drawer-state state-error">
        <strong>详情加载失败</strong><span>{{ catalog.detailError.value }}</span>
      </div>
      <template v-else-if="catalog.detail.value">
        <div class="status-banner">
          <el-tag :type="identityTagType(catalog.detail.value.identityStatus)">{{ identityStatusLabels[catalog.detail.value.identityStatus] }}</el-tag>
          <el-tag :type="adoptionTagType(catalog.detail.value.adoptionStatus)" effect="plain">{{ adoptionStatusLabels[catalog.detail.value.adoptionStatus] }}</el-tag>
          <span>{{ currentContextLabels[catalog.detail.value.currentContext] }}</span>
        </div>

        <section class="detail-section">
          <h3>基础档案</h3>
          <dl class="detail-grid">
            <div><dt>Campus</dt><dd>{{ campusLabel(catalog.detail.value.campusId) }}</dd></div>
            <div><dt>物种</dt><dd>{{ speciesLabels[catalog.detail.value.species] }}</dd></div>
            <div><dt>性别</dt><dd>{{ sexLabels[catalog.detail.value.sex] }}</dd></div>
            <div><dt>毛色</dt><dd>{{ catalog.detail.value.coatColor }}</dd></div>
            <div><dt>绝育状态</dt><dd>{{ sterilizationLabels[catalog.detail.value.sterilizationStatus] }}</dd></div>
            <div><dt>常驻区域</dt><dd>{{ catalog.detail.value.typicalArea }}</dd></div>
          </dl>
          <div class="detail-copy"><span>显著特征</span><p>{{ catalog.detail.value.distinctiveFeatures }}</p></div>
          <div class="detail-copy"><span>档案描述</span><p>{{ catalog.detail.value.description }}</p></div>
        </section>

        <section class="detail-section">
          <h3>档案记录</h3>
          <dl class="detail-grid">
            <div><dt>档案版本</dt><dd>v{{ catalog.detail.value.version }}</dd></div>
            <div><dt>创建时间</dt><dd>{{ formatTime(catalog.detail.value.createdAt) }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ formatTime(catalog.detail.value.updatedAt) }}</dd></div>
          </dl>
        </section>

        <section v-if="catalog.detail.value.identityStatus === 'MERGED' || catalog.detail.value.canonicalAnimalId" class="detail-section merge-section">
          <h3>合并信息</h3>
          <dl class="detail-grid">
            <div><dt>Canonical Animal</dt><dd>{{ catalog.detail.value.canonicalAnimalId ?? '—' }}</dd></div>
            <div><dt>Merge ID</dt><dd>{{ catalog.detail.value.mergeId ?? '—' }}</dd></div>
            <div><dt>合并时间</dt><dd>{{ formatTime(catalog.detail.value.mergedAt) }}</dd></div>
          </dl>
        </section>

        <section class="pending-grid">
          <div><strong>媒体资料</strong><span>当前治理详情契约尚未提供媒体列表。</span></div>
          <div><strong>时间线</strong><span>尚未接入 Animal 时间线数据。</span></div>
          <div><strong>合并历史</strong><span>仅展示当前契约提供的合并引用。</span></div>
        </section>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.animal-page { width: min(1180px, calc(100% - 36px)); margin: 0 auto; padding: 28px 0 48px; color: #49372e; }
.hero-card {
  display: flex; justify-content: space-between; align-items: center; gap: 28px;
  padding: clamp(24px, 4vw, 40px); border: 1px solid rgb(108 79 64 / 10%);
  border-radius: 28px; background: linear-gradient(130deg, #fffaf4, #fff 58%, #eff9f5);
  box-shadow: 0 20px 55px rgb(78 56 44 / 8%);
}
.eyebrow { margin: 0 0 7px; color: #ca6243; font-size: .72rem; font-weight: 800; letter-spacing: .18em; }
.hero-card h1, .drawer-head h2 { margin: 0; color: #402f27; line-height: 1.15; }
.hero-card h1 { font-size: clamp(1.8rem, 4vw, 2.8rem); }
.hero-card p:last-child { max-width: 620px; margin: 12px 0 0; color: #806d62; line-height: 1.7; }
.result-summary {
  flex: 0 0 auto; min-width: 126px; padding: 18px; display: grid; text-align: center;
  border-radius: 22px; color: #8b3e27; background: #fff0e9;
}
.result-summary strong { font-size: 2rem; line-height: 1; }
.result-summary span { margin-top: 7px; font-size: .82rem; }
.filter-card { margin-top: 20px; border: 0; border-radius: 24px; box-shadow: 0 15px 44px rgb(78 56 44 / 7%); }
.filter-grid { display: grid; grid-template-columns: 1.7fr repeat(3, minmax(128px, 1fr)); gap: 4px 14px; }
.filter-grid :deep(.el-form-item) { margin-bottom: 10px; }
.filter-grid :deep(.el-select) { width: 100%; }
.filter-grid :deep(.el-input__wrapper), .filter-grid :deep(.el-select__wrapper) { min-height: 42px; border-radius: 13px; }
.keyword-field { grid-column: span 2; }
.filter-actions { display: flex; align-items: end; gap: 8px; padding-bottom: 10px; }
.filter-actions .el-button { min-height: 42px; border-radius: 13px; }
.state-card {
  min-height: 280px; margin-top: 20px; padding: 32px; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 10px; text-align: center;
  border: 1px dashed #decfc4; border-radius: 24px; background: rgb(255 253 249 / 82%); color: #806d62;
}
.state-card strong { color: #4b372d; font-size: 1.05rem; }
.state-icon { font-size: 2.4rem; }
.state-error { border-color: #f0b6a3; background: #fff6f2; }
.animal-table { margin-top: 20px; border-radius: 22px; overflow: hidden; --el-table-header-bg-color: #f8f2eb; --el-table-row-hover-bg-color: #fff7f1; }
.animal-cell, .card-heading { display: flex; align-items: center; gap: 11px; }
.animal-cell div, .card-heading div { min-width: 0; display: grid; gap: 4px; }
.animal-cell small, .card-heading small, .animal-card > small { color: #927f74; }
.animal-avatar {
  width: 42px; height: 42px; flex: 0 0 auto; display: grid; place-items: center;
  border-radius: 14px; background: linear-gradient(145deg, #ffe6da, #edf8f3); font-size: 1.25rem;
}
.table-sub { display: block; margin-top: 4px; color: #98857a; }
.animal-cards { display: none; }
.pagination-bar { margin-top: 20px; display: flex; justify-content: space-between; align-items: center; color: #8a756a; }
.mobile-pagination { display: none; }
.drawer-head { display: flex; justify-content: space-between; gap: 20px; padding-bottom: 20px; border-bottom: 1px solid #eee3da; }
.drawer-head code { display: block; margin-top: 9px; color: #8d776a; font-size: .75rem; overflow-wrap: anywhere; }
.status-banner { margin: 22px 0; padding: 15px; display: flex; align-items: center; gap: 9px; flex-wrap: wrap; border-radius: 17px; background: #f8f4ee; }
.status-banner > span { color: #766259; font-size: .88rem; }
.detail-section { margin-top: 22px; padding: 21px; border: 1px solid #eee1d7; border-radius: 20px; background: #fffdfa; }
.detail-section h3 { margin: 0 0 16px; color: #594036; font-size: 1rem; }
.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 17px; margin: 0; }
.detail-grid div { min-width: 0; }
.detail-grid dt, .detail-copy span { color: #9a8478; font-size: .76rem; }
.detail-grid dd { margin: 5px 0 0; color: #4d3930; overflow-wrap: anywhere; }
.detail-copy { margin-top: 17px; }
.detail-copy p { margin: 6px 0 0; line-height: 1.7; }
.merge-section { background: #fff8ed; }
.pending-grid { margin-top: 22px; display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.pending-grid div { min-height: 110px; padding: 17px; display: grid; align-content: start; gap: 8px; border-radius: 17px; background: #eef8f4; }
.pending-grid span { color: #718078; font-size: .82rem; line-height: 1.55; }
.drawer-state { min-height: 300px; }
@media (max-width: 1050px) {
  .filter-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .keyword-field { grid-column: span 2; }
}
@media (max-width: 900px) {
  .animal-table { display: none; }
  .animal-cards { margin-top: 18px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px; }
  .animal-card { padding: 18px; border: 1px solid #eaded5; border-radius: 20px; background: #fffdfa; box-shadow: 0 10px 30px rgb(78 56 44 / 6%); cursor: pointer; }
  .card-heading .el-button { margin-left: auto; }
  .animal-card p { margin: 15px 0 11px; color: #705d53; }
  .tag-row { min-height: 34px; display: flex; align-items: center; gap: 7px; flex-wrap: wrap; margin-bottom: 12px; }
  .tag-row > span:last-child { color: #7f6e65; font-size: .8rem; }
}
@media (max-width: 720px) {
  .animal-page { width: calc(100% - 24px); padding-top: 16px; }
  .hero-card { align-items: flex-start; flex-direction: column; border-radius: 22px; }
  .result-summary { width: 100%; grid-template-columns: auto 1fr; align-items: baseline; text-align: left; }
  .result-summary span { margin: 0 0 0 8px; }
  .filter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .keyword-field { grid-column: 1 / -1; }
  .filter-actions { grid-column: 1 / -1; }
  .filter-actions .el-button { flex: 1; }
  .animal-cards { grid-template-columns: 1fr; }
  .pagination-bar > span, .pagination-bar > .el-pagination { display: none; }
  .mobile-pagination { width: 100%; display: flex; align-items: center; justify-content: space-between; }
  .detail-grid { grid-template-columns: 1fr; }
  .pending-grid { grid-template-columns: 1fr; }
}
@media (max-width: 430px) {
  .filter-grid { display: block; }
  .filter-actions { margin-top: 4px; }
  .animal-card { padding: 16px; }
  .drawer-head h2 { font-size: 1.35rem; }
}
</style>
