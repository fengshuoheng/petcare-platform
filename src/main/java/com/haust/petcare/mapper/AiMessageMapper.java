package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.AiMessage;

/**
 * AI 会话消息 Mapper，对应 ai_message 表，服务于「AI 网关」的会话落库与历史回放。
 * <p>
 * 单表 CRUD 走 BaseMapper；按 session_id 取某轮对话历史用 LambdaQueryWrapper 构造，
 * 表上的 idx_session(session_id) 索引支撑该查询。本接口不写自定义 SQL。
 */
public interface AiMessageMapper extends BaseMapper<AiMessage> {
}
