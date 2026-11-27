package com.haust.petcare.auth;

import com.haust.petcare.common.BusinessException;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.entity.AppUser;
import com.haust.petcare.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证码登录链路：随机码格式、Redis TTL、服务端限流、一次性消费。
 * 纯 Mockito 单测，不启动 Spring 容器、不连真实 Redis/MySQL。
 * 原因：sendSms/login 的逻辑都收敛在 AuthService 内部，用 @Mock 替掉 AppUserMapper 与
 * StringRedisTemplate 后能精确编排返回值（如 setIfAbsent 的 true/false）；一旦接真实中间件，
 * 「随机码、TTL、限流」这类边界就依赖运行环境与当前时间，无法稳定复现，启动也慢。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String PHONE = "13700000001";
    private static final String CODE_KEY = "petcare:sms:" + PHONE;
    private static final String LIMIT_KEY = "petcare:sms:limit:" + PHONE;

    @Mock
    private AppUserMapper userMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private PetcareProperties props;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        props = new PetcareProperties();
        // 默认关掉限流，让用例聚焦验证码本身；限流单独测
        props.getAuth().setSmsIntervalSeconds(0);
        authService = new AuthService(userMapper, redis, props);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    /**
     * 场景：调用 sendSms 发送验证码。
     * 断言：用 ArgumentCaptor 捕获写入 Redis 的 key/value，key 为 petcare:sms:{手机号}，
     *       value 匹配 6 位数字，且 set 带 5 分钟 TTL。
     * 业务风险：验证码格式不符会让登录校验形同虚设；缺 TTL 会让验证码长期有效、被撞库或复用。
     */
    @Test
    void sendSms_生成6位数字并写入Redis带TTL() {
        authService.sendSms(PHONE);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(key.capture(), code.capture(), eq(5L), eq(TimeUnit.MINUTES));
        assertEquals(CODE_KEY, key.getValue());
        assertTrue(code.getValue().matches("\\d{6}"),
                "验证码必须是 6 位数字，实际为 " + code.getValue());
    }

    /**
     * 场景：连续 20 次发送验证码。
     * 断言：20 次写入的都是 6 位数字，且去重后多于 1 种取值。
     * 业务风险：验证码写死或随机种子固定时，攻击者拿一个已知码就能登录任意账号。
     */
    @Test
    void sendSms_每次生成的验证码不是固定值() {
        for (int i = 0; i < 20; i++) {
            authService.sendSms(PHONE);
        }

        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(20)).set(eq(CODE_KEY), codes.capture(), eq(5L), eq(TimeUnit.MINUTES));
        List<String> all = codes.getAllValues();
        assertTrue(all.stream().allMatch(c -> c.matches("\\d{6}")));
        assertTrue(new HashSet<>(all).size() > 1, "验证码不应每次都是同一个固定值");
    }

    /**
     * 场景：开启 60 秒发送间隔，setIfAbsent 首次 true、第二次 false，模拟限流键已存在。
     * 断言：第二次调用抛 BusinessException 且提示「过于频繁」，验证码只真正写入过 1 次。
     * 业务风险：没有服务端限流会被短信炸弹刷接口造成资损；只靠前端按钮倒计时挡不住直接调 API。
     */
    @Test
    void sendSms_间隔内重复发送被服务端限流() {
        props.getAuth().setSmsIntervalSeconds(60);
        when(valueOps.setIfAbsent(eq(LIMIT_KEY), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(true, false);

        authService.sendSms(PHONE);
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.sendSms(PHONE));

        assertTrue(ex.getMessage().contains("过于频繁"));
        // 第二次被拦下，验证码只真正写入过一次
        verify(valueOps, times(1)).set(eq(CODE_KEY), anyString(), anyLong(), any(TimeUnit.class));
    }

    /**
     * 场景：Redis 中存在正确验证码，用户凭手机号 + 该 6 位码调用 login 换 token。
     * 断言：签发出非 null 的 token，并 verify(redis).delete 删除验证码 key（一次性消费）。
     * 业务风险：验证码用后不删可被重放、同一码反复换 token；一次性消费是短信登录的核心要求。
     */
    @Test
    void login_正确验证码签发token并立即删除验证码() {
        when(valueOps.get(CODE_KEY)).thenReturn("384217");
        AppUser user = new AppUser();
        user.setId(1L);
        user.setPhone(PHONE);
        user.setNickname("演示用户");
        user.setRole(0);
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);

        String token = authService.login(PHONE, "384217");

        assertNotNull(token);
        verify(redis).delete(CODE_KEY);
    }

    /**
     * 场景：验证码错误、以及验证码已过期（Redis get 返回 null）两种输入分别登录。
     * 断言：两次都抛 BusinessException，且 never() 确认没有误删验证码缓存。
     * 业务风险：错误码被接受等于任意登录；误删缓存会让真实用户输错一次后被迫重新发码。
     */
    @Test
    void login_错误或过期验证码被拒绝且不删除缓存() {
        when(valueOps.get(CODE_KEY)).thenReturn("111111");
        assertThrows(BusinessException.class, () -> authService.login(PHONE, "222222"));

        when(valueOps.get(CODE_KEY)).thenReturn(null);
        assertThrows(BusinessException.class, () -> authService.login(PHONE, "111111"));

        verify(redis, never()).delete(anyString());
    }
}
