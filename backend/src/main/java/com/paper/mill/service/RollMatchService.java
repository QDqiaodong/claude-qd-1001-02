package com.paper.mill.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.paper.mill.common.Dict;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.PaperRoll;

/**
 * 母卷对订单的「上机条件」裁决。
 *
 * <p>排产、已绑定订单的规格修改复核、领料三处共用同一套口径：
 * ① 母卷必须是「可用」；② 幅宽 ≥ 订单目标幅宽；③ 定量偏差 ≤ {@link Dict#GRAMMAGE_TOLERANCE}。
 * 只允许返回布尔值的接口容易在各调用点悄悄分叉（例如只查幅宽忘了查状态），
 * 因此这里统一收集所有不满足项，调用方要么直接拒绝、要么把原因原样展示，保证
 * 「母卷匹配结论」在排产、编辑保存、领料与刷新后的详情页上永远同口径。</p>
 */
@Service
public class RollMatchService {

    /** 母卷是否满足该订单当前规格的上机条件（可用 + 幅宽够 + 定量偏差合格）。 */
    public boolean isMachineUsable(PaperRoll roll, CuttingOrder order) {
        return problems(roll, order).isEmpty();
    }

    /** 收集全部不满足项；空列表表示满足。原因文案可直接回给操作员。 */
    public List<String> problems(PaperRoll roll, CuttingOrder order) {
        List<String> problems = new ArrayList<>(3);
        if (!Dict.isRollUsable(roll.getStatus())) {
            problems.add("母卷状态为「" + roll.getStatus() + "」，不可上机");
        }
        if (roll.getWidthMm() < order.getTargetWidthMm()) {
            problems.add("母卷幅宽 " + roll.getWidthMm() + "mm 小于订单目标幅宽 "
                    + order.getTargetWidthMm() + "mm");
        }
        if (Math.abs(roll.getGrammageGsm() - order.getTargetGrammage()) > Dict.GRAMMAGE_TOLERANCE) {
            problems.add("母卷定量 " + roll.getGrammageGsm() + "g 与订单目标 "
                    + order.getTargetGrammage() + "g 偏差超过 " + Dict.GRAMMAGE_TOLERANCE);
        }
        return problems;
    }

    public String joinProblems(List<String> problems) {
        return String.join("；", problems);
    }
}
