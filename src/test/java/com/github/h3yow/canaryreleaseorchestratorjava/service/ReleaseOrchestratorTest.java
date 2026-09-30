package com.github.h3yow.canaryreleaseorchestratorjava.service;

import com.github.h3yow.canaryreleaseorchestratorjava.domain.AdvanceOutcome;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.FailureReason;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.PlanStatus;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.ReleasePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseOrchestratorTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOrchestratorTest.class);

    private ReleaseOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ReleaseOrchestrator();
    }

    private void logOutcome(String tag, AdvanceOutcome o) {
        log.info("[{}] 判定结果: success={} reason={} status={} batch={} traffic={}% version={} msg={}",
                tag, o.success(), o.failureReason(), o.status(), o.currentBatchIndex(),
                o.cumulativeTrafficPercent(), o.currentVersion(), o.message());
    }

    // ---------- 创建计划：非法比例与版本冲突 ----------

    @Test
    void createPlanRejectsInvalidInputs() {
        log.info("=== 输入: 各种非法计划参数，期望彼此可区分的失败原因 ===");

        AdvanceOutcome sameVersion = orchestrator.createPlan("p1", "v2", "v2", List.of(10, 20));
        logOutcome("target==stable", sameVersion);
        assertFalse(sameVersion.success());
        assertEquals(FailureReason.VERSION_CONFLICT, sameVersion.failureReason());

        AdvanceOutcome zeroRatio = orchestrator.createPlan("p2", "v2", "v1", List.of(0, 20));
        logOutcome("比例为0", zeroRatio);
        assertEquals(FailureReason.INVALID_TRAFFIC_RATIO, zeroRatio.failureReason());

        AdvanceOutcome overHundred = orchestrator.createPlan("p3", "v2", "v1", List.of(60, 60));
        logOutcome("累计>100", overHundred);
        assertEquals(FailureReason.INVALID_TRAFFIC_RATIO, overHundred.failureReason());

        AdvanceOutcome empty = orchestrator.createPlan("p4", "v2", "v1", List.of());
        logOutcome("空批次", empty);
        assertEquals(FailureReason.INVALID_TRAFFIC_RATIO, empty.failureReason());

        AdvanceOutcome ok = orchestrator.createPlan("p5", "v2", "v1", List.of(10, 20, 70));
        logOutcome("合法计划", ok);
        assertTrue(ok.success());

        AdvanceOutcome dup = orchestrator.createPlan("p5", "v3", "v1", List.of(50));
        logOutcome("重复planId", dup);
        assertFalse(dup.success());
        assertEquals(FailureReason.VERSION_CONFLICT, dup.failureReason());
        // 原计划未被覆盖
        assertEquals("v2", orchestrator.getPlan("p5").orElseThrow().getTargetVersion());
    }

    // ---------- 顺序推进与已结束计划的边界 ----------

    @Test
    void advanceSequentiallyUntilCompletedThenReject() {
        orchestrator.createPlan("seq", "v2", "v1", List.of(10, 20, 70));
        log.info("=== 输入: 顺序推进 3 批 (10,20,70)，之后再推进一次 ===");

        AdvanceOutcome a1 = orchestrator.advance("seq", "r1");
        logOutcome("批次1", a1);
        assertTrue(a1.success());
        assertEquals(PlanStatus.IN_PROGRESS, a1.status());
        assertEquals(10, a1.cumulativeTrafficPercent());

        AdvanceOutcome a2 = orchestrator.advance("seq", "r2");
        logOutcome("批次2", a2);
        assertEquals(30, a2.cumulativeTrafficPercent());

        AdvanceOutcome a3 = orchestrator.advance("seq", "r3");
        logOutcome("批次3", a3);
        assertEquals(PlanStatus.COMPLETED, a3.status());
        assertEquals(100, a3.cumulativeTrafficPercent());
        assertEquals("v2", a3.currentVersion());

        AdvanceOutcome a4 = orchestrator.advance("seq", "r4");
        logOutcome("完成后推进", a4);
        assertFalse(a4.success());
        assertEquals(FailureReason.PLAN_ALREADY_FINISHED, a4.failureReason());

        AdvanceOutcome missing = orchestrator.advance("no-such-plan", "r5");
        logOutcome("不存在计划", missing);
        assertEquals(FailureReason.PLAN_NOT_FOUND, missing.failureReason());
    }

    // ---------- 幂等：同一 requestId 重复提交 ----------

    @Test
    void duplicateRequestIdReturnsSameOutcomeAndAppliesOnce() {
        orchestrator.createPlan("idem", "v2", "v1", List.of(10, 20));
        log.info("=== 输入: 同一 requestId=req-A 提交 3 次，再用 req-B 提交 1 次 ===");

        AdvanceOutcome first = orchestrator.advance("idem", "req-A");
        AdvanceOutcome second = orchestrator.advance("idem", "req-A");
        AdvanceOutcome third = orchestrator.advance("idem", "req-A");
        logOutcome("req-A 第1次", first);
        logOutcome("req-A 第2次", second);
        logOutcome("req-A 第3次", third);

        assertTrue(first.success());
        assertEquals(first, second);
        assertEquals(first, third);

        ReleasePlan plan = orchestrator.getPlan("idem").orElseThrow();
        log.info("关键状态: batch={} traffic={}% （判定依据: 重复提交只应生效一次）",
                plan.getCurrentBatchIndex(), plan.getCumulativeTrafficPercent());
        assertEquals(1, plan.getCurrentBatchIndex());
        assertEquals(10, plan.getCumulativeTrafficPercent());

        AdvanceOutcome next = orchestrator.advance("idem", "req-B");
        logOutcome("req-B", next);
        assertTrue(next.success());
        assertEquals(30, next.cumulativeTrafficPercent());
    }

    // ---------- 并发推进：只生效批次数次，状态一致 ----------

    @Test
    void concurrentAdvanceAppliesEachBatchExactlyOnce() throws InterruptedException {
        int batches = 3;
        orchestrator.createPlan("conc", "v2", "v1", List.of(10, 20, 30));
        int threads = 16;
        log.info("=== 输入: {} 个线程用不同 requestId 并发推进同一计划（共 {} 批） ===", threads, batches);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<AdvanceOutcome> outcomes = new ConcurrentLinkedQueue<>();
        AtomicInteger reqSeq = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    AdvanceOutcome o = orchestrator.advance("conc", "req-" + reqSeq.getAndIncrement());
                    outcomes.add(o);
                    logOutcome("并发线程", o);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long successes = outcomes.stream().filter(AdvanceOutcome::success).count();
        long finishedRejections = outcomes.stream()
                .filter(o -> o.failureReason() == FailureReason.PLAN_ALREADY_FINISHED).count();
        log.info("关键状态: 成功数={} 已结束拒绝数={} 总数={} （判定依据: 成功数必须等于批次数 {}）",
                successes, finishedRejections, outcomes.size(), batches);

        assertEquals(batches, successes, "每个批次必须恰好生效一次");
        assertEquals(threads - batches, finishedRejections);
        assertEquals(threads, outcomes.size());

        // 成功结果覆盖每个批次恰好一次，流量比例不出现非法中间值
        Set<Integer> batchIndexes = new HashSet<>();
        for (AdvanceOutcome o : outcomes) {
            if (o.success()) {
                assertTrue(batchIndexes.add(o.currentBatchIndex()), "批次被重复推进: " + o.currentBatchIndex());
            }
        }
        assertEquals(Set.of(1, 2, 3), batchIndexes);

        ReleasePlan plan = orchestrator.getPlan("conc").orElseThrow();
        log.info("最终状态: status={} batch={} traffic={}% version={}",
                plan.getStatus(), plan.getCurrentBatchIndex(),
                plan.getCumulativeTrafficPercent(), plan.getCurrentVersion());
        assertEquals(PlanStatus.COMPLETED, plan.getStatus());
        assertEquals(60, plan.getCumulativeTrafficPercent());
        assertEquals("v2", plan.getCurrentVersion());
    }

    // ---------- 并发重复提交：同一 requestId 只生效一次 ----------

    @Test
    void concurrentDuplicateRequestIdAppliesOnce() throws InterruptedException {
        orchestrator.createPlan("conc-idem", "v2", "v1", List.of(10, 20));
        int threads = 12;
        log.info("=== 输入: {} 个线程用同一 requestId=dup-1 并发提交 ===", threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<AdvanceOutcome> outcomes = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    AdvanceOutcome o = orchestrator.advance("conc-idem", "dup-1");
                    outcomes.add(o);
                    logOutcome("重复提交线程", o);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(threads, outcomes.size());
        List<AdvanceOutcome> distinct = new ArrayList<>(new HashSet<>(outcomes));
        log.info("关键状态: 不同结果数={} （判定依据: 所有重复提交必须返回同一结果）", distinct.size());
        assertEquals(1, distinct.size(), "所有重复提交必须返回一致结果");
        assertTrue(distinct.get(0).success());

        ReleasePlan plan = orchestrator.getPlan("conc-idem").orElseThrow();
        log.info("最终状态: batch={} traffic={}%", plan.getCurrentBatchIndex(),
                plan.getCumulativeTrafficPercent());
        assertEquals(1, plan.getCurrentBatchIndex());
        assertEquals(10, plan.getCumulativeTrafficPercent());
    }

    // ---------- 健康失败自动回滚 ----------

    @Test
    void unhealthyBatchTriggersRollbackExactlyOnce() {
        orchestrator.createPlan("rb", "v2", "v1", List.of(10, 20, 30));
        AtomicInteger gateCalls = new AtomicInteger();
        // 本地替换的健康门禁：第 2 批不通过，模拟抖动
        orchestrator.setHealthGate((version, batch, percent) -> {
            boolean healthy = batch != 2;
            gateCalls.incrementAndGet();
            log.info("[健康门禁] version={} batch={} traffic={}% -> {}", version, batch, percent, healthy);
            return healthy;
        });
        log.info("=== 输入: 3 批计划，门禁在第 2 批失败 ===");

        AdvanceOutcome a1 = orchestrator.advance("rb", "r1");
        logOutcome("批次1", a1);
        assertTrue(a1.success());
        assertEquals(10, a1.cumulativeTrafficPercent());

        AdvanceOutcome a2 = orchestrator.advance("rb", "r2");
        logOutcome("批次2(不健康)", a2);
        assertFalse(a2.success());
        assertEquals(FailureReason.HEALTH_CHECK_FAILED, a2.failureReason());

        ReleasePlan plan = orchestrator.getPlan("rb").orElseThrow();
        log.info("回滚后状态: status={} batch={} traffic={}% version={} （判定依据: 必须回到稳定版本 v1、流量 0%）",
                plan.getStatus(), plan.getCurrentBatchIndex(),
                plan.getCumulativeTrafficPercent(), plan.getCurrentVersion());
        assertEquals(PlanStatus.ROLLED_BACK, plan.getStatus());
        assertEquals(0, plan.getCumulativeTrafficPercent());
        assertEquals("v1", plan.getCurrentVersion());

        // 同一 requestId 重试：命中幂等缓存，不会再次触发门禁或回滚
        int callsBefore = gateCalls.get();
        AdvanceOutcome replay = orchestrator.advance("rb", "r2");
        logOutcome("r2 重试", replay);
        assertEquals(a2, replay);
        assertEquals(callsBefore, gateCalls.get(), "幂等重放不得再次调用健康门禁");

        // 新 requestId 推进：计划已结束，拒绝且不再回滚
        AdvanceOutcome after = orchestrator.advance("rb", "r3");
        logOutcome("回滚后新推进", after);
        assertEquals(FailureReason.PLAN_ALREADY_FINISHED, after.failureReason());
        assertEquals(callsBefore, gateCalls.get(), "已回滚计划不得再次触发健康检查");

        // 状态未被误回滚或重复回滚改变
        assertEquals(PlanStatus.ROLLED_BACK, plan.getStatus());
        assertEquals(0, plan.getCumulativeTrafficPercent());
        assertEquals("v1", plan.getCurrentVersion());
    }

    // ---------- 并发推进中健康失败：只回滚一次 ----------

    @Test
    void concurrentAdvanceWithUnhealthyGateRollsBackOnce() throws InterruptedException {
        orchestrator.createPlan("conc-rb", "v2", "v1", List.of(10, 20, 30));
        // 门禁对累计流量 >= 30% 的批次报不健康
        orchestrator.setHealthGate((version, batch, percent) -> {
            boolean healthy = percent < 30;
            log.info("[健康门禁] batch={} traffic={}% -> {}", batch, percent, healthy);
            return healthy;
        });
        int threads = 10;
        log.info("=== 输入: {} 个线程并发推进，门禁在累计流量>=30% 时失败 ===", threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<AdvanceOutcome> outcomes = new ConcurrentLinkedQueue<>();
        AtomicInteger seq = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    AdvanceOutcome o = orchestrator.advance("conc-rb", "cr-" + seq.getAndIncrement());
                    outcomes.add(o);
                    logOutcome("并发线程", o);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long successes = outcomes.stream().filter(AdvanceOutcome::success).count();
        long rollbacks = outcomes.stream()
                .filter(o -> o.failureReason() == FailureReason.HEALTH_CHECK_FAILED).count();
        long finished = outcomes.stream()
                .filter(o -> o.failureReason() == FailureReason.PLAN_ALREADY_FINISHED).count();
        log.info("关键状态: 成功={} 回滚={} 已结束拒绝={} （判定依据: 批次1成功1次、回滚恰好1次、其余拒绝）",
                successes, rollbacks, finished);

        assertEquals(1, successes, "只有第 1 批（10%）能成功");
        assertEquals(1, rollbacks, "回滚必须恰好发生一次");
        assertEquals(threads - 2, finished);

        ReleasePlan plan = orchestrator.getPlan("conc-rb").orElseThrow();
        log.info("最终状态: status={} traffic={}% version={}",
                plan.getStatus(), plan.getCumulativeTrafficPercent(), plan.getCurrentVersion());
        assertEquals(PlanStatus.ROLLED_BACK, plan.getStatus());
        assertEquals(0, plan.getCumulativeTrafficPercent());
        assertEquals("v1", plan.getCurrentVersion());
    }
}
