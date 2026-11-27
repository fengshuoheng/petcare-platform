<template>
  <!--
    我的订单页（用户侧订单生命周期）：按状态筛选列表，按当前状态展示可执行操作。
    主要交互：去支付、取消、改约、催单（接单/上门/完成）、确认完成、评价、投诉；
    详情抽屉展示状态流转时间线、服务报告与结算信息。
    调用接口：listOrders、orderDetail、createPayment/mockPay、cancelOrder、rescheduleOrder、
             urgeOrder、confirmOrder、reviewOrder、complaintOrder、listSlots（改约时查时间槽）。
  -->
  <div class="orders">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="head">
          <span class="card-title">我的订单</span>
          <div class="filters">
            <el-select v-model="status" size="small" style="width: 140px" @change="load">
              <el-option label="全部" :value="-1" />
              <el-option label="待支付" :value="0" />
              <el-option label="待接单" :value="2" />
              <el-option label="待上门" :value="3" />
              <el-option label="服务中" :value="4" />
              <el-option label="待确认" :value="5" />
              <el-option label="已完成" :value="6" />
              <el-option label="已取消" :value="7" />
            </el-select>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <div v-if="rows.length === 0" class="empty">还没有订单，去「预约服务」下一单吧</div>

      <div v-for="o in rows" :key="o.id" class="order-card">
        <div class="order-head">
          <div>
            <span class="order-no">{{ o.orderNo }}</span>
            <el-tag size="small" :type="statusTag(o.status)" class="ml8">{{ statusText(o.status) }}</el-tag>
            <el-tag v-if="o.urgent" size="small" type="danger" class="ml8">加急</el-tag>
          </div>
          <div class="muted">{{ formatTime(o.createTime) }}</div>
        </div>
        <div class="order-body">
          <div class="info">
            <div class="service-name">{{ o.serviceName }} · {{ o.petName }}（{{ sizeText(o.petSize) }}）</div>
            <div class="muted">{{ o.addressSnapshot }}</div>
            <div class="muted">预约时间：{{ formatTime(o.slotStart) }}</div>
          </div>
          <div class="amount">
            <div class="price">￥{{ o.payAmount }}</div>
            <div class="muted">含附加 ￥{{ o.addonAmount }}</div>
          </div>
        </div>
        <div class="order-actions">
          <el-button size="small" @click="openDetail(o)">详情</el-button>
          <el-button v-if="o.status === 0" type="primary" size="small" @click="pay(o)">去支付</el-button>
          <el-button v-if="o.status === 0 || o.status === 2 || o.status === 3" size="small" @click="cancel(o)">取消</el-button>
          <el-button v-if="o.status === 2 || o.status === 3" size="small" @click="openReschedule(o)">改约</el-button>
          <el-button v-if="o.status === 2" size="small" @click="urge(o, 1)">催接单</el-button>
          <el-button v-if="o.status === 3" size="small" @click="urge(o, 2)">催上门</el-button>
          <el-button v-if="o.status === 4" size="small" @click="urge(o, 3)">催完成</el-button>
          <el-button v-if="o.status === 5" type="success" size="small" @click="confirm(o)">确认完成</el-button>
          <el-button v-if="o.status === 6" type="primary" size="small" plain @click="openReview(o)">评价</el-button>
          <el-button v-if="o.status >= 4 && o.status <= 6" size="small" type="danger" plain @click="openComplaint(o)">投诉</el-button>
        </div>
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="订单详情" size="480px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">{{ detail.order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusText }}</el-descriptions-item>
          <el-descriptions-item label="服务">{{ detail.order.serviceName }}</el-descriptions-item>
          <el-descriptions-item label="宠物">{{ detail.order.petName }}（{{ sizeText(detail.order.petSize) }}）</el-descriptions-item>
          <el-descriptions-item label="时间">{{ formatTime(detail.order.slotStart) }}</el-descriptions-item>
          <el-descriptions-item label="地址">{{ detail.order.addressSnapshot }}</el-descriptions-item>
          <el-descriptions-item label="金额">￥{{ detail.order.payAmount }}</el-descriptions-item>
          <el-descriptions-item label="服务者">{{ detail.staff ? detail.staff.name : '待指派' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.settlement" label="服务者结算">
            ￥{{ detail.settlement.staffIncome }}（平台抽成 ￥{{ detail.settlement.commission }}）
          </el-descriptions-item>
        </el-descriptions>
        <el-divider>状态流转</el-divider>
        <el-timeline>
          <el-timeline-item v-for="log in detail.logs" :key="log.id" :timestamp="formatTime(log.createTime)">
            {{ log.remark }}（{{ log.fromStatus }} → {{ log.toStatus }}）
          </el-timeline-item>
        </el-timeline>
        <template v-if="detail.record">
          <el-divider>服务报告</el-divider>
          <div class="record">{{ detail.record.report }}</div>
          <div class="muted">宠物状态：{{ detail.record.petCondition }}</div>
        </template>
      </template>
    </el-drawer>

    <el-dialog v-model="rescheduleVisible" title="改约" width="480px">
      <el-date-picker v-model="rescheduleDate" type="date" value-format="YYYY-MM-DD" :clearable="false"
                      @change="loadRescheduleSlots" />
      <div class="slots">
        <div v-for="s in rescheduleSlots" :key="s.id" class="slot" :class="{ active: newSlotId === s.id }"
             @click="newSlotId = s.id">
          <div>{{ timeOf(s.slotStart) }}</div>
          <div class="muted">{{ s.staffName }}</div>
        </div>
      </div>
      <template #footer>
        <el-button @click="rescheduleVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!newSlotId" @click="doReschedule">确认改约</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" title="评价服务" width="440px">
      <el-rate v-model="reviewForm.rating" />
      <el-input v-model="reviewForm.tags" placeholder="标签，如：准时,专业,耐心" style="margin-top: 12px" />
      <el-input v-model="reviewForm.content" type="textarea" :rows="3" placeholder="说说这次服务体验"
                style="margin-top: 12px" />
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" @click="doReview">提交评价</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="complaintVisible" title="提交投诉" width="440px">
      <el-select v-model="complaintForm.type" style="width: 100%">
        <el-option label="服务质量" value="服务质量" />
        <el-option label="服务态度" value="服务态度" />
        <el-option label="迟到/爽约" value="迟到爽约" />
        <el-option label="宠物受伤" value="宠物受伤" />
        <el-option label="乱收费" value="乱收费" />
      </el-select>
      <el-input v-model="complaintForm.content" type="textarea" :rows="4" placeholder="请描述问题"
                style="margin-top: 12px" />
      <template #footer>
        <el-button @click="complaintVisible = false">取消</el-button>
        <el-button type="primary" @click="doComplaint">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelOrder, complaintOrder, confirmOrder, createPayment, listOrders, listSlots,
  mockPay, orderDetail, rescheduleOrder, reviewOrder, urgeOrder
} from '../api'

const rows = ref([])
const status = ref(-1)
const detailVisible = ref(false)
const detail = ref(null)

const rescheduleVisible = ref(false)
const rescheduleDate = ref(new Date(Date.now() + 86400000).toISOString().slice(0, 10))
const rescheduleSlots = ref([])
const newSlotId = ref(null)
const currentOrder = ref(null)

const reviewVisible = ref(false)
const reviewForm = ref({ rating: 5, tags: '', content: '' })
const complaintVisible = ref(false)
const complaintForm = ref({ type: '服务质量', content: '' })

// 订单状态码与后端 OrderService 常量一致：0 待支付、1 待派单、2 待接单、3 待上门、4 服务中、
// 5 待确认、6 已完成、7 已取消、8 退款中、9 已退款、10 已关闭；这里只做文案与标签颜色映射，
// 状态码含义以后端 OrderService 为准。模板中各操作按钮的显隐按数值/区间判断（见 v-if）。
const statusMap = {
  0: ['待支付', 'warning'], 1: ['待派单', 'warning'], 2: ['待接单', 'warning'], 3: ['待上门', 'primary'],
  4: ['服务中', 'primary'], 5: ['待确认', 'success'], 6: ['已完成', 'success'], 7: ['已取消', 'info'],
  8: ['退款中', 'danger'], 9: ['已退款', 'info'], 10: ['已关闭', 'info']
}
const statusText = (s) => (statusMap[s] ? statusMap[s][0] : '未知')
const statusTag = (s) => (statusMap[s] ? statusMap[s][1] : 'info')
const sizeText = (s) => ({ S: '小型', M: '中型', L: '大型' }[s] || s)
const formatTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '-')
const timeOf = (v) => String(v).replace('T', ' ').slice(11, 16)

// GET /api/orders：带 status 分页查询，status=-1 表示不筛选（全部）
async function load() {
  const params = { current: 1, size: 50 }
  if (status.value !== -1) params.status = status.value
  rows.value = (await listOrders(params)).data.records || []
}

// 去支付（仅 WAIT_PAY=0 可见）：先 POST /api/payments 建支付单，再 mock-pay 模拟网关完成支付
async function pay(order) {
  const payRes = await createPayment(order.id)
  await mockPay(payRes.data.payNo)
  ElMessage.success('支付成功')
  await load()
}

async function cancel(order) {
  const { value } = await ElMessageBox.prompt('取消原因', '取消订单', {
    confirmButtonText: '确认取消', cancelButtonText: '再想想'
  })
  await cancelOrder(order.id, value || '用户取消')
  ElMessage.success('已取消')
  await load()
}

// 用户确认完成（仅 WAIT_CONFIRM=5 可见）：状态推到 FINISHED，后端同时生成服务者结算
async function confirm(order) {
  await confirmOrder(order.id)
  ElMessage.success('已确认完成')
  await load()
}

// 催单：type 1/2/3 分别对应催接单、催上门、催完成，由后端按订单当前状态校验是否允许催
async function urge(order, type) {
  await urgeOrder(order.id, type)
  ElMessage.success('已催单')
}

async function openDetail(order) {
  detail.value = (await orderDetail(order.id)).data
  detailVisible.value = true
}

async function openReschedule(order) {
  currentOrder.value = order
  newSlotId.value = null
  rescheduleVisible.value = true
  await loadRescheduleSlots()
}

async function loadRescheduleSlots() {
  const res = await listSlots(rescheduleDate.value, currentOrder.value.petId)
  rescheduleSlots.value = res.data || []
}

// 改约：把重新选中的 slotId 提交给 /api/orders/{id}/reschedule，后端校验当前状态可改约
async function doReschedule() {
  await rescheduleOrder(currentOrder.value.id, newSlotId.value)
  ElMessage.success('改约成功')
  rescheduleVisible.value = false
  await load()
}

function openReview(order) {
  currentOrder.value = order
  reviewForm.value = { rating: 5, tags: '', content: '' }
  reviewVisible.value = true
}

// 提交评价（仅 FINISHED=6）：rating/tags/content 随 orderId 发给 /api/orders/review
async function doReview() {
  await reviewOrder({ orderId: currentOrder.value.id, ...reviewForm.value })
  ElMessage.success('评价成功')
  reviewVisible.value = false
  await load()
}

function openComplaint(order) {
  currentOrder.value = order
  complaintForm.value = { type: '服务质量', content: '' }
  complaintVisible.value = true
}

// 提交售后投诉：/api/orders/complaint，生成投诉工单后由运营后台「投诉工单」页处理
async function doComplaint() {
  await complaintOrder({ orderId: currentOrder.value.id, ...complaintForm.value })
  ElMessage.success('投诉已提交，客服会尽快处理')
  complaintVisible.value = false
}

onMounted(load)
</script>

<style scoped>
.orders { display: flex; flex-direction: column; gap: 14px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.card-title { font-weight: 600; }
.filters { display: flex; gap: 8px; }
.order-card { border: 1px solid #ebeef5; border-radius: 10px; padding: 14px; margin-bottom: 12px; }
.order-head { display: flex; justify-content: space-between; align-items: center; }
.order-no { font-weight: 600; }
.ml8 { margin-left: 8px; }
.order-body { display: flex; justify-content: space-between; margin: 10px 0; }
.service-name { font-weight: 600; margin-bottom: 4px; }
.amount { text-align: right; }
.order-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.empty { color: #909399; text-align: center; padding: 30px 0; }
.slots { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 12px; }
.slot { border: 1px solid #ebeef5; border-radius: 8px; padding: 8px; text-align: center; cursor: pointer; }
.slot.active { border-color: #409eff; background: #ecf5ff; }
.record { background: #f8f9fb; border-radius: 8px; padding: 10px; font-size: 13px; line-height: 1.7; }
</style>
