<script setup lang="ts">
import { ref } from 'vue'
import AnimalManagementPage from '@/features/animals/AnimalManagementPage.vue'
import RescueManagementPage from '@/features/rescues/RescueManagementPage.vue'
import { MockAnimalGovernanceDataSource, previewCampusOptions } from './mockAnimalDataSource'
import { MockRescueGovernanceDataSource } from './mockRescueDataSource'

const animalDataSource = new MockAnimalGovernanceDataSource()
const rescueDataSource = new MockRescueGovernanceDataSource()
const activeModule = ref<'animals' | 'rescues'>('rescues')
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

    <nav class="preview-switch" aria-label="预览模块">
      <button :class="{ active: activeModule === 'animals' }" @click="activeModule = 'animals'">Animal 档案</button>
      <button :class="{ active: activeModule === 'rescues' }" @click="activeModule = 'rescues'">救助治理</button>
    </nav>

    <main>
      <AnimalManagementPage v-if="activeModule === 'animals'" :data-source="animalDataSource" :campus-options="previewCampusOptions" />
      <RescueManagementPage v-else :data-source="rescueDataSource" :campus-options="previewCampusOptions" />
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
.preview-switch {
  width: min(1180px, calc(100% - 36px)); margin: 16px auto 0; padding: 5px;
  display: flex; gap: 5px; border: 1px solid #eaded5; border-radius: 16px;
  background: rgb(255 253 249 / 88%); box-sizing: border-box;
}
.preview-switch button {
  flex: 1; min-height: 42px; border: 0; border-radius: 12px; color: #806d62;
  background: transparent; font: inherit; font-weight: 700; cursor: pointer;
}
.preview-switch button.active { color: #8b3e27; background: #fff0e9; box-shadow: inset 0 0 0 1px #f5c3b1; }
main { padding-bottom: 40px; }
@media (max-width: 620px) {
  .preview-header { align-items: flex-start; flex-direction: column; }
  .preview-badge { align-self: stretch; text-align: center; }
  .preview-notice { width: calc(100% - 24px); margin-top: 12px; }
  .preview-switch { width: calc(100% - 24px); }
}
</style>
