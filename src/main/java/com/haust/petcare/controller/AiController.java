package com.haust.petcare.controller;

import com.haust.petcare.common.BusinessException;
import com.haust.petcare.common.Result;
import com.haust.petcare.common.UserContext;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.AiMessage;
import com.haust.petcare.service.AiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * AI 养护助手（用户端业务域）。
 * <p>
 * 架构位置：/api/ai 下，受 AuthInterceptor 保护。问答主逻辑在 AiService：
 * 关键词 + 二元组打分的轻量 RAG、医疗类问题拒答；启用时优先调 Python Agent，调用失败或未启用则降级到本地
 * RAG（apiKey 非空走 DeepSeek，为空用模板回答）。
 * 本类额外承担并发保护：用 Semaphore（许可数 petcare.ai.max-concurrency）限流，
 * acquire 最多等 2 秒，拿不到就快速失败，避免大模型/远程服务被突发流量打满。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * 发起一次 AI 对话。
     * URL: POST /api/ai/chat；调用方：用户端（已登录）。
     * 入参：AiChatRequest，@Valid 校验 message 非空（petId、sessionId 可选，缺省 sessionId 由 Service 生成）。
     * 权限：需登录态；userId 由 UserContext 注入。
     * 流程：先 acquire 拿并发许可（线程被中断则恢复中断标志并提示重试，2 秒内拿不到则提示稍后再试），
     * 拿到后在 try/finally 里 chat 并 release，保证业务抛异常时许可也一定归还、不会泄漏。
     * 返回：含 answer / citations / sessionId / refused / llmUsed 等的 Map。
     */
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@Valid @RequestBody Requests.AiChatRequest request) {
        int acquired;
        try {
            acquired = aiService.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("AI 助手被中断，请重试");
        }
        if (acquired == 0) {
            throw new BusinessException("AI 助手当前请求较多，请稍后再试");
        }
        try {
            return Result.ok(aiService.chat(UserContext.userId(), request));
        } finally {
            aiService.release();
        }
    }

    /**
     * 查询某会话的对话历史。
     * URL: GET /api/ai/history；调用方：用户端（已登录）。
     * 入参：sessionId（必填）。
     * 权限：需登录态；Service 按 sessionId + userId 过滤，只能看自己的会话。
     * 返回：按 id 正序的 AiMessage 列表。
     */
    @GetMapping("/history")
    public Result<List<AiMessage>> history(@RequestParam String sessionId) {
        return Result.ok(aiService.history(sessionId, UserContext.userId()));
    }
}
