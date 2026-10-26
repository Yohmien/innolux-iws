# 业务接入

公共平台和业务能力分开判断。先查当前 Spring 容器、源码和 `GET /process/definition/options`；只有已注册能力无法表达业务事实时才增加实现。验收 fixture 的键、调用和本地写入仅用于说明契约。

## 方向与拆分

| 需求 | 方向 | 事件内容 | 后续 |
| --- | --- | --- | --- |
| 外部系统通知 IWS，或业务节点已到达 | `INBOUND` | 一个调用合同，通常是 `TRIGGER_EVENT` | 推进匹配事件，执行仍按方向动作 |
| IWS 调外部系统 | `OUTBOUND` | 一个调用合同、系统、端点、效果键 | 异步受理后另立结果 `INBOUND` |
| 只在 IWS 本库改状态或计算 | `INTERNAL` | 一个 `SAME_TX` 本地动作和本地效果键 | 写入、检查点和后继激活同事务 |

同一业务有多个外部调用或多个本地动作时，拆成多个事件和边。不要在单事件写多个 operation、`steps[]`、信号槽或同事件回调。多个入站前驱汇合使用独立事件、`AND` 边或同一 `joinCode`。

## 扩展接口

| 接口 | 用途 | 接入核对 |
| --- | --- | --- |
| `EventTypeProvider` | 登记事件类型和支持方向 | `key@version` 唯一、方向准确 |
| `SingleActionOperation` | 执行一个入站或出站逻辑调用 | 合同键/版本、方向、用途、完成合同、资源键 |
| `LocalAction` | 执行一个 `SAME_TX` 本地动作 | 动作键/版本、checkpoint、本地效果键 |
| `StateValidator` | 发布或跳转前校验结构/本库证据 | 精确版本和失败原因 |
| `BranchCondition` | XOR 边的只读条件 | 条件版本、边优先级 |
| `InboundIdentityPolicy` | 把服务端认证上下文绑定到可信来源 | 从请求字段自报身份不可作为授权 |
| `ProcessAccessPolicy` | 实例、事件、调度的对象范围 | 默认拒绝；普通角色不授予全范围 |
| `ImmediateFailureContractProvider` | 声明即时动作的安全失败合同 | 每个可达动作有合同 |
| `ImmediateInputEvidenceProvider` | 返回已持久、未绑定的即时回执 | 按事件和唯一回执读取，不按参数编造 |

能力键和版本直接注册到 `CapabilityRegistry`，重复键启动失败，运行时不回退最新版本。2026-10-23 本次核验只在测试 fixture 中找到业务方向、合同、本地动作和身份策略；生产接入以当时源码、能力查询与发布校验证明 Bean 已注册。process 内部的监督 `LocalAction` 不属于业务扩展。

## 三类动作

- `INBOUND`：合同声明精确 `key/version`、`OperationKind`、`CallPurpose`、`CompletionContract` 和 `callerCandidates`。结果回调使用另一个 `INBOUND + RESULT_CALLBACK`，其 `originOutboundEventId` 指向同实例的原出站事件。
- `OUTBOUND`：合同给出一致的方向、用途、完成合同和非空 `resourceKey`；`ACCEPTANCE` 只表示外部受理，必须安排结果事件。执行实现只完成这一份逻辑调用，返回证据或 `UNKNOWN`，不回写事件、token 或 attempt。
- `INTERNAL`：合同、HTTP 效果键、端点、用途和完成合同为空；动作只写本库一次，不调用 HTTP、第二个动作或生成调用许可。

## 首次外部接单

外部触发不负责创建计划实例：`POST /process/instance/{instanceId}/trigger` 只按现有实例和入站合同匹配事件，不自动挂载、物化或定位业务实例。业务适配层在首次外部接单时必须先明确任务挂载或已存在实例的定位，再把有效 `instanceId` 交给触发入口；无法确定实例时由业务侧先完成挂载或按既有业务事实查找，不能依赖 trigger 隐式建实例。

## 接入验收

先确认能力出现在 `GET /process/definition/options` 和草稿的 `edit-capabilities`，再校验并发布。重点测试重复键、错误版本、可信来源缺失、重复来源键、载荷不一致、结果事件关联、事务回滚和 `UNKNOWN`。fixture 通过不能证明生产 Bean 已接线。

平台源码边界见[能力注册表](../../../../iws-modules/iws-process/src/main/java/com/innolux/iws/process/runtime/CapabilityRegistry.java)、[定义校验](../../../../iws-modules/iws-process/src/main/java/com/innolux/iws/process/support/DefinitionValidator.java)和[公共平台操作指南](../../../../docs/plan-event-public-platform-guide.md)。
