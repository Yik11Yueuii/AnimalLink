import { ApiRequestError } from '../../services/apiClient'
import { runtimeConfig } from '../../config/runtime'
import { currentCampusIdentity, currentMiniappUser, listIdentityCampuses, loginWithWechat, logoutMiniappUser, submitCampusIdentity, type CampusIdentitySnapshot, type CurrentMiniappUser, type IdentityCampusOption } from '../../services/userAuthentication'

Page({
  data: { loading: false, user: null as CurrentMiniappUser | null, error: '', offline: true, identity: null as CampusIdentitySnapshot | null,
    campuses: [] as IdentityCampusOption[], showIdentityForm: false, campusIndex: 0, membershipTypes: ['STUDENT', 'ALUMNI'],
    membershipTypeIndex: 0, selectedCampusName: '', sessionEpoch: 0,
    form: { applicantName: '', affiliationNote: '', expectedGraduationDate: '' } },
  onShow() { this.restoreSession() },
  async restoreSession() {
    const offline = runtimeConfig.userAuthenticationMode === 'offline-demo'
    const sessionEpoch = this.data.sessionEpoch + 1
    this.setData({ offline, sessionEpoch })
    try {
      const user = await currentMiniappUser()
      if (this.data.sessionEpoch !== sessionEpoch) return
      this.setData({ user, identity: null, campuses: [], error: '' })
      if (user) await this.loadCampusIdentity(sessionEpoch, user.userId)
    } catch (error) {
      if (this.data.sessionEpoch === sessionEpoch) {
        this.setData({ user: null, identity: null, campuses: [], error: this.message(error), offline: false })
      }
    }
  },
  async login() {
    const sessionEpoch = this.data.sessionEpoch + 1
    this.setData({ loading: true, error: '', sessionEpoch })
    try {
      const user = await loginWithWechat()
      if (this.data.sessionEpoch !== sessionEpoch) return
      this.setData({ user, identity: null, campuses: [], offline: false })
      await this.loadCampusIdentity(sessionEpoch, user.userId)
    } catch (error) {
      if (this.data.sessionEpoch === sessionEpoch) this.setData({ error: this.message(error) })
    } finally { if (this.data.sessionEpoch === sessionEpoch) this.setData({ loading: false }) }
  },
  async logout() {
    const sessionEpoch = this.data.sessionEpoch + 1
    this.setData({ loading: true, error: '', sessionEpoch, user: null, identity: null, campuses: [], showIdentityForm: false })
    try { await logoutMiniappUser() }
    catch (error) { if (this.data.sessionEpoch === sessionEpoch) this.setData({ error: this.logoutMessage(error) }) }
    finally { if (this.data.sessionEpoch === sessionEpoch) this.setData({ loading: false }) }
  },
  async loadCampusIdentity(sessionEpoch: number, userId: string | undefined) {
    if (runtimeConfig.userAuthenticationMode === 'offline-demo' || !userId) return
    try {
      const [identity, campuses] = await Promise.all([currentCampusIdentity(), listIdentityCampuses()])
      if (this.data.sessionEpoch !== sessionEpoch || this.data.user?.userId !== userId) return
      const activeCampuses = campuses.filter(campus => campus.status === 'ACTIVE')
      this.setData({ identity, campuses: activeCampuses, campusIndex: 0, selectedCampusName: activeCampuses[0]?.name || '' })
    } catch (error) {
      if (this.data.sessionEpoch !== sessionEpoch || this.data.user?.userId !== userId) return
      const expired = error instanceof ApiRequestError && error.statusCode === 401
      this.setData({ user: expired ? null : this.data.user, identity: null, campuses: [], error: this.message(error) })
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
    const campus = this.data.campuses[this.data.campusIndex]
    const applicantName = this.data.form.applicantName.trim()
    if (!campus || !applicantName) { this.setData({ error: '请选择校园并填写姓名。' }); return }
    this.setData({ loading: true, error: '' })
    try {
      await submitCampusIdentity({ campusId: campus.id, requestedMembershipType: this.data.membershipTypes[this.data.membershipTypeIndex] as 'STUDENT' | 'ALUMNI',
        applicantName, affiliationNote: this.data.form.affiliationNote.trim() || undefined,
        expectedGraduationDate: this.data.form.expectedGraduationDate || undefined })
      this.setData({ showIdentityForm: false })
      await this.loadCampusIdentity(this.data.sessionEpoch, this.data.user?.userId)
    } catch (error) { this.setData({ error: this.message(error) }) }
    finally { this.setData({ loading: false }) }
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
