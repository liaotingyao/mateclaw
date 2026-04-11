package vip.mate.llm.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.repository.ModelProviderMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI OAuth 服务 — 基于 PKCE 的 OAuth 2.0 流程。
 * <p>
 * 核心机制：在本地 1455 端口启动临时 HTTP 服务器接收回调，
 * redirect_uri 固定为 http://localhost:1455/auth/callback（与 OpenAI 注册的一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAIOAuthService {

    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize";
    private static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    private static final String REDIRECT_URI = "http://localhost:1455/auth/callback";
    private static final String SCOPES = "openid profile email offline_access";
    private static final String PROVIDER_ID = "openai-chatgpt";
    private static final int CALLBACK_PORT = 1455;

    private final ModelProviderMapper modelProviderMapper;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    /** state → code_verifier 缓存 */
    private final ConcurrentHashMap<String, String> pendingStates = new ConcurrentHashMap<>();

    /** 当前运行中的回调服务器（用于启动新服务器前关闭旧的） */
    private volatile HttpServer activeCallbackServer;

    // ==================== OAuth 流程 ====================

    /**
     * 生成授权 URL 并启动本地回调服务器。
     * <p>
     * 流程：
     * 1. 生成 PKCE code_verifier + code_challenge
     * 2. 启动 localhost:1455 临时 HTTP 服务器
     * 3. 返回授权 URL，前端打开浏览器
     * 4. 用户在 OpenAI 登录后，浏览器重定向到 localhost:1455/auth/callback
     * 5. 临时服务器收到 code，交换 token，保存凭证
     */
    public OAuthAuthorizeResult buildAuthorizeUrl() {
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state = generateState();

        pendingStates.put(state, codeVerifier);

        // 启动本地回调服务器（异步等待回调）
        startCallbackServer(state);

        String url = AUTHORIZE_URL
                + "?response_type=code"
                + "&client_id=" + enc(CLIENT_ID)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&scope=" + enc(SCOPES)
                + "&code_challenge=" + enc(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + enc(state)
                + "&id_token_add_organizations=true"
                + "&codex_cli_simplified_flow=true"
                + "&originator=pi";

        return new OAuthAuthorizeResult(url, state);
    }

    /**
     * 启动临时 HTTP 服务器在 localhost:1455 监听回调
     */
    private void startCallbackServer(String expectedState) {
        // 关闭上一次可能残留的回调服务器
        stopActiveCallbackServer();

        CompletableFuture.runAsync(() -> {
            HttpServer server = null;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", CALLBACK_PORT), 0);
                final HttpServer srv = server;

                server.createContext("/auth/callback", exchange -> {
                    try {
                        String query = exchange.getRequestURI().getQuery();
                        String code = extractParam(query, "code");
                        String state = extractParam(query, "state");

                        if (!expectedState.equals(state)) {
                            String errorHtml = "<html><body><h1>State mismatch</h1><p>OAuth state 不匹配，请重试。</p></body></html>";
                            byte[] bytes = errorHtml.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                            exchange.sendResponseHeaders(400, bytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                            return;
                        }

                        if (code == null || code.isBlank()) {
                            String errorHtml = "<html><body><h1>Missing code</h1><p>缺少授权码。</p></body></html>";
                            byte[] bytes = errorHtml.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                            exchange.sendResponseHeaders(400, bytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                            return;
                        }

                        // 交换 token
                        try {
                            exchangeToken(code, state);
                            String successHtml = "<html><body><h1>✓ 登录成功</h1>"
                                    + "<p>OpenAI OAuth 授权完成，您可以关闭此窗口。</p>"
                                    + "<script>setTimeout(function(){window.close()},2000)</script>"
                                    + "</body></html>";
                            byte[] bytes = successHtml.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                            exchange.sendResponseHeaders(200, bytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                        } catch (Exception e) {
                            log.error("OAuth token 交换失败", e);
                            String errorHtml = "<html><body><h1>Token 交换失败</h1><p>" + e.getMessage() + "</p></body></html>";
                            byte[] bytes = errorHtml.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                            exchange.sendResponseHeaders(500, bytes.length);
                            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                        }
                    } finally {
                        // 收到回调后关闭服务器
                        srv.stop(1);
                        activeCallbackServer = null;
                        log.info("OAuth 回调服务器已关闭");
                    }
                });

                server.start();
                activeCallbackServer = server;
                log.info("OAuth 回调服务器已启动在 http://127.0.0.1:{}", CALLBACK_PORT);

                // 3 分钟超时自动关闭
                final HttpServer finalServer = server;
                CompletableFuture.delayedExecutor(3, TimeUnit.MINUTES).execute(() -> {
                    try {
                        finalServer.stop(0);
                        if (activeCallbackServer == finalServer) {
                            activeCallbackServer = null;
                        }
                        pendingStates.remove(expectedState);
                        log.info("OAuth 回调服务器超时关闭");
                    } catch (Exception ignored) {}
                });

            } catch (java.net.BindException e) {
                log.warn("端口 {} 已被占用，OAuth 回调服务器启动失败: {}", CALLBACK_PORT, e.getMessage());
                pendingStates.remove(expectedState);
            } catch (Exception e) {
                log.error("OAuth 回调服务器启动失败", e);
                pendingStates.remove(expectedState);
                if (server != null) server.stop(0);
            }
        });
    }

    /**
     * 用 authorization code 换取 token（内部调用，由回调服务器触发）
     */
    private void exchangeToken(String code, String state) {
        String codeVerifier = pendingStates.remove(state);
        if (codeVerifier == null) {
            throw new MateClawException("err.llm.oauth_state_invalid", "无效的 OAuth state，可能已过期或重复使用");
        }

        String body = "grant_type=authorization_code"
                + "&client_id=" + enc(CLIENT_ID)
                + "&code=" + enc(code)
                + "&code_verifier=" + enc(codeVerifier)
                + "&redirect_uri=" + enc(REDIRECT_URI);

        JsonNode tokenResponse = postTokenRequest(body);
        saveTokens(tokenResponse);
    }

    /**
     * 刷新 access_token
     */
    public void refreshToken() {
        ModelProviderEntity provider = getProvider();
        if (!StringUtils.hasText(provider.getOauthRefreshToken())) {
            throw new MateClawException("err.llm.oauth_no_refresh", "无 refresh_token，请重新登录");
        }

        String body = "grant_type=refresh_token"
                + "&refresh_token=" + enc(provider.getOauthRefreshToken())
                + "&client_id=" + enc(CLIENT_ID);

        JsonNode tokenResponse = postTokenRequest(body);
        saveTokens(tokenResponse);
    }

    /**
     * 确保 access_token 有效（过期时自动刷新）
     */
    public String ensureValidAccessToken() {
        ModelProviderEntity provider = getProvider();
        if (!StringUtils.hasText(provider.getOauthAccessToken())) {
            throw new MateClawException("err.llm.oauth_not_connected", "未连接 OpenAI OAuth，请先登录");
        }

        // 提前 5 分钟刷新
        if (provider.getOauthExpiresAt() != null
                && System.currentTimeMillis() > provider.getOauthExpiresAt() - 300_000) {
            log.info("OpenAI OAuth token 即将过期，自动刷新...");
            refreshToken();
            provider = getProvider();
        }

        return provider.getOauthAccessToken();
    }

    /**
     * 获取 account_id（用于请求 header）
     */
    public String getAccountId() {
        ModelProviderEntity provider = getProvider();
        String accountId = provider.getOauthAccountId();
        // 兼容修复：旧版 JWT 解析字段名错误导致 accountId 为空，从现有 token 重新解析
        if (!StringUtils.hasText(accountId) && StringUtils.hasText(provider.getOauthAccessToken())) {
            accountId = extractAccountIdFromJwt(provider.getOauthAccessToken());
            if (StringUtils.hasText(accountId)) {
                provider.setOauthAccountId(accountId);
                modelProviderMapper.updateById(provider);
                log.info("从已有 token 重新解析并保存 accountId={}", accountId);
            }
        }
        return accountId;
    }

    /**
     * 清除 OAuth 凭证
     */
    public void revokeToken() {
        // MyBatis Plus updateById 默认跳过 null 字段，必须用 LambdaUpdateWrapper 显式置空
        modelProviderMapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getProviderId, PROVIDER_ID)
                .set(ModelProviderEntity::getOauthAccessToken, null)
                .set(ModelProviderEntity::getOauthRefreshToken, null)
                .set(ModelProviderEntity::getOauthExpiresAt, null)
                .set(ModelProviderEntity::getOauthAccountId, null));
        log.info("OpenAI OAuth 凭证已清除");
    }

    /**
     * 获取 OAuth 连接状态
     */
    public OAuthStatusResult getStatus() {
        ModelProviderEntity provider = modelProviderMapper.selectById(PROVIDER_ID);
        if (provider == null || !StringUtils.hasText(provider.getOauthAccessToken())) {
            return new OAuthStatusResult(false, false, null);
        }
        boolean expired = provider.getOauthExpiresAt() != null
                && System.currentTimeMillis() > provider.getOauthExpiresAt();
        return new OAuthStatusResult(true, expired, provider.getOauthExpiresAt());
    }

    // ==================== 内部工具方法 ====================

    private JsonNode postTokenRequest(String formBody) {
        try {
            String response = restClient.post()
                    .uri(TOKEN_URL)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .body(formBody)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            log.error("OpenAI OAuth token 请求失败", e);
            throw new MateClawException("err.llm.oauth_exchange_failed", "OAuth token 交换失败: " + e.getMessage());
        }
    }

    private void saveTokens(JsonNode tokenResponse) {
        String accessToken = tokenResponse.path("access_token").asText(null);
        String refreshToken = tokenResponse.path("refresh_token").asText(null);
        int expiresIn = tokenResponse.path("expires_in").asInt(3600);

        if (!StringUtils.hasText(accessToken)) {
            throw new MateClawException("err.llm.oauth_no_token", "OAuth 响应中缺少 access_token");
        }

        String accountId = extractAccountIdFromJwt(accessToken);

        ModelProviderEntity provider = getProvider();
        provider.setOauthAccessToken(accessToken);
        if (StringUtils.hasText(refreshToken)) {
            provider.setOauthRefreshToken(refreshToken);
        }
        provider.setOauthExpiresAt(System.currentTimeMillis() + (long) expiresIn * 1000);
        if (StringUtils.hasText(accountId)) {
            provider.setOauthAccountId(accountId);
        }
        modelProviderMapper.updateById(provider);
        log.info("OpenAI OAuth token 已保存，expires_in={}s, accountId={}", expiresIn, accountId);
    }

    /**
     * 从 JWT access_token 中解析 chatgpt_account_id
     */
    String extractAccountIdFromJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(payload);
            JsonNode auth = node.path("https://api.openai.com/auth");
            if (!auth.isMissingNode()) {
                String accountId = auth.path("chatgpt_account_id").asText(null);
                if (accountId == null) {
                    accountId = auth.path("chatgpt_account_user_id").asText(null);
                }
                return accountId;
            }
            return null;
        } catch (Exception e) {
            log.warn("解析 JWT 提取 account_id 失败", e);
            return null;
        }
    }

    private ModelProviderEntity getProvider() {
        ModelProviderEntity provider = modelProviderMapper.selectById(PROVIDER_ID);
        if (provider == null) {
            throw new MateClawException("err.llm.chatgpt_not_configured", "OpenAI ChatGPT provider 未配置，请检查数据库初始化");
        }
        return provider;
    }

    private void stopActiveCallbackServer() {
        HttpServer existing = activeCallbackServer;
        if (existing != null) {
            try {
                existing.stop(0);
                log.info("已关闭上一个残留的 OAuth 回调服务器");
            } catch (Exception ignored) {}
            activeCallbackServer = null;
        }
    }

    // ==================== PKCE 工具 ====================

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return base64UrlEncode(bytes);
    }

    private String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return base64UrlEncode(digest);
        } catch (Exception e) {
            throw new MateClawException("err.llm.pkce_failed", "PKCE code_challenge 生成失败: " + e.getMessage());
        }
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String padBase64(String base64) {
        int mod = base64.length() % 4;
        if (mod > 0) {
            base64 += "=".repeat(4 - mod);
        }
        return base64;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String extractParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ==================== 结果类 ====================

    public record OAuthAuthorizeResult(String authorizeUrl, String state) {}

    public record OAuthStatusResult(boolean connected, boolean expired, Long expiresAt) {}
}
