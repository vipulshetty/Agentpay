package com.agentpaygate.pspsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A fake payment provider. It holds charges in memory and can be told, at runtime, to add
 * latency, return 5xx, time out after charging, and duplicate, delay or reorder its webhooks.
 */
@SpringBootApplication
public class PspSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(PspSimApplication.class, args);
    }
}
