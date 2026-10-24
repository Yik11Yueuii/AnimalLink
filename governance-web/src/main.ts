import { createApp, watch } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles/base.css'
import { useAdminAuthentication } from './composables/useAdminAuthentication'

const authentication = useAdminAuthentication()
watch(() => authentication.state.status, (status) => {
  if (status !== 'unauthenticated' || !router.currentRoute.value.meta.requiresAuthentication) return
  const redirect = router.currentRoute.value.fullPath
  void router.replace({ name: 'login', query: { redirect } })
})

createApp(App).use(router).use(ElementPlus).mount('#app')
