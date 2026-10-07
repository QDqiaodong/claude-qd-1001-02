package com.paper.mill.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

/**
 * 切纸订单修改请求。
 *
 * <p>字段为 null 表示「本字段不改」（PATCH 语义），因此定量/幅宽/重量用包装类型。
 * {@code expectedVersion} 必填：前端打开编辑框时订单是什么版本，提交就带什么版本；
 * 期间订单被别人改过（排产、领料外的任何落库动作都会推进版本），后端按 409 冲突拒绝
 * 并返回当前版本与冲突原因，避免后提交方在旧口径上覆盖新规格。</p>
 */
@Getter
@Setter
public class CuttingOrderUpdateRequest {

    /** 编辑所依据的订单版本，必填 */
    private Long expectedVersion;

    private String customerName;

    /** 目标定量（g/m2） */
    private Integer targetGrammage;

    /** 目标幅宽（毫米） */
    private Integer targetWidthMm;

    /** 目标重量（千克） */
    private BigDecimal targetWeightKg;

    private LocalDate deadline;

    /** 交期是否显式传值（区分「不改交期」与「清空交期」） */
    private boolean deadlineTouched;
}
