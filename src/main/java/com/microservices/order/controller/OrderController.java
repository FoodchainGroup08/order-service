package com.microservices.order.controller;

import com.microservices.order.dto.OrderDtos;
import com.microservices.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderDtos.OrderResponse> createOrder(
            @RequestBody OrderDtos.CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        log.info("Create order request: {}", request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.createOrder(request, idempotencyKey));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDtos.OrderDetailResponse> getOrderById(@PathVariable String orderId) {
        log.info("Get order by id: {}", orderId);
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @GetMapping("/active")
    public ResponseEntity<Page<OrderDtos.OrderListResponse>> getActiveOrders(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Get active orders: customerId={}, branchId={}", customerId, branchId);
        return ResponseEntity.ok(orderService.getActiveOrders(customerId, branchId, page, size));
    }

    @GetMapping("/history")
    public ResponseEntity<Page<OrderDtos.OrderListResponse>> getOrderHistory(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Get order history: customerId={}, branchId={}", customerId, branchId);
        return ResponseEntity.ok(orderService.getOrderHistory(customerId, branchId, page, size));
    }

    @PutMapping("/{orderId}/status")
    public ResponseEntity<OrderDtos.OrderResponse> updateOrderStatus(
            @PathVariable String orderId,
            @RequestBody OrderDtos.UpdateStatusRequest request) {
        log.info("Update order status: orderId={}, newStatus={}", orderId, request.getNewStatus());
        return ResponseEntity.ok(orderService.updateOrderStatus(
                orderId,
                Enum.valueOf(com.microservices.order.entity.Order.OrderStatus.class, request.getNewStatus()),
                request.getUpdatedBy(),
                request.getNotes()));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderDtos.OrderResponse> cancelOrder(
            @PathVariable String orderId,
            @RequestBody OrderDtos.CancelOrderRequest request) {
        log.info("Cancel order: orderId={}, cancelledBy={}", orderId, request.getCancelledBy());
        return ResponseEntity.ok(orderService.cancelOrder(
                orderId, request.getCancelledBy(), request.getReason()));
    }
}
