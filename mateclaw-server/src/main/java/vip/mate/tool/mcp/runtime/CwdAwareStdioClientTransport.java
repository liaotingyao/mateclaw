package vip.mate.tool.mcp.runtime;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Enhanced stdio MCP transport with:
 * <ul>
 *   <li>Working directory (cwd) support</li>
 *   <li>Automatic PATH enrichment for Desktop app environments where
 *       Node.js/npx may not be in the JRE process's PATH</li>
 * </ul>
 */
@Slf4j
public class CwdAwareStdioClientTransport extends StdioClientTransport {

    private final String cwd;

    /** Common Node.js installation paths across platforms */
    private static final String[] NODE_PATH_CANDIDATES = {
        // macOS Homebrew
        "/usr/local/bin",
        "/opt/homebrew/bin",
        // macOS nvm
        System.getProperty("user.home") + "/.nvm/current/bin",
        // Linux common
        "/usr/bin",
        "/usr/local/bin",
        // Linux nvm
        System.getProperty("user.home") + "/.nvm/current/bin",
        // Windows common
        System.getenv("APPDATA") != null ? System.getenv("APPDATA") + "\\npm" : "",
        "C:\\Program Files\\nodejs",
        // pnpm global
        System.getProperty("user.home") + "/.local/share/pnpm",
        System.getProperty("user.home") + "/Library/pnpm",
        // Volta
        System.getProperty("user.home") + "/.volta/bin",
        // fnm
        System.getProperty("user.home") + "/.fnm/current/bin",
    };

    public CwdAwareStdioClientTransport(ServerParameters params, McpJsonMapper jsonMapper, String cwd) {
        super(params, jsonMapper);
        this.cwd = cwd;
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        ProcessBuilder builder = super.getProcessBuilder();
        if (cwd != null && !cwd.isBlank()) {
            builder.directory(new File(cwd));
        }
        enrichPath(builder);
        return builder;
    }

    /**
     * Enrich the process PATH with common Node.js installation directories.
     * Desktop apps (Electron/JRE) often don't inherit the user's shell PATH,
     * causing "npx: command not found" errors.
     */
    private void enrichPath(ProcessBuilder builder) {
        Map<String, String> env = builder.environment();
        String currentPath = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        StringBuilder enriched = new StringBuilder(currentPath);

        for (String candidate : NODE_PATH_CANDIDATES) {
            if (candidate == null || candidate.isEmpty()) continue;
            if (currentPath.contains(candidate)) continue;
            if (Files.isDirectory(Path.of(candidate))) {
                enriched.append(File.pathSeparator).append(candidate);
            }
        }

        // Also try to resolve nvm's actual current version directory
        String nvmDir = System.getenv("NVM_DIR");
        if (nvmDir == null) nvmDir = System.getProperty("user.home") + "/.nvm";
        Path nvmDefault = Path.of(nvmDir, "versions", "node");
        if (Files.isDirectory(nvmDefault)) {
            try (var stream = Files.list(nvmDefault)) {
                stream.filter(Files::isDirectory)
                      .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                      .findFirst()
                      .ifPresent(nodeDir -> {
                          String binPath = nodeDir.resolve("bin").toString();
                          if (!currentPath.contains(binPath)) {
                              enriched.append(File.pathSeparator).append(binPath);
                          }
                      });
            } catch (Exception ignored) {}
        }

        String finalPath = enriched.toString();
        env.put("PATH", finalPath);
        // Windows uses "Path" key
        if (env.containsKey("Path")) {
            env.put("Path", finalPath);
        }

        if (!finalPath.equals(currentPath)) {
            log.debug("[MCP] Enriched PATH for subprocess: added {} entries",
                      finalPath.split(File.pathSeparator).length - currentPath.split(File.pathSeparator).length);
        }
    }
}
