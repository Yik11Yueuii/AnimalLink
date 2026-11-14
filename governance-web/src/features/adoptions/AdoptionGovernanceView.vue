<script setup lang="ts">
import AdoptionManagementPage from './AdoptionManagementPage.vue'
import { adoptionApiDataSource } from './apiDataSource'
import { loadCampusOptions } from '@/features/rescues/apiDataSource'
import { onMounted, ref } from 'vue'
import type { CampusOption } from '@/features/animals/types'
const campuses = ref<CampusOption[]>([])
onMounted(async () => { try { campuses.value = await loadCampusOptions() } catch { campuses.value = [] } })
</script>

<template><AdoptionManagementPage :data-source="adoptionApiDataSource" :campus-options="campuses" /></template>
