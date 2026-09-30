package com.github.h3yow.canaryreleaseorchestratorjava.domain;

import java.util.List;

/** 发布计划：目标版本、稳定版本与每一批的流量比例。 */
public class ReleasePlan {

    private final String planId;
    private final String targetVersion;
    private final String stableVersion;
    private final List<Integer> batchTrafficPercents;

    private PlanStatus status;
    private int currentBatchIndex; // 已完成推进的批次数
    private String currentVersion;

    public ReleasePlan(String planId, String targetVersion, String stableVersion,
                       List<Integer> batchTrafficPercents) {
        this.planId = planId;
        this.targetVersion = targetVersion;
        this.stableVersion = stableVersion;
        this.batchTrafficPercents = List.copyOf(batchTrafficPercents);
        this.status = PlanStatus.CREATED;
        this.currentBatchIndex = 0;
        this.currentVersion = stableVersion;
    }

    public String getPlanId() { return planId; }
    public String getTargetVersion() { return targetVersion; }
    public String getStableVersion() { return stableVersion; }
    public List<Integer> getBatchTrafficPercents() { return batchTrafficPercents; }
    public PlanStatus getStatus() { return status; }
    public int getCurrentBatchIndex() { return currentBatchIndex; }
    public String getCurrentVersion() { return currentVersion; }

    /** 当前金丝雀累计流量比例：已完成批次按比例累计。 */
    public int getCumulativeTrafficPercent() {
        int sum = 0;
        for (int i = 0; i < currentBatchIndex && i < batchTrafficPercents.size(); i++) {
            sum += batchTrafficPercents.get(i);
        }
        return sum;
    }

    /** 计划是否已结束（完成或已回滚），结束后不可再推进。 */
    public boolean isFinished() {
        return status == PlanStatus.COMPLETED || status == PlanStatus.ROLLED_BACK;
    }

    /** 推进到下一批：批次号 +1，当前版本切到目标版本，必要时标记完成。 */
    public void markBatchAdvanced() {
        this.currentBatchIndex++;
        this.currentVersion = targetVersion;
        this.status = (currentBatchIndex == batchTrafficPercents.size())
                ? PlanStatus.COMPLETED : PlanStatus.IN_PROGRESS;
    }

    /** 回滚：回到稳定版本，金丝雀流量清零，状态置为已回滚。 */
    public void markRolledBack() {
        this.status = PlanStatus.ROLLED_BACK;
        this.currentBatchIndex = 0;
        this.currentVersion = stableVersion;
    }
}
