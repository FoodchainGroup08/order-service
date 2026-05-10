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

    private static final String USER_ID = "cust-uuid-001";

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
                        .header("X-User-Id", USER_ID)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.orderType").value("dine-in"))
                .andExpect(jsonPath("$.total").value(25.00))
                .andExpect(jsonPath("$.subtotal").value(25.00))
                .andExpect(jsonPath("$.deliveryFee").value(0.00))
                .andExpect(jsonPath("$.customerName").value("John"))
                .andExpect(jsonPath("$.phoneNumber").value("555-1234"))
                .andExpect(jsonPath("$.paymentMethod").value("card"))
                .andExpect(jsonPath("$.estimatedTime").isNotEmpty())
                .andExpect(jsonPath("$.placedAt").isNotEmpty());

        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);

        Order saved = orders.get(0);
        assertThat(saved.getCustomerId()).isEqualTo(USER_ID);
        assertThat(saved.getBranchId()).isEqualTo("branch-uuid-001");
        assertThat(saved.getOrderType()).isEqualTo(Order.OrderType.DINE_IN);
        assertThat(saved.getStatus()).isEqualTo(Order.OrderStatus.RECEIVED);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(saved.getTableNumber()).isEqualTo("5");
        assertThat(saved.getCustomerName()).isEqualTo("John");
        assertThat(saved.getPhoneNumber()).isEqualTo("555-1234");
        assertThat(saved.getPaymentMethod()).isEqualTo("card");
    }

    @Test
    void createOrder_withFrontendLowercaseOrderType_shouldParseDineIn() throws Exception {
        OrderDtos.CreateOrderRequest req = buildCreateRequest();
        // buildCreateRequest already uses "dine-in" — verify it parses correctly
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", USER_ID)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderType").value("dine-in"));
    }

    @Test
    void createOrder_deliveryOrder_shouldApplyDeliveryFee() throws Exception {
        OrderDtos.CreateOrderRequest req = new OrderDtos.CreateOrderRequest(
                "branch-uuid-001", null,
                List.of(new OrderDtos.OrderItemRequest("menu-1", "Pizza", 1, new BigDecimal("10.00"), null)),
                "delivery", "123 Main St", null, null,
                "John", "555-1234", "card", null);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", USER_ID)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryFee").value(2.00))
                .andExpect(jsonPath("$.total").value(12.00));
    }

    @Test
    void createOrder_missingXUserIdHeader_shouldReturn401() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createOrder_withIdempotencyKey_shouldPersistKeyToDatabase() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", USER_ID)
                        .header("Idempotency-Key", "idem-key-test-001")
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated());

        assertThat(orderRepository.findByIdempotencyKey("idem-key-test-001")).isPresent();
    }

    @Test
    void createOrder_withOptionalItemFields_shouldUseDefaults() throws Exception {
        // items without menuItemName or unitPrice — service should use defaults
        OrderDtos.CreateOrderRequest req = new OrderDtos.CreateOrderRequest(
                "branch-uuid-001", null,
                List.of(new OrderDtos.OrderItemRequest("menu-item-uuid-999", null, 1, null, null)),
                "dine-in", null, "3", null,
                "Jane", "555-0000", "cash", null);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", USER_ID)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].name").value("Item -999"))
                .andExpect(jsonPath("$.items[0].price").value(0.0));
    }

    // ── GET /orders/{id} ──────────────────────────────────────────────────────

    @Test
    void getOrderById_shouldReturn200WithFrontendShape() throws Exception {
        String createBody = mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", USER_ID)
                        .content(objectMapper.writeValueAsString(buildCreateRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(createBody).get("id").asText();

        mockMvc.perform(get("/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.orderType").value("dine-in"))
                .andExpect(jsonPath("$.total").value(25.00))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Jollof Rice"))
                .andExpect(jsonPath("$.items[1].name").value("Chicken"))
                .andExpect(jsonPath("$.customerName").value("John"))
                .andExpect(jsonPath("$.paymentMethod").value("card"))
                .andExpect(jsonPath("$.placedAt").isNotEmpty());
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
        saveOrderWithStatus(Order.OrderStatus.PICKED_UP);   // should NOT appear
        saveOrderWithStatus(Order.OrderStatus.SERVED);      // should NOT appear

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
    void getOrderHistory_shouldReturnPickedUpServedCompletedAndCancelled() throws Exception {
        saveOrderWithStatus(Order.OrderStatus.PICKED_UP);
        saveOrderWithStatus(Order.OrderStatus.SERVED);
        saveOrderWithStatus(Order.OrderStatus.COMPLETED);
        saveOrderWithStatus(Order.OrderStatus.CANCELLED);
        saveOrderWithStatus(Order.OrderStatus.RECEIVED);   // should NOT appear
        saveOrderWithStatus(Order.OrderStatus.PREPARING);  // should NOT appear

        mockMvc.perform(get("/orders/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
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
    void updateOrderStatus_toPickedUp_shouldSucceedFromReady() throws Exception {
        Order order = saveOrderWithStatus(Order.OrderStatus.READY);

        mockMvc.perform(put("/orders/" + order.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.UpdateStatusRequest("PICKED_UP", "staff-uuid-001", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED_UP"));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(Order.OrderStatus.PICKED_UP);
    }

    @Test
    void updateOrderStatus_toServed_shouldSucceedFromReady() throws Exception {
        Order order = saveOrderWithStatus(Order.OrderStatus.READY);

        mockMvc.perform(put("/orders/" + order.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.UpdateStatusRequest("SERVED", "staff-uuid-001", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SERVED"));
    }

    @Test
    void updateOrderStatus_fromPickedUp_shouldReturn400AsTerminal() throws Exception {
        Order order = saveOrderWithStatus(Order.OrderStatus.PICKED_UP);

        mockMvc.perform(put("/orders/" + order.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.UpdateStatusRequest("COMPLETED", "staff-uuid-001", null))))
                .andExpect(status().isBadRequest());
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
    void updateOrderStatus_withUnknownStatus_shouldReturn400() throws Exception {
        Order order = saveOrder();

        mockMvc.perform(put("/orders/" + order.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderDtos.UpdateStatusRequest("FLYING", "staff-uuid-001", null))))
                .andExpect(status().isBadRequest());
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
                "branch-uuid-001", "Main Branch",
                List.of(item1, item2), "dine-in", null, "5", null,
                "John", "555-1234", "card", null);
    }

    private Order saveOrder() {
        return orderRepository.save(Order.builder()
                .customerId(USER_ID)
                .branchId("branch-uuid-001")
                .orderType(Order.OrderType.DINE_IN)
                .totalAmount(new BigDecimal("25.00"))
                .build());
    }

    private Order saveOrderWithStatus(Order.OrderStatus status) {
        return orderRepository.save(Order.builder()
                .customerId(USER_ID)
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
                .customerId(USER_ID)
                .branchId(branchId)
                .orderType(Order.OrderType.DINE_IN)
                .totalAmount(new BigDecimal("25.00"))
                .status(status)
                .build());
    }
}
