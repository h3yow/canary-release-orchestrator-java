package com.github.h3yow.canaryreleaseorchestratorjava.domain;

/** 一次推进/回滚操作的结果，成功与失败共用同一结构便于幂等回放。 */
public record AdvanceOutcome(
        boolean success,
        FailureReason failureReason,
        String message,
        String planId,
        PlanStatus status,
        int currentBatchIndex,
        int cumulativeTrafficPercent,
        String currentVersion) {

    public static AdvanceOutcome ok(ReleasePlan plan, String message) {
        return new AdvanceOutcome(true, null, message, plan.getPlanId(), plan.getStatus(),
                plan.getCurrentBatchIndex(), plan.getCumulativeTrafficPercent(), plan.getCurrentVersion());
    }

    public static AdvanceOutcome fail(FailureReason reason, String message, ReleasePlan plan) {
        return new AdvanceOutcome(false, reason, message,
                plan == null ? null : plan.getPlanId(),
                plan == null ? null : plan.getStatus(),
                plan == null ? 0 : plan.getCurrentBatchIndex(),
                plan == null ? 0 : plan.getCumulativeTrafficPercent(),
                plan == null ? null : plan.getCurrentVersion());
    }
}
