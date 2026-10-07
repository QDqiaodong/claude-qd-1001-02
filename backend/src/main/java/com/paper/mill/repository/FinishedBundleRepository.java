package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.FinishedBundle;

public interface FinishedBundleRepository extends JpaRepository<FinishedBundle, Long> {

    Optional<FinishedBundle> findByBundleNo(String bundleNo);

    List<FinishedBundle> findByOrderId(Long orderId);

    /** 订单详情追溯：按入库时间倒序看本订单的全部成品件 */
    List<FinishedBundle> findByOrderIdOrderByStoredAtDesc(Long orderId);

    List<FinishedBundle> findByStatus(String status);

    List<FinishedBundle> findByWarehouseZone(String zone);

    List<FinishedBundle> findByWarehouseZoneAndStatus(String zone, String status);
}
