package com.microservices.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.dto.OrderDtos;
import com.microservices.order.entity.Order;
import com.microservices.order.repository.OrderRepository;
import com.microservices.order.repository.OrderStatusUpdateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusUpdateRepository orderStatusUpdateRepository;

    @MockBean
    private RedisTemplate<String, String> redisTemplate;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        orderStatusUpdateRepository.deleteAll();
        orderRepository.deleteAll();

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());
    }

    // ── POST /orders ──────────────────────────────────────────────────────────

    @Test
    void createOrder_shouldReturn201AndPersistOrderToDatabase() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.totalAmount").value(25.00));

        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);

        Order saved = orders.get(0);
        assertThat(saved.getCustomerId()).isEqualTo("cust-uuid-001");
        assertThat(saved.getBranchId()).isEqualTo("branch-uuid-001");
        assertThat(saved.getOrderType()).isEqualTo(Order.OrderType.DINE_IN);
        assertThat(saved.getStatus()).isEqualTo(Order.OrderStatus.RECEIVED);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(saved.getTableNumber()).isEqualTo("5");
    }

    @Test
    void createOrder_withIdempotencyKey_shouldPersistKeyToDatabase() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-key-test-001")
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated());

        assertThat(orderRepository.findByIdempotencyKey("idem-key-test-001")).isPresent();
    }

    // ── GET /orders/{id} ──────────────────────────────────────────────────────

    @Test
    void getOrderById_shouldReturn200WithOrderDetailAndItems() throws Exception {
        String createBody = mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(createBody).get("orderId").asText();

        mockMvc.perform(get("/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.customerId").value("cust-uuid-001"))
                .andExpect(jsonPath("$.orderType").value("DINE_IN"))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].menuItemName").value("Jollof Rice"))
                .andExpect(jsonPath("$.items[1].menuItemName").value("Chicken"));
    }

    @Test
    void getOrderById_withNonExistentId_shouldReturn404() throws Exception {
        mockMvc.perform(get("/orders/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    // ── GET /orders/active ────────────────────────────────────────────────────

    @Test
    void getActiveOrders_shouldReturnOnlyActiveOrders() throws Exception {
        saveOrderWithStatus(Order.OrderStatus.RECEIVED);
        saveOrderWithStatus(Order.OrderStatus.CONFIRMED);
        saveOrderWithStatus(Order.OrderStatus.PREPARING);
        saveOrderWithStatus(Order.OrderStatus.COMPLETED);   // should NOT appear
        saveOrderWithStatus(Order.OrderStatus.CANCELLED);   // should NOT appear

        mockMvc.perform(get("/orders/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void getActiveOrders_filteredByCustomerId_shouldReturnOnlyMatchingOrders() throws Exception {
        saveOrderWithStatusAndCustomer(Order.OrderStatus.RECEIVED, "cust-A");
        saveOrderWithStatusAndCustomer(Order.OrderStatus.RECEIVED, "cust-A");
        saveOrderWithStatusAndCustomer(Order.OrderStatus.RECEIVED, "cust-B");

        mockMvc.perform(get("/orders/active").param("customerId", "cust-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].customerId").value("cust-A"));
    }

    @Test
    void getActiveOrders_shouldRespectPaginationParameters() throws Exception {
        for (int i = 0; i < 5; i++) {
            saveOrderWithStatus(Order.OrderStatus.RECEIVED);
        }

        mockMvc.perform(get("/orders/active").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    // ── GET /orders/history ───────────────────────────────────────────────────

    @Test
    void getOrderHistory_shouldReturnOnlyCompletedAndCancelledOrders() throws Exception {
        saveOrderWithStatus(Order.OrderStatus.COMPLETED);
        saveOrderWithStatus(Order.OrderStatus.CANCELLED);
        saveOrderWithStatus(Order.OrderStatus.RECEIVED);   // should NOT appear
        saveOrderWithStatus(Order.OrderStatus.PREPARING);  // should NOT appear

        mockMvc.perform(get("/orders/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getOrderHistory_filteredByBranchId_shouldReturnOnlyMatchingOrders() throws Exception {
        saveOrderWithStatusAndBranch(Order.OrderStatus.COMPLETED, "branch-A");
        saveOrderWithStatusAndBranch(Order.OrderStatus.COMPLETED, "branch-B");

        mockMvc.perform(get("/orders/history").param("branchId", "branch-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].branchId").value("branch-A"));
    }

    // ── PUT /orders/{id}/status ───────────────────────────────────────────────

    @Test
    void updateOrderStatus_shouldReturn200AndUpdateStatusInDatabase() throws Exception {
        Order order = saveOrder();

        mockMvc.perform(put("/orders/" + order.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.UpdateStatusRequest("CONFIRMED", "staff-uuid-001", "Kitchen confirmed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(Order.OrderStatus.CONFIRMED);
    }

    @Test
    void updateOrderStatus_shouldPersistAuditRecordToDatabase() throws Exception {
        Order order = saveOrder();

        mockMvc.perform(put("/orders/" + order.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.UpdateStatusRequest("CONFIRMED", "staff-uuid-001", "Audit note"))))
                .andExpect(status().isOk());

        var audits = orderStatusUpdateRepository.findByOrder_IdOrderByTimestampAsc(order.getId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getOldStatus()).isEqualTo("RECEIVED");
        assertThat(audits.get(0).getNewStatus()).isEqualTo("CONFIRMED");
        assertThat(audits.get(0).getUpdatedBy()).isEqualTo("staff-uuid-001");
        assertThat(audits.get(0).getNotes()).isEqualTo("Audit note");
    }

    @Test
    void updateOrderStatus_withInvalidTransition_shouldReturn400AndLeaveDatabaseUnchanged() throws Exception {
        Order order = saveOrder();

        mockMvc.perform(put("/orders/" + order.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.UpdateStatusRequest("COMPLETED", "staff-uuid-001", null))))
                .andExpect(status().isBadRequest());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(Order.OrderStatus.RECEIVED);
        assertThat(orderStatusUpdateRepository.findAll()).isEmpty();
    }

    @Test
    void updateOrderStatus_withNonExistentOrder_shouldReturn404() throws Exception {
        mockMvc.perform(put("/orders/non-existent-id/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.UpdateStatusRequest("CONFIRMED", "staff-uuid-001", null))))
                .andExpect(status().isNotFound());
    }

    // ── POST /orders/{id}/cancel ──────────────────────────────────────────────

    @Test
    void cancelOrder_shouldReturn200AndPersistCancelledStatusToDatabase() throws Exception {
        Order order = saveOrder(); // starts as RECEIVED

        mockMvc.perform(post("/orders/" + order.getId() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.CancelOrderRequest("cust-uuid-001", "Changed mind"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(Order.OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrder_shouldPersistCancelAuditRecord() throws Exception {
        Order order = saveOrder();

        mockMvc.perform(post("/orders/" + order.getId() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.CancelOrderRequest("cust-uuid-001", "No longer needed"))))
                .andExpect(status().isOk());

        var audits = orderStatusUpdateRepository.findByOrder_IdOrderByTimestampAsc(order.getId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getNewStatus()).isEqualTo("CANCELLED");
        assertThat(audits.get(0).getUpdatedBy()).isEqualTo("cust-uuid-001");
        assertThat(audits.get(0).getNotes()).isEqualTo("No longer needed");
    }

    @Test
    void cancelOrder_fromPreparingStatus_shouldReturn400() throws Exception {
        Order order = saveOrderWithStatus(Order.OrderStatus.PREPARING);

        mockMvc.perform(post("/orders/" + order.getId() + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.CancelOrderRequest("cust-uuid-001", "Changed mind"))))
                .andExpect(status().isBadRequest());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(Order.OrderStatus.PREPARING);
    }

    @Test
    void cancelOrder_withNonExistentOrder_shouldReturn404() throws Exception {
        mockMvc.perform(post("/orders/non-existent-id/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.CancelOrderRequest("cust-uuid-001", "reason"))))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrderDtos.CreateOrderRequest buildCreateRequest() {
        var item1 = new OrderDtos.OrderItemRequest(
                "menu-item-uuid-001", "Jollof Rice", 2, new BigDecimal("10.00"), null);
        var item2 = new OrderDtos.OrderItemRequest(
                "menu-item-uuid-002", "Chicken", 1, new BigDecimal("5.00"), "Extra crispy");
        return new OrderDtos.CreateOrderRequest(
                "cust-uuid-001", "branch-uuid-001",
                List.of(item1, item2), "DINE_IN", null, "5", null);
    }

    private Order saveOrder() {
        return orderRepository.save(Order.builder()
                .customerId("cust-uuid-001")
                .branchId("branch-uuid-001")
                .orderType(Order.OrderType.DINE_IN)
                .totalAmount(new BigDecimal("25.00"))
                .build());
    }

    private Order saveOrderWithStatus(Order.OrderStatus status) {
        return orderRepository.save(Order.builder()
                .customerId("cust-uuid-001")
                .branchId("branch-uuid-001")
                .orderType(Order.OrderType.DINE_IN)
                .totalAmount(new BigDecimal("25.00"))
                .status(status)
                .build());
    }

    private Order saveOrderWithStatusAndCustomer(Order.OrderStatus status, String customerId) {
        return orderRepository.save(Order.builder()
                .customerId(customerId)
                .branchId("branch-uuid-001")
                .orderType(Order.OrderType.DINE_IN)
                .totalAmount(new BigDecimal("25.00"))
                .status(status)
                .build());
    }

    private Order saveOrderWithStatusAndBranch(Order.OrderStatus status, String branchId) {
        return orderRepository.save(Order.builder()
                .customerId("cust-uuid-001")
                .branchId(branchId)
                .orderType(Order.OrderType.DINE_IN)
                .totalAmount(new BigDecimal("25.00"))
                .status(status)
                .build());
    }
}
