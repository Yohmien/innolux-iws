# process 并行写集与交付边界（2026-10-22）

## 定义层已移交主代理（本代理停止修改）

- `src/main/java/com/innolux/iws/process/service/impl/PlanDefinitionServiceImpl.java`
- `src/main/java/com/innolux/iws/process/support/DefinitionValidator.java`
- `src/main/java/com/innolux/iws/process/support/EditCapabilityResolver.java`
- `src/main/java/com/innolux/iws/process/controller/PlanDefinitionController.java`
- definition 相关 API DTO：`DefinitionDraftBo`、`DefinitionEventBo`、`DefinitionStateBo`、`DefinitionEdgeBo`、`SystemPolicyBo`、`PostingModeVectorBo`、`DefinitionSummaryVo`、`DefinitionDetailVo`、`DefinitionValidationVo`、`EditCapabilitiesVo`、`DefinitionEventPatchBo`、`DraftCreateRequest`、`DraftSaveRequest`、`StateSaveRequest`、`EventSaveRequest`、`DefinitionCommandRequest`、`CapabilityOptionsVO`、`ExternalSystemPostingOptionsVO`、`ExternalSystemRowVO`、`PostingImpactVO`。
- `service/CommandReceiptService` 为共享回执组件，主代理可补定义层接入；签名变化需同步 runtime。

主代理负责：定义命令回执完整接入、目录锁序 `catalog → definition`、完整图/能力门禁、D01/D02/D10/D15/D25 定义侧测试。移交时 `PlanDefinitionServiceImpl` 已改为目录先锁、definition 后锁，并在保存后调用完整图校验；最后一次加回执 overload 后未重新编译。

## runtime/本代理持有

- `runtime/**`：`RuntimeDao`、`PlanMaterializer`、`EventEligibilityService`、`BranchSelector`、`EndpointTargetResolver`、`EventMutationService`、`EventUpdateService`、`PlanActivationService`、`SupervisorRuntimeService`（进行中）、`SupervisorTickService`/`PlanEventWorkerService` 实现、`ImmediateExecutionService`。
- 公共 `config/**`、`domain/**`、`mapper/**`、`enums/**`、`api` 中的 job/instance 运行契约。
- 运行侧测试：CAS 单赢、UNKNOWN 防盲重放、BYPASS/Skip 零动作、INTERNAL SAME_TX、AND 汇合、QUEUE 名额、IMMEDIATE 幂等/忙、平台结果 CAS。

当前 runtime 已落：事件认领/租约、OUTBOUND T2 CALL + SEND_INTENT、结果检查点、成功/失败终态、后继激活、计划聚合、同事务 LocalAction、分支决策、目录目标解析。已确认需继续补：挂载/触发/首次确认、RETRY_WAIT 到期认领与恢复命令、查询读模型、Supervisor tick/worker、Immediate、D 系列测试。

## 可拆给第二实施代理

写集：`service/instance/**`、`controller/PlanInstanceController.java`、`controller/ProcessTriggerController.java`、`controller/ExecutionOverviewController.java`、`controller/PlanEventController.java`、`controller/SupervisorController.java`、`api/instance/**`（新 DTO）。

只调用本代理稳定 runtime 接口：

- `SupervisorRuntimeService.ensureSupervisor(scheduleId, planType, scope)`
- `SupervisorRuntimeService.admit(child, supervisor, supervisorDefinition)`
- `PlanMaterializer.materialize(instance)`
- `EventEligibilityService.recompute(eventId)` / `recomputeInstance(instanceId)`
- `EventUpdateService.update(eventId, owner)`
- `RuntimeDao` 只读方法
- `ProcessSchedulerPort` 与 `PlatformResultCommand`

不得修改 `runtime/**`、实体或 Mapper；跨模块调用仍走 HTTP。

## 数据库契约修正

`iws_process_instance` 不再使用 `(schedule_id,plan_type,schedule_scope,plan_role)` 唯一键，改为：

- `supervisor_key` VARCHAR(256) NULL，UNIQUE；仅 SUPERVISOR 非空（`scheduleId:planType:scope`）。
- `business_task_key` VARCHAR(256) NULL，UNIQUE；仅 BUSINESS 非空（`businessType:taskKey`）。

该修正保证同 scope 的 QUEUE 多任务可挂载；SQL 侧需按此调整并做 D35/D36 隔离断言。

## D47 当前状态

- 所有 process `@Service/@Component/@RestController` 已加 `iws.process.enabled` 条件；含 endpoint 类型的 Bean 另加 `@ConditionalOnClass`。
- `ProcessDisabledBeanFilter` 在关闭时移除全局 `com.innolux.iws.**.mapper` 扫描注册的 process BeanDefinition。
- `ProcessConfiguration` 启动门禁已检查 current environment 存在且 enabled。
- 仍缺 `FilteredClassLoader` 真实测试与 BeanDefinition 矩阵断言。
