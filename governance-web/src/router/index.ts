import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import GovernanceLayout from '@/layouts/GovernanceLayout.vue'
import OverviewPage from '@/views/OverviewPage.vue'
import PlaceholderPage from '@/views/PlaceholderPage.vue'
import AccessDeniedPage from '@/views/AccessDeniedPage.vue'
import AuthenticationRequiredPage from '@/views/AuthenticationRequiredPage.vue'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuthentication?: boolean
    title?: string
    description?: string
  }
}

const governancePages: RouteRecordRaw[] = [
  { path: 'identity-verifications', name: 'identity-verifications', component: PlaceholderPage, meta: { title: '校园身份审核', description: 'AI 预审与人工审核', requiresAuthentication: true } },
  { path: 'volunteers', name: 'volunteers', component: PlaceholderPage, meta: { title: '志愿者管理', description: '申请、授权、暂停与撤销', requiresAuthentication: true } },
  { path: 'animals', name: 'animals', component: PlaceholderPage, meta: { title: 'Animal 档案治理', description: '核心档案查看与修正', requiresAuthentication: true } },
  { path: 'animal-identity', name: 'animal-identity', component: PlaceholderPage, meta: { title: 'Animal 待确认/合并', description: 'UNKNOWN、候选确认与重复治理', requiresAuthentication: true } },
  { path: 'incidents', name: 'incidents', component: PlaceholderPage, meta: { title: 'Event / Case 治理', description: '救助流程查看与异常干预', requiresAuthentication: true } },
  { path: 'support', name: 'support', component: PlaceholderPage, meta: { title: '公益支持管理', description: '申请审核、进度、支出与结算', requiresAuthentication: true } },
  { path: 'adoptions', name: 'adoptions', component: PlaceholderPage, meta: { title: '领养管理', description: '申请、审核、交接、关系与回访', requiresAuthentication: true } },
  { path: 'content', name: 'content', component: PlaceholderPage, meta: { title: '内容治理', description: '内容处置与治理记录', requiresAuthentication: true } },
  { path: 'audits', name: 'audits', component: PlaceholderPage, meta: { title: '审计记录', description: '关键治理操作查询', requiresAuthentication: true } },
]

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: GovernanceLayout,
    children: [
      { path: '', name: 'overview', component: OverviewPage, meta: { title: '治理概览' } },
      ...governancePages,
    ],
  },
  { path: '/auth-required', name: 'auth-required', component: AuthenticationRequiredPage, meta: { title: '需要认证' } },
  { path: '/access-denied', name: 'access-denied', component: AccessDeniedPage, meta: { title: '无权限' } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  if (to.meta.requiresAuthentication) {
    return { name: 'auth-required', query: { redirect: to.fullPath } }
  }
})

export default router
