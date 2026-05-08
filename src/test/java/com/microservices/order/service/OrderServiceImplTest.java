package com.microservices.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.dto.OrderDtos;
import com.microservices.order.entity.Order;
import com.microservices.order.entity.OrderItem;
import com.microservices.order.entity.OrderStatusUpdate;
import com.microservices.order.entity.OutboxEvent;
import com.microservices.order.repository.OrderRepository;
import com.microservices.order.repository.OrderStatusUpdateRepository;
import com.microservices.order.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private OrderStatusUpdateRepository orderStatusUpdateRepository;
    @Mock private StatusTransitionValidator statusTransitionValidator;
    @Mock private RedisTemplate<String, String> redisTemplate;

    @SuppressWarnings("unchecked")
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private OrderServiceImpl orderService;

    // ── createOrder ───────────────────────────────────────────────────────────

    @Test
    void createOrder_shouldSaveOrderAndPublishOutboxEvent() {
        when(orderRepository.save(any(Order.class))).thenReturn(stubSavedOrder());

        OrderDtos.OrderResponse response = orderService.createOrder(buildCreateRequest(), null);

        assertThat(response.getOrderId()).isEqualTo("order-uuid-001");
        assertThat(response.getStatus()).isEqualTo("RECEIVED");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("25.00");

        verify(orderRepository).save(any(Order.class));
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void createOrder_withCachedIdempotencyKey_shouldReturnCachedResponseWithoutSaving() throws Exception {
        String cachedJson = new ObjectMapper().writeValueAsString(
                OrderDtos.OrderResponse.builder()
                        .orderId("order-cached-001")
                        .status("RECEIVED")
                        .totalAmount(new BigDecimal("25.00"))
                        .build());

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:idem-key-123")).thenReturn(cachedJson);

        OrderDtos.OrderResponse response = orderService.createOrder(buildCreateRequest(), "idem-key-123");

        assertThat(response.getOrderId()).isEqualTo("order-cached-001");
        verify(orderRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void createOrder_withNewIdempotencyKey_shouldSaveAndCacheResponse() {
        when(orderRepository.save(any(Order.class))).thenReturn(stubSavedOrder());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        OrderDtos.OrderResponse response = orderService.createOrder(buildCreateRequest(), "idem-key-new");

        assertThat(response.getOrderId()).isEqualTo("order-uuid-001");
        verify(valueOperations).set(eq("idempotency:idem-key-new"), anyString(), any());
    }

    // ── getOrderById ──────────────────────────────────────────────────────────

    @Test
    void getOrderById_shouldReturnDetailResponseWithItems() {
        Order order = stubSavedOrder();
        order.setItems(List.of(
                OrderItem.builder().menuItemId("item-1").menuItemName("Jollof Rice")
                        .quantity(2).unitPrice(new BigDecimal("10.00"))
                        .subtotal(new BigDecimal("20.00")).build(),
                OrderItem.builder().menuItemId("item-2").menuItemName("Chicken")
                        .quantity(1).unitPrice(new BigDecimal("5.00"))
                        .subtotal(new BigDecimal("5.00")).build()));
        when(orderRepository.findByIdWithItems("order-uuid-001")).thenReturn(Optional.of(order));

        OrderDtos.OrderDetailResponse response = orderService.getOrderById("order-uuid-001");

        assertThat(response.getOrderId()).isEqualTo("order-uuid-001");
        assertThat(response.getCustomerId()).isEqualTo("cust-uuid-001");
        assertThat(response.getOrderType()).isEqualTo("DINE_IN");
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getMenuItemName()).isEqualTo("Jollof Rice");
        assertThat(response.getItems().get(1).getMenuItemName()).isEqualTo("Chicken");
    }

    @Test
    void getOrderById_withNonExistentId_shouldThrow404() {
        when(orderRepository.findByIdWithItems("unknown-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderById("unknown-id"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ── getActiveOrders ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void getActiveOrders_shouldUseActiveStatusesAndReturnPage() {
        Page<Order> page = new PageImpl<>(List.of(stubSavedOrder()));
        when(orderRepository.findByStatusWithFilters(anyList(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        Page<OrderDtos.OrderListResponse> result = orderService.getActiveOrders(null, null, 0, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getOrderId()).isEqualTo("order-uuid-001");

        ArgumentCaptor<List<Order.OrderStatus>> statusCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderRepository).findByStatusWithFilters(statusCaptor.capture(), isNull(), isNull(), any());
        assertThat(statusCaptor.getValue()).containsExactlyInAnyOrder(
                Order.OrderStatus.RECEIVED, Order.OrderStatus.CONFIRMED,
                Order.OrderStatus.PREPARING, Order.OrderStatus.READY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getActiveOrders_filteredByCustomerId_shouldPassCustomerIdToRepository() {
        Page<Order> page = new PageImpl<>(List.of());
        when(orderRepository.findByStatusWithFilters(anyList(), eq("cust-A"), isNull(), any(Pageable.class)))
                .thenReturn(page);

        orderService.getActiveOrders("cust-A", null, 0, 10);

        verify(orderRepository).findByStatusWithFilters(anyList(), eq("cust-A"), isNull(), any());
    }

    // ── getOrderHistory ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void getOrderHistory_shouldUseHistoryStatusesAndReturnPage() {
        Page<Order> page = new PageImpl<>(List.of());
        when(orderRepository.findByStatusWithFilters(anyList(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        orderService.getOrderHistory(null, null, 0, 10);

        ArgumentCaptor<List<Order.OrderStatus>> statusCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderRepository).findByStatusWithFilters(statusCaptor.capture(), isNull(), isNull(), any());
        assertThat(statusCaptor.getValue()).containsExactlyInAnyOrder(
                Order.OrderStatus.COMPLETED, Order.OrderStatus.CANCELLED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getOrderHistory_shouldPassDescendingCreatedAtSort() {
        Page<Order> page = new PageImpl<>(List.of());
        when(orderRepository.findByStatusWithFilters(anyList(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        orderService.getOrderHistory(null, null, 0, 5);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByStatusWithFilters(anyList(), isNull(), isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    // ── updateOrderStatus ─────────────────────────────────────────────────────

    @Test
    void updateOrderStatus_shouldUpdateStatusAndCreateAuditRecord() {
        Order order = stubSavedOrder(); // RECEIVED
        when(orderRepository.findById("order-uuid-001")).thenReturn(Optional.of(order));
        when(statusTransitionValidator.isValidTransition(Order.OrderStatus.RECEIVED, Order.OrderStatus.CONFIRMED))
                .thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        OrderDtos.OrderResponse response = orderService.updateOrderStatus(
                "order-uuid-001", Order.OrderStatus.CONFIRMED, "staff-001", "Kitchen confirmed");

        assertThat(response.getStatus()).isEqualTo("CONFIRMED");

        ArgumentCaptor<OrderStatusUpdate> auditCaptor = ArgumentCaptor.forClass(OrderStatusUpdate.class);
        verify(orderStatusUpdateRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getOldStatus()).isEqualTo("RECEIVED");
        assertThat(auditCaptor.getValue().getNewStatus()).isEqualTo("CONFIRMED");
        assertThat(auditCaptor.getValue().getUpdatedBy()).isEqualTo("staff-001");
        assertThat(auditCaptor.getValue().getNotes()).isEqualTo("Kitchen confirmed");
    }

    @Test
    void updateOrderStatus_withInvalidTransition_shouldThrow400AndNotSave() {
        Order order = stubSavedOrder(); // RECEIVED
        when(orderRepository.findById("order-uuid-001")).thenReturn(Optional.of(order));
        when(statusTransitionValidator.isValidTransition(Order.OrderStatus.RECEIVED, Order.OrderStatus.COMPLETED))
                .thenReturn(false);
        when(statusTransitionValidator.getValidNextStates(Order.OrderStatus.RECEIVED))
                .thenReturn(Set.of(Order.OrderStatus.CONFIRMED, Order.OrderStatus.CANCELLED));

        assertThatThrownBy(() -> orderService.updateOrderStatus(
                "order-uuid-001", Order.OrderStatus.COMPLETED, "staff-001", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(orderRepository, never()).save(any());
        verify(orderStatusUpdateRepository, never()).save(any());
    }

    @Test
    void updateOrderStatus_toReady_shouldPublishTwoOutboxEvents() {
        Order order = Order.builder()
                .id("order-uuid-001").customerId("cust-uuid-001").branchId("branch-uuid-001")
                .orderType(Order.OrderType.DINE_IN).totalAmount(new BigDecimal("25.00"))
                .status(Order.OrderStatus.PREPARING).build();
        when(orderRepository.findById("order-uuid-001")).thenReturn(Optional.of(order));
        when(statusTransitionValidator.isValidTransition(Order.OrderStatus.PREPARING, Order.OrderStatus.READY))
                .thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        orderService.updateOrderStatus("order-uuid-001", Order.OrderStatus.READY, "staff-001", null);

        // publishes "order.status.updated" + "order.ready"
        verify(outboxEventRepository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    void updateOrderStatus_withOptimisticLockingException_shouldThrow409() {
        Order order = stubSavedOrder();
        when(orderRepository.findById("order-uuid-001")).thenReturn(Optional.of(order));
        when(statusTransitionValidator.isValidTransition(any(), any())).thenReturn(true);
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Order.class, "order-uuid-001"));

        assertThatThrownBy(() -> orderService.updateOrderStatus(
                "order-uuid-001", Order.OrderStatus.CONFIRMED, "staff-001", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void updateOrderStatus_withNonExistentOrder_shouldThrow404() {
        when(orderRepository.findById("ghost-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.updateOrderStatus(
                "ghost-id", Order.OrderStatus.CONFIRMED, "staff-001", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ── cancelOrder ───────────────────────────────────────────────────────────

    @Test
    void cancelOrder_shouldSetStatusToCancelledAndCreateAuditRecord() {
        Order order = stubSavedOrder(); // RECEIVED
        when(orderRepository.findById("order-uuid-001")).thenReturn(Optional.of(order));
        when(statusTransitionValidator.isValidTransition(Order.OrderStatus.RECEIVED, Order.OrderStatus.CANCELLED))
                .thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        OrderDtos.OrderResponse response = orderService.cancelOrder(
                "order-uuid-001", "cust-uuid-001", "Changed mind");

        assertThat(response.getStatus()).isEqualTo("CANCELLED");

        ArgumentCaptor<OrderStatusUpdate> auditCaptor = ArgumentCaptor.forClass(OrderStatusUpdate.class);
        verify(orderStatusUpdateRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getNewStatus()).isEqualTo("CANCELLED");
        assertThat(auditCaptor.getValue().getUpdatedBy()).isEqualTo("cust-uuid-001");
        assertThat(auditCaptor.getValue().getNotes()).isEqualTo("Changed mind");
    }

    @Test
    void cancelOrder_withNonExistentOrder_shouldThrow404() {
        when(orderRepository.findById("ghost-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancelOrder("ghost-id", "cust-001", "reason"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Order stubSavedOrder() {
        return Order.builder()
                .id("order-uuid-001")
                .customerId("cust-uuid-001")
                .branchId("branch-uuid-001")
                .orderType(Order.OrderType.DINE_IN)
                .totalAmount(new BigDecimal("25.00"))
                .status(Order.OrderStatus.RECEIVED)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private OrderDtos.CreateOrderRequest buildCreateRequest() {
        var item1 = new OrderDtos.OrderItemRequest(
                "menu-item-001", "Jollof Rice", 2, new BigDecimal("10.00"), null);
        var item2 = new OrderDtos.OrderItemRequest(
                "menu-item-002", "Chicken", 1, new BigDecimal("5.00"), "Extra crispy");
        return new OrderDtos.CreateOrderRequest(
                "cust-uuid-001", "branch-uuid-001", List.of(item1, item2), "DINE_IN", null, "5", null);
    }
}
