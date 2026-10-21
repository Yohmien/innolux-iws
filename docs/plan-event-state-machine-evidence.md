# 计划事件状态机：IWS 复用证据与数据库快照

> 核验日期：2026-10-21
>
> IWS 源码基线：`57391dda3e8971cca434791686ebde690a9937b0`（`2026-10-09`）
>
> 旧 PCBA 参考基线：`fc286f0`；本文记录旧源实现、IWS 复用能力和目标库快照。
> 关联文档：[计划事件状态机主方案](plan-event-state-machine-design.md)、[时序图](plan-event-state-machine-sequences.md)、[模块复用与新增实施计划](plan-event-state-machine-implementation-plan.md)。

## 结论

IWS 已有可直接复用的调度客户端、执行器注册方式、MyBatis-Plus 条件更新和乐观锁、Sa-Token 权限、数据字典、操作日志及 Warm-Flow 审批能力。本次不需要新建 Quartz、第二套工作流引擎或通用调度平台。

建议只新增一个业务模块 `iws-modules/iws-process`，内部按 `api/definition/runtime/support` 分包；固定执行器复用现有 job 接线，SnailJob 动态管理通过模块内部 `SchedulerAdapter` 薄适配器接入。最终职责按主方案执行，本文的库 API 证据不构成第二份实现规格。

当前 `iws-admin` 的 dev 配置关闭了 SnailJob 客户端，这不是方案阻断。实施时按部署窗口开启并验证执行器注册即可；本文不改任何配置。

## 1. 检索范围与证据方法

后端仓库未发现可用 `.codegraph` 索引，因此使用精确 `rg` 和逐文件读取源码。核验范围包括：

- `iws-modules/iws-job`
- `iws-common/iws-common-job`
- `iws-extend/iws-snailjob-server`
- `iws-modules/iws-workflow`
- `iws-common/iws-common-mybatis`
- `iws-modules/iws-system` 中的字典、权限和日志入口
- 根 `pom.xml`、`iws-modules/pom.xml`、`iws-admin/pom.xml`
- SnailJob 2.0.2 客户端和服务端编译产物中的公开方法签名

## 2. SnailJob 现有能力

### 2.1 依赖与客户端启动

| 证据 | 事实 |
| --- | --- |
| `pom.xml:56` | 统一使用 SnailJob `2.0.2`。 |
| `pom.xml:309-322` | 统一管理 `snail-job-client-starter`、`snail-job-client-job-core` 等客户端依赖。 |
| `iws-common/iws-common-job/pom.xml:37-46` | `iws-common-job` 引入 SnailJob 客户端 starter 和 job core。 |
| `iws-common/.../SnailJobConfig.java:32-35` | 自动配置只在 `snail-job.enabled=true` 时启用，并执行 `@EnableSnailJob`。 |
| `iws-admin/pom.xml:97`、`121` | 主应用已聚合 `iws-job` 和 `iws-workflow`。 |
| `iws-admin/src/main/resources/application-dev.yml:24-25` | dev 当前关闭 SnailJob 客户端；后续可开启，本次不改配置。 |
| `iws-admin/src/main/resources/application-prod.yml:27-28` | prod 同样当前关闭；部署时需单独确认。 |

这些证据说明客户端接入点已经存在，但没有说明生产调度服务已经在运行。

### 2.2 固定 Java 执行器写法

`iws-job` 依赖 `iws-common-job`，并使用 `@JobExecutor(name=...)` 声明固定执行器：

- `iws-modules/iws-job/pom.xml:29-41`
- `iws-modules/iws-job/src/main/java/com/innolux/iws/job/snailjob/TestAnnoJobExecutor.java:27-40`
- `iws-modules/iws-job/src/main/java/com/innolux/iws/job/snailjob/AlipayBillTask.java:33-53`

这些现有执行器分别返回测试成功结果或模拟账单上下文，没有连接任何计划事件业务。因此可以复用“固定名称执行器 + `JobArgs` 参数”的接入方式，不能把样例类当作现成业务 API。

### 2.3 动态增删改、触发和启停能力

SnailJob 2.0.2 客户端编译产物中已经提供动态管理 API。以下内容是依赖库的公开签名，不是 IWS 当前业务接口：

| 类型 | 已核验方法或能力 | 建议用途 |
| --- | --- | --- |
| `com.aizuda.snailjob.client.job.core.openapi.JobOpenApiClientV2` | `addJobByBizId`、`updateJobByBizId`、`getJobDetailByBizId`、`triggerJobByBizId`、`updateJobStatusByBizId`、`existsJobByBizId` | 管理 BusinessSchedule 控制任务及其 worker 物理任务，不创建各计划独立取任务的入口。 |
| `com.aizuda.snailjob.client.job.core.handler.add.ClusterAddBizIdHandler` | 构造器 `(String)`；参数设置方法来自 `AbstractParamsBizIdHandler`，`execute()` 来自 `AbstractRequestHandler` | 创建复用固定执行器的集群任务。 |
| `com.aizuda.snailjob.client.job.core.handler.update.ClusterUpdateBizIdHandler` | 构造器 `(String)`，按 `bizId` 更新任务定义 | 同步业务调度参数、执行器或 Cron。 |
| `com.aizuda.snailjob.client.job.core.handler.update.UpdateJobStatusBizIdHandler` | 构造器 `(String)`；`setStatus(StatusEnum)` | 开启或暂停物理任务。 |
| `com.aizuda.snailjob.client.job.core.handler.trigger.ClusterTriggerBizIdHandler` | 构造器 `(String)`；`addArgsStr(String, Object)`，继承 `execute()` | 请求立即触发；worker 仍检查业务总调度的许可范围。 |
| `com.aizuda.snailjob.common.core.enums.StatusEnum` | `NO=0`、`YES=1` | 与 `sj_job.job_status` 的“0关闭、1开启”一致。 |

上述 API 和 handler 位于 job-core，`StatusEnum` 位于 common-core；继承的请求执行方法位于 client-common。实际核验使用的本机 JAR 路径如下，均为依赖缓存，不含环境凭据：

```text
C:\Users\yohmi\.m2\repository\com\aizuda\snail-job-client-job-core\2.0.2\snail-job-client-job-core-2.0.2.jar
C:\Users\yohmi\.m2\repository\com\aizuda\snail-job-client-common\2.0.2\snail-job-client-common-2.0.2.jar
C:\Users\yohmi\.m2\repository\com\aizuda\snail-job-common-core\2.0.2\snail-job-common-core-2.0.2.jar
C:\Users\yohmi\.m2\repository\com\aizuda\snail-job-common-model\2.0.2\snail-job-common-model-2.0.2.jar
```

2026-10-21 前序探索已实际执行 `javap -classpath '<以分号连接的上述 JAR 路径>' -p '<完整类名>'`，读取表中各类型及以下继承链类型的公开/受保护签名；`StatusEnum` 还实际执行了 `-c -p`，从静态初始化字节码核对 0/1 值。本轮文档修订复用该结果，没有重新执行命令，也没有调用动态管理 API。可复核命令如下（将前序逐类命令合并展示）：

```powershell
$taskJarRoot = 'C:\Users\yohmi\.m2\repository\com\aizuda'
$taskJarVersion = '2.0.2'
$taskJarNames = @(
    'snail-job-client-job-core', 'snail-job-client-common',
    'snail-job-common-core', 'snail-job-common-model'
)
$taskClasspath = ($taskJarNames | ForEach-Object {
    Join-Path $taskJarRoot "$_\$taskJarVersion\$($_)-$taskJarVersion.jar"
}) -join ';'
$taskClasses = @(
    'com.aizuda.snailjob.client.job.core.openapi.JobOpenApiClientV2',
    'com.aizuda.snailjob.client.job.core.handler.add.ClusterAddBizIdHandler',
    'com.aizuda.snailjob.client.job.core.handler.update.ClusterUpdateBizIdHandler',
    'com.aizuda.snailjob.client.job.core.handler.update.UpdateJobStatusBizIdHandler',
    'com.aizuda.snailjob.client.job.core.handler.trigger.ClusterTriggerBizIdHandler',
    'com.aizuda.snailjob.client.job.core.handler.AbstractParamsBizIdHandler',
    'com.aizuda.snailjob.client.job.core.handler.AbstractJobRequestHandler',
    'com.aizuda.snailjob.client.common.rpc.openapi.AbstractRequestHandler'
)
foreach ($taskClass in $taskClasses) {
    javap -classpath $taskClasspath -p $taskClass
}
javap -classpath $taskClasspath -c -p 'com.aizuda.snailjob.common.core.enums.StatusEnum'
```

这些证据没有可引用的源码文件行号，不将字节码偏移伪称源码行号；其依赖版本由根 POM 固定为 `2.0.2`，升级时应重新核对签名。方法存在及数据库唯一键不单独证明并发创建、失败补偿或线上可用性。

当前 IWS 源码中没有以下内容：

- 面向业务层的调度管理 Controller 或 Service；
- 调用上述 OpenAPI 的业务代码；
- 计划事件专用执行器；
- 自动启停协调器。

所以当前状态应表述为“底层客户端具备动态管理方法，IWS 尚无业务可调用 API”。新增的 `SchedulerAdapter` 仅为 `iws-process` 模块内部薄适配器，不另建公共调度平台。

### 2.4 SnailJob 服务端

| 证据 | 事实 |
| --- | --- |
| `iws-extend/iws-snailjob-server/pom.xml:26-37` | 单独引入 `snail-job-server-starter`。 |
| `iws-extend/iws-snailjob-server/.../SnailJobServerApplication.java:23-32` | 独立启动 SnailJob Server。 |
| `iws-extend/iws-snailjob-server/src/main/resources/application.yml:10-17` | Server 使用独立 Web 端口和上下文路径。 |
| 服务端源码目录 | 只有启动类及 Actuator 鉴权过滤器，没有自定义任务管理 Controller。 |

服务端的管理和 OpenAPI 来自 SnailJob 依赖，不应在 IWS 中重复实现一套任务管理平台。

## 3. MyBatis-Plus、权限、字典和日志复用

### 3.1 条件更新和乐观锁

`iws-common/iws-common-mybatis/pom.xml:57` 引入 MyBatis-Plus starter；`pom.xml:223-245` 固定 MyBatis-Plus `3.5.17`。

`iws-common/iws-common-mybatis/.../MybatisPlusConfig.java:56-65` 已注册拦截器链，`:94-99` 明确注册 `OptimisticLockerInnerInterceptor`。现有 `TestTree.java:67`、`TestDemo.java:70` 展示了 `@Version` 注解用法。

计划事件认领和状态迁移应优先使用以下已有能力：

1. `LambdaUpdateWrapper` 显式条件更新：`id + status + version`；
2. `@Version` 乐观锁或等价显式版本条件；
3. 以影响行数是否为 1 判断认领是否成功；
4. 在业务事务内完成“事件状态 + 控制投影 + 审计”一致更新。

这样不需要新增 Redisson 自研锁、租约表之外的锁服务或分布式调度框架。若某次状态迁移跨越外部调用，外部调用前后应分成两个短事务，并以稳定事件键、载荷摘要和版本 CAS 防止重复副作用。

### 3.2 权限、重复提交和审计

`iws-modules/iws-workflow/.../FlwTaskController.java:58-76` 已使用 `@Log`、`@RepeatSubmit` 包裹启停类写操作；`:110-124`、`:199-201`、`:247-251` 使用 `@SaCheckPermission`。`iws-common/iws-common-log/.../LogAspect.java:49-76` 是现有操作日志切面。

计划事件的暂停、恢复、立即触发和待人工命令应沿用这些现有入口：

- 后端权限：`@SaCheckPermission("process:event:...")`；
- 操作审计：`@Log(title = "计划事件", businessType = ...)`；
- 页面防重复：`@RepeatSubmit` 仅用于重复点击保护；
- 真正幂等：数据库状态、版本条件和唯一业务键。

现有操作日志更适合记录 HTTP 入口、登录用户、请求结果和耗时；它不能单独证明定时 worker、分支选择、跳过、外部调用前后检查点或系统自动恢复。因此新增领域动作审计表是必要扩展，并与现有操作日志通过请求 ID/实例/事件键关联。

### 3.3 字典

`iws-common/iws-common-core/.../DictService.java:25-96` 提供字典标签、值和字典项读取；`iws-modules/iws-system/.../SysDictTypeServiceImpl.java:56` 实现该接口，`:119-120`、`:244-273` 提供按类型查询和值标签互转。

事件类型、状态、失败类别和暂停原因应复用现有字典体系，并让运行状态保持稳定字符串或状态码。字典用于显示和配置，不能替代状态机校验。

## 4. Warm-Flow 的复用边界

`iws-modules/iws-workflow/pom.xml:98-113` 引入 Warm-Flow 和 LiteFlow。`iws-admin/src/main/resources/application.yml:244-247` 当前开启 Warm-Flow。

现成调用入口：

- `FlwTaskController.java:60-76`：启动流程、办理任务；
- `FlwTaskController.java:171-173`：终止任务；
- `FlwTaskServiceImpl.java:124-145`：启动和办理分别有事务与 Lock4j 锁；
- `FlwTaskServiceImpl.java:482-498`：终止流程；
- `FlowProcessEventHandler.java:46-59`、`:70-83`：发布流程事件和任务事件；
- `TestLeaveServiceImpl.java:223-280`：现有事件监听样例。

适用边界：

- 可以复用 Warm-Flow 做人审、确认或驳回节点；
- 不应把计划事件的定时执行、领取租约、自动重试和失败恢复交给 Warm-Flow；
- `FlowProcessEventHandler` 使用进程内 `SpringUtils.context().publishEvent`，不是持久化事件总线；
- 目标库 `flow_instance` 只有流程节点和状态字段，没有 `claim_until`、`next_retry_time`、重试次数等调度字段。

计划事件状态机作为唯一执行事实来源。本次简单首次确认直接复用现有鉴权、事务和操作日志即可，无需额外启动 Warm-Flow；实际出现多级审批需求时再复用其既有审批能力。

### 4.1 前后端现有复用入口

目标库实际存在 `flow_category / flow_definition / flow_instance / flow_instance_biz_ext / flow_node / flow_skip / flow_spel / flow_task / flow_his_task / flow_user` 这组 Warm-Flow 表及 SnailJob `sj_workflow*` 表。只读聚合当时为定义 0、实例 0、任务 0；这只说明开发库当前没有运行数据，模块可用性仍由源码、依赖和控制器路由互证，不能据空表断言功能不存在。

Web 已有动态菜单、权限、流程定义设计器、流程实例/待办页面和审批记录组件：

| 前端证据 | 可复用能力与边界 |
| --- | --- |
| `src/router/index.ts:106`、`src/store/modules/permission.ts:69` | 后端菜单动态添加入口；新模块通过菜单权限接入，不硬编码到公共路由 |
| `src/views/workflow/processDefinition/index.vue`、`design.vue:42` | Warm-Flow 定义列表/发布/设计器；可复用于复杂人审，不能替代领域事件状态定义 |
| `src/views/workflow/processInstance/index.vue` | 运行中/已完成实例列表与查看轨迹 |
| `src/api/workflow/instance/index.ts:22-27,46-51` | 实例分页与历史轨迹 API 封装模式 |
| `src/components/Process/approvalRecord.vue`、`flowChart.vue` | 审批记录和流程轨迹展示；新计划链需领域 DTO，可借鉴布局，不复用错误状态语义 |
| `src/components/IwsCrudPage`、`src/utils/iwsFeedback.ts`、`src/utils/dict.ts` | 标准表格、消息、字典和权限基座 |

后端可用入口至少包括 `/workflow/definition` 的定义分页/详情/发布，`/workflow/instance` 的运行/完成实例与轨迹查询，`/workflow/task` 的启动、办理、待办与审批历史。它们可作为可选多级审批的现成能力，但不拥有事件 `claim_until`、`next_retry_time`、外部成功 checkpoint 和纯跳过状态；领域事件模块仍为执行事实源。

源码与表结构进一步限定了复用边界：`FlwDefinitionController.java:47-226` 提供定义保存、发布/取消发布、复制、导入导出和激活；`FlwInstanceController.java:48-220` 提供运行/完成实例、轨迹、变量、暂停和作废；`FlwTaskController.java:47-251` 提供启动、办理、待办/已办、驳回、转办、签收和催办。`flow_instance` 的节点类型支持开始、中间、结束、互斥、并行，但只有一个当前 `node_code/flow_status`，没有 `token_id/branch_key/fork_scope_id/attempt_no/claim_until/next_retry_time`；`flow_his_task` 有审批人、源/目标节点和变量，但未定义外部发送意图、幂等键或本地投影检查点。

`FlowProcessEventHandler` 和 `WorkflowSideEffectListener` 使用普通进程内 Spring 事件，不能作为跨重启可靠通知；可以复用审批结果，但最终必须落入领域模块幂等命令和检查点。

### 4.2 Warm-Flow 编辑动作的代码绑定证据

当前 `warm-flow-plugin-vue3-ui-1.8.9.jar` 的打包资源 `META-INF/resources/warm-flow-ui/js/index-BTmmNT3K.js`（压缩单行文件）明确提示，经典模型/仿钉钉模型“切换后重置节点，保存后不支持修改”。这是现有工作流自身的锁定动作，不能通过 IWS 上层配置绕过。

后端还存在节点身份绑定：`FlwCommonServiceImpl.java:198` 的 `applyNodeCode` 取第一个中间节点作为申请人节点；`FlwDefinitionServiceImpl.java:133-148` 发布时依赖该编码校验审批节点办理人；`WorkflowGlobalListener.java:127`、`:222`、`:288` 多次用 `applyNodeCode` 决定启动人、驳回状态和通知；`FlwChartExtServiceImpl.java:81-95` 按 `nodeCode` 聚合历史任务。修改 `nodeCode`、节点类型或模型类型会破坏运行实例的关联与既有代码语义。

因此编辑门禁分三层：名称/说明/布局等纯展示字段可改；已注册的审批人、表单、按钮和扩展属性只能在新定义/新版本内配置；模型类型、运行节点编码/类型、代码监听器依赖的身份及实现算法必须新建定义并完成代码评审。把节点拖到另一条边、改变网关或顺序属于拓扑修改，同样只允许新版本，且代码绑定节点应禁用移动/连线操作。

计划事件页不会嵌入 Warm-Flow 的自由画布作为主配置器；第一版使用受控表单和单事件编辑。后端必须在保存/发布时校验不改写固定字段，不能只依赖浏览器禁用按钮。

### 4.3 开源候选判断

本地 Maven 依赖和源码已确认 Warm-Flow 1.8.9、LiteFlow 2.16.1.2、SnailJob 2.0.2 已接入；本地缓存未发现 Temporal、Flowable、Camunda 或 Spring Statemachine。Temporal 的持久信号与活动恢复、Flowable/Camunda 的 BPMN 和人审能力均成熟，但会新增独立运行时、持久化、Worker 与部署职责，当前收益不足以抵消现有技术栈的复用价值。Spring Statemachine 仍需自建任务租约、调度和节点读模型；LiteFlow 适合单次 attempt 内部短链路，不承担跨重启事实。结论见[主方案 §13.1](plan-event-state-machine-design.md#131-工作流与开源项目选型)，未对未安装候选做版本兼容或运行验证。

## 5. 最小接线及职责约束

以[主方案 §10、§12](plan-event-state-machine-design.md)为唯一设计依据，只需两种固定执行器代码：

| 拟议执行器 | 职责 |
| --- | --- |
| `businessScheduleExecutor` | BusinessSchedule 的业务统一控制入口，维护各计划逻辑长期监督，协调 worker 启停、到期唤醒和租约分类；仅做控制，不执行业务操作，也不暂停自己 |
| `planEventWorkerExecutor` | 在业务总调度许可范围内，跨允许的 plan types 按 `plan_type` 优先级 → 实例优先级 → 事件优先级有界认领和执行；执行、跳过和事件重试共用此入口 |

`BusinessSchedule` 是拟新增业务配置，保存 `business_code` 和允许的 `plan_type + priority`，统管多个任务关联的计划；各计划逻辑监督归属该业务调度。原 `planSupervisorExecutor` 的控制职责合入 `businessScheduleExecutor`，不再设置各计划可独立取任务的调度入口。同一固定执行器可复用不同 `business_code` 参数，物理任务的划分按主方案确定。

`SchedulerAdapter` 封装依赖库的按 `bizId` 创建、查询、启停和触发能力，禁止业务直接写 `sj_job`。业务代码只写期望控制态，失败由业务调度对账；worker 休眠时业务调度及各计划逻辑监督仍然运行。各计划监督、手工立即触发和重试都不得绕过业务总调度的允许范围和优先级取任务。

`BranchSelector` 是拟新增的小型统一分支决策工具，供统一事件更新入口复用；职责限于主方案约定的分支选择，不新增规则引擎或调度能力。它与 `BusinessSchedule` 均为待实现业务能力，现有源码没有这些 API。

跳过接线按最新业务口径执行：`enabled=false` 跳过整事件；`BYPASS` 只跳过 `EventOperation` 中 `kind=EXTERNAL_POSTING` 且 `target_system_code != self_system_code` 的外部抛账接口操作，本地、查询和控制操作继续执行。两种语义分别处理，并沿用现有日志/审计能力记录跳过原因，不能把 BYPASS 当作整事件禁用。

## 6. 自动启停与租约/重试边界

控制态 `AUTO_RUNNING / AUTO_IDLE / MANUAL_PAUSED` 持久化；人工暂停需明确命令/操作审计，不能仅凭期望与观测不一致推断，因为自动启停尚未收敛也会产生同样的差异。

事件认领使用现有条件更新，先提交认领再执行副作用，收口再次检查版本、owner 和 lease_epoch。租约过期不直接等于失败或可重发：未发送、有可信成功证据、结果未知分别处理。人工确认模式执行失败立即待人工，自动模式才按类型化规则有限退避。已成功外呼仅补本地，未知结果先对账。

SnailJob 的平台重试不能叠加领域重试。两个执行器默认关闭平台自动重试，依靠有界轮次和持久状态恢复。`DISCARD` 等平台阻塞策略不能代替领域 CAS 和外部幂等。

## 7. 数据库快照

目标明确为 `localhost:3306/innolux_iws`，覆盖开发配置中的 3307。通过只读 MCP 的 `mysql-local` 核验，未修改连接配置、数据库或代码。

```sql
SHOW GRANTS;
```

结果为 `SELECT, SHOW DATABASES, SHOW VIEW`，没有写入、DDL 或高风险授权。

```sql
SELECT @@version AS db_version;
```

结果为 `8.0.45`。只读查询解析器拒绝 `VERSION()`、`DATABASE()` 和 `SHOW VARIABLES`，因此使用 `@@version` 取证。

```sql
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'innolux_iws'
  AND (
    TABLE_NAME LIKE '%job%'
    OR TABLE_NAME LIKE '%event%'
    OR TABLE_NAME LIKE '%posting%'
    OR TABLE_NAME LIKE '%plan%'
    OR TABLE_NAME LIKE '%interface%'
    OR TABLE_NAME LIKE '%workflow%'
  )
ORDER BY TABLE_NAME;
```

按 `%job% / %event% / %posting% / %plan% / %interface% / %workflow%` 匹配的完整结果共 9 行：

| 表名 | 归属 |
| --- | --- |
| `sj_job` | SnailJob 任务定义 |
| `sj_job_executor` | SnailJob 已注册执行器 |
| `sj_job_log_message` | SnailJob 日志 |
| `sj_job_summary` | SnailJob 汇总 |
| `sj_job_task` | SnailJob 任务实例 |
| `sj_job_task_batch` | SnailJob 批任务 |
| `sj_workflow` | SnailJob 工作流 |
| `sj_workflow_node` | SnailJob 工作流节点 |
| `sj_workflow_task_batch` | SnailJob 工作流批任务 |

在上述完整表名匹配范围内未发现计划事件业务表、过账表或接口重试表；该查询不证明任意其他命名的表均不存在。新模型须单独设计，不能把 SnailJob 或 Warm-Flow 运行表当成领域事件事实表。

后续按 `flow_` 得到 12 行，包含 `flow_category / flow_definition / flow_instance / flow_instance_biz_ext / flow_node / flow_skip / flow_spel / flow_task / flow_his_task / flow_user` 及两个 `sj_workflow*`；按 `iws_process%`、`iws_business_schedule%` 与已知基础表定向查询，返回 `flow_instance / sys_config / sys_dict_data / sys_dict_type / sys_oper_log` 五行，未命中拟议新表前缀。已有审批、字典、配置和操作日志表可复用；拟议计划事件表尚未部署。

`sj_job` 已核对的关键字段包括：

| 字段 | 结论 |
| --- | --- |
| `biz_id` | `VARCHAR(64) NOT NULL`，可作稳定业务键。 |
| `job_status` | `TINYINT`，注释为“0关闭、1开启”。 |
| `executor_info` | 执行器名称。 |
| `args_str` | 执行参数。 |
| `trigger_type`、`trigger_interval` | 触发类型和 Cron/间隔。 |
| `max_retry_times`、`retry_interval` | 调度层重试，不等同于业务事件重试。 |
| `resident` | 是否常驻任务。 |

唯一键为 `(namespace_id, biz_id)`，可复用稳定 `bizId` 标识 BusinessSchedule 控制任务和 worker 物理任务；具体命名遵循主方案并满足 `VARCHAR(64)` 长度。无需为各计划另建独立监督物理任务。业务控制投影保存期望状态、最近观测状态和故障原因，配合适配器完成重试对账；唯一键本身不能代替该协议。

`sj_job`、`sj_job_task`、`sj_workflow`、`sj_workflow_node` 的排序规则均为 `utf8mb4_0900_ai_ci`。

只读快照结果：

```sql
SELECT job_status, executor_type, COUNT(*) AS job_count
FROM sj_job
WHERE deleted = 0
GROUP BY job_status, executor_type;
```

仅返回一行 `(1, 1, 1)`，即一条开启的 Java 类型任务记录。另行查询 `sj_job_executor` 记录数为 0。

该快照只能说明数据库中存在一条任务定义，不能证明 SnailJob Server、客户端或执行器当前正在运行。dev 配置中的 `snail-job.enabled=false` 与空执行器表相互印证“客户端尚未注册执行器”，但生产运行状态仍需部署后验证。

## 8. 实施顺序

1. 新建唯一业务模块 `iws-modules/iws-process`，按实际 import 复用已有基础依赖；主应用按需引入，依赖方向见主方案。
2. 在 `api/definition/runtime/support` 内按主方案接入 `BusinessSchedule`、计划/事件定义、处理器、状态校验、小型 `BranchSelector` 和 `SchedulerAdapter`；不拆分多个 Maven 模块。
3. 设计状态表、控制投影和审计表，使用现有 MyBatis-Plus 条件更新；SQL 升级单独评审，不在本证据文档中执行。
4. 复用现有 job 接线 `businessScheduleExecutor` 和 `planEventWorkerExecutor`，由业务总调度约束多计划的取任务范围和优先级，并实现薄适配器，禁止业务服务直接写 `sj_job`。
5. 部署时开启 SnailJob 客户端，确认两个执行器注册，随后按 `business_code` 接入业务调度，观察 worker 的创建、暂停、恢复和立即触发，以及各计划逻辑监督持续运行。
6. 对同一记录做并发认领、过期租约回收、业务失败、人工暂停和重启恢复验证；不把单次成功或数据库快照当作并发正确性证明。

## 9. 明确不新增的能力

- 不新增 Quartz 或第二套工作流引擎。
- 不新增通用调度管理平台。
- 不新增按事件类型展开的执行器族。
- 不把 Redis 锁作为唯一正确性来源；实际认领和状态迁移以数据库条件更新为准。
- 不让计划业务直接操作 SnailJob 表或复制 SnailJob 的任务台账。
- 不把 Warm-Flow 审批状态当作定时事件的执行状态。

## 10. 验证边界

本轮仅做源码、依赖产物和只读数据库核验。没有修改配置、数据库或业务代码，没有启动 SnailJob Server，也没有执行动态任务创建、暂停、恢复、触发或端到端事件重放。实施后的有效证据还必须包括：执行器注册记录、任务状态切换结果、并发认领只有一行成功、过期租约恢复、人工暂停不被自动恢复，以及外部副作用未重复执行。

## 11. 旧 PCBA 接口日志、事件和恢复依据

以下是源码与方案证据，不是本次旧 PCBA 数据库运行快照。本次最终目标数据库只采用用户指定的 `innolux_iws`。

| 来源 | 已核对事实与新方案取舍 |
| --- | --- |
| `vietnam_pcba_avc/docs/adr/0001-domain-event-retry-for-pcba-posting.md` | 明确领域事件拥有恢复执行权，接口日志与运维投影不重放 HTTP；共享监督只做控制，worker 执行业务 |
| `docs/pcba-posting-retry-extension-guide.md` | 稳定事件键、载荷冲突、外部成功检查点、职责调度、空闲停机和新任务唤醒；历史测试数量属于旧说明，本文未重跑 |
| `docs/pcba-posting-confirmation-and-waiting-plan-20261016.md` | 首次确认范围持久化并覆盖后续事件。其“失败继续有限自动重试”“首次人工优先于前置等待”被本轮新要求替代 |
| `ruoyi-framework/.../aspectj/ExternalApiLogAspect.java:53-55,153-168` | 外呼作用域关联请求明细；单次请求 `request_id` 关联接口日志，SKIPPED 有明确审计状态 |
| `ruoyi-system/.../service/impl/ExternalApiLogServiceImpl.java:20-21` | 日志独立事务保存；日志保存成功不等于业务状态已完成 |
| `ruoyi-common/.../business/posting/PcbaPostingRequestContext.java:42-92,188-214` | 真正 HTTP 边界记录请求，状态包含 CALLING/SUCCESS/FAILURE/UNKNOWN；跳过可留下未发送审计，不能当成真实成功调用 |
| `sql/upgrade/V1.9.10__pcba_posting_request_trace.sql:11-42` | 主事件下多阶段、多次请求；`execution_id + request_seq` 唯一，明细按事件键及 request_id 索引；该表不拥有重试权 |
| `innolux-wms/.../outbound/posting/service/PcbaOutboundPostingModeProvider.java:19-25` | 当前模式从屏蔽和按箱反馈开关推导，没有完整系统维度配置；新方案显式增加 system_code |
| `innolux-wms/.../outbound/postingevent/service/PcbaOutboundPostingEventStore.java:62-69,177-188` | 现有首次门禁只涵盖指定 MID 事件；本次按计划系统范围重新定义全部适用事件的门禁 |
| `innolux-wms/.../outbound/process/service/PcbaExternalFeedbackProcessServiceImpl.java:160-245,295-320` | 先保存外部成功再本地投影；projectionOnly 不重做外呼。当前瞬时失败会 RETRY_WAIT，本次人工模式失败必须立即待人工 |
| `innolux-wms/src/main/resources/mapper/outbound/process/PcbaOutboundProcessMapper.xml:282-300` | 成功但缺少 LOCAL_SYNC_SUCCESS 的事件仍纳入本地续跑候选，不能仅按外部成功放行后继 |
| `innolux-wms/.../inbound/postingevent/domain/PcbaInboundPostingStage.java:8-12` | 当前入库仅 MID_WMS_FEEDBACK、LOCAL_PROJECTION，不照抄旧方案已经过时的 SAP 续建阶段 |
| `ruoyi-system/src/main/resources/mapper/system/InterfaceRetryMapper.xml:143-176` | 当前状态投影与依赖判断；新设计统一查询/调度口径，不能照搬首次全量 MANUAL_PENDING 的优先顺序 |
| [旧前端部署目录](../../ui/vietnam-pcba-web) | Nginx 部署产物，无 src 和 Git；有界检索仅确认菜单文案，不能验证新页面、路由或状态交互。详见时序图现状基线 |

旧实现还存在需要避免直接复制的恢复边界：出库过期 PROCESSING 恢复未完整区分“请求已发送但结果未知”；入库过期不确定 MID 事件虽有候选 Mapper，静态调用检索未找到消费入口。它们是本轮只读发现，不在旧项目实施修复；新设计使用明确未知结果停留及监督清障协议。
