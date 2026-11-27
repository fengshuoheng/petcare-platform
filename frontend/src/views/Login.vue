<template>
  <!--
    登录页（短信验证码登录入口，也是路由守卫发现未登录时的跳转目标）。
    主要交互：填手机号 → 获取验证码（按钮 60 秒倒计时）→ 填 6 位验证码登录；下方演示账号一键填手机号。
    调用接口：sendSms（接口不返回验证码，只回「发送成功」，本地从后端控制台日志查看 6 位码）、
             login（手机号 + 验证码换 token）、me（取当前用户资料）。
    登录成功后 token/user 存 sessionStorage，按 role 跳 /admin、/staff 或 /home。
  -->
  <div class="login-wrap">
    <div class="login-card">
      <div class="brand">
        <el-icon :size="30" color="#409eff"><House /></el-icon>
        <div>
          <div class="brand-title">毛孩到家</div>
          <div class="brand-sub">宠物上门服务预约平台</div>
        </div>
      </div>

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="手机号">
          <el-input v-model="phone" maxlength="11" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="code-row">
            <el-input v-model="code" maxlength="6" placeholder="请输入 6 位验证码" />
            <el-button :disabled="counting > 0" @click="onSend">
              {{ counting > 0 ? counting + 's' : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="onLogin">登录</el-button>
      </el-form>

      <el-divider>快速填入手机号</el-divider>
      <div class="demo">
        <el-tag v-for="item in demos" :key="item.phone" class="demo-tag" @click="fill(item.phone)">
          {{ item.label }} {{ item.phone }}
        </el-tag>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, me, sendSms } from '../api'

const router = useRouter()
const phone = ref('')
const code = ref('')
const loading = ref(false)
const counting = ref(0)

const demos = [
  { label: '用户', phone: '13700000001' },
  { label: '服务者', phone: '13900000001' },
  { label: '管理员', phone: '13800000000' }
]

function fill(value) {
  phone.value = value
}

// 获取验证码：接口不返回验证码，前端只做手机号格式校验与 60 秒按钮倒计时；
// 发送失败（如间隔内重复发送）由 request.js 的响应拦截器统一弹提示
async function onSend() {
  if (!/^1[3-9]\d{9}$/.test(phone.value)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  try {
    // 接口只回「发送成功」，验证码由服务端写入 Redis，不返回给前端
    await sendSms(phone.value)
    ElMessage.success('验证码已发送（本地 mock：请查看后端控制台日志）')
    counting.value = 60
    const timer = setInterval(() => {
      counting.value -= 1
      if (counting.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e) {
    // 请求拦截器已统一提示错误（如 60 秒内重复发送），这里不重复弹窗
  }
}

// 登录：手机号 + 6 位验证码换 token，存 sessionStorage（每个标签页可独立身份），
// 再调 me 取资料，按 role 跳 /admin(2)、/staff(1)、/home(0)
async function onLogin() {
  if (!phone.value || !code.value) {
    ElMessage.warning('请输入手机号和验证码')
    return
  }
  loading.value = true
  try {
    const res = await login(phone.value, code.value)
    // 存 sessionStorage：每个标签页可以有独立身份，方便同时测试多个角色
    sessionStorage.setItem('token', res.data.token)
    const profile = await me()
    sessionStorage.setItem('user', JSON.stringify(profile.data))
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    ElMessage.success('登录成功')
    const role = profile.data.role
    router.push(role === 2 ? '/admin' : role === 1 ? '/staff' : '/home')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e8f3ff 0%, #f7f9fc 60%, #fff5f5 100%);
}
.login-card {
  width: 380px;
  background: #fff;
  border-radius: 14px;
  padding: 28px;
  box-shadow: 0 12px 40px rgba(64, 158, 255, 0.12);
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}
.brand-title {
  font-size: 20px;
  font-weight: 700;
}
.brand-sub {
  font-size: 12px;
  color: #909399;
}
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.demo {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.demo-tag {
  cursor: pointer;
}
</style>
