package vip.mate.tool.guard;

import lombok.extern.slf4j.Slf4j;
import vip.mate.tool.builtin.ToolExecutionContext;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工作区路径沙箱校验器
 * <p>
 * 限制文件工具只能在工作区活动目录（basePath）及其子目录内操作。
 * 跨平台支持：Windows / macOS / Linux 路径统一处理。
 * <p>
 * 使用方式：在文件工具方法开头调用 {@link #validatePath(String)} 获取规范化路径。
 *
 * @author MateClaw Team
 */
@Slf4j
public final class WorkspacePathGuard {

    private WorkspacePathGuard() {}

    /**
     * 校验文件路径是否在当前工作区活动目录范围内。
     * <p>
     * 从 {@link ToolExecutionContext#workspaceBasePath()} 读取当前活动目录。
     * 为空时不限制（向后兼容）。
     *
     * @param rawPath 用户传入的原始路径
     * @return 规范化后的绝对路径
     * @throws IllegalArgumentException 路径不在允许范围内
     */
    public static Path validatePath(String rawPath) {
        Path normalized = Paths.get(rawPath).toAbsolutePath().normalize();

        String basePath = ToolExecutionContext.workspaceBasePath();
        if (basePath == null || basePath.isBlank()) {
            return normalized; // 未配置活动目录，不限制
        }

        Path root = Paths.get(basePath).toAbsolutePath().normalize();

        // 先用 normalize 检查，再尝试 toRealPath 防符号链接逃逸
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Path is outside workspace boundary: " + normalized + ", allowed root: " + root);
        }

        // 对已存在的路径，解析符号链接后再次校验
        try {
            if (normalized.toFile().exists()) {
                Path realPath = normalized.toRealPath();
                Path realRoot = root.toFile().exists() ? root.toRealPath() : root;
                if (!realPath.startsWith(realRoot)) {
                    throw new IllegalArgumentException(
                            "Path escapes workspace via symlink: " + realPath + ", allowed root: " + realRoot);
                }
                return realPath;
            }
        } catch (IOException e) {
            log.debug("[WorkspacePathGuard] 无法解析真实路径（文件可能不存在）: {}", normalized);
        }

        return normalized;
    }

    /**
     * 获取当前工作区活动目录的 Path（用于设置 Shell 工作目录等）。
     *
     * @return 活动目录 Path，未配置时返回 null
     */
    public static Path getWorkingDirectory() {
        String basePath = ToolExecutionContext.workspaceBasePath();
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        return Paths.get(basePath).toAbsolutePath().normalize();
    }
}
