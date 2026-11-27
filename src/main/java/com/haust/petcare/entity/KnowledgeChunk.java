package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 养护知识库条目，对应表 knowledge_chunk，是本地规则 RAG 的检索数据源。
 * <p>
 * 本项目不引入向量库：24 条人工整理的知识（饮食/护理/疫苗/行为/安全/规则），
 * 检索按 keywords 命中 + 二元组重合度打分取 Top3，可解释、可测试（见 AiService）。
 * 每条都带 source 来源，命中后作为 citations 返回前端展示，保证回答有据可查。
 */
@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 知识标题，命中后作为引用展示给用户 */
    private String title;
    /** 分类：饮食/护理/疫苗/行为/安全/规则，检索可按类过滤 */
    private String category;
    /** 来源（如「宠物营养基础手册」），回答带引用、可追溯出处 */
    private String source;
    /** 知识正文，本地 RAG 用它拼答案 */
    private String content;
    /** 检索关键词，逗号分隔，命中打分用；本项目不引入向量库 */
    private String keywords;
    /** 创建时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
