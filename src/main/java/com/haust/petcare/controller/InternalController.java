package com.haust.petcare.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.common.Result;
import com.haust.petcare.entity.KnowledgeChunk;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.Pet;
import com.haust.petcare.mapper.OrderMapper;
import com.haust.petcare.mapper.PetMapper;
import com.haust.petcare.service.AiService;
import com.haust.petcare.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供 Python Agent 调用的内部接口。
 * 数据所有权仍在 Java：Python 不直连数据库，只通过这里取数。
 * <p>
 * 鉴权链路：/internal/** 不在 /api/** 内，不走 AuthInterceptor（无登录态），
 * 而是由 Servlet 层的 InternalTokenFilter 校验 X-Internal-Token；公网 Nginx 不转发 /internal，仅内网可达。
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final AiService aiService;
    private final PetMapper petMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    public InternalController(AiService aiService, PetMapper petMapper,
                              OrderMapper orderMapper, OrderService orderService) {
        this.aiService = aiService;
        this.petMapper = petMapper;
        this.orderMapper = orderMapper;
        this.orderService = orderService;
    }

    /**
     * 知识库检索（供 Python Agent 做 RAG 取数）。
     * URL: GET /internal/knowledge/search；调用方：Python AI 服务（内部调用，无用户登录态）。
     * 入参：query（必填）、limit（默认 3，Math.min 封顶 10 防止一次返回过多）；复用 AiService.retrieve 的关键词 + 二元组打分。
     * 权限：由 InternalTokenFilter 校验 X-Internal-Token 共享令牌。返回：命中知识片段（id/title/category/source/content）。
     */
    @GetMapping("/knowledge/search")
    public Result<List<Map<String, Object>>> searchKnowledge(@RequestParam String query,
                                                             @RequestParam(defaultValue = "3") int limit) {
        List<KnowledgeChunk> chunks = aiService.retrieve(query, Math.min(limit, 10));
        List<Map<String, Object>> list = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", chunk.getId());
            item.put("title", chunk.getTitle());
            item.put("category", chunk.getCategory());
            item.put("source", chunk.getSource());
            item.put("content", chunk.getContent());
            list.add(item);
        }
        return Result.ok(list);
    }

    /**
     * 按 id 查宠物资料（供 Python Agent 组装上下文）。
     * URL: GET /internal/pets/{id}；调用方：Python AI 服务（内部调用，无用户登录态）。
     * 入参：路径 id + 可选 userId；传了 userId 时校验归属，不一致返回 Result.fail（而非抛异常，便于 Agent 判断）。
     * 权限：InternalTokenFilter 校验 X-Internal-Token。返回：Pet。
     */
    @GetMapping("/pets/{id}")
    public Result<Pet> pet(@PathVariable Long id, @RequestParam(required = false) Long userId) {
        Pet pet = petMapper.selectById(id);
        if (pet == null || (userId != null && !userId.equals(pet.getUserId()))) {
            return Result.fail("宠物不存在");
        }
        return Result.ok(pet);
    }

    /**
     * 查用户最近一笔订单（供 Python Agent 回答「我的订单到哪了」）。
     * URL: GET /internal/orders/latest；调用方：Python AI 服务（内部调用，无用户登录态）。
     * 入参：userId（必填，由 Agent 依据对话上下文传入）；按 createTime 倒序取 1 条。
     * 权限：InternalTokenFilter 校验 X-Internal-Token；这里信任调用方传入的 userId、不做登录态归属校验，
     * 前提是 /internal 只对内网可信服务开放。返回：订单摘要 Map，无订单时 data 为 null。
     */
    @GetMapping("/orders/latest")
    public Result<Map<String, Object>> latestOrder(@RequestParam Long userId) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime)
                .last("limit 1"));
        if (order == null) {
            return Result.ok(null);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("orderNo", order.getOrderNo());
        map.put("status", order.getStatus());
        map.put("statusText", orderService.statusText(order.getStatus()));
        map.put("serviceName", order.getServiceName());
        map.put("slotStart", order.getSlotStart());
        map.put("payAmount", order.getPayAmount());
        return Result.ok(map);
    }
}
