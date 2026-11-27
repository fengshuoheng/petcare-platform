package com.haust.petcare.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.entity.Pet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python AI 服务（LangChain Agent）的网关。
 * 只负责调用与协议转换：超时、异常都返回 null，由 AiService 降级到本地规则 RAG。
 * <p>
 * 职责边界：Java 侧负责数据、检索、工具、落库、限流与拒答；Python 侧负责 Agent 编排与大模型调用，
 * 两者通过 HTTP + JSON 解耦。出站调用 Python 的 /agent/chat 时请求头带
 * X-Internal-Token（petcare.ai.internal-token 共享令牌）；反向的 Java /internal/** 接口
 * 由 InternalTokenFilter 用同一令牌校验，公网 Nginx 不转发 /internal。
 * <p>
 * 链路位置：AiService.chat 在 petcare.ai.enabled=true 时先调本类 chat()，返回 null/空 answer
 * 即降级本地 RAG。本类是一次同步请求-响应（postForObject 拿完整 JSON），连接/读超时来自
 * petcare.ai.service-connect-timeout-ms / service-read-timeout-ms（默认 2000/15000）。
 */
@Service
public class AiGatewayService {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayService.class);

    private final PetcareProperties properties;
    private final RestTemplate restTemplate;

    /**
     * 用配置里的连接/读超时构建网关专用 RestTemplate；
     * 超时设得比直连大模型短，目的是快速失败、尽快让 AiService 降级，不拖住主请求。
     */
    public AiGatewayService(PetcareProperties properties) {
        this.properties = properties;
        // 网关超时来自配置（默认连接 2s、读取 15s），任何超时都会变成 chat() 里的异常并返回 null。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getAi().getServiceConnectTimeoutMs());
        factory.setReadTimeout(properties.getAi().getServiceReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 是否启用 Python AI 服务：开关 petcare.ai.enabled 为真且 serviceUrl 非空。
     * <p>
     * 双条件是为了「没配地址」时也直接走本地规则 RAG，避免误连；AiService.chat 据此决定是否尝试远端。
     *
     * @return true 表示会先尝试远端 Agent
     */
    public boolean enabled() {
        return properties.getAi().isEnabled()
                && StrUtil.isNotBlank(properties.getAi().getServiceUrl());
    }

    /**
     * 调用 Python /agent/chat：只传 userId/sessionId/message/petId，请求头带 X-Internal-Token。
     *
     * @param userId    当前用户 ID
     * @param question  用户问题
     * @param pet       宠物档案，可为 null（只传 petId，明细由 Python 回调 /internal/pets/{id} 取）
     * @param sessionId 会话 ID
     * @return 远端结果；任何异常（连不上、超时、返回格式错误）返回 null，调用方走降级
     */
    public Map<String, Object> chat(Long userId, String question, Pet pet, String sessionId) {
        try {
            // 只传 ID 不传业务明细：数据所有权留在 Java，Python 需要宠物/订单/知识时
            // 回调 /internal/** 获取，避免把用户档案散落到第三方服务。
            JSONObject body = JSONUtil.createObj()
                    .set("userId", userId)
                    .set("sessionId", sessionId)
                    .set("message", question)
                    .set("petId", pet == null ? null : pet.getId());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 出站请求带共享令牌 X-Internal-Token（petcare.ai.internal-token）；
            // 反向 Java /internal/** 接口由 InternalTokenFilter 校验同一令牌，形成双向可信。
            headers.set("X-Internal-Token", properties.getAi().getInternalToken());

            long start = System.currentTimeMillis();
            // 一次同步 POST 请求-响应：超时或异常被 catch 成 null，
            // 由 AiService 无缝降级到本地 RAG，主链路不因 AI 服务抖动而失败。
            String response = restTemplate.postForObject(
                    properties.getAi().getServiceUrl() + "/agent/chat",
                    new HttpEntity<>(body.toString(), headers), String.class);
            long cost = System.currentTimeMillis() - start;
            if (StrUtil.isBlank(response)) {
                return null;
            }

            // 协议转换：解析 answer/refused/llmUsed/citations/tools，缺失字段给默认值，
            // source 标为 python-agent 便于前端区分回答来自远端还是本地。
            JSONObject json = JSONUtil.parseObj(response);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", json.getStr("answer"));
            result.put("refused", json.getBool("refused", false));
            result.put("llmUsed", json.getBool("llmUsed", false));
            JSONArray citations = json.getJSONArray("citations");
            result.put("citations", citations == null ? new JSONArray() : citations);
            JSONArray tools = json.getJSONArray("tools");
            result.put("tools", tools == null ? new JSONArray() : tools);
            result.put("source", "python-agent");
            log.info("AI 服务调用成功 userId={} cost={}ms", userId, cost);
            return result;
        } catch (Exception e) {
            log.warn("AI 服务不可用，降级到本地 RAG：{}", e.getMessage());
            // 连不上、超时、JSON 格式错误统一返回 null，绝不把异常抛给 AiService 主链路。
            return null;
        }
    }
}
