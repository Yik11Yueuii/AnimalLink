<script setup lang="ts">
defineProps<{ title: string; eyebrow: string }>()
</script>
<template>
  <section class="dashboard-panel">
    <header class="panel-header"><div><p>{{ eyebrow }}</p><h2>{{ title }}</h2></div><slot name="action" /></header>
    <slot />
  </section>
</template>
<style scoped>
.dashboard-panel { min-width:0; padding:24px; border:1px solid rgba(118,86,72,.08); border-radius:24px; background:rgba(255,255,255,.94); box-shadow:0 14px 36px rgba(91,65,52,.065); }
.panel-header { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:18px; }.panel-header p { margin:0 0 5px; color:#e36f65; font-size:11px; font-weight:750; letter-spacing:.13em; text-transform:uppercase; }.panel-header h2 { margin:0; color:#3d3431; font-size:19px; }
@media (max-width:420px) { .dashboard-panel { padding:20px 17px; border-radius:20px; } }
</style>
