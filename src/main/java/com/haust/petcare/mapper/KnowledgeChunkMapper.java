package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.KnowledgeChunk;

/**
 * 养护知识库 Mapper，对应 knowledge_chunk 表，是本地规则 RAG 的检索数据源。
 * <p>
 * 按 category / keywords 的检索与打分在 AiService 层完成（关键词命中 + 二元组重合度取 Top3，
 * 不引入向量库），查询用 LambdaQueryWrapper 构造，idx_category(category) 支撑按类过滤；
 * 本接口走 BaseMapper 即可，不写自定义 SQL。
 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {
}
