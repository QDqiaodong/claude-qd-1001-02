package com.paper.mill.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.paper.mill.common.BizException;
import com.paper.mill.common.ConflictException;
import com.paper.mill.dto.CuttingOrderUpdateRequest;
import com.paper.mill.dto.RequisitionRequest;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.FinishedBundle;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.repository.CuttingOrderRepository;
import com.paper.mill.repository.FinishedBundleRepository;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;
import com.paper.mill.service.CuttingOrderService;
import com.paper.mill.service.FinishedBundleService;
import com.paper.mill.service.RequisitionService;

/**
 * 已排产/切裁中订单修改规格的状态一致性集成测试（H2 / MySQL 兼容模式）。
 *
 * <p>覆盖：① 排产定格绑定母卷；② 改规格在事务内复核母卷，不满足整笔拒绝、原订单不变；
 * ③ 满足时放行且欠量/完成率按新目标重量重算、领料与成品历史不被清退；
 * ④ 目标重量不能低于已入库量；⑤ 切裁中改交期只改交期、不回退状态；
 * ⑥ 两人同改一单只有一方成功，失败方拿到当前版本与冲突原因；
 * ⑦ 详情快照各部分同口径；⑧ 已排产后别的候选卷不再可上机/领料。</p>
 */
@SpringBootTest
@ActiveProfiles("it")
class CuttingOrderSpecChangeTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired CuttingOrderService orderService;
    @Autowired RequisitionService requisitionService;
    @Autowired FinishedBundleService bundleService;
    @Autowired CuttingOrderRepository orderRepository;
    @Autowired PaperRollRepository rollRepository;
    @Autowired PaperMachineRepository machineRepository;
    @Autowired FinishedBundleRepository bundleRepository;

    // ---------------- 排产：绑定母卷并进入切裁中 ----------------

    @Test
    void assign_binds_roll_and_moves_to_cutting() {
        Ctx c = new Ctx(70, 3150);
        CuttingOrder assigned = orderService.assignRoll(c.orderId, c.rollNo);
        assertThat(assigned.getStatus()).isEqualTo("切裁中");
        assertThat(assigned.getAssignedRollNo()).isEqualTo(c.rollNo);

        Map<String, Object> v = orderService.checkRoll(c.orderId, c.rollNo);
        assertThat(v.get("ok")).isEqualTo(true);
        assertThat(v.get("assignedToThisOrder")).isEqualTo(true);

        // 另一卷规格也合格的母卷不能再上机/领料
        Ctx other = new Ctx(70, 3000);
        Map<String, Object> vOther = orderService.checkRoll(c.orderId, other.rollNo);
        assertThat(vOther.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(vOther.get("problems"))).contains("已排产绑定母卷");
    }

    // ---------------- 改规格：不满足上机条件时拒绝并保留原订单 ----------------

    @Test
    void spec_change_rejected_when_bound_roll_no_longer_machineable_and_order_untouched() {
        Ctx c = new Ctx(70, 1000);
        orderService.assignRoll(c.orderId, c.rollNo);
        long versionBefore = orderRepository.findById(c.orderId).orElseThrow().getVersion();

        // 把目标幅宽改到 1200：绑定卷只有 1000mm，幅宽不足 → 整笔拒绝
        CuttingOrderUpdateRequest req = c.updateReq(versionBefore);
        req.setTargetWidthMm(1200);
        BizException e = assertThrows(BizException.class, () -> orderService.update(c.orderId, req));
        assertThat(e.getMessage()).contains("不再满足上机条件").contains("幅宽");

        CuttingOrder unchanged = orderRepository.findById(c.orderId).orElseThrow();
        assertThat(unchanged.getTargetWidthMm()).isEqualTo(787);
        assertThat(unchanged.getStatus()).isEqualTo("切裁中");
        assertThat(unchanged.getAssignedRollNo()).isEqualTo(c.rollNo);
        // 拒绝修改不得推进版本
        assertThat(unchanged.getVersion()).isEqualTo(versionBefore);

        // 定量偏差超 3 同样拒绝
        CuttingOrderUpdateRequest gReq = c.updateReq(versionBefore);
        gReq.setTargetGrammage(90);
        BizException e2 = assertThrows(BizException.class, () -> orderService.update(c.orderId, gReq));
        assertThat(e2.getMessage()).contains("不再满足上机条件").contains("偏差");
        assertThat(orderRepository.findById(c.orderId).orElseThrow().getTargetGrammage()).isEqualTo(70);
    }

    // ---------------- 改规格：满足时放行，欠量/完成率重算，历史不清退 ----------------

    @Test
    @SuppressWarnings("unchecked")
    void spec_change_allowed_when_still_machineable_and_gap_progress_recomputed() {
        Ctx c = new Ctx(70, 3150);
        orderService.assignRoll(c.orderId, c.rollNo);

        // 入库 300kg，再把目标重量从 5000 改成 2000：欠量应变 1700，完成率 300/2000=15.0
        c.inbound("FB-CHG-" + c.seq, new BigDecimal("300.00"));
        long v = orderRepository.findById(c.orderId).orElseThrow().getVersion();

        CuttingOrderUpdateRequest req = c.updateReq(v);
        req.setTargetWeightKg(new BigDecimal("2000.00"));
        // 同时把幅宽改成 1092（绑定卷 3150mm 仍够）、定量保持 70
        req.setTargetWidthMm(1092);
        CuttingOrder saved = orderService.update(c.orderId, req);

        assertThat(saved.getVersion()).isEqualTo(v + 1);
        assertThat(saved.getTargetWidthMm()).isEqualTo(1092);
        assertThat(saved.getTargetWeightKg()).isEqualByComparingTo("2000.00");
        assertThat(saved.getDoneWeightKg()).isEqualByComparingTo("300.00");
        assertThat(saved.getGapWeightKg()).isEqualByComparingTo("1700.00");
        assertThat(saved.getProgressRate()).isEqualTo(15.0);
        assertThat(saved.getStatus()).isEqualTo("切裁中");
        assertThat(saved.getAssignedRollNo()).isEqualTo(c.rollNo);

        // 详情快照：绑定卷对新规格仍满足；成品历史保留
        Map<String, Object> detail = orderService.detail(c.orderId);
        CuttingOrder snapOrder = (CuttingOrder) detail.get("order");
        assertThat(snapOrder.getTargetWidthMm()).isEqualTo(1092);
        assertThat(((Map<?, ?>) detail.get("assignedMatch")).get("ok")).isEqualTo(true);
        List<FinishedBundle> bundles = (List<FinishedBundle>) detail.get("finishedBundles");
        assertThat(bundles).hasSize(1);
    }

    // ---------------- 目标重量不能低于已入库量 ----------------

    @Test
    void target_weight_cannot_go_below_done_weight() {
        Ctx c = new Ctx(70, 3150);
        orderService.assignRoll(c.orderId, c.rollNo);
        c.inbound("FB-DONE-" + c.seq, new BigDecimal("500.00"));
        long v = orderRepository.findById(c.orderId).orElseThrow().getVersion();

        CuttingOrderUpdateRequest req = c.updateReq(v);
        req.setTargetWeightKg(new BigDecimal("499.99"));
        BizException e = assertThrows(BizException.class, () -> orderService.update(c.orderId, req));
        assertThat(e.getMessage()).contains("不能改到低于已入库量");

        CuttingOrder untouched = orderRepository.findById(c.orderId).orElseThrow();
        assertThat(untouched.getTargetWeightKg()).isEqualByComparingTo("5000.00");
        assertThat(untouched.getVersion()).isEqualTo(v);
    }

    // ---------------- 切裁中改交期：只改交期，不回退状态、不清历史 ----------------

    @Test
    void deadline_change_in_cutting_only_touches_deadline() {
        Ctx c = new Ctx(70, 3150);
        orderService.assignRoll(c.orderId, c.rollNo);
        c.inbound("FB-DL-" + c.seq, new BigDecimal("100.00"));
        long v = orderRepository.findById(c.orderId).orElseThrow().getVersion();

        CuttingOrderUpdateRequest req = c.updateReq(v);
        req.setDeadline(LocalDate.now().plusDays(30));
        req.setDeadlineTouched(true);
        CuttingOrder saved = orderService.update(c.orderId, req);

        assertThat(saved.getStatus()).isEqualTo("切裁中");
        assertThat(saved.getAssignedRollNo()).isEqualTo(c.rollNo);
        assertThat(saved.getDeadline()).isEqualTo(LocalDate.now().plusDays(30));
        assertThat(saved.getDoneWeightKg()).isEqualByComparingTo("100.00");
        assertThat(bundleRepository.findByOrderId(c.orderId)).hasSize(1);
    }

    // ---------------- 并发：两人同改一单只有一方成功，失败方看到当前版本 ----------------

    @Test
    void concurrent_edits_only_one_wins_loser_sees_current_version_and_reason() throws Exception {
        Ctx c = new Ctx(70, 3150);
        orderService.assignRoll(c.orderId, c.rollNo);
        long baseVersion = orderRepository.findById(c.orderId).orElseThrow().getVersion();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicReference<Long> loserSawVersion = new AtomicReference<>();
        AtomicReference<String> loserReason = new AtomicReference<>();

        Future<?> f1 = pool.submit(() -> runEdit(barrier, c, baseVersion, 1000,
                wins, conflicts, loserSawVersion, loserReason));
        Future<?> f2 = pool.submit(() -> runEdit(barrier, c, baseVersion, 1100,
                wins, conflicts, loserSawVersion, loserReason));
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(wins.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        // 失败方看到的「当前版本」就是成功方提交后的版本
        assertThat(loserSawVersion.get()).isEqualTo(baseVersion + 1);
        assertThat(loserReason.get()).contains("已被其他操作员修改").contains("版本");

        CuttingOrder finalOrder = orderRepository.findById(c.orderId).orElseThrow();
        assertThat(finalOrder.getVersion()).isEqualTo(baseVersion + 1);
        // 两个目标幅宽（1000/1100）都 < 绑定卷 3150，成功方提交的那个生效，没有半更新
        assertThat(finalOrder.getTargetWidthMm()).isIn(1000, 1100);
        assertThat(finalOrder.getStatus()).isEqualTo("切裁中");
        assertThat(finalOrder.getAssignedRollNo()).isEqualTo(c.rollNo);
    }

    // ---------------- 领料只能从绑定母卷走 ----------------

    @Test
    void requisition_from_other_roll_rejected_after_assignment() {
        Ctx c = new Ctx(70, 3150);
        orderService.assignRoll(c.orderId, c.rollNo);
        Ctx other = new Ctx(70, 3000); // 另一卷规格也合格

        RequisitionRequest req = new RequisitionRequest();
        req.setRequestToken("tok-other-roll-" + c.seq + "-" + other.seq);
        req.setRollNo(other.rollNo);
        req.setLengthM(100);
        req.setWeightKg(new BigDecimal("21.00"));
        req.setOperator("张三");
        BizException e = assertThrows(BizException.class,
                () -> requisitionService.submit(c.orderId, req));
        assertThat(e.getMessage()).contains("已排产绑定母卷");
        // 另一卷余量未动、本订单没有新增领料账本
        assertThat(rollRepository.findByRollNo(other.rollNo).orElseThrow().getLengthM())
                .isEqualTo(other.initLength);
        assertThat(requisitionService.listByOrder(c.orderId)).isEmpty();
    }

    private void runEdit(CyclicBarrier barrier, Ctx c, long baseVersion, int newWidth,
                         AtomicInteger wins, AtomicInteger conflicts,
                         AtomicReference<Long> loserSawVersion, AtomicReference<String> loserReason) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            CuttingOrderUpdateRequest req = c.updateReq(baseVersion);
            req.setTargetWidthMm(newWidth);
            orderService.update(c.orderId, req);
            wins.incrementAndGet();
        } catch (ConflictException e) {
            conflicts.incrementAndGet();
            loserSawVersion.set(e.getCurrentVersion());
            loserReason.set(e.getMessage());
        } catch (Exception e) {
            conflicts.incrementAndGet();
            loserReason.set("UNEXPECTED: " + e);
        }
    }

    // ---------------- 夹具 ----------------

    private final class Ctx {
        final long seq = SEQ.incrementAndGet();
        final Long orderId;
        final String rollNo;
        final int initLength = 5000;

        Ctx(int grammage, int widthMm) {
            PaperMachine m = new PaperMachine();
            m.setMachineCode("PM-SC-" + seq);
            m.setMachineName("规格变更测试纸机" + seq);
            m.setDesignWidthMm(3200);
            m.setDesignSpeedMpm(500);
            m.setStatus("运行");
            machineRepository.save(m);

            rollNo = "R-SC-" + seq;
            PaperRoll roll = new PaperRoll();
            roll.setRollNo(rollNo);
            roll.setMachine(m);
            roll.setGrammageGsm(grammage);
            roll.setWidthMm(widthMm);
            roll.setLengthM(initLength);
            roll.setWeightKg(new BigDecimal("1100.00"));
            roll.setGrade("一等");
            roll.setStatus("可用");
            rollRepository.save(roll);

            CuttingOrder order = new CuttingOrder();
            order.setOrderNo("CO-SC-" + seq);
            order.setCustomerName("规格变更测试客户" + seq);
            order.setTargetGrammage(70);
            order.setTargetWidthMm(787);
            order.setTargetWeightKg(new BigDecimal("5000.00"));
            order.setDoneWeightKg(BigDecimal.ZERO);
            order.setStatus("待排产");
            orderId = orderRepository.save(order).getId();
        }

        CuttingOrderUpdateRequest updateReq(long version) {
            CuttingOrderUpdateRequest r = new CuttingOrderUpdateRequest();
            r.setExpectedVersion(version);
            // 默认不动规格；具体用例再覆盖需要改的字段
            r.setTargetGrammage(null);
            r.setTargetWidthMm(null);
            r.setTargetWeightKg(null);
            r.setDeadlineTouched(false);
            return r;
        }

        void inbound(String bundleNo, BigDecimal weight) {
            FinishedBundle b = new FinishedBundle();
            b.setBundleNo(bundleNo);
            CuttingOrder ref = new CuttingOrder();
            ref.setId(orderId);
            b.setOrder(ref);
            b.setGrammageGsm(70);
            b.setWidthMm(787);
            b.setSheetCount(1000);
            b.setWeightKg(weight);
            b.setWarehouseZone("A区");
            bundleService.inbound(b);
        }
    }
}
