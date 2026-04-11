package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.state.MateClawStateAccessor;
import vip.mate.agent.graph.state.MateClawStateKeys;

import java.util.*;
import java.util.concurrent.CancellationException;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * 工具执行节点（ReAct Action 阶段）
 * <p>
 * 委托 {@link ToolExecutionExecutor} 执行工具调用，支持并发执行和审批 barrier。
 * <p>
 * 支持 forced_replay 阶段：当审批通过后的重放调用到达时，跳过 ToolGuard 检查直接执行。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ActionNode implements NodeAction {

    private final ToolExecutionExecutor executor;
    private final vip.mate.channel.web.ChatStreamTracker streamTracker;

    public ActionNode(ToolExecutionExecutor executor) {
        this(executor, null);
    }

    public ActionNode(ToolExecutionExecutor executor, vip.mate.channel.web.ChatStreamTracker streamTracker) {
        this.executor = executor;
        this.streamTracker = streamTracker;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        List<AssistantMessage.ToolCall> toolCalls = state.<List<AssistantMessage.ToolCall>>value(TOOL_CALLS)
                .orElse(List.of());

        MateClawStateAccessor accessor = new MateClawStateAccessor(state);
        String conversationId = accessor.conversationId();
        String agentId = accessor.agentId();

        // 检查停止标志
        if (streamTracker != null && streamTracker.isStopRequested(conversationId)) {
            log.info("[ActionNode] Stop requested, aborting tool execution: conversationId={}", conversationId);
            throw new CancellationException("Stream stopped by user");
        }

        // 检测是否为 forced_replay 阶段（审批通过后的重放）
        String currentPhase = state.value(MateClawStateKeys.CURRENT_PHASE, "");
        boolean isReplay = "forced_replay".equals(currentPhase);

        // 请求者身份（用于审批记录）
        String requesterId = accessor.requesterId();

        // 获取工作区活动目录
        String workspaceBasePath = state.value(MateClawStateKeys.WORKSPACE_BASE_PATH, "");

        // 委托 ToolExecutionExecutor 执行（两阶段：顺序 Guard + 分段并发执行）
        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                toolCalls, conversationId, agentId, isReplay, requesterId, workspaceBasePath);

        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(result.responses())
                .build();

        MateClawStateAccessor.OutputBuilder output = MateClawStateAccessor.output()
                .toolResults(result.responses())
                .messages(List.of((Message) toolResponseMessage))
                .currentPhase("action")
                .events(result.events());

        if (result.awaitingApproval()) {
            output.awaitingApproval(true);
            log.info("[ActionNode] Approval pending detected, setting AWAITING_APPROVAL=true to terminate graph");
        }

        // replay 完成后清空 forced_tool_call，防止下一轮再触发
        if (isReplay) {
            output.forcedToolCall("");
        }

        return output.build();
    }
}
