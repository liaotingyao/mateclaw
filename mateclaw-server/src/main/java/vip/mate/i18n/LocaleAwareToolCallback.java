package vip.mate.i18n;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 国际化工具回调装饰器
 * <p>
 * 包装原始 ToolCallback，覆写 {@link #getToolDefinition()} 返回本地化描述。
 * 其他方法（call、name 等）全部委托给原始回调。
 *
 * @author MateClaw Team
 */
public class LocaleAwareToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String localizedDescription;

    public LocaleAwareToolCallback(ToolCallback delegate, String localizedDescription) {
        this.delegate = delegate;
        this.localizedDescription = localizedDescription;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition original = delegate.getToolDefinition();
        return ToolDefinition.builder()
                .name(original.name())
                .description(localizedDescription)
                .inputSchema(original.inputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }
}
