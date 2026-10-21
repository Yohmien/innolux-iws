# 计划事件模块复用与新增实施计划

日期：2026-10-21。状态：评审稿，仅文档。本文回答“哪些可直接复用、哪些只能参考、哪些必须新增、模块如何按需引入”，并补充新增 `iws-process` 模块的动作计划。

关联材料：[完整方案](plan-event-state-machine-design.md)｜[时序图](plan-event-state-machine-sequences.md)｜[核验依据](plan-event-state-machine-evidence.md)。

## 1. 结论

采用一个新增业务模块 `iws-modules/iws-process`。核心配置与查询、事件状态机、检查点和领域审计在本模块；调度接线复用 `iws-job`/SnailJob；多级人审批复用现有 `iws-workflow`/Warm-Flow，但不让其成为事件执行事实源。Web 只新增业务页面、API 封装和领域展示组件，复用现有请求、权限、菜单、字典、表格和反馈能力。

第一阶段不改变 `iws-api`、不增加独立调度服务、不新增 BPM/消息中间件、不增加自由画布和通用规则引擎。

## 2. 可直接复用

| 需求 | 现有实现 | 复用方式 |
| --- | --- | --- |
| 模块骨架 | `iws-modules/iws-demo/pom.xml`、`iws-modules/pom.xml`、`iws-admin/pom.xml` | 按新模块实际 import 引入 common 依赖；不照搬 demo 的全套依赖 |
| 分页与响应 | `PageQuery`、`PageResult`、`R`、`BaseController` | 普通分页使用 GET + PageQuery；返回 `R<PageResult<T>>`/`R<T>` |
| 持久化 | `BaseMapperPlus`、MyBatis-Plus 分页与条件更新、`OptimisticLockerInnerInterceptor` | 直接复用拦截器、Mapper 和分页；新实体需显式增加 `@Version`/版本条件与影响行数校验 |
| 事务与锁 | 现有 Spring 事务、Lock4j/Redis 能力 | 短事务收口；资源竞争另用现有锁，不把锁当作状态事实 |
| 操作日志 | `@Log`、`@RepeatSubmit`、`LogAspect`、`sys_oper_log` | 现有入口日志继续使用；领域动作另写执行审计，不重复建通用日志平台 |
| 权限 | `@SaCheckPermission`、`v-hasPermi`、`auth.hasPermi/checkPermi` | 菜单、按钮和后端 API 使用同一 `process:*` 权限码 |
| 菜单与字典 | 动态 `getRouters()`、`sys_menu`、`useDict` | 新页面由菜单 SQL 接入；状态显示使用字典加原始码兜底 |
| 定时调度 | `iws-common-job`、`SnailJobConfig`、`iws-job` 的 `@JobExecutor` 模板、SnailJob OpenAPI | 复用现有调度客户端和任务管理；只新增薄 worker/监督执行器 |
| 人工审批 | `iws-workflow`、Warm-Flow 定义/实例/任务控制器、现有待办/已办页面 | 复杂多级审批复用；普通首次确认直接调用领域命令，避免额外流程实例 |
| Web 请求 | `src/utils/request.ts`、`PageResult`、`AxiosPromise` | 新 API 直接调用 `request`；列表读 `data.rows/total` |
| Web 动态路由 | `src/store/modules/permission.ts`、`src/permission.ts` | 只新增后端菜单 SQL；不在 `router/index.ts` 写死业务页 |
| 表格与对话框 | `Pagination`、`IwsTableScrollContainer`、`useSearchToggle`、`useSearchReset`、`useDialogState` | 新领域页复用外壳和交互，不复制分页/搜索逻辑 |
| 反馈与字典 | `IwsFeedback`、`useDict`、`DictTag` 经验 | 复用消息；新增 `ProcessStatusTag` 对空字典回退稳定状态码 |

`IwsCrudPage` 面向字段平铺的 `wms_*` 元数据表，不能承载计划类型子表、系统模式、状态边、事件链和分支 token。计划事件领域页使用普通 `el-table` 与现有 hooks，不修改通用 CRUD 组件。

## 3. 只参考模式、需要领域化改造

| 现有实现 | 参考内容 | 不能直接复用之处 |
| --- | --- | --- |
| `FlwDefinitionController`/`FlwDefinitionServiceImpl` | 定义列表、详情、复制、校验、发布、版本和权限流程 | 计划状态/事件/分支/系统模式是领域定义，不能写入 Warm-Flow 定义表 |
| `FlwInstanceController`/`FlwTaskController` | 实例列表、轨迹、待办/已办、命令权限和重复提交模式 | 审批任务没有事件租约、外部成功 checkpoint、分支 token 和原操作续跑 |
| `FlwChartExtServiceImpl` | 图节点提示、历史聚合和前端展示思路 | 历史按 `nodeCode` 聚合，不能表达多分支事件和逐步请求 |
| `src/views/workflow/processInstance/index.vue` | 页签、筛选、隐藏详情、审批记录交互 | 计划详情需新 VO 和执行链组件；不能把审批状态当事件状态 |
| 旧 PCBA `ExternalApiLogAspect` | 自动记录外部请求、脱敏、请求 ID 关联 | IWS 目标库无 PCBA 专属审计表；需按目标模块补最小请求明细表 |
| 旧 PCBA `PcbaPostingRequestContext`、`wms_pcba_posting_request_detail` | 一次 attempt 下多请求关联、状态明细和成功检查点布局 | 只借鉴协议与数据关系；不复制 PCBA 表、阶段、处理器和业务字段 |

## 4. 必须新增的最小领域代码

| 能力 | 最小文件范围 | 说明 |
| --- | --- | --- |
| 模块与条件装配 | `iws-modules/iws-process/pom.xml`、模块配置/自动配置类 | 默认关闭，按配置启用；不拆多个 Maven 子模块 |
| 配置模型 | `domain/PlanDefinition`、`SystemPolicy`、`StateDefinition`、`EventDefinition`、`EdgeDefinition` 及 Mapper | 版本不可变；系统模式按 `(versionId, systemCode)` 唯一 |
| 运行模型 | `PlanInstance`、`Token`、`PlanEvent`、`Attempt`、`ScheduleControl`、`ActionAudit` | 执行事实与审计，不放在 Warm-Flow 表中 |
| 配置服务 | `PlanDefinitionService`、`BusinessScheduleService` | 草稿、版本、发布、复制、删除、字段白名单、发布前校验 |
| 统一事件更新 | `EventUpdateService`、执行/跳过策略、`EventOperation` 接口 | 一个入口；执行含 operation，跳过不调用 operation；续跑复用原策略 |
| 状态与分支 | `StateValidator`、`StateValidatorRegistry`、`BranchSelector` | 状态校验、源/目标转换、XOR/AND/多起点；不为每个状态建引擎 |
| 执行读模型 | `ExecutionOverviewService`、`PlanChainQueryService`、`EventTraceQueryService` | 计划总览、链路、事件详情、attempt 和请求追踪 |
| 审计 | `ProcessAuditService`、`ProcessCommandAuditInterceptor` | 领域动作和拒绝动作；复用现有 `@Log` 只做入口补充 |
| 调度适配 | `SchedulerAdapter`、`BusinessScheduleExecutor`、`PlanEventWorkerExecutor` | 放在现有 `iws-job` 接线或 process 的 support 包；只有两个固定执行器 |
| 人工审批适配（可选） | `WorkflowApprovalAdapter` | 仅复杂人审启用；审批完成调用同一确认命令，不形成双向依赖 |
| Web API | `src/api/process/{types,schedule,definition,instance,execution,event}.ts` | 统一 `/process` 前缀；GET + `PageQuery`；ID 用字符串 |
| Web 领域页 | `src/views/process/schedule/index.vue` | 业务调度列表及弹窗编辑，不另建 edit 页面 |
| Web 领域页 | `src/views/process/definition/index.vue`、`edit.vue` | 计划/事件统一配置、版本切换、单事件编辑；`edit.vue` 为隐藏路由 |
| Web 领域页 | `src/views/process/execution/index.vue` | 已执行、按优先级待执行、运行中、异常，同时承担实例检索和维护 |
| Web 领域页 | `src/views/process/instance/detail.vue` | 单计划多分支执行链和事件抽屉入口 |
| Web 组件 | `ExecutionChain.vue`、`EventConfigEditorDrawer.vue`、`EventExecutionDrawer.vue`、`ProcessStatusTag.vue`、`AuditTimeline.vue`、`PostingConfirmDialog.vue` | 只实现真实业务差异，不引入新图编辑库 |

领域计算规则可以放少量纯函数：`definitionDraft.ts`、`postingConfirmation.ts`、`executionPresentation.ts`；不要为每个页面建 Pinia store，服务端状态以查询结果为准。

## 5. 按需引入开关与依赖

| 开关/模块 | 行为 | 不启用时 |
| --- | --- | --- |
| `iws.process.enabled` | 装配配置、实例、查询和命令 Controller | 不注册任何 process Controller/Service；应用照常启动 |
| `iws.process.scheduler.enabled` + `snail-job.enabled` | 装配两个固定执行器和控制适配 | 保留配置/查询及 `wake_pending`，可人工维护；不启动自动执行 |
| `iws.process.workflow.enabled` + `warm-flow.enabled` | 两个开关同时为真才装配 Warm-Flow 人审适配 | 普通逐系统确认仍可用；不创建缺失 Bean，不依赖 Warm-Flow 表 |
| 菜单 SQL | 显式插入动态菜单/权限 | 不显示计划事件菜单；已有功能不受影响 |

前端入口由 `sys_menu/getRouters` 和角色授权控制，不新增重复的 `iws.process.web.enabled`。停用 process 页面时必须通过版本化菜单回退脚本撤销/隐藏菜单及角色授权；仅关闭后端 Bean 不会自动撤销已插入的菜单。

依赖按阶段引入：P2 只依赖 `iws-common-core/web/security/mybatis/log`；P6 再增加 `iws-common-job`；P7 前端只使用现有 Vue 依赖；多级审批阶段才评估对 `iws-workflow` 的单向依赖。`iws-api` 在出现跨模块远程契约前不新增。

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
| M01 模块骨架与开关 | 新建 `iws-process` POM；加入 modules/admin；设置 `iws.process.enabled`；建立包结构 | 方案评审、当前项目 POM | `mvn -pl iws-modules/iws-process -am test`；禁用开关时应用启动且无 process Bean |
| M02 配置数据模型 | 创建定义、系统策略、状态、事件、边、审计表的幂等升级和 Mapper | 目标库只读结构核对 | H2/隔离 MySQL 结构、唯一键、乐观锁测试 |
| M03 配置聚合 API | 计划列表、版本详情、草稿保存、复制、字段能力、校验、发布、删除 | M02、`EditCapabilityResolver` | 版本只读/最新草稿/逐系统单值/旧 ACTIVE 退役测试 |
| M04 运行模型与挂载 | 实例、token、事件、attempt、确认、暂停/恢复/取消 | M02、权限与事务 | 幂等挂载、重复确认拒绝、旧实例快照测试 |
| M05 统一事件执行 | Execute/Skip、CAS/租约、发送意图、checkpoint、外部与本地收口、重试续跑 | M03、M04 | 并发单赢、纯跳过零 operation、外部成功不重发、未知转人工 |
| M06 分支与链路 | `BranchSelector`、XOR/AND、多起点/终点、汇合屏障、计划/事件查询 | M04/M05 | 分支单次决策、汇合唯一后继、并发分支不互相回滚 |
| M07 调度与监督 | 复用 SnailJob 客户端和现有 job 接线；两个固定执行器、业务优先级、空闲休眠 | M05、SnailJob 启用窗口 | 执行器注册、任务启停、重复回调不重复执行 |
| M08 可选审批适配 | 仅多级审批需求出现时接入 Warm-Flow；`businessId=planInstanceId`；完成确认幂等 | M04、M08 需求确认 | 审批完成重复回调只确认一次；关闭开关不影响简单确认 |
| M09 审计闭环 | 领域动作审计、命令拦截、外呼两阶段审计、审计查询和脱敏 | M03-M07 | 人工/系统/拒绝/外呼意图/结果均有记录；审计失败不误报成功 |
| M10 Web 管理台 | 业务调度、统一计划配置、执行总览、计划详情、事件抽屉、审计时间线 | M03-M06 | 动态菜单、权限、最新草稿、单事件编辑、轮询与空/错状态通过 |
| M11 集成与发布 | 业务操作注册、首个真实计划、隔离环境演练、菜单/配置/开关发布 | 全部前置、业务映射完成 | P1 两条真实链路通过；旧计划快照不变；撤开关可回退为只读/停用 |

每个动作只实现必要验收项，不顺手建设未来可能使用的通用能力。M08 是可选工作包；没有多级审批需求时不启动。

## 8. 复用收益与边界

直接复用后，无需新建调度平台、审批引擎、权限/菜单/字典、请求客户端、通用分页和操作日志。主要新增仅是领域表、状态/事件执行、审计、移动端零散 API 适配和必要展示组件。Warm-Flow 与 SnailJob 不能替代领域检查点，因为它们不保存外部发送意图、幂等键、事件分支 token 和本地收口事实；该部分必须由新模块承担。

+
## 9. 设计模式选型

参考目录：[重构大师设计模式目录](https://refactoringguru.cn/design-patterns/catalog)。选型原则是围绕已经出现的扩展点和变化轴使用模式；不把模式名写进类名来制造层次，也不新增无实际变化的抽象。Spring 容器、事务 AOP、MyBatis-Plus 和现有框架能力优先于自建实现。

| 模式 | 本项目落点 | 决策与边界 |
| --- | --- | --- |
| 工厂方法 | `EventOperation`/`StateValidator` 从注册表按 key 取得实例 | 有限使用。Spring Bean map/registry 足够；不为每个状态、事件或业务建工厂类。只有某类操作需要在运行时选择不同构造策略时再加工厂 |
| 抽象工厂 | 无当前需求 | 不采用。计划、调度、审批没有成组的可替换产品族，使用它会增加双重抽象 |
| 生成器 | `PlanGraphBuilder` 组装发布版本；`AttemptSnapshotBuilder` 组装恢复快照 | 采用并保持包内可见。用于避免大量构造参数和一次性拼装错误，不作为公开 DSL |
| 原型 | `PlanVersionCopyService` 从已发布/历史版本复制新草稿 | 采用显式深拷贝；不实现 `Cloneable`，避免浅拷贝和可变共享。复制后必须重新发布校验 |
| 单例 | Spring 默认 singleton Bean | 直接使用框架生命周期；禁止自定义 `getInstance`、静态可变实例和手工锁 |
| 适配器 | `SchedulerAdapter`（SnailJob）、`WorkflowApprovalAdapter`（Warm-Flow）、`ExternalSystemAdapter` | 采用。把外部 SDK/状态模型隔在新模块内部；适配器只做协议转换和错误分类，不拥有领域终态 |
| 桥接 | 领域状态/事件与具体平台实现 | 暂不采用。当前只有少数明确适配器；若未来同时存在多个事件类型和多个外部平台，再评估“两个独立变化轴” |
| 组合 | 计划 DAG、分支视图 | 不用于领域执行。DAG 有共享汇合和回边禁止，不能伪装成树；树形结构仅可用于纯展示分组 |
| 装饰 | 事务、审计、指标、权限增强 | 采用 Spring AOP/既有切面，不手写装饰器链。领域审计必须显式持久化，不能只靠切面吞错 |
| 外观 | `PlanCommandFacade`/`ProcessApplicationService`、模块公开 `api` 包 | 采用。对前端和业务模块暴露少量命令/查询，隐藏 token、CAS、审计和调度细节；不把整个模块做成上帝类 |
| 享元 | 只读计划版本、状态/校验器/事件元数据缓存 | 可选且按需。优先复用现有缓存；缓存键含版本和处理器版本，失配回源数据库。不能共享可变执行上下文 |
| 代理 | 事务、权限、懒加载的请求详情 | 使用 Spring/Framework 代理即可，不新增业务代理层；日志和审计需要持久化事实，不能依赖代理是否被调用 |
| 责任链 | `validateStructural → predecessor → confirmation/mode → business → target` | 采用固定顺序的责任链/有序校验器列表。链顺序由引擎固定，配置文件不能任意插入或跳过必需校验 |
| 命令 | `AttachTaskCommand`、`ConfirmPostingCommand`、`RetryEventCommand`、`PausePlanCommand`、`CancelPlanCommand` 等 | 采用统一命令信封（actor、commandKey、expectedVersion、reason）。记录请求意图、审计和结果；当前不需要撤销/重做，不实现 undo |
| 迭代器 | DAG 前驱/后继遍历、活动分支遍历 | 使用项目已有的集合/Stream；只有复杂拓扑遍历复用同一游标时提供包内迭代器。禁止暴露可绕开校验的可变图迭代 |
| 中介者 | PlanService/EventUpdateService 协调 Token、Event、Attempt、Scheduler | 不增加独立 Mediator 对象。现有应用服务已经承担协调职责，再套中介者会重复入口 |
| 备忘录 | `AttemptSnapshot`、checkpoint、source/target state 快照 | 采用持久化备忘录。恢复从 attempt/checkpoint 读取，不能靠内存对象；快照只读、带版本，审计不可擦除 |
| 观察者 | 提交后唤醒调度、发送通知、刷新非关键指标 | 仅用于非权威通知。领域迁移先由数据库和 `wake_pending` 持久化；不依赖进程内 Spring Event 作为唯一事实或持久 outbox |
| 状态 | `state_code`、token、event_status、version 状态 | 数据驱动状态机，不建立每个状态的 GoF State 子类。校验和转移由注册校验器/更新入口完成；避免类爆炸和无法持久化对象状态 |
| 策略 | Execute/Skip updater、FailureClassifier、RetryPolicy、BranchSelector | 采用。策略只改变一个明确维度，由 Spring map/registry 选择；状态迁移、审计和事务边界仍由统一引擎控制 |
| 模板方法 | 事件更新的固定步骤：读取/认领→校验→发送意图→操作→检查点→目标校验→收口 | 采用固定模板，动作/跳过策略作为模板步骤的插槽；不为每条边复制模板子类 |
| 访问者 | 发布期 DAG 多规则校验、状态/边问题汇总 | 可选。当前校验规则有限，先用有序校验器；节点/边类型明显增多再引入，避免为数据结构提前定义 visitor 协议 |

### 9.1 组合方式

领域主链采用“Facade 命令入口 + Command 请求信封 + Strategy 执行/跳过 + Template Method 固定顺序 + Chain 校验 + Memento 检查点 + Adapter 外部能力”。这些模式围绕同一个 `EventUpdateService` 组合，不产生第二套执行路径。

计划版本复制使用 Prototype/Builder；发布校验可以使用有序校验链，必要时再引入 Visitor。调度唤醒和 UI 通知可以观察提交事件，但必须以数据库 `wake_pending` 为恢复事实。

### 9.2 明确避免的模式

- 每个状态建一个 `State` 类、每个事件建一个工厂或每条边建一个 Handler；优先注册表和配置驱动。
- 为多分支 DAG 使用组合模式伪装成树；共享汇合、多起点和多终点需要显式图结构。
- 用装饰器或进程内事件替代事务、审计、持久 outbox 和恢复检查点。
- 自行实现单例、代理、缓存框架、消息总线或规则解释器。
- 为只有一两个实现的接口提前拆 Factory/Abstract Factory/Bridge；等到真实变化轴出现再引入。

模式落点只服务于当前需求；后续若新增同类变化，扩展策略/注册表，不复制模板、命令和审计链。
