# Canary Release Orchestrator（本地发布编排服务）

基于 Spring Boot 的本地可运行金丝雀发布编排服务，用内存状态模拟发布全过程：
分阶段推进、健康门禁、自动回滚，并保证并发推进与重复提交下状态一致、可解释。

## 发布状态模型

一个发布计划（`ReleasePlan`）包含：

| 字段 | 含义 |
| --- | --- |
| `targetVersion` / `stableVersion` | 目标（金丝雀）版本 / 当前稳定版本 |
| `batchTrafficPercents` | 每批流量比例（百分比），累计不得超过 100 |
| `currentBatchIndex` | 已推进到的批次下标，-1 表示尚未开始 |
| `canaryTrafficPercent` | 金丝雀承接的累计流量 = 已推进批次比例之和 |
| `stableTrafficPercent` | 稳定版本流量 = 100 - 金丝雀流量 |
| `rolledBackBatchIndex` | 触发回滚的批次下标，-1 表示未回滚过 |

状态机（`PlanStatus`）：

```
RUNNING --(批次推进, 健康检查通过)--> RUNNING --(最后一批且累计 100%)--> COMPLETED
RUNNING --(批次健康检查失败)--> ROLLED_BACK   （金丝雀流量归零）
```

- `COMPLETED` 与 `ROLLED_BACK` 均为终态，任何推进都会被拒绝（`PLAN_ALREADY_FINISHED`）。
- 不变式：金丝雀流量始终是批次比例的累计值，落在 `(0, 100]` 或回滚后的 `0`，不会出现非法中间值。

## 失败原因（彼此可区分）

| 错误码 | HTTP | 触发场景 |
| --- | --- | --- |
| `PLAN_NOT_FOUND` | 404 | 推进/查询不存在的计划 |
| `PLAN_ALREADY_FINISHED` | 409 | 推进已完成或已回滚的计划 |
| `INVALID_TRAFFIC_RATIO` | 400 | 空批次、单批比例不在 (0,100]、累计超 100 |
| `VERSION_CONFLICT` | 400 | 目标版本=稳定版本，或与运行中计划目标版本冲突 |

## 并发与幂等语义

- **计划级串行化**：每个计划一把锁，推进与回滚在锁内完成，状态迁移原子生效，
  并发推进不会丢批次、不会覆盖状态、不会出现流量的非法中间值。
- **请求级幂等**：每次推进必须携带 `requestId`。`(planId, requestId)` 的首次结果
  （成功快照或拒绝异常）被缓存；重复提交（包括并发重复提交）直接返回首次结果，
  只生效一次，且所有响应内容一致。
- 并发下对同一计划的 N 次推进（不同 `requestId`）：恰好每个批次生效一次，
  超出批次数的请求被 `PLAN_ALREADY_FINISHED` 拒绝，每个请求都有确定结果。

## 健康门禁与回滚规则

- 每批放量前调用一次 `HealthChecker.check(version, canaryTrafficPercent)`，
  该接口是函数式接口，可在本地通过 `ReleaseService.setHealthChecker(...)` 替换实现。
- 检查不通过 → 自动回滚：金丝雀流量归零（稳定版本回到 100%），状态置为
  `ROLLED_BACK`，并记录 `rolledBackBatchIndex`。
- 回滚只发生一次：回滚后计划进入终态，短暂抖动恢复后的再次推进会被拒绝，
  不会重复回滚或误回滚；回滚后的流量分布与版本状态保持可解释、可对账。

## REST 接口

```
POST   /api/releases                 创建计划  {"targetVersion":"v2","stableVersion":"v1","batchTrafficPercents":[10,40,50]}
POST   /api/releases/{id}/advance    推进下一批 {"requestId":"req-1"}
GET    /api/releases/{id}            查询计划状态
```

## 本地验证

```bash
# 运行全部测试（并发推进、重复提交、健康失败回滚、非法边界）
mvn test

# 本地启动（server.port=0，随机端口，见启动日志）
mvn spring-boot:run
```

测试日志会打印输入（`[input]`/`[case]`）、关键状态（`[state]`/`[health]`/`[rollback]`）
与判定依据（`[assert]`/`[reject]`），可直接从日志核对每次状态迁移的理由。
