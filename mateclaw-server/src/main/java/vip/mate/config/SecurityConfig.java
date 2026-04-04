package vip.mate.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security 配置
 * <p>
 * 注意：BCryptPasswordEncoder 单独定义为静态内部配置，避免与 JwtAuthFilter 产生循环依赖
 *
 * @author MateClaw Team
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /**
     * 密码编码器独立配置（打破 SecurityConfig → JwtAuthFilter → AuthService → BCryptPasswordEncoder 循环）
     */
    @Configuration
    static class PasswordEncoderConfig {
        @Bean
        public BCryptPasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .headers(headers -> headers
                // 允许同源 frame 嵌入（Electron 桌面应用、H2 Console 均需要）
                .frameOptions(frame -> frame.sameOrigin())
            )
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 公开接口
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/settings/language",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/webjars/**",
                    "/actuator/**",
                    "/h2-console/**",
                    // 静态资源（前端 SPA）
                    "/",
                    "/index.html",
                    "/assets/**",
                    "/icons/**",
                    "/logo/**",
                    "/favicon.ico"
                ).permitAll()
                // SSE 流式接口允许匿名（开发模式，生产环境可改为 authenticated）
                .requestMatchers("/api/v1/agents/*/chat/stream").permitAll()
                .requestMatchers("/api/v1/chat/stream").permitAll()
                .requestMatchers("/api/v1/chat/*/stop").permitAll()
                // 初始化 Setup API（首次安装语言选择，无需认证）
                .requestMatchers("/api/v1/setup/**").permitAll()
                // 渠道 Webhook 回调（各平台消息推送，由平台签名机制保障安全）
                .requestMatchers("/api/v1/channels/webhook/**").permitAll()
                // 其余接口需要认证
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401,\"msg\":\"Token expired or invalid\",\"data\":null}");
                })
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
