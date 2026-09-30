package com.github.h3yow.canaryreleaseorchestratorjava.service;

import com.github.h3yow.canaryreleaseorchestratorjava.domain.ErrorCode;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.PlanStatus;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.ReleaseException;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.ReleasePlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发布编排核心服务。
 *
 * 并发与幂等语义：
 *  - 每个计划一把锁，推进 / 回滚在锁内串行执行，状态迁移不会出现中间值；
 *  - 每次推进要求携带 requestId，(planId, requestId) 的首次结果被缓存，
 *    重复提交直接返回首次结果（成功快照或原样抛出首次的拒绝异常），只生效一次。
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final Map<String, ReleasePlan> plans = new ConcurrentHashMap<>();
    private final Map<String, Object> planLocks = new ConcurrentHashMap<>();
    /** 幂等结果缓存：key = planId + ":" + requestId */
    private final Map<String, AdvanceOutcome> advanceOutcomes = new ConcurrentHashMap<>();

    private volatile HealthChecker healthChecker = (version, percent) -> true;

    /** 本地可替换的健康检查实现 */
    public void setHealthChecker(HealthChecker healthChecker) {
        this.healthChecker = healthChecker;
    }

    public ReleasePlan createPlan(String targetVersion, String stableVersion,
                                  List<Integer> batchTrafficPercents) {
        validateVersions(targetVersion, stableVersion);
        validateBatches(batchTrafficPercents);
        String id = UUID.randomUUID().toString();
        ReleasePlan plan = new ReleasePlan(id, targetVersion, stableVersion, batchTrafficPercents);
        plans.put(id, plan);
        planLocks.put(id, new Object());
        log.info("[create] planId={} target={} stable={} batches={}", id, targetVersion, stableVersion, batchTrafficPercents);
        return plan.snapshot();
    }

    /**
     * 推进下一批。同一 requestId 重复提交返回首次结果，只生效一次。
     */
    public ReleasePlan advance(String planId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new ReleaseException(ErrorCode.INVALID_TRAFFIC_RATIO, "requestId must not be blank");
        }
        ReleasePlan plan = findPlan(planId);
        String idemKey = planId + ":" + requestId;

        AdvanceOutcome cached = advanceOutcomes.get(idemKey);
        if (cached != null) {
            log.info("[advance] idempotent replay planId={} requestId={}", planId, requestId);
            return cached.replay();
        }
        Object lock = planLocks.get(planId);
        synchronized (lock) {
            // 双检：等待锁期间可能已有相同 requestId 的请求完成
            cached = advanceOutcomes.get(idemKey);
            if (cached != null) {
                log.info("[advance] idempotent replay (after lock) planId={} requestId={}", planId, requestId);
                return cached.replay();
            }
            try {
                ReleasePlan result = doAdvance(plan);
                advanceOutcomes.put(idemKey, AdvanceOutcome.success(result.snapshot()));
                return result.snapshot();
            } catch (ReleaseException e) {
                advanceOutcomes.put(idemKey, AdvanceOutcome.failure(e));
                throw e;
            }
        }
    }

    private ReleasePlan doAdvance(ReleasePlan plan) {
        if (plan.isFinished()) {
            throw new ReleaseException(ErrorCode.PLAN_ALREADY_FINISHED,
                    "plan " + plan.getId() + " is already " + plan.getStatus());
        }
        int next = plan.nextBatchIndex();
        int targetTraffic = plan.cumulativeTrafficOf(next);
        log.info("[advance] planId={} batch={} targetCanaryTraffic={}%", plan.getId(), next, targetTraffic);

        boolean healthy = healthChecker.check(plan.getTargetVersion(), targetTraffic);
        if (!healthy) {
            plan.rollbackToStable(next);
            log.warn("[rollback] planId={} failedBatch={} canaryTraffic={}% stableVersion={} status={}",
                    plan.getId(), next, plan.getCanaryTrafficPercent(), plan.getStableVersion(), plan.getStatus());
            return plan;
        }
        plan.applyNextBatch();
        log.info("[advanced] planId={} batch={} canaryTraffic={}% status={}",
                plan.getId(), plan.getCurrentBatchIndex(), plan.getCanaryTrafficPercent(), plan.getStatus());
        return plan;
    }

    public ReleasePlan getPlan(String planId) {
        return findPlan(planId).snapshot();
    }

    private ReleasePlan findPlan(String planId) {
        ReleasePlan plan = planId == null ? null : plans.get(planId);
        if (plan == null) {
            throw new ReleaseException(ErrorCode.PLAN_NOT_FOUND, "plan " + planId + " not found");
        }
        return plan;
    }

    private void validateVersions(String targetVersion, String stableVersion) {
        if (targetVersion == null || targetVersion.isBlank()
                || stableVersion == null || stableVersion.isBlank()) {
            throw new ReleaseException(ErrorCode.VERSION_CONFLICT, "versions must not be blank");
        }
        if (targetVersion.equals(stableVersion)) {
            throw new ReleaseException(ErrorCode.VERSION_CONFLICT,
                    "target version equals stable version: " + targetVersion);
        }
        for (ReleasePlan p : plans.values()) {
            if (p.getStatus() == PlanStatus.RUNNING && p.getTargetVersion().equals(targetVersion)) {
                throw new ReleaseException(ErrorCode.VERSION_CONFLICT,
                        "another running plan already targets version " + targetVersion);
            }
        }
    }

    private void validateBatches(List<Integer> batches) {
        if (batches == null || batches.isEmpty()) {
            throw new ReleaseException(ErrorCode.INVALID_TRAFFIC_RATIO, "batches must not be empty");
        }
        int sum = 0;
        for (Integer b : batches) {
            if (b == null || b <= 0 || b > ReleasePlan.TOTAL_TRAFFIC) {
                throw new ReleaseException(ErrorCode.INVALID_TRAFFIC_RATIO,
                        "batch traffic percent must be in (0, 100], got " + b);
            }
            sum += b;
            if (sum > ReleasePlan.TOTAL_TRAFFIC) {
                throw new ReleaseException(ErrorCode.INVALID_TRAFFIC_RATIO,
                        "cumulative traffic " + sum + " exceeds " + ReleasePlan.TOTAL_TRAFFIC);
            }
        }
    }

    /** 幂等结果：成功快照或首次的拒绝异常 */
    private record AdvanceOutcome(ReleasePlan plan, ReleaseException error) {
        static AdvanceOutcome success(ReleasePlan plan) { return new AdvanceOutcome(plan, null); }
        static AdvanceOutcome failure(ReleaseException e) { return new AdvanceOutcome(null, e); }

        ReleasePlan replay() {
            if (error != null) {
                throw error;
            }
            return plan;
        }
    }
}
