# 在库管理后继切片清单

> **2026-11-02 重新评审纠偏（历史判断，已由 2026-11-03 最终验收关闭）：** 后续顺序与范围曾以[重新评审与交接](../inventory-reassessment-20261102/review-and-handoff.md)第 6—7 节为准；当时存在空库结构缺失、详情展开权限和数量口径等 P1/P2 问题，P1—P7 未完成。按用户最新边界，不承担旧版兼容，旧库迁移不再作为标准新库交付主线；真实 MOVE/非空清空保持后续范围，不阻断本期其他底座。以下历史记录及测试数字保留其原版本，不代表当前整体验收。用户要求后续不使用子代理。

> **2026-11-03 本期整合验收：** R1—R6 当前范围已完成，复评 REV-01—REV-08 已关闭。正式空库最新入口为 `181` 表、`77` WMS 表、`256` 菜单，规则绑定列、六张分配/预留表、业务屏障表、空间最终字段和新增权限目录齐全；全 WMS `481/481`、根 reactor `43/43` clean `test-compile`、全应用 `43/43` package 通过，最终 JAR 为 `4C2515415A427CF1AC1A516DA05A408F7491B8E227CBEB9DCC6D5552BD95EBC3`。规则详情 readiness 已补齐生产绑定快照字段并由正式 API 复核 `READY/executionReady=true`。空间生命周期、规则发布和逻辑合并均已接入持久计划；10 个 ACTIVE 定义首次 provision 与同库重启后无重复。Web Vitest `264/264`、空间生命周期 `70/70`、类型检查、Oxlint、构建及普通账号真实浏览器通过；本轮另以 `192.168.1.21:18000` 经代理访问最终 JAR，13 项页面断言、20 次 API 均通过。空库 `sys_user`/`sj_system_user`/`sai_user` 已保持需求密码值不变，分别写入 BCrypt、SHA-256、PBKDF2 哈希。真实 MOVE、非空清空、现场入出库、PDA 真机和真实库写入不属于本期已完成范围。

日期：2026-10-29（Asia/Shanghai）。本次只新增本文件，不修改原任务图、合同、history 或[原前沿报告](report.md)。需求仍以 [P1 合同](C:/code/company/innolux-iws-inventory-design/work/inventory-p1-contract-20261027.md)、[任务图](C:/code/company/innolux-iws-inventory-design/work/inventory-implementation-tasks-20261027.md)、[主方案](C:/code/company/innolux-iws-inventory-design/docs/inventory-management-design-20261026.md)和[分配专题](C:/code/company/innolux-iws-inventory-design/docs/inventory-allocation-rules-20261026.md)为准。切片沿用既有任务 ID，不新增父票据，不回写父任务完成状态。

**2026-10-31 10:29 当前进展：** P4-03 trace `13/13`、P4-04 SAME_TX `7/7`、P5-01 结构 `22/22`、P5-02 内核 `21/21` 已通过定向隔离验证。P5-03 frozen-input provider 契约已通过隔离 MySQL `3/3`，并与 `WmsAllocationReservationIntegrationTest` 合并回归 `52/52`；后者仍为 P5-03 `39`、P5-04 `10`，总计 `49`。平台现可由实现 `LocalActionInputProvider` 的本地动作在 `SAME_TX` 内读取完整业务命令、证据和附加事实；空输入失败关闭，普通动作保留通用摘要回退，但 WMS 生产动作尚未绑定持久父任务输入源，supervisor tick/worker 仍不可宣称可执行。分配锁位/释放四张正式定义已按 `INBOUND` fixture 经 `createDraft/validate/publish` 持久化，`4` 个 `ACTIVE`、其中 `2` 个 `QUEUE SUPERVISOR`、合计 `10` 个 INTERNAL 事件和 `4` 个平台监督动作；真实 attach `3/3` 已验证 QUEUE runtime/目录门禁在实例写入前失败、一 taskKey 一业务实例、重放幂等和异摘要拒绝。装拆容量已把本储位活动 `CAPACITY_SHARE@TOP_LEVEL_LOAD` 预留量计入变更后容量，并按 `capacity → occupancy → allocation_lock → channel_lock` 统一锁序；锁保持 `LOCKED/NONE`，实体槽、通道锁及已推进外部效果的锁仍失败关闭。普通通道已支持成员迁移、批量访问顺序、定义编码/模式/状态变更和阻挡边增删，均按相关通道 ID、储位 ID、活动资源次序共锁并 revision CAS。尚未运行 supervisor tick/worker，正式目录/挂载 seed、空间配置 API、阻挡图可达性重算、真实锁消费与 trace 组合、完整 TG03/TG04/TG10 仍未完成。P5-04 仍缺真实设备适配、UNKNOWN 对账和独立效果历史审计；P5-05、全部 P6 和 P7 页面/PDA 未完成。P5-01 当前结构为 `6` 表、`166` 列、`79` 条索引列元数据、`55` CHECK。真实库、部署、commit/push 仍未授权。

**2026-10-31 11:16 当前进展：** 四个 WMS 生产动作已绑定持久业务任务输入。新增
`wms_business_task`（`19` 列、`7` 条索引列元数据、`5` CHECK）及独立 verify；隔离
MySQL `8.0.45` 首次创建、第二次精确元数据预检和独立验收均通过。
`WmsBusinessTaskService` 已实现创建回放、稳定键/实例唯一、双连接并发收敛和
修订号状态迁移，隔离 MySQL `5/5`。
`WmsFrozenActionInputReader` 按 `plan_instance_id` 读取完整 `input_json` 并核对
`inputHash`；`EVALUATE_ALLOCATION/RESERVE_STORAGE/VERIFY_ALLOCATION/APPLY_LOAD_CHANGE`
已实现提供器。新增正式 `EventUpdateService.update` 路径用例，证明
任务经服务创建并启动后，`RESERVE_STORAGE` 从持久命令执行，提交 `LOCKED` 检查点/分配锁，并以
`wms-business-task:<taskId>` 留证。`WmsAllocationReservationIntegrationTest` 当前
`50/50`（P5-03 `40`、P5-04 `10`），provider `3/3`、内核 `21/21`、计划合同 `3/3`；
JDK21 根 Maven main compile `17` 模块通过。supervisor tick/worker、正式调度/计划类型/
挂载 seed、正式父业务创建调用方/Controller 与 core/init 同步仍未完成；不得把本次
正式事件入口验收外推为 QUEUE worker 或真实库已可执行。

**2026-10-31 11:43 当前进展：** 正式 supervisor tick 与 QUEUE worker 隔离链路已通过。
物化 INTERNAL 事件时现在设置 `payloadReady=true` 并继承实例 `inputHash`，外部回执仍可
覆盖入站摘要。新用例先发布 BUSINESS/SUPERVISOR 两套定义并真实 attach 业务实例，督导
完成候选决策和 `PLAN_SLOT` 准入后，worker 连续执行
`EVALUATE_ALLOCATION → RESERVE_STORAGE → VERIFY_ALLOCATION`：三轮均为
`processed=1/succeeded=1`，三个平台事件为 `SUCCESS`，`3` 条 attempt 证据均指向
`wms-business-task:<taskId>`，最终 `1` 个 `LOCKED` 分配锁且业务计划为 `COMPLETED`。
父业务任务在测试中保持 `RUNNING`，没有伪造自动完成。`WmsAllocationSupervisorWorkerIntegrationTest`
为 `1/1`，Surefire XML SHA-256 为 `D926C0FC5A7013752BAB511290094CC39A163F5954A6366F9B714829EFDD9553`，
证据见 [ready](../p5-supervisor-worker-20261031/ready.md)；物化器变更后的 attach、
发布、业务任务、provider、内核和计划合同回归合计 `36/36`。正式目录/挂载 seed、
空间配置 API、阻挡图可达性重算、锁消费和完整 TG03/TG04/TG10 仍未完成；真实库、
部署、commit/push 仍未授权。

**2026-10-31 12:22 当前进展：** 装拆正式 QUEUE 链
`CHECK_LOAD_CHANGE → APPLY_LOAD_CHANGE` 已补通。`CHECK` 只做只读结构预检并提交
`CHECKED` checkpoint，不写关系、根位置、占用或历史；`APPLY` 在最终事务内重新
取得权威锁，复核容量、占用、活动分配锁和通道锁后，原子提交关系/根位置/占用/
关系历史及 `APPLIED` checkpoint。正式计划类型为 `LOAD_CHANGE`，状态机为
`START → CHECKED → END`。`WmsAllocationSupervisorWorkerIntegrationTest` 当前
`3/3`，包括分配三动作、装拆两动作和容量失败回滚/重试，Surefire XML SHA-256 为
`4ED39A2EA9A671BD9FF4C8273CB21B7F9C71AF0EE123A1AF1DF819ED1120FFF5`；
装拆用例确认 CHECK 后原关系/占用不变且无新 `wms_occupancy_effect`，APPLY 后
关系删除、物料成为储位根、活动根由 `1` 变 `2`、关系历史恰为 `1` 条，两个
attempt 均引用 `wms-business-task:<taskId>`，父业务任务仍为 `RUNNING`。
回滚用例在 CHECK 后将容量降为 `1`，APPLY 以 `CAPACITY_EXCEEDED` 进入
`RETRY_WAIT`，未提交任何关系、根位置、占用、关系历史或本效果键占用效果；
恢复容量并将测试容器重试时钟对齐后，同一冻结任务/效果键重试成功，APPLY
attempt 为 `2` 条，最终为 `APPLIED`。
`WmsAllocationReservationIntegrationTest` 复跑 `50/50`（P5-03 `40`、P5-04
`10`），attach、发布、业务任务、provider、内核、计划合同回归合计 `36/36`。
本轮进入空间正式配置 API、目录/挂载 seed 和阻挡图可达性重算；它们仍未完成，
完整 TG03/TG04/TG10、真实锁消费与 trace 组合仍待后续。真实库、部署、
commit/push 仍未授权。

**2026-10-31 12:44 当前进展：** 新增单通道显式阻挡图可达性纯内核。
内核只消费同一通道的显式成员、显式有向阻挡边和调用方给出的活动阻挡储位，
不按地址文本、深度文本或访问顺序推断隐式阻挡。输出按 `access_order` 排序；
活动阻挡储位沿有向边传递阻断全部后代，并输出每个不可达储位的活动阻挡来源。
占用储位本身保持结构可达，由后续占用/容量筛选拒绝，避免把“可达”误当“可分配”。
内核拒绝重复成员/顺序、自环、重复边、通道外储位、有向环和通道外活动阻挡源。
`WmsChannelReachabilityEvaluatorTest` 为 `10/10`，Surefire XML SHA-256 为
`0D65AA739675C1EFC94C9E7EC279E84C73DF5E10039D7C5AB0452F02A388DDB4`；
与 `AllocationKernelTest` 合并回归 `31/31`，JDK21 主源码 `17` 模块、WMS `261`
个源文件编译通过。当前仍是纯内核，未读数据库、未生成空间快照版本、未接入候选
筛选或正式空间配置 API。下一步应实现空间快照读取/版本摘要和候选集成，再补
命令幂等审计入口；真实库、部署、commit/push 仍未授权。

**2026-10-31 13:00 当前进展：** 显式阻挡图已接入隔离 MySQL 空间事实快照。
`WmsChannelReachabilitySnapshotService` 在 `REPEATABLE_READ` 只读事务内读取通道
revision、成员/访问顺序、相关显式阻挡边和活动占用，再复用纯内核计算可达性；
规范序列生成 SHA-256，占用变化会改变摘要。跨通道边和未知通道失败关闭，不返回
部分快照。`WmsChannelReachabilitySnapshotIntegrationTest` 为 `4/4`，Surefire XML
SHA-256 为
`44A07765BE82D2E818912C195B4AFCFC4EB049A235A77255BA31D776ED4CED80`；
与显式阻挡图内核、分配内核合并回归 `35/35`。共享测试上下文复跑
`WmsAllocationReservationIntegrationTest 50/50`、
`WmsAllocationSupervisorWorkerIntegrationTest 3/3`。同时以当前源码重跑 P5-01
隔离验证器，确认规范结果仍为 `22/22`、`6` 表、`166` 列、`79` 条索引列元数据、
`55` CHECK；此前“21/21”说法不作数。当前快照仍是应用内一致读结果，没有持久化
空间版本，也未接入候选筛选或正式空间配置 API。证据见
[ready](../p5-channel-reachability-snapshot-20261031/ready.md)；真实库、部署、
commit/push 仍未授权。

**2026-10-31 13:11 当前进展：** 数据库可达性已进入正式分配候选硬筛选。
评估路径使用一致性快照；锁位路径在通道、储位和实时账锁之后调用
`snapshotForUpdate`，对通道、成员、相关阻挡边和活动占用执行 `FOR UPDATE`
当前读，revision/status 不一致时要求重试。调用方输入 `pathReachable=true`
不能覆盖数据库不可达事实。新用例中活动阻挡储位占用 blocker，被其显式边阻断
的后代以 `PATH_UNREACHABLE` 持久拒绝，独立储位被选中并锁位；
`WmsAllocationReachabilityCandidateIntegrationTest` 为 `1/1`。快照、候选接入、
显式图内核和分配内核合并回归 `36/36`；`WmsAllocationReservationIntegrationTest`
复跑 `50/50`，supervisor/worker 复跑 `3/3`，JDK21 main compile `17` 模块、
WMS `263` 个主源文件通过。证据见
[ready](../p5-allocation-reachability-candidate-20261031/ready.md)。仍无持久化
空间版本、统一物理槽占用模型和正式空间配置 API；真实库、部署、commit/push
仍未授权。

**2026-10-31 13:23 当前进展：** 分配效果推进已接同事务历史审计。复用既有
`wms_object_audit`，每次非回放的 `SEND_INTENT/ACCEPTED/UNKNOWN/CONSUMED`
推进在效果状态 CAS 的同一 `MANDATORY` 事务内，为分配锁和通道锁各追加一条
`ALLOCATION_EFFECT_<target>` 审计；before/after 记录状态、效果和 revision，
证据记录任务、effectKey、trace 和 evidenceRef。`UNKNOWN` 保持未知结果，
幂等回放在写审计前返回，不重复追加。分配集成套件最终 `50/50`，supervisor/worker
`3/3`；`SEND_INTENT` 后恰有两条审计，`UNKNOWN` 重放两次后仍只有两条。
证据见 [ready](../p5-effect-audit-20261031/ready.md)。P5-04 仍缺真实 provider
回执、UNKNOWN 查证/对账和正式释放计划；空间快照持久化仍待唯一 SQL owner 和
已签契约。真实库、部署、commit/push 仍未授权。

**2026-10-31 13:39 当前进展：** 分配释放正式 QUEUE 链已补通。三个释放动作均实现
持久冻结输入提供器：同一 `WmsAllocationReleaseCommand` 由 CHECK 派生无效果检查
命令，由 RELEASE 直接执行，由 VERIFY 派生核验命令，不再把平台摘要当业务命令。
计划经正式发布、attach、supervisor 准入后，worker 连续执行
`CHECK_RELEASE_EVIDENCE → RELEASE_ALLOCATION_LOCK → VERIFY_RELEASE`，三个
attempt 均引用同一 `wms-business-task:<taskId>`；最终分配锁为
`RELEASED/NO_EFFECT`，释放流水与锁终态互链，通道锁为 `RELEASED`，物理占用
不因释放被创建或删除，业务计划为 `COMPLETED`，父业务任务保持 `RUNNING`。
`WmsAllocationSupervisorWorkerIntegrationTest` 扩为 `4/4`，
`WmsAllocationReservationIntegrationTest` 复跑 `50/50`，计划合同 `3/3`。
证据见 [ready](../p5-release-worker-20261031/ready.md)。P5-04 仍缺真实 provider
回执与 UNKNOWN 查证/对账；正式目录/业务任务/释放 seed 仍需唯一 SQL owner 整合；
真实库、部署、commit/push 仍未授权。

**2026-10-31 13:55 当前进展：** P5-04 UNKNOWN 只读查证视图已实现。新增查询服务按
父业务类型/ID、TaskKey 和 `1..200` 上限检索仍为 `LOCKED/UNKNOWN` 的分配锁，
关联通道锁及最近一次 `ALLOCATION_EFFECT_UNKNOWN` 审计证据，并显式返回通道锁是否
同为 `LOCKED/UNKNOWN`。最终 `WmsAllocationReservationIntegrationTest` 为 `51/51`
（P5-03 `40`、P5-04 `11`），`WmsAllocationSupervisorWorkerIntegrationTest`
复跑 `4/4`；JDK21 main compile `17` 模块、WMS `267` 个主源文件通过。证据见
[ready](../p5-unknown-investigation-20261031/ready.md)。该视图只定位待查证事实，
不执行对账、不改 UNKNOWN 状态、不自动释放；真实 provider 回执、权威外部证据、
人工/系统对账决策与正式对账动作仍未完成。正式 API/Controller、目录/挂载 seed、
空间快照持久化和空间配置 API 仍待后续；真实库、部署、commit/push 仍未授权。

**2026-10-31 14:15 当前进展：** P5-04 UNKNOWN 对账后释放合同已补通。现有 CHECK
不允许 `LOCKED/NO_EFFECT`，因此不新增无法落库的中间态；释放冻结命令增加可选
`evidenceType`，空值兼容为 `NO_EFFECT`，`RECONCILED_NO_EFFECT` 才允许终结
`SEND_INTENT/ACCEPTED/UNKNOWN`，并在同一 `SAME_TX` 内直接落
`RELEASED/NO_EFFECT`。普通 `NO_EFFECT` 仍拒绝 UNKNOWN，反向也不能把 `NONE`
伪装成对账证据。`WmsAllocationReservationIntegrationTest` 为 `52/52`
（P5-03 `40`、P5-04 `12`），supervisor/worker `4/4`、计划合同 `3/3`；JDK21
main compile `17` 模块、WMS `267` 个主源文件通过。证据见
[ready](../p5-reconciled-release-20261031/ready.md)。仍缺真实 provider 回执、
权威证据适配器、自动/人工对账编排和正式 API/页面；不得由超时或查证视图自动
释放。真实库、部署、commit/push 仍未授权。

**2026-10-31 14:23 当前进展：** 对账后释放合同增加通道效果一致性保护：每条分配
锁外部效果必须与所属通道锁效果完全一致，否则以 `EVIDENCE_INVALID` 拒绝释放。
最终源码上重跑 `WmsAllocationReservationIntegrationTest` `52/52`、supervisor/
worker `4/4`、计划合同 `3/3`；新增场景还验证 `NONE` 拒绝对账证据、UNKNOWN
拒绝普通无效果、分配/通道 `UNKNOWN/ACCEPTED` 不一致拒绝，以及恢复一致后
`RECONCILED_NO_EFFECT` 释放为 `RELEASED/NO_EFFECT` 并保留对账证据引用。
Surefire XML SHA-256 分别为
`c0835496ab4d9aae4e5a833c3a950193e328dda85efbdf00e0e92711815d591a`、
`6e957d29770b5c89ac8f6fd7c20be13bc06d388821739729fbc33eca1ce6c7c2`、
`5b47aecd4aaa3a2ae6f2e5f2ac307952126ae57c82fcbdb2a84f95181b7502c9`。
真实 provider 回执、权威证据适配器、对账编排和正式 API/页面仍未完成。

**2026-10-31 14:39 当前进展：** P6-01 已启动纯合并计划内核。`WmsLogicalMergePlanner`
要求目标为当前生效 `ACTIVE` 作用域，固定来源范围必须显式给出；只有规则包版本相同
或来源作用域被显式列为兼容时才生成 `MIGRATE`，来源越界或规则不兼容均保留原
归属并生成稳定冲突原因。计划按库存 ID 排序、最多 500 条一批，并用目标、范围及
全部归属事实生成稳定摘要；`WmsLogicalMergePlannerTest` `7/7`。全量 Maven
`testCompile` 仍被既有 `WmsRulePublishRetirementRaceIntegrationTest` 构造器漂移
阻断，本切片未修改该存量测试，改用模块 classpath 定向编译测试。P6-01 仍未做
归属/历史持久化、锁/CAS、effectKey 批次幂等和来源退役。受注释同步影响的 P5
最终源码已复跑 reservation `52/52`、supervisor/worker `4/4`、计划合同 `3/3`；
JDK21 main compile `17` 模块、WMS `268` 个主源文件通过。证据见
[ready](../p6-logical-merge-kernel-20261031/ready.md)。

**2026-11-01 10:28 当前进展：** P6-01 已补真实 MySQL 持久化切片。
`WmsLogicalMergeService` 在调用方事务内锁定目标作用域和物料当前归属，逐项复核
assignment ID、来源作用域版本、规则包版本与 assignment revision；CAS 成功后推进归属
修订并逐物料追加历史。批次复用 `wms_command_attempt` 绑定 effectKey 与冻结输入摘要，
相同摘要幂等回放、异摘要失败关闭，不新增表结构。
`WmsLogicalMergePersistenceIntegrationTest` `6/6` 覆盖成功、回放、异摘要、来源快照
失效、目标变化、历史写入失败整批回滚，以及不同 effectKey 双连接竞争时仅一批提交、
另一批稳定返回 `LOGICAL_MERGE_CONCURRENCY_RETRY`；`WmsLogicalMergePlannerTest` `7/7`。JDK21
全 reactor main compile `43` 模块、WMS `272` 个主源文件通过。证据见
[ready](../p6-logical-merge-persistence-20261101/ready.md)。P6-01 仍未做并发压力与
重试编排、批次暂停恢复、来源作用域退役和完整规则语义兼容，不能关闭父任务或解锁 P6-02。

**2026-11-02 13:20 当前进展：** P7 已授权的在库只读、跨公司可见范围、trace/UNKNOWN
和 PDA 定位切片通过最终 LAN。普通用户不再被建模为固定属于一家公司；公司管理范围使用
`wms_company_management_grant`，库存读取使用公司/仓库/储位 visibility、当前部门关系和
逐行 DataPermission，前端自报 `companyId` 不参与范围判断。LAN 中用户 A 无公司 B 管理授权
仍可读取两家公司库存，用户 B 只读取公司 A；删除部门可见关系后下一请求立即隔离，UNKNOWN
抽屉真实显示两家公司锁。期间修复 UNKNOWN 视图把 64 位 ID 作为 JSON 数值返回导致的
JavaScript 精度丢失，相关 ID 统一返回字符串。最终 JAR
`273250CD6ED57AEDE9D1852C46257735009AFFD038DCE157D35F41561CD05BA2`、init
`44A3A9C1…`、LAN `20261102-131740` 为 `7/7 PASSED`；在库只读 MySQL `13/13`、Catalog
`5/5`、Web Vitest `248/248`、`vue-tsc`、`oxlint`、生产构建及 PDA 静态编译通过。证据见
[ready](../p7-inventory-read-20261101/ready.md)。`INV-P7-03`、`INV-P7-04`、`INV-P7-06`
仍只关闭当前切片，完整动态目录/允许挂载/能力拒绝未全部验收；`INV-P7-05` 继续因真实父业务、
真实 `MOVE`、真实库写入和部署未授权而 `blocked`。真实库、部署、commit/push 仍未授权。

**2026-11-02 13:50 当前进展：** `INV-P3-03-BINDING-SNAPSHOT` 已实施并通过隔离
MySQL 定向验收。规则激活现在显式选择 `STORAGE FILTER/SORT` 用途版本，冻结
精确属性目录版本和 limits，使用 `iws.rule.attribute-bindings/1` 快照及
`iws.rule.digest/2` 稳定摘要；历史 NULL 快照继续 `LEGACY_UNBOUND`，不自动
漂移或回填。混合 scope 优先报 `RULE_PACKAGE_SCOPE_MISMATCH`，未激活规则版本
在包写入前报 `RULE_VERSION_NOT_ACTIVE`。规则集成套件 `41/41`，P7 在库只读
回归 `13/13`，JDK21 全 reactor main compile `17` 模块、WMS `288` 个主源文件
通过。后续已于 2026-11-03 补齐生产事实 provider、正式属性目录、发布执行适配、
core/empty init 同版和规则详情 readiness；正式 API 返回 `READY/executionReady=true`。
TG05 中仅属于旧库迁移的动作不再作为标准化新项目本期主线。当前证据见
[本期整改与验收摘要](../verification-current-20261103/summary.md)。

## 1. 已派首片与当前证据

| 任务 / 切片 | 当前状态 | 交付及边界 |
|---|---|---|
| INV-P4-03：内部 trace 追加持久化 | done（本期底座）；真实 MySQL `WmsMaterialTraceIntegrationTest` `13/13`，汇总/历史查询权限和任务键筛选已纳入最终全 WMS `481/481`。 | material/trace/** 使用 MANDATORY 外事务；头/item/edge 同事务、效果幂等、补偿只追加；effect_result 主定 APPLIED。正式 MOVE/物料父业务仍不开放。 |
| INV-P4-01：material 目标结构 | 目标 DDL 与 core/init 同版切片已获限定接受。 | 支持独立 P4 持久化开发；不证明完整 P4、旧库存承接或正式部署。 |
| INV-P4-02-LOAD-KERNEL | 纯装载内核已接受，当前回归已包含其 23 项。 | 可供独立装载适配使用；不表示已有持久化、关系变更事务或容量账。 |
| 当前回归与构建 | 主已接受 293 项回归与 38 模块 package；trace/P5 另有独立定向证据。 | 293/38 不覆盖本轮 P5-03 变更；不证明新包 LAN、真实 provider、真实库或部署。原 report 不回写。 |

trace 验收解除的是“成功事实追加原语可用”，不是“物料事实已与平台原子提交”。后继整合直接消费 Confucius 的实际接口、输入摘要及验收证据，不预造第二套 trace 服务或改写其结果码。

## 2. INV-P4-04：SAME_TX 接线后继

### 可并行准备与真正前置

| 切片 | 状态与真实前置 | 允许写集及交付 | 验收门 |
|---|---|---|---|
| 平台适配合同与隔离测试准备 | 可与 trace 开发并行；依赖冻结的 P1 身份/摘要合同和现有平台接口。trace 的最终方法签名由其执行者交付，不重复实现。 | 建议 WMS material/process/**、对应独立测试及 fixture；确认目录未被认领后才派。只准备 CHECK/APPLY/VERIFY 的输入输出、能力注册和故障注入边界。 | 不把接口准备、测试骨架或假调用计为 trace 集成完成；不注册正式业务类型、挂载 seed 或 MOVE。 |
| 最小原子事实与 trace/checkpoint 组合 | done（fixture 限定）；`WmsMaterialChangeSameTxIntegrationTest` `7/7`。 | WMS material/process/**、所选 material 持久化调用点及独立测试；正式父业务入口仍未开放。 | 已通过真实 LocalAction 路径验证成功、trace/checkpoint/平台失败回滚、重试、双连接竞争和异摘要冲突；不关闭正式 MOVE 或完整 P4-04。 |
| 持久父任务、QUEUE 与完成屏障 | done（本期底座）；任务/屏障结构、实例唯一性、允许挂载关系、正式 QUEUE worker、跑中重启恢复和权限撤权/停用已验。 | WMS task/**、对应 Mapper/测试；process 注册/适配复用现有平台，未另建引擎。 | 校验 process、scheduler、SnailJob、environment 及唯一已发布定义，缺项返回 `QUEUE_RUNTIME_UNAVAILABLE`；不静默退回 IMMEDIATE、不创建 stranded 实例。持久屏障未满足，父任务不得完成。 |
| 锁消费/占用组合 | done（本期组合底座）；最小原子 fixture 与 P5-03 的锁消费、占用账能力均已验，并纳入最终全 WMS 回归。 | material/process 与 allocation 交叉调用点由同一领域事务收口。 | 同一 APPLY 内提交库存/位置、必要装载/家谱、锁消费/占用、trace 和 checkpoint；装拆前后容量守恒。真实 MOVE 与外部设备回执仍属后续。 |

### 平台接线固定合同

- 固定事件链 CHECK_MATERIAL_CHANGE → APPLY_MATERIAL_CHANGE → VERIFY_MATERIAL_CHANGE；每个事件为一个 INTERNAL 动作，APPLY 声明 SAME_TX、checkpointed=true。LocalAction 不嵌套其他动作或计划，不发 HTTP。
- 复用现有 EventMutationService.executeLocalAndFinish：平台事务包含 action.execute、recordResult 及事件收口/后继推进。trace 必须加入这个外层事务，不能改成异步补历史、REQUIRES_NEW 或提交后补写。
- 同一可信父业务/明细、taskKey、instance、attempt、effectKey、inputHash 必须可回读关联；重试保留原任务身份和冻结版本。同键同摘要返回持久原事实，异摘要拒绝。人员/部门/执行账号取受信上下文，不接受前端自报或虚构 provider。
- trace 的成功领域结果用 APPLIED；不能未经适配就将它当成平台 EffectResult。动作返回平台真实成功枚举并关联领域事实；失败、取消、UNKNOWN 仅沿既有执行审计留证，不伪造成功 trace 或库存 delta。
- 最小 fixture 只选不涉及活动分配锁的明确事实场景；缺必要锁消费/占用能力的输入必须拒绝。不得用总返回成功的空实现绕过；trace 本身仍不依赖 allocation 上线。
- fixture 父业务身份仅存在于隔离测试。正式入口继续受目录、允许挂载、可信父单、规则绑定和任务屏障门禁限制；不新增独立 MATERIAL_CHANGE 业务头，不把 fixture 说成真实 MOVE 或到位证据。

### SAME_TX 必须获得的证据

| 场景 | 实际观察 |
|---|---|
| 成功 | 在同一数据源、同一外层事务中，领域事实、trace头/item/edge、checkpoint及平台事件/token关联正确；VERIFY读取真实提交结果。 |
| 分点失败 | 分别在领域写后、trace item/edge写入、checkpoint写入及平台收口处注入异常，从独立连接确认本次整组均未提交；不是只检查内存异常。 |
| 重试与竞争 | 失败后原键重试、提交结果不明后的事实回读、两个连接同效果键竞争，都不产生重复领域效果或不完整历史；异摘要明确拒绝。 |
| RR与锁后读 | 普通一致性快照只用于准备/查询；事实提交取得锁后从权威行 current read 重校 revision、父链/规则及所需锁证据，不能沿用锁前 MVCC 快照。 |
| 屏障/UNKNOWN | 领域未完成或效果未知时不推进父业务成功；恢复保持原键与证据。QUEUE实际驱动和本地动作fixture分别记录。 |

本切片以 A14、A26—A35 中实际覆盖的 fixture 合同及 WC06/WC11/WC17、AR20/AR21 的 fixture 部分验收；未覆盖的场景逐项留后继。P4-04 的完整状态仍由主任务图管理，独立追加测试、平台实例 COMPLETED 或 HTTP 200均不能直接关闭它。

## 3. P4 持久化的最小供给

这些交付按组合场景需要选择，不把整套完成作为 trace 或平台适配准备的假前置。

| 既有任务 ID / 子集 | 实际前置与供给 | 并行写集 | 验收 |
|---|---|---|---|
| INV-P4-02：装载持久快照读适配 | 已验 load_node/relation结构与纯kernel；独立读路径无需P2迁移、P3完整发布或allocation。 | 建议 load/persistence/**、mapper/wms/load/**及独立测试；不写 material/trace/**，可并行。 | 同一只读一致性快照交付完整节点/显式关系给 LoadTreeKernel；缺节点、非CARRIER父、环/双父/超深整组拒绝，稳定读回；不写关系或推断未知状态代码。 |
| INV-P4-02：所选事实持久化 | 所需 production/属性、库存/容器映射、storage_info与关系历史字段及语义已核对。缺字段由P4-01相应结构子片补齐，不笼统等完整P2。 | material中排除trace目录的持久化区域、load相关区域及Mapper/测试，具体类由整合者认领。 | 整组锁/CAS、根位置与关系历史一致；单叶decimal(20,6)、SUM精确BigDecimal。正式写开放前必须接trace及场景必需容量账，不新增单储位单容器限制。 |
| INV-P3-03：所用规则消费/binding | done（本期范围）；生产 `STORAGE` 来源、6 条空库属性合同、发布绑定快照、正式发布计划、详情 readiness 和旧物料不漂移均已验。 | rule/**及正式 SQL/seed；复用现有 iws-process 发布动作。 | 发布快照可回读、全叶子安全 ALL、目标授权有效；当前正式 API 返回 `bindingSnapshotState=READY`、`executionReady=true`。 |
| INV-P4-03：汇总/历史查询 | 有真实叶子、单位和规则版本，以及已验trace读回；预留/扣留不足部分明确关闭。 | material查询区域，trace查询扩展需先取得Confucius释放的写权。 | 叶子/最近载具去重、不同单位/版本不误聚合、明细分页一致；历史改名不反写，trace追加不改库存。未知事实不填零冒充可用量。 |

## 4. P5 allocation / lock 后继

以下 P5-01—P5-04 的 2026-11-03 本期底座已验收，P5-05 因真实 MOVE/设备回执/现场入出库范围未授权保持 `blocked`。建议 Java 写集相对 WMS 的 src/main/java/com/innolux/iws/wms/，Mapper XML 与测试使用对应独立目录。

| 既有任务 ID | 真实前置 | 写集与并行条件 | 验收及解除条件 |
|---|---|---|---|
| INV-P5-01：分配、锁、通道目标结构 | done（独立目标 DDL 限定）；`22/22`、`6` 表、`166` 列、`79` 条索引列元数据、`55` CHECK。 | 独立 P5 SQL/verify；core/init 仍需唯一 SQL owner 同版整合。 | `candidate_share_key` 支持同候选多维/多槽；释放流水允许同通道多个份额分别释放；结构通过不等于正式 allocation 已上线。 |
| INV-P5-02：候选硬筛选与排序 | done（纯内核限定）；`AllocationKernelTest` `21/21`。 | allocation/kernel/** 及独立测试；未改 rule/load persistence。 | 全叶子安全、禁存/容量/锁/占用/扣留与深位可达；兼容余量、稀缺保护、best-fit/多维残余、稳定唯一键；外部活动通道集合可硬拒绝。 |
| INV-P5-03：短事务锁位、装拆容量账 | done（本期底座）；EVALUATE/RESERVE/VERIFY 与 CHECK/APPLY_LOAD_CHANGE 已有生产能力 Bean、业务任务服务、持久输入提供器、正式目录/挂载、空间快照和 supervisor/worker 链。普通储位成员/访问顺序/通道定义/阻挡边变更共锁、活动容量锁装拆复核均已纳入最终回归。 | allocation/reservation/**、allocation/space/**、allocation/process/**、load/change/**、task/**、kernel 公开预览入口及测试。 | 通道/储位锁序、锁后 current read、成员迁移重试、容量份额/实体槽、共享通道唯一认领、SAME_TX checkpoint 回滚、装拆容量与并发 effectKey、正式 QUEUE 三动作和失败回滚/重试均已验。真实设备外呼不在本期范围。 |
| INV-P5-04：释放、消费、UNKNOWN | done（本期底座）；CHECK/RELEASE/VERIFY 正式 LocalAction、UNKNOWN 查证、`RECONCILED_NO_EFFECT` 受控释放、双连接竞争和同事务审计均已验。 | allocation/**及必要 process 适配/测试。 | 证据检查、逐锁 revision CAS、释放流水回放、同通道多份额、通道收敛、物理占用不变、事故回滚、UNKNOWN 阻断和受控对账释放均已验。真实设备 provider 回执、权威外部结论和现场对账编排由后续 `INV-P5-05` 扩围承接。 |
| INV-P5-05：真实父业务 | blocked；除P5-04/P4-04外，仍需用户明确扩围、真实父单和可信到位证据。 | 本清单不派此项，不生成MOVE/provider/业务种子或菜单。 | A19/A22、TG01/TG02及真实清空、正式物料入口保留后继；fixture不得替代。 |

P5-03必须验证持久活动通道锁，短事务行锁在提交后不能替代后续任务互斥。普通储位加入、移出或跨通道变更共锁旧/新通道与储位；UNKNOWN阻断配置/成员变更及分配。P4已有occupancy两表不表示P5锁位/释放已实现。

本轮 P5-03 短锁/装拆切片证据见 [ready](../p5-allocation-short-tx-20261029/ready.md)，通道成员共锁与最终锁序证据见 [ready](../p5-channel-membership-20261030/ready.md)，访问顺序批量变更证据见 [ready](../p5-channel-access-order-20261030/ready.md)，通道定义与阻挡边变更证据见 [ready](../p5-channel-config-edge-20261030/ready.md)，显式阻挡图可达性内核见 [ready](../p5-channel-reachability-20261031/ready.md)，数据库快照见 [ready](../p5-channel-reachability-snapshot-20261031/ready.md)，锁后当前读候选硬筛选见 [ready](../p5-allocation-reachability-candidate-20261031/ready.md)，计划状态/发布图形合同见 [ready](../p5-allocation-plan-contract-20261030/ready.md)，挂载目录门禁见 [ready](../p5-plan-mount-gate-20261030/ready.md)，四定义正式发布与真实 attach 见 [ready](../p5-plan-runtime-20261030/ready.md)，活动容量锁装拆复核见 [ready](../p5-load-lock-capacity-20261030/ready.md)，P5-04 释放正式动作链证据见 [ready](../p5-allocation-release-20261030/ready.md)，持久业务任务输入源见 [ready](../p5-business-task-input-20261031/ready.md)，supervisor/worker 正式链路见 [ready](../p5-supervisor-worker-20261031/ready.md)，效果历史审计见 [ready](../p5-effect-audit-20261031/ready.md)，正式释放 QUEUE 链见 [ready](../p5-release-worker-20261031/ready.md)，UNKNOWN 只读查证见 [ready](../p5-unknown-investigation-20261031/ready.md)，对账后受控释放见 [ready](../p5-reconciled-release-20261031/ready.md)，P5-01 结构修订证据见 [ready](../p5-allocation-foundation-20261029/ready.md)。以上均为定向隔离验收，不外推为完整父任务完成。

P5-03 当前下一顺序：由唯一 SQL owner 评审并同版整合 `wms_business_task`、正式业务调度/计划类型、允许挂载关系和目录 seed；随后补齐空间快照版本持久化与发布/回放，再把物理实体槽占用和活动占用统一为候选输入，补空间配置正式 API/命令幂等审计，把现有直连领域服务纳入统一 `/wms/crud` 或计划入口。supervisor tick/worker 已通过分配三动作、CHECK/APPLY 装拆两动作和 CHECK/RELEASE/VERIFY 释放三动作的正式调度链，显式阻挡图已接入快照和候选硬筛选。活动容量锁当前只参与装拆容量账、不消费；真实锁消费必须继续在带库存位置、完整 trace 和 checkpoint 的 `APPLY_MATERIAL_CHANGE` SAME_TX 中承接，不得由装拆或页面操作改写锁终态。

保留原图的P4-04最小fixture→P5-03推进顺序，P5-03再完成锁消费/容量与P4原子动作的组合验收；不反造必须先完整P5才能开始trace/P4-04的循环。完整TG03/TG04/TG10及INV-P6-02整合仍需后续实际证据。

## 5. 调度、版本与交付

- 实施派工先确认dirty和认领目录。宽泛wms/**不能同时授予多个执行者；material/trace/**及独立测试属于Confucius，本清单只描述后继，其他代理不写入该区域。
- core/init、公共测试基类、同一Mapper XML、POM、process运行时和根Maven是独占资源。共享文件冲突用排期处理，不假造业务依赖；新package已完成不等于根Maven可被任意执行者占用。
- 每片交实际文件/文字diff、输入摘要、命令和退出结果、隔离MySQL版本/目标、持久行对照、故障注入和并发结果。仅Mock/接口准备不能关闭持久化或平台集成；缺环境单列受阻项。
- 293回归和38模块package使用其报告的输入清单，不与旧106/38或79/56/23/12/82再相加；未来片新增代码须补其相关验证。新包LAN和真实provider结果未在本次核对，不提前填写。
- 当前core摘要为194ed2506efc278ae8f6cdc732e40e13d055782ebee5c623cab022651bcd48c9；读取时empty init为d23be8da101c1653c24dd6a793cdd2ba8d941a8d4b0a6ea6371d2ccca25d1ce9，区别于P4结构验收时252cded3e7bd86725da2b47c8c58746e1a19fb61ba8cffd146b2eeb937be2e58。verification-current同样记录该差异，init SQL语义由主与Tesla核对；不把旧结构结论套到新init。独立material DDL读取摘要为da1c99679704a909d672fe010793f2fed594431b468c350db22ff6b3eb9b2717。

## 6. 核对依据

| 源码/设计 | 本次用于确定的边界 |
|---|---|
| [material DDL](../../script/sql/iws_inventory_management_material_foundation.sql)及P1合同§5.4/§5.5 | trace三表、效果唯一键、FACT/COMPENSATION形状、可空料号、数量精度；load及occupancy与allocation是不同交付。 |
| [LoadTreeKernel](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/load/kernel/LoadTreeKernel.java)、[LoadAggregator](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/load/kernel/LoadAggregator.java) | 当前可复用纯内核，不能作为持久化或容量账证据。 |
| [EventMutationService](../../iws-modules/iws-process/src/main/java/com/innolux/iws/process/runtime/EventMutationService.java)、[LocalAction](../../iws-modules/iws-process/src/main/java/com/innolux/iws/process/runtime/LocalAction.java)、[DefinitionValidator](../../iws-modules/iws-process/src/main/java/com/innolux/iws/process/support/DefinitionValidator.java) | 已有平台SAME_TX收口入口和checkpointed校验；应复用，不能据此宣称WMS已注册或已接线。 |
| [审计实体](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/domain/WmsObjectAudit.java) | 既有对象审计可按适用合同复用；对象审计不能替代物料trace。 |
| [当前回归与构建](../verification-current-20261029/report.md)及主本轮指示 | 当前293/38接受状态、init输入变化，以及Confucius的独占写集/APPLIED决定。 |

本文件已按 2026-11-03 本期整合验收更新。新增证据绑定当前源码、最终 JAR、正式空库最新入口、隔离 MySQL、本机浏览器和 LAN 代理浏览器；未连接或写入真实业务库，未部署、未 commit/push。`INV-I-01` 已按任务图关闭，`INV-P5-05`、`INV-P6-03`、`INV-P7-05` 与 `INV-I-02` 继续保持后续扩围阻塞。完整证据索引见 [本期整改与验收摘要](../verification-current-20261103/summary.md)。
