package com.microservices.order.repository;

import com.microservices.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") String id);

    @Query(value = "SELECT o FROM Order o WHERE o.status IN :statuses " +
                   "AND (:customerId IS NULL OR o.customerId = :customerId) " +
                   "AND (:branchId IS NULL OR o.branchId = :branchId)",
           countQuery = "SELECT COUNT(o) FROM Order o WHERE o.status IN :statuses " +
                        "AND (:customerId IS NULL OR o.customerId = :customerId) " +
                        "AND (:branchId IS NULL OR o.branchId = :branchId)")
    Page<Order> findByStatusWithFilters(
            @Param("statuses") Collection<Order.OrderStatus> statuses,
            @Param("customerId") String customerId,
            @Param("branchId") String branchId,
            Pageable pageable);
}
