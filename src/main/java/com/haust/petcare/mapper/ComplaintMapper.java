package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.Complaint;

/**
 * 投诉工单 Mapper，对应 complaint 表，服务于「履约售后」的投诉提交与客服处理。
 * <p>
 * 单表 CRUD 走 BaseMapper；「普通用户只看自己的投诉、管理员看全部」这一数据隔离在
 * AfterSaleService 里用 LambdaQueryWrapper 按 user_id 追加条件实现，故本接口为空。
 */
public interface ComplaintMapper extends BaseMapper<Complaint> {
}
