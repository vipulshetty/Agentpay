package com.agentpaygate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.agentpaygate.gateway.repo.AgentRepository;
import com.agentpaygate.gateway.repo.AuditRepository;
import com.agentpaygate.gateway.repo.LedgerRepository;
import com.agentpaygate.gateway.repo.PaymentRepository;
import com.agentpaygate.gateway.repo.WebhookInboxRepository;
import com.agentpaygate.gateway.service.OutboxWorker;
import com.agentpaygate.gateway.service.PaymentService;
import com.agentpaygate.gateway.service.WebhookService;
import com.agentpaygate.ledger.Account;
import com.agentpaygate.payments.PaymentState;
import com.agentpaygate.policy.Mandate;
import com.agentpaygate.policy.MandateSignature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration tests with real PostgreSQL and a real (containerised) psp-sim.
 *
 * <p>Nothing here is mocked: the gateway talks HTTP to the provider container, writes to Postgres
 * through Flyway-migrated tables, and the outbox worker runs on its own schedule. Docker tests
 * that do not run are a failure, not a skip.
 *
 * <p>Each test uses its own agent, mandate and merchant so assertions on balance are about that
 * test's money only; the database is shared across the class on purpose (one Postgres container,
 * one Spring context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
                "agentpaygate.admin-token=test-admin-token",
                "agentpaygate.webhook-secret=test-webhook-secret",
                "agentpaygate.psp-timeout-ms=1500",
                "agentpaygate.outbox.poll-interval-ms=100",
                "agentpaygate.outbox.enabled=true"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayIT {

    private static final String ADMIN = "test-admin-token";
    private static final String WEBHOOK_SECRET = "test-webhook-secret";
    private static final KeyPair ISSUER = keyPair();

    static PostgreSQLContainer<?> postgres;
    static GenericContainer<?> psp;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> postgres().getUsername());
        registry.add("spring.datasource.password", () -> postgres().getPassword());
        registry.add("agentpaygate.psp-base-url",
                () -> "http://" + psp().getHost() + ":" + psp().getMappedPort(8081));
    }

    static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
            postgres.start();
        }
        return postgres;
    }

    static synchronized GenericContainer<?> psp() {
        if (psp == null) {
            org.testcontainers.Testcontainers.exposeHostPorts(18080);
            psp = new GenericContainer<>(DockerImageName.parse("eclipse-temurin:21-jre"))
                    .withExposedPorts(8081)
                    .withCopyFileToContainer(
                            org.testcontainers.utility.MountableFile.forHostPath(
                                    "../psp-sim/target/psp-sim-0.1.0-SNAPSHOT.jar"),
                            "/app/psp-sim.jar")
                    .withEnv("PSP_PORT", "8081")
                    .withEnv("GATEWAY_WEBHOOK_URL", "http://host.testcontainers.internal:18080/webhooks/psp")
                    .withEnv("WEBHOOK_SECRET", WEBHOOK_SECRET)
                    .withCommand("java", "-jar", "/app/psp-sim.jar")
                    .waitingFor(Wait.forHttp("/actuator/health").forPort(8081))
                    .withStartupTimeout(Duration.ofMinutes(3));
            psp.start();
        }
        return psp;
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    PaymentService payments;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    LedgerRepository ledger;
    @Autowired
    AuditRepository audit;
    @Autowired
    WebhookService webhooks;
    @Autowired
    WebhookInboxRepository inbox;
    @Autowired
    OutboxWorker outboxWorker;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    AgentRepository agents;

    @BeforeAll
    static void containers() {
        postgres();
        psp();
    }

    // ------------------------------------------------------------------ helpers

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = jsonHeaders();
        headers.set("X-Admin-Token", ADMIN);
        return headers;
    }

    private String pspUrl(String path) {
        return "http://" + psp.getHost() + ":" + psp.getMappedPort(8081) + path;
    }

    private void resetProvider() {
        rest.postForEntity(pspUrl("/admin/failure-modes/reset"), HttpEntity.EMPTY, Map.class);
    }

    private void setProviderFailureMode(String field, boolean value) {
        ResponseEntity<Map> response = rest.postForEntity(pspUrl("/admin/failure-modes"),
                new HttpEntity<>(Map.of(field, value), jsonHeaders()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get(field)).as("provider failure mode %s", field).isEqualTo(value);
    }

    private String createAgent(String agentId) {
        ResponseEntity<Map> response = rest.exchange("/admin/agents", HttpMethod.POST,
                new HttpEntity<>(Map.of("agentId", agentId, "displayName", "test " + agentId), adminHeaders()),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("apiKey");
    }

    private void registerIssuerKey() {
        String der = Base64.getEncoder().encodeToString(ISSUER.getPublic().getEncoded());
        ResponseEntity<Map> response = rest.exchange("/admin/issuer-keys", HttpMethod.POST,
                new HttpEntity<>(Map.of("keyId", "issuer-it", "publicKeyBase64", der), adminHeaders()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void uploadMandate(Mandate mandate) {
        String signature = MandateSignature.sign(mandate, ISSUER.getPrivate());
        String text = com.agentpaygate.policy.CanonicalForm.of(mandate) + "signature=" + signature + "\n";
        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Map> response = rest.exchange("/admin/mandates", HttpMethod.POST,
                new HttpEntity<>(text, headers), Map.class);
        assertThat(response.getStatusCode())
                .as("mandate upload: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private void fund(String agentId, long amount) {
        ResponseEntity<Map> response = rest.exchange("/admin/funding", HttpMethod.POST,
                new HttpEntity<>(Map.of("agentId", agentId, "amountMinor", amount, "currency", "INR"),
                        adminHeaders()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Mandate mandate(String mandateId, String agentId, long perTx, long total, long perDay,
                            long notBefore, long expiresAt, String merchant) {
        return Mandate.of(mandateId, agentId, "issuer-it", "INR", perTx, total, perDay,
                List.of(merchant), List.of(), notBefore, expiresAt, "nonce-" + mandateId);
    }

    private void registerMerchant(String merchantId, String category) {
        ResponseEntity<Map> response = rest.exchange("/admin/merchants", HttpMethod.POST,
                new HttpEntity<>(Map.of("merchantId", merchantId, "category", category), adminHeaders()),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Creates agent, issuer key (idempotent), mandate and funding for one test. */
    private Mandate scenario(String agentId, String mandateId, String merchant, long perTx, long total,
                             long perDay, long funding) {
        createAgent(agentId);
        registerIssuerKey();
        registerMerchant(merchant, "groceries");
        long now = java.time.Instant.now().getEpochSecond();
        Mandate mandate = mandate(mandateId, agentId, perTx, total, perDay, now - 60, now + 3_600, merchant);
        uploadMandate(mandate);
        fund(agentId, funding);
        return mandate;
    }

    private PaymentService.PaymentResult request(String agentId, String mandateId, long amount,
                                                 String merchant, String idemKey) {
        registerMerchant(merchant, "groceries");
        return payments.requestPayment(agentId, new PaymentService.RequestCommand(
                mandateId, merchant, "groceries", amount, "INR", idemKey, idemKey));
    }

    private PaymentState state(String paymentId) {
        return paymentRepository.find(paymentId).orElseThrow().state();
    }

    private long merchantBalance(String merchant) {
        return ledger.balanceOf(Account.merchantPayable(merchant));
    }

    private String signedWebhook(String eventId, String providerRef, String kind, long amount) {
        String body = """
                {"eventId":"%s","providerRef":"%s","kind":"%s","amountMinor":%d}"""
                .formatted(eventId, providerRef, kind, amount);
        long ts = java.time.Instant.now().getEpochSecond();
        String signature = WebhookService.hmac(WEBHOOK_SECRET, ts + "." + body);
        HttpHeaders headers = jsonHeaders();
        headers.set("X-PSP-Signature", "sha256=" + signature);
        headers.set("X-PSP-Timestamp", Long.toString(ts));
        ResponseEntity<Map> response = rest.exchange("/webhooks/psp", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return String.valueOf(response.getBody().get("outcome"));
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void approvesAndCapturesWithinAMandate() {
        resetProvider();
        String merchant = "merchant-happy";
        scenario("agent-happy", "mandate-happy", merchant, 50_000L, 200_000L, 200_000L, 500_000L);

        PaymentService.PaymentResult result = request("agent-happy", "mandate-happy", 25_000L, merchant,
                "idem-happy-1");
        assertThat(result.state()).isEqualTo("AUTHORIZING");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(state(result.paymentId())).isEqualTo(PaymentState.CAPTURED));

        PaymentRepository.PaymentRow row = paymentRepository.find(result.paymentId()).orElseThrow();
        assertThat(row.capturedMinor()).isEqualTo(25_000L);
        assertThat(row.providerRef()).startsWith("psp_");

        assertThat(ledger.conserves()).isTrue();
        assertThat(ledger.noNegativeNonExternalBalances()).isTrue();
        assertThat(merchantBalance(merchant)).isEqualTo(25_000L);
        assertThat(ledger.balanceOf(Account.agentWallet("agent-happy"))).isEqualTo(475_000L);
        assertThat(audit.verify().valid()).isTrue();
    }

    @Test
    @Order(2)
    void idempotentReplayReturnsTheSamePaymentAndChargesOnce() {
        resetProvider();
        String merchant = "merchant-replay";
        scenario("agent-replay", "mandate-replay", merchant, 50_000L, 200_000L, 200_000L, 500_000L);

        PaymentService.PaymentResult first = request("agent-replay", "mandate-replay", 10_000L, merchant,
                "idem-r-1");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(state(first.paymentId())).isEqualTo(PaymentState.CAPTURED));
        PaymentService.PaymentResult second = request("agent-replay", "mandate-replay", 10_000L, merchant,
                "idem-r-1");

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.replayed()).isTrue();
        assertThat(merchantBalance(merchant)).isEqualTo(10_000L);
    }

    @Test
    @Order(3)
    void reusedKeyWithDifferentPayloadIsRejected() {
        String merchant = "merchant-conflict";
        scenario("agent-conflict", "mandate-conflict", merchant, 50_000L, 200_000L, 200_000L, 500_000L);

        request("agent-conflict", "mandate-conflict", 1_000L, merchant, "idem-conflict");
        assertThatThrownBy(() -> request("agent-conflict", "mandate-conflict", 99_000L, merchant,
                "idem-conflict"))
                .isInstanceOf(com.agentpaygate.gateway.support.ApiException.class)
                .hasMessageContaining("different payload");
    }

    @Test
    @Order(4)
    void everyPolicyDenialIsRecordedWithItsReasonCode() {
        resetProvider();
        String merchant = "merchant-denied";
        scenario("agent-denied", "mandate-denied", merchant, 50_000L, 100_000L, 60_000L, 500_000L);

        assertThat(request("agent-denied", "mandate-denied", 50_001L, merchant, "d-1").reasonCode())
                .isEqualTo("AMOUNT_OVER_TX_LIMIT");
        assertThat(request("agent-denied", "mandate-denied", 1_000L, "merchant-unknown", "d-2").reasonCode())
                .isEqualTo("MERCHANT_NOT_ALLOWED");
        assertThat(request("agent-denied", "mandate-denied", 0L, merchant, "d-3").reasonCode())
                .isEqualTo("AMOUNT_NOT_POSITIVE");

        // Exhaust the daily budget (60,000) with three approved payments.
        request("agent-denied", "mandate-denied", 20_000L, merchant, "d-4");
        request("agent-denied", "mandate-denied", 20_000L, merchant, "d-5");
        request("agent-denied", "mandate-denied", 20_000L, merchant, "d-6");
        assertThat(request("agent-denied", "mandate-denied", 1_000L, merchant, "d-7").reasonCode())
                .isEqualTo("DAILY_LIMIT");

        long now = java.time.Instant.now().getEpochSecond();
        uploadMandate(mandate("mandate-expired", "agent-denied", 50_000L, 100_000L, 100_000L,
                now - 3_600, now - 60, merchant));
        assertThat(request("agent-denied", "mandate-expired", 100L, merchant, "d-8").reasonCode())
                .isEqualTo("EXPIRED");

        uploadMandate(mandate("mandate-future", "agent-denied", 50_000L, 100_000L, 100_000L,
                now + 600, now + 3_600, merchant));
        assertThat(request("agent-denied", "mandate-future", 100L, merchant, "d-9").reasonCode())
                .isEqualTo("NOT_YET_VALID");

        // A fresh mandate so the nonce test does not trip over the exhausted daily budget.
        uploadMandate(mandate("mandate-nonce", "agent-denied", 50_000L, 100_000L, 100_000L,
                now - 60, now + 3_600, merchant));
        PaymentService.PaymentResult once = request("agent-denied", "mandate-nonce", 100L, merchant, "d-10");
        assertThat(once.state()).isNotEqualTo("DENIED");
        PaymentService.PaymentResult replayed = payments.requestPayment("agent-denied",
                new PaymentService.RequestCommand("mandate-nonce", merchant, "groceries", 100L, "INR",
                        "d-11", "d-10"));
        assertThat(replayed.reasonCode()).isEqualTo("REPLAYED");

        // A tampered mandate is rejected at upload time, before it can ever be evaluated.
        Mandate tampered = mandate("mandate-tampered", "agent-denied", 10_000_000L, 100_000L, 100_000L,
                now - 60, now + 3_600, merchant);
        String signature = MandateSignature.sign(mandate("mandate-original", "agent-denied", 1_000L,
                100_000L, 100_000L, now - 60, now + 3_600, merchant), ISSUER.getPrivate());
        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Map> rejected = rest.exchange("/admin/mandates", HttpMethod.POST,
                new HttpEntity<>(com.agentpaygate.policy.CanonicalForm.of(tampered) + "signature=" + signature,
                        headers), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(rejected.getBody().get("code"))).isEqualTo("BAD_SIGNATURE");

        assertThat(paymentRepository.countsByState()).containsKey("DENIED");
        assertThat(audit.verify().valid()).isTrue();
    }

    @Test
    @Order(5)
    void concurrentRequestsCannotExceedTheMandateTotal() throws Exception {
        resetProvider();
        String merchant = "merchant-concurrent";
        long amount = 10_000L;
        long budget = 50_000L;
        scenario("agent-concurrent", "mandate-concurrent", merchant, amount, budget, budget, 1_000_000L);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<PaymentService.PaymentResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            tasks.add(() -> {
                start.await(10, TimeUnit.SECONDS);
                return request("agent-concurrent", "mandate-concurrent", amount, merchant, "conc-" + index);
            });
        }
        var futures = tasks.stream().map(pool::submit).toList();
        start.countDown();
        int approved = 0;
        for (var future : futures) {
            PaymentService.PaymentResult result = future.get(60, TimeUnit.SECONDS);
            if (!"DENIED".equals(result.state())) {
                approved++;
            }
        }
        pool.shutdownNow();

        assertThat(approved).isEqualTo((int) (budget / amount));
        assertThat(approved * amount).isLessThanOrEqualTo(budget);
        Map<String, Object> left = payments.remainingBudget("agent-concurrent", "mandate-concurrent");
        assertThat((Long) left.get("remainingTotal")).isEqualTo(0L);
    }

    @Test
    @Order(6)
    void providerTimeoutAfterChargeLeavesUnknownThenReconcilesOnce() {
        resetProvider();
        String merchant = "merchant-timeout";
        scenario("agent-timeout", "mandate-timeout", merchant, 50_000L, 200_000L, 200_000L, 500_000L);
        setProviderFailureMode("timeoutAfterCharge", true);

        PaymentService.PaymentResult result = request("agent-timeout", "mandate-timeout", 30_000L, merchant,
                "t-1");
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(state(result.paymentId())).isEqualTo(PaymentState.CAPTURE_UNKNOWN));

        // The provider did charge, exactly once, even though the gateway saw a timeout.
        long chargesAtProvider = ((List<Map<String, Object>>) rest.getForObject(pspUrl("/admin/charges"),
                List.class)).stream().filter(c -> result.paymentId().equals(c.get("paymentId"))).count();
        assertThat(chargesAtProvider).isEqualTo(1L);

        resetProvider();
        ResponseEntity<Map> run = rest.exchange("/admin/reconciliation/run", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), Map.class);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) run.getBody().get("autoResolved")).intValue()).isGreaterThanOrEqualTo(1);

        assertThat(state(result.paymentId())).isEqualTo(PaymentState.CAPTURED);
        assertThat(merchantBalance(merchant)).isEqualTo(30_000L);
        assertThat(ledger.conserves()).isTrue();
    }

    @Test
    @Order(7)
    void duplicateAndOutOfOrderWebhooksDoNotMoveStateBackwards() {
        resetProvider();
        String merchant = "merchant-webhook";
        scenario("agent-webhook", "mandate-webhook", merchant, 50_000L, 200_000L, 200_000L, 500_000L);

        PaymentService.PaymentResult result = request("agent-webhook", "mandate-webhook", 5_000L, merchant,
                "w-1");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(state(result.paymentId())).isEqualTo(PaymentState.CAPTURED));
        String providerRef = paymentRepository.find(result.paymentId()).orElseThrow().providerRef();
        long inboxBefore = inbox.count();

        assertThat(signedWebhook("evt-late-auth", providerRef, "AUTHORIZED", 5_000L)).isEqualTo("DUPLICATE");
        assertThat(signedWebhook("evt-dup-cap", providerRef, "CAPTURED", 5_000L)).isEqualTo("DUPLICATE");
        assertThat(signedWebhook("evt-dup-cap", providerRef, "CAPTURED", 5_000L)).isEqualTo("DUPLICATE");

        assertThat(state(result.paymentId())).isEqualTo(PaymentState.CAPTURED);
        assertThat(paymentRepository.find(result.paymentId()).orElseThrow().capturedMinor()).isEqualTo(5_000L);
        assertThat(inbox.count() - inboxBefore).isEqualTo(2); // the duplicate event id stored once
        assertThat(merchantBalance(merchant)).isEqualTo(5_000L);
    }

    @Test
    @Order(8)
    void forgedWebhookSignatureIsRejected() {
        String body = """
                {"eventId":"evt-forged","providerRef":"psp_fake","kind":"CAPTURED","amountMinor":100}""";
        HttpHeaders headers = jsonHeaders();
        headers.set("X-PSP-Signature", "sha256=" + "0".repeat(64));
        headers.set("X-PSP-Timestamp", Long.toString(java.time.Instant.now().getEpochSecond()));
        ResponseEntity<Map> response = rest.exchange("/webhooks/psp", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("code"))).isEqualTo("WEBHOOK_REJECTED");

        long old = java.time.Instant.now().getEpochSecond() - 10_000;
        HttpHeaders stale = jsonHeaders();
        stale.set("X-PSP-Signature", "sha256=" + WebhookService.hmac(WEBHOOK_SECRET, old + "." + body));
        stale.set("X-PSP-Timestamp", Long.toString(old));
        ResponseEntity<Map> staleResponse = rest.exchange("/webhooks/psp", HttpMethod.POST,
                new HttpEntity<>(body, stale), Map.class);
        assertThat(staleResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(9)
    void crashBetweenProviderCallAndCommitIsRecoveredWithoutDoubleCharging() {
        resetProvider();
        String merchant = "merchant-crash";
        scenario("agent-crash", "mandate-crash", merchant, 50_000L, 200_000L, 200_000L, 500_000L);

        PaymentService.PaymentResult result = request("agent-crash", "mandate-crash", 7_000L, merchant, "c-1");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(state(result.paymentId())).isEqualTo(PaymentState.CAPTURED));

        PaymentRepository.PaymentRow captured = paymentRepository.find(result.paymentId()).orElseThrow();
        String providerRef = captured.providerRef();
        String providerIdemKey = captured.providerIdemKey();
        assertThat(providerRef).startsWith("psp_");

        // Simulate the crash window: the provider charged, but our row still says AUTHORIZING.
        jdbc.update("update payments set state = 'AUTHORIZING', captured_minor = 0, provider_ref = null "
                + "where payment_id = ?", result.paymentId());
        jdbc.update("update mandate_spend set spent_minor = 0, reserved_minor = 7000, spent_today_minor = 0, "
                + "reserved_today_minor = 7000 where mandate_id = ?", "mandate-crash");

        // Replay the same provider call with the same idempotency key: same charge, no second one.
        ResponseEntity<Map> replay = rest.postForEntity(pspUrl("/v1/charges"), new HttpEntity<>(Map.of(
                "idempotencyKey", providerIdemKey, "amountMinor", 7_000L, "currency", "INR",
                "merchantId", merchant, "paymentId", result.paymentId()), jsonHeaders()), Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("providerRef")).isEqualTo(providerRef);

        long charges = ((List<Map<String, Object>>) rest.getForObject(pspUrl("/admin/charges"), List.class))
                .stream().filter(c -> result.paymentId().equals(c.get("paymentId"))).count();
        assertThat(charges).isEqualTo(1L);

        // The outbox retries and converges on the same provider reference. A restarted gateway
        // first re-enqueues payments that were left mid-call.
        assertThat(outboxWorker.recoverStuck()).isGreaterThanOrEqualTo(1);
        outboxWorker.pollOnce();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(state(result.paymentId())).isEqualTo(PaymentState.CAPTURED));
        assertThat(merchantBalance(merchant)).isEqualTo(7_000L);
        assertThat(ledger.conserves()).isTrue();
    }

    @Test
    @Order(10)
    void auditChainFailsAfterATamperedRow() {
        assertThat(audit.verify().valid()).isTrue();
        Long seq = jdbc.queryForObject("select max(seq) from audit_log", Long.class);
        String original = jdbc.queryForObject("select detail from audit_log where seq = ?", String.class, seq);
        jdbc.update("update audit_log set detail = ? where seq = ?", original + " (tampered)", seq);
        assertThat(audit.verify().valid()).isFalse();
        jdbc.update("update audit_log set detail = ? where seq = ?", original, seq);
        assertThat(audit.verify().valid()).isTrue();
    }

    @Test
    @Order(11)
    void callingTheGatewayWithoutACredentialIsDenied() {
        ResponseEntity<String> noKey = rest.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>("{}", jsonHeaders()), String.class);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders badKey = jsonHeaders();
        badKey.setBearerAuth("apg_not-a-real-key");
        ResponseEntity<String> unknownKey = rest.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>("{}", badKey), String.class);
        assertThat(unknownKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // No admin token at all: Spring Security reports "unauthenticated" (401); a wrong token is
        // a 403. Both are denials and neither reaches the handler.
        ResponseEntity<String> noAdminToken = rest.getForEntity("/admin/overview", String.class);
        assertThat(noAdminToken.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(noAdminToken.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

        HttpHeaders wrongToken = jsonHeaders();
        wrongToken.set("X-Admin-Token", "not-the-token");
        ResponseEntity<String> wrong = rest.exchange("/admin/overview", HttpMethod.GET,
                new HttpEntity<>(wrongToken), String.class);
        assertThat(wrong.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    /**
     * The agent-facing interface is MCP over Streamable HTTP, so the test drives it exactly like an
     * agent client does: initialise, list tools, then call one. Anything that only passes at the
     * service layer would not catch a missing transport, a wrong path or a matcher that does not
     * cover the endpoint.
     */
    @Test
    @Order(12)
    void agentDrivesAPaymentOverMcpStreamableHttp() throws Exception {
        String agentId = "agent-mcp";
        String apiKey = createAgent(agentId);
        registerIssuerKey();
        long now = Instant.now().getEpochSecond();
        uploadMandate(mandate("mandate-mcp", agentId, 100_000, 500_000, 400_000, now - 60, now + 3_600,
                "merchant-mcp"));
        registerMerchant("merchant-mcp", "groceries");
        fund(agentId, 1_000_000);

        HttpClient client = HttpClient.newHttpClient();

        McpReply init = mcp(client, apiKey, null,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                        + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"gateway-it\",\"version\":\"0\"}}}");
        assertThat(init.sessionId()).as("the server establishes an MCP session").isNotBlank();
        assertThat(mapper.readTree(init.json()).at("/result/serverInfo/name").asText())
                .isEqualTo("agentpaygate");

        McpReply listed = mcp(client, apiKey, init.sessionId(),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        List<String> toolNames = new ArrayList<>();
        mapper.readTree(listed.json()).at("/result/tools").forEach(tool -> toolNames.add(tool.get("name").asText()));
        assertThat(toolNames).containsExactlyInAnyOrder("request_payment", "get_payment_status",
                "get_remaining_budget", "list_my_mandates", "describe_deny_reasons");

        McpReply payment = mcp(client, apiKey, init.sessionId(),
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"request_payment\",\"arguments\":{"
                        + "\"mandateId\":\"mandate-mcp\",\"merchantId\":\"merchant-mcp\","
                        + "\"merchantCategory\":\"groceries\",\"amountMinor\":4200,"
                        + "\"currency\":\"INR\",\"idempotencyKey\":\"mcp-it-1\"}}}");
        String toolText = mapper.readTree(payment.json()).at("/result/content/0/text").asText();
        assertThat(toolText).as("request_payment result: %s", toolText).contains("\"approved\":true");
        String paymentId = mapper.readTree(toolText).get("paymentId").asText();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(state(paymentId)).isEqualTo(PaymentState.CAPTURED));

        McpReply status = mcp(client, apiKey, init.sessionId(),
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"get_payment_status\",\"arguments\":{\"paymentId\":\""
                        + paymentId + "\"}}}");
        String statusText = mapper.readTree(status.json()).at("/result/content/0/text").asText();
        assertThat(mapper.readTree(statusText).get("state").asText()).isEqualTo("CAPTURED");
        assertThat(mapper.readTree(statusText).get("capturedMinor").asLong()).isEqualTo(4_200L);
        assertThat(merchantBalance("merchant-mcp")).isEqualTo(4_200L);
        assertThat(ledger.conserves()).isTrue();
    }

    /**
     * A streamed reply has to end.
     *
     * <p>The MCP test above reads the stream by hand and stops at the first complete JSON-RPC
     * message, which a truncated response also satisfies. That is why it stayed green while the
     * gateway was aborting every committed SSE response: the initial request was authorised, then
     * Tomcat re-dispatched it as ASYNC, the stateless security chain denied the re-dispatch and the
     * connection was reset. curl tolerated it; fetch/undici reported "Invalid EOF state".
     *
     * <p>This reads the body as a whole, the way a normal client does: a stream that never ends
     * trips the request timeout, and one that is cut short fails while reading.
     */
    @Test
    @Order(13)
    void mcpStreamingResponseReachesAStrictClient() throws Exception {
        String apiKey = createAgent("agent-mcp-stream");
        HttpClient client = HttpClient.newHttpClient();

        McpReply init = mcp(client, apiKey, null,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                        + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"gateway-it-stream\",\"version\":\"0\"}}}");
        assertThat(init.sessionId()).as("the server establishes an MCP session").isNotBlank();

        HttpRequest listed = HttpRequest.newBuilder(URI.create("http://localhost:18080/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + apiKey)
                .header("Mcp-Session-Id", init.sessionId())
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"))
                .build();
        HttpResponse<String> response = client.send(listed, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).as("a complete streamed body, not a truncated one")
                .contains("\"tools\"")
                .contains("request_payment");
    }

    private record McpReply(String sessionId, String json) {
    }

    /** One JSON-RPC round trip; the transport answers either JSON or a single SSE event. */
    private McpReply mcp(HttpClient client, String apiKey, String sessionId, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:18080/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        // A streamable HTTP response may be an SSE stream that stays open, so the body is read
        // only until the JSON-RPC message is complete and then released.
        HttpResponse<java.io.InputStream> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        String newSession = response.headers().firstValue("Mcp-Session-Id").orElse(sessionId);
        return new McpReply(newSession, readJsonRpc(response.body()));
    }

    private static String readJsonRpc(java.io.InputStream body) throws Exception {
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder buffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    return trimmed.substring(5).trim();
                }
                if (trimmed.isEmpty() || trimmed.startsWith("event:") || trimmed.startsWith("id:")) {
                    continue;
                }
                buffer.append(trimmed);
                if (bracesBalanced(buffer)) {
                    return buffer.toString();
                }
            }
        }
        throw new AssertionError("no JSON-RPC payload received");
    }

    private static boolean bracesBalanced(CharSequence text) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}') {
                depth--;
            }
        }
        return depth == 0 && text.length() > 0;
    }
}
