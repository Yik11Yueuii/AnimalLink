import { createRouter, createWebHistory, type RouteRecordRaw, type RouterHistory } from 'vue-router'
import GovernanceLayout from '@/layouts/GovernanceLayout.vue'
import OverviewPage from '@/views/OverviewPage.vue'
import PlaceholderPage from '@/views/PlaceholderPage.vue'
import AnimalGovernanceView from '@/features/animals/AnimalGovernanceView.vue'
import RescueGovernanceView from '@/features/rescues/RescueGovernanceView.vue'
import AdoptionGovernanceView from '@/features/adoptions/AdoptionGovernanceView.vue'
import AccessDeniedPage from '@/views/AccessDeniedPage.vue'
import LoginPage from '@/views/LoginPage.vue'
import { useAdminAuthentication } from '@/composables/useAdminAuthentication'

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
  { path: 'animals', name: 'animals', component: AnimalGovernanceView, meta: { title: 'Animal 档案治理', description: '核心档案查看与修正', requiresAuthentication: true } },
  { path: 'animal-identity', name: 'animal-identity', component: PlaceholderPage, meta: { title: 'Animal 待确认/合并', description: 'UNKNOWN、候选确认与重复治理', requiresAuthentication: true } },
  { path: 'incidents', name: 'incidents', component: RescueGovernanceView, meta: { title: 'Event / Case 治理', description: '救助流程查看与异常干预', requiresAuthentication: true } },
  { path: 'support', name: 'support', component: PlaceholderPage, meta: { title: '公益支持管理', description: '申请审核、进度、支出与结算', requiresAuthentication: true } },
  { path: 'adoptions', name: 'adoptions', component: AdoptionGovernanceView, meta: { title: '领养申请治理', description: '申请审核状态与关联 Listing 查看', requiresAuthentication: true } },
  { path: 'content', name: 'content', component: PlaceholderPage, meta: { title: '内容治理', description: '内容处置与治理记录', requiresAuthentication: true } },
  { path: 'audits', name: 'audits', component: PlaceholderPage, meta: { title: '审计记录', description: '关键治理操作查询', requiresAuthentication: true } },
]

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: GovernanceLayout,
    meta: { requiresAuthentication: true },
    children: [
      { path: '', name: 'overview', component: OverviewPage, meta: { title: '治理概览' } },
      ...governancePages,
    ],
  },
  { path: '/login', name: 'login', component: LoginPage, meta: { title: '管理员登录' } },
  { path: '/auth-required', redirect: '/login' },
  { path: '/access-denied', name: 'access-denied', component: AccessDeniedPage, meta: { title: '无权限' } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const safePostLoginRedirect = (candidate: unknown): string => {
  if (typeof candidate !== 'string' || !candidate.startsWith('/') || candidate.startsWith('//') || candidate.includes('\\')) return '/'
  try {
    const parsed = new URL(candidate, 'https://governance.animallink.invalid')
    if (parsed.origin !== 'https://governance.animallink.invalid' || parsed.pathname === '/login') return '/'
    return `${parsed.pathname}${parsed.search}${parsed.hash}`
  } catch {
    return '/'
  }
}

export const createGovernanceRouter = (history: RouterHistory) => {
  const router = createRouter({ history, routes })
  router.beforeEach(async (to) => {
    const authentication = useAdminAuthentication()
    await authentication.restore()

    if (to.name === 'login') {
      if (authentication.isAuthenticated.value) return safePostLoginRedirect(to.query.redirect)
      return true
    }

    if (!to.meta.requiresAuthentication) return true
    if (authentication.isAuthenticated.value) return true
    if (authentication.state.status === 'forbidden') return { name: 'access-denied' }
    return { name: 'login', query: { redirect: safePostLoginRedirect(to.fullPath) } }
  })
  return router
}

const router = createGovernanceRouter(createWebHistory())

export default router
