package com.microservices.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.dto.OrderDtos;
import com.microservices.order.entity.Order;
import com.microservices.order.entity.OrderItem;
import com.microservices.order.entity.OrderStatusUpdate;
import com.microservices.order.entity.OutboxEvent;
import com.microservices.order.exception.ResourceNotFoundException;
import com.microservices.order.notification.OrderEmailPublisher;
import com.microservices.order.payment.PaystackClient;
import com.microservices.order.payment.PaystackSignatureVerifier;
import com.microservices.order.repository.OrderRepository;
import com.microservices.order.repository.OrderStatusUpdateRepository;
import com.microservices.order.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private static final List<Order.OrderStatus> ACTIVE_STATUSES =
            List.of(Order.OrderStatus.PAYMENT_PENDING,
                    Order.OrderStatus.RECEIVED, Order.OrderStatus.CONFIRMED,
                    Order.OrderStatus.PREPARING, Order.OrderStatus.READY);

    private static final List<Order.OrderStatus> HISTORY_STATUSES =
            List.of(Order.OrderStatus.PICKED_UP, Order.OrderStatus.SERVED,
                    Order.OrderStatus.COMPLETED, Order.OrderStatus.CANCELLED);

    private static final BigDecimal DELIVERY_FEE = new BigDecimal("2.00");
    private static final String DEFAULT_ESTIMATED_TIME = "20-30 minutes";

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderStatusUpdateRepository orderStatusUpdateRepository;

    @Autowired
    private StatusTransitionValidator statusTransitionValidator;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private PaystackClient paystackClient;

    @Autowired
    private PaystackSignatureVerifier paystackSignatureVerifier;

    @Autowired
    private OrderEmailPublisher orderEmailPublisher;

    @Value("${app.paystack.default-online-checkout:true}")
    private boolean defaultOnlineCheckout;

    @Value("${app.paystack.webhook-enabled:false}")
    private boolean paystackWebhookEnabled;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── createOrder ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public OrderDtos.FrontendOrderResponse createOrder(OrderDtos.CreateOrderRequest request,
                                                       String customerId,
                                                       String idempotencyKey,
                                                       String customerEmailHeader) {
        if (idempotencyKey != null) {
            String cachedResponse = redisTemplate.opsForValue().get("idempotency:" + idempotencyKey);
            if (cachedResponse != null) {
                log.info("Returning cached response for idempotency key: {}", idempotencyKey);
                try {
                    return objectMapper.readValue(cachedResponse, OrderDtos.FrontendOrderResponse.class);
                } catch (Exception e) {
                    log.error("Failed to deserialize cached response", e);
                }
            }
        }

        if (wantsExplicitOnlinePay(request.getPaymentMethod()) && !paystackClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Online payment (Paystack) is not configured — set PAYSTACK_SECRET_KEY");
        }

        boolean paystack = shouldUsePaystack(request.getPaymentMethod());

        Order.OrderType orderType = parseOrderType(request.getOrderType());

        BigDecimal deliveryFee = Order.OrderType.DELIVERY.equals(orderType) ? DELIVERY_FEE : BigDecimal.ZERO;
        BigDecimal subtotal = calculateSubtotal(request.getItems());
        BigDecimal total = subtotal.add(deliveryFee);

        String resolvedEmail = resolveCustomerEmail(request.getCustomerEmail(), customerEmailHeader);
        if (paystack && (resolvedEmail == null || resolvedEmail.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide customerEmail in the body or X-User-Email header for Paystack checkout");
        }

        String paymentMethodStored = request.getPaymentMethod();
        if (paystack && (paymentMethodStored == null || paymentMethodStored.isBlank())) {
            paymentMethodStored = "paystack";
        }

        Order.OrderBuilder builder = Order.builder()
                .customerId(customerId)
                .branchId(request.getBranchId())
                .branchName(request.getBranchName())
                .orderType(orderType)
                .totalAmount(total)
                .deliveryFee(deliveryFee)
                .deliveryAddress(request.getDeliveryAddress())
                .tableNumber(request.getTableNumber())
                .notes(request.getNotes())
                .customerName(request.getCustomerName())
                .phoneNumber(request.getPhoneNumber())
                .paymentMethod(paymentMethodStored)
                .specialInstructions(request.getSpecialInstructions())
                .estimatedTime(DEFAULT_ESTIMATED_TIME)
                .idempotencyKey(idempotencyKey);

        if (paystack) {
            builder.status(Order.OrderStatus.PAYMENT_PENDING)
                    .paymentState(Order.PaymentState.PENDING)
                    .customerEmail(resolvedEmail != null ? resolvedEmail.trim() : null);
        } else {
            builder.paymentState(Order.PaymentState.NOT_APPLICABLE);
        }

        Order order = builder.build();

        order = orderRepository.save(order);
        log.info("Order created: {} paystack={}", order.getId(), paystack);

        Order finalOrder = order;
        List<OrderItem> items = request.getItems().stream()
                .map(itemReq -> {
                    String itemName = resolveItemName(itemReq);
                    BigDecimal unitPrice = resolveUnitPrice(itemReq);
                    BigDecimal itemSubtotal = unitPrice.multiply(new BigDecimal(itemReq.getQuantity()));
                    return OrderItem.builder()
                            .order(finalOrder)
                            .menuItemId(itemReq.getMenuItemId())
                            .menuItemName(itemName)
                            .quantity(itemReq.getQuantity())
                            .unitPrice(unitPrice)
                            .specialInstructions(itemReq.getSpecialInstructions())
                            .subtotal(itemSubtotal)
                            .build();
                })
                .collect(Collectors.toList());

        order.setItems(items);

        PaystackClient.PaystackInitResult paystackInit = null;
        if (paystack) {
            paystackInit = paystackClient.initializeTransaction(
                    order.getId(), resolvedEmail.trim(), total);
            order.setPaystackAccessCode(paystackInit.accessCode());
            order.setPaystackReference(paystackInit.reference());
            order.setPaystackAuthorizationUrl(paystackInit.authorizationUrl());
            order = orderRepository.save(order);
            orderEmailPublisher.sendOrderPlacedOnlineEmail(
                    order, paystackInit.authorizationUrl(), resolvedEmail.trim());
        } else {
            publishOutboxEvent(order, "order.received");
            if (resolvedEmail != null && !resolvedEmail.isBlank()) {
                orderEmailPublisher.sendOrderPlacedOfflineEmail(order, resolvedEmail.trim());
            }
        }

        OrderDtos.FrontendOrderResponse response = toFrontendResponse(order);

        if (idempotencyKey != null) {
            try {
                String serialized = objectMapper.writeValueAsString(response);
                redisTemplate.opsForValue().set("idempotency:" + idempotencyKey, serialized,
                        java.time.Duration.ofMinutes(1));
            } catch (Exception e) {
                log.error("Failed to cache response", e);
            }
        }

        return response;
    }

    // ── Paystack webhook ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public void processPaystackWebhook(String rawBody, String signature) {
        if (!paystackWebhookEnabled) {
            log.debug("Ignoring Paystack webhook (app.paystack.webhook-enabled=false)");
            return;
        }
        if (!paystackSignatureVerifier.isValid(rawBody, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Paystack signature");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook JSON");
        }

        String event = root.path("event").asText("");
        if (!"charge.success".equals(event)) {
            log.debug("Ignoring Paystack event {}", event);
            return;
        }

        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            log.warn("Paystack charge.success without data object");
            return;
        }

        String reference = data.path("reference").asText(null);
        long amountKobo = data.path("amount").asLong(0);
        if (reference == null || reference.isBlank()) {
            log.warn("Paystack charge.success without reference");
            return;
        }

        confirmPaystackCharge(reference, amountKobo);
    }

    private void confirmPaystackCharge(String orderId, long amountKobo) {
        Order order = orderRepository.findByIdWithItems(orderId).orElse(null);
        if (order == null) {
            log.warn("Paystack webhook: unknown reference {}", orderId);
            return;
        }
        finalizePaidOrder(order, amountKobo, "paystack-webhook", "Paystack charge.success");
    }

    /**
     * Shared confirmation path for webhook and Paystack verify API.
     */
    private void finalizePaidOrder(Order order, long amountKobo, String updatedBy, String notes) {
        String orderId = order.getId();
        if (order.getStatus() != Order.OrderStatus.PAYMENT_PENDING) {
            log.info("Order {} not PAYMENT_PENDING (was {}) — idempotent payment noop",
                    orderId, order.getStatus());
            return;
        }

        long expectedKobo = order.getTotalAmount()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        if (amountKobo != expectedKobo) {
            log.error("Paystack amount mismatch order={} expectedKobo={} actualKobo={}",
                    orderId, expectedKobo, amountKobo);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paystack amount mismatch for order");
        }

        String oldStatus = order.getStatus().toString();
        order.setPaymentState(Order.PaymentState.PAID);
        order.setStatus(Order.OrderStatus.CONFIRMED);
        order.setPaystackAuthorizationUrl(null);
        order = orderRepository.save(order);

        orderStatusUpdateRepository.save(OrderStatusUpdate.builder()
                .order(order)
                .oldStatus(oldStatus)
                .newStatus(Order.OrderStatus.CONFIRMED.toString())
                .updatedBy(updatedBy)
                .notes(notes)
                .build());

        publishOutboxEvent(order, "order.received");
        publishOutboxEvent(order, "order.status.updated", oldStatus);

        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            orderEmailPublisher.sendPaymentSuccessfulEmail(order, order.getCustomerEmail());
        }

        log.info("Order {} confirmed after Paystack payment", orderId);
    }

    private boolean shouldUsePaystack(String paymentMethod) {
        if (!paystackClient.isConfigured()) {
            return false;
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return defaultOnlineCheckout;
        }
        String p = paymentMethod.trim().toLowerCase();
        if (isExplicitOfflinePayment(p)) {
            return false;
        }
        return "paystack".equals(p) || "online".equals(p);
    }

    private static boolean isExplicitOfflinePayment(String p) {
        return "cash".equals(p) || "cod".equals(p) || "card".equals(p)
                || "in_store".equals(p) || "in-store".equals(p);
    }

    private static boolean wantsExplicitOnlinePay(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return false;
        }
        String p = paymentMethod.trim().toLowerCase();
        return "paystack".equals(p) || "online".equals(p);
    }

    @Override
    @Transactional
    public OrderDtos.FrontendOrderResponse verifyPaystackPayment(String orderId, String customerId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!customerId.equals(order.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to verify this order");
        }
        if (order.getStatus() != Order.OrderStatus.PAYMENT_PENDING) {
            return toFrontendResponse(order);
        }
        String ref = (order.getPaystackReference() != null && !order.getPaystackReference().isBlank())
                ? order.getPaystackReference()
                : orderId;
        PaystackClient.VerifyResult vr = paystackClient.verifyTransaction(ref);
        if (!vr.success()) {
            String msg = vr.message() != null && !vr.message().isBlank()
                    ? vr.message()
                    : "Payment not completed";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
        finalizePaidOrder(order, vr.amountKobo(), "paystack-verify-api", "Paystack verify API");
        Order updated = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return toFrontendResponse(updated);
    }

    private static String resolveCustomerEmail(String bodyEmail, String headerEmail) {
        if (bodyEmail != null && !bodyEmail.isBlank()) {
            return bodyEmail.trim();
        }
        if (headerEmail != null && !headerEmail.isBlank()) {
            return headerEmail.trim();
        }
        return null;
    }

    // ── getOrderById ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public OrderDtos.FrontendOrderResponse getFrontendOrderById(String orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return toFrontendResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDtos.OrderDetailResponse getOrderById(String orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return toDetailResponse(order);
    }

    // ── list endpoints ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<OrderDtos.OrderListResponse> getActiveOrders(String customerId, String branchId,
                                                              int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderRepository
                .findByStatusWithFilters(ACTIVE_STATUSES, customerId, branchId, pageable)
                .map(this::toListResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderDtos.OrderListResponse> getOrderHistory(String customerId, String branchId,
                                                              int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderRepository
                .findByStatusWithFilters(HISTORY_STATUSES, customerId, branchId, pageable)
                .map(this::toListResponse);
    }

    // ── status update / cancel ────────────────────────────────────────────────

    @Override
    @Transactional
    public OrderDtos.OrderResponse updateOrderStatus(String orderId, Order.OrderStatus newStatus,
                                                     String updatedBy, String notes) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (!statusTransitionValidator.isValidTransition(order.getStatus(), newStatus)) {
            var validStates = statusTransitionValidator.getValidNextStates(order.getStatus());
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid status transition from " + order.getStatus() + ". Valid next states: " + validStates
            );
        }

        try {
            String oldStatus = order.getStatus().toString();
            order.setStatus(newStatus);
            order = orderRepository.save(order);

            orderStatusUpdateRepository.save(OrderStatusUpdate.builder()
                    .order(order)
                    .oldStatus(oldStatus)
                    .newStatus(newStatus.toString())
                    .updatedBy(updatedBy != null ? updatedBy : "system")
                    .notes(notes)
                    .build());

            publishOutboxEvent(order, "order.status.updated", oldStatus);

            if (newStatus == Order.OrderStatus.READY) {
                publishOutboxEvent(order, "order.ready");
            }

            log.info("Order {} status updated to {}", orderId, newStatus);

            return OrderDtos.OrderResponse.builder()
                    .orderId(order.getId())
                    .status(order.getStatus().toString())
                    .totalAmount(order.getTotalAmount())
                    .createdAt(order.getCreatedAt())
                    .build();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.error("Optimistic locking error: concurrent update detected", e);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order was updated by another request. Please retry.");
        }
    }

    @Override
    @Transactional
    public OrderDtos.OrderResponse cancelOrder(String orderId, String cancelledBy, String reason) {
        return updateOrderStatus(orderId, Order.OrderStatus.CANCELLED, cancelledBy, reason);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private Order.OrderType parseOrderType(String orderType) {
        if (orderType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderType is required");
        }
        return switch (orderType.toLowerCase().replace("-", "_")) {
            case "dine_in" -> Order.OrderType.DINE_IN;
            case "takeaway" -> Order.OrderType.TAKEAWAY;
            case "delivery" -> Order.OrderType.DELIVERY;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid orderType: " + orderType);
        };
    }

    private String toFrontendOrderType(Order.OrderType type) {
        return switch (type) {
            case DINE_IN -> "dine-in";
            case TAKEAWAY -> "takeaway";
            case DELIVERY -> "delivery";
        };
    }

    private String resolveItemName(OrderDtos.OrderItemRequest req) {
        if (req.getMenuItemName() != null && !req.getMenuItemName().isBlank()) {
            return req.getMenuItemName();
        }
        String id = req.getMenuItemId();
        String suffix = (id != null && id.length() >= 4) ? id.substring(id.length() - 4) : id;
        return "Item " + suffix;
    }

    private BigDecimal resolveUnitPrice(OrderDtos.OrderItemRequest req) {
        return (req.getUnitPrice() != null) ? req.getUnitPrice() : BigDecimal.ZERO;
    }

    private BigDecimal calculateSubtotal(List<OrderDtos.OrderItemRequest> items) {
        return items.stream()
                .map(item -> resolveUnitPrice(item).multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private OrderDtos.FrontendOrderResponse toFrontendResponse(Order order) {
        BigDecimal deliveryFee = order.getDeliveryFee() != null ? order.getDeliveryFee() : BigDecimal.ZERO;

        List<OrderDtos.FrontendOrderItemResponse> frontendItems = null;
        BigDecimal subtotal = BigDecimal.ZERO;

        if (order.getItems() != null) {
            frontendItems = order.getItems().stream()
                    .map(item -> OrderDtos.FrontendOrderItemResponse.builder()
                            .id(item.getId())
                            .name(item.getMenuItemName())
                            .price(item.getUnitPrice() != null ? item.getUnitPrice().doubleValue() : 0.0)
                            .quantity(item.getQuantity() != null ? item.getQuantity() : 0)
                            .build())
                    .collect(Collectors.toList());

            subtotal = order.getItems().stream()
                    .map(item -> item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        String placedAt = order.getCreatedAt() != null
                ? order.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z"
                : null;

        Order.PaymentState ps = order.getPaymentState() != null
                ? order.getPaymentState()
                : Order.PaymentState.NOT_APPLICABLE;

        String paymentLink = (order.getStatus() == Order.OrderStatus.PAYMENT_PENDING
                && order.getPaystackAuthorizationUrl() != null
                && !order.getPaystackAuthorizationUrl().isBlank())
                ? order.getPaystackAuthorizationUrl()
                : null;

        return OrderDtos.FrontendOrderResponse.builder()
                .id(order.getId())
                .status(order.getStatus() != null ? order.getStatus().toString() : null)
                .items(frontendItems)
                .subtotal(subtotal)
                .deliveryFee(deliveryFee)
                .total(order.getTotalAmount())
                .branchId(order.getBranchId())
                .branchName(order.getBranchName())
                .orderType(order.getOrderType() != null ? toFrontendOrderType(order.getOrderType()) : null)
                .tableNumber(order.getTableNumber())
                .deliveryAddress(order.getDeliveryAddress())
                .customerName(order.getCustomerName())
                .phoneNumber(order.getPhoneNumber())
                .specialInstructions(order.getSpecialInstructions())
                .estimatedTime(order.getEstimatedTime())
                .placedAt(placedAt)
                .paymentMethod(order.getPaymentMethod())
                .paymentLink(paymentLink)
                .paymentReference(order.getPaystackReference())
                .paymentState(ps.name())
                .build();
    }

    private OrderDtos.OrderListResponse toListResponse(Order order) {
        return OrderDtos.OrderListResponse.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .branchId(order.getBranchId())
                .orderType(order.getOrderType() != null ? toFrontendOrderType(order.getOrderType()) : null)
                .status(order.getStatus().toString())
                .tableNumber(order.getTableNumber())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    private OrderDtos.OrderDetailResponse toDetailResponse(Order order) {
        List<OrderDtos.OrderItemResponse> items = order.getItems().stream()
                .map(item -> OrderDtos.OrderItemResponse.builder()
                        .id(item.getId())
                        .menuItemId(item.getMenuItemId())
                        .menuItemName(item.getMenuItemName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .subtotal(item.getSubtotal())
                        .specialInstructions(item.getSpecialInstructions())
                        .build())
                .collect(Collectors.toList());

        return OrderDtos.OrderDetailResponse.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .branchId(order.getBranchId())
                .orderType(order.getOrderType() != null ? order.getOrderType().toString() : null)
                .status(order.getStatus().toString())
                .tableNumber(order.getTableNumber())
                .deliveryAddress(order.getDeliveryAddress())
                .totalAmount(order.getTotalAmount())
                .notes(order.getNotes())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .items(items)
                .build();
    }

    private void publishOutboxEvent(Order order, String topic) {
        publishOutboxEvent(order, topic, null);
    }

    private void publishOutboxEvent(Order order, String topic, String previousStatus) {
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("orderId", order.getId());
            payload.put("customerId", order.getCustomerId());
            payload.put("branchId", order.getBranchId());
            payload.put("status", order.getStatus().toString());
            payload.put("totalAmount", order.getTotalAmount());
            payload.put("orderType", order.getOrderType().toString());

            if ("order.status.updated".equals(topic) && previousStatus != null) {
                payload.put("previousStatus", previousStatus);
                payload.put("newStatus", order.getStatus().toString());
            }

            if ("order.received".equals(topic)) {
                payload.put("tableNumber", order.getTableNumber());
                payload.put("notes", order.getNotes());
                payload.put("customerName", order.getCustomerName());
                payload.put("phoneNumber", order.getPhoneNumber());
                payload.put("paymentMethod", order.getPaymentMethod());
                payload.put("specialInstructions", order.getSpecialInstructions());
                payload.put("deliveryAddress", order.getDeliveryAddress());
                payload.put("deliveryFee", order.getDeliveryFee());
                if (order.getItems() != null) {
                    var itemList = order.getItems().stream()
                            .map(item -> {
                                Map<String, Object> i = new java.util.HashMap<>();
                                i.put("menuItemId", item.getMenuItemId());
                                i.put("menuItemName", item.getMenuItemName());
                                i.put("quantity", item.getQuantity());
                                i.put("unitPrice", item.getUnitPrice());
                                i.put("specialInstructions", item.getSpecialInstructions());
                                return i;
                            })
                            .collect(Collectors.toList());
                    payload.put("items", itemList);
                }
            }

            OutboxEvent event = OutboxEvent.builder()
                    .topic(topic)
                    .partitionKey(order.getId())
                    .payload(objectMapper.writeValueAsString(payload))
                    .published(false)
                    .build();

            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to publish outbox event", e);
        }
    }
}
