package com.microservices.order.service;

import com.microservices.order.dto.OrderDtos;
import com.microservices.order.entity.Order;
import org.springframework.data.domain.Page;

public interface OrderService {

    OrderDtos.OrderResponse createOrder(OrderDtos.CreateOrderRequest request, String idempotencyKey);

    OrderDtos.OrderDetailResponse getOrderById(String orderId);

    Page<OrderDtos.OrderListResponse> getActiveOrders(String customerId, String branchId, int page, int size);

    Page<OrderDtos.OrderListResponse> getOrderHistory(String customerId, String branchId, int page, int size);

    OrderDtos.OrderResponse updateOrderStatus(String orderId, Order.OrderStatus newStatus,
                                              String updatedBy, String notes);

    OrderDtos.OrderResponse cancelOrder(String orderId, String cancelledBy, String reason);
}
