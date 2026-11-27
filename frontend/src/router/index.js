/**
 * 路由表与全局守卫：/login 独立成页，其余页面都嵌在 Layout.vue（侧边菜单 + 内容区）下。
 * 守卫只做「有没有 token」的粗粒度拦截；菜单可见性按角色在 Layout.vue 里判断，
 * 真正的接口级权限仍由后端 AuthInterceptor 与角色校验兜底。
 */
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue'), meta: { title: '登录' } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    redirect: '/home',
    // 以下 5 个页面都挂在需要登录的 Layout 下：home/orders/ai 面向用户，staff/admin 由菜单按角色显示
    children: [
      { path: 'home', name: 'Home', component: () => import('../views/Home.vue'), meta: { title: '预约服务' } },
      { path: 'orders', name: 'Orders', component: () => import('../views/Orders.vue'), meta: { title: '我的订单' } },
      { path: 'ai', name: 'Ai', component: () => import('../views/Ai.vue'), meta: { title: '养护助手' } },
      { path: 'staff', name: 'Staff', component: () => import('../views/Staff.vue'), meta: { title: '服务者工作台' } },
      { path: 'admin', name: 'Admin', component: () => import('../views/Admin.vue'), meta: { title: '运营后台' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes })

// 全局前置守卫：除 /login 外所有页面都要求已登录（无 token 直接 next('/login')），
// 顺带把 meta.title 拼进 document.title；已登录用户停留在页面内不做额外跳转。
router.beforeEach((to, from, next) => {
  const token = sessionStorage.getItem('token') || localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    next('/login')
    return
  }
  document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '毛孩到家'
  next()
})

export default router
