package com.microservices.order.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Paystack REST API — initialize transaction for hosted payment page and verify charges.
 *
 * @see <a href="https://paystack.com/docs/api/transaction/#initialize">Initialize transaction</a>
 * @see <a href="https://paystack.com/docs/api/transaction/#verify">Verify transaction</a>
 */
@Slf4j
@Service
public class PaystackClient {

    private static final String BASE_URL = "https://api.paystack.co";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.paystack.secret-key:}")
    private String secretKey;

    @Value("${app.paystack.currency:NGN}")
    private String currency;

    @Value("${app.paystack.callback-url:http://localhost:5173/checkout/payment/callback}")
    private String callbackUrl;

    /** True when {@code PAYSTACK_SECRET_KEY} / {@code app.paystack.secret-key} is set. */
    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    public PaystackInitResult initializeTransaction(String reference, String customerEmail,
                                                     BigDecimal totalMajorUnits) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Paystack secret key is not configured");
        }
        long amountKobo = totalMajorUnits
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        if (amountKobo <= 0) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Order total must be greater than zero for Paystack");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", reference);
        body.put("email", customerEmail);
        body.put("amount", amountKobo);
        body.put("currency", currency);
        body.put("callback_url", callbackUrl);

        try {
            String raw = restClient.post()
                    .uri(BASE_URL + "/transaction/initialize")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            if (!root.path("status").asBoolean(false)) {
                String msg = root.path("message").asText("Paystack initialize failed");
                log.warn("Paystack initialize rejected: {}", msg);
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, msg);
            }
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Invalid Paystack response");
            }
            return new PaystackInitResult(
                    data.path("authorization_url").asText(null),
                    data.path("access_code").asText(null),
                    data.path("reference").asText(reference));
        } catch (RestClientResponseException e) {
            log.error("Paystack HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Paystack request failed: " + e.getStatusCode());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Paystack initialize error: {}", e.getMessage());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Could not start Paystack payment: " + e.getMessage());
        }
    }

    /**
     * Confirms payment via Paystack HTTP API (no webhook). Reference is the transaction reference
     * returned from initialize (usually the order id).
     */
    public VerifyResult verifyTransaction(String reference) {
        if (!isConfigured()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Paystack secret key is not configured");
        }
        if (reference == null || reference.isBlank()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Payment reference is required");
        }
        String pathRef = UriUtils.encodePathSegment(reference.trim(), StandardCharsets.UTF_8);
        try {
            String raw = restClient.get()
                    .uri(BASE_URL + "/transaction/verify/" + pathRef)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            boolean apiOk = root.path("status").asBoolean(false);
            String apiMessage = root.path("message").asText("");
            JsonNode data = root.get("data");
            if (!apiOk || data == null || data.isNull()) {
                return new VerifyResult(false, 0L, null, apiMessage != null && !apiMessage.isBlank()
                        ? apiMessage : "Paystack verify failed");
            }
            String gatewayStatus = data.path("status").asText("");
            long amountKobo = data.path("amount").asLong(0L);
            boolean paid = "success".equalsIgnoreCase(gatewayStatus);
            return new VerifyResult(paid, amountKobo, gatewayStatus, apiMessage);
        } catch (RestClientResponseException e) {
            log.error("Paystack verify HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Paystack verify failed: " + e.getStatusCode());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Paystack verify error: {}", e.getMessage());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Could not verify Paystack payment: " + e.getMessage());
        }
    }

    public record PaystackInitResult(String authorizationUrl, String accessCode, String reference) {}

    public record VerifyResult(boolean success, long amountKobo, String gatewayStatus, String message) {}
}
