package com.agentpaygate.gateway.web;

import com.agentpaygate.gateway.repo.PaymentRepository;
import com.agentpaygate.gateway.security.AgentAuthentication;
import com.agentpaygate.gateway.service.PaymentService;
import com.agentpaygate.policy.DenyReason;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * The MCP tool surface an agent can call.
 *
 * <p>The agent id always comes from the authenticated credential, never from a tool argument, so
 * an agent cannot ask about, or pay from, another agent's mandate by passing a different id. Every
 * tool is display or request: none of them can approve a payment without going through
 * {@link PaymentService}, which enforces the mandate.
 */
@Service
public class McpTools {

    private final PaymentService payments;

    public McpTools(PaymentService payments) {
        this.payments = payments;
    }

    @Tool(name = "request_payment",
            description = "Ask the gateway to pay a merchant under a signed mandate. "
                    + "The gateway decides; an agent cannot approve its own payment.")
    public Map<String, Object> requestPayment(
            @ToolParam(description = "mandate to spend under") String mandateId,
            @ToolParam(description = "merchant id to pay") String merchantId,
            @ToolParam(description = "merchant category, used when the mandate allows categories") String merchantCategory,
            @ToolParam(description = "amount in minor units (paise/cents)") long amountMinor,
            @ToolParam(description = "ISO currency, INR or USD") String currency,
            @ToolParam(description = "agent-scoped idempotency key; a retry must reuse it") String idempotencyKey) {
        String agentId = currentAgentId();
        PaymentService.PaymentResult result = payments.requestPayment(agentId,
                new PaymentService.RequestCommand(mandateId, merchantId,
                        merchantCategory == null ? "" : merchantCategory, amountMinor, currency,
                        idempotencyKey, idempotencyKey));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", result.paymentId());
        out.put("state", result.state());
        out.put("approved", result.state() != null && !result.state().equals("DENIED"));
        out.put("amountMinor", result.amountMinor());
        out.put("deniedReason", result.reasonCode() == null ? "" : result.reasonCode());
        out.put("replayed", result.replayed());
        return out;
    }

    @Tool(name = "get_payment_status", description = "Read the current state of one of your payments.")
    public Map<String, Object> getPaymentStatus(
            @ToolParam(description = "payment id returned by request_payment") String paymentId) {
        String agentId = currentAgentId();
        PaymentRepository.PaymentRow row = payments.getPayment(agentId, paymentId);
        Map<String, Object> out = new TreeMap<>();
        out.put("paymentId", row.paymentId());
        out.put("state", row.state().name());
        out.put("amountMinor", row.amountMinor());
        out.put("currency", row.currency());
        out.put("capturedMinor", row.capturedMinor());
        out.put("refundedMinor", row.refundedMinor());
        out.put("providerRef", row.providerRef() == null ? "" : row.providerRef());
        out.put("reasonCode", row.reasonCode() == null ? "" : row.reasonCode());
        return out;
    }

    @Tool(name = "get_remaining_budget", description = "How much budget a mandate has left, in minor units.")
    public Map<String, Object> getRemainingBudget(
            @ToolParam(description = "mandate id") String mandateId) {
        return payments.remainingBudget(currentAgentId(), mandateId);
    }

    @Tool(name = "list_my_mandates", description = "List the mandates issued to this agent.")
    public List<Map<String, Object>> listMyMandates() {
        return payments.listMandatesForAgent(currentAgentId());
    }

    /** Deny reason catalogue, so a client can map codes to human text. */
    @Tool(name = "describe_deny_reasons", description = "List the reason codes a denial can carry.")
    public List<String> describeDenyReasons() {
        return java.util.Arrays.stream(DenyReason.values()).map(Enum::name).toList();
    }

    private static String currentAgentId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AgentAuthentication agent)) {
            throw new com.agentpaygate.gateway.support.ApiException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "an agent API key is required");
        }
        return agent.agentId();
    }
}
