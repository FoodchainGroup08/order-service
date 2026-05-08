package com.microservices.order.service;

import com.microservices.order.dto.OrderDtos;
import com.microservices.order.entity.Order;
import com.microservices.order.entity.OrderItem;
import com.microservices.order.entity.OrderStatusUpdate;
import com.microservices.order.entity.OutboxEvent;
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
            List.of(Order.OrderStatus.COMPLETED, Order.OrderStatus.CANCELLED);

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

    @Override
    @Transactional
    public OrderDtos.OrderResponse createOrder(OrderDtos.CreateOrderRequest request, String idempotencyKey) {
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

        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .branchId(request.getBranchId())
                .orderType(Order.OrderType.valueOf(request.getOrderType()))
                .totalAmount(calculateTotal(request.getItems()))
                .deliveryAddress(request.getDeliveryAddress())
                .tableNumber(request.getTableNumber())
                .notes(request.getNotes())
                .idempotencyKey(idempotencyKey)
                .build();

        order = orderRepository.save(order);
        log.info("Order created: {}", order.getId());

        Order finalOrder = order;
        var items = request.getItems().stream()
                .map(itemReq -> {
                    BigDecimal subtotal = itemReq.getUnitPrice()
                            .multiply(new BigDecimal(itemReq.getQuantity()));
                    return OrderItem.builder()
                            .order(finalOrder)
                            .menuItemId(itemReq.getMenuItemId())
                            .menuItemName(itemReq.getMenuItemName())
                            .quantity(itemReq.getQuantity())
                            .unitPrice(itemReq.getUnitPrice())
                            .specialInstructions(itemReq.getSpecialInstructions())
                            .subtotal(subtotal)
                            .build();
                })
                .collect(Collectors.toList());

        order.setItems(items);

        publishOutboxEvent(order, "order.received");

        OrderDtos.OrderResponse response = OrderDtos.OrderResponse.builder()
                .orderId(order.getId())
                .status(order.getStatus().toString())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .build();

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

    @Override
    @Transactional(readOnly = true)
    public OrderDtos.OrderDetailResponse getOrderById(String orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        return toDetailResponse(order);
    }

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

    @Override
    @Transactional
    public OrderDtos.OrderResponse updateOrderStatus(String orderId, Order.OrderStatus newStatus,
                                                     String updatedBy, String notes) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

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

    private void publishOutboxEvent(Order order, String topic) {
        try {
            Map<String, Object> payload = Map.of(
                    "orderId", order.getId(),
                    "customerId", order.getCustomerId(),
                    "branchId", order.getBranchId(),
                    "status", order.getStatus().toString(),
                    "totalAmount", order.getTotalAmount(),
                    "orderType", order.getOrderType().toString()
            );

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

    private OrderDtos.OrderListResponse toListResponse(Order order) {
        return OrderDtos.OrderListResponse.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .branchId(order.getBranchId())
                .orderType(order.getOrderType().toString())
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
                .orderType(order.getOrderType().toString())
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

    private BigDecimal calculateTotal(java.util.List<OrderDtos.OrderItemRequest> items) {
        return items.stream()
                .map(item -> item.getUnitPrice().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
