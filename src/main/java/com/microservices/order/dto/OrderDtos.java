package com.microservices.order.dto;

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
        private Long menuItemId;
        private String itemName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private String specialInstructions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateOrderRequest {
        private Long customerId;
        private Long branchId;
        private List<OrderItemRequest> items;
        private String orderType; // DINE_IN, TAKEAWAY, DELIVERY
        private String deliveryAddress;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderResponse {
        private Long orderId;
        private String orderNumber;
        private String status;
        private BigDecimal totalAmount;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateStatusRequest {
        private String newStatus;
    }
}
