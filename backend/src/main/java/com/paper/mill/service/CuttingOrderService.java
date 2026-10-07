package com.paper.mill.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.ConflictException;
import com.paper.mill.common.Dict;
import com.paper.mill.dto.CuttingOrderUpdateRequest;
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
 * ④ 状态只能逐级推进：待排产 → 切裁中 → 已完成。</p>
 *
 * <p><b>已排产订单改规格的一致性</b>：排产时母卷被定格绑定到订单（{@link CuttingOrder#getAssignedRoll()}）。
 * 之后改目标定量/幅宽/重量，在同一事务、同一行锁内重新核对该母卷是否仍满足上机条件，
 * 不满足则整笔修改拒绝（规格、欠量、完成率全部保持原值），满足才提交；已发生的领料量、
 * 成品入库量不会被清退，欠量与完成率由「目标重量 − 已入库重量」随新目标重量即时重算。
 * 目标重量不得改到低于已入库量。进入「切裁中」后修改交期只改交期，状态、领料与成品追溯不动。</p>
 *
 * <p><b>并发</b>：订单带乐观版本号，所有修改接口要求提交方带上所依据的版本；
 * 两人同改一单只有一方成功，失败方收到 409 + 当前版本 + 冲突原因，杜绝半更新。</p>
 */
@Service
public class CuttingOrderService {

    private final CuttingOrderRepository orderRepository;
    private final PaperRollRepository rollRepository;
    private final CuttingRequisitionRepository requisitionRepository;
    private final RequisitionAttemptRepository attemptRepository;
    private final FinishedBundleRepository bundleRepository;
    private final RollMatchService rollMatch;

    public CuttingOrderService(CuttingOrderRepository orderRepository,
                               PaperRollRepository rollRepository,
                               CuttingRequisitionRepository requisitionRepository,
                               RequisitionAttemptRepository attemptRepository,
                               FinishedBundleRepository bundleRepository,
                               RollMatchService rollMatch) {
        this.orderRepository = orderRepository;
        this.rollRepository = rollRepository;
        this.requisitionRepository = requisitionRepository;
        this.attemptRepository = attemptRepository;
        this.bundleRepository = bundleRepository;
        this.rollMatch = rollMatch;
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
        order.setCustomerName(input.getCustomerName());
        order.setTargetGrammage(input.getTargetGrammage());
        order.setTargetWidthMm(input.getTargetWidthMm());
        order.setTargetWeightKg(input.getTargetWeightKg());
        order.setDoneWeightKg(BigDecimal.ZERO);
        order.setStatus("待排产");
        order.setDeadline(input.getDeadline());
        return orderRepository.save(order);
    }

    /**
     * 修改订单（PATCH 语义 + 乐观版本）。
     *
     * <p>行锁 + 版本双重裁决：先对订单行加写锁（与领料/入库同一锁顺序），再核对调用方
     * 依据的版本。规格（定量/幅宽/重量）一旦订单已绑定排产母卷，必须重新核对该母卷对
     * <em>新规格</em>是否仍满足上机条件：任一不满足整笔拒绝，订单保持原值；目标重量还要
     * ≥ 已入库量。交期修改始终允许，但绝不回退状态、不动领料账本与成品追溯。</p>
     */
    @Transactional
    public CuttingOrder update(Long id, CuttingOrderUpdateRequest input) {
        if (input.getExpectedVersion() == null) {
            throw new BizException("缺少版本号 expectedVersion，请重新打开订单详情后再修改");
        }
        CuttingOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("切纸订单不存在：" + id));
        ensureVersion(order, input.getExpectedVersion());

        if ("已取消".equals(order.getStatus()) || "已完成".equals(order.getStatus())) {
            throw new BizException("订单已处于「" + order.getStatus() + "」，不能再修改");
        }

        boolean specTouched = input.getTargetGrammage() != null
                || input.getTargetWidthMm() != null
                || input.getTargetWeightKg() != null;

        // ---- 先在内存里套上新值并做纯字段校验，任何一项失败都不落库（实体随事务回滚） ----
        Integer newGrammage = order.getTargetGrammage();
        Integer newWidth = order.getTargetWidthMm();
        BigDecimal newWeight = order.getTargetWeightKg();

        if (input.getCustomerName() != null && !input.getCustomerName().isBlank()) {
            order.setCustomerName(input.getCustomerName().trim());
        }
        if (input.getTargetGrammage() != null) {
            if (input.getTargetGrammage() <= 0) {
                throw new BizException("目标定量必须大于 0");
            }
            newGrammage = input.getTargetGrammage();
        }
        if (input.getTargetWidthMm() != null) {
            if (input.getTargetWidthMm() <= 0) {
                throw new BizException("目标幅宽必须大于 0");
            }
            newWidth = input.getTargetWidthMm();
        }
        if (input.getTargetWeightKg() != null) {
            if (input.getTargetWeightKg().signum() <= 0) {
                throw new BizException("目标重量必须大于 0");
            }
            // 已有成品入库（含已出库）时，目标重量不能低于已入库量：
            // 否则欠量被扣成 0、完成率超过 100%，后续入库与追溯全部按错误口径走
            if (order.getDoneWeightKg() != null
                    && order.getDoneWeightKg().compareTo(input.getTargetWeightKg()) > 0) {
                throw new BizException("目标重量不能改到低于已入库量 "
                        + order.getDoneWeightKg() + " 千克（已有成品入库记录，订单保持原值）");
            }
            newWeight = input.getTargetWeightKg();
        }
        if (input.isDeadlineTouched() && input.getDeadline() != null
                && input.getDeadline().isBefore(LocalDate.now())) {
            throw new BizException("交期不能早于今天");
        }

        // ---- 规格变更且已绑定排产母卷：同一行锁内按新规格重新核对这卷母卷 ----
        if (specTouched && order.getAssignedRoll() != null) {
            PaperRoll bound = rollRepository.findByIdForUpdate(order.getAssignedRoll().getId())
                    .orElseThrow(() -> new BizException(
                            "排产母卷已不存在，无法复核上机条件，订单保持原值"));
            CuttingOrder probe = new CuttingOrder();
            probe.setTargetGrammage(newGrammage);
            probe.setTargetWidthMm(newWidth);
            probe.setTargetWeightKg(newWeight);
            List<String> problems = rollMatch.problems(bound, probe);
            if (!problems.isEmpty()) {
                throw new BizException("已绑定排产母卷 " + bound.getRollNo()
                        + "，新规格下不再满足上机条件，已拒绝修改并保留原订单："
                        + rollMatch.joinProblems(problems));
            }
        }

        // ---- 全部通过：提交新规格。已领料量/已入库量不清退、状态不回退 ----
        if (input.getTargetGrammage() != null) {
            order.setTargetGrammage(newGrammage);
        }
        if (input.getTargetWidthMm() != null) {
            order.setTargetWidthMm(newWidth);
        }
        if (input.getTargetWeightKg() != null) {
            order.setTargetWeightKg(newWeight);
        }
        // 进入「切裁中」后交期也可以改，但只改交期本身：deadlineTouched 才触碰，
        // 避免普通保存把交期清空，更不会动状态、领料与成品追溯
        if (input.isDeadlineTouched()) {
            order.setDeadline(input.getDeadline());
        }
        return orderRepository.save(order);
    }

    /**
     * 校验某母卷能否用于本订单（不落库）。
     *
     * @return 校验结论：可用余长、幅宽余量、定量偏差，以及切裁领料的可领上限
     *         （{code maxTakeLengthM} / {code maxTakeWeightKg}，操作员填领料量前先看清）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> checkRoll(Long id, String rollNo) {
        CuttingOrder order = get(id);
        PaperRoll roll = rollRepository.findByRollNo(rollNo)
                .orElseThrow(() -> new BizException("纸卷不存在：" + rollNo));
        boolean boundHere = order.getAssignedRoll() != null
                && order.getAssignedRoll().getId().equals(roll.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rollNo", roll.getRollNo());
        out.put("orderNo", order.getOrderNo());
        out.put("widthGapMm", roll.getWidthMm() - order.getTargetWidthMm());
        out.put("grammageGap", Math.abs(roll.getGrammageGsm() - order.getTargetGrammage()));
        out.put("remainLengthM", roll.getLengthM());
        out.put("remainWeightKg", roll.getWeightKg());
        out.put("status", roll.getStatus());
        out.put("assignedToThisOrder", boundHere);

        List<String> problems = rollMatch.problems(roll, order);
        // 排产定格后，已排产/切裁中的订单只能用绑定的那卷：别的候选卷即使规格仍合格，
        // 也不能再上机或领料（防止旧候选清单继续显示「可上机」导致换卷/串卷）
        if (order.getAssignedRoll() != null && !boundHere) {
            problems.add("订单已排产绑定母卷 " + order.getAssignedRoll().getRollNo()
                    + "，不能改用上机/领料其他母卷");
        }

        boolean usable = problems.isEmpty();
        // 可领上限就是母卷当前真实余量；不可领（非「可用」/规格不符/非绑定卷）时给 0，前端禁止提交
        out.put("maxTakeLengthM", usable ? roll.getLengthM() : 0);
        out.put("maxTakeWeightKg", usable ? roll.getWeightKg() : BigDecimal.ZERO);
        out.put("problems", problems);
        out.put("ok", usable);
        return out;
    }

    /**
     * 排产：指定母卷上切纸机，定格绑定并把订单推进到「切裁中」（同一事务）。
     */
    @Transactional
    public CuttingOrder assignRoll(Long id, String rollNo) {
        CuttingOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("切纸订单不存在：" + id));
        if (!"待排产".equals(order.getStatus())) {
            throw new BizException("只有「待排产」的订单能排产，当前状态：" + order.getStatus()
                    + (order.getAssignedRoll() != null
                            ? "（已绑定母卷 " + order.getAssignedRoll().getRollNo() + "）" : ""));
        }
        PaperRoll roll = rollRepository.findByRollNoForUpdate(rollNo)
                .orElseThrow(() -> new BizException("纸卷不存在：" + rollNo));
        List<String> problems = rollMatch.problems(roll, order);
        if (!problems.isEmpty()) {
            throw new BizException(rollMatch.joinProblems(problems));
        }
        order.setAssignedRoll(roll);
        order.setStatus("切裁中");
        return orderRepository.save(order);
    }

    /** 状态推进：待排产 → 切裁中 → 已完成 */
    @Transactional
    public CuttingOrder transfer(Long id, String target) {
        CuttingOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("切纸订单不存在：" + id));
        if (!Dict.isValidOrderStatus(target)) {
            throw new BizException("订单状态不合法：" + target);
        }
        if (!"已取消".equals(target) && !Dict.canTransferOrder(order.getStatus(), target)) {
            throw new BizException("订单只能逐级推进，不允许从「" + order.getStatus() + "」到「" + target + "」");
        }
        if ("已完成".equals(target) && order.getGapWeightKg().signum() > 0) {
            throw new BizException("还欠 " + order.getGapWeightKg() + " 千克未完成，不能置为已完成");
        }
        order.setStatus(target);
        return orderRepository.save(order);
    }

    /**
     * 订单详情快照：规格/版本、绑定母卷与匹配结论、欠量与完成率、领料账本、领料留痕、
     * 成品入库历史全部来自同一次读、同一份最新状态。页面刷新或重开详情只认这一个接口，
     * 旧的候选母卷结论不会残留在页面上。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        CuttingOrder order = get(id);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("order", order);

        if (order.getAssignedRoll() != null) {
            PaperRoll bound = rollRepository.findById(order.getAssignedRoll().getId()).orElse(null);
            snap.put("assignedRoll", bound);
            if (bound != null) {
                snap.put("assignedMatch", checkRoll(id, bound.getRollNo()));
            } else {
                snap.put("assignedMatch", null);
            }
        } else {
            snap.put("assignedRoll", null);
            snap.put("assignedMatch", null);
        }

        List<CuttingRequisition> ledger =
                requisitionRepository.findByOrderIdOrderByOperatedAtDesc(id);
        List<RequisitionAttempt> attempts =
                attemptRepository.findByOrderIdOrderByAttemptedAtDesc(id);
        List<FinishedBundle> bundles =
                bundleRepository.findByOrderIdOrderByStoredAtDesc(id);
        snap.put("requisitions", ledger);
        snap.put("requisitionAttempts", attempts);
        snap.put("finishedBundles", bundles);

        // 已领料重量来自成功账本汇总（账本只增不改），与订单规格独立，改规格不清零
        BigDecimal requisitionedKg = ledger.stream()
                .map(CuttingRequisition::getWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        snap.put("requisitionedWeightKg", requisitionedKg);
        return snap;
    }

    /**
     * 版本核对：提交方依据的版本与服务端当前版本不一致即 409，
     * 消息中给出订单号、双方版本、当前状态与规格，让失败方明确知道冲突在哪、该按什么重改。
     */
    private void ensureVersion(CuttingOrder order, Long expected) {
        long current = order.getVersion() == null ? 0L : order.getVersion();
        if (expected.longValue() != current) {
            throw new ConflictException("订单 " + order.getOrderNo() + " 已被其他操作员修改："
                    + "你提交时依据的是版本 " + expected + "，服务端当前为版本 " + current
                    + "（状态「" + order.getStatus() + "」，规格 " + order.getTargetGrammage()
                    + "g / " + order.getTargetWidthMm() + "mm / " + order.getTargetWeightKg()
                    + "kg，已入库 " + order.getDoneWeightKg() + "kg）。请按当前版本重新核对后再提交",
                    current);
        }
    }
}
