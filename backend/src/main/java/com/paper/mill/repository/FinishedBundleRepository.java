package com.paper.mill.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.paper.mill.entity.FinishedBundle;

public interface FinishedBundleRepository extends JpaRepository<FinishedBundle, Long> {

    Optional<FinishedBundle> findByBundleNo(String bundleNo);

    List<FinishedBundle> findByOrderId(Long orderId);

    List<FinishedBundle> findByStatus(String status);

    List<FinishedBundle> findByWarehouseZone(String zone);

    List<FinishedBundle> findByWarehouseZoneAndStatus(String zone, String status);

    /** 已发生的成品入库重量（含后来已出库的成品），是订单已入库量的权威口径。 */
    @Query("select coalesce(sum(b.weightKg), 0) from FinishedBundle b where b.order.id = :orderId")
    BigDecimal sumWeightByOrderId(@Param("orderId") Long orderId);
}
