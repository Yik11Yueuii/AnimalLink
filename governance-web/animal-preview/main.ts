import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@/styles/base.css'
import PreviewApp from './PreviewApp.vue'

createApp(PreviewApp).use(ElementPlus).mount('#animal-preview-app')
