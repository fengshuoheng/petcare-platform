package com.haust.petcare.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.AiMessage;
import com.haust.petcare.entity.KnowledgeChunk;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.Pet;
import com.haust.petcare.entity.ServiceItem;
import com.haust.petcare.mapper.AiMessageMapper;
import com.haust.petcare.mapper.KnowledgeChunkMapper;
import com.haust.petcare.mapper.OrderMapper;
import com.haust.petcare.mapper.PetMapper;
import com.haust.petcare.mapper.ServiceItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 宠物养护助手。
 * 检索增强：关键词 + 二元组打分召回知识库，回答必须带来源；
 * 医疗类问题直接拒答；模型不可用时用检索到的原文组织回答。
 * <p>
 * 业务域：AI 问答的编排与数据落地，对应 ai_message（对话消息）、knowledge_chunk（知识片段，只读）、
 * pet / order / service_item（工具取数用）。Java 侧持有全部数据所有权，Python 服务不直连数据库：
 * 它只能通过 /internal/** 接口（见 InternalController，由 InternalTokenFilter 校验 X-Internal-Token）
 * 回调 Java 取知识、宠物档案与最新订单；本类的 retrieve() 正是该内部接口的检索实现。
 * <p>
 * 链路位置：AiController 先用 acquire()/release() 做并发限流再调 chat()；chat() 优先委托
 * AiGatewayService 调 Python FastAPI/LangChain Agent（开关 petcare.ai.enabled），
 * 远端异常/超时返回 null 时降级为本地规则 RAG（retrieve + runTools + 可选直连大模型 callLlm，
 * 再失败用 templateAnswer 拼检索原文）。
 * <p>
 * 面试要点：医疗拒答在调用任何模型之前短路；并发用 Semaphore(maxConcurrency，默认 4)，
 * tryAcquire(2s) 拿不到直接拒绝而非排队；网关与直连大模型各设 connect/read 超时；
 * 用户消息与助手回答都经 save() 落 ai_message，citations/toolCalls 一并持久化便于追溯。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    // 医疗/用药/诊断类敏感词：命中即拒答并建议就医，必须在任何模型调用之前短路，避免给出用药建议。
    private static final List<String> REFUSE_WORDS = Arrays.asList(
            "药", "用药", "剂量", "生病", "呕吐", "腹泻", "拉稀", "发烧", "抽搐",
            "中毒", "手术", "骨折", "便血", "怀孕", "难产", "诊断");

    // 直连大模型时的系统提示词：限定回答范围、禁止诊断用药、要求标注来源、无依据不编造。
    private static final String SYSTEM_PROMPT =
            "你是宠物养护助手，只能依据提供的资料回答饮食、护理、疫苗、行为问题。\n" +
            "规则：\n" +
            "1. 不诊断疾病、不给用药建议；涉及疾病、用药、剂量时，必须建议立即就医。\n" +
            "2. 回答要具体、简短，先给结论；末尾标注来源标题。\n" +
            "3. 提供的资料里没有的内容，直接说没有依据，不要编造。\n" +
            "4. 不要输出与宠物养护无关的内容。";

    private final KnowledgeChunkMapper chunkMapper;
    private final AiMessageMapper messageMapper;
    private final PetMapper petMapper;
    private final OrderMapper orderMapper;
    private final ServiceItemMapper serviceItemMapper;
    private final PetcareProperties properties;
    private final Semaphore semaphore;
    private final RestTemplate restTemplate;
    private final AiGatewayService gatewayService;

    /**
     * 注入知识片段/对话/宠物/订单/服务项 Mapper、配置与 AI 网关；
     * 同时按 maxConcurrency 建信号量、按固定超时建直连大模型的 RestTemplate。
     */
    public AiService(KnowledgeChunkMapper chunkMapper, AiMessageMapper messageMapper,
                     PetMapper petMapper, OrderMapper orderMapper,
                     ServiceItemMapper serviceItemMapper, PetcareProperties properties,
                     AiGatewayService gatewayService) {
        this.chunkMapper = chunkMapper;
        this.messageMapper = messageMapper;
        this.petMapper = petMapper;
        this.orderMapper = orderMapper;
        this.serviceItemMapper = serviceItemMapper;
        this.properties = properties;
        this.gatewayService = gatewayService;
        // 并发闸：Semaphore 许可数取 maxConcurrency（默认 4），至少 1 个；
        // 由 AiController 在 try/finally 中成对 acquire()/release()，超载直接拒绝而非排队堆积。
        this.semaphore = new Semaphore(Math.max(properties.getAi().getMaxConcurrency(), 1));
        // 这个 RestTemplate 只用于直连大模型 /chat/completions：连接 3s、读取 20s；
        // 与 AiGatewayService 里按配置设置的网关超时是两条独立链路。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(20000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * AI 问答主入口：先落用户消息 → 医疗拒答短路 → 优先 Python Agent → 降级本地 RAG → 落助手消息。
     * <p>
     * 链路：AiController /api/ai/chat 在拿到并发许可后调用；history() 可回放同一 sessionId 的对话。
     *
     * @param userId  当前用户 ID（工具取数与消息归档用）
     * @param request 含 message（截断到 500 字）、sessionId（空则生成 UUID）、petId
     * @return sessionId、answer、citations、refused、llmUsed、tools、source
     * @throws BusinessException 问题为空
     */
    public Map<String, Object> chat(Long userId, Requests.AiChatRequest request) {
        String question = request.getMessage() == null ? "" : StrUtil.maxLength(request.getMessage().trim(), 500);
        if (question.isEmpty()) {
            throw new BusinessException("问题不能为空");
        }
        // sessionId 为空就生成一个，同一会话的消息靠它归档；问题截断 500 字防止超长 prompt。
        String sessionId = StrUtil.blankToDefault(request.getSessionId(), IdUtil.fastSimpleUUID());
        // 用户消息先落库：即使后续模型/网关异常，也保留提问记录，便于追溯与看板统计。
        save(sessionId, userId, "user", question, null, null, 0);

        // 医疗拒答短路：命中「药/剂量/呕吐/诊断」等词直接返回就医建议，
        // 不调用 Python 也不调用大模型，这是合规底线（refused=1 落库）。
        if (containsMedical(question)) {
            String answer = "这个问题涉及疾病、用药或剂量，我不能给出建议。请尽快带宠物去正规宠物医院就医，"
                    + "并如实告诉医生宠物的症状、持续时间和近期饮食。";
            save(sessionId, userId, "assistant", answer, "[]", "[\"medical_refuse\"]", 1);
            return result(sessionId, answer, new ArrayList<>(), true, false, Arrays.asList("medical_refuse"));
        }

        Pet pet = request.getPetId() == null ? null : petMapper.selectById(request.getPetId());

        // 优先走 Python LangChain Agent；超时/异常时返回 null，继续走下面的本地 RAG
        if (gatewayService.enabled()) {
            // 远端只接受非空 answer；返回 null/空即视为失败，继续走本地 RAG，
            // 把「开关关闭、连不上、超时、格式错」统一收敛成一条降级路径。
            Map<String, Object> remote = gatewayService.chat(userId, question, pet, sessionId);
            if (remote != null && StrUtil.isNotBlank(String.valueOf(remote.get("answer")))) {
                boolean refused = Boolean.TRUE.equals(remote.get("refused"));
                save(sessionId, userId, "assistant", String.valueOf(remote.get("answer")),
                        JSONUtil.toJsonStr(remote.get("citations")),
                        JSONUtil.toJsonStr(remote.get("tools")), refused ? 1 : 0);
                remote.put("sessionId", sessionId);
                return remote;
            }
        }

        // 本地 RAG：召回 top3 知识片段 + 跑业务工具，作为无模型/模型失败时的回答依据。
        List<KnowledgeChunk> chunks = retrieve(question, 3);
        List<String> toolResults = runTools(userId, question);

        List<Map<String, Object>> citations = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("title", chunk.getTitle());
            citation.put("source", chunk.getSource());
            citation.put("snippet", StrUtil.maxLength(chunk.getContent(), 120));
            citations.add(citation);
        }

        String answer;
        boolean llmUsed = false;
        // 有 apiKey 才直连大模型（OpenAI 兼容 /chat/completions）；调用异常降级为模板回答，
        // 没配 key 时直接用检索原文组织回答，保证 AI 助手在主链路之外始终有兜底答案。
        if (StrUtil.isNotBlank(properties.getAi().getApiKey())) {
            try {
                answer = callLlm(question, pet, chunks, toolResults);
                llmUsed = true;
            } catch (Exception e) {
                log.warn("大模型调用失败，降级为检索回答：{}", e.getMessage());
                answer = templateAnswer(chunks, toolResults);
            }
        } else {
            answer = templateAnswer(chunks, toolResults);
        }

        // 助手回答连同引用、工具结果一起落库，llmUsed/refused 标记回答来源，便于前端与审计。
        save(sessionId, userId, "assistant", answer, JSONUtil.toJsonStr(citations),
                JSONUtil.toJsonStr(toolResults), 0);
        return result(sessionId, answer, citations, false, llmUsed, toolResults);
    }

    /**
     * 回放某会话的对话消息，按 id 升序，且限定只查当前用户的会话（防止查他人对话）。
     *
     * @param sessionId 会话 ID
     * @param userId    当前用户 ID
     * @return 该会话的 user/assistant 消息列表
     */
    public List<AiMessage> history(String sessionId, Long userId) {
        return messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, sessionId)
                .eq(AiMessage::getUserId, userId)
                .orderByAsc(AiMessage::getId));
    }

    // 统一封装返回体；source 标为 java-local，远端成功时由网关分支直接返回 source=python-agent。
    private Map<String, Object> result(String sessionId, String answer,
                                       List<Map<String, Object>> citations,
                                       boolean refused, boolean llmUsed, List<String> tools) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", sessionId);
        map.put("answer", answer);
        map.put("citations", citations);
        map.put("refused", refused);
        map.put("llmUsed", llmUsed);
        map.put("tools", tools);
        map.put("source", "java-local");
        return map;
    }

    // 敏感词子串匹配；命中即拒答，放在最前面是为了不把医疗问题送进任何模型。
    private boolean containsMedical(String question) {
        for (String word : REFUSE_WORDS) {
            if (question.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 轻量检索：关键词命中 + 二元组重合度，返回得分最高的若干片段。
     * <p>
     * 同时被本地 RAG 与 /internal/knowledge/search（Python Agent 回调）复用，
     * 知识片段来自 knowledge_chunk 表，只读不写。
     *
     * @param question 用户问题
     * @param topK     返回片段数
     * @return 按相关度降序的知识片段
     */
    public List<KnowledgeChunk> retrieve(String question, int topK) {
        // 项目没有向量库/ES，知识片段量小，直接全表扫描后打分；
        // 问题去标点空白再按 2-gram（二元组）切分，用字面重合度近似语义召回。
        List<KnowledgeChunk> all = chunkMapper.selectList(null);
        String normalized = question.replaceAll("[\\p{P}\\s]", "");
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 2 <= normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : all) {
            int score = 0;
            String haystack = chunk.getTitle() + chunk.getContent() + StrUtil.nullToEmpty(chunk.getKeywords());
            // 关键词命中权重 5、每个二元组命中权重 1，标题+正文+关键词都参与匹配；
            // 得分为 0 的片段不返回，保证后续回答一定「有依据」。
            if (chunk.getKeywords() != null) {
                for (String keyword : chunk.getKeywords().split(",")) {
                    if (!keyword.isEmpty() && question.contains(keyword)) {
                        score += 5;
                    }
                }
            }
            for (String gram : grams) {
                if (haystack.contains(gram)) {
                    score += 1;
                }
            }
            if (score > 0) {
                scored.add(new ScoredChunk(chunk, score));
            }
        }
        // 按总分降序取 topK：这是本地 RAG 的召回环节，callLlm/templateAnswer 只基于这些片段作答。
        scored.sort(Comparator.comparingInt((ScoredChunk s) -> s.score).reversed());
        return scored.stream().limit(topK).map(s -> s.chunk).collect(Collectors.toList());
    }

    // 三类规则工具：查最近订单、查在售服务价格、返回预约流程；直接查库组装实时业务信息，
    // 等价于给模型补充上下文（Python Agent 侧则通过 /internal/** 回调 Java 拿同类数据）。
    private List<String> runTools(Long userId, String question) {
        List<String> results = new ArrayList<>();
        if (question.contains("订单") || question.contains("到哪") || question.contains("进度")) {
            Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getUserId, userId)
                    .orderByDesc(Order::getCreateTime)
                    .last("limit 1"));
            if (order != null) {
                results.add("最近订单 " + order.getOrderNo() + " 当前状态 " + order.getStatus()
                        + "，预约时间 " + order.getSlotStart());
            } else {
                results.add("当前没有查询到订单记录");
            }
        }
        if (question.contains("价格") || question.contains("多少钱") || question.contains("收费")) {
            List<ServiceItem> items = serviceItemMapper.selectList(new LambdaQueryWrapper<ServiceItem>()
                    .eq(ServiceItem::getStatus, 1)
                    .orderByAsc(ServiceItem::getSort));
            String prices = items.stream()
                    .map(i -> i.getName() + " 小型 " + i.getPriceS() + " 元 / 中型 " + i.getPriceM()
                            + " 元 / 大型 " + i.getPriceL() + " 元")
                    .collect(Collectors.joining("；"));
            results.add("服务价格：" + prices);
        }
        if (question.contains("预约") || question.contains("上门")) {
            results.add("预约流程：选择服务与宠物 → 选择可约时间 → 填写地址 → 试算价格 → 提交订单并在 15 分钟内支付");
        }
        return results;
    }

    // 无模型/模型失败时的降级回答：拼业务工具结果 + top1/top2 知识片段原文 + 来源，
    // 无命中时给保守提示并固定追加就医提醒，宁可少答也不编造。
    private String templateAnswer(List<KnowledgeChunk> chunks, List<String> tools) {
        StringBuilder sb = new StringBuilder();
        if (!tools.isEmpty()) {
            sb.append("【业务信息】").append(String.join("；", tools)).append("\n\n");
        }
        if (chunks.isEmpty()) {
            sb.append("我暂时没有在知识库里找到可靠依据，建议咨询兽医或平台客服。");
            return sb.toString();
        }
        KnowledgeChunk first = chunks.get(0);
        sb.append(first.getContent());
        if (chunks.size() > 1) {
            sb.append("\n\n补充：").append(chunks.get(1).getContent());
        }
        sb.append("\n\n来源：");
        sb.append(chunks.stream().map(c -> "《" + c.getTitle() + "》(" + c.getSource() + ")")
                .collect(Collectors.joining("、")));
        sb.append("\n如果宠物出现精神差、呕吐、腹泻等异常，请及时就医。");
        return sb.toString();
    }

    // 直连 OpenAI 兼容接口：拼 system 提示 + 宠物档案 + 工具结果 + 检索资料 + 问题，
    // temperature 0.3、max_tokens 500 控制稳定性与成本；返回空则抛异常由 chat() 降级。
    private String callLlm(String question, Pet pet, List<KnowledgeChunk> chunks, List<String> tools) {
        StringBuilder context = new StringBuilder();
        for (KnowledgeChunk chunk : chunks) {
            context.append("《").append(chunk.getTitle()).append("》（").append(chunk.getSource()).append("）：")
                    .append(chunk.getContent()).append("\n");
        }
        String userContent = "宠物档案：" + (pet == null ? "未提供" :
                pet.getSpecies() + " " + StrUtil.nullToEmpty(pet.getBreed()) + "，体重 "
                        + pet.getWeightKg() + "kg，过敏：" + StrUtil.nullToEmpty(pet.getAllergies()))
                + "\n业务信息：" + String.join("；", tools)
                + "\n资料：\n" + context
                + "\n用户问题：" + question;

        JSONArray messages = new JSONArray();
        messages.add(JSONUtil.createObj().set("role", "system").set("content", SYSTEM_PROMPT));
        messages.add(JSONUtil.createObj().set("role", "user").set("content", userContent));

        JSONObject body = JSONUtil.createObj()
                .set("model", properties.getAi().getModel())
                .set("messages", messages)
                .set("temperature", 0.3)
                .set("max_tokens", 500);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 只在这条链路用 apiKey 做 Bearer 鉴权；与 Python 网关用的 X-Internal-Token 是两套凭据。
        headers.setBearerAuth(properties.getAi().getApiKey());
        String response = restTemplate.postForObject(properties.getAi().getBaseUrl() + "/chat/completions",
                new HttpEntity<>(body.toString(), headers), String.class);
        JSONObject json = JSONUtil.parseObj(response);
        String content = json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getStr("content");
        if (StrUtil.isBlank(content)) {
            throw new IllegalStateException("模型返回为空");
        }
        return content;
    }

    // 对话落库到 ai_message：content 截断 2000 字，citations/toolCalls 以 JSON 字符串保存，
    // refused 标记医疗拒答；user 与 assistant 两条消息共用同一 sessionId，history() 据此还原。
    private void save(String sessionId, Long userId, String role, String content,
                      String citations, String tools, int refused) {
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(StrUtil.maxLength(content, 2000));
        message.setCitations(citations);
        message.setToolCalls(tools);
        message.setRefused(refused);
        message.setCreateTime(java.time.LocalDateTime.now());
        messageMapper.insert(message);
    }

    /**
     * 申请一个 AI 并发许可：2 秒内拿不到返回 0（由 AiController 提示「请求较多，请稍后再试」）。
     *
     * @return 1 表示拿到许可，0 表示超时未拿到
     * @throws InterruptedException 等待中被中断（Controller 会恢复中断标志并抛业务异常）
     */
    public int acquire() throws InterruptedException {
        return semaphore.tryAcquire(2, TimeUnit.SECONDS) ? 1 : 0;
    }

    /**
     * 归还并发许可，必须与 acquire() 成对使用（AiController 在 finally 中调用，防止异常时许可泄漏）。
     */
    public void release() {
        semaphore.release();
    }

    // 检索打分的内部载体：片段 + 分数，仅用于 retrieve() 内排序，不落库。
    private static class ScoredChunk {
        private final KnowledgeChunk chunk;
        private final int score;

        ScoredChunk(KnowledgeChunk chunk, int score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
