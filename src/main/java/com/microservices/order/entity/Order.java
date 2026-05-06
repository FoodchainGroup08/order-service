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
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private Long branchId;

    @Column(unique = true)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderType orderType;

    private String deliveryAddress;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime readyAt;

    private LocalDateTime completedAt;

    @Version
    private Long version; // For optimistic locking

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderItem> items;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.status = OrderStatus.RECEIVED;
    }

    public enum OrderStatus {
        RECEIVED, CONFIRMED, PREPARING, READY, COMPLETED, CANCELLED
    }

    public enum OrderType {
        DINE_IN, TAKEAWAY, DELIVERY
    }
}
