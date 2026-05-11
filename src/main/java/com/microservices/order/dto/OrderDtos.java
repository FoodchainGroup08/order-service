package com.microservices.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class OrderDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemRequest {
        private String menuItemId;
        private String menuItemName;      // optional — can be null
        private Integer quantity;
        private BigDecimal unitPrice;     // optional — can be null, defaults to 0.00
        private String specialInstructions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateOrderRequest {
        @NotBlank
        private String branchId;
        private String branchName;        // optional
        @NotEmpty
        private List<OrderItemRequest> items;
        @NotBlank
        private String orderType;         // accepts "delivery", "dine-in", "takeaway" OR "DELIVERY", "DINE_IN", "TAKEAWAY"
        private String deliveryAddress;
        private String tableNumber;
        private String notes;
        private String customerName;
        private String phoneNumber;
        private String paymentMethod;
        private String specialInstructions;
        // customerId is NOT here — it is extracted from the X-User-Id JWT header by the controller
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateStatusRequest {
        @NotBlank
        private String newStatus;
        private String updatedBy;
        private String notes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CancelOrderRequest {
        private String cancelledBy;
        private String reason;
    }

    // ── Responses ─────────────────────────────────────────────────────────────

    /** Slim response returned for status-update and cancel operations. Kept for backward compat. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderResponse {
        private String orderId;
        private String status;
        private BigDecimal totalAmount;
        private LocalDateTime createdAt;
    }

    /** List-entry response used by GET /orders/active and GET /orders/history. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderListResponse {
        private String orderId;
        private String customerId;
        private String branchId;
        private String orderType;
        private String status;
        private String tableNumber;
        private BigDecimal totalAmount;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** Item detail included in OrderDetailResponse. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderItemResponse {
        private String id;
        private String menuItemId;
        private String menuItemName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
        private String specialInstructions;
    }

    /** Full order detail including item lines. Kept for backward compat. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderDetailResponse {
        private String orderId;
        private String customerId;
        private String branchId;
        private String orderType;
        private String status;
        private String tableNumber;
        private String deliveryAddress;
        private BigDecimal totalAmount;
        private String notes;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<OrderItemResponse> items;
    }

    // ── Frontend-facing responses ──────────────────────────────────────────────

    /** Item shape expected by the frontend. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FrontendOrderItemResponse {
        private String id;
        private String name;      // menuItemName
        private double price;     // unitPrice as double
        private int quantity;
    }

    /**
     * Full order response shaped exactly as the frontend expects.
     * Returned by POST /orders and GET /orders/{id}.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FrontendOrderResponse {
        private String id;
        private String status;
        private List<FrontendOrderItemResponse> items;
        private BigDecimal subtotal;
        private BigDecimal deliveryFee;
        private BigDecimal total;
        private String branchId;
        private String branchName;
        private String orderType;           // lowercase with hyphens: "dine-in", "takeaway", "delivery"
        private String tableNumber;
        private String deliveryAddress;
        private String customerName;
        private String phoneNumber;
        private String specialInstructions;
        private String estimatedTime;
        private String placedAt;            // ISO-8601 string from createdAt
        private String paymentMethod;
    }
}
