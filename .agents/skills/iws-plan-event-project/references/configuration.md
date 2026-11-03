# 配置与发布

写配置前先确认目标环境、当前目录、当前版本和权限。默认配置中 `iws.endpoint.enabled`、`iws.process.enabled`、`iws.process.scheduler.enabled` 均为关闭；启用 process 还要求已登记且启用的当前环境。页面选择环境不会修改 `iws.endpoint.current-environment`。

## 顺序

1. 建系统、环境、共享地址和系统地址关联。
2. 建业务调度并整体保存计划类型；队列公平窗口要与监督冻结定义一致。
3. 从空草稿逐步保存状态、事件、边和完整系统模式向量。
4. 预览并保存系统模式，校验全部错误后发布。

本节配置与定义写命令各有独立 DTO，均带唯一 `commandKey` 和中文 `reason`。修改用当前 body/query 所需的 `expectedVersion` 或 `expectedRevision`；新增按接口要求留空。响应丢失时用同一命令键和相同载荷重投；版本冲突先重读，不能覆盖新 revision。

## 目录与版本

- 环境和系统码创建后稳定不可改；停用阻止新解析和新准入。
- 地址凭证只引用现有安全配置，不把密码或令牌写入 Skill、请求样例、日志或审计摘要。
- `DRAFT` 才可编辑。已发布版本只读；修改时复制更高版本草稿，再重新核对能力、目录 revision 和系统模式。
- 已物化实例保持创建时的定义 revision、状态和系统模式，不随新发布回写。

## 事件与边

单事件必须满足唯一方向与唯一动作。每份事件补丁同时提交与该事件相关的完整边集合；前驱目标状态等于后继源状态，根从 `START` 出发、叶收敛到 `END`，图不能成环。`SEQUENTIAL` 表达顺序，`XOR` 需要条件或唯一默认边，`AND` 汇合共享 `joinCode`。

事件字段按方向取舍：入站绑定一个入站合同；出站绑定外部系统、地址、效果键和调用合同；本地事件只填本地动作与本地效果键。接口返回的可编辑能力和校验器是当前事实，不能复制其他方向的事件后只改方向。

## 系统模式

先读取 `external-systems` 和 `catalogRevision`，对完整系统向量调用 `system-policies/preview`，再用同一向量保存。`BYPASS` 只适用于目标外部系统的 `OUTBOUND + EXTERNAL_POSTING`，不产生外部完成证据；入站、查询、控制和本系统出站不能被屏蔽为成功。`ACCEPTANCE` 出站还必须有结果事件和显式跳过合同。

## 发布与权限

先调用 `validate` 并处理全部错误，再调用 `publish`。`valid=true` 是发布前置条件。发布后回读当前版本、系统模式和计划状态。

配置权限以 Controller 的实际权限码为准，菜单存在不等于角色已授权。配置入口与更多请求字段见[公共平台操作指南](../../../../docs/plan-event-public-platform-guide.md)和[计划定义 Controller](../../../../iws-modules/iws-process/src/main/java/com/innolux/iws/process/controller/PlanDefinitionController.java)。
