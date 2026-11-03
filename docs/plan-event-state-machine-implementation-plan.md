# 计划事件模块复用与新增实施计划

日期：2026-10-22。状态：评审稿，仅文档，已接续交接。本文回答“哪些可直接复用、哪些只能参考、哪些必须新增、模块如何按需引入”，并将设计模式细化到类职责、执行协议、事务和前端交互。§11 起为补充的编码设计；§24 对应长期监督计划、队列/即时模式及前后端交付门禁，§25-§26 补充计划内批量屏蔽与独立 endpoint 配置模块。均为实施建议，尚未生成业务代码。

关联材料：[完整方案](plan-event-state-machine-design.md)｜[时序图](plan-event-state-machine-sequences.md)｜[核验依据](plan-event-state-machine-evidence.md)。

## 1. 结论

采用两个新增模块：`iws-modules/iws-endpoint`（包 `com.innolux.iws.endpoint`）承载系统、环境、共享地址及系统地址关联，可独立启用并提供基础配置页面；`iws-modules/iws-process` 承载计划配置与查询、事件状态机、检查点和领域审计。endpoint 不读取 process 合同，系统与地址关联由 endpoint 自己的 `iws_endpoint_system_address` 持有；process 只通过 endpoint 发布的窄只读配置接口读取目录，这是配置基础能力，不是业务 action，也不是 self HTTP；所有跨模块业务调用仍统一 HTTP。调度接线复用 `iws-job`/SnailJob；多级人审批复用现有 `iws-workflow`/Warm-Flow，但不让其成为事件执行事实源。Web 新增系统接入基础页面与计划事件领域页面，复用现有请求、权限、菜单、字典、表格和反馈能力。

第一阶段不改变 `iws-api`、不增加独立调度服务、不新增 BPM/消息中间件、不增加自由画布和通用规则引擎。endpoint 与 process 的版本由根 BOM 集中管理，由 admin 显式装配；endpoint 不依赖 process、job 或 wms。

事件执行采用单一业务动作最小粒度：`eventDirection=INBOUND/OUTBOUND/INTERNAL`，中文分别显示为入站/出站/仅本地处理。INBOUND 与 OUTBOUND 互斥且必须恰有一项 `callContractKey/version` 和一项 `effectKey`；INTERNAL 不进行网络调用，必须 0 项调用合同、0 项入站/出站回执、0 条 HTTP 方向记录、0 个调用许可，并且恰有一项已注册的 `localActionKey/version` 和一项 `localEffectKey`，仍可产生一项受控本地业务效果。三类事件都不配置 `steps[]`、多个动作或多个信号槽。多个必需入站拆成多个 INBOUND 事件并以 AND 汇合；多个出站或本地动作按依赖拆成顺序或并行事件；入站触发后的出站抛账、异步提交后的结果回调分别建事件。旧 PCBA 的多 HTTP 请求是历史实现事实，不代表目标支持多个调用；目标 INBOUND/OUTBOUND 重试或重复投递仍可在同一 `logicalCallKey` 下产生多条实际请求记录，INTERNAL 续跑只复用同一 `localEffectKey`。

## 2. 可直接复用

| 需求 | 现有实现 | 复用方式 |
| --- | --- | --- |
| 模块骨架 | `iws-modules/iws-demo/pom.xml`、`iws-modules/pom.xml`、`iws-admin/pom.xml` | 按新模块实际 import 引入 common 依赖；不照搬 demo 的全套依赖 |
| 分页与响应 | `PageQuery`、`PageResult`、`R`、`BaseController` | 普通分页使用 GET + PageQuery；返回 `R<PageResult<T>>`/`R<T>` |
| 持久化 | `BaseMapperPlus`、MyBatis-Plus 分页与条件更新、`OptimisticLockerInnerInterceptor` | 直接复用拦截器、Mapper 和分页；新实体需显式增加 `@Version`/版本条件与影响行数校验 |
| 事务与锁 | 现有 Spring 事务、Lock4j/Redis 能力 | 短事务收口；资源竞争另用现有锁，不把锁当作状态事实 |
| 操作日志 | `@Log`、`@RepeatSubmit`、`LogAspect`、`sys_oper_log` | 现有入口日志继续使用；领域动作另写执行审计，不重复建通用日志平台 |
| 权限 | `@SaCheckPermission`、`v-hasPermi`、`auth.hasPermi/checkPermi` | 菜单、按钮和后端 API 按模块使用同一 `endpoint:*` 或 `process:*` 权限码 |
| 菜单与字典 | 动态 `getRouters()`、`sys_menu`、`useDict` | 状态显示中文标签；缺字典用内置中文，未知码附在中文提示后 |
| 定时调度 | `iws-common-job`、`SnailJobConfig`、`iws-job` 的 `@JobExecutor` 模板、SnailJob OpenAPI | 复用现有调度客户端和任务管理；只新增薄 worker/监督执行器 |
| 人工审批 | `iws-workflow`、Warm-Flow 定义/实例/任务控制器、现有待办/已办页面 | 复杂多级审批复用；普通首次确认直接调用领域命令，避免额外流程实例 |
| Web 请求 | `src/utils/request.ts`、`PageResult`、`AxiosPromise` | 新 API 直接调用 `request`；列表读 `data.rows/total` |
| Web 动态路由 | `src/store/modules/permission.ts`、`src/permission.ts` | 只新增后端菜单 SQL；不在 `router/index.ts` 写死业务页 |
| 表格与对话框 | `Pagination`、`IwsTableScrollContainer`、`useSearchToggle`、`useSearchReset`、`useDialogState` | 新领域页复用外壳和交互，不复制分页/搜索逻辑 |
| 反馈与字典 | `IwsFeedback`、`useDict`、`DictTag` 经验 | ProcessStatusTag 中文标签与稳定英文码分离，空字典不只显示英文码 |

`IwsCrudPage` 面向字段平铺的 `wms_*` 元数据表，不能承载计划类型子表、系统模式、状态边、事件链和分支 token。计划事件领域页使用普通 `el-table` 与现有 hooks，不修改通用 CRUD 组件。

## 3. 只参考模式、需要领域化改造

| 现有实现 | 参考内容 | 不能直接复用之处 |
| --- | --- | --- |
| `FlwDefinitionController`/`FlwDefinitionServiceImpl` | 定义列表、详情、复制、校验、发布、版本和权限流程 | 计划状态/事件/分支/系统模式是领域定义，不能写入 Warm-Flow 定义表 |
| `FlwInstanceController`/`FlwTaskController` | 实例列表、轨迹、待办/已办、命令权限和重复提交模式 | 审批任务没有事件租约、外部成功 checkpoint、分支 token 和原操作续跑 |
| `FlwChartExtServiceImpl` | 图节点提示、历史聚合和前端展示思路 | 历史按 `nodeCode` 聚合，不能表达单一动作事件、分支和独立结果事件 |
| `src/views/workflow/processInstance/index.vue` | 页签、筛选、隐藏详情、审批记录交互 | 计划详情需新 VO 和执行链组件；不能把审批状态当事件状态 |
| 旧 PCBA `ExternalApiLogAspect` | 自动记录外部请求、脱敏、请求 ID 关联 | IWS 目标库无 PCBA 专属审计表；需按目标模块补最小请求明细表 |
| 旧 PCBA `PcbaPostingRequestContext`、`wms_pcba_posting_request_detail` | 历史实现中一次 attempt 可关联多次实际请求 | 只借鉴同一逻辑调用的请求关联、状态明细和检查点布局；目标单一动作事件不允许由此恢复多步骤配置 |

## 4. 必须新增的最小领域代码

| 能力 | 最小文件范围 | 说明 |
| --- | --- | --- |
| Endpoint 模块与条件装配 | `iws-modules/iws-endpoint/pom.xml`、`com.innolux.iws.endpoint` 下 controller/domain/mapper/service/support 及独立自动配置 | 由 `iws.endpoint.enabled` 独立装配；不依赖 process/job/wms；endpoint-only 可单独提供 API 与基础配置页面 |
| Process 模块与条件装配 | `iws-modules/iws-process/pom.xml`、模块配置/自动配置类 | 对 endpoint 声明 `optional=true`，由 `iws.process.enabled` 控制 process 自身装配；运行时启用必须取得 endpoint 只读契约和当前环境映射 |
| Endpoint 配置模型 | `domain/EndpointSystem`、`EndpointEnvironment`、`EndpointAddress`、`EndpointSystemAddress`、`EndpointCatalogControl`、`EndpointAudit` 及 Mapper | `iws_endpoint_system.systemCode` 唯一；`iws_endpoint_environment.environmentCode` 唯一，并保存中文名、enabled、revision；`iws_endpoint_address` 以 `(environmentCode, endpointRef)` 唯一；`iws_endpoint_system_address` 以 `(systemCode, addressId)` 唯一持有关联；`iws_endpoint_catalog_control` 保存唯一全局 revision；`iws_endpoint_audit` 独立追加。配置及关联只在 endpoint 保存，停用或被引用身份不改 code、不物理删除 |
| Process 配置模型 | `domain/PlanDefinition`、`SystemPolicy`、`StateDefinition`、`EventDefinition`、`EdgeDefinition` 及 Mapper | 版本不可变；系统模式按 `(versionId, systemCode)` 唯一；引用合同以 `targetSystemCode + endpointRef` 多对多关联 endpoint 目录，只保存稳定引用；事件方向冻结后只能绑定一组互斥能力：INBOUND/OUTBOUND 的唯一调用合同，或 INTERNAL 的唯一本地动作 |
| 运行模型 | `PlanInstance`、`Token`、`PlanEvent`、`Attempt`、`ScheduleControl`、`ActionAudit` | 执行事实与审计，不放在 Warm-Flow 表中；INTERNAL 的本地动作效果与检查点另按事件关联，不伪装为 HTTP 回执 |
| 配置服务 | `PlanDefinitionService`、`BusinessScheduleService` | 草稿、版本、发布、复制、删除、字段白名单、发布前校验 |
| 统一事件更新 | `EventUpdateService`、执行/跳过策略、`SingleActionOperation`、`LocalAction` 及注册接口 | 一个入口；INBOUND/OUTBOUND 执行本事件唯一 HTTP 合同，INTERNAL 执行一个已注册 LocalAction；跳过不执行动作；续跑复用原策略与检查点，不为每个方向建实现类或 Factory |
| 状态与分支 | `StateValidator`、`StateValidatorRegistry`、`BranchSelector` | 状态校验、源/目标转换、XOR/AND/多起点；不为每个状态建引擎 |
| 执行读模型 | `ExecutionOverviewService`、`PlanChainQueryService`、`EventTraceQueryService` | 计划总览、链路、事件详情、attempt、HTTP 请求或 INTERNAL 本地效果追踪 |
| Endpoint 只读配置契约 | `EndpointCatalogReader`（拟议）读取系统/环境/地址/关联快照、解析 system/ref/current-env，并在同库消费者事务中取得 `iws_endpoint_catalog_control` 共享门 | 仅只读配置与 snapshot；endpoint 不读取 process 合同、计划或实例，process 不直写 endpoint 表；远程配置服务属于后续范围 |
| Endpoint 审计 | `EndpointAuditService`、`iws_endpoint_audit` | 配置、关联、启停、目录 revision 变化和冲突在 endpoint 独立记录；复用现有 `@Log` 只做入口补充，不反依赖 process 审计 |
| Process 审计 | `ProcessAuditService`、`ProcessCommandAuditInterceptor` | 计划策略与动作审计记录 endpoint `catalogRevision`，不反向依赖 endpoint AuditService 或 process 外部审计实现 |
| 调度适配 | `SchedulerAdapter`、`BusinessScheduleExecutor`、`PlanEventWorkerExecutor` | 端口在 process，SnailJob 实现和两个固定执行器在现有 iws-job；单向 job → process |
| 人工审批适配（可选） | `WorkflowApprovalAdapter` | 仅复杂人审启用；审批完成调用同一确认命令，不形成双向依赖 |
| Endpoint Web API | `src/api/endpoint/{system,environment,address}.ts` | 完整接口见 §26；涵盖 `/page`、单对象 GET、POST、PUT、系统地址关联 PUT 和审计分页；ID/revision 用字符串 |
| Endpoint Web 基础页 | `src/views/endpoint/system/index.vue`、`environment/index.vue`、`address/index.vue` | 独立“系统接入配置”菜单；地址页固定为 `address/index.vue`；普通列表、分页、权限、弹窗 CRUD 与反馈，不使用 WMS `IwsCrudPage`；菜单须接入当前菜单返回服务的能力过滤 |
| Process Web API | `src/api/process/{types,schedule,definition,instance,execution,event}.ts` | 统一 `/process` 前缀；GET + `PageQuery`；ID 用字符串 |
| Web 领域页 | `src/views/process/schedule/index.vue` | 业务调度列表及弹窗编辑，不另建 edit 页面 |
| Web 领域页 | `src/views/process/definition/index.vue`、`edit.vue` | 计划/事件统一配置、版本切换、单事件编辑；`edit.vue` 为隐藏路由 |
| Web 领域页 | `src/views/process/execution/index.vue` | 已执行、按优先级待执行、运行中、异常，同时承担实例检索和维护 |
| Web 领域页 | `src/views/process/instance/detail.vue` | 单计划多分支执行链和事件抽屉入口 |
| Web 组件 | `ExecutionChain.vue`、`EventConfigEditorDrawer.vue`、`EventExecutionDrawer.vue`、`ProcessStatusTag.vue`、`AuditTimeline.vue`、`PostingConfirmDialog.vue`、`ExternalSystemPostingDialog.vue` | 计划弹窗只聚合系统引用与模式，共享地址维护跳独立 endpoint 基础页；不引入新图编辑库 |

领域计算规则可以放少量纯函数：`definitionDraft.ts`、`postingConfirmation.ts`、`executionPresentation.ts`；不要为每个页面建 Pinia store，服务端状态以查询结果为准。

## 5. 按需引入开关与依赖

| 开关/模块 | 行为 | 不启用时 |
| --- | --- | --- |
| `iws.endpoint.enabled` | 装配 endpoint 的配置、只读契约和基础页面 API | 不注册 endpoint Bean/API；不影响 process 自身的开关判定 |
| `iws.process.enabled` | 在 endpoint 契约与当前环境映射可用时装配 process 配置、实例、查询和命令 Controller | 不注册任何 process Controller/Service；endpoint-only 仍可独立运行 |
| `iws.process.scheduler.enabled` + `snail-job.enabled` | 装配两个固定执行器和控制适配 | 保留配置/查询及 `wake_pending`，可人工维护；不启动自动执行 |
| `iws.process.workflow.enabled` + `warm-flow.enabled` | 两个开关同时为真才装配 Warm-Flow 人审适配 | 普通逐系统确认仍可用；不创建缺失 Bean，不依赖 Warm-Flow 表 |
| 菜单 SQL | endpoint 与 process 分别显式插入动态菜单/权限 | 不显示对应模块菜单；endpoint 菜单不因 process 禁用而消失 |

前端入口由 `sys_menu/getRouters` 和角色授权控制，不新增重复的 `.web.enabled`。endpoint 与 process 菜单分别版本化回退；仅关闭后端 Bean 不会自动撤销已插入的路由。

依赖按阶段引入：P2 endpoint 只依赖公共基础，可独立装配配置 API/页面；process 对 endpoint 使用 `optional=true`，该声明只控制 Maven 下游传递，不省略 process 自身编译依赖，也不代表宿主已装配 endpoint jar/Bean。宿主需 process 时必须显式装配 endpoint 与 process，并由 `@ConditionalOnClass`+开关双条件建立类型接线。P6 在已有 common-job 的 iws-job 增加 process 接线；P7 前端使用现有 Vue 依赖；多级审批阶段经已核验审批 HTTP 接口接线，不直接依赖 iws-workflow 实现或修改 iws-api 合同。

## 6. 明确不新增的内容

- 不新增 Quartz、消息队列、分布式事务框架或第三套调度器。
- 不新增第二套流程/审批引擎，不复制 Warm-Flow 的状态表。
- 不为每个状态、事件类型、业务模块创建 Controller、Job 或 Factory Bean。
- 不新增自由流程画布、拖拽设计器或前端状态机库；执行链先做只读泳道/表单。
- 不创建独立通用审计中心；`sys_oper_log` 与领域审计各司其职。
- 不在 Web 复制一个全局响应/权限/字典客户端。
- 不复制旧 PCBA 的 `wms_pcba_*` 表、Quartz 任务和专属处理器。
- 不预建归档、补丁、插件、表达式解释器、闭包表；有真实数据量后再拆。

## 7. 新增模块动作计划

| 动作 | 实施内容 | 依赖 | 完成证据 |
| --- | --- | --- | --- |
| M01 模块骨架与开关 | 新建 `iws-endpoint` 与 `iws-process` POM；根 BOM 集中版本，admin 显式加入两个模块；分别设置 `iws.endpoint.enabled`、`iws.process.enabled`；process 以 `optional=true` 编译依赖 endpoint，但宿主装配仍要求 endpoint jar | 方案评审、当前项目 POM/BOM | endpoint-only 可构建启动并单独提供 API/菜单；process 缺 endpoint jar/Bean/current-environment 时启动明确阻断；两禁用时无对应 Bean/路由 |
| M02 配置数据模型 | endpoint：system/environment/address/system-address/catalog-control/audit 表、唯一键、全局目录 revision、shared gate 与 Mapper；process：计划定义/系统策略/状态/类型引用、事件/边、触发回执、方向明细、action audit 及 Mapper | M01、目标库只读结构核对 | 隔离 MySQL 8.0 验 endpoint/process 唯一键、关联、目录锁序、CAS、回执和方向关联；配置只写 endpoint，process 只经 reader 引用；H2 不替代并发/方言验收 |
| M03 配置聚合 API | 计划列表、版本详情、草稿保存、复制、字段能力、校验、发布、删除 | M02、`EditCapabilityResolver` | 版本只读/最新草稿/逐系统单值/旧 ACTIVE 退役测试 |
| M04 运行模型与挂载 | 实例、token、事件、attempt、确认、暂停/恢复/取消 | M03、权限与事务 | 幂等挂载、重复确认不重复放行、旧实例快照测试 |
| M05 统一事件执行 | Execute/Skip、CAS/租约、INBOUND/OUTBOUND 唯一调用或 INTERNAL 唯一本地动作、checkpoint、异步提交/结果事件及编排收口 | M03、M04、参考 HTTP 兼容接入 | 方向互斥、INTERNAL 零网络/回执/调用许可、并发单赢、跳过零动作、已成功动作不重发、未知对账 |
| M06 分支与链路 | `BranchSelector`、XOR/AND、多起点/终点、汇合屏障、计划/事件查询 | M04/M05 | 分支单次决策、汇合唯一后继、并发分支不互相回滚 |
| M07 调度与监督 | 两个固定执行器、各类型唯一长期监督及决策/本地事件、QUEUE名额/挂起/插队、IMMEDIATE同步驱动、控制重启及记录 | M02-M06、SnailJob 启用窗口 | 主方案§17；D35-D42；轮次/名额/即时结果与平台控制分责，结果回调仍只进独立INBOUND |
| M08 可选审批适配 | 仅多级审批需求出现时接入 Warm-Flow；`businessId=planInstanceId`；完成确认幂等 | M04、多级审批实际需求确认 | 审批完成重复回调只确认一次；关闭开关不影响简单确认 |
| M09 审计闭环 | 最小审计随 M02/M03 起接入，后续完善拒绝记录、外呼两阶段审计、查询和脱敏 | M02 起贯穿 M03-M07 | 人工/系统/拒绝/外呼意图/结果均有记录；审计失败不误报成功 |
| M10 Web 管理台 | endpoint：system/environment/address 三页、系统地址关联、审计页签、独立菜单与权限，并接入当前菜单返回服务的能力过滤；process：业务调度、统一计划配置、系统模式聚合弹窗、执行总览、计划详情、事件抽屉、审计时间线 | M01 endpoint、M03-M06 | endpoint-only 页面可独立使用；能力菜单不假设现有过滤已实现；process 动态菜单、权限、最新草稿、单事件编辑、轮询与空/错状态通过 |
| M11 集成与发布 | 业务操作注册、首个真实计划、隔离环境演练、菜单/配置/开关发布 | 全部前置、业务映射完成 | P1 两条真实链路通过；旧计划快照不变；撤开关可回退为只读/停用 |
| M12 独立使用 Skill | 全部代码完成并验收后，依据真实实现编写覆盖全部公开接入点的平台使用/接入 Skill；必须说明 INBOUND/OUTBOUND/INTERNAL 方向选择与互斥能力、INTERNAL 唯一本地动作及禁止网络/回执/调用许可、多入站拆 AND、多出站拆事件及异步 O/I 回调；渐进披露，再用 darwin-skill 优化指南的触发、路由和可操作性 | 所有纳入交付的代码及 M11 验收完成；M08 仅在选用时纳入 | 完整接入点对照、三类方向接入示例核验、结构检查及代表任务验证通过；最终用途是使用指南，非优化报告 |

每个动作只实现必要验收项，不顺手建设未来可能使用的通用能力。M08 是可选工作包；没有多级审批需求时不启动。M12 是用户要求的最终使用资料交付，全部代码完成之前不提前创建 Skill、占位入口或声称其可用。

### 7.1 最终使用 Skill 的交付要求

用户明确要求：全部最终实现代码完成后增加一个独立 Skill，说明如何使用与接入该平台；“接入事件、修改事件、修改计划、新增计划”只是示例，必须覆盖最终实现的所有公开接入点，并把“一事件一方向、一个调用合同或一个本地动作、一个效果键”作为接入前置约束。该 Skill 不负责扫描/优化项目架构，不是本轮设计过程或 Darwin 优化报告；代码尚未落地时只记录本要求，不依据拟议类名提前生成操作指南。

M12 先从最终 Controller/HTTP 合同、能力注册目录、配置页、业务调用方和扩展接口整理完整接入点清单，逐项对应使用说明及已验证示例。覆盖实际存在的任务挂载与业务触发、INBOUND 事件接入、OUTBOUND 调用、INTERNAL 本地动作接入、独立结果事件与 `originOutboundEventId` 关联、事件类型与方向能力、状态校验、分支汇合、系统模式与确认/跳过、失败分类/恢复、计划创建/复制/修改/发布、调度/优先级/中文名、权限与前端展示、查询追踪等；明确不支持同事件多方向、`steps[]`、多 operation、多信号槽、同事件回调、INTERNAL 隐藏跨模块 HTTP 或第二个本地动作，以及隐式 query/revalidate。最终范围以实际公开支持能力为准，不将内部方法误列为可接入接口。可选能力只在已实现启用时给相应指南，无能力则明确边界，不虚构可调用入口。

每个接入点按需要说明：何时使用、具体入口/文件/配置、输入输出与业务关联、方向及唯一调用合同或本地动作合同、前置条件和权限、只改配置还是需要新增代码、最小真实示例、验证方式及版本兼容/失败处置。调用示例沿用最终 HTTP 封装、中文展示与英文代码约定，不带真实凭据，不通过示例隐含授权真实抛账或改库。旧实例冻结、未知结果不得盲重发、入站绑定等待事件、INTERNAL 不经 `WAIT_EXTERNAL_TRIGGER`、同 JVM 仍拆 O/I 两个事件等规则只保留必要使用约束，不复制整份架构设计。

渐进式披露分为：description 精确说明触发任务及范围；SKILL.md 仅保留用途、共同前置、任务选择和按需入口；references 按 INBOUND/OUTBOUND/INTERNAL 及任务拆分配置、接口、代码位置和示例，按当前动作读取，不默认递归加载全部引用。稀疏内容可合并，不为了目录齐全创建空文件，也不把完整方案及全部源码索引塞入主入口。

独立 Skill 使用一个英文名称和唯一可发现入口，默认遵循本机全局单份安装约定；保存位置和真实代码路径在最终交付时核对。用户此次明确要求是针对本领域独立使用 Skill 的授权，覆盖集中规约原“不注册项目 Skill”的相应限制；后续仅补必要入口指针/范围例外，不复制通用 Skill/AGENTS 规约，不在三仓各放一份。当前不修改集中规约或安装 Skill。

生成后必须使用 darwin-skill 优化这份使用指南，检查触发准确性、说明是否能完成真实任务、引用是否按需披露、有无重复读取/无谓确认/超出实现的指引；优化依据和对比材料另存，不进入使用指南正文。至少覆盖常见入站、出站和仅本地处理事件接入任务、试图配置同事件多方向/多步骤/INTERNAL 网络调用的反例、涉及旧版本/未知结果等风险的维护任务和一个不应触发的普通任务，按实际受影响分支验证；对照完整清单防止漏掉非示例接入点。不追求固定分数或轮次，不将“使用指南优化”变成“平台优化方案”。

最终验收同时核对 Skill 结构/引用、真实代码与合同、示例和必要隔离前向任务；区分结构校验、模拟推演和真实执行结果。实际技能发现/调用需要在适用会话验证，不能仅凭文件存在宣称已生效。最终代码验收与 M12 使用指南及 Darwin 验证均完成，才能声明整个交付完成；本条不授权现在编写业务代码或提前执行未来操作。

## 8. 复用收益与边界

直接复用后，无需新建调度平台、审批引擎、权限/菜单/字典、请求客户端、通用分页和操作日志。主要新增仅是领域表、状态/事件执行、审计、移动端零散 API 适配和必要展示组件。Warm-Flow 与 SnailJob 不能替代领域检查点，因为它们不保存外部发送意图、幂等键、事件分支 token 和本地收口事实；该部分必须由新模块承担。

## 9. 设计模式选型

参考目录：[重构大师设计模式目录](https://refactoringguru.cn/design-patterns/catalog)。选型原则是围绕已经出现的扩展点和变化轴使用模式；不把模式名写进类名来制造层次，也不新增无实际变化的抽象。Spring 容器、事务 AOP、MyBatis-Plus 和现有框架能力优先于自建实现。

| 模式 | 本项目落点 | 决策与边界 |
| --- | --- | --- |
| 工厂方法 | `SingleActionOperation`/`LocalAction`/`StateValidator` 的注册选择 | 当前不实现 GoF 工厂方法。按 `(key, capabilityVersion)` 从 Spring Bean 注册表查找是依赖查找，不负责创建产品；不为每个状态、方向、事件或业务建工厂类 |
| 抽象工厂 | 无当前需求 | 不采用。计划、调度、审批没有成组的可替换产品族，使用它会增加双重抽象 |
| 生成器 | PlanGraph 和恢复视图组装 | 保留具名组装函数/包内构建器；仅多阶段不变量确需时使用 Builder，不预建 fluent DSL 或 AttemptSnapshotBuilder 类 |
| 原型 | 历史版本复制新草稿 | 取消 GoF Prototype 标签，使用显式复制服务重分配 ID、重映射引用并重验；不是通过原型对象克隆替代创建 |
| 单例 | Spring 默认 singleton Bean | 直接使用框架生命周期；禁止自定义 `getInstance`、静态可变实例和手工锁 |
| 适配器 | 入站 DTO/身份/协议、出站具体 HTTP 客户端、SnailJob/Warm-Flow 接线 | 按不兼容协议边界采用，入站和出站分责。取消统一 ExternalSystemAdapter 假设；已有接口同构可直接复用，不能把一切 Service 都叫 Adapter；详见 §19/§20 |
| 桥接 | 业务操作与不同系统 HTTP 合同 | 多系统不等于两个独立可替换类层次。业务含义和 HTTP 合同分开描述、用组合即可，当前不引入 GoF Bridge 层次 |
| 组合 | 计划 DAG、分支视图 | 不用于领域执行。DAG 有共享汇合和回边禁止，不能伪装成树；树形结构仅可用于纯展示分组 |
| 装饰 | 日志、指标与业务增强 | 当前不建 GoF Decorator。Spring AOP 是框架代理接线；领域审计显式同事务写，不用装饰链控制外呼/重试或隐藏副作用 |
| 外观 | `PlanCommandFacade`/`ProcessApplicationService`、模块公开 `api` 包 | 采用。对前端和业务模块暴露少量命令/查询，隐藏 token、CAS、审计和调度细节；不把整个模块做成上帝类 |
| 享元 | 版本元数据复用 | 取消 Flyweight 标签；不可变版本缓存是普通缓存，没有为本项目额外划分 intrinsic/extrinsic 享元对象的需要 |
| 代理 | 事务、权限、懒加载的请求详情 | 使用 Spring/Framework 代理即可，不新增业务代理层；日志和审计需要持久化事实，不能依赖代理是否被调用 |
| 责任链 | 结构 → 前驱 → 本事件输入 → 确认/模式 → 唯一调用前置 → 目标 | 保留固定有序校验流水线，拒绝即停止；不是可以任意转交/吞掉的 Handler 链，不为每条规则造处理器类 |
| 命令 | `AttachTaskCommand`、`ConfirmPostingCommand`、`RetryEventCommand`、`PausePlanCommand`、`CancelPlanCommand` 等 | 采用统一命令信封（actor、commandKey、expectedVersion、reason）。记录请求意图、审计和结果；当前不需要撤销/重做，不实现 undo |
| 迭代器 | DAG 前驱/后继遍历、活动分支遍历 | 使用项目已有的集合/Stream；只有复杂拓扑遍历复用同一游标时提供包内迭代器。禁止暴露可绕开校验的可变图迭代 |
| 中介者 | PlanService/EventUpdateService 协调 Token、Event、Attempt、Scheduler | 不增加独立 Mediator 对象。现有应用服务已经承担协调职责，再套中介者会重复入口 |
| 备忘录 | attempt/checkpoint、源态快照 | 保留持久恢复记录，取消严格 GoF Memento/undo 表述；它证明已发生效果，不能还原外部世界或任意回滚 |
| 观察者 | 提交后唤醒调度、发送通知、刷新非关键指标 | 仅用于非权威通知。领域迁移先由数据库和 `wake_pending` 持久化；不依赖进程内 Spring Event 作为唯一事实或持久 outbox |
| 状态 | `state_code`、token、event_status、version 状态 | 数据驱动状态机，不建立每个状态的 GoF State 子类。校验和转移由注册校验器/更新入口完成；避免类爆炸和无法持久化对象状态 |
| 策略 | Execute/Skip、单动作 operation、校验/条件/失败策略 | 保留实际算法差异；单一 FailureClassifier 或 RetryPolicy 用普通组件即可，不因出现名称就建立接口和工厂。INBOUND/OUTBOUND 的唯一调用与 INTERNAL 的唯一本地动作在实现内分支，不为每个方向建 Handler/Factory；异步提交/结果拆分是事件组合，不是第三套执行引擎 |
| 模板方法 | 事件更新的固定流程：读取/认领→校验→方向准备→唯一调用/本地动作→检查点→目标校验→收口 | 采用固定算法骨架，用普通方法组合策略；不建抽象基类/模板子类，不按 GoF 继承形式机械实现 |
| 访问者 | 发布期 DAG 多规则校验、状态/边问题汇总 | 可选。当前校验规则有限，先用有序校验器；节点/边类型明显增多再引入，避免为数据结构提前定义 visitor 协议 |
| 解释器 | 类型、条件及触发配置 | 不采用。只用已注册类型化合同与参数，不引入表达式语言、脚本或任意 SQL |

### 9.1 组合方式

领域主链采用“少量命令/查询门面 + 有类型命令 + 执行/跳过策略 + 固定算法骨架 + 有序校验 + 持久检查点 + 必需的协议适配”。严格采用的 GoF 模式主要是外观、命令、真实算法策略及不兼容协议适配；普通注册表、状态迁移表、数据库回执和检查点不强套 GoF 名称。事件方向互斥，操作能力在实现层收敛为 `SingleActionOperation`：INBOUND/OUTBOUND 执行一个调用合同，INTERNAL 在实现内分支调用一个 `LocalAction`，没有步骤列表、第二项 HTTP 或第二个本地动作；不为方向各建实现类/Factory，异步结果仍由独立 INBOUND 事件推进，并共用同一 EventUpdateService。

版本复制是显式数据复制，发布用图算法与有序校验；无需 Prototype/Visitor 体系。提交后观察者只加速非权威通知，入站/回调回执和 wake_pending 承担可靠恢复；场景逐项论证见 §20/§21，不因模式目录有 23 项就要求全部采用。

### 9.2 明确避免的模式

- 每个状态建一个 `State` 类、每个事件建一个工厂或每条边建一个 Handler；优先注册表和配置驱动。
- 为多分支 DAG 使用组合模式伪装成树；共享汇合、多起点和多终点需要显式图结构。
- 用装饰器或进程内事件替代事务、审计、持久 outbox 和恢复检查点。
- 自行实现单例、代理、缓存框架、消息总线或规则解释器。
- 为只有一两个实现的接口提前拆 Factory/Abstract Factory/Bridge；等到真实变化轴出现再引入。

## 10. 权限复用与租户边界

### 10.1 当前事实与结论

当前 `innolux-iws` 源码未发现 `iws-common-tenant`、`TenantEntity` 或可用的租户上下文实现，`MybatisPlusConfig` 仅保留租户插件注释；目标库 `localhost:3306/innolux_iws` 未发现 `sys_tenant`、`sys_tenant_package`，没有 `sys_user.tenant_id` 等业务租户隔离基础。Warm-Flow 的 `flow_*` 表虽有历史 `tenant_id` 列，不能据此宣称 IWS 已启用多租户。

结论：本模块按当前单租户系统设计，不新增 `tenant_id`、租户选择器、跨租户过滤或租户套餐逻辑。未来若正式引入多租户模块，再按下节扩展；在此之前不把 Warm-Flow 的遗留字段当作隔离依据。

### 10.2 后端权限矩阵

所有 Controller 方法使用对应 `@SaCheckPermission`；写命令按现有约定加 `@Log`、`@RepeatSubmit`，GET 查询不加重复提交限制。权限字符串统一定义在 Java 常量中，菜单 SQL 使用同一字符串。禁止信任前端传入的权限、操作者或租户信息。

| 资源 | 权限码 | 默认角色 |
| --- | --- | --- |
| 业务调度查询 | `process:schedule:list`、`process:schedule:query` | 计划管理员、运行人员、审计只读 |
| 业务调度维护 | `process:schedule:add`、`process:schedule:edit`、`process:schedule:remove` | 计划管理员 |
| 监督运行控制 | `process:schedule:pause`、`process:schedule:resume`、`process:schedule:restart` | 经授权的运行人员；版本切换复用 schedule:edit |
| 队列/即时命令 | `process:instance:hold`、`process:instance:release`、`process:instance:priority`、`process:instance:execute` | 按业务范围授权；后端重验模式/版本/名额，不能由UI隐藏替代 |
| 计划定义查询/版本切换 | `process:definition:list`、`process:definition:query` | 计划管理员、运行人员 |
| 计划草稿编辑 | `process:definition:add`、`process:definition:edit`、`process:definition:copy` | 计划管理员 |
| 发布/删除草稿 | `process:definition:publish`、`process:definition:remove` | 计划管理员；删除只限最新未引用草稿 |
| 执行总览/实例/计划链 | `process:execution:list`、`process:instance:query`、`process:instance:chain` | 运行人员、审计只读 |
| 任务补挂/首次确认 | `process:instance:attach`、`process:instance:confirm` | 运行人员 |
| 暂停/恢复/取消 | `process:instance:pause`、`process:instance:resume`、`process:instance:cancel` | 运行主管 |
| 事件查询 | `process:event:query` | 运行人员、审计只读 |
| 事件重试/重新校验 | `process:event:retry`、`process:event:revalidate` | 运行人员 |
| 事件最终失败 | `process:event:finalize` | 运行主管 |
| 事件插队 | `process:event:priority` | 运行主管 |
| 方向记录列表/摘要 | `process:request:list`、`process:request:query` | 运行人员、审计只读，业务范围过滤；非计划记录也需来源范围 |
| 请求报文 | `process:event:request` | 审计/管理员，默认不授予普通运行人员 |
| 审计时间线 | `process:audit:query` | 审计、运行主管 |

对象级校验与接口权限同时执行：即使拥有 `instance:confirm`，也只能确认当前业务范围内、状态允许且未确认的实例系统；拥有 `event:retry` 也只能恢复指定失败事件，不能借路径参数访问其他实例。分页、导出和详情使用同一数据范围与字段白名单。

建议角色：`计划管理员`负责定义/发布，`运行人员`负责确认与事件重试，`运行主管`负责暂停/取消/最终失败/插队，`审计只读`仅查询链路与审计；权限可叠加，不把“能看”直接等同“能操作”。

### 10.3 后端接口权限复用

原后端已经有接口权限机制，不需要新增第二套权限框架或启动扫描器：`SecurityConfig` 注册 Sa-Token `SaInterceptor`，现有 `iws-modules` 中有 217 处 `@SaCheckPermission`，`FlwDefinitionController`、`FlwInstanceController`、`FlwTaskController` 和系统模块均按 `模块:资源:动作` 校验；基于元数据的通用 CRUD 使用 `StpUtil.checkPermission`。新模块直接沿用同一机制。

1. 每个 `/process/**` 查询和命令方法按 `process:资源:动作` 添加 `@SaCheckPermission`；不复制现有流程中偶发遗漏注解的写法。
2. 需要动态权限键的通用入口才使用 `StpUtil.checkPermission`；固定业务接口优先注解，避免权限码分散在 Service 字符串中。
3. 数据范围继续复用现有 `@DataPermission`/权限上下文，不另建接口级数据过滤器。
4. Service 加载目标实例、事件或 token 后校验对象归属、状态、版本和命令前置；这是业务合法性校验，不重复构建权限框架。接口鉴权先于业务事务，对象范围与状态条件在写事务/CAS 中再次约束，不能把已有权限拦截器当成事务锁。
5. 系统 worker 使用受信系统身份，只调用运行服务，不走人工管理接口；人工命令不得伪造系统身份。
6. 方案要求的命令拒绝审计需要新增接线：它记录 403、参数拒绝和业务拒绝，不负责再做一次鉴权；当前源码没有 ProcessCommandAuditInterceptor，具体顺序见 §15.3。

新增接口测试至少覆盖：未登录、有菜单无 API 权限、错误权限、直接构造 URL、跨实例访问、旧版本写入、重复提交和并发租约竞争。Mock 通过不能替代 Spring MVC + Sa-Token 的真实鉴权测试。

### 10.4 前端权限复用

前端继续使用 `useUserStore().permissions`、`v-hasPermi`、`auth.hasPermi/hasPermiOr/hasPermiAnd` 和后端动态菜单。按钮显示只是体验层，API 注解和对象级校验才是最终授权。

| 页面/控件 | 默认展示条件 | 无权限行为 |
| --- | --- | --- |
| 菜单与路由 | 后端 `getRouters()` 返回且角色拥有权限 | 不注册页面路由 |
| 计划编辑/事件编辑 | `definition:edit` 且版本为最新草稿 | 编辑器只读，不渲染保存控件 |
| 发布/复制/删除草稿 | `definition:publish`/`copy`/`remove` | 隐藏对应操作，服务端仍拒绝 |
| 首次确认 | `instance:confirm` 且当前系统可确认 | 不显示勾选框，提示无权限 |
| 事件重试/重新校验 | `event:retry`/`event:revalidate` 且 `canRetry`/`canRevalidate` | 隐藏按钮并显示后端原因 |
| 暂停/恢复/取消 | 对应实例权限与计划状态 | 隐藏或明确禁用，不推断成功 |
| 审计/请求报文 | `audit:query` / `event:request` | 审计入口或报文详情不可见 |

所有 403 页面显示“无权限执行该动作”，不把 403 当网络错误重试；请求拦截器已经提示的错误不重复弹出。前端不得通过隐藏按钮允许未授权用户执行命令。

### 10.5 若后续引入多租户

只有现有项目真正装配租户上下文、`TenantEntity`、租户插件和 `sys_tenant` 后才启用以下规则，不在当前单租户版本预先增加空字段：

1. 计划定义、业务调度、实例、token、事件、attempt、schedule control、action audit 全部纳入租户实体；唯一键包含 `tenant_id`，例如 `(tenant_id, plan_code, version)` 和 `(tenant_id, task_key)`。
2. 租户来自登录上下文或受信调度上下文，绝不接受客户端可改的 `tenantId`；所有关联写入校验同一租户，防止跨租户引用。
3. MyBatis-Plus 租户插件自动过滤所有新表；确需全局表的表必须显式加入忽略清单并说明理由。缓存键、菜单、角色和审计全部带租户范围。
4. 调度任务持久化租户 ID，worker 在认领时恢复租户上下文，任务结束后清理 ThreadLocal；监督控制、优先级队列和资源组按租户或业务调度范围隔离。
5. 超级管理员可跨租户查询和运维；租户管理员仅管理本租户；跨租户命令返回明确拒绝并审计。租户停用停止新认领，不删除历史实例和审计。
6. 多租户版本需要独立迁移与隔离测试，不能用当前 `flow_*` 的遗留 `tenant_id` 列代替正式租户契约。

## 11. 本次接续结果与实现边界

2026-10-21 已读取交接及四份方案，现场核对文档分支为 `personal/plan-event-state-machine-design`、基线 `b6aad43`，开始时工作树干净；后端 `main` 仍为既定源码基线，三份既有修改保留，Web 开始时干净。交接中的数据库和远端信息属于当时快照；本次只重新核验相关源码与目标库，不执行提交、推送、服务启动或数据库写入。

本轮保留已经接受的模式、确认、跳过、分支和恢复规则。下文给出建议的最小实现，不将拟议类名、签名或字段当作现有 API；涉及真实业务的 operation、状态码、资源键和重试数值仍需 P1 映射，不能用 SMT 示例填成生产配置。

### 11.1 名称和版本分开

| 标识 | 含义 | 何时变化 |
| --- | --- | --- |
| `versionId` | 定义版本行 ID | 复制/创建新定义版本时产生 |
| `planVersion` | 同一 `planCode` 下的业务版本号 | 新建更高版本草稿时分配，普通保存不增加 |
| `revision` | 定义聚合的乐观锁修订号 | 每次基本信息、系统模式、状态、事件/边保存及发布递增 |
| `capabilityVersion` | 操作/校验/条件/恢复协议实现版本 | 代码能力变更时新增，历史版本继续可解析 |
| `eventVersion / tokenVersion` | 运行行并发版本 | 相应持久事实修改时递增 |
| `leaseEpoch` | 一次认领的执行代次 | 认领或撤销旧执行权时递增 |

所有草稿写接口的 `expectedVersion` 指聚合 `revision`。保存事件返回同一 `versionId/planVersion` 和新 `revision`；只有复制才产生新计划版本。Controller 不用 Java 实体直接接收 patch，分别定义 BO，并校验未提供、置空与非法字段，避免默认值覆盖代码锁定字段。

### 11.2 包与依赖的具体选择

采用现有分层风格，在唯一 Maven 模块 `iws-process` 中放 `api / controller / domain / mapper / service / runtime / support`。api 中的命令/结果用于 HTTP DTO，运行扩展仅注册内部编排/协议能力；其他业务模块不直接调用 process Java 门面执行业务。runtime 放执行协调、策略、租约及恢复；support 放编辑门禁、图校验、审计及查询辅助。不再创建泛型 Repository/CommandBus/事件总线。

默认选择 `iws-job → iws-process`：两个薄执行器及 SnailJob SchedulerAdapter 实现在 iws-job，复用其 iws-common-job；process 只声明调度端口，不依赖 job 或 SDK。WMS/其他业务模块提供自己的 HTTP action/业务事实查询端点，并经既有 HTTP 方式调用 process 挂载/触发入口，不为方法直调形成反向模块依赖。process 的 INBOUND/OUTBOUND operation 使用已核验 HTTP 封装；INTERNAL 只解析并调用同模块已注册 LocalAction，不经过 HTTP。validator 只读取本库已持久证据和结构，不 import WMS Service，也不发起只读业务 HTTP。admin 聚合这些模块；组件受总开关及调度开关控制，关闭调度保留 wake_pending。

WorkflowApprovalAdapter 为可选 HTTP 接线，出现多级审批需求才消费既有 workflow 启动/查询/办理 HTTP 合同，审批提交后用持久关联推动 HTTP 确认，不共享事务写 process 确认。不新增公共合同或双向 Java 依赖；开关与目标能力可用性都须校验。所有 process Controller、注册表及监听器均受总开关控制，Bean 条件不等于 Maven JAR 自动移除。

## 12. 按部分落实设计模式与类职责

| 部分 | 采用方式 | 建议落点与调用关系 | 不负责的内容 |
| --- | --- | --- | --- |
| 对外业务接入 | 外观 + 有类型命令 | HTTP Controller → PlanInstanceService；attachTask/receiveTrigger/confirmPosting/retryEvent/pause/resume/cancel 各有 DTO，原 signal 统一到触发合同 | 受理不抛账、不暴露 Mapper，后端其他模块也不直接调用此 Java 门面 |
| 草稿及发布 | 聚合服务 + 显式深拷贝 + 有序校验 | `PlanDefinitionService` 统一保存子表并增加聚合 revision；`PlanVersionCopyService` 复制数据；`DefinitionValidator` 校验完整图 | 不把复制写成 Java `Cloneable`，不复制执行记录 |
| 能力目录 | Spring 注册表 | 按 `(key, capabilityVersion)` 建只读索引，统一提供 operation、LocalAction、validator、condition 描述 | 不按数据库类名反射，不按 Bean 遍历顺序挑首个实现 |
| 发布图计算 | Builder + 拓扑算法 | `PlanGraphBuilder` 一次构建不可变邻接表和引用索引，供校验与查询复用 | 不建设运行时 DSL；DAG 不用树形 Composite 执行 |
| 更新主链 | 固定算法骨架 + 两个策略 | `EventUpdateService` 协调 `ExecuteUpdater/SkipUpdater`，共享认领、结构校验、审计和最终化 | 不按状态建类；不把整条链放在一个数据库事务 |
| 单动作执行 | 策略 + 按需 HTTP 协议适配 + 本地动作接口 | `SingleActionOperation` 描述本事件唯一动作并在实现内按方向分支：INBOUND 消费唯一请求/证据，OUTBOUND 发唯一 HTTP 合同，INTERNAL 调用唯一 `LocalAction`；不改 event/token/attempt | 不调跨模块业务方法，不隐藏查询/对账/级联 HTTP 或第二个本地动作；INTERNAL 不伪装 HTTP ACK |
| 状态校验 | 有序校验流水线 | 引擎固定结构/前驱/输入/模式门禁；StateValidator 只读取本库持久证据和结构，不发起业务 HTTP | 不能跳过必需检查；纯跳过不实际 HTTP，T5 不临时外呼 |
| 分支与汇合 | 统一选择器 + 持久 scope/屏障 | `BranchSelector` 计算分支，更新事务保存结果；汇合由 scope 条件更新单赢 | 不重新选择历史分支，不在选择器中执行操作 |
| 失败与恢复 | 类型化分类 + 持久检查点 | `FailureClassifier/RetryPolicy` 从证据计算门禁，读取原 attempt 及唯一调用或本地动作检查点恢复 | 不按错误文案、最新日志或异常类一个维度决定重发 |
| 调度 | 适配器 + 数据库控制意图 | `BusinessScheduleService` 提供准入/排序；job 中两个固定执行器调用公共运行入口 | 平台 Job 状态不替代领域运行态 |
| 命令审计 | 显式事务写入 + 入口补充 | `ProcessAuditService` 保存状态变化；`ProcessCommandAuditInterceptor` 作为拒绝审计接线职责 | 现有异步 `@Log` 不充当持久幂等回执 |
| 查询 | 命令/查询服务分责 | 三个 QueryService 用同库持久事实生成 VO，复用候选规则 | 不新增 CQRS 中间件、独立读库或第二套状态 |
| Web | 页面协调 + 展示组件 + composable | 父页拥有版本/命令/刷新，抽屉维护单事件副本，`usePlanExecution` 管查询生命周期 | 不在组件重算状态迁移、队列优先级或恢复安全性 |

### 12.1 建议的最小契约

以下是签名草图，不是可编译补丁。命令使用项目 BO/VO 风格即可，无需为命令模式实现统一 `execute(Command)` 总线。

```java
// service 签名草图：HTTP Controller 内部使用；其他业务模块经 HTTP 调用。
AttachResult attachTask(AttachTaskCommand command);
TriggerReceiptResult receiveTrigger(ReceiveTriggerCommand command);
CommandResult confirmPosting(ConfirmPostingCommand command);
CommandResult retryEvent(RetryEventCommand command);

// runtime：仅 worker 调用，不向 HTTP 暴露任意事件执行入口。
UpdateResult update(UpdateRequest request);
// request 含 eventId、受信调度范围；模式、updater 和 checkpoint 从库读取。

interface SingleActionOperation {
    OperationDescriptor describe();
    ActionResult execute(ActionContext context);
}

interface LocalAction {
    LocalActionDescriptor describe();
    LocalActionResult execute(LocalActionContext context);
}

interface StateValidator {
    ValidatorDescriptor describe();
    ValidationResult validateStructural(StateContext context);
    ValidationResult validateBusiness(StateContext context);
}
```

`ActionContext` 为只读本轮视图：冻结输入/摘要、方向、模式、稳定效果键和执行代次。INBOUND/OUTBOUND 另含唯一调用合同与 `effectKey`、稳定业务幂等键、requestId；INTERNAL 另含唯一本地动作键/版本和 `localEffectKey`，没有 HTTP requestId。它不暴露持久实体或 Mapper。结果必须区分 `SUCCEEDED / ACCEPTED / NO_EFFECT / UNKNOWN` 与证据引用；ACCEPTED 只表示 OUTBOUND 提交合同 `ACCEPTANCE` 已取得可信 ACK，仍须由独立 INBOUND 结果事件确认最终业务结果；INTERNAL 的本地动作只返回本地结果，不能返回或伪造 HTTP ACK。`execute` 只发生一次逻辑动作：INBOUND/OUTBOUND 的重试/重复投递沿用同一 `logicalCallKey`，实际请求可多条记录；INTERNAL 的续跑沿用同一 `localEffectKey`。未知 HTTP 结果没有隐式查询入口：需主动核对时只能走预发布 OUTBOUND QUERY 恢复分支，或由人工对账提交证据；INTERNAL 只依据本地动作检查点读取已提交效果。

注册元数据与 `EventTypeDescriptor` 必须兼容方向：`supportedDirections` 至少包含值定义所选的 `eventDirection`。INBOUND/OUTBOUND 声明唯一 `callContractKey/version`、`kind/targetSystemCode`、输入输出契约、幂等及恢复能力；INTERNAL 声明唯一 `localActionKey/version`、`localEffectKey`、本地事务/检查点与恢复能力，并明确不含调用合同、HTTP 端点角色或入站/出站回执。一个 operation 只含一项动作，不提供 `steps[]`、多个动作、多个信号槽，也不允许 INTERNAL 藏跨模块 HTTP 或第二个本地动作。BYPASS 判断只放在执行协调器的同一函数：`direction == OUTBOUND && mode == BYPASS && kind == EXTERNAL_POSTING && target != selfSystem`；INBOUND 与 INTERNAL 不参与屏蔽。`SkipUpdater` 不调用 operation 或 LocalAction 的执行、对账或补偿方法，跳过校验只读取已保存的结构与替代证据。

### 12.2 注册、编辑和部署兼容

1. 启动时收集已装配 Bean 的 descriptor，重复 `(key, version)` 直接配置失败；不用 Spring Bean 名充当持久协议版本。
2. 发布时按完整引用解析能力，校验编辑白名单、方向兼容、单一调用或单一本地动作以及跳过合同，固化能力版本、参数及摘要；同事件多方向、INBOUND/OUTBOUND 缺调用合同、INTERNAL 携带调用/回执/HTTP 端点或超过一个本地动作、多步骤、多信号槽均直接拒绝。
3. worker 认领前再次检查冻结引用可解析；旧实例的调用合同或 LocalAction 引用缺失时显示“能力版本不可用”，停止认领并待部署修复，不退回最新版实现。
4. 兼容升级保留旧能力实现；破坏性升级在旧实例排空或显式迁移后删除旧版。缓存只缓存不可变定义，key 含定义 ID/摘要，运行事实每次从数据库取得。
5. `EditCapabilityResolver` 在每个草稿写入口应用同一规则；界面元数据只辅助展示，服务端对未知字段和代码固定字段的变更明确拒绝。

## 13. 统一事件更新的代码流程与事务

### 13.1 固定主链

```text
worker 取得业务 scope 内合法候选
  → EventUpdateService.update(eventId)
  → claim：短事务重验门禁、资源、CAS、attempt 和 CLAIM 审计
  → OUTBOUND T2：同事务绑定 CALL 控制并提交 SEND_INTENT；INBOUND/INTERNAL 不创建发送意图
  → 从新 attempt 的恢复来源取得原策略/操作/模式及检查点
  → 结构校验；首次执行检查本事件唯一动作输入，恢复先处理已存证据/未知结果
  → SkipUpdater：只校验跳过合同，准备逻辑迁移
    或 ExecuteUpdater：按唯一调用或本地动作的已存结果复用 / SKIPPED_MODE / 实际执行
  → INTERNAL：LocalAction 在自身事务或明确检查点边界执行一个本地业务效果，不发送 HTTP
  → 准备目标校验证据及分支决策输入
  → OUTBOUND ACCEPTANCE：以可信 ACK 收口 SUCCESS/outcome=ACCEPTED；最终结果由独立 I_RESULT 事件等待
  → finish：短事务重验代次/版本及已保存调用或本地效果完成证据，不执行业务外呼或 LocalAction
  → 同事务持久分支决策、token、终态、后继与审计
  → 提交后唤醒；失败保留持久标记，由监督补做
```

EventUpdateService 不加覆盖整个方法的 @Transactional。内部短事务通过注入的 EventMutationService 的公开事务方法或 TransactionTemplate；默认选前者，禁止 this.claim()/this.finish() 自调用假定代理生效。worker/发送线程不继承调用模块事务；HTTP 挂载/触发的接收事务也不加入发送方事务。调用模块用业务数据+持久待发送标记提交后触发；单靠内存 afterCommit 不保证不丢。

### 13.2 每个事务提交什么

| 边界 | 同事务内容 | 事务外内容/失败处置 |
| --- | --- | --- |
| T1 认领 | 控制/实例门禁、按方向适用的资源容量预检、事件 CAS、lease/owner、新 attempt、CLAIM 审计；不扣资源、不创建动作 | 竞争零行退出；不得调用 operation 或 LocalAction；INTERNAL 只使用事件 lease，不进入容量池/CALL 控制，也不得获得调用许可 |
| T2 发送准备 | 仅 OUTBOUND：重验有效代次/取消；同一事务原子取得容量、创建 `schedule_control(scopeType=CALL, scopeKey=O eventId)` INTENT/IN_FLIGHT、写 requestId/SEND_INTENT 和审计 | INTERNAL 不创建 CALL 控制/发送意图/调用许可；T2 无容量不发送并回退等待；意图提交前取消不发送；该事务前不存在扣量或 CALL 行的半提交窗口 |
| 外部调用 | 仅 INBOUND/OUTBOUND HTTP：不持有本地数据库事务 | 由适配器分类确定成功/确定无副作用/未知；关闭客户端透明重放 |
| INTERNAL 本地动作 | LocalAction 优先在同一数据库事务提交业务效果、localEffectKey幂等事实、本地检查点、token/事件终态和审计；确需独立事务时先原子提交效果+检查点，T5只读证据 | 不创建HTTP/收发回执；无已提交效果或检查点才可重试，有已提交效果只补编排；禁止把方法成功文本当外部ACK |
| T3 HTTP 结果 | 仅 INBOUND/OUTBOUND：代次校验、请求结果、本事件唯一调用证据或失败分类、结果审计 | ACCEPTANCE 取得可信 ACK 时本事件可 SUCCESS/outcome=ACCEPTED；提交事务不清除仍需结果确认的调用链控制。存储不可写保留 SEND_INTENT，不执行下一事件 |
| T4 接收端事务 | HTTP action 接收方在自己的事务中保存业务修改、业务幂等键及可查询结果 | 不属于编排事务；包括同机/同库 IWS 自调用，也不能与 T5 合并；接收请求在自身模块内调用 Service 仍是 INBOUND，不能改记为 INTERNAL |
| T5 编排最终化 | scope/分支版本复核、已持久唯一调用或本地动作证据及目标结构校验、token 迁移、事件终态、屏障到达、后继创建、审计、资源释放或调用链占用转移、wake 标记 | 任一编排写失败回滚；已提交的接收端或本地业务效果不能回滚，只恢复编排 |
| TF 失败收口 | 当前代次下记录错误、保留检查点、源态/统一异常态、人工/退避门禁、已证明安全的资源释放和审计 | 代次已失效不能覆盖新执行者；未知物理资源占用保留，由监督分类 |

外部或跨模块业务调用统一走 HTTP：INBOUND 接收请求、OUTBOUND 发起请求，接收端 T4 先提交，编排端取得并保存该唯一调用成功证据后才进入 T5。完全在自身模块内完成、无需其他模块或服务交互的单调用事件可使用 INTERNAL，由唯一 LocalAction 在自身事务或明确检查点执行一项本地业务效果，没有 HTTP、回执或调用许可；它仍须经过认领、确认、终态和审计，不能被当作 HTTP ACK。同一事务只覆盖真实共享的数据库边界：同库 INTERNAL 可在 LocalAction 声明的事务中提交业务修改与效果检查点；同机/同库跨 HTTP 仍不共享事务。接收端成功但编排 checkpoint 未提交时按 UNKNOWN 对账；INTERNAL 已有本地提交证据而 T5 失败时只补编排；checkpoint 已提交、T5 失败也不再执行动作。INBOUND 只消费本请求证据；触发后需要其他模块执行的后续业务必须另建 OUTBOUND HTTP 事件，同一 INBOUND 内跨模块转发被拒绝。既有业务 endpoint 处理本次请求的内部 Service 可正常调用，但不能隐藏后续跨模块转发。引擎内部校验、Mapper 与事务服务仍是普通内部实现。

StateValidator 只读取本库已持久证据、本地动作检查点和结构，`revalidate` 不发起业务 HTTP。需要主动外部查询时，必须使用发布时已确定的独立 OUTBOUND QUERY 恢复分支 `recoveryForEventCode`，仅由原 O_UNKNOWN + 显式 `recoveryRequested` 准入，不能依赖 O_SUCCESS，也不能放开普通后继；运行中不得改图。查询证据供原 O 只读收口，不能替代必需 I_RESULT 的完成屏障或伪造回执；INTERNAL 只按 `localEffectKey` 读取本地效果，不创建查询事件；人工对账只提交证据。最终化只重新检查结构、必要同库事实和已有证据。需要外部强一致前提的动作必须依赖对方幂等/条件写协议，本地数据库锁不能保证外部世界不变。纯跳过不运行外部查询，也不调用业务写服务或 LocalAction。

### 13.3 CAS、锁序与资源

所有持久化执行更新显式约束 `eventId + expectedRevision + owner + leaseEpoch + PROCESSING`，并校验租约仍有效；相应 token/屏障也带版本。更新影响行数必须为 1，再进入下一短事务或后继事件；Mapper 的乐观锁插件不能替自定义 XML 自动补齐这些条件。后续更新使用刚返回的新 revision，不复用认领时的旧值。

lease 时长、续租间隔和外呼超时在同一配置中约束。长调用需要续租时，同代次的续租与 checkpoint 更新共享执行上下文、串行执行短事务并保存最新 revision，不能两个线程各持旧版本互相误判；续租失败立即停止后续发送，当前在途结果按证据收口。不能为一个无限阻塞调用永久续租。过期监督先撤销旧代次，再分类结果，存在发送意图不直接重新发。

同一短事务统一顺序为：业务控制/资源控制行（多个按稳定 key 排序）→ 实例控制行（需要时）→ 决策/汇合 scope → 分支 token（多个按 ID 排序）→ event → attempt/后继。预读可在事务外，锁定后重验引用及版本。禁止完成事件后再反向申请控制行或实例锁；终态汇总使用新的短事务从顺序起点进入。所有这些锁均不跨越外呼。

T1 在短事务检查暂停/取消/类型停用；仅需要 permit 的 OUTBOUND 做容量预检，但不扣业务容量，暂停阻止后续认领。仅 OUTBOUND 的 T2 才与 SEND_INTENT 同事务扣容量、建 CALL 控制、写意图，保证无“T1 已占量、T2 无控制行”的崩溃泄漏。已认领但 T2 无容量的代次不发送，零发送后可安全释放回等待。INTERNAL 不进入容量池和 CALL 控制，只使用事件 lease 和 LocalAction 的本地事务/检查点。OUTBOUND permit/占用计数在 schedule_control CAS 只释放一次；重试接管已持有控制，否则重新准入。未知外部结果继续隔离占用。Redis 锁只减竞争，数据库容量/CALL 控制是事实。

仅 OUTBOUND 的 T2 同事务原子取得容量并创建 CALL 控制（状态 INTENT/IN_FLIGHT），绑定 SEND_INTENT/requestId；不能等 ACK 才建。INTERNAL 没有 T2、CALL 控制或 permit。控制状态统一为 INTENT/IN_FLIGHT/ACCEPTED/WAIT_RESULT/RELEASED。O 可信 ACK 同事务转 ACCEPTED/WAIT_RESULT 并交接 originOutboundEventId；ACK 丢失/崩溃仍占用。I_RESULT、QUERY 恢复分支或显式安全终止凭同一 CALL 控制 CAS 释放一次。QUERY 不申请新 permit，只挂靠原 O 同控制并写独立 QUERY intent/request；因此原 O 占满容量不会阻塞恢复查询。O 终态未收敛时监督保留；取消只允许结果消费与安全收敛。

O_UNKNOWN 是判定而非 eventStatus：最新 attempt/checkpoint 为 UNKNOWN，或 SEND_INTENT 后无最终可信结果且尚无完成证据。QUERY/I_RESULT/安全终止共用。预发布 QUERY 不在正常 expected 集合，仅 O_UNKNOWN+recoveryRequested 创建恢复 token；O 在其他可终结状态完成时，未激活 QUERY 为 NOT_SELECTED；QUERY 成功不进入正常完成屏障。QUERY 属于恢复控制，不参加业务容量预检/扣量。

OUTBOUND permit 的释放不仅在成功 T5：TF 已证明不存在在途/未知副作用且不再占用实际资源时，以及最终失败或取消已安全收敛时，也按统一锁序以事件 permit CAS 保证仅释放一次，将计数递减、状态及审计同事务提交。重试继承仍持有的 permit；前次已释放则重新经过容量准入取得，不能一概跳过容量检查。INTERNAL 没有 permit，不进入该释放路径。对账只能由有效恢复代次或受控安全终止命令解除隔离占用，不能单凭 lease 过期释放。

### 13.4 失败事实与存储故障

`FailureClassifier` 先根据已保存事实计算 `effectResult`，再由模式决定重试门禁。MANUAL_CONFIRM 任意本轮执行失败立即 MANUAL_PENDING；AUTOMATIC/BYPASS 只有明确无副作用或明确允许的安全本地恢复才 RETRY_WAIT。INTERNAL 必须从 LocalAction 的事务结果或检查点判断本地效果是否已提交；未知时只读核对同一 `localEffectKey`，不得以“没有 HTTP ACK”推断未生效。CAS 竞争失败不算业务失败，不增加重试次数。BYPASS 只适用于 OUTBOUND。

外部 HTTP 成功而 T3 未提交时，库中只有 SEND_INTENT，系统只能认为结果未知。数据库恢复后由监督转人工/待对账，原请求可凭稳定幂等键查证；不能承诺在数据库不可用时仍成功保存 checkpoint 或人工状态。T3 已提交、T4/T5 失败则只补本地，不重发外部。INTERNAL 的业务修改与可共享的本地检查点在同一事务提交；若 LocalAction 使用独立事务而检查点未写，后续按 `localEffectKey` 只读核对，不重复应用效果。审计写失败与所属短事务一同回滚；不通过另开事务只保存“成功 checkpoint”却漏掉必需审计来继续推进。

## 14. 恢复、分支和命令的持久协议

### 14.1 单动作备忘录

attempt 保存本事件唯一动作的检查点，不采用 `steps[]`。INBOUND/OUTBOUND 检查点至少含 `logicalCallKey/status/sent/inputHash/idempotencyKey/requestId/evidenceRef/operationVersion/recordedAt`；INTERNAL 检查点至少含 `localEffectKey/status/inputHash/localActionVersion/effectRef/transactionId/recordedAt`，不含 requestId、sent、HTTP 响应或入/出站 receipt。真实 HTTP 请求明细可分页独立存储，正文不塞入检查点；本地动作效果按其自身事务/检查点读取，不能转写成 HTTP 请求记录。状态明确区分 SEND_INTENT、SUCCEEDED、ACCEPTED、SKIPPED_MODE、NO_EFFECT、UNKNOWN；INTERNAL 不使用 ACCEPTED，也不只用一个 boolean success。

每次认领新建 attempt，带 resumeFromAttemptId，将历史唯一调用或本地动作结果、屏蔽/受理证据固化为恢复起点；旧 attempt 退出后不改写。INBOUND/OUTBOUND 的业务幂等键为 eventKey + callContractVersion + effectKey，跨 attempt 不变；INTERNAL 为 eventKey + localActionVersion + localEffectKey。requestId 标识一次实际请求，不以新 attempt 产生新业务幂等键。类型/operation/调用合同/LocalAction均引用原精确版本。合法结果续跑不计执行失败或发送重试，仍有新认领审计。

恢复前先解析原单一动作能力。SUCCEEDED 验成功证据；ACCEPTED 只属于 OUTBOUND 且验可信 ACK，仅表示提交完成；SKIPPED_MODE 验冻结模式及屏蔽合同；无意图的本事件唯一调用才可首次发送。SEND_INTENT/UNKNOWN 先对账；外部一次“未查到”仍 UNKNOWN，只有可靠幂等或排除旧执行/迟到请求的证据才能重发。INTERNAL 不得进入 `WAIT_EXTERNAL_TRIGGER`；前驱、唯一输入和系统确认满足后直接 PENDING，执行时按 `localEffectKey` 查本地检查点，已提交则只恢复编排，没有可信效果证据时保持 UNKNOWN/待核对。重试命令只登记资格，worker 在独占代次中恢复 token 游标，不向其他执行者暴露可抢占的中间源态。

OUTBOUND 的 `ACCEPTANCE` 事件在收到可信 ACK 后以 SUCCESS/outcome=ACCEPTED 收口，只证明提交；异步最终结果必须由独立 INBOUND `I_RESULT` 事件等待 RESULT_CALLBACK，并纳入必需完成屏障。回调归属 I_RESULT 并带 `originOutboundEventId`；早到先持久化为 I_RESULT 输入，可为 WAIT_PREDECESSOR，不回写成 O 的第二项入站调用。O 响应未知时保持 UNKNOWN/人工对账；可信回调可作为 O 收口的只读证据，但所有权仍归 I_RESULT。需主动 HTTP 查证时，只能走已发布 OUTBOUND QUERY 恢复分支 `recoveryForEventCode`，由 O_UNKNOWN + 显式 recoveryRequested 准入，不依赖 O_SUCCESS、不开放普通后继；其证据供原 O 只读收口，不能替代 I_RESULT。

### 14.2 决策 scope 与汇合屏障

为避免把同一分支决定分散存在多个 attempt，第一版复用 token 表保存明确的 scope 协调记录，建议区分 `tokenRole=BRANCH/SCOPE`。SCOPE 记录只协调图节点，不参与事件候选；其唯一逻辑键为 `(instanceId, decisionNodeCode, parentScopeKey)`，保存不可变决策输入摘要、规则版本、已选集合、期待集合及屏障 revision/closed。根 parentScopeKey 使用保留的非空 ROOT 标识，参与唯一约束的范围字段不为 NULL，避免 MySQL 多个 NULL 绕过去重。BRANCH 记录引用该 scope，`branchKey` 在 scope 内唯一；嵌套 AND 引用父 scope，不只按 joinCode 聚合。

首次决策以真实冻结输入和已提交 HTTP/本地动作结果证据计算，在最终化事务中锁定/创建唯一 scope，复核输入及 revision，同时建立分支 token、后继和 NOT_SELECTED 事实；不能在 T5 内外呼取得条件或执行业务动作。并发冲突读回已提交结果核对。返回值相同但输入或实现版本不同仍报冲突，不能静默覆盖。AND 到达按 `(scopeId, branchKey)` 去重；最后一条分支必须先锁同一 scope 再读取期待/到达集合、CAS 关闭屏障并创建唯一后继。事务开始时的旧一致性快照不能作为“已全部到达”的依据。

XOR 未选路径只关闭在当前活动根/已选路径联合可达图中确定不可达的节点；共享汇合保留。独立 INBOUND 结果事件的重复回调只能返回原结果，不能重新建 token。后继稳定键基于实例、事件定义和分支身份；唯一冲突必须核对配置/载荷摘要。同一事件和 scope 不通过重置状态实现循环。

### 14.3 命令幂等与版本冲突

人工命令信封含 `commandKey/expectedVersion/reason` 和动作参数，操作者来自登录上下文；worker/审批回调由受信接线传入稳定系统身份。HTTP BO 不接收可冒充系统的 actor 字段。`commandKey` 是一次用户意图的随机稳定标识；网络重试保持同一 key，用户修改参数后是新命令。

不额外建命令总线或命令表。复用领域审计保存命令回执，建议仅回执记录设置可空唯一 `receiptKey`，按 `actor + action + target + commandKey` 确定，保存参数摘要及最小结果；其他执行审计不占该唯一键。命令事务原子保存回执和业务变化。相同 key/相同摘要返回原受理结果；不同摘要明确冲突。先查命令回执再校验 expectedVersion，使成功后响应丢失的重投不因 revision 已增加误报。并发唯一冲突或状态/版本 CAS 失败时，回滚当前事务后开启新读事务再查同 key 回执，区分已经赢得的同命令与另一用户的修改；不在已回滚事务中继续执行。回执读回仍经过当前权限与对象范围校验，不因知道 commandKey 就绕过授权。

IMMEDIATE例外使用主§17.7的 `IMMEDIATE:scheduleId:commandKey` 唯一回执锚点，避免调用人/重试入口变化重复执行；原actor及业务范围仍参与鉴权和审计，不允许不同权限调用者读取结果。即时PROCESSING/原响应/最新收敛结果追加关联audit，不能UPDATE追加式历史。GET聚合回执不执行动作；UNKNOWN仅显式recoveryOnly恢复证据，不重做业务或自动执行超时后继。

首次确认重投不改原操作者和时间；新 key 再确认已确认系统不重复放行，返回已确认结果。混入非人工系统仍整体拒绝。事件重试重复命令只能有一个待执行资格；“已受理”不等于恢复完成。状态/版本冲突返回明确问题及可重新读取的对象 ID，不自动换新 expectedVersion 再提交。

## 15. 版本配置、调度与审计的实现细节

### 15.1 草稿聚合及发布

每次保存先 CAS 定义根行 `revision + DRAFT + latestKey`，再按单一 BO 更新该事件及关联边/分支等子表，同事务追加审计。修改状态和模式也必须更新同一根 revision，防止两个页面分别改子表而互相不感知。完整图提交前校验，不接受部分写成功。

复制显式深拷贝状态、事件、边、系统模式和配置参数，重新分配行 ID、重映射内部引用；不复制 confirm/token/attempt/audit。分配更高 `planVersion`、切换 latestKey 并建新草稿同事务。已有最新草稿时返回冲突及其 ID，不静默把别人草稿降为不可编辑。

发布重新读取服务器完整草稿，构建不可变 `PlanGraph` 并检查无环、起终点、跳过合同、系统单值及能力版本；校验与 revision 条件绑定，不能用前端之前一次“校验成功”直接发布。原子退休旧 ACTIVE、清 activeKey、激活新版本、写摘要及审计；切换中任何失败回滚。`activeKey/latestKey` 唯一冲突作为并发冲突处理，不能覆盖赢者。逻辑删除最新草稿释放 latestKey，但历史 `planVersion` 不复用；最大版本号包含被删除历史，删除后旧版本不自动恢复为可编辑。

第一版按主方案的必要表，不为发布新增独立图引擎/版本仓库。新表字段及索引落地时以 MySQL 8.0 隔离验证为准；当前目标不存在这些表，不能对拟议索引提供真实 EXPLAIN。机器标识码使用统一格式，摘要用稳定十六进制编码；目标库存幂等键列是 `utf8mb4_0900_ai_ci`，不能用大小写差异区分同一动作。数量延续 decimal/BigDecimal，不能以 double 拼摘要。

### 15.2 调度、资格与查询共用规则

业务候选由 EventEligibilityService 生成：版本/类型、前驱、冻结触发/payload、确认、到期、控制、恢复/结果消费与资源。首次执行、人工定向恢复及结果消费分开，等待本身不是候选；已失败门禁不被重新算成首次就绪。EXCEPTION_HOLD 不放行普通事件，查询仅用只读规则，认领事务重验。

基础排序在分页前应用 `planTypePriority DESC, instancePriority DESC, eventPriority DESC, readyTime ASC, id ASC`，队列排名只对同一竞争 scope 的候选计算。防饥饿固定采用主方案§17.4的fairnessWindowSize保底窗口，不再保留老化/保底二选一给开发者；P1只需给出K及真实共享scope数值。候选准入/rank与worker使用同一窗口快照；已准入计划内事件另按原资格/资源规则排序，不能把子计划占用和事件可执行rank混为一张队列。

监督器通过自己有限轮次的 DECISION/LOCAL INTERNAL 事件维护控制意图、名额准入、过期租约分类及唤醒；不执行子计划业务事件。QUEUE worker 有单轮数量/耗时上限，参数含稳定监督/业务scope，具体事件从数据库选取。IMMEDIATE 不进该队列，由同步驱动调用相同 EventUpdateService，确定失败不自动重试。两个 Job 的平台自动重试均关闭，HTTP 客户端重试由具体操作契约约束。基础设施异常保留日志及控制事实，下一轮监督恢复；业务失败已落库返回本轮处理结果，平台不重演外呼。

`wake_pending` 同时带控制 revision。适配器在事务外确保 Job 存在/启停/触发，再按它读取的 revision CAS 确认已处理；过程中有新唤醒时不能无条件清标记。人工暂停修改同一控制 revision，适配器不能凭旧观察态自动覆盖。即使平台任务被误触发，worker 的数据库门禁仍拒绝非法认领。

### 15.3 拒绝审计不依赖猜测切面顺序

现有权限在 MVC 拦截阶段处理，参数绑定/校验也可能先于 Controller 方法；只包 Controller/Service 的 AOP 无法覆盖所有拒绝。`ProcessCommandAuditInterceptor` 是本模块的入口接线职责名，建议通过早于鉴权的窄路径拦截器捕获已注册 `/process/**` 写命令元数据，再由针对既有异常类型的 MVC 异常处理接线记录拒绝，保留现有响应语义；不重新实现鉴权或吞掉异常。

拒绝记录在原业务事务回滚后由独立审计事务保存；业务服务不要“先写拒绝审计再抛 RuntimeException”而将记录一起回滚。正常命令回执、状态变化与审计仍同一事务，不能全部改成 REQUIRES_NEW。回执唯一键只用于赢者，拒绝/重复观察审计不占它。无法识别的路径或读取不到命令键时只保存安全的入口关联，不读取完整请求正文。拒绝审计失败维持原拒绝响应并告警，不把权限失败转换成成功。

### 15.4 可选人审的可靠接入

简单首次确认不启动审批。多级审批时，将明确系统集合、实例 ID、审批实例 ID 及本次审批意图键/摘要关联保存，同一实例第一版只允许一条活动审批；只接受对应审批终态的结果，businessId 相同不代表所有系统已获批。已有 ProcessEvent 没有办理 taskId，幂等不能依赖缺失字段，而用本地审批意图键 + 审批实例 ID 及冻结系统集合。

审批与 process 的业务交互也统一 HTTP：审批完成先提交自身事务，持久关联/待同步记录驱动 HTTP 确认，同一幂等键重投，不以 REQUIRED 共享事务直写 process。进程内 ProcessEvent 只作为提交后补查提示，不能是唯一同步来源；监督经已有只读审批 HTTP 合同核对终态和审批意图，受理失败由标记恢复。若已有启动 HTTP 不保证按业务意图幂等，P1/M08 先最小补齐，不按网络超时盲建第二审批实例。重复/乱序/驳回/旧回调不放行不符系统范围，无实际需求不启动 M08。

## 16. Web 具体实现分工

### 16.1 统一编辑页与单事件草稿

`definition/edit.vue` 持有服务器完整版本、revision、editCapabilities、当前页签和唯一 `editingEventCode`。它负责请求与命令；`EventConfigEditorDrawer` 只编辑该事件及允许关联边的深拷贝，通过 props/emits 交互，不直接修改列表行或其他事件。`definitionDraft.ts` 只做结构/必填/重复输入提示，完整拓扑、能力及发布判断仍以服务端为准。

事件编辑必须先选 `eventDirection`，界面分别显示 INBOUND（入站）、OUTBOUND（出站）、INTERNAL（仅本地处理），代码仍保留英文枚举值。切换方向时清空不相容字段并提示重新选择能力：INBOUND/OUTBOUND 只展示唯一调用合同/effectKey，INTERNAL 只展示唯一 localActionKey/version/localEffectKey、事务/检查点说明，不展示 HTTP 端点、receipt 或 permit。用户输入只做即时提示，互斥能力和发布拒绝以服务端 revision 与 `EventTypeDescriptor.supportedDirections` 为准。

切换事件、版本或离开路由前，dirty 草稿先由用户决定保存/放弃/留在当前页；保存成功后回读完整定义与能力并更新 revision，打开事件时重新取其最新副本。没有保存就关闭不能污染列表。一次写命令在途时禁止发布、切版本或发送另一份聚合 patch；不能两个页签同时持有旧 revision 连续覆盖。

业务调度页采用 `index.vue` 列表加 `useDialogState` 弹窗编辑，不另建 `schedule/edit.vue`。计划定义的 `edit.vue`、实例的 `detail.vue` 仍是后端菜单提供的隐藏路由；不增加独立事件配置页。

### 16.2 查询生命周期

`hooks/process/usePlanExecution.ts` 只管理快照与请求：传入稳定查询 key、load 函数和是否允许轮询，返回 `data/loading/stale/asOf/error/refresh/stop`。总览、计划链、事件抽屉分别按实际需求使用各自实例；敏感 HTTP 请求明细在授权后单独加载，不进入总览缓存；INTERNAL 从事件/attempt/本地动作审计和检查点读取，不发起请求明细查询。

使用“本次请求结束后再排下一次 setTimeout”，避免 setInterval 堆积。每次对象/筛选变化递增 generation，取消旧请求；响应提交同时比对 generation、对象 ID 和组件仍激活，旧请求的 finally 也不得关闭新请求 loading。AbortController 只取消读取，不能撤销服务器已经接受的写命令。

页面不可见、路由卸载、keep-alive 停用或抽屉关闭即停止定时器并取消读取；恢复可见/激活只立即刷新一次，再按当前是否活动安排轮询。轮询失败保留上次同对象快照并显示过期；切换到另一实例不能展示前一实例的旧快照。终态停止周期刷新，保留手动刷新。403 不自动重试；取消读取不弹业务失败提示。

### 16.3 命令、权限与冲突

按钮条件为“已有权限工具通过 + 后端 canX + 当前页面版本/控制态允许”；不能从状态标签推导可重试。异步变化的 canX 使用 computed 与 auth.hasPermi 组合，不能只靠 mounted 时删除 DOM 的 v-hasPermi 指令。确认弹窗按后端 confirmable 的 MANUAL_CONFIRM 系统预选，提交明确 systemCodes；失败重试使用独立 RetryEventDialog。弹窗 loading 只表明请求在途，受理后回读相应事件/实例并保留“已排队”的文案。

一次意图生成一个 commandKey，网络结果不明时先回读对象，必要时以原 key 重投并取得原命令回执；不为此新增回执查询接口。版本冲突保留用户输入供查看，重新读取服务器对象及能力后由用户决定是否重做，不自动换 revision 保存。

本次核对发现 request.ts 的 createHandledError 只保留 msg/isHandled，会丢失 R.code/data；后端 ServiceException 默认仍返回 HTTP 200 的 R。M10 必须最小扩展既有 handled error，保留 code/data；process 局部异常映射仍用 R 风格返回 code=409，并在安全 data 内带 problemCode、对象 ID 和当前 revision。前端按结构化代码区分 VERSION_CONFLICT、VERSION_READ_ONLY、CODE_REFACTOR_REQUIRED 等，不能按中文消息判断或假定 HTTP 409。保留原消息提示及 isHandledRequestError，避免双重弹错；对取消读取在统一错误拦截器里直接透传取消错误、不弹网络失败。这是已发现缺口对应的受限公共请求改动，需验证现有 API 错误分支兼容，不重写客户端。

列表、计划链和事件抽屉在命令后刷新各自受影响查询，沿用服务端排序/asOf/版本。ExecutionChain/ProcessStatusTag/AuditTimeline 只读；字典缺失以中文映射/提示加原码兜底，FIRST_CONFIRM 显示等待确认，UNKNOWN 显示结果待确认，SKIPPED_MODE 显示已屏蔽而非外部成功。每个查询可有不同快照时间，不伪装为浏览器中的单一原子事务。

## 17. 验证如何对应实现

以下均是后续实施验收，当前文档检查不代表已执行。优先从公共服务、HTTP 边界或 LocalAction 事务边界断言业务结果；内部算法只补有独立规则价值的测试，不逐个私有方法镜像实现。

| 编号 | 验证边界与注入故障 | 必须看到的结果 | 关联动作/主方案 |
| --- | --- | --- | --- |
| D01 | 两个客户端用同 revision 保存不同子表 | 一个成功，一个冲突；无半份子表；planVersion 不变 | M03，A25-A27 |
| D02 | 并发复制/发布；发布中途失败 | 唯一最新草稿/ACTIVE，无覆盖赢者；旧实例摘要不变 | M03，A25/A26 |
| D03 | 并发认领、暂停与认领竞态、旧 epoch 完成 | 一个有效执行代次；暂停后不再新认领；旧者零行停止 | M04/M05，A12/A17 |
| D04 | OUTBOUND T2 提交前失败、T2 后进程退出、T3 失败 | 首者零发送且无残留 CALL 控制；其余保留 SEND_INTENT/INTENT 资源并对账，不自动重发；INTERNAL 不产生 T2/CALL | M05，A09/A12 |
| D05 | OUTBOUND/INBOUND HTTP 接收方已提交，或 INTERNAL 本地事务已提交，编排 checkpoint/目标校验/T5 失败 | 无 checkpoint 时按方向对账；已有 checkpoint 只补编排；不跨 HTTP 或本地事务承诺回滚业务效果 | M05，A08/A10 |
| D06 | Skip 目标写失败；BYPASS 出站与入站/INTERNAL/独立查询 | 纯跳过零 operation/LocalAction 且迁移回滚；仅 OUTBOUND 非本系统 EXTERNAL_POSTING 的唯一调用不发送，INBOUND/INTERNAL 不屏蔽，INTERNAL 可被 Skip 禁止唯一本地动作，QUERY 各自事件继续按图依赖 | M05，A06/A07/A20 |
| D07 | 新 attempt 续跑、旧调用或 LocalAction 能力缺失、独立 QUERY 恢复分支未找到 | IN/OUT 幂等键或 INTERNAL localEffectKey 跨尝试不变；能力缺失停留；查询只走预发布 recoveryForEventCode 显式准入，未找到不直接重发，不替代 I_RESULT | M05，A09/A12/A16 |
| D08 | AND 最后两支并发到达、重复回调、嵌套 scope | 只一个屏障关闭和后继；无旧快照永久等待或跨 scope 汇合 | M06，A11 |
| D09 | XOR 共享后继、输入摘要改变、未选分支 | 决策只一次；共享后继保留；冲突不重新选路 | M06，A11 |
| D10 | 同 commandKey 重投、参数改变、响应丢失 | 相同返回原受理；异参拒绝；确认/重试不重复放行 | M03-M05，A04/A05/A22 |
| D11 | SnailJob 不可达、睡眠中唤醒、旧 revision 清标记 | 控制意图保留；新唤醒不丢；人工暂停不被覆盖 | M07，A13/A17 |
| D12 | 无权限/参数校验/业务事务回滚及审计不可写 | 拒绝仍有独立审计；写审计失败普通命令不提交 | M09，A28 |
| D13 | 总览分页/计数/rank、未来重试、人工等待 | 与 worker 同资格/排序，waiting 无可执行 rank | M06/M10，A15/A19/A21 |
| D14 | Web A→B 快速切换、旧响应晚到、keep-alive | B 不被 A 覆盖，无并发轮询，关闭/隐藏停止请求 | M10，A23/A26 |
| D15 | 单事件脏稿、版本冲突、重复点击、403 | 列表不被未保存副本污染，冲突不覆盖，按钮及请求均受限 | M10，A22/A24/A26 |
| D16 | OUTBOUND 并发资源占用、未知超时、无副作用失败后最终终止/安全取消；INTERNAL 本地未知效果 | 不超容量；INTERNAL 不占 CALL/permit；未知隔离不被转给另一任务；安全释放恰一次，重试按持有情况继承或重获，本地效果按检查点收口 | M05/M07，A12/A14/A17 |

D01-D13/D16 中涉及唯一键、条件更新、锁序和事务的部分使用 MySQL 8.0 兼容隔离实例，不能用 H2 通过替代；纯策略/图算法使用现有单测工具。构建与测试分开报告，Maven 使用现有 Wrapper/JDK 21，并核对 Surefire 实际运行测试数。D14/D15 的请求乱序和交互必须通过实际浏览器；纯函数 Vitest 不能代替。SnailJob/Warm-Flow 联调另给真实接线证据，目标库始终只读。

## 18. 实施顺序、待决项与本轮交付

动作编号 M01-M12 保留，按依赖分段进入：P1 真实链路映射 → M01/M02 及最小审计 → M03 配置聚合 → M04 挂载/确认 → M05 单一调用或本地动作执行/跳过/O-I 恢复 → M06 分支/读模型 → M07 调度 → M10 Web → M11 首批链路演练 → M12 完整接入/使用 Skill 与 darwin-skill 验证。M09 的必要审计贯穿前述阶段，不能到最后才补；M08 仅实际多级审批需要时启动。M07 与已稳定契约的 M10 可并行，不另写第二套 tickets 复制本计划。

编码前仍需以下业务输入；它们不阻止本轮完成公共实现方案，也不能由代理替用户确认：

| 待决项 | 所需具体材料 | 影响部分 |
| --- | --- | --- |
| 两条真实链路 | 真实任务唯一键、businessCode/planType、系统及本系统码、源/目标态，以及各事件方向对应的调用合同或 LocalAction 入口 | M03-M05/M11 |
| 产物与跳过合同 | 每事件真实输入/产物，禁用与模式屏蔽的替代输入，幂等/对账证明 | 发布校验、operation/LocalAction、恢复 |
| 资源与公平 | 各类型scope解析、QUEUE/IMMEDIATE、activePlanLimit、公平窗口K、跨类型提升范围；另行映射仅OUTBOUND使用的资源键/CALL上限 | PLAN_SLOT准入、CALL permit、rank，二者不能互代 |
| 首批数值参数 | 类型优先级、worker 批次/时长、lease/续租、调用超时、自动重试上限/退避 | 调度、失败策略、发布配置 |

本轮仅完善这份实施计划及主方案的一致性表述、追加本轮核验证据；未创建 process 模块、DDL、菜单或真实任务，未授权上线。后续获得实施授权后，从 P1 的具体映射和当前状态继续，不重新选择已接受的架构。

## 19. 新增边界：HTTP、外部触发、双向记录与类型扩展

本节落实用户在接续后的明确补充：增加“等待外部触发”；复核全场景模式；外部或跨模块业务调用统一 HTTP；单调用事件允许无网络调用、只在自身模块执行一个内部本地服务动作；参考 PCBA 既有 HTTP 做法、允许优化但不另建平台；兼容出入站抛账记录；事件类型可扩展。它们覆盖前文“同库业务 Service 可与 T5 合并”等旧建议；正文已同步更正，未实施业务代码。

### 19.1 先按变化维度建模

| 维度 | 建议字段/含义 | 不可混用 |
| --- | --- | --- |
| 节点身份 | eventCode，版本内唯一 | 相同类型可有多个不同节点 |
| 事件类型 | eventTypeKey + eventTypeVersion | 可扩展业务能力，不能等同系统/URL/执行状态 |
| 动作粒度 | `eventDirection=INBOUND/OUTBOUND/INTERNAL`，中文显示入站/出站/仅本地处理。INBOUND/OUTBOUND 互斥，各含一个 callContractKey/version 和 effectKey；INTERNAL 含一个 localActionKey/version 和 localEffectKey | 不支持 `steps[]`、多 operation、多信号槽或同事件多方向；INTERNAL 不得含调用合同、HTTP 端点角色或第二项本地动作 |
| 生命周期 | eventStatus，包括 WAIT_EXTERNAL_TRIGGER；INTERNAL 不使用该等待态 | 各类型共用固定引擎，不各建一套状态机；INTERNAL 在前驱/输入和系统确认后进入 PENDING |
| 来源 | INBOUND 的 callerSystemCode + moduleCode，由可信接入身份解析 | HTTP body 的 systemCode 不授予权限/确认资格；INTERNAL 不创建来源回执 |
| 政策及效果归属 | 事件 systemCode 为模式来源。OUTBOUND 的唯一调用 targetSystemCode 为真实业务效果所属系统；INTERNAL 的效果属于本模块本地业务 | 网关/部署位置不改写业务效果归属；INTERNAL 不得借本地动作改写其他模块/服务数据 |
| 通信与端点角色 | 仅 INBOUND/OUTBOUND：transport 固定 HTTP，角色为 TRIGGER_EVENT / EXECUTE_ACTION / RESULT_CALLBACK / QUERY / CONTROL | INTERNAL 固定无 transport、无端点角色、无 HTTP 方向记录；同 JVM 跨模块仍拆 O/I 两个事件 |
| 入站语义 | INBOUND：REQUEST_EXECUTION / REPORT_COMPLETED_EFFECT / RESULT_CALLBACK | 一个 INBOUND 只接纳一个逻辑请求/效果；INTERNAL 不接纳任何入站请求 |
| 完成协议 | INBOUND/OUTBOUND：BUSINESS_COMPLETION / ACCEPTANCE；INTERNAL：本地事务结果与可核对检查点 | 异步 ACCEPTANCE 只证明提交；HTTP 200/202、ACK、任务号不自动代表业务完成；INTERNAL 不能伪造 HTTP ACK |

按方向、系统模式、调用合同或本地动作以及独立恢复边界拆事件；同系统两次独立调用或两次独立本地效果也必须两个事件，多个入站用 AND 汇合，多个出站/本地动作按顺序或并行分支组合。一个代理接口若一次不可分地改变多个政策系统，第一版拒绝这种不可分配置，先核对既有合同能否最小调整；不能在一个系统的确认下代替所有系统执行。需要其他模块或服务交互时选择 OUTBOUND，接收本次请求的自身端内部 Service 仍属于 INBOUND，不因方法在进程内就改成 INTERNAL。

### 19.2 HTTP 的复用与优化边界

参考源为 C:/code/company/vietnam_pcba_avc，目标为 IWS；源码证据见核验文档 §13。落实为 RestTemplateConfig + RequestExternalUtil 的 get/post/postForm 单次调用、ExternalRestInterceptor 真实出站边界、ExternalApiLog 注解/切面双向业务日志、PcbaPostingRequestContext/Recorder 的逐请求关联机制。移植时去掉 PCBA 专属类型/阶段并适配 IWS R/领域结果；不再平行增加另一套客户端。这些不是 IWS 当前已存在的业务 HTTP Bean，实施应表述为参考移植/兼容优化。

当前参考 RestTemplate 使用 HC4 连接池和 0 自动重试，IWS 静态源码未发现应用级 RestTemplate 配置/封装，只有已有 Spring Web 及 hutool-http 依赖；不能宣称已经有可直接注入的客户端。参考入站方向只在已注解 Controller 留业务日志，客户端拦截器只管出站，目标必须最小补齐需要的入站接线。参考 SAP 工单是 JCo RFC，并非 HTTP；按用户统一 HTTP 要求须在 P1 明确真正可用的 SAP HTTP 接口，不能复制 JCo 或假设 URL 已存在。

不新增 ProcessHttpClient、通用 Gateway、Connector 层次、HTTP 调度平台、URL 脚本执行器或可在线编排的请求模板引擎。可优化已证实的超时配置、连接复用、出入站请求关联、响应业务码分类、正文截断/脱敏及上下文清理；不为了使用 Adapter 模式再包一层完全同构转发接口。参考源 Boot 2/HttpClient 4 与目标 Boot 4 的 request factory 兼容性必须在 M01/M05 验证，不能直接复制旧 import；采用目标支持的同类实现，保留原调用约定。

这里的 HTTP 封装只服务 INBOUND/OUTBOUND。INTERNAL 在自身模块内调用一个已注册 LocalAction，不经过 HTTP 客户端、入站 Controller 或回执表；发布校验拒绝把本地动作伪装成一个 self HTTP 调用。

每次发送前仍由领域服务提交 SEND_INTENT，HTTP 拦截器在真正 execution.execute 的边界记录实际请求；日志切面只补业务接口描述，不代替认领/幂等。关闭非幂等透明重试这一原有约束继续保留。观察日志写失败不等于调用未发生；可信结果只能经检查点协议收口。HTTP 配置集中管理，不把环境 URL/口令/系统对应关系散落到类型或事件类中。

### 19.3 同进程其他后端模块与本地动作边界

模块 A（例如 WMS）与 process 属于不同业务模块或服务时，即使同 JVM、同库也走相同 HTTP 合同。A 在自己的事务保存业务任务和待挂载/待触发记录，提交后调用已有 HTTP 封装；失败由持久标记扫描重投，sourceRequestId 保持不变。标记优先复用业务已有字段/执行记录，没有时才补最小记录，不新建通用投递平台。完全属于 A 自身、无需其他模块或服务参与的单个本地业务效果可由 INTERNAL 事件执行；一旦需要 process 或其他模块响应、落库或提供结果，就必须建 OUTBOUND 事件。

HTTP 接收线程使用自己的事务，不看见 A 尚未提交的数据，也不继承 Spring 事务、Sa-Token ThreadLocal 或数据权限上下文。禁止 A 持行锁同步 self HTTP、process 持事件锁等待 action HTTP、action 反向等待原事件完成；否则可能出现同库锁等待或请求线程池耗尽。self HTTP 默认在取得可靠 ACK 后即可响应，不等 worker 消费 I；若接口必须同步业务完成，接收端必须在自身请求事务内完成本次唯一业务效果并保存证据，I 只消费该证据，不能让 HTTP 线程等待 sender 已占用的 worker/资源。

调用身份沿用已有系统接口机制，增加模块来源及受信原操作人关联；不能伪造登录用户或无鉴权开放自调用 URL。接收端处理本次 INBOUND 请求时正常调用自己的 Service/Mapper 事务；即使这个方法与 Controller 同进程，事件方向仍是 INBOUND。若触发后还要跨模块执行业务操作，必须另建 OUTBOUND HTTP 事件，不能把后续 Service 转发藏在原 I 内。INTERNAL 只在同一模块调用一个 LocalAction，不能借它隐藏跨模块 HTTP、第二个本地动作或其他服务的远程调用。引擎内部图计算、Mapper、短事务及 SDK 调度接线也保持普通内部方法。统一 HTTP 针对业务模块/服务交互，不要求把引擎内部方法或同模块唯一本地动作 HTTP 化。

### 19.4 等待、持久入站与异步结果

WAIT_EXTERNAL_TRIGGER 只用于 INBOUND 事件，waitReason=BUSINESS_TRIGGER/RESULT_CALLBACK。首次准入顺序是前驱 → 本事件唯一输入 → 系统首次确认 → 调度/资源。初始未收到请求不建 attempt、不记失败；等待本身不增加尝试或失败次数。OUTBOUND 不等待自己的第二项入站调用；其响应未知进入异常/人工，最终结果由独立 INBOUND 事件承接。INTERNAL 永不进入 WAIT_EXTERNAL_TRIGGER：前驱和唯一输入满足、系统确认成立后即为 PENDING，随后按普通调度资格执行唯一本地动作。

复用既有 Controller/身份核验，必要时补薄触发/结果入口。TriggerReceiptService 是领域持久受理职责，表 iws_process_trigger_receipt 保存来源请求、唯一 callContractKey/version、effectKey、业务关联、结果证据和原 ACK；不是 HTTP 客户端或新消息总线。唯一请求键含可信 caller/模块范围、callContractKey/version、sourceRequestId；同键同摘要回原 ACK，异摘要冲突。不同调用者转发同一逻辑效果时，在目标 INBOUND 事件上以 `logicalCallKey/effectKey/hash` CAS 应用一次；多个必需入站请求是多个 INBOUND 事件 + AND，不在一个事件配置多个信号槽。

入站请求可早于前驱/确认；未挂载且合同允许时持久待关联，挂载后监督扫描关联，不自动选最新版本补建计划；不允许时明确拒绝并让来源按同 key 重试。关联只接受冻结合同中的事件/调用者/业务键，输入改变不覆盖已应用载荷。外部报告已完成效果必须先验可信证据，再作为既有成功事实供 worker 消费，不能再次执行相同效果。失败人工门禁只能由重试命令放行，任何补发请求/回调不能替代首次确认或重试。

已能唯一匹配时，受理事务将 INBOUND 明细绑定 instanceId/eventId/logicalCallKey/receiptId，关联 WAIT_EXTERNAL_TRIGGER（或提前 WAIT_PREDECESSOR）事件；不同方向不能共用事件。未知实例先保存，占位出现后唯一关联并应用/wake。每个 INBOUND 事件表达一个逻辑请求，但可保存多个可信来源 receipt/到达明细；必须选定唯一 applied/canonical_receipt_id，effectKey CAS 只应用 canonical 一次，其他作为替代/冲突证据。多个必需逻辑请求各建事件 AND。I_RESULT 同样保留 originOutboundEventId；重复投递不形成第二效果。

异步方案至少为 O_SUBMIT（OUTBOUND，completionContract=ACCEPTANCE）→ I_RESULT（INBOUND，WAIT_EXTERNAL_TRIGGER/RESULT_CALLBACK）→ 依赖结果的后继事件。T1 只做资格预检、不扣业务容量；仅 OUTBOUND 的 T2 同一事务完成容量占用、创建 schedule_control(scopeType=CALL, scopeKey=原 O eventId) 的 INTENT/IN_FLIGHT、写入 SEND_INTENT/requestId/审计；T2 无容量则零发送并安全回退，不能等 ACK 才建控制。O_SUBMIT 取得可信 ACK 后保存 SUCCESS/outcome=ACCEPTED、externalTaskId 和期限，同时把 CALL 控制转为 ACCEPTED/WAIT_RESULT 并交接 originOutboundEventId，只证明提交。ACK 丢失或崩溃仍占用 INTENT/IN_FLIGHT。I_RESULT 进入必需完成屏障；I_RESULT、预发布 QUERY 或安全终止凭同一 CALL control CAS 释放一次，I_RESULT 认领继承。O 终态但链未收敛时监督不能关闭。

回调先持久化回执，再绑定唯一 I_RESULT 事件；接收方不直接更新 token/终态/任何 PROCESSING 的 O attempt。早到回调可先存为 I_RESULT 输入并保持 WAIT_PREDECESSOR，O_SUBMIT 提交后由持久扫描重新计算 I_RESULT 资格，不依赖进程内通知。回调不抢 O 的 lease、不修改 O 的活动 revision、不清人工失败门禁。O 响应未知时仍 UNKNOWN，可信回调可作为 O 收口的只读证据，但所有权归 I_RESULT；需主动 HTTP 查证时只能走已发布 OUTBOUND QUERY 恢复分支 `recoveryForEventCode`，仅 O_UNKNOWN + 显式 recoveryRequested 准入，不依赖 O_SUCCESS、不开放普通后继，运行中不改图。

人工暂停/类型停用时仍可收回执，但不新认领 O；恢复后消费 I。取消先停止新业务发送，必要的只消费结果/收敛操作可在受控资格下进行；O 终态但链未收敛时仍保留监督与资源隔离。INTERNAL 不等待或收回执；暂停/取消只阻止新的本地动作认领，已提交的本地效果不能回滚，只按检查点收口。最终失败/终态迟到回调追加证据和告警，不复活事件；矛盾结果转冲突对账。BYPASS 出站未真实发送时，I_RESULT 不会有真实回调，发布必须为其配置显式屏蔽替代 skip 合同，否则拒绝，不能永久等待或伪造回调。

### 19.5 出入站抛账记录兼容

参考接口日志已区分调用方向，目标新增时保留方向语义并映射到 INBOUND（入站）/OUTBOUND（出站）/INTERNAL（仅本地处理）展示，不强改参考原 direction 码。HTTP 观测记录只在真实入站/出站边界写，业务结果/恢复权仍在回执和 checkpoint。INTERNAL 不创建入站/出站请求明细，也不产生 requestId/receipt；其事实在事件、attempt、ActionAudit 和本地效果检查点中查询。旧 PCBA 一个业务方法可能真实发出多个 HTTP 请求；这只是历史实现事实，不代表目标支持多个调用。目标 INBOUND/OUTBOUND 事件仍只有一个调用合同，INTERNAL 只有一个本地动作；重试/重复投递的实际 HTTP 请求记录可以多条。逐请求记录继续保留。

统一请求明细仅记录 INBOUND/OUTBOUND，增加 direction、purpose、来源/目标系统及模块、sourceRequestId、correlationId/parentRequestId/peerRequestId、effectKey、receiptId、可空实例/事件/attempt、HTTP 与业务结果、sent/证据时间、脱敏摘要。一个事件只产生本方向明细；入站可先于挂载/attempt；callback 作为独立 INBOUND I_RESULT 记录。INTERNAL 不写该明细表，只在动作审计中记录 localActionKey/version、localEffectKey、本地事务结果和检查点引用。非计划旧记录也能查，无法确认关联时明确 UNKNOWN/UNLINKED，不猜成功。

同 JVM 跨模块自调用拆 O/I 两事件，各有方向记录，以 correlation/peer、logicalCallKey、originOutboundEventId 关联；一次 HTTP 不双计业务效果。BYPASS/SKIP 的 sent=false 仅在 attempt/checkpoint/审计中，不创建 OUTBOUND 方向记录；已到 INBOUND 保留。抛账统计只匹配 OUTBOUND EXECUTE_ACTION + EXTERNAL_POSTING effectKey；QUERY/CONTROL/callback/INTERNAL 本地效果不计新抛账，也不进入 HTTP 方向统计。

Web 在执行总览内增加“接口抛账记录”页签及方向/用途/结果/业务关联筛选，事件抽屉链接同一记录明细；可查看未关联计划记录，不另建通用日志产品。查询权限 process:request:list/query，正文仍需 process:event:request；列表不带正文，禁止“按日志重放”。INTERNAL 不伪造成 HTTP 行，仍在事件抽屉展示“仅本地处理”和动作审计，不提供可重放的请求。外部回执和内部人工命令审计分别标真实来源，补齐触发受理/拒绝/去重/关联、结果等待/消费/超期、双向 HTTP、INTERNAL 本地效果、类型注册版本和安全资源释放的审计动作。

### 19.6 事件类型可扩展

事件定义冻结 eventTypeKey/eventTypeVersion，复用原能力注册表收集 EventTypeDescriptor：稳定类型标识/版本、中文名称、`supportedDirections`、输入输出结构、状态校验/恢复要求、可编辑字段和代码锁定理由。方向能力必须互斥：INBOUND/OUTBOUND 描述一个 trigger/operation 调用合同、HTTP 端点角色和 `effectKey`；INTERNAL 描述一个 `localActionKey/version`、`localEffectKey`、本地事务/检查点，不出现调用合同、入站/出站 receipt、HTTP 方向或调用许可。类型不组合 `steps[]`、多 operation、多信号或一个方向下的多个本地动作；不创建另一套 registry、插件包加载器或类名反射；DTO schema 只验证受支持字段，不引入任意表达式执行。

类型主要是已注册能力选择。相同 HTTP/本地动作/校验合同的参数变体仅增加定义数据，无需每类型一个 Java 类；真有新业务算法或结果协议才补最小已注册实现及新能力版本。`SingleActionOperation` 是统一执行入口，内部分支到唯一 HTTP 调用或唯一 `LocalAction`，不为 INBOUND/OUTBOUND/INTERNAL 各建 operation 子类或 Factory。EventTypeDescriptor 不定义独立 run/retry/finish 方法，统一 EventUpdateService 的 EXECUTE/SKIP/等待/恢复协议负责事实收口。事件类型不同也不能私自跳过确认、操纵 token、增加第二项调用/本地动作或隐藏重试。

发布时解析精确类型与全部依赖版本、校验方向兼容、唯一调用合同或唯一本地动作、完成后端角色/检查点，保存摘要；新类型不修改核心状态枚举或新增 Controller/Job/Factory。worker 遇类型、调用合同或 LocalAction 能力缺失停止认领并显示部署缺口，不切换最新版；部署升级保留旧能力直到旧实例安全结束。类型可在最新草稿中从受控目录选择，代码固定类型不可改绑，旧实例快照不变。类型与系统模式、触发时机、同步/异步完成彼此独立，但发布后的事件仍只有一个方向和一个动作。

### 19.7 仅本地处理事件

INTERNAL 用于单调用事件无需网络、只在本模块执行一个内部本地服务的场景。它仍是有业务效果的事件，不是纯跳过、查询或“无动作占位”：事件定义必须绑定一个已注册的 `localActionKey/version` 和稳定的 `localEffectKey`。`EventTypeDescriptor` 必须支持 INTERNAL，能力目录能按精确版本解析对应 LocalAction；缺少实现时阻断发布或停止认领，不能退回最新版本或把动作改成 HTTP。

INTERNAL 的准入不等待外部触发。前驱与唯一输入满足、系统首次确认成立后，事件直接进入 PENDING，再由统一调度认领；不创建 WAIT_EXTERNAL_TRIGGER、TriggerReceipt、入站/出站请求明细、CALL 控制或调用许可。系统确认、人工门禁、版本冻结和暂停/取消仍走与其他方向相同的门禁。

LocalAction 只允许一项受控本地业务效果，在自己的事务或明确检查点边界执行。同库时，业务修改、幂等事实和本地效果检查点应在同一事务提交；独立事务时必须在动作内保存可查询的 `localEffectKey`/效果证据，事件只读取该证据收口。它不能返回或伪造 HTTP ACK，不能以 Controller 成功文本、日志或进程内事件代替检查点，也不能在同一事件内调用第二个本地动作。需要其他模块、其他服务或远程系统交互时，必须拆成 OUTBOUND 事件；接收请求并在自身 Controller/Service 事务内处理时仍是 INBOUND。

Skip 可以按冻结合同禁止并跳过该事件的唯一本地动作，跳过时零动作、零业务效果、零 HTTP；不得先执行本地动作再补跳过。BYPASS 只适用于 OUTBOUND 非本系统 EXTERNAL_POSTING，INTERNAL 与 INBOUND 均不可使用。重复执行沿用同一 `localEffectKey`，已有检查点只恢复编排；未知效果只读核对，确认未提交且合同允许时才重试。`SingleActionOperation` 的内部方向分支即可调用 LocalAction，不需要每方向一个类或 Factory。

## 20. 全场景设计模式复核矩阵

以时序文档所有场景逐项复核，不能只看成功箭头；每行同时约束失败/重入/等待路径。新场景编号以时序文档最终标题为准，后端其他模块与方向记录分别有完整时序。

| 场景 | 保留的设计及所解决问题 | 反例/不采用的模式 |
| --- | --- | --- |
| 1 配置/复制/发布 | 聚合服务+不可变图装配+有序校验，绑定 revision；复制显式重映射引用；方向互斥，INBOUND/OUTBOUND 调用/effect 唯一，INTERNAL 本地动作/localEffectKey 唯一 | 复制不是 Prototype；并发发布靠唯一键/事务，Builder 不保证并发正确 |
| 2 挂载/根选择/入站 | HTTP 命令受理、唯一任务、持久触发回执；BranchSelector 只选路；一个 INBOUND 事件只接纳一个逻辑请求 | HTTP 响应丢失靠回执；不用 Observer 防丢，不靠请求数量猜分支 |
| 3 首次确认/失败重试 | 明确命令 DTO+系统确认事实，触发与确认各一道门禁；INTERNAL 确认后直接 PENDING | 不增加审批/Mediator；迟到触发不能清 EXECUTION_FAILED，INTERNAL 不进入外部触发等待 |
| 4 正常执行 | 固定算法骨架+SingleActionOperation，IN/OUT 唯一 HTTP 合同或 INTERNAL 唯一 LocalAction，按方向分类结果 | 接收端业务事务不和 T5 合并；INTERNAL 不建 HTTP ACK；统一协议不能提供跨请求 exactly-once |
| 5 BYPASS | 仅对 OUTBOUND 非本系统 EXTERNAL_POSTING 的唯一调用事件记 SKIPPED_MODE；INBOUND/INTERNAL 不能屏蔽 | QUERY/INTERNAL 本地动作是独立事件；混合不可分接口不能整段跳过 |
| 6 纯跳过 | Skip 策略零 operation/LocalAction/HTTP，结构/合同/迁移同事务 | 不通过业务接口探测来“跳过”；缺触发仍等待，不靠恒 true validator；跳过可禁本地动作 |
| 7 XOR/AND/多根终点 | 决策服务+持久 scope+原子屏障；多必需入站或多个必需本地效果分别是多个事件+AND | DAG 不是 Composite；回调/分支重复靠唯一事实而非 Mediator/Observer |
| 8 分类/异常隔离 | 类型化 effectResult+模式门禁，HTTP 与本地检查点原事实保留 | 网络错误不等于未发送，本地无 ACK 也不等于未提交；正常等待非失败，不强制每错误一套策略类 |
| 9 续跑/新 attempt | 持久单动作检查点+原能力版本+同 worker；IN/OUT 同 logicalCallKey 实际请求可多次，INTERNAL 同 localEffectKey 读取本地效果 | checkpoint 不是 GoF Memento/undo；新 attempt 不能换业务幂等键或 localEffectKey |
| 10 监督/休眠/优先级 | SnailJob 窄适配、持久控制意图、统一资格排序；未收敛 chainControl 不能因 O 终态关闭 | 不为每系统/类型加 Job，Observer 唤醒丢失由扫描修复，不叠加重试 |
| 11 调度描述 | QueryService 同库事实/资格，scope 内排名 | 普通查询不执行 validator HTTP，不建事件投影平台；等待无可执行 rank |
| 12 整体编排 | 普通应用协调+同一更新入口；多次调用/本地动作由多个事件/边组合 | 不建 TriggerOrchestrator/Mediator；部分成功不能“恢复快照”撤销外部或本地效果 |
| 13 Web/人审 | 父页协调/抽屉草稿/已有审批接线，简单确认直接命令；方向显示入站/出站/仅本地处理 | 页面副本不是 Prototype；审批完成也不直接业务 HTTP 或 LocalAction 过账 |
| 14 查询/抽屉/轮询 | 三个读服务+composable 代次保护；事件按方向关联 HTTP 请求或本地效果 | 不伪造入站 attempt/HTTP 行；轮询不是状态机，取消读不撤销已提交命令 |
| 15 编辑能力/代码锁 | 同一注册表+白名单，方向互斥地绑定一个调用合同或一个本地动作精确版本 | 注册表不是工厂；界面禁用不替代服务端 revision/capability 校验 |
| 16 审计/拒绝 | 正常事实显式事务审计，拒绝早于鉴权接线/回滚后保存；本地动作写 ActionAudit/检查点 | Spring AOP 是代理，不是可靠 Decorator；HTTP 观测不是 checkpoint，本地日志也不是 |
| 17 多来源入站 | 每个来源请求对应一个 INBOUND 事件；同 effectKey CAS 一次；多必需请求多个事件+AND | 来源次数不等于业务次数，不把所有系统客户端包装成抽象工厂 |
| 18 等触发/先后交错 | INBOUND 可等待业务触发；OUTBOUND 不等待自己的结果回调；INTERNAL 不经 WAIT_EXTERNAL_TRIGGER；I_RESULT 独立 | 不每个等待状态一类，不靠重复请求隐式人工确认/重试 |
| 19 HTTP/同步结果/接收事务 | INBOUND/OUTBOUND 参考 HTTP 封装；BUSINESS_COMPLETION 要业务证据；INBOUND 只消费本请求；INTERNAL 只在同一模块执行一个 LocalAction | 跨模块/服务仍不直接方法调用；同模块内部 Service 不被强制 HTTP 化；不另建通用 HTTP 平台 |
| 20 异步受理/等待 | OUTBOUND T2 先建 CALL 控制；O ACCEPTANCE→SUCCESS/ACCEPTED；I_RESULT 独立 INBOUND；permit 转 chainControl | 不在 O 上等 REMOTE_RESULT；不以 202 作为业务成功；INTERNAL 无 T2/CALL/permit |
| 21 早/晚/重复回调 | 回调归属 I_RESULT；关联 originOutboundEventId；早到 WAIT_PREDECESSOR | 回调 Controller 不接管 O 的 lease/终态；Observer 不能保证无丢唤醒 |
| 22 自调用/端点角色 | 同 JVM 跨模块拆 O/I 两个事件；接收 I 不等待 O 响应形成环；同模块纯本地效果用 INTERNAL | 不把 action 重新转发 trigger；类层次 Bridge 不解决递归调用 |
| 23 后端其他模块 | 跨模块业务+待发送标记事务、提交后 HTTP、每调用一个事件；同模块只有本地效果才用 INTERNAL | 不持业务锁 self HTTP，不透传 ThreadLocal、不自建消息总线 |
| 24 方向记录 | 每事件一方向；同 JVM 两事件两方向记录；INTERNAL 只有 ActionAudit/本地检查点 | 两方向观测不算两次抛账；INTERNAL 不伪造 HTTP 行；日志缺事件证据不自动重放 |
| 25 类型扩展 | 注册目录+方向兼容+一个调用合同或一个本地动作+冻结能力版本，统一引擎 | 不每类型/方向一个 Factory/State/Job，不把任意脚本当可扩展 |
| 26 仅本地处理 | 前驱/输入+系统确认→PENDING；一个 LocalAction 在自身事务/检查点产生一项本地效果；跳过零动作 | 不等待外部触发，不建 receipt/HTTP 方向/CALL 许可；不藏跨模块 HTTP 或第二个本地动作 |

## 21. 23 种模式的最终取舍与变化点

§9 的目录保留作选择索引，本节为全场景复核结论。每种模式都需要有真实变化点，不要求代码具有全部模式角色。

| 模式 | 最终取舍 | 场景依据 |
| --- | --- | --- |
| Factory Method / Abstract Factory | 不自建；注册表只查已装配 Bean，没有运行时创建产品族需求 | 1/15/25/26，同类型、多系统或不同方向也不需成套工厂 |
| Builder | 仅复杂图/快照组装按需；简单数据用构造/具名函数 | 1，校验不变量不等于建一套 DSL |
| Prototype | 不采用；显式复制服务 | 1/13，重新分配数据身份，不复制执行事实 |
| Singleton | Spring 生命周期即可 | 所有场景，实例上下文不得存可变 singleton 字段 |
| Adapter | 保留真正 DTO/认证/业务结果不兼容的窄边界 | 17/19/21，以及现有调度/审批接线；HTTP 本身不是 Adapter |
| Bridge | 不采用双层类族；普通组合即可 | 19/22/25/26，多系统/多类型/本地动作不自动形成两个抽象层次 |
| Composite | 不用于 DAG 执行；展示树可普通数据分组 | 7/14，汇合共享及活动 scope 不是树 |
| Decorator | 不新增业务装饰链 | 4/16，避免审计/重试包装改变副作用语义 |
| Facade | 保留少量应用服务/HTTP 命令和查询入口 | 2/3/12/17，不另加全转发的上帝门面 |
| Flyweight | 不采用；版本缓存是缓存 | 1/14，未出现享元对象拆分需要 |
| Proxy | 复用 Spring 事务/权限/切面代理 | 4/16，受理/失败审计仍有明确提交点 |
| Chain of Responsibility | 保留固定校验流水线思想，不建开放 handler 链 | 1/4/6/18，必需校验不允许动态略过 |
| Command | 保留有类型命令/处理入口与回执，不建 Bus/undo | 2/3/9/17，相同意图响应丢失可幂等重投 |
| Iterator | 集合及图遍历算法足够 | 1/7/14，不暴露可绕过约束的可变游标 |
| Mediator | 不新增独立对象 | 7/12/22，已知职责用应用服务协调 |
| Memento | 不按 GoF 实现；保留数据库 checkpoint/证据 | 8/9/20/26，外部世界与本地已提交效果都不能靠对象快照恢复 |
| Observer | 仅非权威通知加速 | 2/10/21，提交后唤醒失效由持久事实扫描恢复 |
| State | 数据驱动生命周期，不为每状态建类 | 4/8/18/20/25/26，状态转换只在统一引擎 |
| Strategy | 保留真实执行/跳过及 HTTP 调用或本地动作协议差异；单实现不强加接口 | 4/5/6/8/25/26，不扩成每事件或每方向一 Handler |
| Template Method | 固定顺序用方法组合，不使用继承模板子类 | 4/9/20/21/26，提交、结果和仅本地处理事件也走同一固定算法 |
| Visitor | 暂不采用，有序图校验足够 | 1/7，不为规则扩展提前给图节点造 accept 协议 |
| Interpreter | 不采用，配置仅为已注册合同的类型化参数 | 1/15/25，不增加脚本、表达式或规则平台 |

单动作约束是事件合同，不是新的设计模式。不为 INBOUND/OUTBOUND/INTERNAL、同步/异步或每个业务类型另建 Strategy/Factory；`SingleActionOperation` 按受控注册选择并在内部分支调用唯一 HTTP 合同或唯一 `LocalAction`，异步拆分由图的边和必需完成屏障表达。

## 22. 本轮新增验收与实施落点

以下为计划，不是已通过的业务测试。复用 M01-M12：M02 加回执/方向明细、事件等待字段及 INTERNAL 的非 HTTP 效果证据；M03 加方向互斥、INBOUND/OUTBOUND 唯一调用合同/effectKey 或 INTERNAL 唯一本地动作/localEffectKey 与发布校验；M04 加业务 HTTP 受理、多入站拆事件和同 effectKey 去重；M05 加三类方向的单动作执行、本地事务/检查点、异步提交/结果事件及续跑；M06 加关联查询；M07 加待回执/期限扫描及未收敛 chainControl 监督；M09 加双向/本地动作及触发审计；M10 加方向记录、等待、调用或本地动作选择；M12 在全部代码验收后交付完整接入/使用 Skill。M08 人审维持可选，不因类型扩展变成必须。

| 编号 | 场景/故障 | 可观察结果 |
| --- | --- | --- |
| D17 | 前驱/唯一入站请求/INTERNAL本地输入/确认任意顺序、缺少本事件请求 | INBOUND 等待原因准确，未就绪零发送；INTERNAL 在前驱/输入+系统确认后直接 PENDING，不进入外部触发等待；先确认事实保留；请求不能清失败门禁 |
| D18 | 同请求重投/异摘要、多来源同 effectKey/独立 effectKey、INTERNAL 同 localEffectKey | 原 ACK 可读回，异参冲突；同 effectKey 只应用一次；独立 INBOUND/INTERNAL 效果拆独立事件；本地效果只按 localEffectKey 应用一次 |
| D19 | 同 JVM 跨模块 O/I 事件先提交/回滚、HTTP 超时/重启、INTERNAL 本地事务回滚 | 仅已提交跨模块业务被触发，待发送可恢复；O/I 独立且无持锁 self HTTP/ThreadLocal 身份依赖；本地动作随自身事务回滚或保留可核对 checkpoint |
| D20 | HTTP 200/202 仅受理、客户端透明重试、响应丢失 | ACK 不证明 BUSINESS_COMPLETION；ACCEPTANCE 仅 SUCCESS/ACCEPTED；无隐藏重放；UNKNOWN 可对账；INTERNAL 不使用 HTTP ACK 判定 |
| D21 | 结果回调早于 I_RESULT 前驱、晚到/重复/乱序/冲突 | 回调先存 I_RESULT（可 WAIT_PREDECESSOR），不抢 O lease、只消费一次，矛盾结果待对账；INTERNAL 不生成回调 |
| D22 | 等 OUTBOUND 结果时人工暂停/取消、超期未知、终态迟到、INTERNAL 未知本地效果 | 外部等待可收证据不新发送；INTERNAL 不进入 WAIT_EXTERNAL_TRIGGER，未知效果只读检查点核对；未知均不盲目重发且资源不误释放 |
| D23 | A 已完成 B 失败、OUTBOUND HTTP 成功但 T5 失败、INTERNAL 本地动作提交但事件收口失败 | A 不重发；接收端事务不回滚；INTERNAL 已有本地效果证据时只补编排，不再次执行 LocalAction |
| D24 | 入站早于实例、同 JVM 两方向、旧日志无事件、跳过、INTERNAL 执行 | 只有 INBOUND/OUTBOUND 分开关联 HTTP 记录；INTERNAL 无假 attempt/HTTP 行/receipt/permit，双计业务次数或按日志重放均禁止；跳过零动作 |
| D25 | 新事件类型/版本、旧调用或 LocalAction 缺失、不兼容方向 | 新类型无需改执行器，旧快照不变，缺能力阻断；同事件多调用/多本地动作或方向能力不兼容时发布拒绝 |
| D26 | BYPASS 同系统/非本系统与混合捆绑接口、BYPASS+INTERNAL | 只屏蔽 OUTBOUND 非本系统 EXTERNAL_POSTING 的唯一调用；入站不能假装未收到；INTERNAL 仍按合同执行或走 Skip；QUERY 各自事件；不支持屏蔽的捆绑合同拒绝 |
| D27 | 中文调度名创建/改名、同名不同业务、名称超长 | 后台 job_name 为可辨识中文；bizId/执行器标识不变且不重复建任务，超长明确拒绝 |
| D28 | 所有前端/运维展示名及字典故障 | INBOUND/OUTBOUND/INTERNAL 显示入站/出站/仅本地处理；其余中文展示、英文代码；未知码以中文提示加原码，改名不影响身份/关联 |
| D29 | 入站关联等待事件、早于挂载/attempt、多来源/多请求 | 一个 INBOUND 事件一个逻辑请求；多来源可有 receipt 证据但唯一 canonical；多必需请求拆事件 AND；effectKey 仅应用 canonical 一次 |
| D30 | 发布同事件多方向、`steps[]`、多 operation、多信号槽、IN/OUT 缺调用或 INTERNAL 携带网络/回执/许可/第二个本地动作 | 发布明确拒绝；多入站拆多个 INBOUND 事件+AND，多出站拆顺序/并行事件，入站后出站至少两个事件，多个本地动作拆多个 INTERNAL 事件 |
| D31 | O_SUBMIT ACCEPTANCE 成功但未收敛、I_RESULT 早到/迟到、O 已终态 | T1 只预检，OUTBOUND T2 同事务取得容量/创建 CALL/SEND_INTENT；O 仅 SUCCESS/ACCEPTED；I_RESULT 独立进入屏障；I/QUERY/安全终止凭同一控制释放一次；O_UNKNOWN 判定一致；O 终态未收敛监督不关 |
| D32 | 在 validator/revalidate 中尝试外部查证、QUERY 容量已满、INBOUND 转发、BYPASS 后等回调、INTERNAL 发查询 | validator 只读证据；QUERY 仅走预发布恢复分支、挂靠原 CALL 不另占容量/不进入正常屏障；O 非 UNKNOWN 终止时 QUERY=NOT_SELECTED；跨模块业务拆 OUTBOUND；BYPASS 的 I_RESULT 必须有替代 skip；INTERNAL 只读本地检查点 |
| D33 | 同 JVM self HTTP O/I、重复投递/重试、未知结果、取消 | O/I 各自事件与方向记录且无响应-前驱环；self 默认可靠 ACK 后响应，同步业务完成由接收端请求事务保存证据；重试沿用同一 logicalCallKey；INTERNAL 不参与 self HTTP；查询恢复须显式准入；UNKNOWN/人工门禁/租约取消安全不变 |
| D34 | INTERNAL 前驱/输入/确认、一个 LocalAction、事务提交/回滚、重试、Skip、跨模块或第二动作 | 满足门禁后 PENDING 而非 WAIT_EXTERNAL_TRIGGER；零 HTTP/收发 receipt/方向记录/SEND_INTENT/CALL permit；只执行一个已注册 localActionKey/version/localEffectKey；检查点或事务证明效果，UNKNOWN 不伪造 ACK；Skip 零动作，第二动作/跨模块/隐藏 HTTP 发布拒绝 |

状态/回执/并发与扫描竞态使用隔离 MySQL 8.0 验证；INTERNAL 另验证本地动作与效果检查点的事务提交/回滚、唯一 localEffectKey 和零 HTTP/permit；HTTP 协议用本地受控桩覆盖响应断开、先回调后 ACK、超时、重复和身份拒绝，不调用真实过账。实际浏览器验证入站、出站、仅本地处理的等待原因、方向记录、类型能力和命令冲突。P1 另明确每个事件的方向、唯一调用合同或本地动作、effectKey 或 localEffectKey、来源协议、同步返回合同、各模块端点、结果回调证据/期限及出入站历史记录来源；不把这些未决值假定为已部署事实。

M07 必须实现统一中文任务命名：监督使用“计划监督－业务中文名－计划类型中文名－范围中文名”，worker使用“计划事件－业务中文名－事件执行－职责中文名”。中文展示名写入 SnailJob job_name，长度以实际列/客户端约束为准；bizId、executor 名及恢复关联保留技术键。命名/改名与控制意图有审计，更新展示名不重建任务。

所有前端展示名称都使用中文 displayName（计划、事件、状态、类型、系统、分支、调用、本地动作及任务），允许必要缩写/编号；代码中的 class/method/field/enum/code/permission/path/executor/bizId 仍英文。发布与注册校验中文名称非空且不只是技术码，不扩展为全栈多语言平台。状态字典不可用用内置中文映射兜底；未知码显示“未知状态（code）”，历史缺名称显示“未配置中文名称（code）”，不只显示英文。D28 验证全部页面、类型选择/事件链/方向记录/中文调度名与技术键分离，INBOUND/OUTBOUND/INTERNAL 分别显示“入站/出站/仅本地处理”；改展示名不改变旧实例身份、调用协议或本地动作键。

## 23. 单动作最小化说明

本节把“一事件一方向、一个外部调用或一个本地动作、一个效果键”固定为实施和接入的硬约束。它不是把多个 HTTP 放进一个 operation，也不是在一个事件里先入站再转发；事件是调度、确认、租约、恢复和审计的最小单位。一个事件只处理一个逻辑动作，复杂流程由多个事件和边表达。

- 配置边界：`eventDirection` 只能为 `INBOUND`、`OUTBOUND` 或 `INTERNAL`，中文分别显示入站/出站/仅本地处理；源/目标状态各一个。INBOUND/OUTBOUND 互斥且各有一个 `callContractKey/version` 和 `effectKey`；INTERNAL 的调用合同/effectKey 必须为空，并恰有一个 `localActionKey/version` 和 `localEffectKey`。发布拒绝 `steps[]`、多个 operation、多个信号槽、同一事件多方向、IN/OUT 缺调用、INTERNAL 携带 HTTP/回执/许可或第二个本地动作。`EventTypeDescriptor` 的 `supportedDirections` 必须与冻结方向兼容，并按方向提供唯一能力。
- 仅本地处理：INTERNAL 不进入 `WAIT_EXTERNAL_TRIGGER`；前驱和唯一输入满足、系统确认成立后直接 PENDING。唯一 LocalAction 在自身事务或明确检查点边界执行一项受控本地业务效果，同库时应让业务修改、幂等事实和本地效果检查点一同提交；它不能伪装 HTTP ACK，也不能用日志、进程事件或成功文案代替检查点。需要其他模块、服务或远程系统交互时必须建 OUTBOUND 事件；接收 HTTP 请求并在自身端 Controller/Service 事务内处理仍是 INBOUND。Skip 可禁用该本地动作，跳过时零动作、零业务效果、零 HTTP。
- 多入站：每个 INBOUND 事件只接纳一个有效逻辑请求；多个必需请求分别建事件并以 AND 汇合。不同来源转发同一 effectKey 时，可在同一逻辑调用下保留多条 actual request 记录并 CAS 应用一次；独立 effectKey 必须另建事件。
- 多出站：每个 OUTBOUND 事件只发一个 HTTP 合同；多个出站按依赖拆顺序事件或并行分支。入站触发后需要出站抛账，至少是 INBOUND → OUTBOUND 两个事件；不能在同一 I 内直接调用跨模块 Service 完成后续业务。多个独立本地效果同样拆成多个 INTERNAL 事件，每个事件只保留一个 localActionKey/version/localEffectKey。
- 异步提交：O_SUBMIT（OUTBOUND）使用 `completionContract=ACCEPTANCE` 时，T2 已创建持久 CALL 控制，可信 ACK 后事件可 SUCCESS/outcome=ACCEPTED，只证明提交。必须另建 I_RESULT（INBOUND，`WAIT_EXTERNAL_TRIGGER/RESULT_CALLBACK`）作为必需后继/完成屏障；回调归属 I_RESULT，并带 `originOutboundEventId`、effectKey 和可信来源关联。O 不再进入自己的 REMOTE_RESULT 等待，也不消费回调。早到回调先持久化为 I_RESULT 输入，可为 WAIT_PREDECESSOR；O 提交后由扫描唤醒，不依赖进程内通知。
- 同步出站：`completionContract=BUSINESS_COMPLETION` 必须取得可信业务完成证据。HTTP 200/202、受理号或 ACK 只能证明交互/提交，不能把事件标成业务完成。
- 未知与证据：O 无可信 ACK 或响应丢失时保持 UNKNOWN/人工对账，不能按 ACCEPTANCE 自动成功或重发。可信早到回调可作为 O 恢复的只读证据，但所有权仍归 I_RESULT，不能回绑成 O 的第二项入站调用；需要主动 HTTP 查证时只能走发布时确定的 OUTBOUND QUERY 恢复分支 `recoveryForEventCode`，仅 O_UNKNOWN + 显式 recoveryRequested 准入，不依赖 O_SUCCESS、不开放普通后继、运行中不改图。查询证据只能供原 O 只读收口，不能替代必需 I_RESULT 或伪造回执。
- 资源：T1 只做资格预检、不扣业务容量。仅 OUTBOUND 的 T2 同一事务完成容量占用、创建 `schedule_control(scopeType=CALL, scopeKey=原 O eventId)` 的 INTENT/IN_FLIGHT、写入 `SEND_INTENT/requestId/审计`；T2 无容量则零发送并安全回退，不能等 ACK 才建控制。INTERNAL 不占业务容量、不建 CALL control/SEND_INTENT/调用许可，只使用事件 lease 和本地检查点。O ACCEPTANCE 取得 ACK 后，同事务转 ACCEPTED/WAIT_RESULT 并交接 originOutboundEventId，不按普通成功 release；ACK 丢失/崩溃仍占用。I_RESULT、预发布 QUERY 或安全终止凭同一 CALL control CAS 释放一次；I_RESULT 认领继承。O 终态但链未收敛时监督保留控制。
- QUERY 恢复：预发布 recoveryForEventCode 不在正常 expected；仅 O_UNKNOWN+recoveryRequested 创建 token，O 其他可终结状态完成时 NOT_SELECTED，QUERY 成功不入正常完成屏障。QUERY 不参加业务容量预检/扣量，挂靠原 CALL control、不另取 permit，避免原 O 占满容量造成对账死锁；它是独立 QUERY intent/request，证据只读供 O。
- BYPASS：只适用于 OUTBOUND、非本系统、`EXTERNAL_POSTING` 的唯一调用。INBOUND 不能以 BYPASS 假装未收到；INTERNAL 不能因 BYPASS 被禁用，仍按前驱/输入和系统确认执行或走 Skip；QUERY 动作各自建独立事件。BYPASS 的 O 不真实发送时，I_RESULT 不会有真实回调，发布必须为 I_RESULT 配置显式屏蔽替代 skip 合同，否则拒绝配置，避免永久等待或伪造回调。
- 校验与内部调用：StateValidator 只读取本库已持久证据、本地动作检查点和结构；`revalidate` 不发起业务 HTTP；人工对账只提交证据。接收端处理当前 INBOUND 请求的内部 Service/Mapper 可正常调用，但触发后要跨模块执行的后续业务必须另建 OUTBOUND HTTP 事件，不得隐藏转发。INTERNAL 只调同一模块唯一 LocalAction，不生成查询/回执，也不得通过它的内部实现隐藏跨模块 HTTP 或第二个业务动作。
- 同 JVM self HTTP：O 和 I 各自是独立事件、独立方向记录和独立租约；I 不能以前驱等待 O 的同步响应形成环。self 默认取得可靠 ACK 后即可响应，不等 worker 消费 I；若必须同步业务完成，接收端在自身请求事务完成本次唯一业务效果并保存证据，I 只消费证据，HTTP 线程不能等待 sender 已占用的 worker/资源。类型/发布检查可把跨模块 self 调用拆成并行关联事件，但必须区分原 ACK 语义与内部业务完成，不能用进程内通知替代持久回执；同模块纯本地效果使用 INTERNAL，不制造 self HTTP。
- 重试与重复投递：INBOUND/OUTBOUND 的同一 `logicalCallKey` 可跨 attempt 和多次实际请求记录；INTERNAL 的同一 `localEffectKey` 跨 attempt 不变。重复投递/执行沿用原效果键，不产生新逻辑调用；INTERNAL 已有本地检查点只恢复编排，未知效果只读核对。不能借重试名义增加第二个调用合同、第二个 HTTP 接口、第二个 LocalAction 或事件内多步骤。
- 历史证据：旧 PCBA 的逐请求上下文、多请求关联和 ThreadLocal 覆盖证据继续保留在 evidence/参考节；旧实现确实可能一次业务方法发多个 HTTP，但这只是历史事实，不代表目标支持多个调用。它们不是目标多步骤事件合同，也不得作为发布校验的通过依据。

M12 使用指南必须从本节生成接入检查：先选 INBOUND/OUTBOUND/INTERNAL；INBOUND/OUTBOUND 再绑定唯一调用合同和 effectKey，INTERNAL 再绑定唯一 LocalAction、localEffectKey 与事务/检查点；最后决定多入站 AND、多出站/多本地动作事件或异步 O-I 拆分。示例应包含三类正确接入，以及“同事件多方向/多步骤/同事件回调/INTERNAL 网络调用/INTERNAL 第二本地动作/INBOUND 或 INTERNAL 使用 BYPASS”被拒绝的反例。M12 仍在全部代码完成并通过最终验收后独立交付，继续使用 darwin-skill 优化，保持中文展示名与英文代码标识分离，HTTP 参考实现只作兼容复用，不另建平台。

## 24. 长期监督计划、两类模式与实施就绪复核

唯一行为规格见[主方案 §17](plan-event-state-machine-design.md#17-两类长期监督调度计划的实施契约)。本节只映射代码职责/依赖和验收，不复制一套可独立漂移的规则。此前“所有入口只受理后排队”适用于 QUEUE；IMMEDIATE 专用入口返回同步结果。“监督不执行业务事件”仍成立，监督执行的仅是自己 DECISION/LOCAL 的调度事件。

### 24.1 最小职责及模式选择

统一术语：前文INTERNAL“不扣容量/不占permit”专指CALL资源池；所有BUSINESS计划，包括仅INTERNAL计划，仍受监督PLAN_SLOT限制。监督自身不占子计划名额。QUEUE与IMMEDIATE的资源/失败/重试差异以主§17.4-17.8为准，不从通用人工重试/自动退避规则推导即时后台重做。

| 实施区域/拟议文件 | 本次必要职责 | 复用/约束 |
| --- | --- | --- |
| domain/PlanDefinition、PlanInstance、EventDefinition/Mapper | planRole/eventRole、监督唯一绑定/轮次、业务supervisorInstanceId、modeSnapshot/holdStart/firstAdmittedAt；目标唯一键见主§17.7 | 复用原表与CAS，M02补字段/索引，不新建第二套监督事件表 |
| service/SupervisorService | 绑定scope、版本切换、pause/resume/restart-worker、轮次准入与fencing；以最小方法协调既有事件更新 | 不为每个业务或QUEUE/IMMEDIATE建Controller/Job；版本切换无迁移半轮 |
| service/SupervisorRoundService | 有限轮次开始/续原轮/关闭，roundNo+eventKey/localEffectKey，最多一个OPEN；决策结果持久化 | 单一事务协调，不将长期监督图改成有环状态机；不每轮复制定义版本 |
| runtime/注册调度LocalAction | 精确DECISION/LOCAL目录；每事件一项选择或本库调度效果，逐候选独立LOCAL；提交时重验候选 | 复用SingleActionOperation/LocalAction与BranchSelector，类型能力限制+发布校验；不用通用规则脚本 |
| service/EventEligibilityService及原候选查询 | 监督绑定/名额/holdStart/mode、原前驱确认/资源资格；保底窗口和rank同源 | QUEUE/IMMEDIATE仅两个明确分支，不为两个分支新增Strategy接口层；仅出现真实第三种变化时再评估 |
| service/ImmediateExecutionService | 命令幂等→监督准入→独立同步业务驱动→确定结果/未知收口；执行期限每事件重验 | 与队列使用同EventUpdateService、同失败分类和checkpoint；不能复制第二套执行引擎或在LOCAL中调用业务 |
| service/PlanInstanceService及终结事务 | PLAN_SLOT整计划持有/释放；未开始挂起/放行/优先级；不安全终态拒绝 | 取得/释放从统一锁序起点进入，名额不是CALL permit；本地业务计划同样占PLAN_SLOT |
| job/BusinessScheduleExecutor、SchedulerAdapter | 按监督实例推动轮次；本库控制意图提交后幂等应用SnailJob，记录desired/observed | 原两个固定执行器；平台HTTP是事务外控制接线，不是监督事件业务HTTP |
| ExecutionOverviewService、SupervisorQueryService及VO | 类型/范围监督总览、轮次/调度记录、子计划占用/排队分类；命令回执 | 复用ActionAudit、PageResult/数据权限；只读查询不触发调度 |
| Web schedule API/types、SupervisorDetailDrawer、DispatchRecordTable | 中文模式/占用/轮次、记录筛选导航、QUEUE操作、IMMEDIATE结果和断线回查 | 复用request/iwsFeedback/usePlanExecution；只按resultCode=SUCCEEDED报成功，不改全局请求拦截器 |

使用应用服务+既有注册能力+本库事务即可覆盖，不将“监督”再包装成Mediator、通用Orchestrator、Observer可靠消息或任意脚本执行器。Decision 输出为持久事实，不是靠UI推导的临时排序；SchedulerAdapter 仅转换控制平台协议，不负责子计划业务。

### 24.2 纳入既有工作包

| 工作包 | 必须增加的内容/依赖 | 可交付边界 |
| --- | --- | --- |
| P1 | 两条真实链路补 mode/scope/activePlanLimit/K/即时期限/业务幂等及安全失败合同 | 没有真实映射可先做公共能力，不擅定首批业务 |
| M02 | role、监督control/round、PLAN_SLOT/命令唯一约束、审计调度关联；复核MySQL索引/NULL语义 | 用实际MySQL兼容隔离库验约束/事务；本轮无DDL |
| M03 | 监督图编辑/发布门禁、方向角色交叉、即时同步完成性、参数范围、版本切换兼容 | 监督事件只允许已注册调度能力，不用管理员权限绕过 |
| M04/M05 | 监督绑定及QUEUE挂起/准入；即时幂等同步驱动与期限/失败；PLAN_SLOT安全释放 | M05必须先支持同一updater在队列/同步两种驱动下使用；不可等M07才复制执行流程 |
| M06 | 监督轮次/调度记录及queue/slot分类读模型、命令结果 | 与认领同资格/排序/数据权限/快照 |
| M07 | 在上述事务/读模型上接SnailJob，控制恢复与重启；不承担未设计的业务定案 | QUEUE真实启停联调；IMMEDIATE不依赖tick返回 |
| M09/M10 | 调度意图/平台结果/拒绝审计；监督抽屉、记录表、按钮权限、即时错误和回查 | 领域结果与R传输成功区分、版本冲突重取快照 |
| M11/M12 | 两条真实链路验证D35-D42；全部验收后使用Skill覆盖监督接入/两模式/禁止误用 | 不能提前声称已联调或生成“可用”使用Skill |

依赖次序按“角色/唯一约束→发布→准入/执行→查询→平台接线与Web→真实映射演练”；M07不是单独的定时任务外壳。已有M01-M12编号保留，不另建重复票据。

### 24.3 新增验收 D35-D42

下表都是后续代码验收计划，本轮只完成文档一致性和图表渲染；Mock/渲染不能证明持久事务及并发正确。

| 编号 | 输入/故障注入 | 必须断言的可观察结果 | 场景/工作包 |
| --- | --- | --- | --- |
| D35 | 同业务同type/scope并发创建监督；不同业务/类型；改请求scope；非法监督IN/OUT/业务LocalAction | 唯一一个监督；不同范围独立；scope只由注册事实解析；非法角色/网络/业务动作发布拒绝；每轮有限DAG | 27，M02/M03/M04 |
| D36 | QUEUE limit=1与N；仅INTERNAL业务；已准入后等callback/人工/未来重试/暂停；安全终态重复回调 | 同时HELD PLAN_SLOT不超limit；等待仍占；纯本地业务也占；安全终结释放一次，CALL独立继承/释放 | 28/31，M04/M05/M07 |
| D37 | holdStart挂载/放行、暂停期间挂载、插队并发与持续高优先级、窗口末位候选失效 | 挂起CREATED/no-slot/rank=null；放行不越门禁；已准入hold拒绝；不抢占；K次成功准入至少一普通候选；失效不消耗保底，UI/rank一致 | 28/29，M04/M06/M10 |
| D38 | 两节点同时即时执行、同commandKey重投/异参、换key同taskKey；即时attach/后台worker误触发 | 一胜一BUSY；无排队/202成功；同键回原结果/PROCESSING，异参拒绝；任务不重复/名额不重复；QUEUE入口不能执行即时任务 | 30，M02/M04/M05 |
| D39 | 即时本地成功、同步HTTP确定失败/部分成功、异步图/人工等待图、未知结果/断线/期限 | 整计划COMPLETED才SUCCEEDED；确定失败即时FAILED/FAILED_FINAL无后台重试；异步图发布拒绝；未知/超时明确错误且保留slot，命令GET不触发执行 | 30/31，M03/M05/M09/M10 |
| D40 | 决策后候选失效、LOCAL提交前后崩溃、同轮双tick、旧epoch迟到、新唤醒与旧observed冲突 | STALE_CANDIDATE不强执行；同轮/效果键不变、最多一个OPEN；旧代次拒绝；意图已提交可补做，平台ACK不误清新revision；不重发业务效果 | 27/31，M02/M05/M07 |
| D41 | 人工监督暂停/恢复、restart-worker、版本切换含open轮/名额/UNKNOWN CALL/队列不兼容 | pause不被auto覆盖；暂停restart拒绝；平台重启只修控制；未清空安全事实不能切模式/定义；合法切换仍是同一监督身份，旧轮记录可追溯 | 27/31，M03/M07/M09 |
| D42 | 调度记录筛选/翻页/跨数据范围、控制平台失败、角色按钮、即时R.code=200但resultCode=FAILED、网络reject | 轮次→事件/attempt→子计划可导航；待平台应用明确；后端权限/版本检查；错误立即显示并保留instance/queryUrl；仅SUCCEEDED成功；断线沿原key查询且不自动换key重做 | 32，M06/M09/M10 |

D39额外断言：准入持久executionDeadline，重投不延长；剩余预算不足不启动动作；慢HTTP/限时本地动作与响应期限竞争时，超时立即明确返回、stopRequested阻止后继，晚到只保存证据/安全收口。所有即时路径声明预算及safeFailureContract，缺一发布拒绝；运行时合同违反为FAILED_HOLD，保留slot至显式安全终结，不能伪称结果未知或强制释放。D40额外覆盖显式recoveryOnly的唯一worker例外：仅RECOVERY_ONLY资格、原PLAN_SLOT/QUERY控制、无在途原动作、不重试普通业务、不执行超时后继；无显式命令及原动作仍活跃均拒绝认领。D41额外覆盖tick与即时请求争OPEN轮次返回SUPERVISOR_BUSY，以及暂停只收口/关闭旧轮次，不能永久OPEN阻断版本切换。

DB约束/并发/回滚使用MySQL兼容隔离环境；外部成功/未知/部分失败用响应桩及固定业务证据；真实SnailJob另验意图→观测/重启，真实浏览器另验前端失败/轮询/权限和记录导航。公共能力自检不代表两条真实业务端到端已验收。

D40再覆盖原executionDeadline已过的显式恢复：仅该命令的RECOVERY_ONLY以独立有限recoveryDeadline通过认领/QUERY发送门禁；原期限不变、stopRequested不清，普通业务/后继继续拒绝。恢复期限到期不自动续期/重试，仍保持原PLAN_SLOT/CALL。

### 24.4 前后端实施是否会跑偏

| 需求 | 唯一规格入口 | 开发不能自行改变的约束 |
| --- | --- | --- |
| 各业务各类独有长期监督 | 主§3.3/§17.1-17.3 | 是实际SUPERVISOR计划及轮次，不退回“仅配置+扫描日志”；唯一scope、版本/事件身份和恢复不重置 |
| 仅决策/本地事件调度 | 主§17.2 | DECISION/LOCAL INTERNAL；业务驱动/控制平台接线在事件外，禁止把跨模块业务装成本地调度动作 |
| 队列串并行、挂起和插队 | 主§17.4 | PLAN_SLOT从准入到安全终态；挂起无名额/rank；排序/公平服务同源；不抢断 |
| 即时单计划及即时结果 | 主§17.5/§17.8 | 无异步排队/自动retry，整个计划成功才成功；UNKNOWN占用保留，客户端断线查原命令；resultCode控制反馈 |
| 展示调度记录和运维控制 | 主§17.6-17.8 | 领域意图和平台观测分列；稳定轮次/事件/子计划关联、权限/版本/数据范围、中文展示 |

结论：补充后公共前后端实施边界可明确评审，M01-M12 有具体落点及 D01-D48 验收依据；进入最终审阅。首批真实链路的P1业务事实与数值仍须补齐，不能在实施中凭示例猜定，更不能把该结论说成业务代码/数据库已经完成。用户本轮只授权文档完善，不授权创建业务模块、执行DDL/DML、启动真实计划或提交推送。

## 25. 外部系统引用与计划内一次性屏蔽配置

行为规格以[主方案§18](plan-event-state-machine-design.md#18-独立系统端点模块与基础配置可视化页面)为准。系统、环境、共享地址及 `systemCode + addressId` 关联全部由独立 `iws-endpoint` 持有；计划侧入口为 `/process/definition → definition/edit.vue → 系统模式 → ExternalSystemPostingDialog`，该弹窗只汇总计划事件的系统引用与模式。查看或维护共享地址跳独立 endpoint 基础页，返回后重新读取 `catalogRevision`、目录快照和影响预览；不把地址或关联写操作塞入计划弹窗。对各事件收集系统并配置模式，不允许扩展单事件为多系统/多调用。

### 25.1 最小实现落点

| 工作包/文件职责 | 新增内容及复用边界 |
| --- | --- |
| M02 endpoint系统目录/地址/关联 | 独立 `iws-endpoint` 内实现 `iws_endpoint_system(systemCode 唯一)`、`iws_endpoint_environment(environmentCode 唯一，中文名/enabled/revision)`、`iws_endpoint_address(environmentCode+endpointRef 唯一，protocol/host/port/basePath/enabled/revision/credentialRef)`、`iws_endpoint_system_address(systemCode+addressId 唯一)`、`iws_endpoint_catalog_control(单行全局 revision)` 和 `iws_endpoint_audit`。地址、关联和审计只在 endpoint 保存；不照搬凭据值、toString 或实体出参，不把 `sys_client.ip_whitelist` 当出站地址 |
| M02 endpoint reader/shared gate | `EndpointCatalogReader` 只读系统/环境/地址/关联 snapshot，并在消费者同一数据库事务中先对 `iws_endpoint_catalog_control` 取共享锁、比对 revision；endpoint 写目录先独占锁控制行，再改目录/关联、写 audit、递增 revision。统一顺序为目录控制→definition，endpoint 不反查 process；第一版限同库同一事务管理器 |
| M03 计划聚合服务 | 从完整定义/合同生成ExternalSystemPostingOptionsVO；按计划系统归属去重，记录policy/target/caller角色；`targetSystemCode + endpointRef` 仅经 `EndpointCatalogReader` 解析到 endpoint 的 `iws_endpoint_system_address`，process 不自建系统地址关联；保留所有分支/disabled/QUERY/结果事件；引用不一致报错，不按IP猜系统、不在 process 复制地址 |
| M03 原system_policy保存 | 同一汇总/校验方法服务于GET、影响预览、完整向量PUT及原单行patch；保存先经 reader 取得目录 shared gate/snapshot 并校验 catalogRevision，再按固定锁序锁 definition/system policy；按系统一模式、命令幂等、全部事务原子/审计，不在Controller循环调用旧接口 |
| M05 原HTTP发送边界 | 未发送用受控环境端点，T2/SEND_INTENT冻结environment/endpointRef/revision/目标摘要和原请求目标；相同逻辑调用重试不因目录换IP漂移，UNKNOWN不自动切换目标；BYPASS在地址解析/外呼前合法匹配，零发送 |
| M09 审计/权限 | endpoint 使用 `endpoint:system/environment/address:list/query/add/edit` 与 `endpoint:audit:query`；系统地址关联更新需 `endpoint:system:edit` 且关联地址需 `endpoint:address:query`。endpoint 独立写 `iws_endpoint_audit` 并复用 `@Log` 补充；process沿用 `definition:query/edit`，action audit 关联 `catalogRevision`，不反向依赖 endpoint AuditService。目录影响与计划策略影响分列；敏感凭据不返回，引用身份只停用且不改 code，模式变化逐系统前后值同事务审计 |
| M10 definition/edit.vue | “系统模式”页签复用useDialogState/ElTable，新增ExternalSystemPostingDialog；共享地址查看/维护跳独立 endpoint 页面，保持 environmentCode、systemCode、endpointRef 筛选，返回重取 catalog+预览。打开汇总前处理单事件未保存内容 |
| M10 API/types/纯展示 | process新增GET external-systems、POST preview、PUT system-policies；endpoint的完整 API、关联与审计接口见 §26。计划摘要中，具 `endpoint:address:query` 且同范围者可看实际 IP；无该权限只显示别名/关联状态。搜索后仍提交完整向量；只勾选系统设BYPASS，其余保留mode，取消BYPASS必须明确目标mode |
| M11/M12 | 完整图多分支、多系统/同IP及目录变更验收；最终使用Skill纳入“计划配置内一次性屏蔽”入口、无网络预览和禁止隐式历史覆盖 |

仅复用已有Spring/MP/事务、权限、CRUD表单、分页和参考IP解析方式；不新建外部集成平台、网络探活模块、按IP分组过账引擎或独立事件配置器。系统/环境/地址只由 endpoint 配置模块写入，计划表只保存目标系统与端点引用；不在每个事件复制可变地址/模式。参考项目的批量retry不是批量配置接口，不能直接拿来执行保存。

### 25.2 新增验收 D43-D46

以下为后续实现验收；本轮仅文档/源码及结构只读核验。

| 编号 | 场景输入 | 必须观察的结果 |
| --- | --- | --- |
| D43 | 完整图含MID/SAP/MES、同系统多接口、多IP、不同系统同IP、未选分支、disabled/恢复QUERY、INTERNAL | 后端读取完整版本，不靠页面分页/已选路径；systemCode去重且caller/target/endpoint分责；各事件一合同；可屏蔽/不受影响/disabled清晰分组；无凭据字段 |
| D44 | 只屏蔽MID，其它原AUTOMATIC或MANUAL；过滤列表后提交；取消既有BYPASS；监督/旧版本/运行实例 | 只指定系统合法出站抛账零发送；其余保持原mode及门禁；取消需选目标mode；完整向量不删未见系统；监督不可配/旧版只读/旧实例快照不变；计划系统模式弹窗与独立 endpoint 基础菜单互不替代 |
| D45 | 多系统批量保存中一行非法、图revision变、目录端点并发改、未保存单事件；缺失I_RESULT替代skip | 任一不符整批回滚且无部分审计成功；单行入口同全图校验；目录/定义冲突重取；未保存内容先处理；缺回调替代合同拒绝；preview不探活、不发送 |
| D46 | 端点缺失/停用/重复、BYPASS合法skip、QUERY仍需发送、改IP后未发送/原SEND_INTENT/UNKNOWN重试 | 地址状态不自动BYPASS；仅合法屏蔽事件可无地址零发送，必发事件缺地址阻断；首次发送解析受控环境、T2记录目标版本；重试不漂移新IP/不故障切换/不改变模式，同IP其它系统不受屏蔽 |

结构/并发/CAS及整批回滚用MySQL兼容隔离库；发送/零发送/重试目标以HTTP桩断言；浏览器分别检查独立 endpoint 基础页和计划系统模式弹窗，覆盖页面往返、批量勾选/取消/冲突/权限和影响预览。源码参考只证明来源有IP解析，不能证明目标已可执行该菜单。

D43/D45补充 caller=SAP而policy=MID_WMS的INBOUND：displaySystems可含SAP，但policySystems只含真正的政策归属；SAP无其它政策事件时显示“无政策归属/currentMode=null/modeEditable=false”，不提交/创建SAP策略、不伪造默认mode。INTERNAL所需政策仍保留于完整模式向量，过滤菜单不能误删。

## 26. 独立 endpoint 模块启停、依赖与可视页面验收

### 26.1 模块边界、装配与配置引用

| 边界 | 实施约束 |
| --- | --- |
| endpoint 依赖 | `iws-endpoint` 只依赖公共基础能力，不依赖 process、job 或 wms；包根固定为 `com.innolux.iws.endpoint`，可单独启用 API 与基础页面 |
| process 依赖 | process 源码依赖 endpoint 发布的窄只读配置接口，POM 声明 `optional=true`。该声明只控制 Maven 下游传递，不省略 process 自身编译依赖，也不替代宿主所需的 endpoint jar/Bean；缺 jar、缺 Bean 或 process 开关为 true 时启动明确失败 |
| 版本与装配 | endpoint、process 版本由根 BOM 集中管理；admin 显式装配两个模块。无条件配置只读取字符串开关并报告缺失，涉及 endpoint 类型的扫描与 Controller 放进 `@ConditionalOnClass`+开关双条件内部配置，避免禁用 process 时提前链接缺失类型 |
| 业务调用边界 | 配置目录读取是基础配置能力；所有跨模块业务 action、触发、结果和查询仍按一事件一调用走 HTTP，不以 Spring Bean 调用替代业务合同 |
| 系统地址关联 | `iws_endpoint_system_address` 由 endpoint 独立持有，以 `(systemCode, addressId)` 唯一；关联只表示系统可使用该地址，不保存 apiPath/业务动作，一系统可关联多个地址，一地址可供多个系统共享。endpoint 不读取 process 合同，process 不保存第二套关联 |
| 配置读取边界 | process 只通过 `EndpointCatalogReader` 读取系统、环境、地址、关联、`snapshot` 和同库 shared gate；不取得 Mapper，不接收 process Service 注入。共享地址及关联新增/修改/启停只走 endpoint 自有写 Service |
| 目录 revision 与锁序 | `iws_endpoint_catalog_control` 为唯一全局控制行，目录任何变化均递增 revision。endpoint 先独占锁控制行，再原子修改目录/关联、写 `iws_endpoint_audit` 并递增 revision；process 先经 reader 对同一控制行取共享锁并读 snapshot，校验 revision 后再锁 definition/system policy。统一顺序为目录控制→定义，禁止反向加锁或 endpoint 查询 process 引用。第一版限同库、同一事务管理器；远程配置服务与跨 HTTP 原子性不在范围 |
| 审计边界 | endpoint 独立写 `iws_endpoint_audit`，现有 `@Log` 只补入口日志；process action audit 记录所用 `catalogRevision`，不反向依赖 endpoint 审计 Service，也不把 endpoint 配置审计并入 process 动作审计 |
| 身份与凭据 | `systemCode`、`environmentCode` 为稳定键；被引用身份停用时不删除、不改 code。真实地址和凭据不从参考源复制。具 `endpoint:address:query` 且同范围者可在 endpoint 配置页及计划摘要查看实际 IP；无该权限时计划摘要只显示别名/关联状态。凭据只保存 `credentialRef`，API/VO 返回 `hasCredential`，不回显凭据或额外连接配置 |
| 当前环境 | endpoint-only 允许空目录且不要求配置当前环境；process 启用时 `iws.endpoint.current-environment` 必须非空并指向 enabled 的 `environmentCode`。页面当前环境标记只读自部署配置，选择或编辑其他环境不会切换运行时 |
| 运行冻结 | 计划实例的 mode 快照不因 endpoint 配置修改而变化；`SEND_INTENT` 冻结目标环境、endpoint 引用及 revision/摘要，后续共享地址修改不得让已发送意图或 UNKNOWN 重试漂移到新目标 |

### 26.2 启停组合与启动门禁

| `iws.endpoint.enabled` | `iws.process.enabled` | 必须结果 |
| --- | --- | --- |
| false | false | 两个模块均无 Bean/API/菜单路由；应用按现有基础功能启动 |
| true | false | endpoint-only：系统接入配置 API/页面/菜单可用，不装配任何 process Bean；允许空目录，不要求 `current-environment` 已配置 |
| true | true | endpoint 契约、Bean、endpoint jar 和有效 `current-environment`（指向 enabled 环境）均齐备时，两模块正常装配；process 只读取 endpoint 配置 |
| false | true | 启动明确阻断并指出 endpoint 模块禁用，不创建半可用 process；不得静默回退到旧全局开关或本地复制配置 |
| true | true，但 endpoint jar/Bean/current-environment 缺失或环境未启用/不存在 | 启动明确阻断，分别指出依赖缺失、契约 Bean 缺失、部署配置未设置或环境无效；不注册可执行 process 业务 Bean，不能返回空目录或自动 BYPASS |

菜单和前端路由分别由两个模块的权限与版本化菜单控制，并在当前 `sys_menu/getRouters` 菜单返回服务中按宿主 enabled 能力过滤 endpoint/process 所属菜单及子路由。该能力过滤需在 M10 核对并接入现有服务，不能当作已存在，也不能用前端硬编码菜单作为权威。关闭 process 不隐藏 endpoint 基础菜单；关闭 endpoint 时不得保留可写的 endpoint 页面或伪造只读缓存。

### 26.3 endpoint API、权限与可视页面

| 完整 API（前缀 `/endpoint`） | 权限 | 语义 |
| --- | --- | --- |
| `GET /systems/page`、`GET /systems/{systemCode}`；`POST /systems`；`PUT /systems/{systemCode}` | `endpoint:system:list/query/add/edit` | 系统目录 CRUD/启停；写信封含 commandKey、expectedVersion、reason |
| `GET /environments/page`、`GET /environments/{environmentCode}`；`POST /environments`；`PUT /environments/{environmentCode}` | `endpoint:environment:list/query/add/edit` | 多环境配置；current 标记只读，页面选择不切 runtime |
| `GET /addresses/page`、`GET /addresses/{id}`；`POST /addresses`；`PUT /addresses/{id}` | `endpoint:address:list/query/add/edit` | 共享地址 CRUD；创建 expectedVersion=ABSENT，修改使用对象 revision，列表只返安全字段 |
| `PUT /systems/{systemCode}/addresses` | `endpoint:system:edit`，且关联地址需 `endpoint:address:query` | 原子保存该系统完整有效关联集/停用解绑，目录 revision 与审计同事务；不是 process 表 |
| `GET /audit/page` | `endpoint:audit:query` | 独立配置审计分页，追加不可改删，只含非敏感前后摘要 |

地址创建可同时携带 `systemCodes`，保存地址与关联同事务；同时校验 `endpoint:system:edit`，无该权限只能创建未关联地址。endpoint 不按 IP 猜系统归属，合同只能引用已登记的系统地址关联。

Web使用独立基础菜单“系统接入配置”，页面固定为 `src/views/endpoint/system/index.vue`、`environment/index.vue`、`address/index.vue`。三页均为普通列表、分页、权限、弹窗CRUD和IwsFeedback反馈，不使用WMS IwsCrudPage或网络拓扑编辑器。

系统页从已有端点建立多选关联；地址页表单覆盖 `environmentCode`、`endpointRef`、协议、host（域名/IPv4/IPv6 单独字段）、端口、基础路径、关联系统、影响提示、启停和 revision。endpoint 自己只能计算 system-environment-address 关联影响；process 合同/事件影响仅作可选导航，不参与地址/关联保存前置，endpoint 不查询 process 表。敏感凭据只允许填写 `credentialRef`，编辑时显示 `hasCredential` 状态，不提供密文回显。并发冲突必须展示当前 revision/占用对象并重新取数，由用户决定是否重做，不能自动覆盖。页面间跳转保留 environmentCode、systemCode、endpointRef 筛选；停用被引用身份只控制新解析，不删除、不改 code。

### 26.4 新增验收 D47-D48

| 编号 | 场景输入 | 必须观察的结果 |
| --- | --- | --- |
| D47 | endpoint-only 空目录且未配 current-environment；两开关均禁用；process 启用但 endpoint jar/Bean/current-environment 缺失或环境无效；optional 依赖被下游过滤；关闭 process 后重进 endpoint 页面 | endpoint-only API/菜单可独立工作且空目录不影响启动；两禁用无 endpoint/process Bean/API/路由；process 缺任一运行前提时启动以明确原因阻断且不回退空目录/BYPASS；optional 不替代宿主 jar；关闭 process 不隐藏 endpoint 菜单，也不留 process route |
| D48 | 三页 CRUD/分页/权限与菜单能力过滤；system-address 关联原子提交；协议/host/port/basePath/environmentCode/ref、启停、revision 冲突；审计页；凭据回显；地址权限可见性；计划弹窗跳页面并返回；旧实例与 SEND_INTENT 冻结 | 三页普通列表+弹窗 CRUD 正常，地址页固定为 `address/index.vue`；系统关联与地址/目录 revision/审计同事务，reader shared gate 后锁 definition；目录冲突整批重取；具 `endpoint:address:query` 且同范围可看实际 IP，否则只见别名/状态；endpoint 影响提示只含自有关联，process 影响仅可选导航且不阻塞保存；凭据只返回 ref/状态；页面往返保留 environmentCode/systemCode/endpointRef 并以新 revision 重预览；计划弹窗不写地址/关联，旧实例 mode 不变，SEND_INTENT 目标不漂移 |

D47/D48 仍按隔离 MySQL、HTTP 桩和浏览器交互分别取证；本轮仅更新方案，不创建模块、DDL、代码或运行页面。
