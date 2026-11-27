package com.haust.petcare.config;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内部接口鉴权：/internal/** 只能由持有共享令牌的服务调用。
 * 公网 Nginx 不转发 /internal，Java 端口在 Docker 内网访问，这里再加一层令牌校验。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 服务间共享令牌的请求头名，与 Python AI 服务约定一致；不复用 authorization（那是用户 token） */
    private static final String HEADER = "X-Internal-Token";

    private final PetcareProperties properties;

    public InternalTokenFilter(PetcareProperties properties) {
        this.properties = properties;
    }

    /**
     * 每个请求过滤一次（OncePerRequestFilter 保证 forward / include 等场景下不重复执行）。
     * 只对 /internal/** 生效：其余路径直接放行，交给后面的 Filter / AuthInterceptor 处理，两套鉴权互不重叠。
     * 命中内部路径时比对 X-Internal-Token 与配置 petcare.ai.internal-token；不一致直接写 401 JSON 并 return，
     * 不调用 filterChain.doFilter，未鉴权请求到不了 Controller。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 非内部接口直接放行；/api/** 的登录态由更靠后的 AuthInterceptor 负责
        if (!request.getRequestURI().startsWith("/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }
        // 共享令牌放在自定义头，与用户 authorization 头彻底分开
        String token = request.getHeader(HEADER);
        // 与服务端配置的共享令牌做等值比较；轻量服务间认证，不解析 JWT
        if (token == null || !token.equals(properties.getAi().getInternalToken())) {
            // 直接写 401 + JSON 错误体并终止链路；显式声明 UTF-8 避免中文乱码
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    "{\"success\":false,\"errorMsg\":\"内部接口鉴权失败\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // 令牌校验通过，放行到 InternalController
        filterChain.doFilter(request, response);
    }
}
