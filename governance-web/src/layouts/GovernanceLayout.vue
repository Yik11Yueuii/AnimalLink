<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, CollectionTag, DocumentChecked, Files, FirstAidKit, House, Menu, School, UserFilled } from '@element-plus/icons-vue'
import { useAdminAuthentication } from '@/composables/useAdminAuthentication'

const route = useRoute()
const router = useRouter()
const authentication = useAdminAuthentication()
const logoutPending = ref(false)
const sessionStatus = computed(() => authentication.isAuthenticated.value ? '管理员已认证' : '认证状态未知')
const isCollapsed = ref(false)
const mobileNavigationOpen = ref(false)
const mobileToggle = ref<{ $el: HTMLButtonElement }>()
const pageTitle = computed(() => route.meta.title ?? 'AnimalLink 治理后台')
let desktopQuery: MediaQueryList | undefined
const closeMobileNavigationOnDesktop = (event: MediaQueryListEvent) => {
  if (event.matches) mobileNavigationOpen.value = false
}
const restoreMobileToggleFocus = async () => {
  await nextTick()
  if (!desktopQuery?.matches) mobileToggle.value?.$el?.focus()
}

onMounted(() => {
  desktopQuery = window.matchMedia('(min-width: 1001px)')
  desktopQuery.addEventListener('change', closeMobileNavigationOnDesktop)
})
onUnmounted(() => desktopQuery?.removeEventListener('change', closeMobileNavigationOnDesktop))

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

const logout = async () => {
  if (logoutPending.value) return
  logoutPending.value = true
  try {
    await authentication.logout()
    ElMessage.success('已安全退出治理后台。')
    await router.replace({ name: 'login' })
  } catch {
    ElMessage.error(authentication.state.message || '退出登录失败，请稍后重试。')
  } finally {
    logoutPending.value = false
  }
}
</script>

<template>
  <el-container class="governance-shell">
    <el-aside :width="isCollapsed ? '76px' : '252px'" class="governance-sidebar desktop-sidebar">
      <div class="brand" :class="{ 'brand--collapsed': isCollapsed }">
        <span class="brand-mark" aria-hidden="true"><span>🐾</span></span>
        <span v-if="!isCollapsed" class="brand-copy"><strong>AnimalLink</strong><small>校园动物守护计划</small></span>
      </div>
      <div v-if="!isCollapsed" class="navigation-label">工作台</div>
      <el-menu :collapse="isCollapsed" :default-active="route.path" :collapse-transition="false" router class="governance-menu">
        <template v-for="item in navigation" :key="item.index">
          <el-sub-menu v-if="item.children" :index="item.index">
            <template #title><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></template>
            <el-menu-item v-for="child in item.children" :key="child.index" :index="child.index">{{ child.label }}</el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="item.index"><el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template></el-menu-item>
        </template>
      </el-menu>
      <div v-if="!isCollapsed" class="sidebar-care"><span aria-hidden="true">🌿</span><p><strong>温柔治理</strong><small>让每个生命都被看见</small></p></div>
    </el-aside>
    <el-container class="content-shell">
      <el-header class="governance-header">
        <el-button text circle class="desktop-toggle" aria-label="收起或展开导航" @click="isCollapsed = !isCollapsed"><el-icon><Menu /></el-icon></el-button>
        <el-button ref="mobileToggle" text circle class="mobile-toggle" aria-label="打开导航" aria-controls="mobile-navigation" :aria-expanded="mobileNavigationOpen" @click="mobileNavigationOpen = true"><el-icon><Menu /></el-icon></el-button>
        <div class="header-copy"><span>{{ pageTitle }}</span><small>AnimalLink 治理中心</small></div>
        <div class="header-actions">
          <el-button text circle aria-label="查看通知"><el-icon><Bell /></el-icon></el-button>
          <span class="session-status" :class="{ 'session-status--authenticated': authentication.isAuthenticated.value }" :title="authentication.state.session?.userId"><i></i> {{ sessionStatus }}</span>
          <el-button class="logout-button" text :loading="logoutPending" @click="logout">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="governance-main"><RouterView /></el-main>
    </el-container>
  </el-container>
  <el-drawer id="mobile-navigation" v-model="mobileNavigationOpen" title="导航" direction="ltr" size="min(286px, 88vw)" class="mobile-navigation-drawer" @closed="restoreMobileToggleFocus">
    <div class="drawer-brand"><span class="brand-mark" aria-hidden="true"><span>🐾</span></span><span><strong>AnimalLink</strong><small>校园动物守护计划</small></span></div>
    <el-menu :default-active="route.path" router class="governance-menu" @select="mobileNavigationOpen = false">
      <template v-for="item in navigation" :key="item.index">
        <el-sub-menu v-if="item.children" :index="item.index">
          <template #title><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></template>
          <el-menu-item v-for="child in item.children" :key="child.index" :index="child.index">{{ child.label }}</el-menu-item>
        </el-sub-menu>
        <el-menu-item v-else :index="item.index"><el-icon><component :is="item.icon" /></el-icon><template #title>{{ item.label }}</template></el-menu-item>
      </template>
    </el-menu>
    <el-button class="drawer-logout" plain :loading="logoutPending" @click="logout">退出登录</el-button>
  </el-drawer>
</template>
