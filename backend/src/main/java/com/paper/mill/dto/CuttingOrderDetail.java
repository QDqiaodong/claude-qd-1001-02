package com.paper.mill.dto;

import java.math.BigDecimal;
import java.util.List;

import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.CuttingRequisition;
import com.paper.mill.entity.FinishedBundle;
import com.paper.mill.entity.RequisitionAttempt;

/**
 * 订单详情快照：订单、母卷校验、欠量/领料/成品追溯在同一个只读事务内取数。
 */
public record CuttingOrderDetail(
        CuttingOrder order,
        AssignedRollCheck assignedRoll,
        List<RollMatch> rolls,
        BigDecimal requisitionedWeightKg,
        Long requisitionedLengthM,
        List<CuttingRequisition> requisitions,
        List<RequisitionAttempt> requisitionAttempts,
        List<FinishedBundle> finishedBundles
) {

    public record AssignedRollCheck(
            Long rollId,
            String rollNo,
            boolean ok,
            boolean requirable,
            String status,
            Integer widthGapMm,
            Integer grammageGap,
            Integer remainLengthM,
            BigDecimal remainWeightKg,
            Integer maxTakeLengthM,
            BigDecimal maxTakeWeightKg,
            List<String> problems
    ) {
    }

    public record RollMatch(
            Long rollId,
            String rollNo,
            boolean usable,
            boolean match,
            boolean requirable,
            String status,
            Integer grammageGsm,
            Integer widthMm,
            Integer remainLengthM,
            BigDecimal remainWeightKg,
            Integer widthGapMm,
            Integer grammageGap,
            Integer maxTakeLengthM,
            BigDecimal maxTakeWeightKg,
            List<String> problems
    ) {
    }
}
