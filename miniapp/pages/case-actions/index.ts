import { ApiRequestError } from '../../services/apiClient'
import { closeCase, createCaseAction, listCaseActions, storedMiniappSession, submitCaseResult } from '../../services/userAuthentication'
import type { CaseAction } from '../../services/userAuthentication'
import { getSelectedCampus } from '../../state/campus'

Page({
  caseId: '', campusId: '', generation: 0, session: '',
  data: { displayCaseId: '', isOwner: false, resultSummary: '', outcomeIndex: 0, outcomeLabels: ['继续观察', '处理成功', '未解决结束', '取消处理'], items: [] as CaseAction[], status: '', total: 0, page: -1, loading: false, submitting: false, error: '', message: '', description: '', date: '', time: '' },
  onLoad(options: Record<string, string | undefined>) { this.caseId = options.caseId || ''; this.campusId = options.campusId || ''; this.setData({ displayCaseId: this.caseId }) },
  onShow() { this.session = storedMiniappSession() || ''; if (!this.session || getSelectedCampus()?.id !== this.campusId) { this.invalidate(); this.setData({ error: '请登录并切换到案件所属高校。' }); return } void this.load(0) },
  onHide() { this.invalidate() },
  onUnload() { this.invalidate() },
  invalidate() { this.generation++; this.setData({ items: [], status: '', isOwner: false, resultSummary: '', outcomeIndex: 0, page: -1, total: 0, loading: false, submitting: false, description: '', error: '', message: '' }) },
  discardStale(version: number) { if (version === this.generation) this.invalidate() },
  current(version: number, token: string) { return version === this.generation && !!token && token === storedMiniappSession() && getSelectedCampus()?.id === this.campusId },
  onResultSummary(e: WechatMiniprogram.Input) { this.setData({ resultSummary: e.detail.value }) },
  onOutcome(e: WechatMiniprogram.PickerChange) { this.setData({ outcomeIndex: Number(e.detail.value) }) },
  async onResult() { await this.transition(false) },
  async onClose() { await this.transition(true) },
  async transition(close: boolean) {
    if (this.data.submitting || this.data.loading || (close ? this.data.status !== 'RESOLVED' || !this.data.isOwner : this.data.status !== 'ACTIVE')) return
    const version = this.generation, token = this.session
    if (!this.current(version, token)) { this.invalidate(); return }
    const summary = this.data.resultSummary.trim(), outcomes = ['CONTINUE_OBSERVATION', 'RESOLVED', 'UNRESOLVED', 'CANCELLED']
    if (!close && (!summary || !outcomes[this.data.outcomeIndex])) { this.setData({ message: '请填写结果说明。' }); return }
    this.setData({ submitting: true, message: '' })
    try {
      if (close) await closeCase(this.caseId, this.campusId)
      else await submitCaseResult(this.caseId, this.campusId, outcomes[this.data.outcomeIndex], summary)
      if (!this.current(version, token)) { this.discardStale(version); return }
      this.setData({ resultSummary: '', items: [], page: -1, message: close ? '案件已结案。' : '处理结果已保存。' })
      await this.load(0)
    } catch (error) {
      if (!this.current(version, token)) { this.discardStale(version); return }
      const status = error instanceof ApiRequestError ? error.statusCode : null
      this.setData({ message: status === 403 ? '当前账号无权执行此操作。' : status === 409 ? '案件状态已变更，请刷新后查看。' : status === 400 ? '请检查结果说明。' : '操作结果未确认，请先刷新案件和历史记录后再处理。' })
      if (status === 409) await this.load(0)
    } finally { if (version === this.generation) this.setData({ submitting: false }) }
  },
  onDescription(e: WechatMiniprogram.Input) { this.setData({ description: e.detail.value }) },
  onDate(e: WechatMiniprogram.PickerChange) { this.setData({ date: e.detail.value as string }) },
  onTime(e: WechatMiniprogram.PickerChange) { this.setData({ time: e.detail.value as string }) },
  onRetry() { void this.load(this.data.page < 0 ? 0 : this.data.page + 1) },
  onMore() { if (!this.data.loading && !this.data.submitting && this.data.items.length < this.data.total) void this.load(this.data.page + 1) },
  async load(page: number) {
    if (this.data.loading || !this.caseId || !this.session) return
    const version = this.generation, token = this.session
    if (!this.current(version, token)) { this.invalidate(); this.setData({ error: '账号或高校已切换，请重新进入。' }); return }
    this.setData({ loading: true, error: '' })
    try {
      const result = await listCaseActions(this.caseId, this.campusId, page)
      if (!this.current(version, token)) { this.discardStale(version); return }
      if (result.caseId !== this.caseId || result.campusId !== this.campusId || result.page !== page) throw new Error('返回的案件信息不匹配')
      const existing = page === 0 ? [] : this.data.items
      const ids = new Set(existing.map(item => item.id))
      this.setData({ items: [...existing, ...result.items.filter(item => !ids.has(item.id))], page, total: result.total, status: result.status, isOwner: result.isOwner })
    } catch (error) {
      if (!this.current(version, token)) { if (version === this.generation) { this.invalidate(); if (!storedMiniappSession()) this.setData({ error: '登录已失效，请重新登录。' }) }; return }
      const status = error instanceof ApiRequestError ? error.statusCode : null
      this.setData({ error: status === 401 ? '登录已失效，请重新登录。' : status === 403 ? '无权查看此案件的处理记录。' : '读取失败，请重试。' })
    } finally { if (version === this.generation) this.setData({ loading: false }) }
  },
  async onSubmit() {
    if (this.data.submitting || this.data.loading || this.data.status !== 'ACTIVE') return
    const version = this.generation, token = this.session, description = this.data.description.trim()
    if (!this.current(version, token)) { this.invalidate(); this.setData({ error: '账号或高校已切换，请重新进入。' }); return }
    const date = new Date(`${this.data.date}T${this.data.time}:00`)
    if (!description || !this.data.date || !this.data.time || Number.isNaN(date.getTime())) { this.setData({ message: '请填写处理描述和有效的发生时间。' }); return }
    this.setData({ submitting: true, message: '' })
    try {
      await createCaseAction(this.caseId, this.campusId, description, date.toISOString())
      if (!this.current(version, token)) { this.discardStale(version); return }
      this.setData({ description: '', message: '处理记录已保存。', items: [], page: -1 })
      await this.load(0)
    } catch (error) {
      if (!this.current(version, token)) { if (version === this.generation) { this.invalidate(); if (!storedMiniappSession()) this.setData({ error: '登录已失效，请重新登录。' }) }; return }
      const status = error instanceof ApiRequestError ? error.statusCode : null
      this.setData({ message: status === 401 ? '登录已失效，请重新登录。' : status === 403 ? '当前账号无权添加记录。' : status === 409 ? '案件已结束，不能继续添加。' : status === 400 ? '请检查处理描述和时间。' : '提交结果未确认，请先刷新记录后再重试。' })
      if (status === 409) await this.load(0)
    } finally { if (version === this.generation) this.setData({ submitting: false }) }
  },
})
