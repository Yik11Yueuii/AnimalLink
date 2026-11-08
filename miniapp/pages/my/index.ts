import { ApiRequestError } from '../../services/apiClient'
import { runtimeConfig } from '../../config/runtime'
import { currentCampusIdentity, currentMiniappUser, listIdentityCampuses, loginWithWechat, logoutMiniappUser, storedMiniappSession, submitCampusIdentity, type CampusIdentitySnapshot, type CurrentMiniappUser, type IdentityCampusOption } from '../../services/userAuthentication'

const emptyForm = () => ({ applicantName: '', affiliationNote: '', expectedGraduationDate: '' })
const sessionPresence = (): 'present' | 'absent' | 'unknown' => {
  try { return storedMiniappSession() ? 'present' : 'absent' }
  catch { return 'unknown' }
}
const expiredRequestSession = (requestToken: string | undefined): 'replaced' | 'absent' | 'unverified' => {
  try {
    const currentToken = storedMiniappSession()
    if (currentToken && currentToken !== requestToken) return 'replaced'
    return currentToken ? 'unverified' : 'absent'
  } catch { return 'unverified' }
}
type AuthPhase = 'unauthenticated' | 'restoring' | 'loggingIn' | 'loggingOut' | 'authenticated' | 'sessionUnverified' | 'restricted'

Page({
  openAdoptionApplications() { wx.navigateTo({ url: '/pages/my-applications/index' }) },
  loginInFlight: null as Promise<CurrentMiniappUser> | null,
  data: { loading: false, authPhase: 'unauthenticated' as AuthPhase, user: null as CurrentMiniappUser | null, error: '', offline: true, sessionRetryAvailable: false, identity: null as CampusIdentitySnapshot | null,
    campuses: [] as IdentityCampusOption[], showIdentityForm: false, campusIndex: 0, membershipTypes: ['STUDENT', 'ALUMNI'],
    membershipTypeIndex: 0, selectedCampusName: '', sessionEpoch: 0,
    form: emptyForm() },
  onShow() { if (!this.data.loading) this.restoreSession() },
  async restoreSession() {
    if (this.data.authPhase === 'loggingIn' || this.data.authPhase === 'loggingOut'
      || this.data.authPhase === 'restoring' || this.data.loading) return
    const offline = runtimeConfig.userAuthenticationMode === 'offline-demo'
    const sessionEpoch = this.data.sessionEpoch + 1
    this.setData({ offline, sessionEpoch, loading: true, authPhase: 'restoring', sessionRetryAvailable: false })
    try {
      const user = await currentMiniappUser()
      if (this.data.sessionEpoch !== sessionEpoch) return
      this.setData({ user, identity: null, campuses: [], error: '', sessionRetryAvailable: false,
        ...(user?.userId !== this.data.user?.userId ? { showIdentityForm: false, form: emptyForm() } : {}) })
      if (user) await this.loadCampusIdentity(sessionEpoch, user.userId)
    } catch (error) {
      if (this.data.sessionEpoch === sessionEpoch) {
        const presence = sessionPresence()
        const restricted = presence === 'present' && error instanceof ApiRequestError && error.statusCode === 403
        const retry = presence !== 'absent'
        this.setData({ user: null, identity: null, campuses: [], showIdentityForm: false,
          form: emptyForm(), authPhase: restricted ? 'restricted' : retry ? 'sessionUnverified' : 'unauthenticated',
          sessionRetryAvailable: retry, error: this.message(error) })
      }
    } finally {
      if (this.data.sessionEpoch === sessionEpoch) {
        this.setData({ loading: false, authPhase: (this.data.authPhase as AuthPhase) === 'restoring'
          ? this.data.user ? 'authenticated' : 'unauthenticated' : this.data.authPhase })
      }
    }
  },
  async login() {
    if (this.data.loading || this.data.offline || this.data.sessionRetryAvailable
      || this.data.authPhase !== 'unauthenticated') return
    const sessionEpoch = this.data.sessionEpoch + 1
    this.setData({ loading: true, authPhase: 'loggingIn', error: '', sessionEpoch })
    const loginTask = loginWithWechat(() => this.data.sessionEpoch === sessionEpoch && this.data.authPhase === 'loggingIn')
    this.loginInFlight = loginTask
    try {
      const user = await loginTask
      if (this.data.sessionEpoch !== sessionEpoch) return
      this.setData({ user, identity: null, campuses: [], offline: false, sessionRetryAvailable: false,
        showIdentityForm: false, form: emptyForm() })
      await this.loadCampusIdentity(sessionEpoch, user.userId)
    } catch (error) {
      if (this.data.sessionEpoch === sessionEpoch) {
        const retry = sessionPresence() !== 'absent'
        this.setData({ error: this.message(error), sessionRetryAvailable: retry,
          authPhase: retry ? 'sessionUnverified' : 'unauthenticated' })
      }
    } finally {
      if (this.loginInFlight === loginTask) this.loginInFlight = null
      if (this.data.sessionEpoch === sessionEpoch) this.setData({ loading: false,
        authPhase: (this.data.authPhase as AuthPhase) === 'loggingIn'
          ? this.data.user ? 'authenticated' : 'unauthenticated' : this.data.authPhase })
    }
  },
  async logout() {
    if (this.data.authPhase === 'loggingOut') return
    const pendingLogin = this.loginInFlight
    const sessionEpoch = this.data.sessionEpoch + 1
    this.setData({ loading: true, authPhase: 'loggingOut', error: '', sessionEpoch, user: null, identity: null, campuses: [],
      sessionRetryAvailable: false, showIdentityForm: false, form: emptyForm() })
    // Capture the old token for revocation immediately; a pending login cleans up
    // its own newly issued token if this logout invalidates it.
    const revocation = logoutMiniappUser().then(() => null, error => error as unknown)
    let cancellationError: unknown = null
    try {
      if (pendingLogin) {
        try { await pendingLogin }
        catch (error) {
          if (error instanceof ApiRequestError && error.message.includes('撤销未确认')) cancellationError = error
        }
      }
      const revocationError = await revocation
      if (revocationError || cancellationError) throw revocationError || cancellationError
    }
    catch (error) { if (this.data.sessionEpoch === sessionEpoch) this.setData({ error: this.logoutMessage(error) }) }
    finally { if (this.data.sessionEpoch === sessionEpoch) {
      const retry = sessionPresence() !== 'absent'
      this.setData({ loading: false, authPhase: retry ? 'sessionUnverified' : 'unauthenticated',
        user: null, identity: null, campuses: [], sessionRetryAvailable: retry,
        showIdentityForm: false, form: emptyForm() })
    } }
  },
  async loadCampusIdentity(sessionEpoch: number, userId: string | undefined) {
    if (runtimeConfig.userAuthenticationMode === 'offline-demo' || !userId) return
    let requestToken: string | undefined
    try {
      requestToken = storedMiniappSession()
      const identity = await currentCampusIdentity()
      if (this.data.sessionEpoch !== sessionEpoch || this.data.user?.userId !== userId) return
      const campuses = await listIdentityCampuses().catch(error => {
        if (error instanceof ApiRequestError && error.statusCode === 401) {
          throw new ApiRequestError('校园列表暂不可用，请稍后重试。', 503)
        }
        throw error
      })
      if (this.data.sessionEpoch !== sessionEpoch || this.data.user?.userId !== userId) return
      const activeCampuses = campuses.filter(campus => campus.status === 'ACTIVE')
      this.setData({ identity, campuses: activeCampuses, campusIndex: 0, selectedCampusName: activeCampuses[0]?.name || '' })
    } catch (error) {
      if (this.data.sessionEpoch !== sessionEpoch || this.data.user?.userId !== userId) return
      const expired = error instanceof ApiRequestError && error.statusCode === 401
      const sessionState = expired ? expiredRequestSession(requestToken) : null
      if (sessionState === 'replaced') return
      this.setData({ user: expired ? null : this.data.user, identity: null, campuses: [],
        ...(expired ? { showIdentityForm: false, form: emptyForm(),
          authPhase: sessionState === 'absent' ? 'unauthenticated' : 'sessionUnverified',
          sessionRetryAvailable: sessionState !== 'absent' } : {}),
        error: sessionState === 'unverified' ? '会话状态无法确认，请重试验证或主动退出。' : this.message(error) })
    }
  },
  retryCampusIdentity() { return this.loadCampusIdentity(this.data.sessionEpoch, this.data.user?.userId) },
  openIdentityForm() {
    if (!this.data.user) { this.setData({ error: '请先完成微信登录。' }); return }
    if (!this.data.identity) { this.setData({ error: '暂时无法读取校园身份，请重试。' }); return }
    if (!this.data.campuses.length) { this.setData({ error: '当前没有可申请的校园。' }); return }
    this.setData({ showIdentityForm: true, error: '' })
  },
  closeIdentityForm() { this.setData({ showIdentityForm: false }) },
  campusChange(event: WechatMiniprogram.PickerChange) {
    const campusIndex = Number(event.detail.value)
    this.setData({ campusIndex, selectedCampusName: this.data.campuses[campusIndex]?.name || '' })
  },
  membershipTypeChange(event: WechatMiniprogram.PickerChange) { this.setData({ membershipTypeIndex: Number(event.detail.value) }) },
  formInput(event: WechatMiniprogram.Input) {
    const field = event.currentTarget.dataset.field as 'applicantName' | 'affiliationNote' | 'expectedGraduationDate'
    this.setData({ [`form.${field}`]: event.detail.value })
  },
  async submitIdentity() {
    if (this.data.loading || !this.data.user || !this.data.identity) return
    const sessionEpoch = this.data.sessionEpoch
    const userId = this.data.user.userId
    let requestToken: string | undefined
    const campus = this.data.campuses[this.data.campusIndex]
    const applicantName = this.data.form.applicantName.trim()
    if (!campus || !applicantName) { this.setData({ error: '请选择校园并填写姓名。' }); return }
    this.setData({ loading: true, error: '' })
    try {
      requestToken = storedMiniappSession()
      await submitCampusIdentity({ campusId: campus.id, requestedMembershipType: this.data.membershipTypes[this.data.membershipTypeIndex] as 'STUDENT' | 'ALUMNI',
        applicantName, affiliationNote: this.data.form.affiliationNote.trim() || undefined,
        expectedGraduationDate: this.data.form.expectedGraduationDate || undefined })
      if (this.data.sessionEpoch !== sessionEpoch || this.data.user?.userId !== userId) return
      this.setData({ showIdentityForm: false })
      await this.loadCampusIdentity(sessionEpoch, userId)
    } catch (error) {
      if (this.data.sessionEpoch === sessionEpoch && this.data.user?.userId === userId) {
        const expired = error instanceof ApiRequestError && error.statusCode === 401
        const sessionState = expired ? expiredRequestSession(requestToken) : null
        if (sessionState === 'replaced') return
        this.setData({ error: sessionState === 'unverified' ? '会话状态无法确认，请重试验证或主动退出。' : this.message(error), ...(expired ? {
          user: null, identity: null, campuses: [], showIdentityForm: false, form: emptyForm(),
          authPhase: sessionState === 'absent' ? 'unauthenticated' : 'sessionUnverified',
          sessionRetryAvailable: sessionState !== 'absent',
        } : {}) })
      }
    } finally { if (this.data.sessionEpoch === sessionEpoch) this.setData({ loading: false }) }
  },
  message(error: unknown) {
    if (error instanceof ApiRequestError && error.statusCode === 401) return '登录已失效，请重新登录。'
    if (error instanceof ApiRequestError && error.statusCode === 403) return '当前会话的身份查询被拒绝。请重试验证或主动退出。'
    if (error instanceof ApiRequestError && error.statusCode === 503) {
      return error.message.startsWith('校园列表暂不可用') ? error.message : '微信身份服务暂不可用，请稍后重试。'
    }
    return error instanceof Error ? error.message : '操作未完成，请稍后重试。'
  },
  logoutMessage(error: unknown) {
    if (!(error instanceof ApiRequestError)) return '本机登录信息清理未确认，服务端会话状态也未确认。请稍后重试。'
    if (error instanceof ApiRequestError && (error.statusCode === null || error.statusCode === 503)) {
      return '本机已清除登录状态，但服务端会话撤销未得到确认。请稍后重新登录。'
    }
    if (error instanceof ApiRequestError && error.statusCode === 401) return '登录已失效，本机已清除登录状态。'
    return '本机已清除登录状态，但服务端会话撤销未得到确认。请稍后重新登录。'
  },
})
