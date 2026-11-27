package com.haust.petcare.auth;

import com.haust.petcare.common.Result;
import com.haust.petcare.common.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证入口（用户端 /api/auth），整个鉴权链路的 HTTP 门面。
 * <p>
 * sms / login / dev-login 三个接口在 WebConfig 中被显式放行、不经过 AuthInterceptor，
 * 因为调用它们时用户还没有 token，无法携带 authorization；logout / me 则仍需登录态。
 * 这里只做参数绑定与 Result 包装，验证码校验、token 签发都下沉到 AuthService。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 发送验证码：只回「发送成功」，响应里绝不带验证码。
     * 验证码由服务端生成并写入 Redis，本地通过后端日志查看，生产交给短信网关。
     */
    @PostMapping("/sms")
    public Result<Void> sms(@Valid @RequestBody SmsRequest request) {
        authService.sendSms(request.getPhone());
        return Result.ok();
    }

    /**
     * 验证码登录：用手机号 + 6 位验证码换取 token。
     * URL: POST /api/auth/login；调用方：用户端（此时尚未登录）。
     * 入参：LoginRequest，@Valid 校验手机号格式与 6 位数字验证码。
     * 权限：WebConfig 白名单放行，无需 authorization。
     * 返回：仅含 token（UUID）的 Map；前端把它放进后续请求的 authorization 头。
     * 验证码校验通过后会在 AuthService 中立即删除，属于一次性凭证。
     */
    @PostMapping("/login")
    public Result<Map<String, String>> login(@Valid @RequestBody LoginRequest request) {
        String token = authService.login(request.getPhone(), request.getCode());
        Map<String, String> data = new HashMap<>();
        data.put("token", token);
        return Result.ok(data);
    }

    /**
     * 演示/联调用：免验证码直接换身份，由配置开关控制，生产必须关闭。
     */
    @PostMapping("/dev-login")
    public Result<Map<String, String>> devLogin(@Valid @RequestBody SmsRequest request) {
        String token = authService.devLogin(request.getPhone());
        Map<String, String> data = new HashMap<>();
        data.put("token", token);
        return Result.ok(data);
    }

    /**
     * 退出登录：删除 Redis 中的 token，使其立即失效。
     * URL: POST /api/auth/logout；调用方：用户端（已登录）。
     * 入参：authorization 请求头；required=false 只是参数绑定层面不强制，该路径仍在 /api/** 内，
     * 正常到达时 AuthInterceptor 已保证 token 存在。
     * 权限：需登录态。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "authorization", required = false) String token) {
        authService.logout(token);
        return Result.ok();
    }

    /**
     * 获取当前登录用户信息。
     * URL: GET /api/auth/me；调用方：用户端（已登录）。
     * 入参：无显式参数，用户身份从 UserContext（ThreadLocal）取，由 AuthInterceptor 写入。
     * 权限：需登录态；不接收前端传来的 userId，避免越权查询他人信息。
     * 返回：LoginUser（id / phone / nickname / role / staffId）。
     */
    @GetMapping("/me")
    public Result<?> me() {
        return Result.ok(authService.current(UserContext.userId()));
    }

    /**
     * 发送验证码 / 开发者登录共用的请求体。
     * 手机号用 @NotBlank + 正则 ^1[3-9]\d{9}$ 在参数绑定阶段校验，
     * 把非法号码挡在写 Redis / 调短信通道之前；缺少 @Valid 时这些注解不会生效。
     */
    public static class SmsRequest {
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }

    /**
     * 验证码登录请求体：在手机号校验之外，追加 6 位数字验证码校验（^\d{6}$）。
     * 与 SmsRequest 分开定义，让「发送验证码」和「登录」两个接口的入参各自最小化，
     * 字段级约束由 @Valid 触发，校验失败交给全局异常处理器统一转成错误响应。
     */
    public static class LoginRequest {
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;

        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码必须为 6 位数字")
        private String code;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }
}
