package com.microservices.order.service;

import com.microservices.order.dto.OrderDtos;
import com.microservices.order.entity.Order;
import com.microservices.order.entity.OrderItem;
import com.microservices.order.entity.OrderStatusUpdate;
import com.microservices.order.entity.OutboxEvent;
import com.microservices.order.exception.ResourceNotFoundException;
import com.microservices.order.repository.OrderRepository;
import com.microservices.order.repository.OrderStatusUpdateRepository;
import com.microservices.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private static final List<Order.OrderStatus> ACTIVE_STATUSES =
            List.of(Order.OrderStatus.RECEIVED, Order.OrderStatus.CONFIRMED,
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── createOrder ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public OrderDtos.FrontendOrderResponse createOrder(OrderDtos.CreateOrderRequest request,
                                                       String customerId,
                                                       String customerEmail,
                                                       String idempotencyKey) {
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

        Order.OrderType orderType = parseOrderType(request.getOrderType());

        BigDecimal deliveryFee = Order.OrderType.DELIVERY.equals(orderType) ? DELIVERY_FEE : BigDecimal.ZERO;
        BigDecimal subtotal = calculateSubtotal(request.getItems());
        BigDecimal total = subtotal.add(deliveryFee);

        Order order = Order.builder()
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
                .customerEmail(coalesce(customerEmail, request.getCustomerEmail()))
                .phoneNumber(request.getPhoneNumber())
                .paymentMethod(request.getPaymentMethod())
                .specialInstructions(request.getSpecialInstructions())
                .estimatedTime(DEFAULT_ESTIMATED_TIME)
                .idempotencyKey(idempotencyKey)
                .build();

        order = orderRepository.save(order);
        log.info("Order created: {}", order.getId());

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

        publishOutboxEvent(order, "order.received");

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

            publishOutboxEvent(order, "order.status.updated");

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

    /**
     * Parses orderType from frontend format ("dine-in", "takeaway", "delivery") or
     * backend enum name format ("DINE_IN", "TAKEAWAY", "DELIVERY").
     */
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

    /**
     * Converts an OrderType enum to the lowercase-hyphen string the frontend expects.
     */
    private String toFrontendOrderType(Order.OrderType type) {
        return switch (type) {
            case DINE_IN -> "dine-in";
            case TAKEAWAY -> "takeaway";
            case DELIVERY -> "delivery";
        };
    }

    /** Returns the item name from the request, or a safe default if not supplied. */
    private String resolveItemName(OrderDtos.OrderItemRequest req) {
        if (req.getMenuItemName() != null && !req.getMenuItemName().isBlank()) {
            return req.getMenuItemName();
        }
        String id = req.getMenuItemId();
        String suffix = (id != null && id.length() >= 4) ? id.substring(id.length() - 4) : id;
        return "Item " + suffix;
    }

    /** Returns the unit price from the request, or BigDecimal.ZERO if not supplied. */
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
                .customerEmail(order.getCustomerEmail())
                .phoneNumber(order.getPhoneNumber())
                .specialInstructions(order.getSpecialInstructions())
                .estimatedTime(order.getEstimatedTime())
                .placedAt(placedAt)
                .paymentMethod(order.getPaymentMethod())
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

    private String coalesce(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
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
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("orderId",     order.getId());
            payload.put("customerId",  order.getCustomerId());
            payload.put("branchId",    order.getBranchId());
            payload.put("status",      order.getStatus().toString());
            payload.put("totalAmount", order.getTotalAmount());
            payload.put("orderType",   order.getOrderType().toString());

            payload.put("customerName",  order.getCustomerName());
            payload.put("customerEmail", order.getCustomerEmail());

            if ("order.status.updated".equals(topic) || "order.ready".equals(topic)) {
                OrderStatusUpdate latest = orderStatusUpdateRepository
                        .findTopByOrder_IdOrderByTimestampDesc(order.getId())
                        .orElse(null);
                if (latest != null) {
                    payload.put("previousStatus", latest.getOldStatus());
                    payload.put("newStatus",      latest.getNewStatus());
                    payload.put("updatedBy",      latest.getUpdatedBy());
                    payload.put("notes",          latest.getNotes());
                } else {
                    payload.put("newStatus", order.getStatus().toString());
                }
            }

            if ("order.received".equals(topic)) {
                payload.put("tableNumber",          order.getTableNumber());
                payload.put("notes",                order.getNotes());
                payload.put("phoneNumber",          order.getPhoneNumber());
                payload.put("paymentMethod",        order.getPaymentMethod());
                payload.put("specialInstructions",  order.getSpecialInstructions());
                payload.put("deliveryAddress",      order.getDeliveryAddress());
                payload.put("deliveryFee",          order.getDeliveryFee());
                if (order.getItems() != null) {
                    var itemList = order.getItems().stream()
                            .map(item -> {
                                Map<String, Object> i = new java.util.HashMap<>();
                                i.put("menuItemId",          item.getMenuItemId());
                                i.put("menuItemName",        item.getMenuItemName());
                                i.put("quantity",            item.getQuantity());
                                i.put("unitPrice",           item.getUnitPrice());
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
