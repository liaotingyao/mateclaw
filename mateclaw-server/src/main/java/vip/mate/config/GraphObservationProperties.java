package vip.mate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Graph 观察结果处理阈值配置
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.agent.graph.observation")
public class GraphObservationProperties {

    /** 单次工具结果最大字符数 */
    private int maxSingleObservationChars = 4000;

    /** 所有观察记录总字符数上限 */
    private int maxTotalObservationChars = 12000;

    /** 单次结果超过此阈值视为"大结果" */
    private int largeResultThreshold = 3000;

    /** 触发 summarize 的最小观察轮次 */
    private int minRoundsForSummarize = 3;

    /** 截断时保留前部占比（0-1） */
    private double headRatio = 0.4;

    /** 截断省略标记（%d 会被替换为原始字符数） */
    private String truncationMarker = "\n\n... [内容已截断，共 %d 字符，保留前后关键片段] ...\n\n";

    /** 检测到尾部错误模式时的 tail 保留比例（默认 0.8，优先保留错误信息） */
    private double errorTailRatio = 0.8;

    /** 截断时最少保留字符数（避免过度截断导致信息完全丢失） */
    private int minKeepChars = 2000;
}
