package com.haust.petcare.config;

import com.haust.petcare.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置：注册登录态拦截器并定义放行白名单。
 * <p>
 * 架构位置：拦截分两层——请求先过 Servlet 容器层的 Filter（InternalTokenFilter 负责 /internal/** 的共享令牌），
 * 进入 DispatcherServlet 后才轮到本类注册的 HandlerInterceptor（AuthInterceptor 负责 /api/** 的登录态）。
 * 因此 /internal/** 不会经过这里，/api/** 之外的文档端点也不拦截。
 * 白名单原则：要么是「拿到 token 之前」的认证入口，要么是「服务器到服务器」回调，要么是无须登录态的静态/文档资源。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 把 AuthInterceptor 挂到所有 /api/** 请求，再逐项排除不需要登录态的路径。
     * 拦截顺序：Filter（InternalTokenFilter）→ DispatcherServlet → 本拦截器 preHandle → Controller；
     * 被 excludePathPatterns 命中的请求连 preHandle 都不会进，也就不会写 UserContext。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // 只拦 /api 前缀：/internal/** 是内网接口，由 Filter 层的令牌校验负责，不走这里
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 发验证码：用户还没登录、拿不到 token，必须放行
                        "/api/auth/sms",
                        // 验证码登录：用验证码换 token，同样处于「无 token」阶段
                        "/api/auth/login",
                        // 本地联调免验证码登录：由 petcare.auth.dev-login-enabled 控制（默认 false），
                        // 放行是因为它本身也要签发 token；生产关闭后即使路径放行，Service 里也会拒绝
                        "/api/auth/dev-login",
                        // 支付渠道回调：服务器到服务器调用、没有用户态，
                        // 靠 PaymentService 里的签名验签保证合法性，不能要求 authorization
                        "/api/pay/callback",
                        // 以下为 Knife4j / Swagger 文档端点，纯静态资源或接口描述，不需要登录态
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/v2/api-docs",
                        // Spring Boot 默认错误转发路径，放行避免异常时被拦截器二次 401 盖掉真实错误
                        "/error",
                        // 浏览器自动请求的站点图标
                        "/favicon.ico");
    }
}
