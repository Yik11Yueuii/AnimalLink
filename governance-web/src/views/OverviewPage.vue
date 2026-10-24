<script setup lang="ts">
import { ArrowRight, Avatar, ChatDotRound, CollectionTag, DocumentChecked, FirstAidKit, School, UserFilled } from '@element-plus/icons-vue'
import DashboardPanel from '@/components/dashboard/DashboardPanel.vue'
import MetricCard from '@/components/dashboard/MetricCard.vue'

const metrics = [
  { label: '待审核校园身份', value: '12', note: '演示数据 · 较昨日 +2', tone: 'coral' as const, icon: UserFilled },
  { label: '待确认动物档案', value: '8', note: '演示数据 · 含 3 组候选', tone: 'mint' as const, icon: CollectionTag },
  { label: '进行中救助 Case', value: '5', note: '演示数据 · 1 项需关注', tone: 'orange' as const, icon: FirstAidKit },
  { label: '待处理内容举报', value: '6', note: '演示数据 · 今日新增 2', tone: 'rose' as const, icon: DocumentChecked },
]
const todos = [
  { title: '校园身份审核', detail: '有 4 份材料等待人工复核', tag: '待审核', tone: 'warning' },
  { title: '动物身份合并', detail: '2 组疑似重复档案需要确认', tag: '需判断', tone: 'primary' },
  { title: '异常内容处置', detail: '1 条高优先级举报等待处理', tag: '较紧急', tone: 'danger' },
]
const shortcuts = [
  { label: '身份审核', to: '/identity-verifications', icon: UserFilled, color: 'coral' },
  { label: '档案治理', to: '/animals', icon: CollectionTag, color: 'mint' },
  { label: '救助管理', to: '/incidents', icon: FirstAidKit, color: 'orange' },
  { label: '领养管理', to: '/adoptions', icon: School, color: 'rose' },
]
const notices = [
  { date: '10月18日', title: '治理工作台视觉系统上线', detail: '首页统计当前为演示数据，正式接口将在后续阶段接入。' },
  { date: '10月16日', title: '校园动物档案治理提醒', detail: '处理合并候选时，请优先核对时间线与媒体证据。' },
]
</script>

<template>
  <section class="overview-page">
    <el-alert class="demo-alert" title="认证已接入，治理业务接口尚未开放；当前统计数字均为演示数据。" type="warning" :closable="false" show-icon />
    <section class="hero-card">
      <img class="hero-art" src="/illustrations/campus-companions-banner.png" alt="猫咪和狗狗在温暖校园花园中休息的插画" />
      <div class="hero-overlay" aria-hidden="true"></div>
      <div class="hero-content">
        <span class="hero-kicker">ANIMALLINK · CAMPUS CARE</span>
        <h1>让每一次守护，<br />都温柔而有迹可循</h1>
        <p>集中处理身份审核、动物档案、救助与领养治理，让校园里的每个生命都被认真看见。</p>
        <div class="hero-actions">
          <el-button class="hero-primary" round @click="$router.push('/animals')">查看动物档案 <el-icon><ArrowRight /></el-icon></el-button>
          <span><i></i> 系统运行平稳 · 演示状态</span>
        </div>
      </div>
    </section>
    <div class="section-heading"><div><p>OVERVIEW</p><h2>今日治理概览</h2></div><el-tag round effect="plain" type="info">演示数据</el-tag></div>
    <div class="metric-grid"><MetricCard v-for="item in metrics" :key="item.label" v-bind="item" /></div>
    <div class="dashboard-grid">
      <DashboardPanel title="待办事项" eyebrow="TO-DO LIST">
        <template #action><el-button text type="primary" @click="$router.push('/audits')">查看审计</el-button></template>
        <div class="todo-list">
          <button v-for="item in todos" :key="item.title" type="button" class="todo-item">
            <span class="todo-check"><el-icon><DocumentChecked /></el-icon></span>
            <span class="todo-copy"><strong>{{ item.title }}</strong><small>{{ item.detail }}</small></span>
            <el-tag :type="item.tone" size="small" round>{{ item.tag }}</el-tag>
          </button>
        </div>
      </DashboardPanel>
      <DashboardPanel title="快捷操作" eyebrow="QUICK ACCESS">
        <div class="shortcut-grid">
          <button v-for="item in shortcuts" :key="item.to" type="button" class="shortcut" :class="`shortcut--${item.color}`" @click="$router.push(item.to)">
            <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
          </button>
        </div>
        <div class="care-note"><el-icon><Avatar /></el-icon><span><strong>温馨提示</strong>管理员认证已接入，治理业务接口与写操作仍保持关闭。</span></div>
      </DashboardPanel>
    </div>
    <DashboardPanel title="系统公告" eyebrow="ANNOUNCEMENTS" class="notice-panel">
      <template #action><span class="notice-count"><el-icon><ChatDotRound /></el-icon> 2 条更新</span></template>
      <div class="notice-list"><article v-for="notice in notices" :key="notice.title"><time>{{ notice.date }}</time><div><h3>{{ notice.title }}</h3><p>{{ notice.detail }}</p></div></article></div>
    </DashboardPanel>
  </section>
</template>

<style scoped>
.overview-page{display:grid;gap:24px;width:min(100%,1280px);margin:0 auto}.demo-alert{border:1px solid #f2dbb9;border-radius:15px;background:#fff8e9}.hero-card{position:relative;min-height:340px;overflow:hidden;border-radius:30px;background:#f5e2d4;box-shadow:0 18px 48px rgba(106,75,59,.12)}.hero-art,.hero-overlay{position:absolute;inset:0;width:100%;height:100%}.hero-art{object-fit:cover;object-position:center}.hero-overlay{background:linear-gradient(90deg,rgba(255,249,239,.98) 0%,rgba(255,249,239,.91) 35%,rgba(255,249,239,.25) 68%,transparent 100%)}.hero-content{position:relative;z-index:1;width:min(560px,58%);padding:52px}.hero-kicker{display:inline-flex;padding:7px 11px;border-radius:99px;background:rgba(238,117,105,.12);color:#d95f57;font-size:11px;font-weight:800;letter-spacing:.11em}.hero-content h1{margin:17px 0 14px;color:#443733;font-size:clamp(34px,4vw,52px);line-height:1.14;letter-spacing:-.045em}.hero-content p{max-width:500px;margin:0;color:#746761;font-size:15px;line-height:1.85}.hero-actions{display:flex;align-items:center;gap:18px;margin-top:27px}.hero-primary{height:42px;padding-inline:20px;border:0;background:#eb7469;color:#fff;box-shadow:0 9px 22px rgba(235,116,105,.26)}.hero-primary:hover,.hero-primary:focus-visible{background:#dc655c;color:#fff}.hero-actions span{color:#746761;font-size:12px;white-space:nowrap}.hero-actions i{display:inline-block;width:7px;height:7px;margin-right:5px;border-radius:50%;background:#65a789;box-shadow:0 0 0 4px rgba(101,167,137,.13)}
.section-heading{display:flex;align-items:end;justify-content:space-between;gap:16px;margin-top:4px}.section-heading p{margin:0 0 5px;color:#e36f65;font-size:11px;font-weight:800;letter-spacing:.14em}.section-heading h2{margin:0;color:#3d3431;font-size:22px}.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:15px}.dashboard-grid{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(300px,.65fr);gap:20px}.todo-list{display:grid;gap:10px}.todo-item{display:grid;grid-template-columns:42px minmax(0,1fr) auto;align-items:center;gap:12px;width:100%;padding:12px;border:0;border-radius:16px;background:#fffaf5;color:inherit;text-align:left;cursor:pointer;transition:transform .16s ease,background .16s ease}.todo-item:hover,.todo-item:focus-visible{outline:0;background:#fff3eb;transform:translateY(-1px)}.todo-check{display:grid;width:42px;height:42px;place-items:center;border-radius:14px;background:#fce6df;color:#df7167;font-size:18px}.todo-copy{display:grid;gap:4px;min-width:0}.todo-copy strong{color:#453a36;font-size:14px}.todo-copy small{overflow:hidden;color:#8b7f79;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.shortcut-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.shortcut{display:flex;align-items:center;gap:9px;padding:14px 12px;border:0;border-radius:15px;color:#544843;font-weight:650;cursor:pointer;transition:transform .16s ease,filter .16s ease}.shortcut:hover,.shortcut:focus-visible{outline:2px solid #f19a91;outline-offset:2px;transform:translateY(-1px);filter:saturate(1.1)}.shortcut--coral{background:#ffe9e4}.shortcut--mint{background:#e2f4eb}.shortcut--orange{background:#fff0dc}.shortcut--rose{background:#f8e7ed}.care-note{display:flex;gap:10px;margin-top:15px;padding:12px;border-radius:15px;background:#f4f1eb;color:#7f746e;font-size:12px;line-height:1.55}.care-note .el-icon{flex:0 0 auto;margin-top:2px;color:#58a083}.care-note strong{display:block;color:#554a45}.notice-panel{margin-bottom:10px}.notice-count{display:inline-flex;align-items:center;gap:5px;color:#8b7f79;font-size:12px}.notice-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.notice-list article{display:grid;grid-template-columns:68px minmax(0,1fr);gap:13px;padding-top:16px;border-top:1px dashed #e6dcd5}.notice-list time{color:#df7167;font-size:12px;font-weight:700}.notice-list h3{margin:0 0 5px;color:#483d38;font-size:14px}.notice-list p{margin:0;color:#8b7f79;font-size:12px;line-height:1.65}
@media(max-width:1100px){.metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.dashboard-grid{grid-template-columns:1fr}.hero-content{width:66%;padding:42px}}@media(max-width:720px){.overview-page{gap:18px}.hero-card{min-height:440px;border-radius:24px}.hero-art{object-position:65% center}.hero-overlay{background:linear-gradient(180deg,rgba(255,249,239,.97) 0%,rgba(255,249,239,.88) 52%,rgba(255,249,239,.18) 100%)}.hero-content{width:100%;padding:29px 24px}.hero-content h1{font-size:clamp(31px,10vw,42px)}.metric-grid{grid-template-columns:1fr}.notice-list{grid-template-columns:1fr}.hero-actions{align-items:flex-start;flex-direction:column;gap:13px}.section-heading{align-items:center}}@media(max-width:390px){.hero-card{min-height:470px}.hero-content{padding:25px 19px}.hero-content h1{font-size:31px}.shortcut-grid{grid-template-columns:1fr}.todo-item{grid-template-columns:40px minmax(0,1fr)}.todo-item .el-tag{grid-column:2;justify-self:start}.section-heading h2{font-size:20px}}
.todo-copy small { overflow:visible; white-space:normal; line-height:1.5; }
.todo-item .el-tag { justify-self:end; }
.shortcut { min-height:48px; }
@media(max-width:1100px) { .notice-list { grid-template-columns:1fr; } }
@media(min-width:601px) and (max-width:1000px) {
  .hero-card { min-height:320px; }
  .hero-art { object-position:right center; }
  .hero-overlay { background:linear-gradient(90deg,rgba(255,249,239,.98) 0%,rgba(255,249,239,.92) 38%,rgba(255,249,239,.18) 72%,transparent 100%); }
  .hero-content { width:min(500px,65%); padding:38px; }
  .hero-content h1 { font-size:clamp(34px,4.5vw,42px); }
  .hero-actions { flex-direction:row; align-items:center; }
  .metric-grid { grid-template-columns:repeat(2,minmax(0,1fr)); }
}
@media(max-width:600px) {
  .hero-card { display:flex; flex-direction:column; min-height:0; }
  .hero-content { order:0; width:100%; padding:24px 22px 16px; background:#fff9ef; }
  .hero-content h1 { margin:12px 0 10px; font-size:clamp(29px,8vw,34px); }
  .hero-content p { font-size:14px; line-height:1.65; }
  .hero-actions { margin-top:18px; gap:10px; }
  .hero-art { position:relative; order:1; width:100%; height:auto; aspect-ratio:8 / 3; object-fit:cover; }
  .hero-overlay { display:none; }
}
@media(max-width:390px) {
  .hero-content { padding:22px 19px 14px; }
  .hero-content h1 { font-size:29px; }
  .todo-item .el-tag { justify-self:start; }
}
</style>
