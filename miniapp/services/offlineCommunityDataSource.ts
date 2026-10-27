import type { CommunityDataSource } from './communityDataSource'
import type {
  CommunityAnimalCardResponse,
  CommunityCommentResponse,
  CommunityPostResponse,
  PostType,
} from '../types/community'

const animal = (id: string, displayName: string, species: 'CAT' | 'DOG' | 'OTHER'): CommunityAnimalCardResponse => ({
  id,
  displayName,
  species,
  adoptionStatus: 'NOT_OPEN',
  currentContext: 'CAMPUS',
})

const post = (
  id: string,
  campusId: string,
  postType: PostType,
  textContent: string,
  createdAt: string,
  options: Partial<CommunityPostResponse> = {},
): CommunityPostResponse => ({
  id,
  campusId,
  postType,
  textContent,
  authorDisplayName: '校园成员',
  animal: null,
  likeCount: 0,
  commentCount: 0,
  createdAt,
  updatedAt: createdAt,
  ...options,
})

export const offlineCommunityPosts: CommunityPostResponse[] = [
  post('33000000-0000-4000-8000-000000000005', '22000000-0000-4000-8000-000000000001', 'CAMPUS_POST', '【虚构演示】阿黄下午在开阔区域慢慢散步，精神状态看起来不错。这里只记录一般观察，没有救助结论。', '2026-10-22T16:00:00+08:00', { animal: animal('23000000-0000-4000-8000-000000000003', '阿黄', 'DOG'), likeCount: 8, commentCount: 0 }),
  post('33000000-0000-4000-8000-000000000004', '22000000-0000-4000-8000-000000000001', 'CAMPUS_POST', '【虚构演示】墨墨今晚在安静处短暂停留，看到有人靠近就慢慢走开了。记录到状态平稳。', '2026-10-21T19:10:00+08:00', { animal: animal('23000000-0000-4000-8000-000000000002', '墨墨', 'CAT'), likeCount: 12, commentCount: 1 }),
  post('33000000-0000-4000-8000-000000000003', '22000000-0000-4000-8000-000000000001', 'CAMPUS_POST', '【虚构演示】今天为小橘补充了外观特征，之后再遇见时可以先核对耳朵和尾巴，不要只凭毛色判断。', '2026-10-21T09:15:00+08:00', { animal: animal('23000000-0000-4000-8000-000000000001', '小橘', 'CAT'), likeCount: 19, commentCount: 0 }),
  post('33000000-0000-4000-8000-000000000001', '22000000-0000-4000-8000-000000000001', 'CAMPUS_POST', '【虚构演示】傍晚第一次遇见小橘，它在树荫边停了一会儿。先远距离记录，不追赶也不随意投喂。', '2026-10-20T17:20:00+08:00', { animal: animal('23000000-0000-4000-8000-000000000001', '小橘', 'CAT'), likeCount: 24, commentCount: 2 }),
  post('post-mingde-5', 'campus-mingde', 'RESCUE_UPDATE', '橘子今天已经完成复查，精神状态稳定。志愿者仍会继续观察两天，也请大家保持安静距离。', '2026-10-22T16:10:00+08:00', { animal: animal('animal-orange', '橘子', 'CAT'), likeCount: 28, commentCount: 5 }),
  post('post-mingde-4', 'campus-mingde', 'CAMPUS_POST', '傍晚在图书馆东侧看见雪团慢慢经过，今天没有下雨，草坪也很干燥。', '2026-10-21T18:35:00+08:00', { animal: animal('animal-snow', '雪团', 'CAT'), likeCount: 16, commentCount: 2 }),
  post('post-mingde-3', 'campus-mingde', 'ADOPTION', '阿麦的领养交接正在准备中。批准申请不等于已经完成领养，后续仍以正式交接记录为准。', '2026-10-20T09:15:00+08:00', { animal: { ...animal('animal-wheat', '阿麦', 'DOG'), adoptionStatus: 'HANDOVER_PENDING' }, likeCount: 35, commentCount: 3 }),
  post('post-mingde-2', 'campus-mingde', 'CAMPUS_POST', '周末志愿者整理了公共饮水点，感谢路过同学没有投放不合适的食物。', '2026-10-18T12:00:00+08:00', { likeCount: 11, commentCount: 0 }),
  post('post-mingde-1', 'campus-mingde', 'POST_ADOPTION', '来福在新家适应良好，今天第一次愿意在阳台边安静晒太阳。', '2026-10-16T10:20:00+08:00', { animal: { ...animal('animal-lucky', '来福', 'DOG'), adoptionStatus: 'ADOPTED', currentContext: 'ADOPTED_HOME' }, likeCount: 42, commentCount: 4 }),
  post('post-boyan-5', 'campus-boyan', 'CAMPUS_POST', '研究生公寓庭院的饮水碗已清洁，小灰今天在树荫下休息。', '2026-10-22T14:30:00+08:00', { animal: animal('animal-grey', '小灰', 'CAT'), likeCount: 13, commentCount: 1 }),
  post('post-boyan-4', 'campus-boyan', 'ADOPTION', '奶盖的公开领养信息已更新，申请仍需要经过正式审核。', '2026-10-21T11:00:00+08:00', { animal: { ...animal('animal-milk', '奶盖', 'DOG'), adoptionStatus: 'OPEN' }, likeCount: 21, commentCount: 2 }),
  post('post-boyan-3', 'campus-boyan', 'RESCUE_UPDATE', '艺术中心北侧的观察事件已经转交志愿者跟进，目前不需要同学自行靠近。', '2026-10-19T16:00:00+08:00', { likeCount: 18, commentCount: 0 }),
  post('post-boyan-2', 'campus-boyan', 'POST_ADOPTION', '一则领养后近况：适应期正在按计划进行，档案会继续更新。', '2026-10-17T13:45:00+08:00', { likeCount: 30, commentCount: 3 }),
  post('post-boyan-1', 'campus-boyan', 'CAMPUS_POST', '雨后道路湿滑，公共投喂点已经临时调整到有遮挡的位置。', '2026-10-15T08:40:00+08:00', { likeCount: 7, commentCount: 0 }),
  post('post-hupan-5', 'campus-hupan', 'CAMPUS_POST', '松果今天仍在湖畔南门步道附近活动，请不要追赶或突然靠近。', '2026-10-22T17:05:00+08:00', { animal: animal('animal-pine', '松果', 'CAT'), likeCount: 19, commentCount: 2 }),
  post('post-hupan-4', 'campus-hupan', 'RESCUE_UPDATE', '南区广场的救助进度已更新，点点状态平稳，现场由志愿者持续处理。', '2026-10-21T15:25:00+08:00', { animal: animal('animal-dot', '点点', 'DOG'), likeCount: 26, commentCount: 4 }),
  post('post-hupan-3', 'campus-hupan', 'CAMPUS_POST', '湖畔花园今天记录到小羽活动，观察时请不要投喂或触碰。', '2026-10-20T07:50:00+08:00', { animal: animal('animal-bird', '小羽', 'OTHER'), likeCount: 9, commentCount: 1 }),
  post('post-hupan-2', 'campus-hupan', 'ADOPTION', '斑斑的领养信息仍处于公开阶段，提交申请不代表自动获得领养资格。', '2026-10-18T19:10:00+08:00', { animal: { ...animal('animal-spots', '斑斑', 'CAT'), adoptionStatus: 'OPEN' }, likeCount: 24, commentCount: 2 }),
  post('post-hupan-1', 'campus-hupan', 'POST_ADOPTION', '领养家庭分享了一段适应期文字近况，暂时没有可公开访问的媒体地址。', '2026-10-14T12:30:00+08:00', { likeCount: 33, commentCount: 0 }),
]

const comment = (id: string, postId: string, content: string, _displayName: string, createdAt: string): CommunityCommentResponse => ({
  // 离线预览也遵循匿名契约，不展示可追溯作者身份。
  id,
  postId,
  content,
  authorDisplayName: '校园成员',
  createdAt,
})

const commentsByPost: Record<string, CommunityCommentResponse[]> = {
  '33000000-0000-4000-8000-000000000001': [
    comment('34000000-0000-4000-8000-000000000001', '33000000-0000-4000-8000-000000000001', '【虚构演示】谢谢记录，我经过时也会保持距离观察。', '韩师同学', '2026-10-20T17:35:00+08:00'),
    comment('34000000-0000-4000-8000-000000000002', '33000000-0000-4000-8000-000000000001', '【虚构演示】外观特征很清楚，等审核后再作为公开识别参考。', '校园观察者', '2026-10-20T17:42:00+08:00'),
  ],
  '33000000-0000-4000-8000-000000000004': [
    comment('34000000-0000-4000-8000-000000000003', '33000000-0000-4000-8000-000000000004', '【虚构演示】墨墨比较谨慎，大家安静路过就好。', '校园成员', '2026-10-21T19:25:00+08:00'),
  ],
  'post-mingde-5': [
    comment('comment-1', 'post-mingde-5', '谢谢志愿者更新，辛苦了。', '明德同学', '2026-10-22T16:20:00+08:00'),
    comment('comment-2', 'post-mingde-5', '今晚经过时会保持距离。', '安静观察者', '2026-10-22T16:35:00+08:00'),
    comment('comment-3', 'post-mingde-5', '希望橘子继续稳定恢复。', '图书馆读者', '2026-10-22T17:00:00+08:00'),
    comment('comment-4', 'post-mingde-5', '公共区域的提醒已经看到了。', '校园志愿者', '2026-10-22T17:20:00+08:00'),
    comment('comment-5', 'post-mingde-5', '感谢持续记录。', '校友小安', '2026-10-22T18:05:00+08:00'),
  ],
  'post-mingde-4': [
    comment('comment-6', 'post-mingde-4', '傍晚也见到了，状态看起来不错。', '小周', '2026-10-21T19:00:00+08:00'),
    comment('comment-7', 'post-mingde-4', '谢谢温柔观察。', '动物守护队', '2026-10-21T19:20:00+08:00'),
  ],
}

const delay = () => new Promise<void>((resolve) => setTimeout(resolve, 120))

export class OfflineCommunityDataSource implements CommunityDataSource {
  async getFeed(campusId: string, page: number, size: number) {
    await delay()
    const items = offlineCommunityPosts
      .filter((item) => item.campusId === campusId)
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    const start = page * size
    return { items: items.slice(start, start + size), page, size, total: items.length }
  }

  async getPost(postId: string) {
    await delay()
    const result = offlineCommunityPosts.find((item) => item.id === postId)
    if (!result) throw new Error('未找到该校园动态。')
    return { ...result }
  }

  async getComments(postId: string, page: number, size: number) {
    await delay()
    const comments = commentsByPost[postId] ?? []
    const start = page * size
    return { items: comments.slice(start, start + size), page, size, total: comments.length }
  }
}
