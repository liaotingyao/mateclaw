package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;

import java.util.Map;

/**
 * 直接回答节点
 * <p>
 * 当 PlanGenerationNode 判定用户消息是简单问答时，
 * 将 direct_answer 透传为 final_summary，直接结束图执行。
 * <p>
 * 如果 PlanGenerationNode 已通过 broadcastContent() 推送了内容
 * （contentStreamed=true），则不再复制到 FINAL_SUMMARY，
 * 避免 StreamAccumulator 重复收集导致持久化内容翻倍。
 *
 * @author MateClaw Team
 */
public class DirectAnswerNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) {
        boolean alreadyStreamed = state.value(MateClawStateKeys.CONTENT_STREAMED, false);
        if (alreadyStreamed) {
            // broadcastContent 已推送并被 accumulator 收集，不重复写入 FINAL_SUMMARY
            return Map.of();
        }
        String directAnswer = state.value(PlanStateKeys.DIRECT_ANSWER, "");
        return Map.of(PlanStateKeys.FINAL_SUMMARY, directAnswer);
    }
}
