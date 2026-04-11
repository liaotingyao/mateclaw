package vip.mate.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.*;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDiscoveryService {

    private final ModelProviderService modelProviderService;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // ==================== 模型发现 ====================

    public DiscoverResult discoverModels(String providerId) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        if (!Boolean.TRUE.equals(provider.getSupportModelDiscovery())) {
            throw new MateClawException("err.llm.discovery_not_supported", "该供应商不支持模型发现: " + providerId);
        }

        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        List<ModelInfoDTO> discovered = fetchRemoteModels(provider, protocol);

        // 去重：对比已有模型
        Set<String> existingIds = modelConfigService.listModelsByProvider(providerId).stream()
                .map(ModelConfigEntity::getModelName)
                .collect(Collectors.toSet());
        List<ModelInfoDTO> newModels = discovered.stream()
                .filter(m -> !existingIds.contains(m.getId()))
                .toList();

        return new DiscoverResult(discovered, newModels, discovered.size(), newModels.size());
    }

    // ==================== 连接测试 ====================

    public TestResult testConnection(String providerId) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        long start = System.currentTimeMillis();

        try {
            if (Boolean.TRUE.equals(provider.getSupportModelDiscovery())) {
                // 支持模型发现的 provider：调用模型列表 API 验证连接
                fetchRemoteModels(provider, protocol);
                long latency = System.currentTimeMillis() - start;
                return TestResult.ok(latency, "连接成功");
            } else {
                // 不支持模型发现（如智谱）：用第一个已配置模型发送测试请求
                List<ModelConfigEntity> models = modelConfigService.listModelsByProvider(providerId);
                if (models.isEmpty()) {
                    throw new MateClawException("err.llm.no_model_for_test", "该供应商没有已配置的模型，无法测试连接");
                }
                String testModelId = models.get(0).getModelName();
                String response = sendTestPrompt(provider, protocol, testModelId);
                long latency = System.currentTimeMillis() - start;
                return TestResult.ok(latency, response);
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return TestResult.fail(latency, extractErrorMessage(e));
        }
    }

    // ==================== 单模型测试 ====================

    public TestResult testModel(String providerId, String modelId) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        long start = System.currentTimeMillis();

        try {
            String response = sendTestPrompt(provider, protocol, modelId);
            long latency = System.currentTimeMillis() - start;
            return TestResult.ok(latency, response);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return TestResult.fail(latency, extractErrorMessage(e));
        }
    }

    // ==================== 批量添加发现的模型 ====================

    public int batchAddModels(String providerId, List<String> modelIds) {
        modelProviderService.getProviderConfig(providerId);
        Set<String> existingIds = modelConfigService.listModelsByProvider(providerId).stream()
                .map(ModelConfigEntity::getModelName)
                .collect(Collectors.toSet());

        int added = 0;
        for (String modelId : modelIds) {
            if (!existingIds.contains(modelId)) {
                modelConfigService.addModelToProvider(providerId, modelId, modelId, false);
                added++;
            }
        }
        return added;
    }

    // ==================== 协议分派：模型列表 ====================

    private List<ModelInfoDTO> fetchRemoteModels(ModelProviderEntity provider, ModelProtocol protocol) {
        return switch (protocol) {
            case OPENAI_COMPATIBLE -> fetchOpenAiCompatibleModels(provider);
            case DASHSCOPE_NATIVE -> fetchDashScopeModels(provider);
            case GEMINI_NATIVE -> fetchGeminiModels(provider);
            case ANTHROPIC_MESSAGES -> fetchAnthropicModels(provider);
            case OPENAI_CHATGPT -> throw new MateClawException("err.llm.chatgpt_no_discovery", "ChatGPT OAuth provider 不支持模型发现");
        };
    }

    private List<ModelInfoDTO> fetchOpenAiCompatibleModels(ModelProviderEntity provider) {
        String baseUrl = normalizeBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.llm.base_url_missing", "Base URL 未配置");
        }
        String apiKey = provider.getApiKey();

        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        RestClient.RequestHeadersSpec<?> spec = client.get().uri("/v1/models");
        if (modelProviderService.hasUsableApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }
        // 添加自定义 headers（从 generateKwargs 中读取）
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        applyCustomHeaders(spec, kwargs);

        String body = spec.retrieve().body(String.class);
        return parseOpenAiModelsResponse(body);
    }

    private List<ModelInfoDTO> fetchDashScopeModels(ModelProviderEntity provider) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.dashscope_key_missing", "DashScope API Key 未配置");
        }

        // DashScope 兼容模式暴露了 OpenAI 兼容的 /v1/models 端点
        RestClient client = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .build();

        String body = client.get().uri("/v1/models").retrieve().body(String.class);
        return parseOpenAiModelsResponse(body);
    }

    private List<ModelInfoDTO> fetchGeminiModels(ModelProviderEntity provider) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.gemini_key_missing", "Gemini API Key 未配置");
        }

        RestClient client = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        String body = client.get()
                .uri("/v1beta/models?key={key}", apiKey.trim())
                .retrieve()
                .body(String.class);
        return parseGeminiModelsResponse(body);
    }

    private List<ModelInfoDTO> fetchAnthropicModels(ModelProviderEntity provider) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.anthropic_key_missing", "Anthropic API Key 未配置");
        }

        String baseUrl = StringUtils.hasText(provider.getBaseUrl())
                ? normalizeBaseUrl(provider.getBaseUrl())
                : "https://api.anthropic.com";

        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey.trim())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();

        String body = client.get().uri("/v1/models").retrieve().body(String.class);
        return parseAnthropicModelsResponse(body);
    }

    // ==================== 协议分派：单模型测试 ====================

    private String sendTestPrompt(ModelProviderEntity provider, ModelProtocol protocol, String modelId) {
        return switch (protocol) {
            case OPENAI_COMPATIBLE -> sendOpenAiTestPrompt(provider, modelId);
            case DASHSCOPE_NATIVE -> sendDashScopeTestPrompt(provider, modelId);
            case GEMINI_NATIVE -> sendGeminiTestPrompt(provider, modelId);
            case ANTHROPIC_MESSAGES -> sendAnthropicTestPrompt(provider, modelId);
            case OPENAI_CHATGPT -> throw new MateClawException("err.llm.chatgpt_no_test", "ChatGPT OAuth provider 不支持模型测试");
        };
    }

    private String sendOpenAiTestPrompt(ModelProviderEntity provider, String modelId) {
        String baseUrl = normalizeBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.llm.base_url_missing", "Base URL 未配置");
        }

        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "请回复：连接正常")),
                "max_tokens", 10,
                "temperature", 0
        );

        // 从 generateKwargs 读取 completionsPath（智谱等用 /chat/completions 而非 /v1/chat/completions）
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        String completionsPath = resolveCompletionsPath(baseUrl, kwargs);

        RestClient.RequestHeadersSpec<?> spec = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri(completionsPath)
                .body(requestBody);

        if (modelProviderService.hasUsableApiKey(provider.getApiKey())) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey().trim());
        }
        applyCustomHeaders(spec, kwargs);

        String body = spec.retrieve().body(String.class);
        return extractOpenAiChatContent(body);
    }

    private String sendDashScopeTestPrompt(ModelProviderEntity provider, String modelId) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.dashscope_key_missing", "DashScope API Key 未配置");
        }

        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "请回复：连接正常")),
                "max_tokens", 10,
                "temperature", 0
        );

        String body = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .build()
                .post()
                .uri("/v1/chat/completions")
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return extractOpenAiChatContent(body);
    }

    private String sendGeminiTestPrompt(ModelProviderEntity provider, String modelId) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.gemini_key_missing", "Gemini API Key 未配置");
        }

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", "请回复：连接正常"))
                )),
                "generationConfig", Map.of("maxOutputTokens", 10, "temperature", 0)
        );

        String body = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri("/v1beta/models/{model}:generateContent?key={key}", modelId, apiKey.trim())
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return extractGeminiContent(body);
    }

    private String sendAnthropicTestPrompt(ModelProviderEntity provider, String modelId) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.anthropic_key_missing", "Anthropic API Key 未配置");
        }

        String baseUrl = StringUtils.hasText(provider.getBaseUrl())
                ? normalizeBaseUrl(provider.getBaseUrl())
                : "https://api.anthropic.com";

        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "请回复：连接正常")),
                "max_tokens", 10
        );

        String body = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey.trim())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build()
                .post()
                .uri("/v1/messages")
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return extractAnthropicContent(body);
    }

    // ==================== JSON 解析 ====================

    private List<ModelInfoDTO> parseOpenAiModelsResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return Collections.emptyList();
            }
            List<ModelInfoDTO> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                if (StringUtils.hasText(id)) {
                    models.add(new ModelInfoDTO(id, id));
                }
            }
            return models;
        } catch (Exception e) {
            log.warn("解析 OpenAI 模型列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ModelInfoDTO> parseGeminiModelsResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode models = root.path("models");
            if (!models.isArray()) {
                return Collections.emptyList();
            }
            List<ModelInfoDTO> result = new ArrayList<>();
            for (JsonNode node : models) {
                String name = node.path("name").asText("");
                String displayName = node.path("displayName").asText(name);
                // Gemini 返回 "models/gemini-1.5-pro" 格式，去掉 "models/" 前缀
                if (name.startsWith("models/")) {
                    name = name.substring(7);
                }
                if (StringUtils.hasText(name)) {
                    result.add(new ModelInfoDTO(name, displayName));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析 Gemini 模型列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ModelInfoDTO> parseAnthropicModelsResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return Collections.emptyList();
            }
            List<ModelInfoDTO> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                String displayName = node.path("display_name").asText(id);
                if (StringUtils.hasText(id)) {
                    models.add(new ModelInfoDTO(id, displayName));
                }
            }
            return models;
        } catch (Exception e) {
            log.warn("解析 Anthropic 模型列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String extractOpenAiChatContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("choices").path(0).path("message").path("content").asText("连接正常");
        } catch (Exception e) {
            return "连接正常（响应解析异常）";
        }
    }

    private String extractGeminiContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("连接正常");
        } catch (Exception e) {
            return "连接正常（响应解析异常）";
        }
    }

    private String extractAnthropicContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("content").path(0).path("text").asText("连接正常");
        } catch (Exception e) {
            return "连接正常（响应解析异常）";
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 从 generateKwargs 中解析 completionsPath，处理 baseUrl 与路径前缀的重叠。
     * 例如：baseUrl 以 /v4 结尾，completionsPath 为 /chat/completions → 最终 /chat/completions
     *       baseUrl 以 /v1 结尾，completionsPath 为 /v1/chat/completions → 最终 /chat/completions
     */
    private String resolveCompletionsPath(String baseUrl, Map<String, Object> kwargs) {
        String path = "/v1/chat/completions";
        if (kwargs != null) {
            Object raw = kwargs.get("completionsPath");
            if (raw instanceof String value && StringUtils.hasText(value)) {
                path = value.trim();
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
            }
        }
        // 避免路径重叠：如果 baseUrl 以 /v1 结尾且 path 以 /v1/ 开头，去掉重复
        if (baseUrl != null && baseUrl.endsWith("/v1") && path.startsWith("/v1/")) {
            path = path.substring(3);
        }
        return path;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private void applyCustomHeaders(RestClient.RequestHeadersSpec<?> spec, Map<String, Object> kwargs) {
        if (kwargs == null) {
            return;
        }
        Object customHeaders = kwargs.get("customHeaders");
        if (customHeaders instanceof Map) {
            ((Map<String, Object>) customHeaders).forEach((key, value) -> {
                if (value != null) {
                    spec.header(key, value.toString());
                }
            });
        }
    }

    private String extractErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return "未知错误: " + e.getClass().getSimpleName();
        }
        // 截取合理长度
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return msg;
    }
}
