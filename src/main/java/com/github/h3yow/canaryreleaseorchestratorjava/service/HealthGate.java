package com.github.h3yow.canaryreleaseorchestratorjava.service;

/** 健康门禁：每批放量前调用，可在本地替换实现。 */
@FunctionalInterface
public interface HealthGate {

    /**
     * @param targetVersion  待放量的目标版本
     * @param batchIndex     即将放量的批次序号（从 1 开始）
     * @param trafficPercent 该批次完成后金丝雀的累计流量比例
     * @return true 表示健康，允许放量
     */
    boolean isHealthy(String targetVersion, int batchIndex, int trafficPercent);
}
