<template>
  <!--
    预约服务页（下单主流程）：宠物/地址档案 → 选服务 → 查可约时间槽 → 五段价格试算 → 提交订单并支付。
    主要交互：四个卡片分步选择，任一影响价格的选项变化都会自动重新试算；弹窗新增宠物/地址；提交后询问是否立即支付。
    调用接口：listPets/listAddresses/listServices/listAddons、listSlots、quotePrice、
             createPet/createAddress、createOrder、createPayment、mockPay。
  -->
  <div class="home">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">1. 选择宠物与地址</span>
          <div>
            <el-button size="small" @click="petDialog = true">+ 添加宠物</el-button>
            <el-button size="small" @click="addressDialog = true">+ 添加地址</el-button>
          </div>
        </div>
      </template>
      <el-row :gutter="16">
        <el-col :span="8">
          <div class="label">宠物</div>
          <el-select v-model="petId" style="width: 100%" placeholder="请选择宠物">
            <el-option v-for="p in pets" :key="p.id" :label="`${p.name}（${speciesText(p.species)} ${p.weightKg || '-'}kg）`"
                       :value="p.id" />
          </el-select>
        </el-col>
        <el-col :span="8">
          <div class="label">服务地址</div>
          <el-select v-model="addressId" style="width: 100%" placeholder="请选择地址">
            <el-option v-for="a in addresses" :key="a.id" :label="`${a.district} ${a.detail}`" :value="a.id" />
          </el-select>
        </el-col>
        <el-col :span="8">
          <div class="label">服务日期</div>
          <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
                          :disabled-date="disabledDate" style="width: 100%" />
        </el-col>
      </el-row>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header><span class="card-title">2. 选择服务</span></template>
      <div class="services">
        <div v-for="s in services" :key="s.id" class="service" :class="{ active: serviceId === s.id }"
             @click="serviceId = s.id">
          <div class="service-name">{{ s.name }}</div>
          <div class="service-desc">{{ s.description }}</div>
          <div class="service-meta">
            <span class="price">￥{{ priceOf(s) }}</span>
            <span class="muted">{{ s.durationMin }} 分钟</span>
          </div>
        </div>
      </div>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">3. 选择时间</span>
          <span class="muted">每个时段一位服务者，先到先得</span>
        </div>
      </template>
      <div v-if="slots.length === 0" class="empty">当天暂无可约时间，换一天试试</div>
      <div v-else class="slots">
        <div v-for="s in slots" :key="s.id" class="slot" :class="{ active: slotId === s.id }"
             @click="slotId = s.id">
          <div class="slot-time">{{ timeOf(s.slotStart) }}</div>
          <div class="muted">{{ s.staffName }} · {{ s.staffRating }}分</div>
        </div>
      </div>
    </el-card>

    <el-card class="page-card" shadow="never">
      <template #header><span class="card-title">4. 附加项与试算</span></template>
      <el-checkbox-group v-model="addonCodes">
        <el-checkbox v-for="a in addons" :key="a.code" :label="a.code">
          {{ a.name }} +￥{{ a.price }}
        </el-checkbox>
      </el-checkbox-group>
      <div style="margin-top: 10px">
        <el-checkbox v-model="urgent">加急上门（+30%）</el-checkbox>
      </div>

      <el-divider />
      <div v-loading="quoting" class="quote">
        <template v-if="quote">
          <div class="quote-row"><span>服务费</span><span>￥{{ quote.basePrice }}</span></div>
          <div class="quote-row"><span>附加项</span><span>￥{{ quote.addonAmount }}</span></div>
          <div class="quote-row"><span>距离费（{{ quote.distanceKm }}km）</span><span>￥{{ quote.distanceFee }}</span></div>
          <div class="quote-row" v-if="quote.nightFee > 0"><span>夜间服务费</span><span>￥{{ quote.nightFee }}</span></div>
          <div class="quote-row" v-if="quote.urgentFee > 0"><span>加急费</span><span>￥{{ quote.urgentFee }}</span></div>
          <div class="quote-row total"><span>合计</span><span>￥{{ quote.total }}</span></div>
        </template>
        <div v-else class="muted">选择服务、宠物、地址和时间后自动试算</div>
      </div>

      <el-button type="primary" size="large" style="width: 100%; margin-top: 14px"
                 :disabled="!canSubmit" :loading="submitting" @click="submit">
        提交订单
      </el-button>
    </el-card>

    <el-dialog v-model="petDialog" title="添加宠物" width="460px">
      <el-form label-width="80px">
        <el-form-item label="名字"><el-input v-model="petForm.name" /></el-form-item>
        <el-form-item label="种类">
          <el-select v-model="petForm.species" style="width: 100%">
            <el-option label="猫" value="CAT" /><el-option label="狗" value="DOG" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="品种"><el-input v-model="petForm.breed" /></el-form-item>
        <el-form-item label="体重(kg)"><el-input-number v-model="petForm.weightKg" :min="0.5" :max="80" :step="0.5" /></el-form-item>
        <el-form-item label="生日">
          <el-date-picker v-model="petForm.birthday" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="过敏史"><el-input v-model="petForm.allergies" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="petForm.notes" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="petDialog = false">取消</el-button>
        <el-button type="primary" @click="savePet">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addressDialog" title="添加地址" width="460px">
      <el-form label-width="80px">
        <el-form-item label="联系人"><el-input v-model="addressForm.contact" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="addressForm.phone" maxlength="11" /></el-form-item>
        <el-form-item label="城市"><el-input v-model="addressForm.city" /></el-form-item>
        <el-form-item label="区"><el-input v-model="addressForm.district" /></el-form-item>
        <el-form-item label="详细地址"><el-input v-model="addressForm.detail" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addressDialog = false">取消</el-button>
        <el-button type="primary" @click="saveAddress">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createAddress, createOrder, createPayment, createPet, listAddresses, listAddons,
  listPets, listServices, listSlots, mockPay, quotePrice
} from '../api'

const pets = ref([])
const addresses = ref([])
const services = ref([])
const addons = ref([])
const slots = ref([])

const petId = ref(null)
const addressId = ref(null)
const serviceId = ref(null)
const slotId = ref(null)
const date = ref(new Date(Date.now() + 86400000).toISOString().slice(0, 10))
const addonCodes = ref([])
const urgent = ref(false)
const quote = ref(null)
const quoting = ref(false)
const submitting = ref(false)

const petDialog = ref(false)
const addressDialog = ref(false)
const petForm = ref({ species: 'CAT', weightKg: 4 })
const addressForm = ref({ city: '杭州市', district: '西湖区' })

const canSubmit = computed(() => petId.value && addressId.value && serviceId.value && slotId.value)

function speciesText(s) {
  return { CAT: '猫', DOG: '狗', OTHER: '其他' }[s] || s
}
// 列表里的价格只是按体重预估的展示价，真正应付金额以 quotePrice 的试算结果为准
function priceOf(item) {
  const pet = pets.value.find((p) => p.id === petId.value)
  if (!pet || !pet.weightKg) return item.priceM
  if (pet.weightKg <= 10) return item.priceS
  if (pet.weightKg <= 25) return item.priceM
  return item.priceL
}
function timeOf(value) {
  return String(value).replace('T', ' ').slice(11, 16)
}
function disabledDate(d) {
  return d.getTime() < Date.now() - 86400000
}

async function loadBase() {
  const [p, a, s, ad] = await Promise.all([listPets(), listAddresses(), listServices(), listAddons()])
  pets.value = p.data || []
  addresses.value = a.data || []
  services.value = s.data || []
  addons.value = ad.data || []
  if (!petId.value && pets.value.length) petId.value = pets.value[0].id
  if (!addressId.value && addresses.value.length) addressId.value = addresses.value[0].id
  if (!serviceId.value && services.value.length) serviceId.value = services.value[0].id
}

// GET /api/slots：按日期 + 宠物查可约时间槽（每个时段只挂一位服务者，先到先得）
async function loadSlots() {
  slotId.value = null
  if (!petId.value || !date.value) return
  const res = await listSlots(date.value, petId.value)
  slots.value = res.data || []
}

// POST /api/orders/price：把当前选择提交给后端做五段计价，返回的分项金额直接渲染到「附加项与试算」卡片
async function refreshQuote() {
  if (!petId.value || !addressId.value || !serviceId.value) return
  quoting.value = true
  try {
    const res = await quotePrice({
      serviceId: serviceId.value, petId: petId.value, addressId: addressId.value,
      slotId: slotId.value, addonCodes: addonCodes.value, urgent: urgent.value
    })
    quote.value = res.data
  } catch (e) {
    quote.value = null
  } finally {
    quoting.value = false
  }
}

// 下单：带 requestId 让后端幂等，防重复提交；创建成功后询问是否立即
// 「创建支付单 → 模拟支付」，选「稍后支付」也不阻塞，最后统一跳到订单页
async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const res = await createOrder({
      serviceId: serviceId.value, petId: petId.value, addressId: addressId.value,
      slotId: slotId.value, addonCodes: addonCodes.value, urgent: urgent.value,
      requestId: crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random()
    })
    const order = res.data
    try {
      await ElMessageBox.confirm(
        `订单 ${order.orderNo} 已创建，应付 ￥${order.payAmount}。现在支付吗？`,
        '订单创建成功',
        { confirmButtonText: '立即支付', cancelButtonText: '稍后支付', type: 'success' }
      )
      const pay = await createPayment(order.id)
      await mockPay(pay.data.payNo)
      ElMessage.success('支付成功，服务者将尽快接单')
    } catch (e) {
      // 用户选择稍后支付
    }
    location.href = '/orders'
  } finally {
    submitting.value = false
  }
}

async function savePet() {
  if (!petForm.value.name) {
    ElMessage.warning('请填写宠物名字')
    return
  }
  await createPet(petForm.value)
  petDialog.value = false
  petForm.value = { species: 'CAT', weightKg: 4 }
  await loadBase()
  ElMessage.success('已添加宠物')
}

async function saveAddress() {
  if (!addressForm.value.detail) {
    ElMessage.warning('请填写详细地址')
    return
  }
  await createAddress(addressForm.value)
  addressDialog.value = false
  addressForm.value = { city: '杭州市', district: '西湖区' }
  await loadBase()
  ElMessage.success('已添加地址')
}

// 选宠物 / 改日期 → 重查时间槽；影响价格的任一选项变化 → 重新试算（deep 才能监听到 addonCodes 数组内部变化）
watch([petId, date], loadSlots)
watch([petId, addressId, serviceId, slotId, addonCodes, urgent], refreshQuote, { deep: true })

onMounted(async () => {
  await loadBase()
  await loadSlots()
})
</script>

<style scoped>
.home { display: flex; flex-direction: column; gap: 14px; }
.page-card { border-radius: 10px; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-weight: 600; }
.label { font-size: 12px; color: #909399; margin-bottom: 6px; }
.services { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
.service {
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 14px;
  cursor: pointer;
  transition: all 0.2s;
}
.service:hover { border-color: #a0cfff; }
.service.active { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.12); }
.service-name { font-weight: 600; margin-bottom: 6px; }
.service-desc { font-size: 12px; color: #909399; min-height: 34px; }
.service-meta { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.slots { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; }
.slot {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 10px;
  cursor: pointer;
  text-align: center;
}
.slot.active { border-color: #409eff; background: #ecf5ff; }
.slot-time { font-weight: 600; margin-bottom: 4px; }
.empty { color: #909399; font-size: 13px; }
.quote-row { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 6px; }
.quote-row.total { font-size: 16px; font-weight: 700; color: #f56c6c; margin-top: 8px; }
</style>
