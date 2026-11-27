package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.Review;

/**
 * 订单评价 Mapper，对应 review 表，服务于「履约售后」的评价提交与评分重算。
 * <p>
 * 「一单一评」由 review.uk_order 唯一索引兜底，提交前 AfterSaleService 先 selectCount
 * 拦截重复；按 staff_id 列评价（limit 50）用 LambdaQueryWrapper.last 拼接，
 * 都是单表操作，本接口无需自定义 SQL。
 */
public interface ReviewMapper extends BaseMapper<Review> {
}
