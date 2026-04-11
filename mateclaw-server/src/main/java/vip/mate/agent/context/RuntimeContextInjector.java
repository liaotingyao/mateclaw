package vip.mate.agent.context;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 运行时上下文注入器 — 在 LLM 消息列表中预注入当前时间等运行时信息。
 * <p>
 * 参考 Claude Code 的 prependUserContext 模式：将时间信息作为首条 meta UserMessage 注入，
 * 而非修改 System Prompt，以保持 prompt cache 命中率。
 */
public final class RuntimeContextInjector {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private RuntimeContextInjector() {
    }

    /**
     * 构建运行时上下文消息，包含当前日期和时间。
     */
    public static String buildContextMessage() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        return "[system-context] 当前时间: " + now.format(DATE_FMT)
                + " " + now.format(TIME_FMT) + " (Asia/Shanghai)";
    }

    /**
     * 构建运行时上下文消息，包含当前日期、时间和工作目录。
     */
    public static String buildContextMessage(String workspaceBasePath) {
        StringBuilder sb = new StringBuilder(buildContextMessage());
        if (workspaceBasePath != null && !workspaceBasePath.isBlank()) {
            sb.append("\n[system-context] 工作目录: ").append(workspaceBasePath)
              .append("\n你只能在此目录及其子目录内读写文件和执行命令。");
        }
        return sb.toString();
    }
}
