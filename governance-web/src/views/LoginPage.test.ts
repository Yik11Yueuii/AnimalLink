import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { reactive, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginPage from './LoginPage.vue'

const login = vi.fn()
const restore = vi.fn()
const authenticationState = reactive({
  status: 'unauthenticated',
  message: '',
  session: null,
  expired: false,
})

vi.mock('@/api/authentication', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/authentication')>()
  return { ...original, prepareAdminAuthentication: vi.fn().mockResolvedValue(undefined) }
})
vi.mock('@/composables/useAdminAuthentication', () => ({
  useAdminAuthentication: () => ({ state: authenticationState, isAuthenticated: ref(false), login, restore }),
}))
vi.mock('@/router', () => ({ safePostLoginRedirect: () => '/' }))

describe('administrator login page', () => {
  beforeEach(() => {
    login.mockReset()
    restore.mockReset()
    authenticationState.status = 'unauthenticated'
    authenticationState.message = ''
  })

  const render = async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: LoginPage },
        { path: '/', component: { template: '<div>overview</div>' } },
      ],
    })
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginPage, { global: { plugins: [router, ElementPlus] } })
    return { wrapper, router }
  }

  it('renders the real administrator form without registration or demo credentials', async () => {
    const { wrapper } = await render()
    expect(wrapper.text()).toContain('管理员登录')
    expect(wrapper.text()).not.toContain('注册')
    expect(wrapper.text()).not.toContain('忘记密码')
    expect(wrapper.find('input[autocomplete="current-password"]').attributes('type')).toBe('password')
  })

  it('prevents duplicate submission and clears the password after success', async () => {
    let resolveLogin!: () => void
    login.mockReturnValue(new Promise<void>((resolve) => { resolveLogin = resolve }))
    const { wrapper, router } = await render()
    await wrapper.find('input[autocomplete="username"]').setValue('admin.root')
    await wrapper.find('input[autocomplete="current-password"]').setValue('private-password')

    await wrapper.find('[data-testid="login-form"]').trigger('submit')
    await wrapper.find('[data-testid="login-form"]').trigger('submit')
    expect(login).toHaveBeenCalledTimes(1)

    resolveLogin()
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/')
    expect((wrapper.find('input[autocomplete="current-password"]').element as HTMLInputElement).value).toBe('')
  })
})
