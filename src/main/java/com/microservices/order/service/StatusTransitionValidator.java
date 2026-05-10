package com.microservices.order.service;

import com.microservices.order.entity.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class StatusTransitionValidator {

    private final Map<Order.OrderStatus, Set<Order.OrderStatus>> allowedTransitions;

    public StatusTransitionValidator() {
        allowedTransitions = new HashMap<>();

        // PAYMENT_PENDING — only cancel via API; CONFIRMED is applied internally after Paystack webhook
        allowedTransitions.put(Order.OrderStatus.PAYMENT_PENDING,
                Set.of(Order.OrderStatus.CANCELLED));

        // RECEIVED can go to CONFIRMED, PREPARING (kitchen accepts directly), or CANCELLED
        allowedTransitions.put(Order.OrderStatus.RECEIVED,
                Set.of(Order.OrderStatus.CONFIRMED, Order.OrderStatus.PREPARING, Order.OrderStatus.CANCELLED));

        // CONFIRMED can go to PREPARING or CANCELLED
        allowedTransitions.put(Order.OrderStatus.CONFIRMED,
                Set.of(Order.OrderStatus.PREPARING, Order.OrderStatus.CANCELLED));

        // PREPARING can go to READY
        allowedTransitions.put(Order.OrderStatus.PREPARING,
                Set.of(Order.OrderStatus.READY));

        // READY can go to PICKED_UP (takeaway/delivery), SERVED (dine-in), or legacy COMPLETED
        allowedTransitions.put(Order.OrderStatus.READY,
                Set.of(Order.OrderStatus.PICKED_UP, Order.OrderStatus.SERVED, Order.OrderStatus.COMPLETED));

        // PICKED_UP and SERVED are terminal states (new flow)
        allowedTransitions.put(Order.OrderStatus.PICKED_UP, new HashSet<>());
        allowedTransitions.put(Order.OrderStatus.SERVED, new HashSet<>());

        // COMPLETED and CANCELLED are terminal states (legacy / backward compat)
        allowedTransitions.put(Order.OrderStatus.COMPLETED, new HashSet<>());
        allowedTransitions.put(Order.OrderStatus.CANCELLED, new HashSet<>());
    }

    public boolean isValidTransition(Order.OrderStatus from, Order.OrderStatus to) {
        Set<Order.OrderStatus> validNextStates = allowedTransitions.get(from);
        return validNextStates != null && validNextStates.contains(to);
    }

    public Set<Order.OrderStatus> getValidNextStates(Order.OrderStatus currentStatus) {
        return allowedTransitions.getOrDefault(currentStatus, new HashSet<>());
    }
}
