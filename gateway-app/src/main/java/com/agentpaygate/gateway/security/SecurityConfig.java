package com.agentpaygate.gateway.security;

import com.agentpaygate.gateway.config.AppProperties;
import com.agentpaygate.gateway.repo.AgentRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two separate authentication schemes, deliberately:
 *
 * <ul>
 *   <li><b>Agents</b> use an API key (hashed in the database) and may call the MCP endpoint;</li>
 *   <li><b>Operators</b> use a shared admin token header and may call the admin API.</li>
 * </ul>
 *
 * <p>Webhooks are unauthenticated at the transport level because the provider has no API key: they
 * are authenticated by HMAC over the raw body inside the webhook controller. That is why
 * {@code /webhooks/**} is permitted here and verified there.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AgentRepository agents, AppProperties properties)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                        // The container's error dispatch must be reachable, otherwise every error is
                        // rewritten into a 401 and the real status and message are lost.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/**").permitAll()
                        .requestMatchers("/admin/**").access((authentication, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(
                                        hasAdminToken(context.getRequest(), properties.adminToken())))
                        .requestMatchers("/mcp", "/mcp/**").hasRole("AGENT")
                        .anyRequest().denyAll())
                .addFilterBefore(new ApiKeyFilter(agents), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "agent authentication required"))
                        .accessDeniedHandler((request, response, ex) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "not authorised")));
        return http.build();
    }

    /** Constant-time comparison so a wrong admin token leaks nothing through timing. */
    private static boolean hasAdminToken(jakarta.servlet.http.HttpServletRequest request, String expected) {
        String provided = request.getHeader("X-Admin-Token");
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
