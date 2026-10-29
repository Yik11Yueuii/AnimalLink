<script setup lang="ts">
import AdoptionManagementPage from './AdoptionManagementPage.vue'
import { adoptionApiDataSource } from './apiDataSource'
</script>

<template><AdoptionManagementPage :data-source="adoptionApiDataSource" /></template>
