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
        private String menuItemName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private String specialInstructions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateOrderRequest {
        @NotBlank
        private String branchId;
        private String branchName;
        @NotEmpty
        private List<OrderItemRequest> items;
        @NotBlank
        private String orderType;
        private String deliveryAddress;
        private String tableNumber;
        private String notes;
        private String customerName;
        private String customerEmail;
        private String phoneNumber;
        private String paymentMethod;
        private String specialInstructions;

        public CreateOrderRequest(String branchId, String branchName, List<OrderItemRequest> items,
                                  String orderType, String deliveryAddress, String tableNumber,
                                  String notes, String customerName, String phoneNumber,
                                  String paymentMethod, String specialInstructions) {
            this.branchId = branchId;
            this.branchName = branchName;
            this.items = items;
            this.orderType = orderType;
            this.deliveryAddress = deliveryAddress;
            this.tableNumber = tableNumber;
            this.notes = notes;
            this.customerName = customerName;
            this.phoneNumber = phoneNumber;
            this.paymentMethod = paymentMethod;
            this.specialInstructions = specialInstructions;
        }
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FrontendOrderItemResponse {
        private String id;
        private String name;
        private double price;
        private int quantity;
    }

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
        private String orderType;
        private String tableNumber;
        private String deliveryAddress;
        private String customerName;
        private String customerEmail;
        private String phoneNumber;
        private String specialInstructions;
        private String estimatedTime;
        private String placedAt;
        private String paymentMethod;
    }
}
