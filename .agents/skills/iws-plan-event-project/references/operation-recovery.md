# 运行与恢复

先读实例、事件、链、尝试、请求和审计，再提交命令。query/GET 只读已存事实，不触发重试、外呼或重新校验。提交时逐项遵循对应 DTO：挂载使用任务输入与 `commandKey`，没有 `expectedVersion`/`reason`；入站触发使用来源合同、请求和载荷字段，没有 `commandKey`/`expectedVersion`/`reason`；即时执行使用任务输入与 `commandKey`，没有 `expectedVersion`/`reason`；其他配置、实例、事件和监督命令只在各自 DTO 声明时使用 version 与 `reason`。响应丢失时按该命令的幂等键或来源请求回查。

## 挂载、触发与确认

- `POST /process/instance/attach` 只受理冻结为 `QUEUE` 的任务。body 为调度、计划类型、业务键、任务键、输入摘要、根集合和 `commandKey`；没有 `expectedVersion`/`reason`。`businessType:taskKey` 是幂等键，相同摘要返回原实例，不同摘要拒绝。
- `POST /process/instance/{id}/trigger` 的路径实例必须等于 body 实例。body 使用来源系统、模块、合同键/版本、用途、来源请求、效果键、载荷摘要和可选出站事件关联；没有 `commandKey`/`expectedVersion`/`reason`。可信来源、合同、来源请求和载荷摘要共同构成入站幂等事实；HTTP 回执不等于业务执行成功。
- `POST /process/instance/{id}/confirm-posting` 使用实例、首次确认系统集合、当前 `expectedVersion`、唯一 `commandKey` 和 `reason`。它只用于冻结为 `MANUAL_CONFIRM` 的系统；确认成功后同实例同系统的后续事件不再确认，确认不是外部调用成功证据。

## QUEUE 与 IMMEDIATE

`QUEUE` 通过监督准入和 worker 推进。`IMMEDIATE` 在当前请求同步执行，要求计划名额与动作合同适配；禁止人工确认、`ACCEPTANCE` 结果回调和正数重试。即时执行 DTO 使用调度、计划类型、业务键、任务键、输入摘要和 `commandKey`，没有 `expectedVersion`/`reason`；每个入站事件都必须有唯一、已持久且未绑定的输入回执。

只有即时执行查询的 `resultCode=SUCCEEDED` 表示成功。`PROCESSING`、`BUSY`、`NOT_READY`、监督暂停/忙碌、模式不符、资源未就绪、`RESULT_UNKNOWN`、`RESPONSE_TIMEOUT` 和失败停留都不是成功。

## UNKNOWN 与恢复

触发恢复前先确认：`effectResult=UNKNOWN`，或事件已标记发送且最近检查点为 `SEND_INTENT`/`UNKNOWN`。恢复命令为 `POST /process/event/{id}/retry`，body 使用 `eventId/expectedVersion/commandKey/reason/recoveryOnly`，路径事件 ID 必须与 body 一致；恢复原证据或 QUERY 对账时显式设置 `recoveryOnly=true`。普通 `retry` 不能盲重发未知外部副作用；响应丢失的即时命令先用原 `commandKey` 查询，不能换键重做。

新恢复命令在读取当前事实后生成新的唯一 `commandKey`；该请求响应丢失时，用同一个键和相同载荷回查或重投，不能生成第二个恢复键。前一普通命令已成功返回时，不沿用它的键发起另一项恢复。

显式 QUERY 恢复同时要求原事件无活动租约、实例仍持有计划名额、原事件仍有未释放调用许可，冻结图中恰有一个尚未激活且指向原事件、类型为 `OUTBOUND + QUERY` 的已发布事件，并且业务定义有正数执行预算。服务端只激活该 QUERY 并标为恢复专用，不重发原出站。

已有成功效果时，只有可复用的 `evidenceRef` 或 `checkpointJson`，并且计划定义配置了正数 `maxExecutionMs` 恢复预算，才能按检查点收口；缺少预算时拒绝。成功事件按恢复语义只补编排。没有证据且副作用仍未知时继续对账，不能最终失败、制造成功或重发原调用。

## 监督与完成证据

实例生命周期与监督/事件命令按各自 DTO 使用当前 `expectedVersion`、唯一 `commandKey` 和 `reason`，有额外对象的命令再补该对象字段。暂停、恢复、取消、重启 worker 和切换监督版本表达控制意图，不证明 SnailJob 已应用，也不重放未知事件。完成后结合事件状态、`effectResult`、完成合同、请求、尝试、检查点、证据和下游结果事件判断业务结果；`INTERNAL` 查看本地动作结果、检查点和审计。

对象范围由 `ProcessAccessPolicy` 与功能权限共同决定，缺失策略时普通用户默认拒绝。恢复条件和查询入口详解见[公共平台操作指南](../../../../docs/plan-event-public-platform-guide.md)与[事件命令 Controller](../../../../iws-modules/iws-process/src/main/java/com/innolux/iws/process/controller/PlanEventController.java)。
