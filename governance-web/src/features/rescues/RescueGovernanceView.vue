<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { CampusOption } from '@/features/animals/types'
import RescueManagementPage from './RescueManagementPage.vue'
import { loadCampusOptions, rescueApiDataSource } from './apiDataSource'
const campuses = ref<CampusOption[]>([]), loading = ref(true), error = ref('')
const load = async () => {
  loading.value = true; error.value = ''
  try { campuses.value = await loadCampusOptions() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Campus 加载失败。' }
  finally { loading.value = false }
}
onMounted(() => { void load() })
</script>
<template>
  <div v-if="loading" class="route-state" v-loading="true">正在加载 Campus…</div>
  <div v-else-if="error" class="route-state"><strong>Campus 加载失败</strong><span>{{ error }}</span><el-button type="primary" @click="load">重试</el-button></div>
  <RescueManagementPage v-else :data-source="rescueApiDataSource" :campus-options="campuses" :initial-campus-id="campuses[0]?.id" />
</template>
<style scoped>.route-state{min-height:260px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;color:#806d62}</style>
