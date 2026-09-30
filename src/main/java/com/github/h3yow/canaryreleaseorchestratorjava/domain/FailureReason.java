package com.github.h3yow.canaryreleaseorchestratorjava.domain;

/** 彼此可区分的失败原因。 */
public enum FailureReason {
    PLAN_NOT_FOUND,
    PLAN_ALREADY_FINISHED,
    INVALID_TRAFFIC_RATIO,
    VERSION_CONFLICT,
    HEALTH_CHECK_FAILED
}
