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

        // RECEIVED can go to CONFIRMED, PREPARING (kitchen accepts directly), or CANCELLED
        allowedTransitions.put(Order.OrderStatus.RECEIVED,
                Set.of(Order.OrderStatus.CONFIRMED, Order.OrderStatus.PREPARING, Order.OrderStatus.CANCELLED));

        // CONFIRMED can go to PREPARING or CANCELLED
        allowedTransitions.put(Order.OrderStatus.CONFIRMED,
                Set.of(Order.OrderStatus.PREPARING, Order.OrderStatus.CANCELLED));

        // PREPARING can go to READY
        allowedTransitions.put(Order.OrderStatus.PREPARING,
                Set.of(Order.OrderStatus.READY));

        // READY can go to COMPLETED
        allowedTransitions.put(Order.OrderStatus.READY,
                Set.of(Order.OrderStatus.COMPLETED));

        // COMPLETED and CANCELLED are terminal states
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
