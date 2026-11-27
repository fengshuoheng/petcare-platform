<template>
  <!--
    服务者工作台（履约侧）：查看个人资料/评级与可提现金额，按状态处理派给自己的订单。
    主要交互：接单 → 出发上门 → 到达打卡 → 填写服务报告完成服务；详情抽屉看状态流转时间线。
    调用接口：staffProfile、staffSettlements、staffOrders、staffOrderDetail、
             staffAccept、staffStart、staffArrive、staffComplete。
  -->
  <div class="staff">
    <el-card class="page-card" shadow="never">
      <div class="profile">
        <div class="avatar">{{ (profile.name || '服')[0] }}</div>
        <div class="profile-info">
          <div class="name">{{ profile.name }} <el-tag size="small" type="warning">L{{ profile.level }}</el-tag></div>
          <div class="muted">评分 {{ profile.rating }} · 已完成 {{ profile.orderCount }} 单 · 可服务 {{ profile.sizeTypes }}</div>
        </div>
        <div class="income">
          <div class="muted">可提现</div>
          <div class="price">￥{{ settlement.withdrawable || 0 }}</div>
        </div>
      </div>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="head">
          <el-radio-group v-model="status" size="small" @change="load">
            <el-radio-button :label="2">待接单</el-radio-button>
            <el-radio-button :label="3">待上门</el-radio-button>
            <el-radio-button :label="4">服务中</el-radio-button>
            <el-radio-button :label="5">待确认</el-radio-button>
            <el-radio-button :label="-1">全部</el-radio-button>
          </el-radio-group>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>

      <div v-if="rows.length === 0" class="empty">暂无订单</div>

      <div v-for="o in rows" :key="o.id" class="order-card">
        <div class="order-head">
          <div>
            <span class="order-no">{{ o.orderNo }}</span>
            <el-tag size="small" :type="statusTag(o.status)" class="ml8">{{ statusText(o.status) }}</el-tag>
            <el-tag v-if="o.urgent" size="small" type="danger" class="ml8">加急</el-tag>
          </div>
          <div class="muted">{{ formatTime(o.slotStart) }}</div>
        </div>
        <div class="order-body">
          <div>
            <div class="service">{{ o.serviceName }} · {{ o.petName }}（{{ sizeText(o.petSize) }}）</div>
            <div class="muted">{{ o.addressSnapshot }}</div>
            <div class="muted">联系人 {{ o.contactPhone }}</div>
            <div v-if="o.remark" class="muted">备注：{{ o.remark }}</div>
          </div>
          <div class="price">￥{{ o.payAmount }}</div>
        </div>
        <div class="actions">
          <el-button v-if="o.status === 2" type="primary" size="small" @click="accept(o)">接单</el-button>
          <el-button v-if="o.status === 3" type="primary" size="small" @click="start(o)">出发上门</el-button>
          <el-button v-if="o.status === 4" size="small" @click="arrive(o)">到达打卡</el-button>
          <el-button v-if="o.status === 4" type="success" size="small" @click="openComplete(o)">完成服务</el-button>
          <el-button size="small" @click="openDetail(o)">详情</el-button>
        </div>
      </div>
    </el-card>

    <el-dialog v-model="completeVisible" title="完成服务" width="480px">
      <el-form label-width="90px">
        <el-form-item label="宠物状态"><el-input v-model="completeForm.petCondition" placeholder="如：正常/略紧张" /></el-form-item>
        <el-form-item label="服务前照片"><el-input v-model="completeForm.beforePhotos" placeholder="图片地址，可留空" /></el-form-item>
        <el-form-item label="服务后照片"><el-input v-model="completeForm.afterPhotos" /></el-form-item>
        <el-form-item label="服务报告">
          <el-input v-model="completeForm.report" type="textarea" :rows="4" placeholder="做了哪些项目、宠物反应、护理建议" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="completeVisible = false">取消</el-button>
        <el-button type="primary" @click="doComplete">提交</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="订单详情" size="440px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">{{ detail.order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusText }}</el-descriptions-item>
          <el-descriptions-item label="客户地址">{{ detail.order.addressSnapshot }}</el-descriptions-item>
          <el-descriptions-item label="预约时间">{{ formatTime(detail.order.slotStart) }}</el-descriptions-item>
          <el-descriptions-item label="金额">￥{{ detail.order.payAmount }}</el-descriptions-item>
        </el-descriptions>
        <el-divider>状态流转</el-divider>
        <el-timeline>
          <el-timeline-item v-for="log in detail.logs" :key="log.id" :timestamp="formatTime(log.createTime)">
            {{ log.remark }}
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  staffAccept, staffArrive, staffComplete, staffOrderDetail, staffOrders, staffProfile,
  staffSettlements, staffStart
} from '../api'

const profile = ref({})
const settlement = ref({})
const rows = ref([])
const status = ref(2)
const detailVisible = ref(false)
const detail = ref(null)
const completeVisible = ref(false)
const completeForm = ref({ petCondition: '正常', beforePhotos: '', afterPhotos: '', report: '' })
const currentOrder = ref(null)

// 订单状态码与后端 OrderService 常量一致（0 待支付 … 10 已关闭），只做文案/标签颜色映射；
// 含义以后端 OrderService 为准。工作台按钮按状态显隐：2 接单、3 出发、4 打卡/完成。
const statusMap = {
  0: ['待支付', 'warning'], 1: ['待派单', 'warning'], 2: ['待接单', 'warning'], 3: ['待上门', 'primary'],
  4: ['服务中', 'primary'], 5: ['待确认', 'success'], 6: ['已完成', 'success'], 7: ['已取消', 'info'],
  8: ['退款中', 'danger'], 9: ['已退款', 'info'], 10: ['已关闭', 'info']
}
const statusText = (s) => (statusMap[s] ? statusMap[s][0] : '未知')
const statusTag = (s) => (statusMap[s] ? statusMap[s][1] : 'info')
const sizeText = (s) => ({ S: '小型', M: '中型', L: '大型' }[s] || s)
const formatTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '-')

// GET /api/staff/orders：服务者视角的订单分页列表，按 status 过滤（默认只看待接单 2）
async function load() {
  const params = { current: 1, size: 50 }
  if (status.value !== -1) params.status = status.value
  rows.value = (await staffOrders(params)).data.records || []
}

// 接单（仅 WAIT_ACCEPT=2 可见）：后端把状态从待接单推到 WAIT_VISIT=3 待上门
async function accept(order) {
  await staffAccept(order.id)
  ElMessage.success('已接单')
  await load()
}

// 出发上门：WAIT_VISIT=3 → SERVING=4，记录开始履约
async function start(order) {
  await staffStart(order.id)
  ElMessage.success('已出发')
  await load()
}

// 到达打卡：只记录到达时间与位置，状态仍停在 SERVING=4，不推进状态机
async function arrive(order) {
  await staffArrive(order.id)
  ElMessage.success('到达打卡成功')
}

function openComplete(order) {
  currentOrder.value = order
  completeForm.value = { petCondition: '正常', beforePhotos: '', afterPhotos: '', report: '' }
  completeVisible.value = true
}

// 完成服务：提交服务报告/照片，SERVING=4 → WAIT_CONFIRM=5，之后由用户确认完成
async function doComplete() {
  if (!completeForm.value.report) {
    ElMessage.warning('请填写服务报告')
    return
  }
  await staffComplete(currentOrder.value.id, completeForm.value)
  ElMessage.success('服务已完成，等待用户确认')
  completeVisible.value = false
  await load()
}

async function openDetail(order) {
  detail.value = (await staffOrderDetail(order.id)).data
  detailVisible.value = true
}

onMounted(async () => {
  const [p, s] = await Promise.all([staffProfile(), staffSettlements()])
  profile.value = p.data || {}
  settlement.value = s.data || {}
  await load()
})
</script>

<style scoped>
.staff { display: flex; flex-direction: column; gap: 14px; }
.profile { display: flex; align-items: center; gap: 14px; }
.avatar {
  width: 46px; height: 46px; border-radius: 50%;
  background: linear-gradient(135deg, #409eff, #79bbff);
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-size: 20px; font-weight: 600;
}
.profile-info { flex: 1; }
.name { font-weight: 600; margin-bottom: 4px; }
.income { text-align: right; }
.head { display: flex; justify-content: space-between; align-items: center; }
.order-card { border: 1px solid #ebeef5; border-radius: 10px; padding: 14px; margin-bottom: 12px; }
.order-head { display: flex; justify-content: space-between; align-items: center; }
.order-no { font-weight: 600; }
.ml8 { margin-left: 8px; }
.order-body { display: flex; justify-content: space-between; margin: 10px 0; }
.service { font-weight: 600; margin-bottom: 4px; }
.actions { display: flex; gap: 8px; }
.empty { color: #909399; text-align: center; padding: 30px 0; }
</style>
