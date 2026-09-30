package com.github.h3yow.canaryreleaseorchestratorjava.service;

/**
 * 健康门禁：每批放量前调用一次，可在本地替换实现。
 */
@FunctionalInterface
public interface HealthChecker {

    /**
     * @param version             待放量的目标版本
     * @param canaryTrafficPercent 放量后金丝雀将承接的累计流量百分比
     * @return true 表示健康，允许放量；false 触发自动回滚
     */
    boolean check(String version, int canaryTrafficPercent);
}
