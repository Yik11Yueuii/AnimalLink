import { ApiRequestError } from '../../services/apiClient'
import { communityRequest, communityWriteAvailable } from '../../services/communityWrite'
import type { CommunityLikeState, CommunityReceipt } from '../../services/communityWrite'
import { storedMiniappSession } from '../../services/userAuthentication'
import { getCommunityDataSource, isCommunityOfflineDemo } from '../../services/sourceFactory'
import { resolveCurrentCampusContext } from '../../services/campusContext'
import { CommentThread } from '../../state/communityFeed'
import { getSelectedCampus } from '../../state/campus'
import type { CommunityPostView, CommunityCommentView } from '../../utils/communityPresentation'
import { toCommunityCommentView, toCommunityPostView } from '../../utils/communityPresentation'
import { readableError } from '../../utils/presentation'

const source = getCommunityDataSource()
const comments = new CommentThread(source, 3)

Page({
  detailRequestVersion: 0,
  privateVersion: 0,
  likeVersion: 0,
  likeSession: '', likeCampus: '',
  data: {
    offline: isCommunityOfflineDemo(),
    likeState: null as CommunityLikeState | null, likeBusy:false, commentText:'', commentBusy:false, commentUncertain:false, actionMessage:'',
    postId: '',
    post: null as CommunityPostView | null,
    loading: true,
    errorMessage: '',
    comments: [] as CommunityCommentView[],
    commentTotal: 0,
    commentsLoading: false,
    commentsLoadingMore: false,
    commentsError: '',
    commentsHasMore: false,
  },
  onLoad(options: Record<string, string | undefined>) {
    const postId = options.postId ? decodeURIComponent(options.postId) : ''
    comments.selectPost(postId)
    this.setData({ postId })
    void this.loadAll()
  },
  onShow() { if(this.data.post) void this.loadLike(); else if(this.data.postId && !this.data.loading) void this.loadAll() },
  onHide() { this.detailRequestVersion++; this.privateVersion++; this.likeVersion++; this.likeSession='';this.likeCampus='';this.setData({loading:false,likeState:null,likeBusy:false,commentText:'',commentBusy:false,commentUncertain:false,actionMessage:''}) },
  commentChanged(e:WechatMiniprogram.Input) { this.setData({commentText:e.detail.value}) },
  async loadLike() {
    const token=storedMiniappSession(),campus=getSelectedCampus()?.id,version=this.privateVersion,likeVersion=++this.likeVersion,postId=this.data.postId
    this.setData({likeState:null});if(this.data.offline||!communityWriteAvailable()||!campus||!this.data.post||this.data.post.campusId!==campus)return
    try {const state=await communityRequest<CommunityLikeState>('/posts/'+encodeURIComponent(postId)+'/like?campusId='+encodeURIComponent(campus),'GET');if(version===this.privateVersion&&likeVersion===this.likeVersion&&token===storedMiniappSession()&&campus===getSelectedCampus()?.id){this.likeSession=token||'';this.likeCampus=campus;this.setData({likeState:state})}}catch(e){if(version===this.privateVersion&&likeVersion===this.likeVersion)this.setData({likeState:null})}
  },
  async toggleLike() {
    if(this.data.likeBusy||!this.data.likeState)return;if(this.likeSession!==storedMiniappSession()||this.likeCampus!==getSelectedCampus()?.id){this.setData({likeState:null});return}const token=storedMiniappSession(),campus=getSelectedCampus()?.id,version=this.privateVersion,postId=this.data.postId,active=this.data.likeState.active
    if(this.data.offline||!campus||!communityWriteAvailable())return;this.likeVersion++;this.setData({likeBusy:true,actionMessage:''})
    try{const state=await communityRequest<CommunityLikeState>('/posts/'+encodeURIComponent(postId)+'/like?campusId='+encodeURIComponent(campus),active?'DELETE':'POST');if(version===this.privateVersion&&token===storedMiniappSession()&&campus===getSelectedCampus()?.id)this.setData({likeState:state})}
    catch(e){if(version===this.privateVersion&&token===storedMiniappSession()&&campus===getSelectedCampus()?.id){this.setData({likeState:null,likeBusy:false,actionMessage:'操作结果未确定，正在重新查询点赞状态。'});await this.loadLike()}}
    finally{if(version===this.privateVersion&&token===storedMiniappSession()&&campus===getSelectedCampus()?.id)this.setData({likeBusy:false})}
  },
  async submitComment(){
    if(this.data.commentBusy||this.data.commentUncertain)return;const token=storedMiniappSession(),campus=getSelectedCampus()?.id,version=this.privateVersion,content=this.data.commentText.trim(),postId=this.data.postId
    if(this.data.offline||!campus||!communityWriteAvailable()||!content||content.length>1000){this.setData({actionMessage:'请先登录并填写 1000 字以内的评论。'});return}this.setData({commentBusy:true,actionMessage:''})
    try{await communityRequest<CommunityReceipt>('/posts/'+encodeURIComponent(postId)+'/comments','POST',{campusId:campus,content});if(version===this.privateVersion&&token===storedMiniappSession()&&campus===getSelectedCampus()?.id)this.setData({commentText:'',actionMessage:'评论已提交，待审核。'})}
    catch(e){if(version===this.privateVersion&&campus===getSelectedCampus()?.id){const current=storedMiniappSession();if(!current&&e instanceof ApiRequestError&&e.statusCode===401)this.setData({likeState:null,commentText:'',actionMessage:'登录已失效，请重新登录。'});else if(token===current){const rejected=e instanceof ApiRequestError&&e.statusCode!==null&&e.statusCode>=400&&e.statusCode<500;this.setData({commentUncertain:!rejected,actionMessage:rejected?'评论被拒绝，请检查高校成员资格和内容。':'评论提交结果未确定，请勿重复提交相同内容。'})}}}
    finally{if(version===this.privateVersion)this.setData({commentBusy:false})}
  },
  onRetry() {
    void this.loadAll()
  },
  onUnload() { this.detailRequestVersion += 1; this.privateVersion += 1; this.likeVersion += 1 },
  loadMoreComments() {
    void this.loadNextComments()
  },
  openAnimal(event: WechatMiniprogram.TouchEvent) {
    const animalId = String(event.currentTarget.dataset.id ?? '')
    if (animalId) wx.navigateTo({ url: `/pages/animal-detail/index?animalId=${encodeURIComponent(animalId)}` })
  },
  async loadAll() {
    if (!this.data.postId) {
      this.setData({ loading: false, errorMessage: '缺少 Post ID。' })
      return
    }
    const requestVersion = ++this.detailRequestVersion
    this.setData({ loading: true, errorMessage: '', comments: [], commentTotal: 0, commentsError: '' })
    try {
      const resolution = await resolveCurrentCampusContext()
      if (requestVersion !== this.detailRequestVersion || resolution.status === 'superseded') return
      if (resolution.status === 'missing') throw new Error('当前高校已失效，请重新选择高校。')
      if (resolution.status === 'unavailable') throw new Error('暂时无法验证当前高校，请检查网络后重试。')
      const post = await source.getPost(this.data.postId)
      if (requestVersion !== this.detailRequestVersion || getSelectedCampus()?.id !== resolution.campus.id) return
      if (post.campusId !== resolution.campus.id) throw new Error('该动态不属于当前高校。')
      this.setData({ post: toCommunityPostView(post) })
      void this.loadLike()
      const commentPending = comments.refresh()
      this.applyComments(comments.snapshot)
      const snapshot = await commentPending
      if (requestVersion === this.detailRequestVersion && getSelectedCampus()?.id === resolution.campus.id) this.applyComments(snapshot)
    } catch (error) {
      if (requestVersion === this.detailRequestVersion) this.setData({ post: null, errorMessage: readableError(error) })
    } finally {
      if (requestVersion === this.detailRequestVersion) this.setData({ loading: false })
    }
  },
  async loadNextComments() {
    const pending = comments.loadMore()
    this.applyComments(comments.snapshot)
    this.applyComments(await pending)
  },
  applyComments(snapshot: ReturnType<typeof commentSnapshot>) {
    this.setData({
      comments: snapshot.items.map(toCommunityCommentView),
      commentTotal: snapshot.total,
      commentsLoading: snapshot.loading,
      commentsLoadingMore: snapshot.loadingMore,
      commentsError: snapshot.errorMessage,
      commentsHasMore: snapshot.hasMore,
    })
  },
})

function commentSnapshot() {
  return comments.snapshot
}
