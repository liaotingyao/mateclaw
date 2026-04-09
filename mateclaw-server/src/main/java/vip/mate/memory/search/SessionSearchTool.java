package vip.mate.memory.search;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Session search tool — lets the agent search its conversation history.
 * <p>
 * Two modes:
 * - "recent": list recent conversations (metadata only, no LLM cost)
 * - "search": keyword-based full-text search over message content
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSearchTool {

    private final SessionSearchService sessionSearchService;

    @Tool(description = """
            搜索 Agent 的历史对话记录。
            mode 说明：
            - "recent"：列出最近的会话（标题、时间、消息数），不需要 query 参数
            - "search"：按关键词全文搜索消息内容，返回匹配的消息片段
            适用于回忆之前讨论过的话题、查找历史决策、检索之前的上下文。
            """)
    public String session_search(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "当前会话 ID（用于排除当前会话）") String currentConversationId,
            @ToolParam(description = "搜索模式：recent 或 search") String mode,
            @ToolParam(description = "搜索关键词（mode=search 时必填）", required = false) String query,
            @ToolParam(description = "返回结果数量上限，默认 10", required = false) Integer limit) {

        if (agentId == null) {
            return error("agentId 不能为空");
        }
        if (mode == null || mode.isBlank()) {
            mode = "recent";
        }

        int effectiveLimit = limit != null && limit > 0 ? limit : 10;

        try {
            if ("recent".equalsIgnoreCase(mode.trim())) {
                return handleRecent(agentId, effectiveLimit);
            } else if ("search".equalsIgnoreCase(mode.trim())) {
                if (query == null || query.isBlank()) {
                    return error("mode=search 时 query 不能为空");
                }
                return handleSearch(agentId, currentConversationId, query, effectiveLimit);
            } else {
                return error("无效的 mode: " + mode + "，请使用 recent 或 search");
            }
        } catch (Exception e) {
            log.warn("[SessionSearch] Tool call failed: {}", e.getMessage());
            return error("搜索失败: " + e.getMessage());
        }
    }

    private String handleRecent(Long agentId, int limit) {
        List<Map<String, Object>> sessions = sessionSearchService.listRecent(agentId, limit);

        JSONObject result = new JSONObject();
        result.set("mode", "recent");
        result.set("count", sessions.size());
        result.set("sessions", sessions);
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String handleSearch(Long agentId, String currentConversationId,
                                String query, int limit) {
        List<SessionSearchResult> results = sessionSearchService.search(
                agentId, currentConversationId != null ? currentConversationId : "", query, limit);

        JSONObject result = new JSONObject();
        result.set("mode", "search");
        result.set("query", query);
        result.set("count", results.size());
        result.set("matches", results.stream().map(r -> {
            JSONObject item = new JSONObject();
            item.set("conversationId", r.conversationId());
            item.set("title", r.title());
            item.set("role", r.role());
            item.set("snippet", r.snippet());
            item.set("time", r.time() != null ? r.time().toString() : null);
            return item;
        }).toList());
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String error(String message) {
        JSONObject result = new JSONObject();
        result.set("error", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }
}
