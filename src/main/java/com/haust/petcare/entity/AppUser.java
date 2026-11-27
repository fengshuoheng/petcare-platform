package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台账号，对应表 app_user，是登录与权限的载体。
 * <p>
 * 登录采用「手机号 + 短信验证码」：验证码存 Redis 不落库，校验通过后签发 UUID token 也存
 * Redis（见 AuthService / AuthInterceptor），因此本表不存密码、不存 token。
 * phone 上有唯一索引 uk_phone 保证一号一账号；role 区分用户/服务者/管理员，
 * 服务者账号通过 staff_id 关联 staff 表；status=0 的禁用账号在登录时被拒绝。
 */
@Data
@TableName("app_user")
public class AppUser {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 手机号，唯一索引 uk_phone；登录凭证，验证码按此 key 存 Redis */
    private String phone;
    /** 昵称，新用户首次登录自动取手机号后 4 位生成 */
    private String nickname;
    /** 头像 URL */
    private String avatar;
    /** 角色：0 普通用户 1 服务者 2 管理员，决定可访问的接口范围 */
    private Integer role;
    /** 关联 staff.id：仅服务者账号有值，登录 token 中带上它用于工作台鉴权 */
    private Long staffId;
    /** 账号状态：1 正常 0 禁用，禁用账号登录时被拒绝 */
    private Integer status;
    /** 注册时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
