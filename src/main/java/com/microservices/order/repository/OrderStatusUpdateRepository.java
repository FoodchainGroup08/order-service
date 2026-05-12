package com.microservices.order.repository;

import com.microservices.order.entity.OrderStatusUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderStatusUpdateRepository extends JpaRepository<OrderStatusUpdate, String> {

    List<OrderStatusUpdate> findByOrder_IdOrderByTimestampAsc(String orderId);

    Optional<OrderStatusUpdate> findTopByOrder_IdOrderByTimestampDesc(String orderId);
}
