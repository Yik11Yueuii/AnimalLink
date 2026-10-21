<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { Lock, UserFilled } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { authenticationMessage, prepareAdminAuthentication } from '@/api/authentication'
import { useAdminAuthentication } from '@/composables/useAdminAuthentication'
import { safePostLoginRedirect } from '@/router'

const route = useRoute()
const router = useRouter()
const authentication = useAdminAuthentication()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const preparing = ref(false)
const errorMessage = ref(authentication.state.message)

const prepare = async () => {
  if (preparing.value) return
  preparing.value = true
  try {
    await prepareAdminAuthentication()
  } catch (error) {
    errorMessage.value = authenticationMessage(error)
  } finally {
    preparing.value = false
  }
}

const submit = async () => {
  if (submitting.value || !form.username.trim() || !form.password) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await authentication.login(form.username, form.password)
    form.password = ''
    await router.replace(safePostLoginRedirect(route.query.redirect))
  } catch (error) {
    form.password = ''
    errorMessage.value = authenticationMessage(error)
  } finally {
    submitting.value = false
  }
}

const retrySession = async () => {
  errorMessage.value = ''
  await authentication.restore(true)
  if (authentication.isAuthenticated.value) {
    await router.replace(safePostLoginRedirect(route.query.redirect))
  } else {
    errorMessage.value = authentication.state.message
  }
}

onMounted(prepare)
onUnmounted(() => { form.password = '' })
</script>

<template>
  <main class="login-page">
    <section class="login-story" aria-label="AnimalLink 校园动物守护计划">
      <div class="story-brand"><span class="brand-mark" aria-hidden="true"><span>🐾</span></span><div><strong>AnimalLink</strong><small>校园动物守护计划</small></div></div>
      <div class="story-copy"><span>GOVERNANCE · WITH CARE</span><h1>每一次治理，<br />都从可信身份开始</h1><p>安全登录校园动物治理中心，审慎处理身份、档案、救助、公益与领养事务。</p></div>
      <div class="story-note"><span aria-hidden="true">🌿</span><p><strong>温柔治理</strong><small>让每个生命都被看见，让每次操作都有迹可循。</small></p></div>
    </section>

    <section class="login-panel">
      <div class="mobile-brand"><span class="brand-mark" aria-hidden="true"><span>🐾</span></span><strong>AnimalLink</strong></div>
      <div class="login-card">
        <p class="login-eyebrow">ADMIN PORTAL</p>
        <h2>管理员登录</h2>
        <p class="login-intro">使用由学校授权的治理账号继续。</p>
        <el-alert v-if="errorMessage" data-testid="login-error" :title="errorMessage" type="error" :closable="false" show-icon />
        <form data-testid="login-form" class="login-form" @submit.prevent="submit">
          <label for="admin-username">管理员用户名</label>
          <el-input id="admin-username" v-model="form.username" data-testid="username" :prefix-icon="UserFilled" autocomplete="username" maxlength="128" placeholder="请输入管理员用户名" :disabled="submitting" />
          <label for="admin-password">密码</label>
          <el-input id="admin-password" v-model="form.password" data-testid="password" :prefix-icon="Lock" autocomplete="current-password" maxlength="128" type="password" show-password placeholder="请输入密码" :disabled="submitting" />
          <el-button data-testid="login-submit" class="login-submit" type="primary" native-type="submit" :loading="submitting" :disabled="submitting || !form.username.trim() || !form.password">{{ submitting ? '正在安全验证' : '登录治理后台' }}</el-button>
        </form>
        <el-button v-if="authentication.state.status === 'unavailable' || authentication.state.status === 'validation-failed'" text type="primary" :loading="authentication.state.status === 'initializing'" @click="retrySession">重试会话检查</el-button>
        <p class="security-note"><span aria-hidden="true">🔒</span>密码和会话凭据不会保存在浏览器存储中。</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page{display:grid;grid-template-columns:minmax(360px,.9fr) minmax(460px,1.1fr);min-height:100vh;background:#fbf7f1}.login-story{position:relative;display:flex;overflow:hidden;flex-direction:column;padding:46px clamp(36px,5vw,76px);background:radial-gradient(circle at 85% 12%,rgba(255,255,255,.7),transparent 24%),linear-gradient(150deg,#ffe4da 0%,#f7d9c9 50%,#dcefe4 100%)}.login-story::before,.login-story::after{position:absolute;border-radius:50%;content:""}.login-story::before{right:-80px;bottom:16%;width:260px;height:260px;background:rgba(255,255,255,.24)}.login-story::after{right:70px;bottom:-90px;width:210px;height:210px;background:rgba(101,167,137,.14)}.story-brand,.mobile-brand{display:flex;position:relative;z-index:1;align-items:center;gap:12px}.story-brand>div{display:grid}.story-brand strong,.mobile-brand strong{color:#493b36;font-size:20px}.story-brand small{color:#8d746a;font-size:11px;letter-spacing:.08em}.story-copy{position:relative;z-index:1;margin:auto 0;color:#493a35}.story-copy>span{display:inline-flex;padding:7px 11px;border-radius:99px;background:rgba(255,255,255,.48);color:#ca5e55;font-size:11px;font-weight:800;letter-spacing:.12em}.story-copy h1{margin:20px 0 17px;font-size:clamp(38px,4.5vw,60px);line-height:1.12;letter-spacing:-.045em}.story-copy p{max-width:480px;margin:0;color:#765f57;font-size:15px;line-height:1.9}.story-note{display:flex;position:relative;z-index:1;align-items:center;gap:12px;padding:15px 17px;border:1px solid rgba(255,255,255,.6);border-radius:19px;background:rgba(255,255,255,.43);backdrop-filter:blur(12px)}.story-note>span{font-size:24px}.story-note p{display:grid;gap:3px;margin:0}.story-note strong{color:#4d7561;font-size:13px}.story-note small{color:#708379;font-size:11px}.login-panel{display:grid;padding:32px;place-items:center}.login-card{width:min(100%,460px);padding:42px;border:1px solid #eee0d7;border-radius:28px;background:#fffdf9;box-shadow:0 24px 70px rgba(96,70,57,.11)}.login-eyebrow{margin:0 0 8px;color:#df6a61;font-size:11px;font-weight:800;letter-spacing:.14em}.login-card h2{margin:0;color:#403632;font-size:30px;letter-spacing:-.03em}.login-intro{margin:9px 0 26px;color:#8a7d76;font-size:14px}.login-card .el-alert{margin-bottom:18px;border-radius:13px}.login-form{display:grid;gap:10px}.login-form label{margin-top:8px;color:#544843;font-size:13px;font-weight:700}.login-form :deep(.el-input__wrapper){min-height:46px;border-radius:13px;box-shadow:0 0 0 1px #e8dbd2 inset}.login-form :deep(.el-input__wrapper.is-focus){box-shadow:0 0 0 1px #eb7469 inset}.login-submit{width:100%;height:47px;margin-top:17px;border:0;border-radius:14px;background:#e97066;box-shadow:0 10px 24px rgba(233,112,102,.24);font-weight:750}.login-submit:hover,.login-submit:focus{background:#d95f57}.security-note{display:flex;align-items:center;justify-content:center;gap:6px;margin:22px 0 0;color:#9a8d86;font-size:11px}.mobile-brand{display:none;margin-bottom:22px}.mobile-brand .brand-mark{width:38px;height:38px}.login-card>.el-button{display:flex;margin:12px auto 0}.login-card :deep(.el-alert__title){line-height:1.55}
@media(max-width:900px){.login-page{grid-template-columns:1fr}.login-story{display:none}.login-panel{padding:30px 20px}.mobile-brand{display:flex}.login-card{padding:36px}}
@media(max-width:520px){.login-panel{align-content:start;padding:24px 14px}.login-card{padding:28px 22px;border-radius:23px}.login-card h2{font-size:27px}}
</style>
