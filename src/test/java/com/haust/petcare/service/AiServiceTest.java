package com.haust.petcare.service;

import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.KnowledgeChunk;
import com.haust.petcare.mapper.AiMessageMapper;
import com.haust.petcare.mapper.KnowledgeChunkMapper;
import com.haust.petcare.mapper.OrderMapper;
import com.haust.petcare.mapper.PetMapper;
import com.haust.petcare.mapper.ServiceItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AI 助手问答链路：医疗问题拒答、命中知识库后带引用作答、无命中时明确说无依据。
 * 纯 Mockito 单测（不启 Spring 容器、不连 MySQL/Redis/大模型）：
 * chat 的三条分支完全由 KnowledgeChunkMapper 的返回值决定，@Mock 即可覆盖；
 * 同时把 API Key 置空，关掉真实大模型调用，保证用例离线、结果可重复。
 */
@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private PetMapper petMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private ServiceItemMapper serviceItemMapper;
    @Mock
    private AiGatewayService gatewayService;

    private AiService aiService;

    @BeforeEach
    void setUp() {
        PetcareProperties properties = new PetcareProperties();
        // 置空 API Key：走本地知识库/模板分支，避免单测真的调用外部大模型
        properties.getAi().setApiKey("");
        aiService = new AiService(chunkMapper, messageMapper, petMapper, orderMapper,
                serviceItemMapper, properties, gatewayService);
        lenient().when(messageMapper.insert(any())).thenReturn(1);
    }

    /**
     * 场景：用户问「猫呕吐了吃什么药？」，命中医疗敏感词。
     * 断言：refused=true，且回答包含「就医」引导。
     * 业务风险：模型若给出用药/剂量建议会造成宠物伤害与平台责任，必须在入口先拦截。
     */
    @Test
    void shouldRefuseMedicalQuestion() {
        Requests.AiChatRequest request = new Requests.AiChatRequest();
        request.setMessage("猫呕吐了吃什么药？");

        Map<String, Object> result = aiService.chat(1L, request);

        assertTrue((Boolean) result.get("refused"));
        assertTrue(result.get("answer").toString().contains("就医"));
    }

    /**
     * 场景：知识库命中一条「幼猫喂养频率」片段，用户问三个月幼猫怎么喂。
     * 断言：不拒答、答案包含片段原文「每天喂 4 到 5 次」、citations 带标题「幼猫喂养频率」。
     * 业务风险：答案不带引用就成了无来源的自由发挥，无法追溯，也无法证明「只依据知识库作答」。
     */
    @Test
    void shouldAnswerWithCitation() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setTitle("幼猫喂养频率");
        chunk.setCategory("饮食");
        chunk.setSource("宠物营养基础手册");
        chunk.setContent("3 个月以内的幼猫建议每天喂 4 到 5 次，少量多餐。");
        chunk.setKeywords("幼猫,喂养,频率");
        when(chunkMapper.selectList(any())).thenReturn(Arrays.asList(chunk));

        Requests.AiChatRequest request = new Requests.AiChatRequest();
        request.setMessage("三个月幼猫怎么喂");
        Map<String, Object> result = aiService.chat(1L, request);

        assertFalse((Boolean) result.get("refused"));
        assertTrue(result.get("answer").toString().contains("每天喂 4 到 5 次"));
        assertTrue(result.get("citations").toString().contains("幼猫喂养频率"));
    }

    /**
     * 场景：知识库无命中（selectList 返回空），用户问无关问题「今天天气怎么样」。
     * 断言：回答包含「没有在知识库里找到」，即明确说无依据而不是硬答。
     * 业务风险：RAG 无证据时仍强行生成就是幻觉，会用编造内容误导用户。
     */
    @Test
    void shouldReturnNoEvidenceWhenNothingMatched() {
        when(chunkMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        Requests.AiChatRequest request = new Requests.AiChatRequest();
        request.setMessage("今天天气怎么样");
        Map<String, Object> result = aiService.chat(1L, request);

        assertTrue(result.get("answer").toString().contains("没有在知识库里找到"));
    }
}
