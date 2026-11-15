package com.agentpaygate.gateway.security;

/** The authenticated agent for the current request. Derived from the API key, never from input. */
public record AgentAuthentication(String agentId) {
}
