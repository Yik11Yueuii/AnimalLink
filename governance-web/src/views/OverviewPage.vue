<script setup lang="ts">
import { ArrowRight, CollectionTag, DocumentChecked, FirstAidKit, School, UserFilled } from '@element-plus/icons-vue'

const entries = [
  { title: '身份与志愿者', description: '校园身份审核与志愿者授权管理', to: '/identity-verifications', icon: UserFilled },
  { title: 'Animal 治理', description: '核心档案、待确认身份与重复治理', to: '/animals', icon: CollectionTag },
  { title: '救助与公益', description: 'Event、Case 与公益支持审核', to: '/incidents', icon: FirstAidKit },
  { title: '领养管理', description: '申请、交接、领养关系与回访', to: '/adoptions', icon: School },
  { title: '内容与审计', description: '内容治理与关键操作追溯', to: '/content', icon: DocumentChecked },
]
</script>

<template>
  <section class="page">
    <div class="page-intro"><p class="eyebrow">AnimalLink Governance</p><h1>治理工作台</h1><p>审核、纠错、审计与监督的统一入口。</p></div>
    <el-alert title="正式身份认证尚未接入。当前仅展示基础界面，治理业务接口与操作保持未接通状态。" type="warning" :closable="false" show-icon />
    <div class="entry-grid">
      <el-card v-for="entry in entries" :key="entry.to" class="entry-card" shadow="never">
        <el-icon class="entry-icon"><component :is="entry.icon" /></el-icon>
        <h2>{{ entry.title }}</h2><p>{{ entry.description }}</p>
        <el-button text type="primary" @click="$router.push(entry.to)">进入模块 <el-icon><ArrowRight /></el-icon></el-button>
      </el-card>
    </div>
  </section>
</template>
