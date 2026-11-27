package com.haust.petcare.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 集中式配置绑定类：把 application.yml 里 petcare.* 的配置映射成强类型对象。
 * <p>
 * 架构位置：@ConfigurationProperties(prefix = "petcare") + @Component，启动时由 Spring Boot 宽松绑定
 * （yml 的 kebab-case 自动对应驼峰字段，如 sms-ttl-minutes → smsTtlMinutes），各 Service 注入本类读取。
 * 按业务域分 6 组：门店 / 认证 / 订单 / 结算 / AI / 支付；字段注释写明对应 yml key、默认值与生产注意。
 * 生产重点：dev-login-enabled 必须关闭，internal-token / pay.secret / ai.api-key 必须换成强密钥且不要硬编码提交。
 */
@Data
@Component
@ConfigurationProperties(prefix = "petcare")
public class PetcareProperties {

    /** 门店：坐标与里程计费（petcare.store.*） */
    private Store store = new Store();
    /** 认证：验证码 / token / 开发者登录（petcare.auth.*） */
    private Auth auth = new Auth();
    /** 订单：支付超时 / 自动确认 / 改约次数（petcare.order.*） */
    private Order order = new Order();
    /** 结算：平台抽成与 T+N 可提现天数（petcare.settlement.*） */
    private Settlement settlement = new Settlement();
    /** AI：大模型、Python 服务、内部令牌与并发（petcare.ai.*） */
    private Ai ai = new Ai();
    /** 支付：模拟网关签名密钥（petcare.pay.*） */
    private Pay pay = new Pay();

    /**
     * 门店基础信息（计价业务域）：PriceService 用经纬度算上门距离与里程费，StatsService 用名称展示看板。
     * 对应 petcare.store.*；lat/lng 是 double 基本类型，未配置时默认 0.0，生产必须填门店真实坐标否则距离算错。
     */
    @Data
    public static class Store {
        /** 门店名称；key: petcare.store.name，默认 null */
        private String name;
        /** 门店纬度；key: petcare.store.lat，默认 0.0 */
        private double lat;
        /** 门店经度；key: petcare.store.lng，默认 0.0；与 lat 成对配置 */
        private double lng;
        /** 免费里程（公里）；key: petcare.store.free-km，默认 5；范围内不收里程费 */
        private int freeKm = 5;
        /** 超出免费里程后每公里费用（元）；key: petcare.store.per-km-fee，默认 5 */
        private int perKmFee = 5;
    }

    /**
     * 认证配置（鉴权链路业务域），被 AuthService / AuthInterceptor 读取。
     * 对应 petcare.auth.*；时间单位写在字段名里（minutes / seconds）避免歧义。
     * 生产重点：dev-login-enabled 必须为 false；token / 验证码 TTL 不宜过长，防止令牌与验证码被滥用。
     */
    @Data
    public static class Auth {
        /** 登录令牌有效期（分钟）；key: petcare.auth.token-ttl-minutes，默认 120；
         *  AuthInterceptor 每次有效请求把 Redis TTL 重置成该值，实现滑动过期 */
        private long tokenTtlMinutes = 120;
        // key: petcare.auth.sms-ttl-minutes，默认 5；生产建议 ≤10 分钟，过长增大被爆破风险
        /** 验证码有效期（分钟） */
        private long smsTtlMinutes = 5;
        // key: petcare.auth.sms-interval-seconds，默认 60 秒，0 表示关闭限流；生产务必保持开启以防短信轰炸
        /** 同一手机号两次发送的最小间隔（秒），服务端 SETNX 限流；0 表示关闭 */
        private long smsIntervalSeconds = 60;
        // key: petcare.auth.dev-login-enabled，默认 false；仅 application-local.yml 开启，生产必须删除或设为 false
        /** 开发者免验证码登录，仅本地/演示环境开启，生产必须关闭 */
        private boolean devLoginEnabled = false;
    }

    /**
     * 订单时限配置（订单状态机业务域），被 OrderService / ScheduledJobs 读取。
     * 对应 petcare.order.*；决定超时关单、自动确认、改约次数等阈值。
     */
    @Data
    public static class Order {
        /** 未支付订单超时时间（分钟）；key: petcare.order.pay-timeout-minutes，默认 15；
         *  下单时写入 payExpireAt，closeTimeoutOrders 定时任务据此关单并释放时间槽 */
        private int payTimeoutMinutes = 15;
        /** 派单超时阈值（分钟）；key: petcare.order.dispatch-timeout-minutes，默认 5；
         *  当前业务代码未直接读取该值，属于预留配置项 */
        private int dispatchTimeoutMinutes = 5;
        /** 服务完成后用户未确认、系统自动确认的小时数；key: petcare.order.auto-confirm-hours，默认 24；
         *  autoConfirm 定时任务据此把 WAIT_CONFIRM 推进到 FINISHED 并生成结算单 */
        private int autoConfirmHours = 24;
        /** 每单允许改约次数上限；key: petcare.order.reschedule-limit，默认 1；OrderService.reschedule 校验 */
        private int rescheduleLimit = 1;
    }

    /**
     * 结算配置（资金业务域），被 SettlementService 读取。
     * 对应 petcare.settlement.*；订单完成后生成结算单，T+N 到期由 releaseDue 定时任务转为可提现。
     */
    @Data
    public static class Settlement {
        /** 平台抽成比例；key: petcare.settlement.commission-rate，默认 0.15（15%）；
         *  服务者收入 = 实付 × (1 - 抽成)，生产调整该比例会改变新结算单口径，需谨慎 */
        private double commissionRate = 0.15;
        /** 结算周期 T+N 天数；key: petcare.settlement.settle-days，默认 7；
         *  写入结算单 settleTime，releaseDue 定时任务据此把 status 0 改为 1（可提现） */
        private int settleDays = 7;
    }

    /**
     * AI 服务配置（AI 助手业务域）：一半给 Java 直连大模型用（AiService），一半给调 Python Agent 用（AiGatewayService）。
     * 对应 petcare.ai.*；enabled 决定是否优先走 Python Agent，调用失败或未启用则降级到本地 RAG
     * （apiKey 非空走 DeepSeek，为空用模板回答）。
     * 生产重点：api-key 用环境变量注入（application.yml 里是 ${DEEPSEEK_API_KEY:}）、不要硬编码；
     * internal-token 默认值只是占位，必须改成高强度随机串并与 Python 侧一致，否则 /internal/** 形同不设防。
     */
    @Data
    public static class Ai {
        /** DeepSeek API Key；key: petcare.ai.api-key，默认空（为空时 AiService 降级为检索/模板回答） */
        private String apiKey;
        /** 大模型接口地址；key: petcare.ai.base-url，默认 https://api.deepseek.com */
        private String baseUrl = "https://api.deepseek.com";
        /** 模型名；key: petcare.ai.model，默认 deepseek-chat */
        private String model = "deepseek-chat";
        /** 本机 Semaphore 许可数；key: petcare.ai.max-concurrency，默认 4；
         *  限制同时进入 chat 的请求数，注意这是单实例内的限流，多实例部署时总并发会按实例数放大 */
        private int maxConcurrency = 4;

        // key: petcare.ai.enabled，默认 false；仅 application-local.yml 开启，关闭或调用失败会降级到本地 RAG
        /** 是否启用 Python AI 服务；关闭时走本地规则 RAG */
        private boolean enabled = false;
        // key: petcare.ai.service-url，默认 http://localhost:8000；生产填 Docker 内网服务名，勿暴露公网
        /** Python FastAPI 服务地址 */
        private String serviceUrl = "http://localhost:8000";
        // key: petcare.ai.internal-token，默认 petcare-internal-token（占位弱口令）；
        // 双向使用：InternalTokenFilter 校验入站请求、AiGatewayService 调 Python 时写入 X-Internal-Token，生产必须改强令牌
        /** Java 内部接口与 Python 服务之间共享的令牌 */
        private String internalToken = "petcare-internal-token";
        /** 调 Python 服务的连接超时（毫秒）；key: petcare.ai.service-connect-timeout-ms，默认 2000 */
        private int serviceConnectTimeoutMs = 2000;
        /** 调 Python 服务的读取超时（毫秒）；key: petcare.ai.service-read-timeout-ms，默认 15000；
         *  超时后 gateway 返回 null，AiService 继续走本地 RAG，是降级链路的一环 */
        private int serviceReadTimeoutMs = 15000;
    }

    /**
     * 支付配置（支付业务域），被 PaymentService.sign 读取，用于生成与校验回调签名。
     * 对应 petcare.pay.*；本项目是模拟网关，真实接入时密钥必须放进密钥管理系统而非配置文件。
     */
    @Data
    public static class Pay {
        // key: petcare.pay.secret，默认 petcare-mock-secret（占位）；
        // 参与 MD5(payNo|amount|status|secret) 签名，生产用环境变量 ${PAY_SECRET} 覆盖并定期轮换
        /** 模拟网关签名密钥；真实接入时应放在密钥管理系统 */
        private String secret = "petcare-mock-secret";
    }
}
