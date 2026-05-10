package com.microservices.order.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes HTML email jobs to Kafka — same JSON shape as {@code notification.email.send}
 * consumed by notifications-service / Brevo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEmailPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topic-email-send:notification.email.send}")
    private String emailTopic;

    @Value("${app.paystack.currency:NGN}")
    private String currencyLabel;

    /** Order created — Paystack checkout (includes payment link in HTML). */
    public void sendOrderPlacedOnlineEmail(Order order, String paymentUrl, String toEmail) {
        String html = OrderEmailTemplates.orderPlacedOnline(order, paymentUrl, currencyLabel);
        String name = displayName(order);
        publish(toEmail, name,
                "Complete payment — FoodChain order " + order.getId(),
                html,
                "ORDER_PLACED_ONLINE");
    }

    /** Order created — cash / in-branch paths (no Paystack). */
    public void sendOrderPlacedOfflineEmail(Order order, String toEmail) {
        String html = OrderEmailTemplates.orderPlacedOffline(order, currencyLabel);
        String name = displayName(order);
        publish(toEmail, name,
                "Order received — FoodChain " + order.getId(),
                html,
                "ORDER_PLACED_OFFLINE");
    }

    /** After Paystack confirms payment (verify API or webhook). */
    public void sendPaymentSuccessfulEmail(Order order, String toEmail) {
        String html = OrderEmailTemplates.paymentSuccessful(order, currencyLabel);
        String name = displayName(order);
        publish(toEmail, name,
                "Payment confirmed — FoodChain order " + order.getId(),
                html,
                "ORDER_PAYMENT_CONFIRMED");
    }

    private static String displayName(Order order) {
        String n = order.getCustomerName();
        return (n != null && !n.isBlank()) ? n.trim() : "Customer";
    }

    private void publish(String toEmail, String toName, String subject, String html, String emailType) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Skip email {} — no recipient", emailType);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toEmail", toEmail);
        body.put("toName", toName);
        body.put("subject", subject);
        body.put("htmlContent", html);
        body.put("emailType", emailType);
        try {
            String json = objectMapper.writeValueAsString(body);
            kafkaTemplate.send(emailTopic, toEmail, json)
                    .whenComplete((r, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} to Kafka: {}", emailType, ex.getMessage());
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Serialize email event failed: {}", e.getMessage());
        }
    }
}
