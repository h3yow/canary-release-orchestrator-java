package com.github.h3yow.canaryreleaseorchestratorjava.domain;

import java.util.List;

/**
 * 发布计划：目标版本、稳定版本与分批流量比例。
 *
 * 不变式（由本类的领域方法维护，调用方需在计划级锁内操作）：
 *  - 金丝雀流量 = 已推进批次的流量比例累计值，始终落在 [0, TOTAL_TRAFFIC]；
 *  - 稳定版本流量 = TOTAL_TRAFFIC - 金丝雀流量；
 *  - 回滚只允许发生一次，回滚后金丝雀流量归零、状态终态化。
 */
public class ReleasePlan {

    public static final int TOTAL_TRAFFIC = 100;

    private final String id;
    private final String targetVersion;
    private final String stableVersion;
    /** 每批流量比例（百分比），累计不得超过 TOTAL_TRAFFIC */
    private final List<Integer> batchTrafficPercents;

    private PlanStatus status;
    /** 当前已推进到的批次下标，-1 表示尚未开始 */
    private int currentBatchIndex;
    /** 当前金丝雀（目标版本）承接的累计流量百分比 */
    private int canaryTrafficPercent;
    /** 触发回滚的批次下标，-1 表示未发生过回滚；用于防止同一批被重复回滚 */
    private int rolledBackBatchIndex;

    public ReleasePlan(String id, String targetVersion, String stableVersion,
                       List<Integer> batchTrafficPercents) {
        this.id = id;
        this.targetVersion = targetVersion;
        this.stableVersion = stableVersion;
        this.batchTrafficPercents = List.copyOf(batchTrafficPercents);
        this.status = PlanStatus.RUNNING;
        this.currentBatchIndex = -1;
        this.canaryTrafficPercent = 0;
        this.rolledBackBatchIndex = -1;
    }

    /** 快照拷贝构造，用于幂等结果与对外返回，避免内部状态被后续并发修改影响 */
    private ReleasePlan(ReleasePlan src) {
        this.id = src.id;
        this.targetVersion = src.targetVersion;
        this.stableVersion = src.stableVersion;
        this.batchTrafficPercents = src.batchTrafficPercents;
        this.status = src.status;
        this.currentBatchIndex = src.currentBatchIndex;
        this.canaryTrafficPercent = src.canaryTrafficPercent;
        this.rolledBackBatchIndex = src.rolledBackBatchIndex;
    }

    /** 返回当前状态的不可变快照 */
    public ReleasePlan snapshot() {
        return new ReleasePlan(this);
    }

    public boolean isFinished() {
        return status != PlanStatus.RUNNING;
    }

    /** 下一批的下标，-1 表示没有下一批 */
    public int nextBatchIndex() {
        return currentBatchIndex + 1 < batchTrafficPercents.size() ? currentBatchIndex + 1 : -1;
    }

    /** 推进到 nextIndex 批次后的金丝雀累计流量 */
    public int cumulativeTrafficOf(int batchIndex) {
        int sum = 0;
        for (int i = 0; i <= batchIndex; i++) {
            sum += batchTrafficPercents.get(i);
        }
        return sum;
    }

    /**
     * 推进一个批次（健康检查已通过）。
     */
    public void applyNextBatch() {
        if (isFinished()) {
            throw new ReleaseException(ErrorCode.PLAN_ALREADY_FINISHED,
                    "plan " + id + " is already " + status);
        }
        int next = nextBatchIndex();
        if (next < 0) {
            throw new ReleaseException(ErrorCode.PLAN_ALREADY_FINISHED,
                    "plan " + id + " has no remaining batch");
        }
        int cumulative = cumulativeTrafficOf(next);
        if (cumulative <= 0 || cumulative > TOTAL_TRAFFIC) {
            throw new ReleaseException(ErrorCode.INVALID_TRAFFIC_RATIO,
                    "cumulative traffic " + cumulative + " out of range (0, " + TOTAL_TRAFFIC + "]");
        }
        this.currentBatchIndex = next;
        this.canaryTrafficPercent = cumulative;
        if (next == batchTrafficPercents.size() - 1 && cumulative == TOTAL_TRAFFIC) {
            this.status = PlanStatus.COMPLETED;
        }
    }

    /**
     * 回滚到稳定版本：金丝雀流量归零，状态终态化。
     * 同一批只允许回滚一次，且计划只能回滚一次。
     */
    public void rollbackToStable(int failedBatchIndex) {
        if (status == PlanStatus.ROLLED_BACK) {
            throw new ReleaseException(ErrorCode.PLAN_ALREADY_FINISHED,
                    "plan " + id + " already rolled back at batch " + rolledBackBatchIndex);
        }
        if (isFinished()) {
            throw new ReleaseException(ErrorCode.PLAN_ALREADY_FINISHED,
                    "plan " + id + " is already " + status + ", cannot roll back");
        }
        this.rolledBackBatchIndex = failedBatchIndex;
        this.canaryTrafficPercent = 0;
        this.status = PlanStatus.ROLLED_BACK;
    }

    public String getId() { return id; }
    public String getTargetVersion() { return targetVersion; }
    public String getStableVersion() { return stableVersion; }
    public List<Integer> getBatchTrafficPercents() { return batchTrafficPercents; }
    public PlanStatus getStatus() { return status; }
    public int getCurrentBatchIndex() { return currentBatchIndex; }
    public int getCanaryTrafficPercent() { return canaryTrafficPercent; }
    public int getStableTrafficPercent() { return TOTAL_TRAFFIC - canaryTrafficPercent; }
    public int getRolledBackBatchIndex() { return rolledBackBatchIndex; }
}
