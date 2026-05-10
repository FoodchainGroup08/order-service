package com.microservices.order.controller;

import com.microservices.order.service.OrderService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Paystack sends HTTP POST webhooks (no JWT). Route must stay anonymous on the API gateway.
 * Disabled by default ({@code app.paystack.webhook-enabled=false}) until you enable it in config.
 */
@Slf4j
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaystackWebhookController {

    private final OrderService orderService;

    @Value("${app.paystack.webhook-enabled:false}")
    private boolean webhookEnabled;

    @Hidden
    @PostMapping(value = "/paystack/webhook", consumes = "application/json")
    public ResponseEntity<Void> paystackWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Paystack-Signature", required = false) String signature) {
        log.info("Paystack webhook received (bytes={})", rawBody != null ? rawBody.length() : 0);
        if (!webhookEnabled) {
            log.debug("Paystack webhook ignored — app.paystack.webhook-enabled=false");
            return ResponseEntity.ok().build();
        }
        orderService.processPaystackWebhook(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
