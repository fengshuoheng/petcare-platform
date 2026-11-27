package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 养护助手的会话消息，对应表 ai_message，属于「AI 网关」的会话落库环节。
 * <p>
 * Java 侧负责鉴权、限流与会话持久化；一条消息归属一个 session_id 会话，供 /api/ai/history 回放。
 * 回答必须携带 citations（命中的知识条目标题/来源），检索不到时按兜底话术回答、不编造；
 * 疾病/用药类问题在入口直接拒答（refused=1）并引导就医，规避模型幻觉给出医疗建议的风险。
 */
@Data
@TableName("ai_message")
public class AiMessage {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 会话 id，同一轮多轮对话共享，用于 /api/ai/history 取历史 */
    private String sessionId;
    /** 提问用户，用于鉴权与数据隔离 */
    private Long userId;
    /** 消息角色：user 用户提问 / assistant 助手回答 */
    private String role;
    /** 消息正文 */
    private String content;
    /** 命中的知识条目引用（标题/来源），回答必须带引用、无依据时兜底不编造 */
    private String citations;
    /** Python Agent 的工具调用记录（查档案/查订单/检索知识库），便于排查 */
    private String toolCalls;
    /** 是否拒答：1 表示疾病/用药类问题被入口拦截并引导就医 */
    private Integer refused;
    /** 创建时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
