<script setup lang="ts">
import AnimalManagementPage from './AnimalManagementPage.vue'
import type { AnimalGovernanceDataSource } from './dataSource'

const unavailableDataSource: AnimalGovernanceDataSource = {
  async search() {
    throw new Error('治理 Animal 列表接口尚未接入；当前页面不会发起后端请求。')
  },
  async getDetail() {
    throw new Error('治理 Animal 详情接口尚未接入。')
  },
}
</script>

<template>
  <AnimalManagementPage :data-source="unavailableDataSource" :campus-options="[]" />
</template>
