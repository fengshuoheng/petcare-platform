package com.haust.petcare.common;

import lombok.Data;

/**
 * 登录态快照：签发 token 时由 AuthService 把用户关键信息序列化成 JSON 存 Redis，
 * AuthInterceptor 校验 token 后反序列化还原，放进 UserContext（ThreadLocal）供本次请求使用。
 * <p>
 * 只放鉴权与业务判断需要的字段，不含密码、验证码、token 本身；
 * 这样 service 层不必再查库就能拿到 userId / role / staffId。
 */
@Data
public class LoginUser {

    /** 用户 id（app_user.id），业务里最常用的归属标识 */
    private Long id;

    /** 手机号 */
    private String phone;

    /** 昵称 */
    private String nickname;

    /** 0用户 1服务者 2管理员 */
    private Integer role;

    /** 服务者 id，仅 role=1 的账号有值，工作台接口据此鉴权；普通用户为 null */
    private Long staffId;
}
