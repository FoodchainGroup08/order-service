package com.microservices.order.controller;

import com.microservices.order.dto.OrderDtos;
import com.microservices.order.entity.Order;
import com.microservices.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/v1/orders")
@Tag(name = "Orders", description = "Place new orders, track order status, view order history, and manage the order lifecycle through to completion or cancellation.")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Operation(
        summary = "Place a new order",
        description = "Creates a new order. The customer identity is resolved from the X-User-Id header set by the API gateway JWT filter — "
            + "do NOT pass customerId in the request body. "
            + "orderType accepts lowercase-hyphen format (\"dine-in\", \"takeaway\", \"delivery\") as well as the enum names. "
            + "Supply an Idempotency-Key header to safely retry without creating duplicate orders.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Order placed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request — missing required fields, empty items list, or unknown orderType"),
        @ApiResponse(responseCode = "409", description = "Duplicate request — order with this Idempotency-Key already exists")
    })
    @PostMapping
    public ResponseEntity<OrderDtos.FrontendOrderResponse> createOrder(
            @Valid @RequestBody OrderDtos.CreateOrderRequest request,
            @Parameter(description = "Customer UUID injected by the API gateway from the validated JWT token")
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(description = "Optional client-generated unique key used to safely retry the request without creating duplicate orders")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "X-User-Id header is required (set by the API gateway JWT filter)");
        }
        log.info("Create order request from user={}, branchId={}", userId, request.getBranchId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.createOrder(request, userId, idempotencyKey));
    }

    @Operation(
        summary = "Get order by ID",
        description = "Returns full details for a single order in the frontend-compatible response shape, "
            + "including the item breakdown, pricing, current status, and placement timestamp.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order details with item lines"),
        @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDtos.FrontendOrderResponse> getOrderById(
            @Parameter(description = "UUID of the order", required = true)
            @PathVariable String orderId) {
        log.info("Get order by id: {}", orderId);
        return ResponseEntity.ok(orderService.getFrontendOrderById(orderId));
    }

    @Operation(
        summary = "List active orders",
        description = "Returns a paginated list of orders that are currently in progress "
            + "(RECEIVED, CONFIRMED, PREPARING, READY). "
            + "Filter by customerId to show a specific customer's live orders, or by branchId to show all active orders at a branch.",
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
        description = "Returns a paginated list of terminal orders (PICKED_UP, SERVED, COMPLETED, CANCELLED), "
            + "sorted by most recent first.",
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
        description = "Transitions an order to a new status. "
            + "Valid values: RECEIVED → CONFIRMED | PREPARING | CANCELLED; "
            + "CONFIRMED → PREPARING | CANCELLED; "
            + "PREPARING → READY; "
            + "READY → PICKED_UP (takeaway/delivery) | SERVED (dine-in) | COMPLETED (legacy). "
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
            @Valid @RequestBody OrderDtos.UpdateStatusRequest request) {
        log.info("Update order status: orderId={}, newStatus={}", orderId, request.getNewStatus());

        Order.OrderStatus newStatus;
        try {
            newStatus = Order.OrderStatus.valueOf(request.getNewStatus().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown order status: " + request.getNewStatus()
                    + ". Valid values: RECEIVED, CONFIRMED, PREPARING, READY, PICKED_UP, SERVED, COMPLETED, CANCELLED");
        }

        return ResponseEntity.ok(orderService.updateOrderStatus(
                orderId, newStatus, request.getUpdatedBy(), request.getNotes()));
    }

    @Operation(
        summary = "Cancel an order",
        description = "Cancels the order and records who cancelled it and why. "
            + "Only orders in RECEIVED or CONFIRMED status can be cancelled — orders that are already being prepared cannot be reversed.",
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
