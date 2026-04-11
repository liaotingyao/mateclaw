package vip.mate.agent;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.*;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

/**
 * Agent 统一工具集合
 * <p>
 * 将 @Tool Bean、ToolCallbackProvider、MCP server 暴露的 tool callbacks
 * 统一收集为一致的 ToolCallback 列表，供 StateGraph 节点使用。
 *
 * @author MateClaw Team
 */
public class AgentToolSet {

    private final List<Object> toolBeans;
    private final List<ToolCallback> callbacks;
    private final Map<String, ToolCallback> callbackByName;

    private AgentToolSet(List<Object> toolBeans, List<ToolCallback> callbacks) {
        this.toolBeans = List.copyOf(toolBeans);
        // 按工具名去重：内置工具在前（先添加），MCP 工具在后，同名时保留内置工具
        // 使用 LinkedHashMap 保证插入顺序，确保内置工具始终排在 MCP 工具前面（影响 LLM 工具选择倾向）
        this.callbackByName = callbacks.stream()
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb,
                        (a, b) -> a,
                        LinkedHashMap::new));
        // callbacks 列表也使用去重后的结果，避免 Spring AI ToolCallingChatOptions 校验重名报错
        this.callbacks = List.copyOf(callbackByName.values());
    }

    /**
     * 从预构建的 ToolCallback 列表构建工具集（用于 i18n 等需要包装 callback 的场景）
     */
    public static AgentToolSet fromCallbacks(List<Object> toolBeans, List<ToolCallback> callbacks) {
        return new AgentToolSet(toolBeans != null ? toolBeans : List.of(), callbacks);
    }

    /**
     * 从 @Tool Bean 列表和 ToolCallbackProvider 列表构建统一工具集
     */
    public static AgentToolSet from(List<Object> toolBeans, List<ToolCallbackProvider> providers) {
        List<ToolCallback> allCallbacks = new ArrayList<>();

        // 收集 @Tool Bean 的 callbacks
        if (toolBeans != null) {
            for (Object bean : toolBeans) {
                ToolCallback[] cbs = ToolCallbacks.from(bean);
                Collections.addAll(allCallbacks, cbs);
            }
        }

        // 收集 ToolCallbackProvider 的 callbacks
        if (providers != null) {
            for (ToolCallbackProvider provider : providers) {
                ToolCallback[] cbs = provider.getToolCallbacks();
                if (cbs != null) {
                    Collections.addAll(allCallbacks, cbs);
                }
            }
        }

        return new AgentToolSet(toolBeans != null ? toolBeans : List.of(), allCallbacks);
    }

    /**
     * 过滤掉 denied 工具后返回新的 AgentToolSet。
     * denied 工具不会暴露给模型，模型完全不知道它们的存在。
     *
     * @param deniedTools denied 工具名集合（为空或 null 时直接返回 this）
     */
    public AgentToolSet withDeniedToolsFiltered(Set<String> deniedTools) {
        if (deniedTools == null || deniedTools.isEmpty()) {
            return this;
        }
        List<ToolCallback> filtered = new ArrayList<>(callbacks);
        filtered.removeIf(cb -> deniedTools.contains(cb.getToolDefinition().name()));
        return new AgentToolSet(toolBeans, filtered);
    }

    /**
     * 仅保留指定名称的工具（白名单模式，用于 per-agent 绑定）
     *
     * @param allowedTools 允许的工具名集合（为 null 时直接返回 this，表示使用全局默认）
     */
    public AgentToolSet withAllowedToolsOnly(Set<String> allowedTools) {
        if (allowedTools == null) {
            return this; // null = 无绑定，使用全局默认
        }
        List<ToolCallback> filtered = new ArrayList<>(callbacks);
        filtered.removeIf(cb -> !allowedTools.contains(cb.getToolDefinition().name()));
        return new AgentToolSet(toolBeans, filtered);
    }

    /**
     * 获取所有 ToolCallback
     */
    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /**
     * 获取按名称索引的 ToolCallback Map
     */
    public Map<String, ToolCallback> callbackByName() {
        return callbackByName;
    }

    /**
     * 获取原始的 @Tool Bean 列表
     */
    public List<Object> toolBeans() {
        return toolBeans;
    }

    /**
     * 返回排除指定工具名后的新 AgentToolSet
     */
    public AgentToolSet excluding(Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return this;
        }
        List<ToolCallback> filtered = callbacks.stream()
                .filter(cb -> !toolNames.contains(cb.getToolDefinition().name()))
                .toList();
        return new AgentToolSet(toolBeans, filtered);
    }

    /**
     * 是否为空（无任何工具）
     */
    public boolean isEmpty() {
        return callbacks.isEmpty();
    }

    /**
     * 工具数量
     */
    public int size() {
        return callbacks.size();
    }
}
