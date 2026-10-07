package com.paper.mill.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.paper.mill.entity.CuttingRequisition;

public interface CuttingRequisitionRepository extends JpaRepository<CuttingRequisition, Long> {

    /** 订单详情追溯：按操作时间倒序看本订单的每一次领料 */
    List<CuttingRequisition> findByOrderIdOrderByOperatedAtDesc(Long orderId);

    /** 母卷导轨追溯：按操作时间倒序看本卷被领走过哪些料 */
    List<CuttingRequisition> findByRollIdOrderByOperatedAtDesc(Long rollId);

    @Query("select coalesce(sum(r.weightKg), 0) from CuttingRequisition r where r.order.id = :orderId")
    BigDecimal sumWeightByOrderId(@Param("orderId") Long orderId);

    @Query("select coalesce(sum(r.lengthM), 0) from CuttingRequisition r where r.order.id = :orderId")
    Long sumLengthByOrderId(@Param("orderId") Long orderId);
}
