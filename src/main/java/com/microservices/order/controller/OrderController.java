package com.microservices.order.controller;

import com.microservices.order.dto.OrderDtos;
import com.microservices.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Place new orders, track order status, view order history, and manage the order lifecycle through to completion or cancellation.")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Operation(
        summary = "Place a new order",
        description = "Creates a new order for the specified customer and branch. Item prices and names are snapshotted at the time of order creation so the order record is unaffected by future menu changes. "
            + "Supply an Idempotency-Key header to safely retry without creating duplicate orders — the same key will return the original order response.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Order placed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request — missing required fields or empty items list"),
        @ApiResponse(responseCode = "409", description = "Duplicate request — order with this Idempotency-Key already exists")
    })
    @PostMapping
    public ResponseEntity<OrderDtos.OrderResponse> createOrder(
            @RequestBody OrderDtos.CreateOrderRequest request,
            @Parameter(description = "Optional client-generated unique key used to safely retry the request without creating duplicate orders")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        log.info("Create order request: {}", request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.createOrder(request, idempotencyKey));
    }

    @Operation(
        summary = "Get order by ID",
        description = "Returns full details for a single order including the item breakdown, pricing, current status, and the full status history.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order details with item lines and status history"),
        @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDtos.OrderDetailResponse> getOrderById(
            @Parameter(description = "UUID of the order", required = true)
            @PathVariable String orderId) {
        log.info("Get order by id: {}", orderId);
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @Operation(
        summary = "List active orders",
        description = "Returns a paginated list of orders that are currently in progress (e.g. PENDING, ACCEPTED, PREPARING, READY). "
            + "Filter by customerId to show a specific customer's live orders, or by branchId to show all active orders at a branch (useful for kitchen display systems).",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of active orders")
    })
    @GetMapping("/active")
    public ResponseEntity<Page<OrderDtos.OrderListResponse>> getActiveOrders(
            @Parameter(description = "Filter to orders placed by this customer UUID")
            @RequestParam(required = false) String customerId,
            @Parameter(description = "Filter to orders placed at this branch UUID")
            @RequestParam(required = false) String branchId,
            @Parameter(description = "Zero-based page number (default 0)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Number of orders per page (default 10)")
            @RequestParam(defaultValue = "10") int size) {
        log.info("Get active orders: customerId={}, branchId={}", customerId, branchId);
        return ResponseEntity.ok(orderService.getActiveOrders(customerId, branchId, page, size));
    }

    @Operation(
        summary = "Get order history",
        description = "Returns a paginated list of completed or cancelled orders, sorted by most recent first. "
            + "Filter by customerId to show a specific customer's past orders, or by branchId to show all historical orders at a branch.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of completed or cancelled orders")
    })
    @GetMapping("/history")
    public ResponseEntity<Page<OrderDtos.OrderListResponse>> getOrderHistory(
            @Parameter(description = "Filter to orders placed by this customer UUID")
            @RequestParam(required = false) String customerId,
            @Parameter(description = "Filter to orders placed at this branch UUID")
            @RequestParam(required = false) String branchId,
            @Parameter(description = "Zero-based page number (default 0)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Number of orders per page (default 10)")
            @RequestParam(defaultValue = "10") int size) {
        log.info("Get order history: customerId={}, branchId={}", customerId, branchId);
        return ResponseEntity.ok(orderService.getOrderHistory(customerId, branchId, page, size));
    }

    @Operation(
        summary = "Update order status",
        description = "Transitions an order to a new status. Valid status values are: PENDING → ACCEPTED → PREPARING → READY → DELIVERED. "
            + "A status-change event is published to Kafka so the kitchen-service and other consumers are notified in real time.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order status updated"),
        @ApiResponse(responseCode = "400", description = "Invalid status value or illegal status transition"),
        @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @PutMapping("/{orderId}/status")
    public ResponseEntity<OrderDtos.OrderResponse> updateOrderStatus(
            @Parameter(description = "UUID of the order to update", required = true)
            @PathVariable String orderId,
            @RequestBody OrderDtos.UpdateStatusRequest request) {
        log.info("Update order status: orderId={}, newStatus={}", orderId, request.getNewStatus());
        return ResponseEntity.ok(orderService.updateOrderStatus(
                orderId,
                Enum.valueOf(com.microservices.order.entity.Order.OrderStatus.class, request.getNewStatus()),
                request.getUpdatedBy(),
                request.getNotes()));
    }

    @Operation(
        summary = "Cancel an order",
        description = "Cancels the order and records who cancelled it and why. Only orders in PENDING or ACCEPTED status can be cancelled — orders that are already being prepared cannot be reversed.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order cancelled successfully"),
        @ApiResponse(responseCode = "400", description = "Order is in a state that cannot be cancelled"),
        @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderDtos.OrderResponse> cancelOrder(
            @Parameter(description = "UUID of the order to cancel", required = true)
            @PathVariable String orderId,
            @RequestBody OrderDtos.CancelOrderRequest request) {
        log.info("Cancel order: orderId={}, cancelledBy={}", orderId, request.getCancelledBy());
        return ResponseEntity.ok(orderService.cancelOrder(
                orderId, request.getCancelledBy(), request.getReason()));
    }
}
