package com.agentpaygate.gateway.service;

import com.agentpaygate.gateway.repo.AgentRepository;
import com.agentpaygate.gateway.repo.AuditRepository;
import com.agentpaygate.gateway.repo.IssuerKeyRepository;
import com.agentpaygate.gateway.repo.LedgerRepository;
import com.agentpaygate.gateway.repo.MandateRepository;
import com.agentpaygate.gateway.repo.PaymentRepository;
import com.agentpaygate.gateway.repo.ReconciliationRepository;
import com.agentpaygate.gateway.repo.SpendRepository;
import com.agentpaygate.gateway.support.ApiException;
import com.agentpaygate.gateway.support.MandateFileParser;
import com.agentpaygate.ledger.Account;
import com.agentpaygate.ledger.JournalEntry;
import com.agentpaygate.payments.PaymentState;
import com.agentpaygate.policy.MandateSignature;
import com.agentpaygate.policy.SignedMandate;
import com.agentpaygate.policy.SpendState;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator actions: register a human issuer key, upload a signed mandate, create an agent, fund a
 * wallet, and read the operational views the console uses.
 *
 * <p>Uploaded mandates are re-verified against the registered issuer key before they are stored.
 * A mandate that does not verify is never persisted, so the database cannot contain a mandate that
 * would later be evaluated on the strength of an unverified signature.
 */
@Service
public class AdminService {

    private final AgentRepository agents;
    private final IssuerKeyRepository issuerKeys;
    private final MandateRepository mandates;
    private final PaymentRepository payments;
    private final LedgerRepository ledger;
    private final SpendRepository spend;
    private final ReconciliationRepository reconciliation;
    private final AuditRepository audit;

    public AdminService(AgentRepository agents, IssuerKeyRepository issuerKeys, MandateRepository mandates,
                        PaymentRepository payments, LedgerRepository ledger, SpendRepository spend,
                        ReconciliationRepository reconciliation, AuditRepository audit) {
        this.agents = agents;
        this.issuerKeys = issuerKeys;
        this.mandates = mandates;
        this.payments = payments;
        this.ledger = ledger;
        this.spend = spend;
        this.reconciliation = reconciliation;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> createAgent(String agentId, String displayName) {
        if (agents.exists(agentId)) {
            throw ApiException.badRequest("AGENT_EXISTS", "agent " + agentId + " already exists");
        }
        String apiKey = "apg_" + UUID.randomUUID().toString().replace("-", "");
        agents.create(agentId, displayName, apiKey);
        audit.append("admin", "AGENT_CREATED", agentId, "displayName=" + displayName);
        // The raw key is returned exactly once and never stored.
        return Map.of("agentId", agentId, "apiKey", apiKey,
                "note", "demo key: shown once, stored only as a SHA-256 hash");
    }

    @Transactional
    public void registerIssuerKey(String keyId, String publicKeyBase64) {
        try {
            byte[] der = java.util.Base64.getDecoder().decode(publicKeyBase64);
            PublicKey key = java.security.KeyFactory.getInstance("Ed25519")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(der));
            issuerKeys.save(keyId, key);
            audit.append("admin", "ISSUER_KEY_REGISTERED", keyId, "algorithm=Ed25519");
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_ISSUER_KEY", "not a base64 DER Ed25519 public key");
        }
    }

    @Transactional
    public Map<String, Object> uploadMandate(String mandateFileText) {
        SignedMandate signedMandate = MandateFileParser.parse(mandateFileText);
        PublicKey issuerKey = issuerKeys.find(signedMandate.mandate().issuerKeyId())
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_ISSUER_KEY",
                        "issuer key " + signedMandate.mandate().issuerKeyId() + " is not registered"));
        if (!MandateSignature.verify(signedMandate.mandate(), signedMandate.signatureBase64(), issuerKey)) {
            throw ApiException.badRequest("BAD_SIGNATURE", "mandate signature does not verify");
        }
        if (!agents.exists(signedMandate.mandate().agentId())) {
            throw ApiException.badRequest("UNKNOWN_AGENT",
                    "agent " + signedMandate.mandate().agentId() + " does not exist");
        }
        if (mandates.exists(signedMandate.mandate().mandateId())) {
            throw ApiException.badRequest("MANDATE_EXISTS", "mandate already uploaded");
        }
        mandates.save(signedMandate);
        audit.append("admin", "MANDATE_REGISTERED", signedMandate.mandate().mandateId(),
                "agent=" + signedMandate.mandate().agentId()
                        + " maxTotal=" + signedMandate.mandate().maxTotal());
        return Map.of(
                "mandateId", signedMandate.mandate().mandateId(),
                "agentId", signedMandate.mandate().agentId(),
                "currency", signedMandate.mandate().currency(),
                "maxTotal", signedMandate.mandate().maxTotal(),
                "verified", true);
    }

    @Transactional
    public Map<String, Object> fund(String agentId, long amountMinor, String currency) {
        if (amountMinor <= 0L) {
            throw ApiException.badRequest("INVALID_AMOUNT", "funding amount must be positive");
        }
        if (!agents.exists(agentId)) {
            throw ApiException.notFound("agent " + agentId);
        }
        String businessEventId = "funding:" + agentId + ":" + UUID.randomUUID();
        ledger.post(JournalEntry.funding(businessEventId, currency, agentId, amountMinor));
        audit.append("admin", "WALLET_FUNDED", agentId, "amount=" + amountMinor + " currency=" + currency);
        return Map.of(
                "agentId", agentId,
                "walletBalance", ledger.balanceOf(Account.agentWallet(agentId)),
                "businessEventId", businessEventId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> out = new TreeMap<>();
        Map<String, Long> byState = new TreeMap<>(payments.countsByState());
        out.put("paymentsByState", byState);
        out.put("totalCapturedMinor", payments.totalCapturedMinor());
        out.put("unresolvedDiscrepancies", reconciliation.unresolvedCount());
        out.put("ledgerConserves", ledger.conserves());
        out.put("noNegativeBalances", ledger.noNegativeNonExternalBalances());
        out.put("agents", agents.list().size());
        // "Open" means work is still pending. CAPTURED and REFUNDED are not terminal in the state
        // machine (a capture can still be refunded) but they are settled: nothing is in flight, so
        // counting them made a fully settled ledger report "open payments: 3".
        out.put("openPayments", byState.entrySet().stream()
                .filter(e -> {
                    PaymentState state = PaymentState.valueOf(e.getKey());
                    return !state.isTerminal()
                            && state != PaymentState.CAPTURED
                            && state != PaymentState.REFUNDED;
                })
                .mapToLong(Map.Entry::getValue).sum());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ledgerView(int limit) {
        Map<String, Object> out = new TreeMap<>();
        Map<String, Long> balances = new TreeMap<>();
        ledger.balances().forEach((account, value) ->
                balances.put(account.type() + ":" + account.ref(), value));
        out.put("balances", balances);
        out.put("conserves", ledger.conserves());
        out.put("noNegativeNonExternal", ledger.noNegativeNonExternalBalances());
        out.put("entries", ledger.entries(limit));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mandates() {
        return mandates.listAll().stream()
                .map(row -> {
                    Map<String, Object> copy = new TreeMap<>(row);
                    SpendState state = spend.current(String.valueOf(row.get("mandate_id")));
                    copy.put("remainingTotal", ((Number) row.get("max_total")).longValue() - state.committedTotal());
                    return copy;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> auditVerify() {
        AuditRepository.AuditVerify verify = audit.verify();
        return Map.of("valid", verify.valid(), "entries", verify.entries(),
                "brokenAtSeq", verify.brokenAtSeq() == null ? "" : verify.brokenAtSeq());
    }
}
