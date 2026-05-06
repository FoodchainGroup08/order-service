package com.microservices.order.service;

import com.microservices.order.dto.OrderDtos;
import com.microservices.order.entity.Order;
import com.microservices.order.entity.OrderItem;
import com.microservices.order.entity.OutboxEvent;
import com.microservices.order.repository.OrderRepository;
import com.microservices.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private StatusTransitionValidator statusTransitionValidator;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public OrderDtos.OrderResponse createOrder(OrderDtos.CreateOrderRequest request, String idempotencyKey) {
        // Check idempotency: If request processed in last 60 seconds, return cached response
        if (idempotencyKey != null) {
            String cachedResponse = redisTemplate.opsForValue().get("idempotency:" + idempotencyKey);
            if (cachedResponse != null) {
                log.info("Returning cached response for idempotency key: {}", idempotencyKey);
                try {
                    return objectMapper.readValue(cachedResponse, OrderDtos.OrderResponse.class);
                } catch (Exception e) {
                    log.error("Failed to deserialize cached response", e);
                }
            }
        }

        // Create order with items
        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .branchId(request.getBranchId())
                .orderNumber(generateOrderNumber())
                .orderType(Order.OrderType.valueOf(request.getOrderType()))
                .totalAmount(calculateTotal(request.getItems()))
                .deliveryAddress(request.getDeliveryAddress())
                .build();

        order = orderRepository.save(order);
        log.info("Order created: {}", order.getId());

        // Create order items
        Order finalOrder = order;
        var items = request.getItems().stream()
                .map(itemReq -> OrderItem.builder()
                        .order(finalOrder)
                        .menuItemId(itemReq.getMenuItemId())
                        .itemName(itemReq.getItemName())
                        .quantity(itemReq.getQuantity())
                        .unitPrice(itemReq.getUnitPrice())
                        .specialInstructions(itemReq.getSpecialInstructions())
                        .build())
                .collect(Collectors.toList());

        order.setItems(items);

        // Publish outbox event: order.received
        publishOutboxEvent(order, "order.received");

        OrderDtos.OrderResponse response = OrderDtos.OrderResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .status(order.getStatus().toString())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .build();

        // Cache response for idempotency
        if (idempotencyKey != null) {
            try {
                String serialized = objectMapper.writeValueAsString(response);
                redisTemplate.opsForValue().set("idempotency:" + idempotencyKey, serialized, java.time.Duration.ofMinutes(1));
            } catch (Exception e) {
                log.error("Failed to cache response", e);
            }
        }

        return response;
    }

    @Transactional
    public OrderDtos.OrderResponse updateOrderStatus(Long orderId, Order.OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        // Validate status transition
        if (!statusTransitionValidator.isValidTransition(order.getStatus(), newStatus)) {
            var validStates = statusTransitionValidator.getValidNextStates(order.getStatus());
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid status transition from " + order.getStatus() + ". Valid next states: " + validStates
            );
        }

        try {
            order.setStatus(newStatus);
            order = orderRepository.save(order);

            // Publish outbox event
            publishOutboxEvent(order, "order.status.updated");

            if (newStatus == Order.OrderStatus.READY) {
                order.setReadyAt(java.time.LocalDateTime.now());
                orderRepository.save(order);
            }

            log.info("Order {} status updated to {}", orderId, newStatus);

            return OrderDtos.OrderResponse.builder()
                    .orderId(order.getId())
                    .orderNumber(order.getOrderNumber())
                    .status(order.getStatus().toString())
                    .totalAmount(order.getTotalAmount())
                    .createdAt(order.getCreatedAt())
                    .build();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.error("Optimistic locking error: concurrent update detected", e);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order was updated by another request. Please retry.");
        }
    }

    private void publishOutboxEvent(Order order, String eventType) {
        try {
            Map<String, Object> payload = Map.of(
                    "orderId", order.getId(),
                    "orderNumber", order.getOrderNumber(),
                    "customerId", order.getCustomerId(),
                    "branchId", order.getBranchId(),
                    "status", order.getStatus().toString(),
                    "totalAmount", order.getTotalAmount(),
                    "orderType", order.getOrderType().toString()
            );

            OutboxEvent event = OutboxEvent.builder()
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .topicName(eventType)
                    .published(false)
                    .build();

            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to publish outbox event", e);
        }
    }

    private BigDecimal calculateTotal(java.util.List<OrderDtos.OrderItemRequest> items) {
        return items.stream()
                .map(item -> item.getUnitPrice().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String generateOrderNumber() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
