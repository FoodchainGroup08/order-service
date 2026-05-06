package com.microservices.order.controller;

import com.microservices.order.dto.OrderDtos;
import com.microservices.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
        OrderDtos.OrderResponse response = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{orderId}/status")
    public ResponseEntity<OrderDtos.OrderResponse> updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody OrderDtos.UpdateStatusRequest request) {
        log.info("Update order status: orderId={}, newStatus={}", orderId, request.getNewStatus());
        OrderDtos.OrderResponse response = orderService.updateOrderStatus(orderId, 
                Enum.valueOf(com.microservices.order.entity.Order.OrderStatus.class, request.getNewStatus()));
        return ResponseEntity.ok(response);
    }
}
