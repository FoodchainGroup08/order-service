package com.microservices.order.service;

import com.microservices.order.dto.OrderDtos;
import com.microservices.order.entity.Order;
import org.springframework.data.domain.Page;

public interface OrderService {

    /**
     * Creates a new order. customerId is extracted from the JWT gateway header (X-User-Id)
     * and passed in explicitly — it is not present in the request body.
     */
    OrderDtos.FrontendOrderResponse createOrder(OrderDtos.CreateOrderRequest request,
                                                String customerId,
                                                String idempotencyKey);

    OrderDtos.FrontendOrderResponse getFrontendOrderById(String orderId);

    OrderDtos.OrderDetailResponse getOrderById(String orderId);

    Page<OrderDtos.OrderListResponse> getActiveOrders(String customerId, String branchId, int page, int size);

    Page<OrderDtos.OrderListResponse> getOrderHistory(String customerId, String branchId, int page, int size);

    OrderDtos.OrderResponse updateOrderStatus(String orderId, Order.OrderStatus newStatus,
                                              String updatedBy, String notes);

    OrderDtos.OrderResponse cancelOrder(String orderId, String cancelledBy, String reason);
}
