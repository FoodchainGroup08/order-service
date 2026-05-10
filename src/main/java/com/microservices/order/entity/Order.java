package com.microservices.order.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_branch_id", columnList = "branch_id"),
        @Index(name = "idx_customer_id", columnList = "customer_id"),
        @Index(name = "idx_branch_status", columnList = "branch_id, status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "branch_id", nullable = false, columnDefinition = "CHAR(36)")
    private String branchId;

    @Column(name = "customer_id", nullable = false, columnDefinition = "CHAR(36)")
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "table_number", length = 10)
    private String tableNumber;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderItem> items;

    @Column(name = "customer_name", length = 255)
    private String customerName;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "special_instructions", columnDefinition = "TEXT")
    private String specialInstructions;

    @Column(name = "delivery_fee", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(name = "branch_name", length = 255)
    private String branchName;

    @Column(name = "estimated_time", length = 100)
    private String estimatedTime;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = OrderStatus.RECEIVED;
        }
        if (this.deliveryFee == null) {
            this.deliveryFee = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum OrderStatus {
        RECEIVED, CONFIRMED, PREPARING, READY, PICKED_UP, SERVED, COMPLETED, CANCELLED
    }

    public enum OrderType {
        DINE_IN, TAKEAWAY, DELIVERY
    }
}
