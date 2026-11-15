package com.agentpaygate.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AgentPayGate control plane.
 *
 * <p>This is the only place that authenticates agents and decides payments. The Go edge in front
 * of it is a coarse filter; calling this service directly without a valid API key is denied here.
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
