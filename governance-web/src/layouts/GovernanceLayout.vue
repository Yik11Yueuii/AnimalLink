<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Bell, CollectionTag, DocumentChecked, Files, FirstAidKit, House, School, UserFilled } from '@element-plus/icons-vue'

const route = useRoute()
const isCollapsed = ref(false)
const pageTitle = computed(() => route.meta.title ?? 'AnimalLink 治理后台')

const navigation = [
  { index: '/', label: '治理概览', icon: House },
  { index: '/identity-verifications', label: '身份与志愿者', icon: UserFilled, children: [
    { index: '/identity-verifications', label: '校园身份审核' },
    { index: '/volunteers', label: '志愿者管理' },
  ] },
  { index: '/animals', label: 'Animal 治理', icon: CollectionTag, children: [
    { index: '/animals', label: '档案治理' },
    { index: '/animal-identity', label: '待确认/合并' },
  ] },
  { index: '/incidents', label: '救助与公益', icon: FirstAidKit, children: [
    { index: '/incidents', label: 'Event / Case 治理' },
    { index: '/support', label: '公益支持管理' },
  ] },
  { index: '/adoptions', label: '领养管理', icon: School },
  { index: '/content', label: '内容治理', icon: DocumentChecked },
  { index: '/audits', label: '审计记录', icon: Files },
]
</script>

<template>
  <el-container class="shell">
    <el-aside :width="isCollapsed ? '68px' : '248px'" class="sidebar">
      <div class="brand">
        <span class="brand-mark">A</span>
        <span v-if="!isCollapsed">AnimalLink</span>
      </div>
      <el-menu :collapse="isCollapsed" :default-active="route.path" :collapse-transition="false" router class="menu">
        <template v-for="item in navigation" :key="item.index">
          <el-sub-menu v-if="item.children" :index="item.index">
            <template #title><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></template>
            <el-menu-item v-for="child in item.children" :key="child.index" :index="child.index">{{ child.label }}</el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="item.index"><el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template></el-menu-item>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-button text circle aria-label="收起或展开导航" @click="isCollapsed = !isCollapsed">☰</el-button>
        <div class="header-title">{{ pageTitle }}</div>
        <div class="header-actions"><el-icon><Bell /></el-icon><span class="session-status">未认证</span></div>
      </el-header>
      <el-main class="main"><RouterView /></el-main>
    </el-container>
  </el-container>
</template>
