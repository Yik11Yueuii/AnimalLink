<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()
const title = computed(() => route.meta.title ?? '治理模块')
const description = computed(() => route.meta.description ?? '该模块正在规划中。')
</script>

<template>
  <section class="page placeholder">
    <el-result icon="info" :title="String(title)" :sub-title="String(description)">
      <template #extra><el-tag type="success" effect="plain">管理员已认证</el-tag><p>认证已接入，治理业务接口尚未开放。本页面不连接受保护业务 API，也不提供治理操作。</p></template>
    </el-result>
  </section>
</template>
