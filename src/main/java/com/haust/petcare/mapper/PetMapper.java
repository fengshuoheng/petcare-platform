package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.Pet;

/**
 * 宠物档案 Mapper，对应 pet 表，服务于预约下单时的宠物归属校验与体型换算。
 * <p>
 * OrderService 按 id 取宠物并校验 user_id 归属、SlotService 取宠物算体型以过滤可用时间槽，
 * 都是单表操作，走 BaseMapper + LambdaQueryWrapper，本接口无需自定义 SQL。
 */
public interface PetMapper extends BaseMapper<Pet> {
}
