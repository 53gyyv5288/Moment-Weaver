package com.momentweaver.config;

import com.momentweaver.auth.security.JwtAuthenticationFilter;
import com.momentweaver.auth.security.RestAuthEntryPoint;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final RestAuthEntryPoint authEntryPoint;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(authEntryPoint))
            .authorizeHttpRequests(auth -> auth
                // 异步 dispatch 跳过 auth：
                //   - SseEmitter 流结束后 Tomcat 异步 dispatch 回 servlet，SecurityContext 已丢失
                //   - 再走一遍 AuthorizationFilter 会拒 anonymous，产生 AccessDenied 日志噪声
                //   - 响应此时已 commit（写了 SSE 事件），filter chain 无意义
                //   - ERROR dispatch 同理
                //   必须在最前面，否则会被后面的 authenticated() 兜底拒绝
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                // 公开：注册/登录/健康检查/公开授权/公开分享/swagger
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/healthz",
                    "/api/v1/readyz",
                    "/api/v1/public/authz/**",
                    "/api/v1/public/shares/**",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/doc.html",
                    "/webjars/**"
                ).permitAll()
                // 内部白名单：AI 服务调 Spring 端做兜底 authz check。
                // 路径白名单 + 控制器内校验 X-Internal-Secret header（共享密钥）。
                // 不带正确 secret → 401；userId 非工作区成员 → 403；
                // 路径只暴露"subject X 是否已授权"的 boolean，不返回授权详情 → 泄露面有限。
                .requestMatchers("/api/v1/memory/subjects/*/authorizations/check").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
