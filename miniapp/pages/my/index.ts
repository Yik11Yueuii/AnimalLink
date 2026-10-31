import { ApiRequestError } from '../../services/apiClient'
import { runtimeConfig } from '../../config/runtime'
import { currentMiniappUser, loginWithWechat, logoutMiniappUser, type CurrentMiniappUser } from '../../services/userAuthentication'

Page({
  data: { loading: false, user: null as CurrentMiniappUser | null, error: '', offline: true },
  onShow() { this.restoreSession() },
  async restoreSession() {
    const offline = runtimeConfig.userAuthenticationMode === 'offline-demo'
    this.setData({ offline })
    try {
      const user = await currentMiniappUser()
      this.setData({ user, offline, error: '' })
    } catch (error) { this.setData({ user: null, error: this.message(error), offline: false }) }
  },
  async login() {
    this.setData({ loading: true, error: '' })
    try { this.setData({ user: await loginWithWechat(), offline: false }) }
    catch (error) { this.setData({ error: this.message(error) }) }
    finally { this.setData({ loading: false }) }
  },
  async logout() {
    this.setData({ loading: true, error: '' })
    try { await logoutMiniappUser() }
    catch (error) { this.setData({ error: this.logoutMessage(error) }) }
    finally { this.setData({ user: null, loading: false }) }
  },
  message(error: unknown) {
    if (error instanceof ApiRequestError && error.statusCode === 401) return '登录已失效，请重新登录。'
    if (error instanceof ApiRequestError && error.statusCode === 503) return '微信身份服务暂不可用，请稍后重试。'
    return error instanceof Error ? error.message : '操作未完成，请稍后重试。'
  },
  logoutMessage(error: unknown) {
    if (error instanceof ApiRequestError && (error.statusCode === null || error.statusCode === 503)) {
      return '本机已清除登录状态，但服务端会话撤销未得到确认。请稍后重新登录。'
    }
    if (error instanceof ApiRequestError && error.statusCode === 401) return '登录已失效，本机已清除登录状态。'
    return '本机已清除登录状态，但服务端会话撤销未得到确认。请稍后重新登录。'
  },
})
