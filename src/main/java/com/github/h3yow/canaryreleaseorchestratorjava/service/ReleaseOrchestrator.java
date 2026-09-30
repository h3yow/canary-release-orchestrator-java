package com.github.h3yow.canaryreleaseorchestratorjava.service;

import com.github.h3yow.canaryreleaseorchestratorjava.domain.AdvanceOutcome;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.FailureReason;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.PlanStatus;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.ReleasePlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发布编排核心服务。
 *
 * <p>一致性语义：
 * <ul>
 *   <li>每个计划的所有状态变更都在该计划对象的监视器锁内完成，并发推进被串行化；</li>
 *   <li>推进结果按 (planId, requestId) 缓存，同一请求标识重复提交返回首次的结果，只生效一次；</li>
 *   <li>健康检查失败时在锁内一次性完成回滚，计划进入 ROLLED_BACK 后不再接受推进，
 *       因此同一批不会因抖动被重复回滚。</li>
 * </ul>
 */
@Service
public class ReleaseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOrchestrator.class);
    private static final int MAX_TOTAL_TRAFFIC = 100;

    private final ConcurrentHashMap<String, ReleasePlan> plans = new ConcurrentHashMap<>();
    /** 幂等缓存：planId + 分隔符 + requestId -> 首次推进结果。 */
    private final ConcurrentHashMap<String, AdvanceOutcome> idempotencyCache = new ConcurrentHashMap<>();

    private volatile HealthGate healthGate = (version, batch, percent) -> true;

    /** 替换健康门禁（本地测试用）。 */
    public void setHealthGate(HealthGate healthGate) {
        this.healthGate = healthGate;
    }

    /** 创建发布计划，校验流量比例与版本冲突。 */
    public AdvanceOutcome createPlan(String planId, String targetVersion, String stableVersion,
                                     List<Integer> batchTrafficPercents) {
        if (planId == null || planId.isBlank()) {
            return AdvanceOutcome.fail(FailureReason.PLAN_NOT_FOUND, "planId 不能为空", null);
        }
        if (targetVersion == null || targetVersion.isBlank()
                || stableVersion == null || stableVersion.isBlank()
                || targetVersion.equals(stableVersion)) {
            return AdvanceOutcome.fail(FailureReason.VERSION_CONFLICT,
                    "目标版本与稳定版本缺失或相同: target=" + targetVersion + ", stable=" + stableVersion, null);
        }
        String ratioError = validateRatios(batchTrafficPercents);
        if (ratioError != null) {
            return AdvanceOutcome.fail(FailureReason.INVALID_TRAFFIC_RATIO, ratioError, null);
        }
        ReleasePlan plan = new ReleasePlan(planId, targetVersion, stableVersion, batchTrafficPercents);
        ReleasePlan existing = plans.putIfAbsent(planId, plan);
        if (existing != null) {
            return AdvanceOutcome.fail(FailureReason.VERSION_CONFLICT,
                    "计划已存在，拒绝覆盖: " + planId, existing);
        }
        log.info("[create] planId={} target={} stable={} batches={}", planId, targetVersion,
                stableVersion, batchTrafficPercents);
        return AdvanceOutcome.ok(plan, "计划已创建");
    }

    private String validateRatios(List<Integer> ratios) {
        if (ratios == null || ratios.isEmpty()) {
            return "批次流量比例不能为空";
        }
        int sum = 0;
        for (int i = 0; i < ratios.size(); i++) {
            Integer r = ratios.get(i);
            if (r == null || r <= 0 || r > MAX_TOTAL_TRAFFIC) {
                return "第 " + (i + 1) + " 批流量比例非法: " + r + "（须在 1..100 之间）";
            }
            sum += r;
            if (sum > MAX_TOTAL_TRAFFIC) {
                return "累计流量比例 " + sum + " 超过上限 " + MAX_TOTAL_TRAFFIC;
            }
        }
        return null;
    }

    /**
     * 推进下一批。同一 requestId 重复提交返回首次结果；健康检查失败自动回滚。
     */
    public AdvanceOutcome advance(String planId, String requestId) {
        ReleasePlan plan = plans.get(planId);
        if (plan == null) {
            log.info("[advance] planId={} requestId={} -> PLAN_NOT_FOUND", planId, requestId);
            return AdvanceOutcome.fail(FailureReason.PLAN_NOT_FOUND, "计划不存在: " + planId, null);
        }
        if (requestId == null || requestId.isBlank()) {
            return AdvanceOutcome.fail(FailureReason.INVALID_TRAFFIC_RATIO, "requestId 不能为空", plan);
        }
        String idemKey = planId + "#" + requestId;
        synchronized (plan) {
            AdvanceOutcome cached = idempotencyCache.get(idemKey);
            if (cached != null) {
                log.info("[advance] planId={} requestId={} -> 幂等命中，回放首次结果: success={} status={}",
                        planId, requestId, cached.success(), cached.status());
                return cached;
            }
            AdvanceOutcome outcome = doAdvance(plan, requestId);
            idempotencyCache.put(idemKey, outcome);
            return outcome;
        }
    }

    /** 必须在 plan 的监视器锁内调用。 */
    private AdvanceOutcome doAdvance(ReleasePlan plan, String requestId) {
        if (plan.isFinished()) {
            log.info("[advance] planId={} requestId={} -> PLAN_ALREADY_FINISHED status={}",
                    plan.getPlanId(), requestId, plan.getStatus());
            return AdvanceOutcome.fail(FailureReason.PLAN_ALREADY_FINISHED,
                    "计划已结束，状态=" + plan.getStatus(), plan);
        }
        int nextBatch = plan.getCurrentBatchIndex() + 1;
        int nextCumulative = plan.getCumulativeTrafficPercent()
                + plan.getBatchTrafficPercents().get(plan.getCurrentBatchIndex());
        log.info("[advance] planId={} requestId={} 尝试批次={} 目标累计流量={}% 当前状态={}",
                plan.getPlanId(), requestId, nextBatch, nextCumulative, plan.getStatus());

        boolean healthy = healthGate.isHealthy(plan.getTargetVersion(), nextBatch, nextCumulative);
        if (!healthy) {
            // 自动回滚：一次性回到稳定版本与零金丝雀流量；状态置为 ROLLED_BACK 后不再接受推进，
            // 因此同一批的短暂抖动不会触发第二次回滚。
            plan.markRolledBack();
            log.info("[rollback] planId={} 批次={} 健康检查未通过，已回滚到稳定版本={}，金丝雀流量=0%",
                    plan.getPlanId(), nextBatch, plan.getStableVersion());
            return AdvanceOutcome.fail(FailureReason.HEALTH_CHECK_FAILED,
                    "第 " + nextBatch + " 批健康检查未通过，已自动回滚到稳定版本 " + plan.getStableVersion(), plan);
        }

        plan.markBatchAdvanced();
        boolean done = plan.getStatus() == PlanStatus.COMPLETED;
        log.info("[advance] planId={} requestId={} 批次={} 放量成功，累计流量={}% 状态={}",
                plan.getPlanId(), requestId, nextBatch, plan.getCumulativeTrafficPercent(), plan.getStatus());
        return AdvanceOutcome.ok(plan, done ? "全部批次放量完成" : "第 " + nextBatch + " 批放量成功");
    }

    /** 查询计划。 */
    public Optional<ReleasePlan> getPlan(String planId) {
        return Optional.ofNullable(plans.get(planId));
    }
}
