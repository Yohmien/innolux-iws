# 计划事件 Web 契约对齐清单

更新：2026-10-23。本清单按当前后端 controller 与 Java record 重新核对定义、执行查询、事件、实例、调度、监督和即时执行。Web 只调用已实现的 controller 路径；未实现的接口不保留前端推算或伪回执。

## 已对齐的定义 API

| 能力         | 实际前端调用                          | 实际请求/响应                                                                                                                       |
| ------------ | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 版本分页     | `GET /process/definition/page`        | `planCode/status/planRole + PageQuery`，`R<PageResult<DefinitionSummaryVo>>`                                                        |
| 版本详情     | `GET /process/definition/{versionId}` | `DefinitionDetailVo`，`summary` 为嵌套对象                                                                                          |
| 编辑能力     | `GET .../edit-capabilities`           | `versionId/revision/editable/readOnlyReason/lockedFields/fields/events`；`fields` 为字段级策略，`events` 为事件编辑、改图、删除能力 |
| 能力目录     | `GET /process/definition/options`     | `eventTypes/callContracts/localActions/validators/branchConditions`，均使用 `key/version`                                           |
| 创建草稿     | `POST /process/definition/draft`      | `{draft: DefinitionDraftBo, commandKey, reason}`                                                                                    |
| 保存基本信息 | `PUT .../base`                        | `{draft, expectedVersion: string, commandKey, reason}`；Java long 接收十进制字符串                                                  |
| 保存状态     | `PUT .../states/{stateCode}`          | `{state: DefinitionStateBo, expectedVersion: string, commandKey, reason}`                                                           |
| 保存事件     | `PUT .../events/{eventCode}`          | `{patch: {event, edges, expectedVersion, catalogRevision?}, commandKey, reason}`                                                    |
| 删除事件     | `DELETE .../events/{eventCode}`       | query 参数 `expectedVersion/commandKey/reason`                                                                                      |
| 外部系统全图 | `GET .../external-systems`            | `ExternalSystemPostingOptionsVO`，含策略系统、展示系统、目录/环境 revision、地址与允许模式                                          |
| 系统策略预览 | `POST .../system-policies/preview`    | `PostingModeVectorBo` -> `PostingImpactVO`                                                                                          |
| 系统策略保存 | `PUT .../system-policies`             | `PostingModeVectorBo` -> `PostingPolicySaveResultVo`                                                                                |
| 当前策略明细 | `GET .../system-policies`             | `SystemPolicyBo[]`；用于补齐非模式字段，不替代全图汇总                                                                              |
| 校验         | `POST .../validate`                   | 无 Request body，返回 `DefinitionValidationVo`                                                                                      |
| 发布         | `POST .../publish`                    | `DefinitionCommandRequest`，返回 `DefinitionValidationVo`                                                                           |
| 复制         | `POST .../copy`                       | `DefinitionCommandRequest`，返回 `DefinitionDetailVo`                                                                               |
| 删除草稿     | `DELETE .../{versionId}`              | query 参数 `expectedVersion/commandKey/reason`                                                                                      |

## 当前真实字段差异

- 详情使用 `summary.versionId/planCode/planName/planType/planVersion/planRole/status/revision/latest/active/publishedAt`。
- `planVersion` 在摘要中是数值；`versionId/revision` 在响应和页面状态中均为字符串，`expectedVersion` 也以十进制字符串提交，避免前端数值精度损失。
- `DefinitionDetailVo` 返回 `selfSystemCode/businessType/businessScheduleCode/mode/activePlanLimit/fairnessWindowSize/maxExecutionMs/completionPolicy/exceptionStateCode/states/events/edges/systemPolicies/roundBatchLimit/roundMaxDurationMs/workerBatchLimit/workerMaxDurationMs/leaseMs/retryMax/retryBackoffMs/scheduleIntervalMs/scheduleCron/jobTimeoutMs/jobMaxRetryTimes/description`。页面展示并提交这些冻结参数；不展示 DTO 未返回的更新人等字段。
- 状态字段为 `stateCode/displayName/stateKind/validatorKey/validatorVersion/conditionDescription/terminal/sortOrder`。
- 事件字段为 `eventCode/displayName/eventRole/eventDirection/eventTypeKey/eventTypeVersion/callContractKey/callContractVersion/operationKind/localActionKey/localActionVersion/effectKey/localEffectKey/systemCode/targetSystemCode/endpointRef/purpose/completionContract/sourceStateCode/targetStateCode/enabled/skipPolicy/skipContractKey/recoveryForEventCode/priority/editPolicy/graphEditPolicy/lockedFields/remark`。
- 边字段为 `predecessorEventCode/successorEventCode/branchKey/branchKind/conditionKey/conditionVersion/defaultBranch/joinCode/priority`；没有独立 branches DTO。
- 能力目录不返回系统目录，创建/编辑时的 `selfSystemCode` 使用稳定码文本输入；最终系统归属仍由后端校验。基础字段和事件字段的禁用状态消费 `fields/events` metadata，旧响应缺少 `fields/events` 时才回退到 `lockedFields`。

## 系统模式页面边界

- 系统模式统一使用 `ExternalSystemPostingDialog`：读取全图汇总与当前策略明细，展示策略归属和 caller-only 展示系统；caller-only 不生成策略行、不随保存提交。
- 保存前必须用当前定义 revision、catalog revision、环境和完整策略向量调用 preview；向量、revision 或目录变化后旧预览失效。保存返回 `PostingPolicySaveResultVo`，以返回 revision 重新读取并重预览，不按 `revision + 1` 推算。
- 从端点目录返回或窗口重新聚焦时重新读取全图 revision，保留用户输入后重新预览。取消 BYPASS 必须显式选择 `AUTOMATIC` 或 `MANUAL_CONFIRM`。
- 没有策略归属但有入站来源的系统只读展示；如果当前没有可编辑策略系统，保存入口禁用。
- 端点地址跳转到既有 `/endpoint/address` 页面；本切片不修改 endpoint 页面、全局 request 或 router。

## Runtime 已对齐

- 执行总览调用 `GET /process/execution/overview`，查询接受 `executed/pending/running/exceptionPageNum/PageSize`；四分类均为 `PageResult<ExecutionPlanVO>`，页面分别读取 `rows/total` 并独立分页。
- `GET /process/instance/{id}/chain` 返回 `PlanChainVO`。未实例化的图事件允许 `eventId/eventVersion=null`，状态展示 `NOT_EVALUATED`；无 eventId 不打开事件详情。chain 与实例摘要返回共享准入描述的 `allowedActions: List<String>`、`reasons`；前端同时校验功能权限。
- 实例、事件审计和 `/event/{eventId}/attempts` 均为 `PageResult`。attempts 使用 `pageNum/pageSize`，默认 1/20、最大 200，抽屉展示分页。单请求由 `/event/{eventId}/requests/{requestId}` 懒加载。
- 请求总览使用 `/process/requests/page`、`/process/requests/{requestId}`，权限为 `process:request:list/query`。列表保留未关联记录；安全详情不含原始报文。事件接口请求页签复用请求分页组件。
- 实例、事件、监督命令 body 使用 controller 对应 command record：实例/事件路径 ID 同时写入 body，优先级字段为 `priority`，监督 expected version 取 `summary.controlRevision`。
- 监督详情是 `SupervisorDetailVO {summary,...}`；当前摘要以 `supervisorInstanceId/controlState/controlRevision/roundNo/heldSlots/allowedActions/actionReason` 为准，轮次和调度记录使用新的扁平 DTO。Controller 中若干修订仍是 Java `Long`，API 适配层统一转成十进制字符串，提交命令时仍发送字符串。
- 即时执行命令为 `scheduleId/planType/businessType/businessId/taskKey/inputHash/commandKey`，不携带输入 JSON、actor 或凭据。只有 `data.resultCode=SUCCEEDED` 成功；网络超时/不明结果只查询原 `commandKey`，不生成新键重放。

## 仍存在的接口缺口

- 调度计划类型目录没有独立查询接口；调度编辑页保留计划类型编码/中文名手工输入，不伪造目录选项。
- 已执行 `pnpm lint`、`pnpm exec vue-tsc --noEmit`、进程范围 `vitest` 和 `pnpm build`；浏览器联调由主代理统一组织。
