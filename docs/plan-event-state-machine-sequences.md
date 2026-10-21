# IWS 计划事件分场景及整合时序图

日期：2026-10-21。状态：目标设计，尚未实施。

[完整方案](plan-event-state-machine-design.md)为唯一规格；[模块复用与新增实施计划](plan-event-state-machine-implementation-plan.md)说明复用边界和开发动作；[核验依据](plan-event-state-machine-evidence.md)记录源码、依赖和目标数据库事实。本文将其具体化为 16 个场景，包含业务统一调度、BranchSelector、前后端管理台、计划总览与两级执行描述、代码绑定动作编辑门禁和全动作审计。示例状态不是现有业务数据库状态。

## 共同契约

- 一个业务可有多个任务，一个任务一个计划实例；业务统一调度计划 BusinessSchedule 登记允许的 plan_type 及类型优先级。所有 worker 都受该业务统一准入和排序约束。
- 新配置只创建新计划版本并供新实例挂载；旧版本定义和旧计划实例保留原冻结配置，不自动迁移、覆盖或重跑。
- 计划版本按 `(plan_version, system_code)` 各保存一条单值过账模式；不同系统可采用不同 AUTOMATIC/MANUAL_CONFIRM/BYPASS。事件只按自身系统码解析所属实例快照，不在事件上建立第二套模式来源。
- plan_status 管理实例；token.state_code 表示已提交的流程位置 S1/S2/EXCEPTION_HOLD；event_status 管理事件执行。token_status=ACTIVE/BLOCKED/FINISHED 只是元数据，不复制事件状态机。
- 每条事件固定 source_state → target_state。StateValidator 提供源态及目标态校验；恢复按检查点校验，不要求已成功步骤的原始业务初态重新成立。
- enabled=false 才走整事件 Skip 策略：不调用任何 Operation，仅作结构校验、状态迁移、审计及后继就绪更新。
- enabled=true 始终走 Execute 策略。BYPASS 仅跳过 kind=EXTERNAL_POSTING 且 target_system_code!=self_system_code 的操作步骤，记 SKIPPED_MODE；本地、查询、控制及本系统步骤照常执行。事件完成为 SUCCESS，整事件禁用完成才为 SKIPPED。
- MANUAL_CONFIRM 首次依赖满足者 MANUAL_PENDING/FIRST_CONFIRM，其余 WAIT_PREDECESSOR；确认按实例+系统保存，可一次提交多个所选系统。首次确认后自动执行，任意执行失败立即 MANUAL_PENDING/EXECUTION_FAILED。后续命令是 RETRY_EVENT，首次确认事实不清除。AUTOMATIC 直接自动执行；BYPASS 的未屏蔽步骤同样直接自动调度，两者可按规则有限重试。
- 认领、发送意图、检查点和收口均检查 event_id、version、lease_epoch、owner。存在 SEND_INTENT 而无可信结果即未知，不凭超时或一次未查到直接重发。
- 最终失败只允许从未被活动租约占用且无未收口副作用的事件进入；计划取消先禁止新认领，存在 PROCESSING/未知结果时等待安全收口或人工，不能直接保存 CANCELLED 伪造取消完成。
- BranchSelector 统一选择 XOR/AND 和起点，决策按 scope 持久一次、重试复用。未选路径为 NOT_SELECTED，不冒充跳过执行。
- 只复用现有 SnailJob 并接线两个固定执行器：businessScheduleExecutor 做业务控制及各计划逻辑监督，planEventWorkerExecutor 做实际执行。控制器不执行业务，也不自动覆盖人工暂停。
- 复用现有 Warm-Flow 仅用于可选多级人审、待办和审批轨迹；简单首次确认直接调用领域确认命令。审批完成必须幂等写回领域状态，不能把 flow_status 当成 event_status。
- 本轮只编写文档；后续可以启用已有 SnailJob，当前关闭不构成阻断。

## 现状基线

本节只记录已核对的当前实现，用于识别迁移差异。下文序列均为目标设计。

| 主题 | 当前实现 | 证据 |
| --- | --- | --- |
| 前端部署产物 | Nginx 产物没有源码和 Git；语言包有“接口异常重试记录”菜单文案，但有界检索未命中本文所需的 /iws/retry、/outbound/order/posting、MANUAL_CONFIRM、MANUAL_PENDING、WAIT_PREDECESSOR 页面调用或状态文案。部署包无法证明新页面契约。 | C:/code/company/ui/vietnam-pcba-web/dist-pcba/static/js/index-KhyucIum.js:1 |
| 过账追溯查询 | 已有 GET /iws/retry/events、GET /iws/retry/{id}/trace、GET /iws/retry/{id}/requests、GET /iws/retry/{id}/requests/{requestId}。列表项清除请求和响应正文，完整请求内容只能经单请求详情读取。 | C:/code/company/vietnam_pcba_avc/ruoyi-system/src/main/java/com/ruoyi/system/rest/PcbaPostingTraceController.java:29-80；C:/code/company/vietnam_pcba_avc/ruoyi-common/src/main/java/com/ruoyi/common/business/posting/PcbaPostingEventTrace.java:13-18；C:/code/company/vietnam_pcba_avc/ruoyi-common/src/main/java/com/ruoyi/common/business/posting/PcbaPostingRequestDetail.java:15-45 |
| 当前模式 | GET /outbound/order/posting/mode 与 POST /outbound/order/posting/mode 使用 BYPASS、MANUAL_CONFIRM、AUTOMATIC。切换请求只有 mode 和 expectedMode；服务把 midwms.feedback.bypass.enabled 与 pcba.outbound.feedback.box-level 映射为三种模式。没有独立的 scope=SYSTEM 字段，也没有 skip_scope。 | C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/domain/PcbaOutboundPostingMode.java:6-9；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/domain/dto/PcbaOutboundPostingModeSwitchDto.java:14-18；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/service/PcbaOutboundPostingModeService.java:73-111；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/external/midWms/common/MidWmsFeedbackBypass.java:21；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/box/service/PcbaOutboundBoxFeedbackModeProvider.java:15 |
| 当前模式快照 | 模式固化在稳定事件 ORDER_POSTING_CONTEXT 的 payload 中，首批写入 postingMode 和 confirmed=0，首次确认后改为 confirmed=1。 | C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/resources/mapper/outbound/posting/PcbaOutboundPostingModeMapper.xml:105-115；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/domain/PcbaOutboundPostingContext.java:12-23 |
| 当前首次确认 | 只为新建且尚为 PENDING 的业务事件加 WAIT_ORDER_CONFIRM 门禁。POST /outbound/order/posting/{outOrderNo}/retry 在未确认时写 confirmed=1，再执行所选事件；POST /{outOrderNo}/manual-pending 把待执行事件转人工待处理。 | C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/postingevent/service/PcbaOutboundPostingEventStore.java:62-69,177-188；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/service/impl/PcbaOutboundPostingServiceImpl.java:303-315,385-401；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/controller/PcbaOutboundPostingOrderController.java:87-128 |
| 当前前置等待 | WAIT_PREDECESSOR 是查询时根据依赖计算出的展示状态，不是独立的持久运行态；实际状态仍可为 PENDING 或 RETRY_WAIT。 | C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/resources/mapper/outbound/posting/PcbaOutboundPostingMapper.xml:74-93；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/service/impl/PcbaOutboundPostingServiceImpl.java:700-704 |
| 当前失败续跑 | 瞬时错误在阈值内进入 RETRY_WAIT 并按退避到期；达到上限或业务拒绝进入 MANUAL_PENDING。RETRY_WAIT 的人工命令只提前到期。除整单入口外另有批量、按箱、最终失败路由；其中按箱入口当前直接拒绝。 | C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/process/service/PcbaExternalFeedbackProcessServiceImpl.java:298-320；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/process/service/PcbaOutboundEventManualRetryService.java:94-101；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/controller/PcbaOutboundPostingOrderController.java:95-129；C:/code/company/vietnam_pcba_avc/innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/service/impl/PcbaOutboundPostingServiceImpl.java:176-191 |
| 当前状态投影 | 运维查询把 MANUAL_PENDING、前置等待、执行中、成功、最终失败映射为 2、4、0、1、3；提示文案包含“请到工单过账确认页面整单处理”和“等待前置事件完成”。 | C:/code/company/vietnam_pcba_avc/ruoyi-system/src/main/resources/mapper/system/InterfaceRetryMapper.xml:143-176 |

当前接口已经存在，但不是本文的目标执行协议。新设计需要增加计划版本和冻结配置，显式区分 plan_status、state_code、event_status，并让模式确认、异常停留、恢复和调度控制可独立寻址。

## 场景 1：业务调度、计划与事件配置发布

前提：业务调度登记可挂载计划类型及优先级；草稿包含系统模式、事件启用、源/目标状态、操作类型/目标系统、分支和状态校验描述。

结果：合法配置发布为不可变版本；缺校验器、无法收敛的图、缺少跳过产物替代关系均拒绝。发布无需等待 SnailJob 在线。

```mermaid
sequenceDiagram
  actor U as 配置人员
  participant P as PlanService
  participant V as StateValidator
  participant B as BranchSelector
  participant D as DB
  U->>P: 提交业务调度及计划版本
  P->>D: 校验 business_code、允许 plan_type 和优先级
  P->>V: 检查状态定义、源/目标校验器及条件描述
  P->>P: 检查有向图无环、起终点可达及汇合结构
  P->>B: 检查选路条件注册、互斥和默认分支
  B-->>P: 分支选择规则完整
  P->>V: 校验整事件跳过及抛账操作屏蔽的输入输出合同
  alt 缺校验器或下游依赖无替代输入
    P-->>U: 拒绝发布，列出具体节点及条件
  else 配置合法
    P->>D: 同库事务新版本 ACTIVE、旧 ACTIVE 转 RETIRED、摘要和审计
    P-->>U: 已发布；旧版本内容及旧实例保持，新实例只取 ACTIVE
  end
```

不变量：配置中的业务状态说明与实际校验器版本对应；不能用状态名称代替条件，也不能在配置中执行任意 SQL/脚本。

## 场景 2：业务任务挂载计划、多起点与业务信号

前提：任务有稳定业务键；类型在 BusinessSchedule 允许范围内。多起点选择规则、允许信号集合已定义。

结果：一个任务幂等挂载一个计划；只激活已选根。尚未发生的动作保留事件占位，收到真实信号后才固化载荷。

```mermaid
sequenceDiagram
  participant A as 业务模块
  participant P as PlanService
  participant B as BranchSelector
  participant D as DB
  participant S as SchedulerAdapter
  A->>P: attachTask(business_id, task_key, plan_type)
  P->>D: 校验业务调度准入并唯一解析 ACTIVE 版本
  alt 同任务已挂载
    P->>D: 比较关键输入摘要
    P-->>A: 相同返回既有实例，不同拒绝覆盖
  else 新任务
    P->>B: select 根入口及本次输入快照
    B-->>P: selected_root_set、理由及输入摘要
    P->>D: 同库保存实例、冻结版本/模式、根决策和 token
    P->>D: 就绪事件按模式设 PENDING 或 MANUAL_PENDING
    P->>D: 未满足前驱/业务信号者 WAIT_PREDECESSOR
    P->>D: 保存逻辑监督及 wake_pending 后提交
    P->>S: 提交后唤醒业务控制任务
    P-->>A: 返回 plan_instance_id
  end
  A->>P: 后续真实业务 signal
  P->>D: 幂等固化允许信号的 payload/hash
  P->>D: 重新判断依赖及确认，只激活既定图中的事件
```

不变量：任务挂载不抛账；未发生的业务动作不提前造载荷；运行中不得因扫描发现新条件而新增根或改变已选路径。

## 场景 3：MANUAL_CONFIRM 首次确认与失败后事件重试

前提：各系统模式和计划版本已冻结。确认事实按 plan_instance_id+system_code 保存，整计划命令可一次明确确认多个所选系统。

结果：首次确认只放行就绪事件，后续事件继承；失败恢复另记事件重试，不再次做首次确认。

```mermaid
sequenceDiagram
  actor U as 操作人员
  participant P as PlanService
  participant D as DB
  participant S as SchedulerAdapter
  loop 首次所有适用事件
    P->>D: 计算前驱、信号、载荷是否就绪
    alt 前置未满足
      P->>D: WAIT_PREDECESSOR，记录仍需首次确认
    else 前置已满足但未确认
      P->>D: MANUAL_PENDING，reason=FIRST_CONFIRM
    end
  end
  U->>P: confirmPosting(实例, 明确系统集合)
  P->>D: 同库事务锁定实例，逐系统首次写 confirmed=1
  P->>D: 所选系统已就绪者 PENDING，未就绪者保持等待
  P->>D: 保存首次审计与 wake_pending 后提交
  P->>S: 请求业务调度唤醒，不在请求线程执行
  Note over P,D: 各系统新事件满足前置后继承该系统确认，不二次首次确认
  Note over P,D: 任一实际执行失败均立即 MANUAL_PENDING/EXECUTION_FAILED
  U->>P: RETRY_EVENT(失败事件)
  P->>D: 原子保存 recovery_requested 与待执行资格
  Note over P,D: confirmed 保持不变；重试再失败仍待人工
```

不变量：首次等待不是执行异常，不增加失败次数；确认与放行共同回滚；确认不能越过前驱、资源或真实业务信号。

## 场景 4：正常执行、源/目标校验与成功检查点

前提：业务统一调度已允许该类型，事件前置及模式门禁满足，enabled=true。

结果：事件完成 SUCCESS，token 从 S1 到固定 S2，后继唯一激活。示意同时展示外部步骤及本地步骤，实际 operation 可以只有其中一种。

```mermaid
sequenceDiagram
  participant J as SnailJob
  participant E as EventUpdateService
  participant V as StateValidator
  participant O as Operation
  participant D as DB
  J->>E: planEventWorkerExecutor(业务调度范围)
  E->>D: 按统一类型/实例/事件优先级取候选
  E->>D: CAS 认领并保存 attempt、owner、lease_epoch
  alt 认领失败
    E-->>J: 放弃，不执行副作用
  else 认领成功
    E->>V: 校验源态结构及未完成步骤业务前置
    V-->>E: 源态条件通过
    E->>D: 有效执行代次下持久化 SEND_INTENT
    E->>O: 本地长事务外执行未完成外部步骤
    O-->>E: 可信成功结果
    E->>D: 有效执行代次下保存外部成功 checkpoint
    E->>O: 执行尚未完成的本地步骤
    O-->>E: 本地收口完成
    E->>V: 校验固定目标 S2 的结构和实际业务结果
    V-->>E: 目标条件通过
    E->>D: 短事务 CAS token S1 到 S2、事件 SUCCESS
    E->>D: 同事务保存后继/到达事实、审计和 wake_pending
    E-->>J: 本轮已处理，平台不再叠加业务重试
  end
```

不变量：若本地结果写入与事件完成同库，相关收口同事务；外部成功独立保存，不能因后续本地事务失败而撤销成功证据。

## 场景 5：BYPASS 只屏蔽非本系统接口抛账操作

前提：enabled=true，模式 BYPASS，操作注册元数据区分类型和目标系统。业务操作工具类自身的方法在匹配屏蔽时不调用。

结果：只有匹配的接口抛账步骤 SKIPPED_MODE；其余步骤仍实际执行，事件最终 SUCCESS，流程状态正常推进。

```mermaid
sequenceDiagram
  participant E as EventUpdateService
  participant V as StateValidator
  participant O as Operation
  participant D as DB
  E->>D: CAS 认领 enabled=true 事件，选择 Execute 策略
  E->>V: 校验源态及模式对应的步骤合同
  loop 每个冻结的操作步骤
    alt kind=EXTERNAL_POSTING 且 target_system_code!=self_system_code
      E->>D: 步骤 SKIPPED_MODE，sent=false，写屏蔽理由
      Note over E,O: 不调用该抛账操作方法，不伪造外部成功
    else 本地、查询、控制或本系统操作
      E->>V: 校验本步骤真实前置
      E->>D: 实际外呼前保存 SEND_INTENT
      E->>O: 执行未屏蔽步骤
      O-->>E: 返回实际结果
      E->>D: 保存本步骤 checkpoint
    end
  end
  E->>V: 目标校验，保留屏蔽事实而非断言已外部过账
  E->>D: 同事务 token S1 到 S2、事件 SUCCESS、后继唤醒
```

不变量：不能按 URL 猜测接口类型；不能屏蔽全部外部接口。剩余步骤失败按自动模式有限重试/人工异常规则处理，BYPASS 不增加首次确认。

## 场景 6：enabled=false 整事件纯跳过

前提：事件允许整事件跳过，前置及首次确认门禁满足，替代输入合同有效。

结果：初态到终态的变更和事件 SKIPPED 同事务提交，完全不调用任何内部 Operation。

```mermaid
sequenceDiagram
  participant E as EventUpdateService
  participant V as StateValidator
  participant B as BranchSelector
  participant O as Operation
  participant D as DB
  E->>D: CAS 认领 enabled=false 事件并记 Skip 策略
  E->>V: 源态结构、跳过合同及固定目标结构校验
  V-->>E: 校验结果
  alt 校验不通过
    E->>D: 保留源态并记录失败，按模式进入人工或退避
  else 校验通过
    Note over E,O: 不调用 Operation、外部接口、库存或业务收口
    opt 目标后有分支节点
      E->>B: 统一选择后继，使用真实或声明替代输入
      B-->>E: 冻结分支决策
    end
    E->>D: 同库事务 CAS token source 到 target
    E->>D: 同事务事件 SKIPPED、决策/后继、审计、wake_pending
    alt 任一写入失败
      E->>D: 整体回滚，不能只留下 SKIPPED
    else 提交成功
      Note over E,D: 实际内部操作次数为零，后继仍需自己的初态校验
    end
  end
```

不变量：跳过不是删除图中的边。下游真实依赖被跳过的产物而没有替代输入时，发布/跳过命令拒绝，不伪造业务数据。

## 场景 7：BranchSelector、XOR/AND 分支及汇合

前提：到达已发布的决策节点。动态分支的决策时点是本次真正到达节点时，根选择则发生在挂载时。

结果：同 scope 一次决策，重试复用；XOR 只走已选路径，AND 全部期待分支到达才放行。

```mermaid
sequenceDiagram
  participant E as EventUpdateService
  participant B as BranchSelector
  participant V as StateValidator
  participant D as DB
  E->>D: 读取 instance、decision_node、fork_scope 的已有决策
  alt 已有决策
    E->>B: 比较输入摘要并复用
    B-->>E: 原选中集合，摘要不一致则冲突待人工
  else 首次决策
    E->>B: select(版本、决策节点、scope、输入快照)
    B-->>E: selectedBranchKeys、规则版本及理由
    E->>D: 同事务保存唯一决策并创建分支 token
  end
  alt XOR
    E->>D: 未选且确定不可达的路径记 NOT_SELECTED
    Note over E,D: 共享汇合/后继保留，不关闭其他可达路径
    E->>V: 共享源状态校验器，准入唯一选中分支
    E->>D: 选中路径到达后，仅按其前驱放行 XOR 汇合
  else AND
    E->>D: 冻结期待 branch_key 集合并建立独立 token
    loop 各分支独立执行或合法整事件跳过
      E->>V: 共享源校验与本分支目标校验
      E->>D: 保存该分支到达事实，不改兄弟成功
    end
    E->>V: 共享汇合状态校验器检查期待集合
    alt 全部到达
      E->>D: CAS 关闭屏障并创建唯一后继
    else 仍有等待/异常分支
      E->>D: 保持汇合等待，列出阻塞分支
    end
  end
```

不变量：分支工具只决策，不执行事件或直接改状态；后继创建、到达事实和事件完成同事务。多终点按 ALL_SELECTED 或显式 ANY_SUCCESS 收敛，未收敛副作用不能直接取消。

## 场景 8：失败分类、回源态或统一异常停留

前提：执行中的本轮失败，已知操作阶段和步骤证据。异常位置和人工/自动门禁分别判断。

结果：无副作用失败保留源态；外部成功、本地失败或未知/部分成功进入本计划统一 EXCEPTION_HOLD。人工模式无论何种执行失败都立即待人工。

```mermaid
sequenceDiagram
  participant E as EventUpdateService
  participant V as StateValidator
  participant D as DB
  E->>D: 读取本 token 原 attempt、SEND_INTENT 和 checkpoint
  alt 证明没有副作用且源态仍有效
    E->>V: 检查可保留/条件恢复源态
    E->>D: 有效代次下保持 source_state
  else 外部已成功、本地未完成
    E->>D: token.state_code=EXCEPTION_HOLD
    E->>D: 保留 EXTERNAL_SUCCEEDED，只允许补本地
  else 未知结果或部分成功
    E->>D: token.state_code=EXCEPTION_HOLD
    E->>D: 保存 blocked_event、原 updater/operation、源态和步骤证据
  end
  alt MANUAL_CONFIRM
    E->>D: 立即 MANUAL_PENDING/EXECUTION_FAILED，不清首次确认
  else 自动模式且异常停留、外部已成功、仅剩安全本地步骤、规则允许
    E->>D: 同事务 RETRY_WAIT、next_retry_time、recovery_requested
  else 自动模式且未进入异常停留、可安全重试未耗尽
    E->>D: RETRY_WAIT 与 next_retry_time
  else 未知、需人工或次数耗尽
    E->>D: MANUAL_PENDING/EXECUTION_FAILED
  end
  E->>D: 聚合计划状态并保留兄弟成功
  opt 隔离配置为 PAUSE_PLAN
    E->>D: 停止普通新认领，已执行分支按检查点收口
  end
```

不变量：租约回收先 CAS 撤销旧执行权；有发送意图无确定结果视为未知。只有可信幂等或排除旧执行/迟到请求的证据才可重发，不凭一次“没查到”判断未执行。

异常停留下不能只有 RETRY_WAIT 而没有定向恢复资格。自动本地恢复只适用于明确配置且具备可信成功检查点的情况；其他异常待人工。MANUAL_CONFIRM 不生成自动恢复请求。

## 场景 9：事件重试、局部回源与检查点续跑

前提：目标为可恢复失败事件；成功、跳过、最终失败不可复活。人工暂停优先，需先恢复暂停。HTTP 命令只排队，不对账或外呼。

结果：按失败 token 的原尝试恢复，直到目标状态并满足后继初态；原成功步骤不重做。

```mermaid
sequenceDiagram
  actor U as 操作人员
  participant P as PlanService
  participant E as EventUpdateService
  participant V as StateValidator
  participant O as Operation
  participant D as DB
  U->>P: RETRY_EVENT(失败 event_id)
  P->>D: 校验权限、当前失败、计划控制态和活动租约
  P->>D: 原子保存 recovery_requested、PENDING、wake_pending
  P-->>U: 已受理，首次确认保持
  Note over E,D: 同一 worker 经业务统一排序认领定向恢复
  E->>D: 用 blocked_event/last_attempt 找原 updater 和 operation
  E->>D: CAS 新执行代次，不按全计划最后日志找位置
  E->>V: 源结构及恢复合同，不重验已成功步骤的旧业务初态
  alt 有 SEND_INTENT 但结果未知
    E->>O: 只读对账或使用人工提交的可信证据
    O-->>E: 成功、可安全重发或仍未知
  end
  alt 仍未知或恢复条件不满足
    E->>D: 保持 EXCEPTION_HOLD/MANUAL_PENDING
  else 恢复条件满足
    E->>D: CAS 失败 token 回本事件源态，保持 PROCESSING 独占
    alt 原 updater 为整事件 Skip
      Note over E,O: 不调用 Operation，复用跳过校验和推进
    else 原 updater 为 Execute
      E->>V: 成功步骤只验成功证据；未完成步骤校验当前前置
      E->>O: 只做未完成步骤，模式屏蔽步骤不调用
      O-->>E: 补本地/必要后续步骤完成
    end
    E->>V: 校验目标状态及恢复后置条件
    E->>D: 同事务 token source 到 target、终态、后继及 wake_pending
    E->>V: 后继初态按共享状态定义校验
    E->>D: 只清本事件异常及 recovery_requested，其他异常不清
    opt 无后继且所有选中路径与输入已收口
      E->>D: plan_status=COMPLETED，关闭本实例逻辑监督
    end
  end
```

不变量：EXCEPTION_HOLD 中仅允许明确请求的定向恢复候选，不先把整个计划改为 RUNNING；外部成功只补本地。恢复再次失败按原模式处理，MANUAL_CONFIRM 仍立即待人工。

自动模式明确允许的安全本地恢复可由失败事务直接设置 recovery_requested，替代图中人工排队步骤，其余认领、检查点和目标校验相同；仍需到期且未被人工暂停。

## 场景 10：业务统一调度、计划监督、优先级及自动启停

前提：BusinessSchedule 登记该业务允许的计划类型及优先级。SnailJob 按需开启；类型/实例/事件优先级统一作用于合法候选。

结果：业务控制长期运行，各计划逻辑监督持续检查，空闲仅休眠 worker；各计划不得独立绕过统一调度。

```mermaid
sequenceDiagram
  participant J as SnailJob
  participant B as BusinessSchedule
  participant S as SchedulerAdapter
  participant E as EventUpdateService
  participant D as DB
  J->>B: businessScheduleExecutor(business_code)
  B->>D: 读取允许 plan_type、优先级和业务控制态
  loop 各任务计划实例的逻辑监督
    B->>D: 检查依赖、人工暂停、异常、到期及恢复候选
    B->>D: 更新持久期望态，不执行业务操作
  end
  alt 有合法到期候选
    B->>S: 确保组 worker 开启/请求触发
    S->>J: 复用按 bizId 启停/触发 API
  else 持续空闲超过阈值
    B->>S: 休眠组 worker，业务控制任务仍运行
  end
  J->>E: planEventWorkerExecutor(business_code)
  E->>B: 复用统一候选范围、优先级与公平规则
  B-->>E: 类型优先级、实例优先级、事件优先级、就绪时间、ID
  E->>D: 在资源/并发门禁内有界 CAS 认领
  E->>E: 统一执行、跳过或定向恢复
  E-->>J: 返回本轮结果，业务失败已持久化
  Note over B,D: 插队只改合法未认领候选优先级，不越前驱/确认
  Note over B,D: 人工暂停不自动唤醒；平台不可达保留控制意图
```

不变量：不开第二套调度平台；控制、worker 两种固定执行器代码复用参数。实例终态关闭自身逻辑监督，不暂停服务其他计划的业务控制任务。每实例物理监督为可选映射，也不直接取业务任务。

## 场景 11：调度执行描述与运维查询

前提：已存在冻结状态描述、校验器条件说明、执行/校验审计及调度控制事实。默认查询不重新运行校验，更不触发业务操作。

结果：返回已执行计划、按有效优先级待执行计划、活动计划逐分支所处事件、已达/下一状态及校验条件；异常附停留位置与恢复要求。

```mermaid
sequenceDiagram
  actor U as 运维人员
  participant P as PlanService
  participant B as BusinessSchedule
  participant D as DB
  U->>P: 查询 business_schedule_code 和可选业务/任务范围
  P->>D: 一致性读快照，记录 as_of 与版本
  P->>B: 取得与 worker 相同的准入/排序/公平规则
  B-->>P: 有效配置及资源 scope
  P->>D: 读取已结束计划名称、任务、时间及结果
  P->>D: 读取候选事件并按实例首个候选聚合 rank
  P->>D: 读取活动实例、所有分支 token、事件及当前步骤
  P->>D: 读取冻结状态描述和条件说明、最近校验结果/时间
  P->>D: 读取当前 target 或各后继 target、分支是否已决定
  P->>D: 读取异常状态、失败步骤、原源态及恢复证据
  P-->>U: executed_plans、pending_plans、running_plans.branches
  P-->>U: current_state、next_states、validation、exception、as_of
  Note over P,U: PASS/FAIL/NOT_EVALUATED/NOT_APPLICABLE 分别展示
  Note over P,U: 等待首次确认不伪造异常，终态 next_states 为空
```

不变量：等待首确、前置、资源或未来重试者有 eligible=false 与原因，不冒充即将执行；已开始但等待的计划归活动计划。位次注明资源 scope；分支未决定列候选目标，不把后继 source 重复当下一目标。显式重新校验仅调用无副作用检查入口，历史结果与最新观察分开保存。

## 场景 12：从挂载到执行、跳过、异常恢复的整合链路

前提：业务统一调度和计划版本已发布。示例包含人工确认、整事件跳过、统一分支选择及外部成功后本地失败。

结果：失败事件从原检查点恢复，已成功外部步骤不重复，最终满足全部结束屏障才完成。

```mermaid
sequenceDiagram
  participant A as 业务及操作人员
  participant P as PlanService
  participant B as BusinessSchedule
  participant R as BranchSelector
  participant E as EventUpdateService
  participant V as StateValidator
  participant O as Operation
  participant D as DB
  A->>P: 创建任务并挂载已允许的 plan_type
  P->>R: 选择根入口
  P->>D: 冻结实例、模式、根及业务信号合同
  P->>D: 首次就绪待人工，其他待前置
  A->>P: 首次过账确认
  P->>D: 原子确认系统范围并放行就绪事件
  B->>D: 监督本业务各计划并统一候选优先级
  Note over B,E: 现有 SnailJob 控制 worker，worker 按统一范围认领
  E->>V: E1 源态校验
  E->>D: SEND_INTENT
  E->>O: E1 未屏蔽的外部抛账
  O-->>E: 外部成功
  E->>D: 持久化成功 checkpoint
  E->>O: 本地收口
  O-->>E: 本地失败
  E->>D: token EXCEPTION_HOLD、MANUAL_PENDING/EXECUTION_FAILED
  Note over E,D: 外部成功事实和首次确认均保留
  A->>P: RETRY_EVENT(E1)，不是再次首次确认
  P->>D: recovery_requested、PENDING、wake_pending
  B->>D: 保持其他异常，准入指定恢复候选
  E->>D: 认领失败 token 的原 updater/operation
  E->>V: 恢复结构及成功证据检查
  E->>D: 受保护地回 E1 源态，不清 checkpoint
  E->>O: 仅补本地收口，不重复外呼
  E->>V: 终态校验
  E->>D: E1 SUCCESS，token 到目标态，后继唯一激活
  E->>V: E2 初态与目标结构校验
  Note over E,O: E2 enabled=false，整事件不调用 Operation
  E->>D: E2 SKIPPED，token 到配置目标态
  E->>R: 统一分支决策，重试复用既有结果
  R-->>E: 已选路径及汇合范围
  E->>D: 持久决策、激活后继/合法分支
  E->>V: 后继初态/汇合屏障校验
  E->>D: 后续事件逐步推进，真实业务信号未到仍等待
  alt 全部选中必需路径完成且输入关闭
    E->>D: COMPLETED，关闭本实例逻辑监督
  else 仍有异常或必需信号未到
    E->>D: 保留准确阻塞说明，计划不伪报完成
  end
  A->>P: 查看调度执行描述
  P-->>A: 名称与队列、逐分支事件/状态/校验条件/异常描述
```

不变量：示例不是唯一业务图。若 E1 为 BYPASS，则仅其外部抛账步骤 SKIPPED_MODE、本地步骤继续；E2 的整事件跳过语义不变。源状态/目标状态从配置读取，不硬编码在工具类中。

## 场景 13：Web 配置、逐系统确认与事件重试

前提：配置人员登录 Web；计划版本已定义多个系统，每个系统各有一个模式值。复杂审批可选启用 Warm-Flow，简单首次确认直接调用计划命令。

结果：配置草稿和发布结果可审计；确认按所选系统分别持久化；重试只排队，不在 HTTP 请求中调用外部系统。

```mermaid
sequenceDiagram
  actor U as 配置/操作人员
  participant W as IWS Web
  participant P as PlanService
  participant A as WorkflowApprovalAdapter
  participant D as DB
  participant S as SchedulerAdapter
  U->>W: 切换查看任意历史/已发布版本
  W->>P: GET /process/definition/{versionId}
  P-->>W: 完整只读快照和 isLatest/isDraft
  U->>W: 在最新草稿中编辑基本信息、系统模式、状态
  U->>W: 从事件列表选择一个事件，仅展开该事件及关联边编辑
  W->>P: PUT /process/definition/{versionId}/events/{eventCode}
  P->>D: 复核最新草稿、单事件、状态/边引用、分支屏障和 expectedVersion
  P-->>W: 保存单个事件并返回新草稿版本
  U->>W: 发布/校验
  W->>P: POST /definition/{id}/validate|publish
  P->>D: 保存不可变版本、模式映射、菜单审计
  P-->>W: 返回问题或发布成功；仅供后续新实例使用
  U->>W: 选择 MANUAL_CONFIRM 系统并确认
  opt 复杂多级审批
    W->>P: 请求审批
    P->>A: 启动 Warm-Flow，businessId=plan_instance_id
    A-->>P: 审批完成事件
  end
  W->>P: POST /instance/{id}/confirm-posting
  P->>D: 锁实例，逐系统校验模式并 CAS 保存 confirmed
  P->>D: 就绪事件 PENDING，wake_pending=1
  P->>S: 提交后唤醒 worker
  P-->>W: 返回实际确认系统与已放行数量
  U->>W: 点击失败事件“事件重试”
  W->>P: POST /event/{id}/retry + 幂等命令键
  P->>D: 保存 recovery_requested、PENDING、审计
  P-->>W: 已排队；确认事实不变
  Note over W,D: 前端只显示服务端状态，不自行改事件/计划状态
```

不变量：一个事件只解析自己系统码对应的模式；前端提交的系统集合只能是人工模式且属于该实例；确认和重试均使用服务端事务、权限与幂等。

## 场景 14：计划总览、计划链与事件执行描述

前提：后端已维护定义、token、事件、attempt 和调度控制记录；Web 查询权限允许查看业务范围。查询是只读快照。

结果：运维先看计划总览，再进入一个计划的分支链，最后打开事件抽屉查看 attempt 和请求；三个层级各有时间与版本，不根据日志猜测。

```mermaid
sequenceDiagram
  actor U as 运维人员
  participant W as IWS Web
  participant P as ExecutionOverviewService
  participant Q as PlanChainQueryService
  participant T as EventTraceQueryService
  participant D as DB
  U->>W: 打开计划执行总览
  W->>P: GET /process/execution/overview?businessScheduleCode=...
  P->>D: 一致性快照读取计划控制、实例、候选和异常
  D-->>P: executed/pending/running/exception 基础事实
  P-->>W: 计划名称、队列位次、资源 scope、当前分支摘要、asOf
  U->>W: 查看某个计划执行链
  W->>Q: GET /instance/{id}/chain
  Q->>D: 读取冻结定义 + token/事件/状态校验记录
  Q-->>W: graph、branches、current_state、next_states、validation
  W-->>U: 多分支泳道；已到达/下一目标及条件
  U->>W: 点击事件
  W->>T: GET /event/{eventId} + /attempts
  T->>D: 读取源/目标、模式、步骤、checkpoint、attempt、请求关联
  T-->>W: 事件执行描述和分页尝试链
  opt 运维要求重新校验
    U->>W: 点击重新校验
    W->>T: POST /event/{eventId}/revalidate
    T->>D: 无副作用校验并记录本次观察
    T-->>W: PASS/FAIL/NOT_EVALUATED/NOT_APPLICABLE
  end
  Note over W,D: 轮询仅刷新只读快照；页面隐藏停止轮询
```

不变量：计划总览的待执行顺序来自统一调度服务；计划链不把并行分支压成一个节点；事件详情不把 request_id 日志成功当成事件完成。页面错误/空数据保留明确说明。


## 场景 15：代码绑定动作、移动与编辑门禁

前提：计划版本、动作/校验器/恢复适配器注册元数据存在；Warm-Flow 节点身份和内置监听器依赖已在定义中标注。

结果：浏览器只允许编辑元数据声明的字段；代码实现、校验逻辑、恢复协议和固定节点身份只读。拓扑移动仅在当前版本和注册元数据允许时通过全图校验。

```mermaid
sequenceDiagram
  actor U as 配置人员
  participant W as EventConfigEditor
  participant P as PlanDefinitionService
  participant C as EditCapabilityResolver
  participant R as CapabilityRegistry
  participant D as DB
  U->>W: 打开最新草稿中的某个事件
  W->>P: GET definition + edit-capabilities
  P->>C: 解析事件动作/状态/边/Warm-Flow 标识
  C->>R: 查询 operation、validator、branch、recovery 元数据
  R-->>C: edit_policy、graph_edit_policy、rebindable、locked_fields
  C-->>W: 字段级 editCapabilities 和代码版本
  alt edit_policy=REGISTERED_ONLY
    W-->>U: 只显示服务端注册目录中的兼容实现选择，不编辑实现内容
  else edit_policy=CODE_FIXED
    W-->>U: 动作名称/类型只读，显示代码重构原因
  end
  U->>W: 尝试移动节点、改源/目标或分支层
  alt graph_edit_policy=EDITABLE_NEW_VERSION
    W->>P: 提交结构 patch + expectedVersion
    P->>C: 重新检查边界、校验器和受影响事件
    alt 校验通过且仍为最新草稿
      P->>D: 保存结构草稿并返回新版本
    else 缺代码兼容或非最新草稿
      P-->>W: 409 CODE_REFACTOR_REQUIRED / VERSION_READ_ONLY
    end
  else graph_edit_policy=CODE_FIXED
    W-->>U: 移动/改边控件禁用，仅查看当前固定结构
  end
  opt 需要新增动作实现、校验器、接口或恢复算法
    Note over U,R: 必须先改代码并发布新的注册能力；配置页不执行任意类名/脚本
  end
```

不变量：画布坐标移动不改变业务语义；改变边、顺序、网关或汇合属于拓扑变更。代码绑定字段只读，服务端对构造请求返回明确错误；运行实例和旧版本永不随新草稿移动。


## 场景 16：配置、执行、编辑、删除与重试的全动作审计

前提：现有 HTTP 操作日志可用；领域模块写入追加式 action audit。人工和系统动作分别使用真实用户或稳定系统账号。

结果：每个动作都有可查询的前后状态、版本、操作者、时间和结果；审计写入与状态变更一致，外部调用前后证据可关联。

```mermaid
sequenceDiagram
  actor U as 操作人员
  participant W as IWS Web
  participant C as ProcessController
  participant I as CommandAuditInterceptor
  participant S as PlanDefinitionService
  participant E as EventUpdateService
  participant X as ExternalSystem
  participant J as SnailJob
  participant A as ProcessAuditService
  participant D as DB
  U->>W: 编辑/复制/发布/删除计划草稿
  W->>C: 配置命令 + commandKey + expectedVersion
  C->>I: 进入已注册命令，执行鉴权/参数前置
  alt 权限或参数前置拒绝
    I->>A: 记录 REJECTED_DENIED/REJECTED_VALIDATION
    A->>D: 保存账号、命令键、对象和原因摘要
    I-->>C: 拒绝，不进入 service
  else 准入
    C->>S: 保存命令
    S->>A: 同一事务追加动作、前后版本、结果
    A->>D: 保存不可变审计
  end
  U->>W: 逐系统确认 / 暂停 / 恢复 / 取消
  W->>C: 运行命令 + 实例版本
  C->>E: 校验并条件更新
  E->>A: 记录操作者、系统集合/原因、前后计划状态
  J->>E: 系统触发执行/跳过/重试/续跑
  alt SKIP 整事件
    E->>A: actor_type=SYSTEM，记录 SKIP 动作及原因
    E->>D: 同事务保存 SKIPPED、token 推进和审计
  else EXECUTE
    E->>D: 本地事务认领并记录 CLAIM 审计
    alt 存在未完成外部步骤
      E->>D: 保存 SEND_INTENT
      E->>A: actor_type=SYSTEM，记录发送意图及 job/batch
      E->>D: 同事务保存 intent 审计后提交
      E->>X: 事务外调用外部系统
      alt 外部结果确定
        X-->>E: 成功/失败结果
        E->>A: 记录结果审计，关联 attempt/requestId
        E->>D: 同事务保存成功 checkpoint 或失败、状态和审计
      else 超时、断连或响应未知
        E->>A: 记录 UNKNOWN 审计
        E->>D: EXCEPTION_HOLD/MANUAL_PENDING，等待对账
      end
    else 纯本地步骤
      E->>A: 记录本地执行结果，不创建外部发送意图
      E->>D: 本地结果、状态和审计同事务提交
    end
  end
  U->>W: 查看实例/事件审计时间线
  W->>C: GET /instance/{id}/audit 或 /event/{eventId}/audit
  C->>A: 按权限查询并脱敏
  A-->>W: 动作、账号/系统、时间、前后状态、结果、关联证据
```

不变量：领域审计以 commandKey 和业务/attempt/request ID 关联；现有 @Log 只补充入口记录，不假设已有 traceId。审计为追加记录，不提供修改/删除接口；删除配置仅限未发布、未引用的最新草稿并记录删除前快照。审计失败时普通命令不返回成功；已发生外部副作用保留 checkpoint 并转人工。


## 需求覆盖索引

| 原始要求 | 覆盖场景 |
| --- | --- |
| 1 系统字段及不同过账模式 | 1、3、4、5 |
| 2 启用、初态及终态配置 | 1、6 |
| 3 跳过且状态一致 | 6、12 |
| 4 抽象状态及校验 | 1、4、6、11 |
| 5 计划状态机、共享初终态校验、固定转换 | 4、7、9 |
| 6 多分支、多起点、多终点 | 2、7、12 |
| 7 链路状态管理索引 | 主方案 §7.3；11 的定义图叠加运行事实 |
| 8 回初态或统一异常停留条件 | 8、9 |
| 9 多任务与唯一任务计划 | 2、10 |
| 10 事件定时、计划监督、排序和插队 | 10 |
| 11 统一事件更新工具 | 4、6、9 |
| 12 执行操作与纯跳过分离 | 4、5、6 |
| 13 原更新策略/操作检查点续跑 | 8、9、12 |
| 14 可选模块及复用 | 共同契约、10；主方案 §12 |
| 新增统一分支选择工具 | 2、6、7、12 的 BranchSelector |
| 新增业务统一调度及计划类型优先级 | 1、2、10 |
| 新增调度执行描述 | 11、14；主方案 §11.3 |
| 前后端配置、逐系统确认与重试 | 13；主方案 §13 |
| 计划/事件两级执行链详情 | 14；主方案 §13.4 |
| Warm-Flow 人审复用 | 13；主方案 §13.1 |
| 计划与事件统一配置页面、单事件编辑 | 13；主方案 §13.3 |
| 新配置只影响新计划/实例，旧计划保留旧配置 | 13；主方案 §3.1、§4.3、§11 |
| 版本切换查看、仅最新草稿可编辑 | 13；主方案 §4.1、§4.3、§13.3 |
| 代码绑定动作、移动和节点身份编辑门禁 | 15；主方案 §4.5、§13.3 |
| 配置/执行/编辑/删除/重试等全动作审计 | 16；主方案 §11.5、§13.2 |
| 命令版本与权限 | 13、14；主方案 §13.2、§13.4 |
| 最终失败/取消 | 8、9；主方案 §5.3、§13.2 |

本轮验证仅针对文档与图表；后续按主方案验收矩阵验证真实业务状态、数据库事务、并发、SnailJob 接线和外部幂等。
