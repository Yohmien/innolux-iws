---
name: iws-plan-event-project
description: 仅在当前 IWS 三仓库内接入、配置、发布或排障计划事件时使用；新业务先评估复用 iws-endpoint/iws-process，并按 INBOUND、OUTBOUND、INTERNAL 逐事件拆分，覆盖权限、幂等与 UNKNOWN/checkpoint 恢复。普通 CRUD、菜单调整和纯架构审查不适用。
---

# IWS 计划事件项目接入

只用于以下目录及子目录：

- `C:/code/company/innolux-iws`
- `C:/code/company/ui/innolux-iws-web`
- `C:/code/company/ui/innolux-iws-pda-plus`

用于业务接入、计划配置、发布、运行与恢复。平台源码优化、全仓架构分析和普通 CRUD 留在当前项目编码流程中。

## 先判定已有能力

截至 2026-10-23，公共平台已交付 `iws-endpoint`、`iws-process`、Job 接线、Web 页面、SQL 和菜单，默认配置仍关闭。完整验收与边界见[公共平台验收记录](../../../docs/plan-event-verification.md)，操作细节见[公共平台操作指南](../../../docs/plan-event-public-platform-guide.md)。

截至 2026-10-23 本次源码核验，主源码中没有生产业务使用的 `EventTypeProvider`、`SingleActionOperation`、业务 `LocalAction`、业务 `StateValidator`、`InboundIdentityPolicy` 或 `ProcessAccessPolicy` 实现；测试 fixture 和 process 内部的监督动作不能当作业务接线。这是核验时点快照，后续接入以当时源码、能力查询和发布校验为准。新增业务先复用已有平台能力，再按实际缺口注册独立 Bean：

1. 读取目标业务代码、当前 Controller 和已登记能力，确认需求能否只通过环境、地址、调度、计划和已注册能力配置完成。
2. 需要代码接入时，推荐先写逐事件表：事件、方向、唯一动作、系统与端点、源/目标状态、分支、完成合同、失败与恢复。明确的小接入可以只列必要字段并直接实施，不另立审批。
3. 每个事件只承担一个 `INBOUND`、一个 `OUTBOUND` 或一个 `INTERNAL` 动作。多调用、多本地动作或异步结果继续拆事件和边。
4. 先经查询入口读取对象和 revision，再写配置或运行命令。真实外部调用、生产写入和调度启停必须处于用户给出的环境、对象和变更范围内。

## 不可省略的事实边界

- 发布版本冻结；修改已发布定义时复制新草稿。配置、定义和人工命令按各自 DTO 取值并只发送该 DTO 声明的字段：相应版本化命令带当前 revision/version、`commandKey` 和中文 `reason`；入站触发不使用 `commandKey`/`expectedVersion`/`reason`，补挂和即时执行只有 `commandKey`，没有 `expectedVersion`/`reason`。
- 入站按来源合同四元组与载荷摘要幂等；结果回调是图中另立的 `INBOUND`，并关联原 `originOutboundEventId`。
- 事件进入 `UNKNOWN` 时先取当前事实、尝试和证据。普通命令没有成功；不得为求继续而改判失败、换命令键或盲重发外部调用。
- 功能权限和对象范围由后端执行。`process:object:all` 只属于明确的管理员全范围授权。

## 按当前动作读取

- 判断方向、设计调用合同、可信入站身份或本地动作：读[业务接入](references/integration.md)。
- 建目录、调度、草稿、状态事件、系统模式、校验或发布：读[配置与发布](references/configuration.md)。
- 挂载、触发、QUEUE/IMMEDIATE、确认、查询、失败处理或恢复：读[运行与恢复](references/operation-recovery.md)。

这些入口按当前动作并列读取；跨方向接入通常依次需要接入方案和配置，只有真正涉及实例、命令或异常时才读运行与恢复。

## 完成标准

接入交付推荐逐个事件说明方向、唯一动作、系统/端点、分支、完成合同和失败恢复；明确小接入可简表。配置方案给出 revision、发布检查和权限；运行或恢复给出可回读的实例、事件、尝试、请求、审计或检查点事实。HTTP 200、页面提交成功或控制意图都不替代领域完成证据。
