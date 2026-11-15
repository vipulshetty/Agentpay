package com.agentpaygate.gateway.security;

import com.agentpaygate.gateway.repo.AgentRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates an agent from {@code Authorization: Bearer <api-key>}.
 *
 * <p>The key is hashed and looked up; the raw key never reaches the database, a log line or an
 * error message. A missing or unknown key leaves the request unauthenticated, and Spring Security
 * turns that into a 401 for protected paths. The edge proxy forwards this header unchanged and is
 * not trusted to have checked it.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String MDC_AGENT = "agentId";

    private final AgentRepository agents;

    public ApiKeyFilter(AgentRepository agents) {
        this.agents = agents;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String rawKey = header.substring("Bearer ".length()).trim();
            agents.findAgentIdByApiKey(rawKey).ifPresent(agentId -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        new AgentAuthentication(agentId), null,
                        List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                MDC.put(MDC_AGENT, agentId);
            });
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_AGENT);
        }
    }
}
