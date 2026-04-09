package vip.mate.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;

/**
 * 记忆自动更新配置
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.memory")
public class MemoryProperties {

    /** 启用对话后自动记忆提取 */
    private boolean autoSummarizeEnabled = true;

    /** 触发记忆提取的最小消息数 */
    private int minMessagesForSummarize = 4;

    /** 触发记忆提取的最小用户消息长度 */
    private int minUserMessageLength = 10;

    /** 跳过 cron 触发的对话（避免递归写入） */
    private boolean skipCronConversations = true;

    /** 记忆摘要的最大输出 token 数 */
    private int summaryMaxTokens = 1000;

    /** 启用定期记忆整合（daily notes → MEMORY.md） */
    private boolean emergenceEnabled = true;

    /** 记忆整合扫描的天数范围 */
    private int emergenceDayRange = 7;

    /** 同一 Agent 记忆提取的冷却时间（分钟） */
    private int cooldownMinutes = 5;

    /** 构建对话 transcript 时的最大消息数（防止过长） */
    private int maxTranscriptMessages = 30;

    // ==================== Dreaming 配置 ====================

    /** 启用定时 Dreaming（自动执行记忆整合） */
    private boolean dreamingEnabled = true;

    /** Dreaming 调度 cron 表达式（Spring 6 字段格式，默认每天凌晨 3 点） */
    private String dreamingCron = "0 0 3 * * ?";

    /** 记忆召回评分阈值，低于此分的候选不进入 LLM 整合 */
    private double emergenceScoreThreshold = 0.4;

    /** 最少召回次数门控（低于此值直接跳过评分） */
    private int emergenceMinRecallCount = 3;

    /** 最少不同查询数门控（低于此值直接跳过评分） */
    private int emergenceMinUniqueQueries = 2;

    /** 候选最大年龄（天），超过此值不参与评分。0=不限 */
    private int emergenceMaxAgeDays = 30;

    // ==================== Memory Nudge 配置 ====================

    /** 启用对话中记忆自省（每 N 轮异步提取结构化记忆） */
    private boolean nudgeEnabled = true;

    /** 每多少轮消息触发一次 Nudge（0=关闭） */
    private int nudgeTurnInterval = 6;

    /** Nudge 审查的最大消息数 */
    private int nudgeMaxMessages = 20;

    /** 同一 Agent Nudge 冷却时间（分钟） */
    private int nudgeCooldownMinutes = 10;

    // ==================== Provider 管理 ====================

    /** 禁用的 MemoryProvider ID 集合（例如 "structured", "session_search"） */
    private Set<String> disabledProviders = new HashSet<>();
}
