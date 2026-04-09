package vip.mate.memory.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.memory.service.StructuredMemoryService;
import vip.mate.memory.spi.MemoryProvider;

import java.util.List;

/**
 * Structured memory provider — contributes typed memory entries
 * (user/feedback/project/reference) to the system prompt.
 * <p>
 * Tool beans (StructuredMemoryTool) are auto-discovered by ToolRegistry's
 * component scan, so getToolBeans() returns empty.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructuredMemoryProvider implements MemoryProvider {

    private final StructuredMemoryService structuredMemoryService;

    @Override
    public String id() {
        return "structured";
    }

    @Override
    public int order() {
        return 10; // after builtin (0)
    }

    /**
     * Returns typed memory entries formatted as a Markdown block
     * for system prompt injection.
     */
    @Override
    public String systemPromptBlock(Long agentId) {
        try {
            return structuredMemoryService.buildMemoryBlock(agentId);
        } catch (Exception e) {
            log.warn("[StructuredMemory] Failed to build memory block for agent={}: {}",
                    agentId, e.getMessage());
            return "";
        }
    }

    /**
     * Tools are auto-discovered by ToolRegistry component scan.
     */
    @Override
    public List<Object> getToolBeans() {
        return List.of();
    }
}
