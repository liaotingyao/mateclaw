package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 内置工具：Agent 委派
 * <p>
 * 允许当前 Agent 将子任务委派给另一个 Agent 执行，实现多 Agent 协作。
 * 被委派的 Agent 在独立会话中运行，结果作为工具观察返回给调用方。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegateAgentTool {

    private static final int MAX_DELEGATION_DEPTH = 3;
    private static final int MAX_RESULT_LENGTH = 4000;

    private final AgentService agentService;
    private final AgentMapper agentMapper;

    @Tool(description = """
            Delegate a task to another Agent for multi-agent collaboration. \
            Target Agent executes in an independent session and returns its final reply. \
            Provide complete task context.""")
    public String delegateToAgent(
            @ToolParam(description = "Target Agent name (exact match)") String agentName,
            @ToolParam(description = "Task description with complete context information") String task) {

        // 1. 参数校验
        if (agentName == null || agentName.isBlank()) {
            return "[错误] 请指定目标 Agent 名称。" + availableAgentsHint();
        }
        if (task == null || task.isBlank()) {
            return "[错误] 请提供任务描述。";
        }

        // 2. 递归深度检查
        int depth = DelegationContext.currentDepth();
        if (depth >= MAX_DELEGATION_DEPTH) {
            return "[错误] 委派层级已达上限（" + MAX_DELEGATION_DEPTH + " 层），无法继续委派，请直接处理任务。";
        }

        // 3. 按名称查找目标 Agent
        AgentEntity target = agentMapper.selectOne(
                new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getName, agentName.trim())
                        .eq(AgentEntity::getEnabled, true));

        if (target == null) {
            return "[错误] 未找到名为「" + agentName + "」的已启用 Agent。" + availableAgentsHint();
        }

        // 4. 在独立会话中执行
        String tempConversationId = "delegate-" + UUID.randomUUID();
        log.info("Agent 委派: depth={}, target={}({}), task={}",
                depth + 1, target.getName(), target.getId(),
                task.length() > 100 ? task.substring(0, 100) + "..." : task);

        DelegationContext.enter();
        try {
            String result = agentService.chat(target.getId(), task, tempConversationId);
            String truncated = truncate(result, MAX_RESULT_LENGTH);
            return "[Agent「" + target.getName() + "」的回复]\n\n" + truncated;
        } catch (Exception e) {
            log.error("Agent 委派执行失败: target={}, error={}", target.getName(), e.getMessage(), e);
            return "[错误] Agent「" + target.getName() + "」执行失败: " + e.getMessage();
        } finally {
            DelegationContext.exit();
        }
    }

    @Tool(description = "List all available Agents (enabled), including name, type, and description.")
    public String listAvailableAgents() {
        List<AgentEntity> agents = agentMapper.selectList(
                new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getEnabled, true)
                        .orderByAsc(AgentEntity::getName));

        if (agents.isEmpty()) {
            return "当前没有可用的 Agent。";
        }

        StringBuilder sb = new StringBuilder("可用 Agent 列表：\n\n");
        for (AgentEntity agent : agents) {
            sb.append("- **").append(agent.getName()).append("**");
            sb.append(" (").append(agent.getAgentType()).append(")");
            if (agent.getDescription() != null && !agent.getDescription().isBlank()) {
                sb.append(" — ").append(agent.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String availableAgentsHint() {
        List<AgentEntity> agents = agentMapper.selectList(
                new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getEnabled, true)
                        .select(AgentEntity::getName));
        if (agents.isEmpty()) {
            return "";
        }
        String names = agents.stream()
                .map(AgentEntity::getName)
                .collect(Collectors.joining("、"));
        return "\n可用 Agent: " + names;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n\n... [结果已截断，原文共 " + text.length() + " 字符]";
    }
}
