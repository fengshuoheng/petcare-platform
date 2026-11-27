<template>
  <!--
    运营后台（管理侧）：数据看板 + 订单/退款/服务/投诉四类管理。
    主要交互：按状态与关键词查订单、改派服务者、平台取消订单；退款审批（同意/拒绝）；
    服务项新增/编辑与上下架；投诉工单处理。
    调用接口：adminStats、adminOrders、orderDetail、reassignOrder、cancelOrder、listStaffs、
             adminRefunds/approveRefund/rejectRefund、adminServices/saveService、adminComplaints/resolveComplaint。
  -->
  <div class="admin">
    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane label="数据看板" name="stats">
        <el-row :gutter="14">
          <el-col :span="6" v-for="item in statCards" :key="item.label">
            <el-card class="stat-card" shadow="never">
              <div class="muted">{{ item.label }}</div>
              <div class="stat-value">{{ item.value }}</div>
            </el-card>
          </el-col>
        </el-row>
        <el-card class="page-card" shadow="never" style="margin-top: 14px">
          <template #header><span class="card-title">订单状态分布</span></template>
          <div class="dist">
            <el-tag v-for="(count, key) in stats.statusDistribution" :key="key" type="info" class="dist-tag">
              {{ statusText(Number(key)) }}：{{ count }}
            </el-tag>
            <span v-if="!stats.statusDistribution" class="muted">暂无数据</span>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="订单管理" name="orders">
        <el-card class="page-card" shadow="never">
          <div class="toolbar">
            <el-select v-model="orderStatus" size="small" style="width: 140px" @change="loadOrders" clearable placeholder="全部状态">
              <el-option v-for="s in [0,1,2,3,4,5,6,7,8,9,10]" :key="s" :label="statusText(s)" :value="s" />
            </el-select>
            <el-input v-model="keyword" size="small" style="width: 200px" placeholder="订单号/宠物名" @keyup.enter="loadOrders" />
            <el-button size="small" @click="loadOrders">查询</el-button>
          </div>
          <el-table :data="orders" size="small" border>
            <el-table-column prop="orderNo" label="订单号" width="190" />
            <el-table-column prop="serviceName" label="服务" width="140" />
            <el-table-column prop="petName" label="宠物" width="90" />
            <el-table-column label="预约时间" width="150">
              <template #default="{ row }">{{ formatTime(row.slotStart) }}</template>
            </el-table-column>
            <el-table-column label="金额" width="90">
              <template #default="{ row }">￥{{ row.payAmount }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
                <el-button link type="warning" size="small" @click="openReassign(row)">改派</el-button>
                <el-button link type="danger" size="small" @click="cancelOrder(row)">取消</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="退款审批" name="refunds">
        <el-card class="page-card" shadow="never">
          <el-table :data="refunds" size="small" border>
            <el-table-column prop="refundNo" label="退款单号" width="220" />
            <el-table-column prop="orderId" label="订单ID" width="90" />
            <el-table-column prop="amount" label="金额" width="100" />
            <el-table-column prop="reason" label="原因" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="refundTag(row.status)">{{ refundText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160">
              <template #default="{ row }">
                <template v-if="row.status === 0">
                  <el-button link type="success" size="small" @click="approve(row)">同意</el-button>
                  <el-button link type="danger" size="small" @click="reject(row)">拒绝</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="服务管理" name="services">
        <el-card class="page-card" shadow="never">
          <div class="toolbar">
            <el-button size="small" type="primary" @click="openService()">新增服务</el-button>
          </div>
          <el-table :data="services" size="small" border>
            <el-table-column prop="name" label="服务" width="160" />
            <el-table-column prop="category" label="分类" width="90" />
            <el-table-column label="小型/中型/大型" width="200">
              <template #default="{ row }">￥{{ row.priceS }} / ￥{{ row.priceM }} / ￥{{ row.priceL }}</template>
            </el-table-column>
            <el-table-column prop="durationMin" label="时长(分)" width="90" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? '上架' : '下架' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openService(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="投诉工单" name="complaints">
        <el-card class="page-card" shadow="never">
          <el-table :data="complaints" size="small" border>
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column prop="content" label="内容" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 2 ? 'success' : 'warning'">{{ complaintText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button v-if="row.status !== 2" link type="primary" size="small" @click="openResolve(row)">处理</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="detailVisible" title="订单详情" size="460px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">{{ detail.order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusText }}</el-descriptions-item>
          <el-descriptions-item label="服务者">{{ detail.staff ? detail.staff.name : '待指派' }}</el-descriptions-item>
          <el-descriptions-item label="金额">￥{{ detail.order.payAmount }}</el-descriptions-item>
          <el-descriptions-item label="地址">{{ detail.order.addressSnapshot }}</el-descriptions-item>
        </el-descriptions>
        <el-divider>状态流转</el-divider>
        <el-timeline>
          <el-timeline-item v-for="log in detail.logs" :key="log.id" :timestamp="formatTime(log.createTime)">
            {{ log.remark }}
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

    <el-dialog v-model="reassignVisible" title="改派服务者" width="360px">
      <el-select v-model="newStaffId" style="width: 100%" placeholder="选择服务者">
        <el-option v-for="s in staffs" :key="s.id" :label="`${s.name}（${s.rating}分）`" :value="s.id" />
      </el-select>
      <template #footer>
        <el-button @click="reassignVisible = false">取消</el-button>
        <el-button type="primary" @click="doReassign">确认改派</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="serviceVisible" :title="serviceForm.id ? '编辑服务' : '新增服务'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="serviceForm.name" /></el-form-item>
        <el-form-item label="分类"><el-input v-model="serviceForm.category" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="serviceForm.description" type="textarea" /></el-form-item>
        <el-form-item label="时长(分)"><el-input-number v-model="serviceForm.durationMin" :min="30" :max="240" /></el-form-item>
        <el-form-item label="小型价"><el-input-number v-model="serviceForm.priceS" :min="0" /></el-form-item>
        <el-form-item label="中型价"><el-input-number v-model="serviceForm.priceM" :min="0" /></el-form-item>
        <el-form-item label="大型价"><el-input-number v-model="serviceForm.priceL" :min="0" /></el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="serviceForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="serviceVisible = false">取消</el-button>
        <el-button type="primary" @click="saveServiceForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resolveVisible" title="处理投诉" width="420px">
      <el-input v-model="resolveResult" type="textarea" :rows="4" placeholder="处理结果" />
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" @click="doResolve">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  adminComplaints, adminOrderDetail, adminOrders, adminRefunds, adminServices, adminStats, approveRefund,
  cancelOrder as cancelOrderApi, listStaffs, reassignOrder, rejectRefund,
  resolveComplaint, saveService
} from '../api'

const tab = ref('stats')
const stats = ref({})
const orders = ref([])
const refunds = ref([])
const services = ref([])
const complaints = ref([])
const staffs = ref([])
const orderStatus = ref(null)
const keyword = ref('')

const detailVisible = ref(false)
const detail = ref(null)
const reassignVisible = ref(false)
const newStaffId = ref(null)
const currentOrder = ref(null)
const serviceVisible = ref(false)
const serviceForm = ref({})
const resolveVisible = ref(false)
const resolveResult = ref('')
const currentComplaint = ref(null)

// 订单状态码与后端 OrderService 常量一致（0 待支付 … 10 已关闭），看板状态分布也按这套码展示；
// 含义以后端 OrderService 为准。筛选下拉直接枚举 0–10。
const statusMap = {
  0: ['待支付', 'warning'], 1: ['待派单', 'warning'], 2: ['待接单', 'warning'], 3: ['待上门', 'primary'],
  4: ['服务中', 'primary'], 5: ['待确认', 'success'], 6: ['已完成', 'success'], 7: ['已取消', 'info'],
  8: ['退款中', 'danger'], 9: ['已退款', 'info'], 10: ['已关闭', 'info']
}
const statusText = (s) => (statusMap[s] ? statusMap[s][0] : '未知')
const statusTag = (s) => (statusMap[s] ? statusMap[s][1] : 'info')
// 退款单、投诉单的 status 是各自实体的局部状态，不是订单状态码，含义同样以后端定义为准
const refundText = (s) => ({ 0: '待审批', 1: '已同意', 2: '已拒绝', 3: '已退款' }[s] || '未知')
const refundTag = (s) => ({ 0: 'warning', 1: 'primary', 2: 'danger', 3: 'success' }[s] || 'info')
const complaintText = (s) => ({ 0: '待受理', 1: '处理中', 2: '已解决', 3: '已关闭' }[s] || '未知')
const formatTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '-')

const statCards = computed(() => [
  { label: '今日订单', value: stats.value.todayOrders ?? 0 },
  { label: '今日 GMV', value: '￥' + (stats.value.todayGmv ?? 0) },
  { label: '总订单', value: stats.value.totalOrders ?? 0 },
  { label: '待处理退款', value: stats.value.pendingRefunds ?? 0 }
])

async function loadStats() {
  stats.value = (await adminStats()).data || {}
}
async function loadOrders() {
  const params = { current: 1, size: 50 }
  if (orderStatus.value !== null && orderStatus.value !== '') params.status = orderStatus.value
  if (keyword.value) params.keyword = keyword.value
  orders.value = (await adminOrders(params)).data.records || []
}
async function loadRefunds() {
  refunds.value = (await adminRefunds()).data || []
}
async function loadServices() {
  services.value = (await adminServices()).data || []
}
async function loadComplaints() {
  complaints.value = (await adminComplaints()).data || []
}

// 切到某个页签才加载对应数据，进入后台不会一次性把全部接口都打一遍
async function onTab(name) {
  if (name === 'orders') await loadOrders()
  if (name === 'refunds') await loadRefunds()
  if (name === 'services') await loadServices()
  if (name === 'complaints') await loadComplaints()
  if (name === 'stats') await loadStats()
}

// 管理后台查看任意订单详情：必须走 /admin/orders/{id}（后端 admin=true 跳过本人校验）；
// 若误用用户端 /orders/{id}，会因登录人不是订单本人而抛「订单不存在」
async function openDetail(row) {
  detail.value = (await adminOrderDetail(row.id)).data
  detailVisible.value = true
}

function openReassign(row) {
  currentOrder.value = row
  newStaffId.value = row.staffId
  reassignVisible.value = true
}

// 改派：把订单指派给指定服务者，后端把状态推回 WAIT_ACCEPT（2）等待该服务者接单
async function doReassign() {
  await reassignOrder(currentOrder.value.id, newStaffId.value)
  ElMessage.success('已改派')
  reassignVisible.value = false
  await loadOrders()
}

async function cancelOrder(row) {
  await ElMessageBox.confirm('确定取消该订单？', '取消订单', { type: 'warning' })
  await cancelOrderApi(row.id, '平台取消')
  ElMessage.success('已取消')
  await loadOrders()
}

// 同意退款（仅 status=0 待审批可见）：后端执行退款并回写订单状态；拒绝则走 rejectRefund
async function approve(row) {
  await approveRefund(row.id)
  ElMessage.success('已同意退款')
  await loadRefunds()
}

async function reject(row) {
  const { value } = await ElMessageBox.prompt('拒绝原因', '拒绝退款')
  await rejectRefund(row.id, value || '平台驳回')
  ElMessage.success('已拒绝')
  await loadRefunds()
}

function openService(row) {
  serviceForm.value = row ? { ...row } : { status: 1, durationMin: 60, priceS: 99, priceM: 129, priceL: 169 }
  serviceVisible.value = true
}

async function saveServiceForm() {
  await saveService(serviceForm.value)
  ElMessage.success('已保存')
  serviceVisible.value = false
  await loadServices()
}

function openResolve(row) {
  currentComplaint.value = row
  resolveResult.value = ''
  resolveVisible.value = true
}

// 处理投诉：提交处理结果并把工单 status 置为 2（已解决）
async function doResolve() {
  await resolveComplaint(currentComplaint.value.id, { result: resolveResult.value, status: 2 })
  ElMessage.success('已处理')
  resolveVisible.value = false
  await loadComplaints()
}

onMounted(async () => {
  const s = await listStaffs()
  staffs.value = s.data || []
  await loadStats()
})
</script>

<style scoped>
.admin { display: flex; flex-direction: column; gap: 12px; }
.stat-card { border-radius: 10px; }
.stat-value { font-size: 24px; font-weight: 700; margin-top: 6px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.dist { display: flex; flex-wrap: wrap; gap: 8px; }
.dist-tag { }
.card-title { font-weight: 600; }
</style>
