package com.haust.petcare.auth;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.common.LoginUser;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.entity.AppUser;
import com.haust.petcare.mapper.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 认证领域服务：验证码的发放与校验、token 的签发与销毁、登录态还原。
 * <p>
 * 架构位置：介于 AuthController（HTTP 门面）与 Redis / AppUserMapper（存储）之间，是鉴权链路的核心。
 * 本项目不用 JWT，token 只是一个无含义的 UUID，登录态完全存在 Redis 的 petcare:token:{token} 里；
 * 好处是服务端删 key 即可主动踢人，代价是每次请求都要查一次 Redis（由 AuthInterceptor 完成）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 验证码本体：value 为 6 位数字，TTL 由 petcare.auth.sms-ttl-minutes 控制 */
    private static final String SMS_KEY = "petcare:sms:";
    /** 发送频率限制：SETNX 占位，TTL 由 petcare.auth.sms-interval-seconds 控制 */
    private static final String SMS_LIMIT_KEY = "petcare:sms:limit:";
    /** 登录令牌：key 为 petcare:token:{token}，value 为 LoginUser 的 JSON，TTL 由 petcare.auth.token-ttl-minutes 控制 */
    private static final String TOKEN_KEY = "petcare:token:";
    /** 6 位验证码的取值范围 0 ~ 999999 */
    private static final long CODE_MODULUS = 1_000_000L;

    private final AppUserMapper userMapper;
    private final StringRedisTemplate redis;
    private final PetcareProperties properties;

    public AuthService(AppUserMapper userMapper, StringRedisTemplate redis, PetcareProperties properties) {
        this.userMapper = userMapper;
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 发送短信验证码：服务端限流 → 生成 6 位随机码 → 写入 Redis(带 TTL) → 交给短信通道。
     *
     * 注意：验证码不通过 HTTP 响应返回，接口只回「发送成功」。
     * 本项目没有接入真实短信网关，用日志模拟发送；接入时把 log.info 那一行
     * 替换成 smsGateway.send(phone, code) 并删除日志，避免生产环境泄露验证码。
     */
    public void sendSms(String phone) {
        long interval = properties.getAuth().getSmsIntervalSeconds();
        if (interval > 0) {
            Boolean allowed;
            try {
                // SETNX + 过期时间：同一个手机号在 interval 秒内只能发一次，
                // 前端的 60s 倒计时只是体验，真正的防刷必须放在服务端。
                allowed = redis.opsForValue().setIfAbsent(SMS_LIMIT_KEY + phone, "1",
                        interval, TimeUnit.SECONDS);
            } catch (RedisConnectionFailureException e) {
                throw new BusinessException("Redis 连接失败，请确认 Redis 已启动且密码正确");
            }
            if (!Boolean.TRUE.equals(allowed)) {
                throw new BusinessException("验证码发送过于频繁，请 " + interval + " 秒后再试");
            }
        }

        String code = generateSmsCode();
        try {
            redis.opsForValue().set(SMS_KEY + phone, code,
                    properties.getAuth().getSmsTtlMinutes(), TimeUnit.MINUTES);
        } catch (RedisConnectionFailureException e) {
            // 发送失败要释放限流占位，否则用户白等一个完整的间隔
            if (interval > 0) {
                redis.delete(SMS_LIMIT_KEY + phone);
            }
            throw new BusinessException("Redis 连接失败，请确认 Redis 已启动且密码正确");
        }

        // mock 短信通道：本地/演示环境从后端控制台日志查看验证码。
        log.info("【短信验证码】phone={} code={} 有效期 {} 分钟", phone, code,
                properties.getAuth().getSmsTtlMinutes());
    }

    /**
     * 由 UUID 派生 6 位数字验证码。
     *
     * 取 UUID 高低各 64 位异或后掩掉符号位（避免 Math.abs(Long.MIN_VALUE) 仍为负的坑），
     * 再对 10^6 取模，用 %06d 补前导零，保证恒为 6 位数字（可能以 0 开头）。
     * UUID v4 有 122 位随机位，足够作为验证码熵源；生产环境也可用 SecureRandom.nextInt(1000000)。
     */
    private String generateSmsCode() {
        UUID uuid = UUID.randomUUID();
        long n = (uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits()) & Long.MAX_VALUE;
        return String.format(Locale.ROOT, "%06d", n % CODE_MODULUS);
    }

    /**
     * 验证码登录：比对 Redis 中的码，成功后立即删除（一次性），再签发 token。
     */
    public String login(String phone, String code) {
        String cached;
        try {
            cached = redis.opsForValue().get(SMS_KEY + phone);
        } catch (RedisConnectionFailureException e) {
            throw new BusinessException("Redis 连接失败，请确认 Redis 已启动且密码正确");
        }
        // 统一提示，避免通过不同报错信息枚举「该手机号是否刚发过验证码」
        if (cached == null || !cached.equals(code)) {
            throw new BusinessException("验证码错误或已过期");
        }
        // 一次性消费：校验通过立刻删除，同一个验证码不能重复登录
        redis.delete(SMS_KEY + phone);
        return issueToken(getOrCreate(phone));
    }

    /**
     * 开发者免验证码登录，仅用于本地联调与自动化测试切换身份。
     * 由 petcare.auth.dev-login-enabled 控制（默认 false），生产环境必须关闭。
     */
    public String devLogin(String phone) {
        if (!properties.getAuth().isDevLoginEnabled()) {
            throw new BusinessException("开发者登录未开启");
        }
        return issueToken(getOrCreate(phone));
    }

    /**
     * 退出登录：直接删除 Redis 中的 token key。
     * 因为没有 JWT 式的无状态自校验，删除 key 就等于「吊销会话」，
     * 前端再带旧 token 会在 AuthInterceptor 查不到 value 而被 401。
     * 对 null token 静默返回，方便前端重复调用退出接口。
     */
    public void logout(String token) {
        if (token != null) {
            redis.delete(TOKEN_KEY + token);
        }
    }

    /**
     * 由 ThreadLocal 里的 userId 还原完整登录用户（供 /api/auth/me 使用）。
     * 这里重新查库而不是复用 Redis 里的快照：role / staffId / 昵称等可能已被后台改动，
     * 查库能拿到最新值；查不到（例如账号被删）则直接拒绝，避免已注销身份继续生效。
     */
    public LoginUser current(Long userId) {
        if (userId == null) {
            throw new BusinessException("未登录");
        }
        AppUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return toLoginUser(user);
    }

    /**
     * 手机号首次登录即注册：查不到就插入一条 AppUser（role=0 普通用户、status=1 正常、
     * 默认昵称取手机号后 4 位），把「注册」藏进登录流程，实现免注册登录。
     * 末尾拦截 status=0 的禁用账号——即使验证码正确，被封禁的用户也拿不到 token。
     */
    private AppUser getOrCreate(String phone) {
        AppUser user = userMapper.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getPhone, phone));
        if (user == null) {
            user = new AppUser();
            user.setPhone(phone);
            user.setNickname("用户" + phone.substring(phone.length() - 4));
            user.setRole(0);
            user.setStatus(1);
            userMapper.insert(user);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用");
        }
        return user;
    }

    /**
     * 签发登录令牌：生成无含义 UUID，把 LoginUser 序列化成 JSON 写入 Redis 并设置 TTL。
     * value 存用户快照而非只存 userId，是为了让 AuthInterceptor 一次 Redis 读就能还原完整身份、
     * 少查一次库；代价是后台改资料后已签发 token 里的快照不会立刻刷新（由 current() 查库弥补）。
     */
    private String issueToken(AppUser user) {
        String token = IdUtil.fastSimpleUUID();
        try {
            redis.opsForValue().set(TOKEN_KEY + token, JSONUtil.toJsonStr(toLoginUser(user)),
                    properties.getAuth().getTokenTtlMinutes(), TimeUnit.MINUTES);
        } catch (RedisConnectionFailureException e) {
            throw new BusinessException("Redis 连接失败，请确认 Redis 已启动且密码正确");
        }
        return token;
    }

    /**
     * 把实体 AppUser 裁剪成登录态 DTO LoginUser。
     * 只保留鉴权与展示必需的字段：role 供权限判断、staffId 供服务者端定位到具体员工，
     * 不把 status 等与鉴权无关的字段带进 Redis，减小令牌体积也避免信息外泄。
     */
    private LoginUser toLoginUser(AppUser user) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(user.getId());
        loginUser.setPhone(user.getPhone());
        loginUser.setNickname(user.getNickname());
        loginUser.setRole(user.getRole());
        loginUser.setStaffId(user.getStaffId());
        return loginUser;
    }
}
