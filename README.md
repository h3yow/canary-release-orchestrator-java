# Canary Release Orchestrator (Java)

本地可运行的金丝雀发布编排服务。基于 Spring Boot，全部状态保存在内存中，模拟发布计划的创建、分阶段放量、健康门禁与自动回滚，并保证并发推进与重复提交下的状态一致、可解释。

## 运行与验证

```bash
# 运行测试（含并发、幂等、回滚场景，日志打印输入/关键状态/判定依据）
mvn test

# 本地启动（默认 8080 端口）
mvn spring-boot:run
```

### REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/releases` | 创建发布计划 |
| POST | `/api/releases/{planId}/advance` | 推进下一批（幂等） |
| GET  | `/api/releases/{planId}` | 查询计划状态 |

创建计划示例：

```json
{
  "planId": "demo",
  "targetVersion": "v2.0",
  "stableVersion": "v1.0",
  "batchTrafficPercents": [10, 20, 70]
}
```

推进示例（`requestId` 为幂等键）：

```json
{ "requestId": "r1" }
```

所有写操作返回统一的 `AdvanceOutcome`：`success / failureReason / message / planId / status / currentBatchIndex / cumulativeTrafficPercent / currentVersion`，成功与失败共用同一结构，便于幂等重放与结果比对。

## 发布状态模型

```
CREATED --推进成功--> IN_PROGRESS --最后一批成功--> COMPLETED
   |                       |
   +--健康检查失败(任一批)--+--> ROLLED_BACK
```

- `ReleasePlan` 持有：`targetVersion`（目标版本）、`stableVersion`（稳定版本）、`batchTrafficPercents`（每批流量比例）、`status`、`currentBatchIndex`（已完成的批次数）、`currentVersion`。
- 金丝雀累计流量 = 已完成批次比例之和，只取 `0, b1, b1+b2, ...` 这些离散值，不会出现非法中间值；创建时校验每批比例在 `1..100` 且累计不超过 `100`。
- 推进成功：批次号 +1，当前版本切到目标版本；最后一批完成后状态为 `COMPLETED`。
- `COMPLETED` 与 `ROLLED_BACK` 均为终态，之后任何推进都被拒绝（`PLAN_ALREADY_FINISHED`）。

## 失败原因（彼此可区分）

| FailureReason | 触发条件 |
| --- | --- |
| `PLAN_NOT_FOUND` | 推进不存在的计划 |
| `PLAN_ALREADY_FINISHED` | 推进已完成或已回滚的计划 |
| `INVALID_TRAFFIC_RATIO` | 比例不在 1..100、累计超 100、批次为空 |
| `VERSION_CONFLICT` | 目标版本与稳定版本相同/缺失，或 planId 已存在 |
| `HEALTH_CHECK_FAILED` | 健康门禁未通过，已自动回滚 |

## 并发与幂等语义

- **串行化**：每个计划的所有状态变更都在该计划对象的监视器锁内完成。多客户端并发推进同一计划时被串行化，每个批次恰好生效一次，不会丢批、不会覆盖状态。
- **幂等**：推进结果按 `(planId, requestId)` 缓存在 `idempotencyCache` 中，写入与状态变更在同一临界区内完成。同一 `requestId` 重复提交（包括并发重复）只生效一次，并返回与首次完全一致的结果，且不会再次触发健康检查。
- **创建防覆盖**：`plans.putIfAbsent` 保证相同 `planId` 只创建一次，重复创建返回 `VERSION_CONFLICT`，原计划不被覆盖。

## 健康门禁与回滚规则

- 健康门禁是可在本地替换的函数式接口 `HealthGate`：

  ```java
  boolean isHealthy(String targetVersion, int batchIndex, int trafficPercent);
  ```

  默认实现恒健康；测试中通过 `orchestrator.setHealthGate(...)` 注入故障场景。

- 每批放量前调用一次门禁。检查不通过时**在同一临界区内**完成回滚：状态置为 `ROLLED_BACK`、批次清零、当前版本回到稳定版本、金丝雀流量归零。
- 回滚只发生一次：计划进入 `ROLLED_BACK` 终态后，后续推进一律返回 `PLAN_ALREADY_FINISHED`，同一 `requestId` 的重试命中幂等缓存直接回放首次结果——因此同一批的短暂抖动不会被重复回滚，也不会误回滚其他批次。

## 测试覆盖

`src/test/java/.../service/ReleaseOrchestratorTest.java`：

| 测试 | 场景 |
| --- | --- |
| `createPlanRejectsInvalidInputs` | 非法比例、版本冲突、重复 planId |
| `advanceSequentiallyUntilCompletedThenReject` | 顺序推进到完成、终态拒绝、不存在计划 |
| `duplicateRequestIdReturnsSameOutcomeAndAppliesOnce` | 同一 requestId 重复提交 |
| `concurrentAdvanceAppliesEachBatchExactlyOnce` | 16 线程并发推进，每批恰好生效一次 |
| `concurrentDuplicateRequestIdAppliesOnce` | 12 线程并发重复提交同一 requestId |
| `unhealthyBatchTriggersRollbackExactlyOnce` | 第 2 批门禁失败自动回滚，重试不重复回滚 |
| `concurrentAdvanceWithUnhealthyGateRollsBackOnce` | 并发推进中门禁失败，回滚恰好一次 |

测试日志会打印每个请求的输入、判定结果（success/reason/status/流量/版本）以及断言的判定依据，可用 `mvn test` 直接观察。
