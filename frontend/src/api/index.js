/**
 * 后端接口清单：按业务域分组，路径前缀统一为 /api（见 request.js 的 baseURL）。
 * 每个导出方法对应一个后端 Controller 接口，命名与 @*Mapping 对齐，便于对照接口讲解前端逻辑。
 */
import request from './request'

// —— 认证 /api/auth ——
// POST /api/auth/sms 只回「发送成功」，接口不返回验证码；本地联调从后端控制台日志查看 6 位码
// /login 用手机号 + 6 位验证码换 token，/me 取当前用户，/logout 退出；/dev-login 是配置开关控制的联调后门

/** 发送验证码：服务端生成并写入 Redis，响应不含验证码 */
export const sendSms = (phone) => request.post('/auth/sms', { phone })
export const login = (phone, code) => request.post('/auth/login', { phone, code })
/** 演示/联调：免验证码切换身份，生产环境需关闭后端开关 */
export const devLogin = (phone) => request.post('/auth/dev-login', { phone })
export const me = () => request.get('/auth/me')
export const logout = () => request.post('/auth/logout')

// —— 宠物档案 /api/pets（下单页选宠物）：GET 列表，POST 新增，PUT/DELETE 改删
export const listPets = () => request.get('/pets')
export const createPet = (data) => request.post('/pets', data)
export const updatePet = (id, data) => request.put(`/pets/${id}`, data)
export const deletePet = (id) => request.delete(`/pets/${id}`)

// —— 地址档案 /api/addresses（下单页选服务地址，价格试算的距离费由它的经纬度算）
export const listAddresses = () => request.get('/addresses')
export const createAddress = (data) => request.post('/addresses', data)

// —— 服务目录 /api：GET /services 服务项、/services/addons 附加项价格、/staffs 服务者列表
export const listServices = () => request.get('/services')
export const listAddons = () => request.get('/services/addons')
export const listStaffs = () => request.get('/staffs')

// —— 可约时间槽 GET /api/slots 与价格试算 POST /api/orders/price（五段计价，下单前先试算并展示明细）
export const listSlots = (date, petId) => request.get('/slots', { params: { date, petId } })
export const quotePrice = (data) => request.post('/orders/price', data)

// —— 订单 /api/orders：POST 下单、GET 列表/详情，cancel/reschedule/urge/confirm 推进状态，review/complaint 售后
// createOrder 的 data.requestId 用于后端幂等，防止重复提交生成两笔订单
export const createOrder = (data) => request.post('/orders', data)
export const listOrders = (params) => request.get('/orders', { params })
export const orderDetail = (id) => request.get(`/orders/${id}`)
export const cancelOrder = (id, reason) => request.post(`/orders/${id}/cancel`, { reason })
export const rescheduleOrder = (id, slotId) => request.post(`/orders/${id}/reschedule`, { slotId })
export const urgeOrder = (id, type) => request.post(`/orders/${id}/urge`, { type })
export const confirmOrder = (id) => request.post(`/orders/${id}/confirm`)
export const reviewOrder = (data) => request.post('/orders/review', data)
export const complaintOrder = (data) => request.post('/orders/complaint', data)
export const myComplaints = () => request.get('/orders/complaints')

// —— 支付 /api/payments：POST 创建支付单 + /{payNo}/mock-pay 模拟网关支付；
// 真正的 POST /api/pay/callback 在鉴权白名单里、由模拟网关回调，前端不直接调用
export const createPayment = (orderId) => request.post('/payments', { orderId })
export const mockPay = (payNo) => request.post(`/payments/${payNo}/mock-pay`)

// —— 服务者工作台 /api/staff：订单列表/详情、接单 accept、出发 start、到达打卡 arrive、完成 complete、结算 settlements
export const staffOrders = (params) => request.get('/staff/orders', { params })
export const staffOrderDetail = (id) => request.get(`/staff/orders/${id}`)
export const staffProfile = () => request.get('/staff/profile')
export const staffAccept = (id) => request.post(`/staff/orders/${id}/accept`)
export const staffStart = (id) => request.post(`/staff/orders/${id}/start`)
export const staffArrive = (id) => request.post(`/staff/orders/${id}/arrive`)
export const staffComplete = (id, data) => request.post(`/staff/orders/${id}/complete`, data)
export const staffSettlements = () => request.get('/staff/settlements')

// —— 通知 /api/notifications：消息列表与未读数
export const listNotifications = () => request.get('/notifications')
export const unreadCount = () => request.get('/notifications/unread')

// —— AI 助手 /api/ai：POST /chat 问答、GET /history 历史；chat 单独放宽到 60s 超时
// 注意：当前是普通 POST 一次性返回 JSON，不是 SSE 流式，前端拿到整段 answer/citations 再渲染
export const aiChat = (data) => request.post('/ai/chat', data, { timeout: 60000 })
export const aiHistory = (sessionId) => request.get('/ai/history', { params: { sessionId } })

// —— 管理后台 /api/admin：stats 看板、orders/refunds/services/complaints 管理与 reassign 改派
export const adminStats = () => request.get('/admin/stats')
export const adminOrders = (params) => request.get('/admin/orders', { params })
export const adminRefunds = (status) => request.get('/admin/refunds', { params: { status } })
export const approveRefund = (id) => request.post(`/admin/refunds/${id}/approve`)
export const rejectRefund = (id, reason) => request.post(`/admin/refunds/${id}/reject`, { reason })
export const adminServices = () => request.get('/admin/services')
export const saveService = (data) => request.post('/admin/services', data)
export const adminComplaints = () => request.get('/admin/complaints')
export const resolveComplaint = (id, data) => request.post(`/admin/complaints/${id}/resolve`, data)
export const reassignOrder = (id, staffId) => request.post(`/admin/orders/${id}/reassign`, { staffId })
// 管理后台订单详情：走 /admin/orders/{id}，后端以 admin=true 跳过用户归属校验（普通 /orders/{id} 会按本人校验报「订单不存在」）
export const adminOrderDetail = (id) => request.get(`/admin/orders/${id}`)
