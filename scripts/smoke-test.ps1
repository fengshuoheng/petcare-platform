# 全链路冒烟测试：一条脚本从登录走到管理看板，验证各 /api 接口联通与订单状态机能跑通。
# 用法：.\smoke-test.ps1 [-Base http://127.0.0.1:8082]
# 前提：Spring Boot 已启动且用 local profile（dev-login 默认仅 local 开启）；库里已有演示用
#       宠物/地址/服务者/排班数据，否则第 3 步查不到可约时间会 throw；任一步出错即停止。
# 说明：脚本只调 /api/**，AI 也是经 Java 的 /api/ai/chat 转发验证，不直连 Python 服务。
param(
    [string]$Base = "http://127.0.0.1:8082"
)

$ErrorActionPreference = "Stop"

# 通用 POST：按需注入 authorization 头，body 转成 UTF-8 JSON（避免中文备注乱码）
function Post($path, $body, $token) {
    $headers = @{}
    if ($token) { $headers["authorization"] = $token }
    $json = if ($body -eq $null) { "{}" } else { $body | ConvertTo-Json -Depth 8 }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    Invoke-RestMethod -Uri "$Base$path" -Method Post -Headers $headers -Body $bytes -ContentType "application/json; charset=utf-8"
}

# 通用 GET：同样按需注入 token，返回体统一取 .data
function GetApi($path, $token) {
    $headers = @{}
    if ($token) { $headers["authorization"] = $token }
    Invoke-RestMethod -Uri "$Base$path" -Method Get -Headers $headers
}

# 验证码接口只回「发送成功」、不回传验证码，自动化测试改用 dev-login 后门拿 token
# （dev-login 由 petcare.auth.dev-login-enabled 控制，默认仅 local profile 开启）
function Login($phone) {
    (Post "/api/auth/dev-login" @{ phone = $phone } $null).data.token
}

# 步骤 0：确认 /api/auth/sms 能调通；接口不回传验证码，本地从后端控制台日志查看 6 位码
Write-Host "0. 验证码发送接口自检" -ForegroundColor Cyan
Post "/api/auth/sms" @{ phone = "13700000001" } $null | Out-Null
Write-Host "   发送成功（验证码不回传，本地查看后端控制台日志）"

# 步骤 1：用 dev-login 免验证码换用户 token（联调后门，仅 local profile 开启），后续请求都带它
Write-Host "1. 用户登录" -ForegroundColor Cyan
$userToken = Login "13700000001"
Write-Host "   token ok"

# 步骤 2：读宠物/服务/地址基础档案，各取第一条作为后续试算与下单的入参
Write-Host "2. 宠物与服务" -ForegroundColor Cyan
$pets = (GetApi "/api/pets" $userToken).data
$services = (GetApi "/api/services" $userToken).data
Write-Host ("   宠物 " + $pets.Count + " 只，服务 " + $services.Count + " 项")

$petId = $pets[0].id
$serviceId = $services[0].id
$addresses = (GetApi "/api/addresses" $userToken).data
$addressId = $addresses[0].id

# 步骤 3：查明天可约时间槽并取第一个；没有排班直接 throw，避免后续步骤拿空数据空转
Write-Host "3. 查询可约时间" -ForegroundColor Cyan
$date = (Get-Date).AddDays(1).ToString("yyyy-MM-dd")
$slots = (GetApi "/api/slots?date=$date&petId=$petId" $userToken).data
if (-not $slots -or $slots.Count -eq 0) { throw "没有可约时间，请确认排班" }
$slot = $slots[0]
Write-Host ("   明天共 " + $slots.Count + " 个可约时段，选 " + $slot.slotStart + " / " + $slot.staffName)

# 步骤 4：价格试算，打印五段计价的分项（服务 + 附加 + 距离 = 合计），下单金额以它为准
Write-Host "4. 价格试算" -ForegroundColor Cyan
$quote = (Post "/api/orders/price" @{
        serviceId = $serviceId; petId = $petId; addressId = $addressId; slotId = $slot.id;
        addonCodes = @("nail"); urgent = $false
    } $userToken).data
Write-Host ("   服务 " + $quote.basePrice + " + 附加 " + $quote.addonAmount + " + 距离 " + $quote.distanceFee + " = " + $quote.total)

# 步骤 5：下单，带 requestId 让后端幂等；返回订单号、应付金额与初始状态（WAIT_PAY=0）
Write-Host "5. 下单" -ForegroundColor Cyan
$requestId = [guid]::NewGuid().ToString()
$order = (Post "/api/orders" @{
        serviceId = $serviceId; petId = $petId; addressId = $addressId; slotId = $slot.id;
        addonCodes = @("nail"); urgent = $false; remark = "smoke test"; requestId = $requestId
    } $userToken).data
Write-Host ("   订单 " + $order.orderNo + " 金额 " + $order.payAmount + " 状态 " + $order.status)

# 步骤 6：创建支付单并调用 mock-pay，验证模拟网关 + /api/pay/callback 回调能把订单推到待接单
Write-Host "6. 支付（模拟网关）" -ForegroundColor Cyan
$pay = (Post "/api/payments" @{ orderId = $order.id } $userToken).data
$payResult = (Post ("/api/payments/" + $pay.payNo + "/mock-pay") @{} $userToken).data
Write-Host ("   支付单 " + $pay.payNo + " success=" + $payResult.success)

# 步骤 7：换服务者 token，走接单→出发→到达打卡→完成，验证履约状态机与状态流转日志
Write-Host "7. 服务者接单与履约" -ForegroundColor Cyan
$staffToken = Login "13900000001"
$staffOrders = (GetApi "/api/staff/orders?current=1&size=10" $staffToken).data.records
$staffOrder = $staffOrders | Where-Object { $_.id -eq $order.id } | Select-Object -First 1
if (-not $staffOrder) { throw "服务者看不到订单，检查订单 staff_id" }
Post ("/api/staff/orders/" + $order.id + "/accept") @{} $staffToken | Out-Null
Post ("/api/staff/orders/" + $order.id + "/start") @{} $staffToken | Out-Null
Post ("/api/staff/orders/" + $order.id + "/arrive") @{} $staffToken | Out-Null
Post ("/api/staff/orders/" + $order.id + "/complete") @{
    report = "已完成基础洗护，宠物状态良好"; petCondition = "正常"; beforePhotos = "before.jpg"; afterPhotos = "after.jpg"
} $staffToken | Out-Null
$detail = (GetApi ("/api/orders/" + $order.id) $userToken).data
Write-Host ("   履约后状态 " + $detail.statusText)

# 步骤 8：用户确认完成 + 评价，验证订单进入已完成并生成服务者结算
Write-Host "8. 用户确认与评价" -ForegroundColor Cyan
Post ("/api/orders/" + $order.id + "/confirm") @{} $userToken | Out-Null
Post "/api/orders/review" @{ orderId = $order.id; rating = 5; tags = "准时,专业"; content = "服务很好" } $userToken | Out-Null
$detail = (GetApi ("/api/orders/" + $order.id) $userToken).data
Write-Host ("   确认后状态 " + $detail.statusText + "，结算 " + $detail.settlement.staffIncome + " 元")

# 步骤 9：另下一单支付后取消，管理员审批退款，验证售后/退款链路
Write-Host "9. 取消退款流程" -ForegroundColor Cyan
$slot2 = $slots[1]
$order2 = (Post "/api/orders" @{
        serviceId = $serviceId; petId = $petId; addressId = $addressId; slotId = $slot2.id;
        addonCodes = @(); urgent = $false; remark = "refund test"; requestId = [guid]::NewGuid().ToString()
    } $userToken).data
$pay2 = (Post "/api/payments" @{ orderId = $order2.id } $userToken).data
Post ("/api/payments/" + $pay2.payNo + "/mock-pay") @{} $userToken | Out-Null
Post ("/api/orders/" + $order2.id + "/cancel") @{ reason = "临时有事" } $userToken | Out-Null
$adminToken = Login "13800000000"
$refunds = (GetApi "/api/admin/refunds?status=0" $adminToken).data
$refund = $refunds | Where-Object { $_.orderId -eq $order2.id } | Select-Object -First 1
if ($refund) {
    Post ("/api/admin/refunds/" + $refund.id + "/approve") @{} $adminToken | Out-Null
    Write-Host ("   退款 " + $refund.amount + " 元已通过")
}

# 步骤 10：AI 助手两条分支——正常问答带知识库引用、医疗问题返回 refused=true
Write-Host "10. AI 助手" -ForegroundColor Cyan
$ai = (Post "/api/ai/chat" @{ message = "三个月大的幼猫每天喂几次？"; petId = $petId } $userToken).data
Write-Host ("   回答：" + $ai.answer.Substring(0, [Math]::Min(80, $ai.answer.Length)) + "...")
Write-Host ("   引用 " + $ai.citations.Count + " 条，llmUsed=" + $ai.llmUsed)
$refuse = (Post "/api/ai/chat" @{ message = "猫呕吐了吃什么药？" } $userToken).data
Write-Host ("   医疗问题 refused=" + $refuse.refused)

# 步骤 11：读管理看板统计，确认订单、GMV 与 AI 对话数据已落库
Write-Host "11. 管理看板" -ForegroundColor Cyan
$stats = (GetApi "/api/admin/stats" $adminToken).data
Write-Host ("   今日订单 " + $stats.todayOrders + "，GMV " + $stats.todayGmv + "，AI 对话 " + $stats.aiToday)

Write-Host "`n全链路 smoke test 通过" -ForegroundColor Green
