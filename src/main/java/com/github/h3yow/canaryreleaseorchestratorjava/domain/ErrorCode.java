package com.github.h3yow.canaryreleaseorchestratorjava.domain;

/**
 * 可彼此区分的失败原因。
 */
public enum ErrorCode {
    PLAN_NOT_FOUND,
    PLAN_ALREADY_FINISHED,
    INVALID_TRAFFIC_RATIO,
    VERSION_CONFLICT
}
