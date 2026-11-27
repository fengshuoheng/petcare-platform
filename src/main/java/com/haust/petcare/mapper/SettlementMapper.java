package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.Settlement;

/**
 * 结算单 Mapper，对应 settlement 表，服务于「结算」环节的生成、查询与到期释放。
 * <p>
 * SettlementService 按 order_id 先 selectCount 防重复结算、按 staff_id 列收入、
 * releaseDue 用 LambdaUpdateWrapper 把到期的 0 待结算批量改为 1 可提现；
 * 都是单表操作，本接口无需自定义 SQL（uk_order 唯一索引兜底防重复结算）。
 */
public interface SettlementMapper extends BaseMapper<Settlement> {
}
