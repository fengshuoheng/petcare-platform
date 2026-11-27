/**
 * 应用入口：创建 Vue 实例，注册 Element Plus 组件库与图标、路由，挂载到 #app。
 * Element Plus 全局 CSS 也在此引入；路由表见 router/index.js，根组件见 App.vue。
 */
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

const app = createApp(App)
// 批量全局注册图标组件，模板里可直接写 <Calendar />、<List /> 等，无需逐个 import
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.use(ElementPlus)
app.use(router)
app.mount('#app')
