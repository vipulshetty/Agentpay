package com.agentpaygate.gateway.web;

import com.agentpaygate.gateway.repo.AuditRepository;
import com.agentpaygate.gateway.repo.PaymentRepository;
import com.agentpaygate.gateway.service.AdminService;
import com.agentpaygate.gateway.service.PaymentService;
import com.agentpaygate.gateway.service.ReconciliationService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator API. Every endpoint requires the admin token (see {@code SecurityConfig}); the console
 * calls these through its own server-side route handlers, so the token never reaches a browser.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService admin;
    private final PaymentService paymentService;
    private final ReconciliationService reconciliation;
    private final AuditRepository audit;
    private final PaymentRepository payments;
    private final com.agentpaygate.gateway.repo.LedgerRepository ledger;
    private final com.agentpaygate.gateway.repo.MerchantRepository merchants;

    public AdminController(AdminService admin, PaymentService paymentService,
                           ReconciliationService reconciliation, AuditRepository audit,
                           PaymentRepository payments, com.agentpaygate.gateway.repo.LedgerRepository ledger,
                           com.agentpaygate.gateway.repo.MerchantRepository merchants) {
        this.admin = admin;
        this.paymentService = paymentService;
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.payments = payments;
        this.ledger = ledger;
        this.merchants = merchants;
    }

    public record CreateAgentRequest(String agentId, String displayName) {
    }

    public record RegisterKeyRequest(String keyId, String publicKeyBase64) {
    }

    public record FundRequest(String agentId, long amountMinor, String currency) {
    }

    public record MerchantRequest(String merchantId, String category) {
    }

    /**
     * Registers a merchant and its category. This is what makes a category rule enforceable: the
     * gateway resolves categories here, not from the request that wants to spend.
     */
    @PostMapping("/merchants")
    public ResponseEntity<Map<String, Object>> registerMerchant(@RequestBody MerchantRequest request) {
        merchants.upsert(request.merchantId(), request.category());
        audit.append("operator", "MERCHANT_REGISTERED", request.merchantId(),
                "category=" + request.category());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("merchantId", request.merchantId(), "category", request.category()));
    }

    @GetMapping("/merchants")
    public List<Map<String, Object>> merchants() {
        return merchants.list();
    }

    @PostMapping("/agents")
    public ResponseEntity<Map<String, Object>> createAgent(@RequestBody CreateAgentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(admin.createAgent(request.agentId(), request.displayName()));
    }

    @PostMapping("/issuer-keys")
    public ResponseEntity<Map<String, Object>> registerKey(@RequestBody RegisterKeyRequest request) {
        admin.registerIssuerKey(request.keyId(), request.publicKeyBase64());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("keyId", request.keyId()));
    }

    @PostMapping("/mandates")
    public ResponseEntity<Map<String, Object>> uploadMandate(@RequestBody String mandateFile) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.uploadMandate(mandateFile));
    }

    @PostMapping("/funding")
    public Map<String, Object> fund(@RequestBody FundRequest request) {
        return admin.fund(request.agentId(), request.amountMinor(), request.currency());
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return admin.overview();
    }

    @GetMapping("/payments")
    public List<Map<String, Object>> payments(@RequestParam(defaultValue = "50") int limit) {
        return payments.list(limit).stream().map(row -> {
            Map<String, Object> out = new java.util.TreeMap<>();
            out.put("paymentId", row.paymentId());
            out.put("agentId", row.agentId());
            out.put("mandateId", row.mandateId());
            out.put("merchantId", row.merchantId());
            out.put("currency", row.currency());
            out.put("amountMinor", row.amountMinor());
            out.put("state", row.state().name());
            out.put("providerRef", row.providerRef() == null ? "" : row.providerRef());
            out.put("reasonCode", row.reasonCode() == null ? "" : row.reasonCode());
            out.put("createdAt", row.createdAt().toString());
            return out;
        }).toList();
    }

    @GetMapping("/payments/{paymentId}")
    public Map<String, Object> payment(@PathVariable String paymentId) {
        PaymentRepository.PaymentRow row = payments.find(paymentId)
                .orElseThrow(() -> com.agentpaygate.gateway.support.ApiException.notFound("payment " + paymentId));
        Map<String, Object> detail = new java.util.TreeMap<>();
        Map<String, Object> payment = new java.util.TreeMap<>();
        payment.put("paymentId", row.paymentId());
        payment.put("agentId", row.agentId());
        payment.put("mandateId", row.mandateId());
        payment.put("merchantId", row.merchantId());
        payment.put("merchantCategory", row.merchantCategory());
        payment.put("currency", row.currency());
        payment.put("amountMinor", row.amountMinor());
        payment.put("state", row.state().name());
        payment.put("capturedMinor", row.capturedMinor());
        payment.put("refundedMinor", row.refundedMinor());
        payment.put("providerRef", row.providerRef() == null ? "" : row.providerRef());
        payment.put("reasonCode", row.reasonCode() == null ? "" : row.reasonCode());
        payment.put("createdAt", row.createdAt().toString());
        payment.put("updatedAt", row.updatedAt().toString());
        detail.put("payment", payment);
        detail.put("events", payments.events(paymentId));
        detail.put("ledger", ledger.entriesForPayment(paymentId));
        return detail;
    }

    @GetMapping("/mandates")
    public List<Map<String, Object>> mandates() {
        return admin.mandates();
    }

    @GetMapping("/ledger")
    public Map<String, Object> ledger(@RequestParam(defaultValue = "100") int limit) {
        return admin.ledgerView(limit);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog(@RequestParam(defaultValue = "50") int limit) {
        return audit.list(limit);
    }

    @GetMapping("/audit/verify")
    public Map<String, Object> verifyAudit() {
        return admin.auditVerify();
    }

    @PostMapping("/reconciliation/run")
    public ReconciliationService.RunResult runReconciliation() {
        return reconciliation.run();
    }

    @GetMapping("/reconciliation")
    public List<Map<String, Object>> reconciliation() {
        return reconciliation.list();
    }
}
