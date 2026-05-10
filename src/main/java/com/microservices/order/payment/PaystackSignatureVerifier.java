package com.microservices.order.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Validates {@code X-Paystack-Signature} (HMAC SHA512 of raw body with secret key).
 */
@Component
public class PaystackSignatureVerifier {

    @Value("${app.paystack.secret-key:}")
    private String secretKey;

    public boolean isValid(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()
                || secretKey == null || secretKey.isBlank()
                || rawBody == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expectedHex = HexFormat.of().formatHex(hash);
            String provided = signatureHeader.trim().toLowerCase();
            String expected = expectedHex.toLowerCase();
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
