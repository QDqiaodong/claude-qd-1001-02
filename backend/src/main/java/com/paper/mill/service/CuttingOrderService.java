package com.paper.mill.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.common.VersionConflictException;
import com.paper.mill.dto.CuttingOrderDetail;
import com.paper.mill.dto.CuttingOrderDetail.AssignedRollCheck;
import com.paper.mill.dto.CuttingOrderDetail.RollMatch;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.CuttingRequisition;
import com.paper.mill.entity.FinishedBundle;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.RequisitionAttempt;
import com.paper.mill.repository.CuttingOrderRepository;
import com.paper.mill.repository.CuttingRequisitionRepository;
import com.paper.mill.repository.FinishedBundleRepository;
import com.paper.mill.repository.PaperRollRepository;
import com.paper.mill.repository.RequisitionAttemptRepository;

/**
 * 切纸工段 · 切纸订单业务。
 *
 * <p>核心规则：① 上机母卷幅宽必须 ≥ 订单目标幅宽；② 母卷定量与订单目标定量的偏差
 * 不得超过 {@link Dict#GRAMMAGE_TOLERANCE}；③ 母卷必须是「可用」状态；
 * ④ 状态只能逐级推进：待排产 → 切裁中 → 已完成；⑤ 已绑定母卷后修改规格，
 * 必须在订单行锁和母卷行锁内重新校验，已发生领料和成品追溯不回写、不删除。</p>
 */
@Service
public class CuttingOrderService {

    private final CuttingOrderRepository orderRepository;
    private final PaperRollRepository rollRepository;
    private final CuttingRequisitionRepository requisitionRepository;
    private final RequisitionAttemptRepository attemptRepository;
    private final FinishedBundleRepository bundleRepository;

    public CuttingOrderService(CuttingOrderRepository orderRepository,
                               PaperRollRepository rollRepository,
                               CuttingRequisitionRepository requisitionRepository,
                               RequisitionAttemptRepository attemptRepository,
                               FinishedBundleRepository bundleRepository) {
        this.orderRepository = orderRepository;
        this.rollRepository = rollRepository;
        this.requisitionRepository = requisitionRepository;
        this.attemptRepository = attemptRepository;
        this.bundleRepository = bundleRepository;
    }

    public List<CuttingOrder> list(String status, String customerName) {
        if (status != null && !status.isBlank()) {
            return orderRepository.findByStatus(status);
        }
        if (customerName != null && !customerName.isBlank()) {
            return orderRepository.findByCustomerName(customerName);
        }
        return orderRepository.findAll();
    }

    public CuttingOrder get(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BizException("切纸订单不存在：" + id));
    }

    /**
     * 订单详情取一个只读事务快照：订单、绑定母卷结论、候选母卷、领料汇总/账本和成品入库记录
     * 均来自同一份已提交状态，避免页面多个接口分别读到规格修改前后的混合数据。
     */
    @Transactional(readOnly = true)
    public CuttingOrderDetail detail(Long id) {
        CuttingOrder order = get(id);
        List<PaperRoll> rolls = rollRepository.findAll();
        List<CuttingRequisition> requisitions =
                requisitionRepository.findByOrderIdOrderByOperatedAtDesc(id);
        List<RequisitionAttempt> attempts =
                attemptRepository.findByOrderIdOrderByAttemptedAtDesc(id);
        List<FinishedBundle> bundles = bundleRepository.findByOrderId(id);

        List<RollMatch> matches = new ArrayList<>();
        Long assignedRollId = order.getAssignedRoll() == null ? null : order.getAssignedRoll().getId();
        for (PaperRoll roll : rolls) {
            boolean isAssigned = assignedRollId != null && assignedRollId.equals(roll.getId());
            RollCheck check = check(order, roll, isAssigned, isAssigned);
            matches.add(new RollMatch(
                    roll.getId(), roll.getRollNo(), Dict.isRollUsable(roll.getStatus()),
                    check.ok(), check.requirable(), roll.getStatus(), roll.getGrammageGsm(),
                    roll.getWidthMm(), roll.getLengthM(), roll.getWeightKg(),
                    check.widthGap(), check.grammageGap(),
                    check.requirable() ? roll.getLengthM() : 0,
                    check.requirable() ? roll.getWeightKg() : BigDecimal.ZERO,
                    check.problems()));
        }

        AssignedRollCheck assigned = null;
        if (order.getAssignedRoll() != null) {
            PaperRoll roll = order.getAssignedRoll();
            RollCheck check = check(order, roll, true, true);
            assigned = new AssignedRollCheck(
                    roll.getId(), roll.getRollNo(), check.ok(), check.requirable(),
                    roll.getStatus(), check.widthGap(), check.grammageGap(),
                    roll.getLengthM(), roll.getWeightKg(),
                    check.requirable() ? roll.getLengthM() : 0,
                    check.requirable() ? roll.getWeightKg() : BigDecimal.ZERO,
                    check.problems());
        }

        return new CuttingOrderDetail(order, assigned, matches,
                requisitionRepository.sumWeightByOrderId(id),
                requisitionRepository.sumLengthByOrderId(id),
                requisitions, attempts, bundles);
    }

    @Transactional
    public CuttingOrder create(CuttingOrder input) {
        if (input.getOrderNo() == null || input.getOrderNo().isBlank()) {
            throw new BizException("订单号不能为空");
        }
        orderRepository.findByOrderNo(input.getOrderNo().trim()).ifPresent(o -> {
            throw new BizException("订单号已存在：" + input.getOrderNo());
        });
        if (input.getCustomerName() == null || input.getCustomerName().isBlank()) {
            throw new BizException("客户名称不能为空");
        }
        if (input.getTargetGrammage() == null || input.getTargetGrammage() <= 0) {
            throw new BizException("目标定量必须大于 0");
        }
        if (input.getTargetWidthMm() == null || input.getTargetWidthMm() <= 0) {
            throw new BizException("目标幅宽必须大于 0");
        }
        if (input.getTargetWeightKg() == null || input.getTargetWeightKg().signum() <= 0) {
            throw new BizException("目标重量必须大于 0");
        }
        if (input.getDeadline() != null && input.getDeadline().isBefore(LocalDate.now())) {
            throw new BizException("交期不能早于今天");
        }
        CuttingOrder order = new CuttingOrder();
        order.setOrderNo(input.getOrderNo().trim());
        order.setCustomerName(input.getCustomerName().trim());
        order.setTargetGrammage(input.getTargetGrammage());
        order.setTargetWidthMm(input.getTargetWidthMm());
        order.setTargetWeightKg(input.getTargetWeightKg());
        order.setDoneWeightKg(BigDecimal.ZERO);
        order.setStatus("待排产");
        order.setDeadline(input.getDeadline());
        return orderRepository.save(order);
    }

    /**
     * 修改订单。待排产且未绑定母卷时只校验规格本身；已绑定母卷/切裁中时：
     * <ul>
     *   <li>规格字段变化：在订单、母卷行锁内重新核对绑定母卷，失败则整笔回滚，原订单和账本保持不变；</li>
     *   <li>目标重量不得低于已入库重量（含已出库件），欠量和完成率随后由新目标与已入库量重算；</li>
     *   <li>只提交交期时仅更新交期，不改状态、不清领料、不清成品追溯；</li>
     *   <li>请求版本过期返回 409，并带当前版本、规格、状态和具体冲突原因。</li>
     * </ul>
     */
    @Transactional
    public CuttingOrder update(Long id, CuttingOrder input) {
        CuttingOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("切纸订单不存在：" + id));
        ensureVersion(input.getVersion(), order, id, "修改订单");

        if ("已取消".equals(order.getStatus()) || "已完成".equals(order.getStatus())) {
            throw new BizException("订单已处于「" + order.getStatus() + "」，不能再修改");
        }
        if (input.getDeadline() != null && input.getDeadline().isBefore(LocalDate.now())) {
            throw new BizException("交期不能早于今天");
        }

        boolean specChanged = input.getTargetGrammage() != null
                || input.getTargetWidthMm() != null || input.getTargetWeightKg() != null;

        if (!specChanged) {
            applySimpleFields(order, input);
            try {
                return orderRepository.save(order);
            } catch (ObjectOptimisticLockingFailureException e) {
                throw versionConflict(input.getVersion(), id, "规格或状态刚被其他操作员修改");
            }
        }

        Integer nextGrammage = input.getTargetGrammage() == null
                ? order.getTargetGrammage() : input.getTargetGrammage();
        Integer nextWidth = input.getTargetWidthMm() == null
                ? order.getTargetWidthMm() : input.getTargetWidthMm();
        BigDecimal nextWeight = input.getTargetWeightKg() == null
                ? order.getTargetWeightKg() : input.getTargetWeightKg();
        validateSpec(nextGrammage, nextWidth, nextWeight);

        // 已入库量按成品账本汇总，而不是信任可能被旧并发写覆盖的订单缓存值。
        BigDecimal inboundWeight = bundleRepository.sumWeightByOrderId(id);
        if (nextWeight.compareTo(inboundWeight) < 0) {
            throw new BizException("目标重量不能低于已入库的 " + inboundWeight + " 千克"
                    + "（含已出库成品；请保留原订单或改为不小于该重量）");
        }
        BigDecimal nextGap = nextWeight.subtract(inboundWeight);

        PaperRoll boundRoll = lockAssignedRoll(order);
        if (boundRoll != null) {
            RollCheck rollCheck = check(nextGrammage, nextWidth, nextGap, boundRoll, true, true);
            if (!rollCheck.ok()) {
                throw new BizException("绑定母卷 " + boundRoll.getRollNo()
                        + " 已不满足修改后的上机条件，本次修改已拒绝；原订单规格、已发生领料量、订单欠量、完成率和成品追溯均保持原值。原因："
                        + String.join("；", rollCheck.problems()));
            }
        }

        order.setTargetGrammage(nextGrammage);
        order.setTargetWidthMm(nextWidth);
        order.setTargetWeightKg(nextWeight);
        // 以成品账本为准修正缓存，使欠量/完成率和后续入库立即使用同一订单口径。
        order.setDoneWeightKg(inboundWeight);
        applySimpleFields(order, input);
        try {
            return orderRepository.save(order);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw versionConflict(input.getVersion(), id,
                    "规格修改与另一名操作员的修改并发，当前母卷校验/领料/成品口径已刷新");
        }
    }

    /**
     * 校验某母卷能否用于本订单（不落库）。待排产候选只核对状态和规格；
     * 已绑定母卷还要确认剩余重量能覆盖当前欠量。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> checkRoll(Long id, String rollNo) {
        CuttingOrder order = get(id);
        PaperRoll roll = rollRepository.findByRollNo(rollNo)
                .orElseThrow(() -> new BizException("纸卷不存在：" + rollNo));
        boolean assigned = order.getAssignedRoll() != null
                && roll.getId().equals(order.getAssignedRoll().getId());
        RollCheck check = check(order, roll, assigned, assigned);
        boolean ready = assigned ? check.ok()
                : (Dict.isRollUsable(roll.getStatus())
                        && check.widthGap() >= 0
                        && check.grammageGap() <= Dict.GRAMMAGE_TOLERANCE);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rollNo", roll.getRollNo());
        out.put("orderNo", order.getOrderNo());
        out.put("assigned", assigned);
        out.put("requirable", check.requirable());
        out.put("widthGapMm", check.widthGap());
        out.put("grammageGap", check.grammageGap());
        out.put("remainLengthM", roll.getLengthM());
        out.put("remainWeightKg", roll.getWeightKg());
        out.put("status", roll.getStatus());
        out.put("maxTakeLengthM", check.requirable() ? roll.getLengthM() : 0);
        out.put("maxTakeWeightKg", check.requirable() ? roll.getWeightKg() : BigDecimal.ZERO);
        out.put("problems", check.problems());
        out.put("ok", ready);
        return out;
    }

    /**
     * 排产：指定母卷上切纸机，并把订单推进到「切裁中」。
     */
    @Transactional
    public CuttingOrder assignRoll(Long id, String rollNo, Long expectedVersion) {
        CuttingOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("切纸订单不存在：" + id));
        ensureVersion(expectedVersion, order, id, "排产");
        if (!"待排产".equals(order.getStatus())) {
            throw new BizException("只有「待排产」的订单能排产，当前状态：" + order.getStatus());
        }
        if (order.getAssignedRoll() != null) {
            throw new BizException("订单已绑定母卷 " + order.getAssignedRoll().getRollNo()
                    + "，不能重复排产");
        }
        PaperRoll roll = rollRepository.findByRollNoForUpdate(rollNo)
                .orElseThrow(() -> new BizException("纸卷不存在：" + rollNo));
        RollCheck check = check(order, roll, true, false);
        if (!check.ok()) {
            throw new BizException("母卷不能上机：" + String.join("；", check.problems()));
        }
        order.setAssignedRoll(roll);
        order.setStatus("切裁中");
        try {
            return orderRepository.save(order);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw versionConflict(expectedVersion, id, "排产前订单刚被其他操作员修改");
        }
    }

    /** 状态推进：待排产 → 切裁中 → 已完成；切裁中必须已有绑定母卷。 */
    @Transactional
    public CuttingOrder transfer(Long id, String target, Long expectedVersion) {
        CuttingOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("切纸订单不存在：" + id));
        ensureVersion(expectedVersion, order, id, "状态推进");
        if (!Dict.isValidOrderStatus(target)) {
            throw new BizException("订单状态不合法：" + target);
        }
        if (!"已取消".equals(target) && !Dict.canTransferOrder(order.getStatus(), target)) {
            throw new BizException("订单只能逐级推进，不允许从「" + order.getStatus() + "」到「" + target + "」");
        }
        if ("切裁中".equals(target) && order.getAssignedRoll() == null) {
            throw new BizException("订单还没有绑定排产母卷，请先在母卷校验清单中选择可上机母卷");
        }
        if ("已完成".equals(target) && order.getGapWeightKg().signum() > 0) {
            throw new BizException("还欠 " + order.getGapWeightKg() + " 千克未完成，不能置为已完成");
        }
        order.setStatus(target);
        try {
            return orderRepository.save(order);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw versionConflict(expectedVersion, id, "状态流转前订单刚被其他操作员修改");
        }
    }

    private void applySimpleFields(CuttingOrder order, CuttingOrder input) {
        if (input.getCustomerName() != null && !input.getCustomerName().isBlank()) {
            order.setCustomerName(input.getCustomerName().trim());
        }
        if (input.getDeadline() != null) {
            order.setDeadline(input.getDeadline());
        }
    }

    private void validateSpec(Integer grammage, Integer width, BigDecimal weight) {
        if (grammage == null || grammage <= 0) {
            throw new BizException("目标定量必须大于 0");
        }
        if (width == null || width <= 0) {
            throw new BizException("目标幅宽必须大于 0");
        }
        if (weight == null || weight.signum() <= 0) {
            throw new BizException("目标重量必须大于 0");
        }
    }

    private PaperRoll lockAssignedRoll(CuttingOrder order) {
        if (order.getAssignedRoll() == null) {
            return null;
        }
        return rollRepository.findByIdForUpdate(order.getAssignedRoll().getId())
                .orElseThrow(() -> new BizException("绑定母卷不存在，不能继续修改订单规格"));
    }

    private RollCheck check(CuttingOrder order, PaperRoll roll, boolean capacityRequired,
                            boolean requirableWhenOk) {
        BigDecimal gap = order.getTargetWeightKg().subtract(
                bundleRepository.sumWeightByOrderId(order.getId()));
        if (gap.signum() < 0) {
            gap = BigDecimal.ZERO;
        }
        return check(order.getTargetWidthMm(), order.getTargetGrammage(), gap, roll,
                capacityRequired, requirableWhenOk);
    }

    /**
     * @param capacityRequired true 表示这是排产/已绑定母卷，必须有余量覆盖欠量；
     *                         false 表示待排产候选清单，只判断规格能不能切。
     * @param requirableWhenOk true 表示该卷是订单当前唯一允许领料的绑定母卷。
     */
    private RollCheck check(Integer targetWidth, Integer targetGrammage, BigDecimal gap,
                            PaperRoll roll, boolean capacityRequired, boolean requirableWhenOk) {
        List<String> problems = new ArrayList<>();
        int widthGap = roll.getWidthMm() - targetWidth;
        int grammageGap = Math.abs(roll.getGrammageGsm() - targetGrammage);
        if (!Dict.isRollUsable(roll.getStatus())) {
            problems.add("母卷状态为「" + roll.getStatus() + "」，不可上机");
        }
        if (widthGap < 0) {
            problems.add("母卷幅宽 " + roll.getWidthMm() + " 小于订单目标幅宽 " + targetWidth);
        }
        if (grammageGap > Dict.GRAMMAGE_TOLERANCE) {
            problems.add("母卷定量 " + roll.getGrammageGsm() + " 与订单目标 " + targetGrammage
                    + " 偏差超过 " + Dict.GRAMMAGE_TOLERANCE);
        }
        if (capacityRequired && gap.signum() > 0
                && roll.getWeightKg().compareTo(gap) < 0) {
            problems.add("母卷剩余重量 " + roll.getWeightKg() + " 千克不足订单欠量 "
                    + gap + " 千克");
        }
        boolean specOk = Dict.isRollUsable(roll.getStatus()) && widthGap >= 0
                && grammageGap <= Dict.GRAMMAGE_TOLERANCE;
        boolean capacityOk = !capacityRequired || gap.signum() == 0
                || roll.getWeightKg().compareTo(gap) >= 0;
        boolean ok = specOk && capacityOk;
        return new RollCheck(ok, ok && requirableWhenOk, widthGap, grammageGap, problems);
    }

    private void ensureVersion(Long expected, CuttingOrder current, Long orderId, String action) {
        if (expected == null) {
            throw versionConflict(null, orderId, action + "请求缺少 version，请刷新订单详情后重试");
        }
        if (!expected.equals(current.getVersion())) {
            throw versionConflict(expected, orderId,
                    "页面基于版本 " + expected + " 编辑，当前版本已变为 " + current.getVersion());
        }
    }

    private VersionConflictException versionConflict(Long expected, Long orderId, String reason) {
        CuttingOrder current = orderRepository.findById(orderId).orElse(null);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (current != null) {
            snapshot.put("version", current.getVersion());
            snapshot.put("status", current.getStatus());
            snapshot.put("targetGrammage", current.getTargetGrammage());
            snapshot.put("targetWidthMm", current.getTargetWidthMm());
            snapshot.put("targetWeightKg", current.getTargetWeightKg());
            snapshot.put("doneWeightKg", current.getDoneWeightKg());
            snapshot.put("gapWeightKg", current.getGapWeightKg());
            snapshot.put("assignedRollNo", current.getAssignedRollNo());
            snapshot.put("deadline", current.getDeadline());
        }
        snapshot.put("conflictReason", reason);
        return new VersionConflictException(expected,
                current == null ? null : current.getVersion(), snapshot);
    }

    private record RollCheck(boolean ok, boolean requirable, int widthGap,
                             int grammageGap, List<String> problems) {
    }
}
