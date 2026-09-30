package com.github.h3yow.canaryreleaseorchestratorjava.domain;

/**
 * 发布计划生命周期状态。
 */
public enum PlanStatus {
    /** 进行中，可继续推进 */
    RUNNING,
    /** 全部批次完成，目标版本承接 100% 流量 */
    COMPLETED,
    /** 健康检查失败，已回滚到稳定版本 */
    ROLLED_BACK
}
