package com.github.h3yow.canaryreleaseorchestratorjava.tests;

import com.github.h3yow.canaryreleaseorchestratorjava.domain.ErrorCode;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.PlanStatus;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.ReleaseException;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.ReleasePlan;
import com.github.h3yow.canaryreleaseorchestratorjava.service.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布编排核心语义测试：并发推进、重复提交幂等、健康失败回滚、非法边界。
 * 日志打印输入、关键状态与判定依据。
 */
class ReleaseServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ReleaseServiceTest.class);

    private ReleaseService service;

    @BeforeEach
    void setUp() {
        service = new ReleaseService();
    }

    @Test
    void createPlanRejectsInvalidInput() {
        log.info("[case] 非法输入校验：空批次 / 单批越界 / 累计超上限 / 版本相同 / 运行中版本冲突");
        expectError(ErrorCode.INVALID_TRAFFIC_RATIO,
                () -> service.createPlan("v2", "v1", List.of()), "空批次");
        expectError(ErrorCode.INVALID_TRAFFIC_RATIO,
                () -> service.createPlan("v2", "v1", List.of(0, 50)), "单批为 0");
        expectError(ErrorCode.INVALID_TRAFFIC_RATIO,
                () -> service.createPlan("v2", "v1", List.of(101)), "单批超 100");
        expectError(ErrorCode.INVALID_TRAFFIC_RATIO,
                () -> service.createPlan("v2", "v1", List.of(60, 50)), "累计 110 超上限");
        expectError(ErrorCode.VERSION_CONFLICT,
                () -> service.createPlan("v1", "v1", List.of(50)), "目标版本=稳定版本");

        service.createPlan("v2", "v1", List.of(50, 50));
        expectError(ErrorCode.VERSION_CONFLICT,
                () -> service.createPlan("v2", "v9", List.of(10)), "与运行中计划目标版本冲突");
        log.info("[assert] 以上非法输入均被拒绝且错误码可区分 ✔");
    }

    @Test
    void advanceAccumulatesTrafficBatchByBatch() {
        log.info("[case] 分阶段推进：流量按批累计，完成后拒绝继续推进");
        ReleasePlan plan = service.createPlan("v2", "v1", List.of(10, 40, 50));
        log.info("[input] batches={} planId={}", plan.getBatchTrafficPercents(), plan.getId());

        ReleasePlan s1 = service.advance(plan.getId(), "r1");
        log.info("[state] after batch0: canary={}% stable={}% status={}",
                s1.getCanaryTrafficPercent(), s1.getStableTrafficPercent(), s1.getStatus());
        assertEquals(10, s1.getCanaryTrafficPercent());
        assertEquals(90, s1.getStableTrafficPercent());
        assertEquals(PlanStatus.RUNNING, s1.getStatus());

        ReleasePlan s2 = service.advance(plan.getId(), "r2");
        log.info("[state] after batch1: canary={}% status={}", s2.getCanaryTrafficPercent(), s2.getStatus());
        assertEquals(50, s2.getCanaryTrafficPercent());

        ReleasePlan s3 = service.advance(plan.getId(), "r3");
        log.info("[state] after batch2: canary={}% status={}", s3.getCanaryTrafficPercent(), s3.getStatus());
        assertEquals(100, s3.getCanaryTrafficPercent());
        assertEquals(0, s3.getStableTrafficPercent());
        assertEquals(PlanStatus.COMPLETED, s3.getStatus());

        expectError(ErrorCode.PLAN_ALREADY_FINISHED,
                () -> service.advance(plan.getId(), "r4"), "已完成后继续推进");
        expectError(ErrorCode.PLAN_NOT_FOUND,
                () -> service.advance("no-such-plan", "r5"), "不存在的计划");
        log.info("[assert] 流量按批累计 10→50→100，终态推进与不存在的计划均被拒绝 ✔");
    }

    @Test
    void unhealthyBatchTriggersSingleRollback() {
        log.info("[case] 健康门禁失败：自动回滚一次，短暂抖动不得重复回滚");
        AtomicInteger checks = new AtomicInteger();
        // 金丝雀流量达到 60%（即第 2 批）时检查失败一次，随后恢复（模拟短暂抖动）
        service.setHealthChecker((version, percent) -> {
            int n = checks.incrementAndGet();
            boolean ok = percent < 60;
            log.info("[health] check#{} version={} canary={}% -> {}", n, version, percent, ok ? "OK" : "FAIL");
            return ok;
        });
        ReleasePlan plan = service.createPlan("v2", "v1", List.of(20, 40, 40));

        ReleasePlan s1 = service.advance(plan.getId(), "r1");
        assertEquals(20, s1.getCanaryTrafficPercent());
        log.info("[state] batch0 ok: canary={}%", s1.getCanaryTrafficPercent());

        ReleasePlan rolled = service.advance(plan.getId(), "r2");
        log.info("[state] batch1 unhealthy -> status={} canary={}% stable={}% rolledBackBatch={}",
                rolled.getStatus(), rolled.getCanaryTrafficPercent(),
                rolled.getStableTrafficPercent(), rolled.getRolledBackBatchIndex());
        assertEquals(PlanStatus.ROLLED_BACK, rolled.getStatus());
        assertEquals(0, rolled.getCanaryTrafficPercent());
        assertEquals(100, rolled.getStableTrafficPercent());
        assertEquals(1, rolled.getRolledBackBatchIndex());
        assertEquals("v1", rolled.getStableVersion());

        // 抖动恢复后再次推进：计划已终态，必须拒绝而不是二次回滚
        expectError(ErrorCode.PLAN_ALREADY_FINISHED,
                () -> service.advance(plan.getId(), "r3"), "回滚后再次推进");
        ReleasePlan after = service.getPlan(plan.getId());
        assertEquals(PlanStatus.ROLLED_BACK, after.getStatus());
        assertEquals(0, after.getCanaryTrafficPercent());
        assertEquals(1, after.getRolledBackBatchIndex());
        log.info("[assert] 回滚仅发生一次，流量回到稳定版本 100%，终态不可再变 ✔");
    }

    @Test
    void duplicateRequestIdTakesEffectOnlyOnce() {
        log.info("[case] 幂等：同一 requestId 重复提交只生效一次且结果一致");
        ReleasePlan plan = service.createPlan("v2", "v1", List.of(30, 70));

        ReleasePlan first = service.advance(plan.getId(), "req-1");
        ReleasePlan replay = service.advance(plan.getId(), "req-1");
        log.info("[state] first: batch={} canary={}% | replay: batch={} canary={}%",
                first.getCurrentBatchIndex(), first.getCanaryTrafficPercent(),
                replay.getCurrentBatchIndex(), replay.getCanaryTrafficPercent());
        assertEquals(first.getCurrentBatchIndex(), replay.getCurrentBatchIndex());
        assertEquals(first.getCanaryTrafficPercent(), replay.getCanaryTrafficPercent());
        assertEquals(30, service.getPlan(plan.getId()).getCanaryTrafficPercent());

        // 被拒绝的操作重复提交也应返回一致的失败
        service.advance(plan.getId(), "req-2"); // 完成
        expectError(ErrorCode.PLAN_ALREADY_FINISHED,
                () -> service.advance(plan.getId(), "req-3"), "首次拒绝");
        expectError(ErrorCode.PLAN_ALREADY_FINISHED,
                () -> service.advance(plan.getId(), "req-3"), "重复提交同一拒绝");
        log.info("[assert] 重复提交结果一致，流量未被重复推进 ✔");
    }

    @Test
    void concurrentAdvanceKeepsStateConsistent() throws InterruptedException {
        log.info("[case] 并发推进：多客户端同时推进，批次不丢、流量无非法中间值");
        ReleasePlan plan = service.createPlan("v2", "v1", List.of(25, 25, 25, 25));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReleasePlan> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ReleaseException> rejections = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            String requestId = "concurrent-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    results.add(service.advance(plan.getId(), requestId));
                } catch (ReleaseException e) {
                    rejections.add(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int succeeded = results.size();
        int rejected = rejections.size();
        log.info("[state] succeeded={} rejected={} (total={})", succeeded, rejected, threads);
        assertEquals(threads, succeeded + rejected, "每个请求都必须有确定结果");

        // 生效的推进数 = 批次数，其余被 PLAN_ALREADY_FINISHED 拒绝
        assertEquals(4, succeeded, "4 个批次只能各生效一次");
        rejections.forEach(e -> assertEquals(ErrorCode.PLAN_ALREADY_FINISHED, e.getErrorCode()));

        // 成功结果按批次下标应恰好覆盖 0..3，流量为合法累计值 25/50/75/100
        List<Integer> batches = results.stream().map(ReleasePlan::getCurrentBatchIndex).sorted().toList();
        List<Integer> traffics = results.stream().map(ReleasePlan::getCanaryTrafficPercent).sorted().toList();
        log.info("[state] committed batches={} traffics={}", batches, traffics);
        assertEquals(List.of(0, 1, 2, 3), batches);
        assertEquals(List.of(25, 50, 75, 100), traffics);

        ReleasePlan fin = service.getPlan(plan.getId());
        log.info("[state] final: status={} canary={}%", fin.getStatus(), fin.getCanaryTrafficPercent());
        assertEquals(PlanStatus.COMPLETED, fin.getStatus());
        assertEquals(100, fin.getCanaryTrafficPercent());
        log.info("[assert] 并发下批次恰好生效一次、无丢失无重复、流量始终为合法累计值 ✔");
    }

    @Test
    void concurrentDuplicateRequestIdCommitsOnce() throws InterruptedException {
        log.info("[case] 并发 + 重复提交：同一 requestId 并发提交只生效一次");
        ReleasePlan plan = service.createPlan("v2", "v1", List.of(50, 50));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReleasePlan> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    results.add(service.advance(plan.getId(), "same-req"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(threads, results.size());
        for (ReleasePlan r : results) {
            assertEquals(0, r.getCurrentBatchIndex());
            assertEquals(50, r.getCanaryTrafficPercent());
        }
        log.info("[state] all {} responses identical: batch=0 canary=50%", results.size());
        assertEquals(50, service.getPlan(plan.getId()).getCanaryTrafficPercent());
        log.info("[assert] 同一 requestId 并发提交仅推进一批，所有响应一致 ✔");
    }

    private void expectError(ErrorCode code, Runnable action, String scenario) {
        ReleaseException e = assertThrows(ReleaseException.class, action::run);
        log.info("[reject] scenario={} -> errorCode={} message={}", scenario, e.getErrorCode(), e.getMessage());
        assertEquals(code, e.getErrorCode(), "判定依据：错误码必须可区分");
    }
}
