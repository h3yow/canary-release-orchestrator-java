package com.github.h3yow.canaryreleaseorchestratorjava.domain;

/** 发布计划生命周期状态。 */
public enum PlanStatus {
    /** 已创建，尚未放量。 */
    CREATED,
    /** 正在分批次放量。 */
    IN_PROGRESS,
    /** 全部批次放量完成。 */
    COMPLETED,
    /** 健康检查失败，已回滚到稳定版本。 */
    ROLLED_BACK
}
