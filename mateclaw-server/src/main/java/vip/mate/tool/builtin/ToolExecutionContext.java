package vip.mate.tool.builtin;

/**
 * 工具执行上下文 — 通过 ThreadLocal 向 @Tool 方法传递执行环境信息
 * <p>
 * 在 ToolExecutionExecutor.executeSingleTool() 中 set，在 finally 中 clear。
 * 视频生成等需要知道 conversationId 的工具从此处获取。
 *
 * @author MateClaw Team
 */
public final class ToolExecutionContext {

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    /** 工作区活动目录（为空不限制） */
    private static final ThreadLocal<String> WORKSPACE_BASE_PATH = new ThreadLocal<>();

    private ToolExecutionContext() {}

    public static void set(String conversationId, String username) {
        CONVERSATION_ID.set(conversationId);
        USERNAME.set(username);
        WORKSPACE_BASE_PATH.remove();
    }

    public static void set(String conversationId, String username, String workspaceBasePath) {
        CONVERSATION_ID.set(conversationId);
        USERNAME.set(username);
        WORKSPACE_BASE_PATH.set(workspaceBasePath);
    }

    public static String conversationId() {
        return CONVERSATION_ID.get();
    }

    public static String username() {
        return USERNAME.get();
    }

    /** 获取当前工作区活动目录，为 null 表示不限制 */
    public static String workspaceBasePath() {
        return WORKSPACE_BASE_PATH.get();
    }

    public static void clear() {
        CONVERSATION_ID.remove();
        USERNAME.remove();
        WORKSPACE_BASE_PATH.remove();
    }
}
