package com.agentpaygate.gateway.service;

import com.agentpaygate.gateway.config.AppProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the simulated payment provider.
 *
 * <p>Three outcomes are distinguished, because they mean different things for a payment:
 * <ul>
 *   <li>success → the charge happened, we have a provider reference;</li>
 *   <li>timeout / connection failure → <b>unknown</b>: the charge may or may not have happened;</li>
 *   <li>5xx → the provider failed before charging (in this simulator), so retrying is safe and
 *       must reuse the same idempotency key.</li>
 * </ul>
 *
 * <p>Every call sends the payment's stored provider idempotency key. Nothing here generates a new
 * one, ever.
 */
@Component
public class PspClient {

    private final RestClient client;
    private final String baseUrl;

    public PspClient(AppProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.pspTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.pspTimeoutMs()));
        this.client = builder.baseUrl(properties.pspBaseUrl()).requestFactory(factory).build();
        this.baseUrl = properties.pspBaseUrl();
    }

    public record Charge(String providerRef, String status) {
    }

    public record StatementLine(String providerRef, String paymentId, long amountMinor, String status) {
    }

    public static class PspTimeout extends RuntimeException {
        public PspTimeout(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class PspServerError extends RuntimeException {
        private final int status;

        public PspServerError(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public Charge charge(String providerIdemKey, long amountMinor, String currency, String merchantId,
                         String paymentId) {
        try {
            return client.post()
                    .uri("/v1/charges")
                    .body(Map.of(
                            "idempotencyKey", providerIdemKey,
                            "amountMinor", amountMinor,
                            "currency", currency,
                            "merchantId", merchantId,
                            "paymentId", paymentId))
                    .retrieve()
                    .body(Charge.class);
        } catch (HttpServerErrorException e) {
            throw new PspServerError(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (org.springframework.web.client.RestClientException e) {
            if (isTimeout(e)) {
                throw new PspTimeout("provider call timed out for payment " + paymentId, e);
            }
            throw e;
        }
    }

    public Charge refund(String providerIdemKey, String providerRef, long amountMinor) {
        try {
            return client.post()
                    .uri("/v1/refunds")
                    .body(Map.of("idempotencyKey", providerIdemKey, "providerRef", providerRef,
                            "amountMinor", amountMinor))
                    .retrieve()
                    .body(Charge.class);
        } catch (HttpServerErrorException e) {
            throw new PspServerError(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (org.springframework.web.client.RestClientException e) {
            if (isTimeout(e)) {
                throw new PspTimeout("refund call timed out for provider ref " + providerRef, e);
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    public List<StatementLine> statement() {
        try {
            Map<String, Object> body = client.get().uri("/v1/statement").retrieve().body(Map.class);
            if (body == null) {
                return List.of();
            }
            List<Map<String, Object>> lines = (List<Map<String, Object>>) body.getOrDefault("lines", List.of());
            return lines.stream()
                    .map(line -> new StatementLine(
                            String.valueOf(line.get("providerRef")),
                            String.valueOf(line.getOrDefault("paymentId", "")),
                            ((Number) line.get("amountMinor")).longValue(),
                            String.valueOf(line.get("status"))))
                    .toList();
        } catch (HttpServerErrorException e) {
            throw new PspServerError(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (org.springframework.web.client.RestClientException e) {
            if (isTimeout(e)) {
                throw new PspTimeout("statement request timed out", e);
            }
            throw e;
        }
    }

    /**
     * A read timeout can surface either as a {@link ResourceAccessException} (connect/send) or as
     * a {@link org.springframework.web.client.RestClientException} wrapping a
     * {@code SocketTimeoutException} while the response body is being consumed. Both mean the same
     * thing to a payment: we do not know whether the provider acted.
     */
    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ResourceAccessException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public String baseUrl() {
        return baseUrl;
    }
}
