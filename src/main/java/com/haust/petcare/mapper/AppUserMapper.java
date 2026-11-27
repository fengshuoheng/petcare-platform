package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.AppUser;

/**
 * 平台账号 Mapper，对应 app_user 表，服务于登录与身份识别。
 * <p>
 * AuthService 按 phone 查账号（getOrCreate，首次登录自动注册）、按 id 查当前用户（current），
 * 都由 BaseMapper + LambdaQueryWrapper 完成；uk_phone 唯一索引保证一号一账号，
 * 也是「并发下同一手机号重复注册」的兜底。本接口无需自定义 SQL。
 */
public interface AppUserMapper extends BaseMapper<AppUser> {
}
