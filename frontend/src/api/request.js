/**
 * 全局 axios 实例：baseURL=/api，由 Nginx 反向代理到 Spring Boot。
 * 在这里集中处理三件事——请求注入 token、剥离后端统一响应包装、错误集中提示，
 * 让各页面只关心业务数据，不用重复写鉴权头和错误弹窗。
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({ baseURL: '/api', timeout: 15000 })

// 请求拦截器：把 token 写入 authorization 请求头，后端 AuthInterceptor 据此校验身份
request.interceptors.request.use((config) => {
  // 优先用当前标签页的身份，支持同时开多个标签页测试不同角色
  const token = sessionStorage.getItem('token') || localStorage.getItem('token')
  if (token) config.headers['authorization'] = token
  return config
})

// 响应拦截器：后端统一返回 { success, data, errorMsg }，成功时直接把整个 res 交给业务层，
// 页面里取 res.data 即是业务数据；success=false 也转成 Promise.reject 并在这里统一弹错误提示，
// 避免每个页面重复处理失败文案。
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.success === false) {
      ElMessage.error(res.errorMsg || '操作失败')
      return Promise.reject(new Error(res.errorMsg || '操作失败'))
    }
    return res
  },
  (error) => {
    // 401：token 过期或无效，清掉 sessionStorage/localStorage 里的身份再踢回登录页；
    // 当前已在 /login 时只清数据不跳，避免重定向循环。403 提示「没有权限」，
    // 其余按网络错误提示，三类错误文案都统一在这里收敛。
    if (error.response && error.response.status === 401) {
      sessionStorage.removeItem('token')
      sessionStorage.removeItem('user')
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      if (router.currentRoute.value.path !== '/login') {
        ElMessage.error('登录已过期，请重新登录')
        router.push('/login')
      }
    } else if (error.response && error.response.status === 403) {
      ElMessage.error('没有权限')
    } else {
      ElMessage.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default request
