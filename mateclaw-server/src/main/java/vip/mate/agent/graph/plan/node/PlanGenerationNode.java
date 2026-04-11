package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.context.RuntimeContextInjector;
import vip.mate.planning.service.PlanningService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 计划生成节点
 * <p>
 * 职责：
 * <ol>
 *   <li>判断是否需要规划（简单问答快速退出）</li>
 *   <li>如需规划：生成计划 JSON、解析、校验</li>
 *   <li>调 PlanningService.createPlan() 持久化</li>
 *   <li>发布 plan_created 事件</li>
 * </ol>
 * <p>
 * 使用 {@link NodeStreamingChatHelper} 进行流式调用。
 * 即便最终返回 JSON，也允许模型的 planning 输出以流式产生，最终再聚合解析。
 * 直接回答路径也通过流式 helper 实时输出给前端。
 *
 * @author MateClaw Team
 */
@Slf4j
public class PlanGenerationNode implements NodeAction {

    private final ChatModel chatModel;
    private final PlanningService planningService;
    private final NodeStreamingChatHelper streamingHelper;
    private final ConversationWindowManager conversationWindowManager;
    private final AgentToolSet toolSet;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PLANNING_PROMPT = """
            你是任务规划器，不是聊天助手。

            你的输出必须满足以下规则：
            1. 只能返回一个 JSON 对象。
            2. 不允许输出任何 JSON 之外的文字。
            3. 不允许使用 markdown 代码块。
            4. 不要解释，不要寒暄，不要先说"我来...""我先..."。

            返回格式二选一：

            不需要规划时：
            {"needs_planning": false, "direct_answer": "..."}

            需要规划时：
            {"needs_planning": true, "steps": ["步骤1", "步骤2", "步骤3"]}

            要求：
            - steps 数量 2 到 6 个。
            - 每个步骤必须是可执行动作，不要写空话。
            - 默认不要把 MEMORY.md、PROFILE.md、记忆文件当成独立步骤；但如果用户目标明显依赖历史偏好、长期约束、过往决策或持续上下文，可以加入必要的记忆读取步骤。
            - 不要把技能文件当成独立步骤，除非用户任务明确要求。
            - 如果用户目标需要调用任何工具才能完成（包括记忆读写、文件操作、搜索、命令执行等），必须返回 needs_planning: true。只有纯知识问答（不需要调用任何工具的简单问题）才返回 needs_planning: false。
            - 如果无法确定，也必须返回合法 JSON，不能输出自然语言。
            """;

    public PlanGenerationNode(ChatModel chatModel, PlanningService planningService,
                              NodeStreamingChatHelper streamingHelper,
                              ConversationWindowManager conversationWindowManager,
                              AgentToolSet toolSet) {
        this.chatModel = chatModel;
        this.planningService = planningService;
        this.streamingHelper = streamingHelper;
        this.conversationWindowManager = conversationWindowManager;
        this.toolSet = toolSet;
    }

    /**
     * @deprecated Use constructor with full parameters
     */
    @Deprecated
    public PlanGenerationNode(ChatModel chatModel, PlanningService planningService) {
        this(chatModel, planningService, null, null, null);
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        PlanStateAccessor accessor = new PlanStateAccessor(state);
        String goal = accessor.goal();
        String systemPrompt = accessor.systemPrompt();
        String agentId = state.value(MateClawStateKeys.TRACE_ID, "unknown");
        String conversationId = accessor.conversationId();

        log.info("[PlanGeneration] Evaluating goal: {}", goal.length() > 100 ? goal.substring(0, 100) + "..." : goal);

        List<GraphEventPublisher.GraphEvent> events = new ArrayList<>();
        events.add(GraphEventPublisher.phase("planning", Map.of("goal", goal)));

        // Replay 模式：计划已在 state 中（由 chatWithReplayStream 注入），直接跳过 LLM
        Long existingPlanId = state.<Long>value(PlanStateKeys.PLAN_ID).orElse(null);
        if (existingPlanId != null) {
            List<String> existingSteps = accessor.planSteps();
            int resumeIndex = accessor.currentStepIndex();
            log.info("[PlanGeneration] Replay mode — reusing plan {} at step {}/{}", existingPlanId, resumeIndex, existingSteps.size());
            return PlanStateAccessor.output()
                    .needsPlanning(true)
                    .planId(existingPlanId)
                    .planSteps(existingSteps)
                    .planValid(true)
                    .currentStepIndex(resumeIndex)
                    .currentPhase("plan_generated")
                    .events(events)
                    .build();
        }

        try {
            // 构建 prompt 消息列表：PLANNING_PROMPT 作为独立 system message，
            // 不拼接完整 systemPrompt（wiki/技能/记忆指南等与规划决策无关，
            // 拼接后会稀释 PLANNING_PROMPT 的指令优先级）
            List<Message> promptMessages = new ArrayList<>();
            promptMessages.add(new SystemMessage(PLANNING_PROMPT));
            // 注入运行时上下文（当前时间 + 工作目录）
            String workspaceBasePath = state.value(MateClawStateKeys.WORKSPACE_BASE_PATH, "");
            promptMessages.add(new UserMessage(RuntimeContextInjector.buildContextMessage(workspaceBasePath)));

            // 注入可用工具名称，帮助 LLM 判断用户目标是否需要工具
            if (toolSet != null && !toolSet.callbacks().isEmpty()) {
                String toolNames = toolSet.callbacks().stream()
                        .map(cb -> cb.getToolDefinition().name())
                        .collect(Collectors.joining(", "));
                promptMessages.add(new UserMessage(
                        "你可以使用以下工具：" + toolNames
                                + "\n如果用户目标需要调用任何工具才能完成，必须返回 needs_planning: true。"));
            }

            // 注入 working context（对话历史摘要），让规划能感知之前对话的约束和补充条件
            String workingContext = accessor.workingContext();
            if (!workingContext.isEmpty()) {
                promptMessages.add(new UserMessage(
                        "以下是此前对话中用户提出的约束、说明和上下文，请在规划时充分考虑：\n\n"
                                + workingContext));
            }

            promptMessages.add(new UserMessage("用户目标：" + goal));

            Prompt prompt = new Prompt(promptMessages);

            // 静默流式调用 LLM — 返回结构化 JSON，不直接推送给前端
            NodeStreamingChatHelper.StreamResult result = streamingHelper.streamCallSilent(
                    chatModel, prompt, conversationId, "plan_generation");

            // PTL 处理：压缩后重试
            if (result.isPromptTooLong() && conversationWindowManager != null) {
                log.warn("[PlanGeneration] Prompt too long, attempting compaction and retry");
                List<Message> compactedMessages = conversationWindowManager.compactForRetry(
                        promptMessages.subList(1, promptMessages.size()));
                if (compactedMessages != null) {
                    List<Message> retryMessages = new ArrayList<>();
                    retryMessages.add(promptMessages.get(0));
                    retryMessages.addAll(compactedMessages);
                    result = streamingHelper.streamCallSilent(
                            chatModel, new Prompt(retryMessages), conversationId, "plan_generation_compact_retry");
                }
            }

            String llmResponse = result.text();
            log.debug("[PlanGeneration] LLM response: {}", llmResponse);

            // 清理 markdown 代码块标记
            String cleanedJson = cleanJsonResponse(llmResponse);

            // 解析 JSON
            Map<String, Object> parsed = objectMapper.readValue(cleanedJson, new TypeReference<>() {});
            boolean needsPlanning = Boolean.TRUE.equals(parsed.get("needs_planning"));

            if (!needsPlanning) {
                // 简单问答快速退出 — 解析出 direct_answer 后手动推送给前端
                String directAnswer = parsed.get("direct_answer") != null
                        ? parsed.get("direct_answer").toString() : llmResponse;
                log.info("[PlanGeneration] Simple question detected, returning direct answer");

                // 手动广播 direct_answer 文本（而不是原始 JSON）
                streamingHelper.broadcastContent(conversationId, directAnswer);

                return PlanStateAccessor.output()
                        .needsPlanning(false)
                        .directAnswer(directAnswer)
                        .currentPhase("direct_answer")
                        .contentStreamed(true)
                        .thinkingStreamed(!result.thinking().isEmpty())
                        .mergeUsage(state, result)
                        .events(events)
                        .build();
            }

            // 需要规划：提取步骤
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) parsed.get("steps");
            if (steps == null || steps.isEmpty()) {
                log.warn("[PlanGeneration] LLM returned needs_planning=true but empty steps, falling back to direct answer");
                return PlanStateAccessor.output()
                        .needsPlanning(false)
                        .directAnswer(llmResponse)
                        .currentPhase("direct_answer")
                        .contentStreamed(true)
                        .thinkingStreamed(!result.thinking().isEmpty())
                        .mergeUsage(state, result)
                        .events(events)
                        .build();
            }

            // 持久化计划
            var plan = planningService.createPlan(agentId, goal, steps);
            log.info("[PlanGeneration] Plan created: id={}, steps={}", plan.getId(), steps.size());

            // 发布 plan_created 事件
            events.add(GraphEventPublisher.planCreated(plan.getId(), steps));

            return PlanStateAccessor.output()
                    .needsPlanning(true)
                    .planId(plan.getId())
                    .planSteps(steps)
                    .planValid(true)
                    .currentStepIndex(0)
                    .currentPhase("plan_generated")
                    .contentStreamed(true)
                    .thinkingStreamed(!result.thinking().isEmpty())
                    .mergeUsage(state, result)
                    .events(events)
                    .build();

        } catch (Exception e) {
            log.error("[PlanGeneration] Failed to generate plan: {}", e.getMessage(), e);
            // 降级：作为简单问答处理，不向前端暴露内部异常细节
            return PlanStateAccessor.output()
                    .needsPlanning(false)
                    .directAnswer("抱歉，我暂时无法完成规划，请重试或换一种方式描述任务。")
                    .currentPhase("direct_answer")
                    .events(events)
                    .build();
        }
    }

    /**
     * 清理 LLM 返回的 JSON，移除可能的 markdown 代码块标记。
     * 若响应中不包含合法的 JSON 对象，抛出异常让调用方走降级路径。
     */
    private String cleanJsonResponse(String response) {
        if (response == null) {
            throw new IllegalArgumentException("LLM returned null response");
        }
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("```json?\\n?", "").replaceAll("```", "").trim();
        }
        // 找到第一个 { 和最后一个 }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException(
                    "LLM response does not contain a valid JSON object: " + cleaned.substring(0, Math.min(80, cleaned.length())));
        }
        return cleaned.substring(start, end + 1);
    }
}
