<template>
  <!--
    全局布局：左侧菜单 + 顶栏 + 内容区（<router-view />）。
    主要交互：按当前用户 role 显示菜单与身份标签；「切换身份」用 dev-login 快速换演示账号；退出清本地 token。
    调用接口：devLogin、me、logout。
  -->
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">
        <span class="brand-dot"></span>
        <span>毛孩到家</span>
      </div>
      <el-menu :default-active="$route.path" router background-color="#0f172a" text-color="#94a3b8"
               active-text-color="#ffffff">
        <el-menu-item index="/home"><el-icon><Calendar /></el-icon><span>预约服务</span></el-menu-item>
        <el-menu-item index="/orders"><el-icon><List /></el-icon><span>我的订单</span></el-menu-item>
        <el-menu-item index="/ai"><el-icon><ChatDotRound /></el-icon><span>养护助手</span></el-menu-item>
        <el-menu-item v-if="isStaff" index="/staff"><el-icon><Tools /></el-icon><span>服务者工作台</span></el-menu-item>
        <el-menu-item v-if="isAdmin" index="/admin"><el-icon><DataLine /></el-icon><span>运营后台</span></el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="title">{{ $route.meta.title }}</div>
        <div class="right">
          <el-tag v-if="isAdmin" type="danger" size="small">管理员</el-tag>
          <el-tag v-else-if="isStaff" type="warning" size="small">服务者</el-tag>
          <el-tag v-else type="success" size="small">用户</el-tag>
          <span class="name">{{ user.nickname || user.phone }}</span>
          <el-dropdown trigger="click" @command="onSwitchRole">
            <el-button text type="primary" size="small">切换身份</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="13700000001">用户 · 演示用户</el-dropdown-item>
                <el-dropdown-item command="13900000001">服务者 · 张美美</el-dropdown-item>
                <el-dropdown-item command="13900000002">服务者 · 李强</el-dropdown-item>
                <el-dropdown-item command="13800000000">管理员 · 平台</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button text type="primary" @click="onLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { devLogin, logout, me } from '../api'

const router = useRouter()
const user = ref(JSON.parse(sessionStorage.getItem('user') || localStorage.getItem('user') || '{}'))
// 角色：2 管理员 / 1 服务者 / 0 普通用户，决定侧边菜单与顶栏身份标签的显示
const isAdmin = computed(() => user.value.role === 2)
const isStaff = computed(() => user.value.role === 1)

// 切换身份：dev-login 免验证码换 token → me 取资料 → 写 sessionStorage → 整页重载，
// 让菜单、页面状态与已加载数据都按新角色重置（仅用于本地联调/冒烟）
async function onSwitchRole(phone) {
  const res = await devLogin(phone)
  sessionStorage.setItem('token', res.data.token)
  const profile = await me()
  sessionStorage.setItem('user', JSON.stringify(profile.data))
  ElMessage.success('已切换为 ' + (profile.data.nickname || phone))
  const target = profile.data.role === 2 ? '/admin' : profile.data.role === 1 ? '/staff' : '/home'
  // 重新加载，确保所有页面状态、菜单、数据都按新身份重置
  window.location.href = target
}

// 退出：调 logout 后清掉 sessionStorage/localStorage 里的身份并回登录页；接口异常也照常清理
async function onLogout() {
  try {
    await logout()
  } catch (e) {
    // 忽略退出接口异常
  }
  sessionStorage.removeItem('token')
  sessionStorage.removeItem('user')
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100%; }
.aside { background: #0f172a; }
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding-left: 18px;
  gap: 10px;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.brand-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  background: linear-gradient(135deg, #409eff, #f56c6c);
  display: inline-block;
}

.aside :deep(.el-menu) { border-right: none; }
.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}
.title { font-weight: 600; }
.right { display: flex; align-items: center; gap: 10px; }
.name { font-size: 13px; color: #606266; }
.main { padding: 18px; overflow-y: auto; }
</style>
