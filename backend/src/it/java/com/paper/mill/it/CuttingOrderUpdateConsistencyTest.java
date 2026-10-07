package com.paper.mill.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.paper.mill.common.BizException;
import com.paper.mill.common.VersionConflictException;
import com.paper.mill.dto.CuttingOrderDetail;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.FinishedBundle;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.repository.CuttingOrderRepository;
import com.paper.mill.repository.CuttingRequisitionRepository;
import com.paper.mill.repository.FinishedBundleRepository;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;
import com.paper.mill.service.CuttingOrderService;

/**
 * 已排产/切裁中订单修改规格的一致性集成测试。
 */
@SpringBootTest
@ActiveProfiles("it")
class CuttingOrderUpdateConsistencyTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired CuttingOrderService orderService;
    @Autowired CuttingOrderRepository orderRepository;
    @Autowired PaperRollRepository rollRepository;
    @Autowired PaperMachineRepository machineRepository;
    @Autowired FinishedBundleRepository bundleRepository;
    @Autowired CuttingRequisitionRepository requisitionRepository;

    @Test
    void spec_update_after_assign_rechecks_bound_roll_and_keeps_ledger_when_accepted() {
        Fixture f = fixture(10000, new BigDecimal("2200.00"), "可用", new BigDecimal("1000.00"));
        CuttingOrder input = f.update(f.order.getVersion());
        input.setTargetWidthMm(800);
        input.setTargetGrammage(72);
        input.setTargetWeightKg(new BigDecimal("600.00"));

        CuttingOrder saved = orderService.update(f.orderId, input);

        assertThat(saved.getTargetWidthMm()).isEqualTo(800);
        assertThat(saved.getTargetGrammage()).isEqualTo(72);
        assertThat(saved.getStatus()).isEqualTo("切裁中");
        assertThat(saved.getAssignedRoll().getId()).isEqualTo(f.rollId);
        assertThat(saved.getGapWeightKg()).isEqualByComparingTo("600.00");
        assertThat(saved.getVersion()).isEqualTo(1);

        CuttingOrderDetail detail = orderService.detail(f.orderId);
        assertThat(detail.assignedRoll().ok()).isTrue();
        assertThat(detail.requisitionedWeightKg()).isEqualByComparingTo("220.00");
        assertThat(detail.requisitionedLengthM()).isEqualTo(1000L);
        assertThat(detail.requisitions()).hasSize(1);
        assertThat(detail.finishedBundles()).hasSize(1);
    }

    @Test
    void spec_update_is_rejected_when_bound_roll_no_longer_matches() {
        Fixture f = fixture(1000, new BigDecimal("220.00"), "可用", new BigDecimal("1000.00"));
        CuttingOrder input = f.update(f.order.getVersion());
        input.setTargetWidthMm(4000);

        BizException e = assertThrows(BizException.class, () -> orderService.update(f.orderId, input));
        assertThat(e.getMessage()).contains("绑定母卷").contains("不满足").contains("幅宽");

        CuttingOrder unchanged = orderRepository.findById(f.orderId).orElseThrow();
        assertThat(unchanged.getTargetWidthMm()).isEqualTo(787);
        assertThat(unchanged.getStatus()).isEqualTo("切裁中");
        assertThat(unchanged.getAssignedRoll().getId()).isEqualTo(f.rollId);
        assertThat(unchanged.getGapWeightKg()).isEqualByComparingTo("900.00");
        assertThat(unchanged.getVersion()).isZero();
        assertThat(orderService.detail(f.orderId).requisitions()).hasSize(1);
    }

    @Test
    void target_weight_cannot_go_below_finished_inbound_including_outbound_bundles() {
        Fixture f = fixture(1000, new BigDecimal("220.00"), "可用", new BigDecimal("1000.00"));
        CuttingOrder input = f.update(f.order.getVersion());
        input.setTargetWeightKg(new BigDecimal("99.00"));

        BizException e = assertThrows(BizException.class, () -> orderService.update(f.orderId, input));
        assertThat(e.getMessage()).contains("目标重量不能低于已入库").contains("100.00");

        CuttingOrder unchanged = orderRepository.findById(f.orderId).orElseThrow();
        assertThat(unchanged.getTargetWeightKg()).isEqualByComparingTo("1000.00");
        assertThat(unchanged.getDoneWeightKg()).isEqualByComparingTo("100.00");
    }

    @Test
    void deadline_only_update_never_resets_status_binding_requisition_or_bundles() {
        Fixture f = fixture(1000, new BigDecimal("220.00"), "可用", new BigDecimal("1000.00"));
        LocalDate deadline = LocalDate.now().plusDays(20);
        CuttingOrder input = new CuttingOrder();
        input.setVersion(f.order.getVersion());
        input.setDeadline(deadline);

        CuttingOrder saved = orderService.update(f.orderId, input);

        assertThat(saved.getDeadline()).isEqualTo(deadline);
        assertThat(saved.getStatus()).isEqualTo("切裁中");
        assertThat(saved.getAssignedRoll().getId()).isEqualTo(f.rollId);
        assertThat(saved.getTargetWidthMm()).isEqualTo(787);
        assertThat(saved.getDoneWeightKg()).isEqualByComparingTo("100.00");
        assertThat(orderService.detail(f.orderId).requisitions()).hasSize(1);
        assertThat(orderService.detail(f.orderId).finishedBundles()).hasSize(1);
    }

    @Test
    void stale_version_fails_with_current_version_and_conflict_reason() {
        Fixture f = fixture(10000, new BigDecimal("2200.00"), "可用", new BigDecimal("1000.00"));
        CuttingOrder first = f.update(0L);
        first.setTargetWidthMm(790);
        orderService.update(f.orderId, first);

        CuttingOrder stale = f.update(0L);
        stale.setTargetWidthMm(800);
        VersionConflictException e = assertThrows(VersionConflictException.class,
                () -> orderService.update(f.orderId, stale));
        assertThat(e.getCurrentVersion()).isEqualTo(1);
        assertThat(e.getCurrent().get("targetWidthMm")).isEqualTo(790);
        assertThat(String.valueOf(e.getCurrent().get("conflictReason"))).contains("版本");
    }

    @Test
    void requirable_rolls_come_from_latest_order_snapshot_after_spec_change() {
        Fixture f = fixture(10000, new BigDecimal("2200.00"), "可用", new BigDecimal("1000.00"));
        PaperRoll other = roll(f.roll.getMachine(), "R-OTHER-" + f.n, 75, 1000, 5000,
                new BigDecimal("700.00"), "可用");

        CuttingOrder input = f.update(f.order.getVersion());
        input.setTargetGrammage(75);
        input.setTargetWeightKg(new BigDecimal("600.00"));
        orderService.update(f.orderId, input);

        CuttingOrderDetail detail = orderService.detail(f.orderId);
        assertThat(detail.assignedRoll().rollNo()).isEqualTo(f.rollNo);
        assertThat(detail.assignedRoll().ok()).isFalse();
        assertThat(detail.assignedRoll().problems().toString()).contains("定量");
        PaperRoll otherMatch = detail.rolls().stream()
                .filter(r -> r.rollNo().equals(other.getRollNo())).findFirst().orElseThrow();
        assertThat(otherMatch.match()).isTrue();
        // 其他可匹配卷仍不能领料：订单已绑定的是原母卷，不能把旧/新候选卷当作可领料卷。
        assertThat(otherMatch.requirable()).isFalse();
    }

    private Fixture fixture(int length, BigDecimal weight, String rollStatus, BigDecimal targetWeight) {
        long n = SEQ.incrementAndGet();
        PaperMachine machine = new PaperMachine();
        machine.setMachineCode("PM-OU-" + n);
        machine.setMachineName("订单修改测试纸机" + n);
        machine.setDesignWidthMm(4200);
        machine.setDesignSpeedMpm(500);
        machine.setStatus("运行");
        machineRepository.save(machine);

        String rollNo = "R-OU-" + n;
        PaperRoll roll = new PaperRoll();
        roll.setRollNo(rollNo);
        roll.setMachine(machine);
        roll.setGrammageGsm(70);
        roll.setWidthMm(3150);
        roll.setLengthM(length + 1000);
        roll.setWeightKg(weight.add(new BigDecimal("220.00")));
        roll.setGrade("一等");
        roll.setStatus(rollStatus);
        rollRepository.save(roll);

        CuttingOrder order = new CuttingOrder();
        order.setOrderNo("CO-OU-" + n);
        order.setCustomerName("订单修改客户" + n);
        order.setAssignedRoll(roll);
        order.setTargetGrammage(70);
        order.setTargetWidthMm(787);
        order.setTargetWeightKg(targetWeight);
        order.setDoneWeightKg(BigDecimal.ZERO);
        order.setStatus("切裁中");
        order.setDeadline(LocalDate.now().plusDays(7));
        order = orderRepository.save(order);

        // 一条已发生的领料账本，成功记录不允许被规格修改清掉。
        createRequisition(order, roll, 1000, new BigDecimal("220.00"));
        roll.setLengthM(length);
        roll.setWeightKg(weight);
        rollRepository.save(roll);

        FinishedBundle bundle = new FinishedBundle();
        bundle.setBundleNo("FB-OU-" + n);
        bundle.setOrder(order);
        bundle.setGrammageGsm(70);
        bundle.setWidthMm(787);
        bundle.setSheetCount(1000);
        bundle.setWeightKg(new BigDecimal("100.00"));
        bundle.setWarehouseZone("A区");
        bundle.setStatus("已出库");
        bundle.setStoredAt(java.time.LocalDateTime.now().minusDays(1));
        bundle.setOutboundAt(java.time.LocalDateTime.now().minusHours(6));
        bundleRepository.save(bundle);
        order.setDoneWeightKg(new BigDecimal("100.00"));
        orderRepository.save(order);

        return new Fixture(order.getId(), roll.getId(), rollNo, n,
                orderRepository.findById(order.getId()).orElseThrow(), roll);
    }

    private com.paper.mill.entity.CuttingRequisition createRequisition(
            CuttingOrder order, PaperRoll roll, int length, BigDecimal weight) {
        com.paper.mill.entity.CuttingRequisition q = new com.paper.mill.entity.CuttingRequisition();
        q.setRequisitionNo("LL-OU-" + SEQ.incrementAndGet());
        q.setOrder(order);
        q.setRoll(roll);
        q.setLengthM(length);
        q.setWeightKg(weight);
        q.setOperator("测试员");
        q.setOperatedAt(java.time.LocalDateTime.now().minusHours(2));
        q.setRemainLengthM(roll.getLengthM() - length);
        q.setRemainWeightKg(roll.getWeightKg().subtract(weight));
        return requisitionRepository.save(q);
    }

    private PaperRoll roll(PaperMachine machine, String rollNo, int gsm, int width,
                           int length, BigDecimal weight, String status) {
        PaperRoll roll = new PaperRoll();
        roll.setRollNo(rollNo);
        roll.setMachine(machine);
        roll.setGrammageGsm(gsm);
        roll.setWidthMm(width);
        roll.setLengthM(length);
        roll.setWeightKg(weight);
        roll.setGrade("一等");
        roll.setStatus(status);
        return rollRepository.save(roll);
    }

    private static final class Fixture {
        final Long orderId;
        final Long rollId;
        final String rollNo;
        final long n;
        final CuttingOrder order;
        final PaperRoll roll;

        Fixture(Long orderId, Long rollId, String rollNo, long n,
                CuttingOrder order, PaperRoll roll) {
            this.orderId = orderId;
            this.rollId = rollId;
            this.rollNo = rollNo;
            this.n = n;
            this.order = order;
            this.roll = roll;
        }

        CuttingOrder update(Long version) {
            CuttingOrder input = new CuttingOrder();
            input.setVersion(version);
            input.setCustomerName(order.getCustomerName());
            input.setTargetGrammage(order.getTargetGrammage());
            input.setTargetWidthMm(order.getTargetWidthMm());
            input.setTargetWeightKg(order.getTargetWeightKg());
            input.setDeadline(order.getDeadline());
            return input;
        }
    }
}
