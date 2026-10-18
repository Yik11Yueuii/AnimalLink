<script setup lang="ts">
import type { Component } from 'vue'
defineProps<{ label: string; value: string; note: string; tone: 'coral' | 'mint' | 'orange' | 'rose'; icon: Component }>()
</script>
<template>
  <article class="metric-card" :class="`metric-card--${tone}`">
    <div class="metric-icon" aria-hidden="true"><el-icon><component :is="icon" /></el-icon></div>
    <div class="metric-copy"><p>{{ label }}</p><strong>{{ value }}</strong><span>{{ note }}</span></div>
    <svg class="metric-decoration" viewBox="0 0 48 48" aria-hidden="true">
      <g v-if="tone === 'coral'" fill="currentColor"><ellipse cx="24" cy="30" rx="10" ry="8"/><ellipse cx="13" cy="19" rx="4" ry="6" transform="rotate(-25 13 19)"/><ellipse cx="21" cy="14" rx="4" ry="6"/><ellipse cx="29" cy="14" rx="4" ry="6"/><ellipse cx="37" cy="19" rx="4" ry="6" transform="rotate(25 37 19)"/></g>
      <path v-else-if="tone === 'mint'" d="M9 36C12 20 22 10 39 9c-1 17-11 27-27 28m0 0c6-7 12-12 20-16" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
      <path v-else-if="tone === 'orange'" d="M9 24V13l7 5a17 17 0 0 1 16 0l7-5v11c0 10-6 15-15 15S9 34 9 24Z M18 27h.1M30 27h.1M22 32h4" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
      <path v-else d="M24 39 10 26C1 17 14 6 24 17 34 6 47 17 38 26Z" fill="none" stroke="currentColor" stroke-width="3" stroke-linejoin="round"/>
    </svg>
  </article>
</template>
<style scoped>
.metric-card { position:relative; display:flex; align-items:flex-start; gap:14px; min-width:0; padding:20px 52px 20px 20px; border:1px solid rgba(118,86,72,.08); border-radius:22px; background:rgba(255,255,255,.92); box-shadow:0 12px 32px rgba(91,65,52,.07); }
.metric-decoration { position:absolute; top:13px; right:12px; width:34px; height:34px; color:#d68e7e; opacity:.22; pointer-events:none; }.metric-card--mint .metric-decoration { color:#5d9e81; }.metric-card--orange .metric-decoration { color:#db9859; }.metric-card--rose .metric-decoration { color:#c97d90; }
.metric-icon { display:grid; flex:0 0 46px; height:46px; place-items:center; border-radius:16px; font-size:22px; }
.metric-copy { display:grid; gap:3px; min-width:0; }.metric-copy p { margin:0; color:#776b66; font-size:13px; font-weight:650; }.metric-copy strong { color:#382f2c; font-size:30px; line-height:1.12; letter-spacing:-.04em; }.metric-copy span { color:#9a8e88; font-size:12px; }
.metric-card--coral .metric-icon { background:#ffe3dd; color:#e96e63; }.metric-card--mint .metric-icon { background:#ddf3e9; color:#4e9a7d; }.metric-card--orange .metric-icon { background:#fff0d8; color:#e59345; }.metric-card--rose .metric-icon { background:#fbe5eb; color:#c9667c; }
@media (max-width:420px) { .metric-card { padding:17px 52px 17px 17px; } }
</style>
