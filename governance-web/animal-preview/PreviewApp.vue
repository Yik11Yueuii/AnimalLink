<script setup lang="ts">
import AnimalManagementPage from '@/features/animals/AnimalManagementPage.vue'
import { MockAnimalGovernanceDataSource, previewCampusOptions } from './mockAnimalDataSource'

const dataSource = new MockAnimalGovernanceDataSource()
</script>

<template>
  <div class="preview-shell">
    <header class="preview-header">
      <div class="preview-brand">
        <span class="preview-paw" aria-hidden="true">🐾</span>
        <div>
          <strong>AnimalLink</strong>
          <span>校园动物治理后台</span>
        </div>
      </div>
      <span class="preview-badge">本地交互预览 · 模拟数据</span>
    </header>

    <el-alert
      class="preview-notice"
      title="此入口仅用于本地界面预览"
      description="无需管理员登录，不读取或覆盖认证状态，也不会向 Gateway 或任何业务服务发送请求。"
      type="warning"
      :closable="false"
      show-icon
    />

    <main>
      <AnimalManagementPage :data-source="dataSource" :campus-options="previewCampusOptions" />
    </main>
  </div>
</template>

<style scoped>
.preview-shell {
  min-height: 100vh;
  background:
    radial-gradient(circle at 8% 2%, rgb(244 139 109 / 13%), transparent 30rem),
    radial-gradient(circle at 92% 18%, rgb(123 195 170 / 12%), transparent 26rem),
    var(--al-color-canvas, #fbf6ee);
}
.preview-header {
  min-height: 72px;
  padding: 14px clamp(18px, 4vw, 56px);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid rgb(91 70 57 / 10%);
  background: rgb(255 253 249 / 90%);
  backdrop-filter: blur(16px);
}
.preview-brand { display: flex; align-items: center; gap: 12px; }
.preview-brand div { display: grid; gap: 2px; }
.preview-brand strong { color: #4f382c; font-size: 1.1rem; }
.preview-brand span { color: #8b7468; font-size: .82rem; }
.preview-paw {
  width: 42px; height: 42px; display: grid; place-items: center;
  border-radius: 14px; background: #f9e0d5;
}
.preview-badge {
  padding: 8px 13px; border-radius: 999px; color: #8b3e27;
  background: #fff0e9; border: 1px solid #f5c3b1; font-size: .84rem; font-weight: 700;
}
.preview-notice { width: min(1180px, calc(100% - 36px)); margin: 20px auto 0; }
main { padding-bottom: 40px; }
@media (max-width: 620px) {
  .preview-header { align-items: flex-start; flex-direction: column; }
  .preview-badge { align-self: stretch; text-align: center; }
  .preview-notice { width: calc(100% - 24px); margin-top: 12px; }
}
</style>
