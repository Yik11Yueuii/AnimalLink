<script setup lang="ts">
import RescueManagementPage from './RescueManagementPage.vue'
import type { RescueGovernanceDataSource } from './dataSource'
const unavailableDataSource: RescueGovernanceDataSource = {
  async search() { throw new Error('治理救助列表接口尚未接入；当前页面不会发起后端请求。') },
  async getDetail() { throw new Error('治理救助详情接口尚未接入。') },
}
</script>
<template><RescueManagementPage :data-source="unavailableDataSource" :campus-options="[]" /></template>
