import { runtimeConfig } from '../../config/runtime'
import { ApiRequestError } from '../../services/apiClient'
import {
  createRescueDraft,
  getRescueDraft,
  submitRescueDraft,
  updateRescueDraft,
  type RescueDraftInput,
} from '../../services/rescueDraft'
import {
  currentCampusIdentity,
  currentMiniappUser,
  type CampusIdentitySnapshot,
} from '../../services/userAuthentication'
import { getSelectedCampus } from '../../state/campus'
import {
  localDateTimeFromInstant,
  localDateTimeInput,
  localDateTimeToInstant,
  rescueDraftFingerprint,
} from '../../utils/rescueDraftForm'

const emptyForm = () => ({
  description: '',
  abnormalitySummary: '',
  occurredAt: localDateTimeInput(),
  publicLocationDescription: '',
  exactLocationDescription: '',
})

Page({
  data: {
    campus: null as ReturnType<typeof getSelectedCampus>,
    loading: false,
    error: '',
    notice: '',
    draftId: '',
    draftStorageKey: '',
    savedDraftFingerprint: '',
    hasUnsavedChanges: false,
    submittedEventId: '',
    form: emptyForm(),
  },

  async onLoad() {
    const campus = getSelectedCampus()
    this.setData({ campus })
    if (!campus) {
      this.setData({ error: '请先选择高校。' })
      return
    }
    if (runtimeConfig.userAuthenticationMode !== 'api') {
      this.setData({ error: '当前为离线演示模式，救助上报需要真实登录。' })
      return
    }

    try {
      const [user, identity] = await Promise.all([currentMiniappUser(), currentCampusIdentity()])
      if (!user) {
        this.setData({ error: '请先前往 My 完成微信登录。' })
        return
      }
      if (!active(identity, campus.id)) {
        this.setData({ error: '需要该高校的有效校园成员身份；请先在 My 提交申请并等待审核。' })
        return
      }

      const draftStorageKey = draftKey(user.userId, campus.id)
      this.setData({ draftStorageKey })
      const savedDraftId = wx.getStorageSync(draftStorageKey) as string | undefined
      if (savedDraftId) await this.restoreDraft(savedDraftId)
    } catch (error) {
      this.setData({ error: message(error) })
    }
  },

  async restoreDraft(draftId: string) {
    try {
      const draft = await getRescueDraft(draftId)
      if (draft.campusId !== this.data.campus?.id || draft.finalEventId) {
        this.clearPrivateDraft()
        return
      }
      this.setData({
        draftId: draft.id,
        form: {
          description: draft.description,
          abnormalitySummary: draft.abnormalitySummary || '',
          occurredAt: localDateTimeFromInstant(draft.occurredAt),
          publicLocationDescription: draft.publicLocationDescription,
          exactLocationDescription: draft.exactLocationDescription || '',
        },
        notice: '已恢复仅属于当前账号和高校的草稿。',
        savedDraftFingerprint: rescueDraftFingerprint(draft),
        hasUnsavedChanges: false,
      })
    } catch (error) {
      this.clearPrivateDraft()
      this.setData({ error: message(error) })
    }
  },

  formInput(event: WechatMiniprogram.Input) {
    const field = event.currentTarget.dataset.field as keyof RescueDraftInput
    this.setData({ [`form.${field}`]: event.detail.value, hasUnsavedChanges: true })
  },

  async saveDraft() {
    if (this.data.loading) return
    const input = this.input()
    if (!input) return
    this.setData({ loading: true, error: '', notice: '' })
    try {
      const draft = this.data.draftId
        ? await updateRescueDraft(this.data.draftId, input)
        : await createRescueDraft(input)
      if (draft.campusId !== this.data.campus?.id) {
        throw new Error('草稿校园与当前选择不一致，已拒绝保存。')
      }
      wx.setStorageSync(this.data.draftStorageKey, draft.id)
      this.setData({
        draftId: draft.id,
        notice: '草稿已安全保存，仅你本人可以继续编辑。',
        savedDraftFingerprint: rescueDraftFingerprint(draft),
        hasUnsavedChanges: false,
      })
    } catch (error) {
      this.handleError(error)
    } finally {
      this.setData({ loading: false })
    }
  },

  async submit() {
    if (this.data.loading) return
    if (!this.data.draftId) {
      this.setData({ error: '请先保存草稿，再确认正式提交。' })
      return
    }
    const input = this.input()
    if (!input) return
    if (this.data.hasUnsavedChanges || rescueDraftFingerprint(input) !== this.data.savedDraftFingerprint) {
      this.setData({ error: '草稿有未保存修改，请先保存最新内容后再提交。' })
      return
    }
    this.setData({ loading: true, error: '', notice: '' })
    const confirmed = await new Promise<boolean>((resolve) => {
      wx.showModal({
        title: '确认提交救助事件',
        content: '提交后将生成 REPORTED Event，草稿不可再编辑。',
        success: (result) => resolve(result.confirm),
        fail: () => resolve(false),
      })
    })
    if (!confirmed) {
      this.setData({ loading: false })
      return
    }
    try {
      const event = await submitRescueDraft(this.data.draftId)
      this.clearPrivateDraft()
      this.setData({ submittedEventId: event.id, notice: `提交成功：Event ${event.id} · ${event.status}` })
    } catch (error) {
      this.handleError(error)
    } finally {
      this.setData({ loading: false })
    }
  },

  handleError(error: unknown) {
    if (error instanceof ApiRequestError && error.statusCode === 401) {
      this.clearPrivateDraft()
      this.setData({ error: '登录已失效，已清除本页私人草稿内容。' })
      return
    }
    this.setData({ error: message(error) })
  },

  clearPrivateDraft() {
    if (this.data.draftStorageKey) wx.removeStorageSync(this.data.draftStorageKey)
    this.setData({ draftId: '', form: emptyForm(), savedDraftFingerprint: '', hasUnsavedChanges: false })
  },

  input(): RescueDraftInput | null {
    const { campus, form } = this.data
    if (!campus || !form.description.trim() || !form.publicLocationDescription.trim()) {
      this.setData({ error: '请填写事件说明和公开位置描述。' })
      return null
    }
    return {
      campusId: campus.id,
      description: form.description.trim(),
      abnormalitySummary: form.abnormalitySummary.trim() || undefined,
      occurredAt: localDateTimeToInstant(form.occurredAt),
      publicLocationDescription: form.publicLocationDescription.trim(),
      exactLocationDescription: form.exactLocationDescription.trim() || undefined,
    }
  },
})

function active(snapshot: CampusIdentitySnapshot | null, campusId: string) {
  return !!snapshot?.memberships.some((value) => value.campus.id === campusId && value.status === 'ACTIVE')
}

function draftKey(userId: string, campusId: string) {
  return `animallink.miniapp.rescue-draft.${userId}.${campusId}`
}

function message(error: unknown) {
  return error instanceof Error ? error.message : '救助操作失败，请保留填写内容后重试。'
}
