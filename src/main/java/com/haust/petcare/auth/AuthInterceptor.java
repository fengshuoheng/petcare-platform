package com.haust.petcare.auth;

import cn.hutool.json.JSONUtil;
import com.haust.petcare.common.LoginUser;
import com.haust.petcare.common.UserContext;
import com.haust.petcare.config.PetcareProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

/**
 * 登录态拦截器：挂在 /api/** 上，是「前端带 authorization → 后端识别用户」的核心环节。
 * <p>
 * 架构位置：属于 Spring MVC 的 HandlerInterceptor 层，晚于 Servlet Filter（InternalTokenFilter），
 * 只在请求进入 Controller 之前执行；白名单路径由 WebConfig 排除，不会走到这里。
 * 职责：校验 token → 从 Redis 还原 LoginUser → 写入 UserContext（ThreadLocal）→ 给活跃用户续期。
 * 与 AuthService 约定同一套 Redis key（petcare:token:{token}），本类只读、不修改 value。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 与 AuthService.issueToken 写出的前缀保持一致；拦截器只读这个 key，登录态以它是否存在为准 */
    private static final String TOKEN_KEY = "petcare:token:";

    private final StringRedisTemplate redis;
    private final PetcareProperties properties;

    public AuthInterceptor(StringRedisTemplate redis, PetcareProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 进入 Controller 前校验登录态并装配用户上下文。
     * 返回 false 会直接中断请求、不再执行目标方法，由这里写回 401 状态码。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 约定前端把登录返回的 UUID token 放在 authorization 头；白名单之外的 /api 请求走到这里都应带该头
        String token = request.getHeader("authorization");
        // 没带 token 直接 401，避免匿名访问需要用户身份的接口
        if (token == null || token.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        // token 本身无含义（非 JWT），合法与否完全以 Redis 中是否存在该 key 为准；这也是服务端能主动踢人的原因
        String value = redis.opsForValue().get(TOKEN_KEY + token);
        // key 不存在 = 未登录 / 已登出 / 已过期；过期由 Redis TTL 自动淘汰，代码里无需再判时间
        if (value == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        // 把 Redis 中的用户 JSON 还原成 LoginUser 放进 ThreadLocal；
        // 后续 Controller / Service 统一用 UserContext.userId() 取当前用户，不必层层透传参数
        UserContext.set(JSONUtil.toBean(value, LoginUser.class));
        // 续期：每次有效请求把 TTL 重置回 token-ttl-minutes，实现「滑动过期」——活跃用户不掉线、
        // 静默用户到点自动失效；放在身份校验通过之后，未登录请求不会给陌生 key 续命
        redis.expire(TOKEN_KEY + token, properties.getAuth().getTokenTtlMinutes(), TimeUnit.MINUTES);
        return true;
    }

    /**
     * 请求处理完成后清理 ThreadLocal（视图渲染完或业务抛异常后都会回调）。
     * <p>
     * 为什么必须 clear：Servlet 容器用线程池复用工作线程，ThreadLocal 绑在线程上，不会随一次请求结束自动消失。
     * 若不清理，用户 A 请求结束后同一线程被分配给用户 B 时，B 会通过 UserContext 读到 A 的 LoginUser，
     * 造成身份「串号」和越权。只要 preHandle 写入了上下文，就必须在这里成对移除；
     * 放在 afterCompletion 而不是 postHandle，是因为业务抛异常时 postHandle 不执行、而它仍会执行，能保证异常路径也清理。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理：线程池复用会把上一个请求的用户身份残留给下一个请求
        UserContext.clear();
    }
}
