package com.harucut.payment.repository;

import com.harucut.payment.entity.PaymentOrder;
import com.harucut.payment.enums.OrderStatus;
import com.harucut.payment.enums.OrderType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);

    Page<PaymentOrder> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    boolean existsByIdempotencyKey(String idempotencyKey);

    // 결과 미확정(만듦/긁는 중) 갱신 주문이 남아 있나 — 있으면 새 청구를 만들지 않는다
    boolean existsByUserIdAndOrderTypeAndStatusIn(Long userId, OrderType orderType, Collection<OrderStatus> statuses);

    // 청구 직전 재확인·스토어 전환 시 취소 — 잠금 순서는 늘 구독 행 → 주문 행(ADR-0011 §2)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.id = :id")
    Optional<PaymentOrder> lockById(@Param("id") Long id);

    @Query("select o.id from PaymentOrder o where o.userId = :userId and o.orderType = :orderType and o.status = :status")
    List<Long> findIdsByUserIdAndOrderTypeAndStatus(@Param("userId") Long userId, @Param("orderType") OrderType orderType,
                                                    @Param("status") OrderStatus status);
}
