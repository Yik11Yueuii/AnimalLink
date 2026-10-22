<script setup lang="ts">
import AdoptionManagementPage from './AdoptionManagementPage.vue'
import type { AdoptionGovernanceDataSource } from './dataSource'

const unavailableDataSource: AdoptionGovernanceDataSource = {
  async search() { throw new Error('治理领养申请列表接口尚未接入；当前页面不会发起后端请求。') },
  async getDetail() { throw new Error('治理领养申请详情接口尚未接入。') },
}
</script>

<template><AdoptionManagementPage :data-source="unavailableDataSource" /></template>
