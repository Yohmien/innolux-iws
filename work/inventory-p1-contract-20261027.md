# IWS+ 在库管理 P1 目标模型与交付合同

日期：2026-10-27（Asia/Shanghai；直接写、F0 hardening 和 P3/Catalog 限定证据增量记录于 2026-10-29）。状态：标准化新项目 P1—P7 本期合同已按 2026-11-03 整合验收执行；真实 MOVE、非空清空、现场入出库、正式物料写入和真实库部署仍为后续范围。本文用于冻结目标模型、字段与约束、API 边界、计划事件挂载和分阶段交付合同，不替代部署记录。

2026-10-28 增量只修改本文件与实施任务图，固化 `INV-S2-F0` 当前直接写范围、schema 登记锚点、直接冻结和不扩大完整 S2 验收边界的决定；未修改 SQL、Java、空库 init、集中 history，也未提交、推送、连接或写入数据库。实施前仍须重新核对当前源码、数据库基线和现场数据。

2026-10-29 增量继续只修改本文件与实施任务图，记录 resumed Harness、P3 独立 12 表和 core/init 同版结构、Catalog 菜单/只读 consumer 的限定证据，并收紧 F0 与规则发布合同。早期记录时 F0 或规则修复尚未通过；当前限定结果见下方最新前沿，不把局部切片写成父任务 `done`，也不表示已提交、部署、写真实库或完成主实测。

2026-11-03 本期最终验收：复评 `inventory-completion-review-20261103` 的 REV-01—REV-08 已按当前源码关闭。正式空库入口实测 `181` 表、`77` WMS 表、`256` 菜单；最终 JAR `4C2515415A427CF1AC1A516DA05A408F7491B8E227CBEB9DCC6D5552BD95EBC3`。全 WMS `481/481`、根 reactor `43/43` clean `test-compile`、全应用 `43/43` package、Web Vitest `264/264`、空间生命周期 `70/70` 均通过。生产规则属性来源、WMS `ProcessAccessPolicy`、后台受信执行、空间严格父链/生命周期/锁序、普通 UPDATE 关闭边界和退役状态迁移均已接入正式路径；另修复规则详情 Mapper 漏读 `attribute_bindings_json`，正式 API 现返回 `bindingSnapshotState=READY`、`executionReady=true`。普通账号在本机与 `192.168.1.21:18000` LAN 代理浏览器中均通过 13 项检查、20 次 API，错误为 0。真实 MOVE、非空拆分/收缩清空、现场入出库、正式物料父业务写入、真实库写入和部署仍未执行。以下 2026-10-29 前沿保留为历史证据边界，具体最终证据以[整改与验收摘要](C:/code/company/innolux-iws/work/verification-current-20261103/summary.md)为准。

历史测试快照：DeepSeek 当时已执行 Maven，主 Review 读取日志复核全 WMS 结果为 `105` 项、`103 PASS / 1 failure / 1 error`。旧 35 个 TestDatabase 路径错误已消除，company `27/27` 通过；跨 scope 并发 publish 用例仍 20 秒超时，主怀疑 Mapper 列端 CAST 破坏索引点查，待修；新 fence 测试因历史 schema version 登记跨用例未清而撞 `WMS_HISTORICAL-1`，原 coder 正在修 fixture。该快照不是全绿，也不等待后续结果阻塞本轮文档交付。

当前验收前沿（2026-10-29）：主已接受空间十表 core/init，以及 P4 物料目标 DDL 与 core/init 的限定结论。以下只记录当前可复核的切片，不把父任务、整套回归或目标准备写成部署完成。

| 领域 | 已通过或已接受的限定证据 | 当前边界 |
|---|---|---|
| 空间结构 | MySQL 8.0.45 隔离结果为 `core 55 / init 159 / WMS 55 / menu 242 / role-menu 133`；十表结构、verify 与空数据一致，旧 `45/149` 表结构及 seed 摘要保持一致。见[整合报告](C:/code/company/innolux-iws/work/space-core-init-20261029/report.md)。 | 只关闭空间十表 core/init 同版切片；完整 P2、S2—S4、数据承接与部署未完成。 |
| P4 物料结构 | 最新 11 表达到 `220` 列、`85` 条索引列元数据、`34` 个 CHECK，当前受影响矩阵 `24/24`；core/init 已实测并获主接受为 `core 66 / init 170 / WMS 66 / menu 242 / role-menu 133`，三路 11 表同版，旧 `55/159` 结构和数据不变。见[Spec 修复报告](C:/code/company/innolux-iws/work/material-foundation-20261029/report-spec-fixes.md)与[core/init 报告](C:/code/company/innolux-iws/work/material-core-init-20261029/report.md)。 | 只接受目标和同版结构切片；旧 `78/86` 仅为未变部分的版本化证据，不能称当前源完整 `102` 项已重跑。完整 P4、服务事务、迁移与部署未完成。 |
| 规则与装载内核 | 条件 kernel `79/0/0/0`，其 8 个文件后续仅补头注且正文未变；结果 kernel 实际 Maven `56/0/0/0`，完成时间 `2026-10-29T13:10:29+08:00`；装载 kernel 最终仍为 `23`，主已读 SUM 修复并接受纯 kernel。 | 结果 kernel 尚无生产用途注册、service 接入或发布 binding 快照；装载 kernel 未 Maven/DB。三者均为纯内存或隔离测试，不能相加成整体门禁。 |
| HTTP 认证 | SSE 认证修复的真实 MVC 媒体转换链 `12/0/0/0` 通过，覆盖缺失、失效、过期、有效连接和普通 JSON 错误。 | 尚未重新 package 或执行新 LAN；旧 `3B44BC58…` JAR 的 `12` 个 LAN 场景仍只绑定旧包，OAuth 真实 provider 未验证，整体 HTTP 未关闭。 |
| 规则 scope | 21 个交付文件及最终有效 `82/0/0/0` XML 已由主读 Guard 全类并接受：`82` 是首轮 `69` 项通过加 scopeGuard `13` 项复验，不是首轮整套直接全绿。锁后 current read、zone 精确类型/已锁父链、生命周期与 `409` 行为均已通过。 | Gauss 当前继续使用唯一根 Maven 槽执行 15 个 WMS 类加 SSE 12 的全当前回归，并准备新包；结果未出。正式产品 schema、package/LAN 与部署仍未完成。 |
| Web 与整体基线 | `244+70`、`tsc/lint/build` 旧证据绑定未变 Web 源码，可继续复用。 | 全 WMS `106` 与旧 `38` 个 reactor 模块 package 是历史整体基线；不能把后续 `79/56/23/12/82` 相加成“新整套”。P2/P3/P4/S2/S3/S4/P5 等父任务仍未完成，无 live 部署。 |

## 1. 权威输入与冻结范围

权威输入：

- [在库管理完整方案](../docs/inventory-management-design-20261026.md)
- [储位分配计划与锁定方案](../docs/inventory-allocation-rules-20261026.md)
- [SQL 初始化、旧库升级与基础规则种子计划](../docs/inventory-management-sql-upgrade-plan-20261026.md)
- [初始化配置值表](../docs/inventory-initial-configuration-20261026.md)
- [Web 与 CRUD 修订计划](../docs/inventory-management-web-crud-plan-20261026.md)
- [重建后数据库基线](../docs/inventory-management-database-baseline-20261027.md)
- [方案交接](handoff-20261027-inventory-management.md)

P1 冻结公司、仓库、物料库区、设备库区、储位、逻辑归属、规则包、物料单元、载具装载、容量、分配锁、预留、trace、业务任务屏障和升级控制的命名与字段边界；同时冻结计划事件的身份和事务合同。实际 DDL、Java/Web 实现、计划定义发布、真实设备和真实数据库执行仍在后续阶段。

冻结/解冻、真实盘点、真实入出库、真实移库和物料拆合不属于当前底座执行验收。通用合同和隔离 fixture 不能写成这些业务已接通。

`INV-S2-F0` 的 component `WMS_DIRECT_WRITE`、schema version `1` 和 release `INV-WMS-DIRECT-WRITE-1` 只标识当前应用直接写入口的 schema 登记与门禁切片，不是完整 P1、完整 S2 或业务 release。公司写入口按实际调用划分为创建、改名备注、启停（`setEnabled` 一个命令族）和管理范围替换四个类别，供应商入口为 add/update/remove/import；其他 WMS 写能力和正式业务不在本切片。

## 2. 当前源码与运行事实

2026-10-27 历史源码快照的只读核对 C:/code/company/innolux-iws：分支 main，HEAD e9361ae58d66440155b39b72affee13b79025600，工作树为空。

| 文件 | 本次 SHA-256 | 字节 |
|---|---|---:|
| script/sql/iws_wms_smt_core.sql | 98BF277B32AE16FECB3FFF4BFCFC56307118A622053499CC99CA5364CE9AE709 | 76,368 |
| script/sql/iws_init_from_empty.sql | B59424862C3D231BBC753D3127A5DF2EA85963F02D023EEDF52E3ACBFCC150E2 | 533,577 |
| script/sql/iws_init.sql | 87671BE8133358527E7C6FAC61B17E66B53FE8C8DCFD08EEDA32A9F353397DAF | 204,217 |
| script/sql/iws_plan_event_process_schema.sql | 437C63E7DEABE304A5DBBBA6DC565CA161E560A94190670FDD34034E8936A751 | 104,563 |

上述历史源码快照中的 WMS 结构（当前 dev 部署事实见下方只读复核）：

- wms_warehouse_zone 仍使用可空 warehouse_code、单层 parent_zone_id 和旧 zone_type，见 iws_wms_smt_core.sql:28-52。
- wms_storage 只挂 zone_id，无 depth_no，见 iws_wms_smt_core.sql:62-93。
- wms_container.storage_id 只有旧唯一键 uk_wms_container_storage，见 iws_wms_smt_core.sql:136-166；当前 Java 主源码未发现 WmsContainer 或容器位置写入。
- wms_inventory 仍内联生产、位置和聚合预留字段，数量为 decimal(20,6)，见 iws_wms_smt_core.sql:276-330。
- wms_inventory_flow 已有唯一 idempotency_key，见 iws_wms_smt_core.sql:339-369。
- 当前 iws-wms Java 运行时只有供应商标准表；元数据登记见 IwsCrudMetaRegistry.java:149，不能据此宣称目标 WMS 模型已实现。
- 2026-10-28 增量时，schema 升级控制五表已有 SQL 结构，公司五表及供应商当前结构也已在工作树中形成；Java 尚未接入并通过当前直接写 schema 门禁。该结构事实不证明 F0 门禁、冻结或真实库验收已完成。

计划事件结构：

- AttachTaskCommand 字段为 scheduleId、planType、businessType、businessId、taskKey、holdStart、inputHash、rootSet、commandKey，见 AttachTaskCommand.java:27-29。
- 平台以“businessType:taskKey”幂等；同任务同摘要返回原实例，异摘要拒绝，见 PlanInstanceServiceImpl.java:205-287。
- 数据库 task_key 最长 128、business_id 最长 128、business_task_key 最长 256，见 iws_plan_event_process_schema.sql:997-1000。
- 本地动作必须声明 checkpointed=true 且 transactionMode=SAME_TX，见 DefinitionValidator.java:405-420；动作、检查点、事件终态及后继激活同事务，见 EventMutationService.java:553-565。
- 能力按 key/version 精确注册，重复注册启动失败，不回退最新版，见 CapabilityRegistry.java。
- 发布前执行能力和目录校验，新发布只影响后续实例，见 PlanDefinitionServiceImpl.java:579-624。

现有授权源码：

- LoginHelper.isSuperAdmin 以固定 SUPER_ADMIN_USER_ID 判定超级管理员，见 LoginHelper.java:238-248。
- SysPermissionServiceImpl.getMenuPermission 对超级管理员返回 *:*:*；普通用户返回由角色菜单解析的权限，见 SysPermissionServiceImpl.java:64-73。
- SaPermissionImpl 优先读取登录用户内存权限，缺失时通过 PermissionService 重新解析，见 SaPermissionImpl.java:67-82。
- LoginUser 已持有 menuPermission、roles、dataScopeRoleMap 和当前 deptId，可作为功能权限与部门数据范围的输入。
- ProcessObjectAuthorizer 展示了“功能权限之上再校验 object:all 或业务对象策略，未注册策略默认拒绝”的现有模式，见 ProcessObjectAuthorizer.java:128-146。
- PlusDataPermissionHandler 已支持超级管理员旁路以及按角色 dataScope 展开部门/本人范围，但没有公司对象范围，见 PlusDataPermissionHandler.java:84-95、232-263。

因此公司管理范围不能仅靠现有 @DataPermission 自动推导；合同新增显式角色-公司授权表和 WMS 对象范围策略。库存可见范围把部门可见性作为附加行谓词，并逐行叠加现有角色数据范围，不替代其 ALL/CUSTOM/DEPT/SELF 等分支。当前主源码尚无这两项 WMS 策略实现，属于后续实现缺口而非已实现事实。

运行配置：

- application.yml:257-265 当前 process.enabled=false、process.scheduler.enabled=false。
- application-dev.yml:25 当前 snail-job.enabled=false。
- application-apk.yml:13-16 只启用 process，scheduler 仍为 false。
- ProcessJobConfiguration.java:30-33、78-93 只有在 process、process.scheduler、snail-job 三开关全为 true 时才装配监督和 QUEUE worker。

集中规约指向的 C:/code/company/innolux-iws/.agents/skills/iws-plan-event-project/SKILL.md 当前不存在；本轮读取了方案工作区同版 Skill 及引用。这属于实施入口可追溯性缺口，不能宣称产品工作区已自动加载。

历史基线：2026-10-27 记录 MySQL 8.0.45、124 表、WMS 20 表为空，APK/process 有运行事实，当时未完成本轮复核。当前事实：主 Review 已接受[真实旧库只读报告](C:/code/company/innolux-iws/work/inventory-real-baseline-readonly-20261029/report.md)：MCP 首查 SHOW GRANTS 确认专用只读账号；dev `3306/innolux-iws/8.0.45` 当前 124 张基础表中，20 张 WMS 表逐表精确 COUNT(*) 均为 0，检查的新 23 个对象及 S1 列未部署。当前 dev 空源演练无需编造 B04/B05 行级映射，生产 B04/B05、完整 S2/S3/S4 和 cutover 仍未关闭；APK 3 条，process 3 COMPLETED + 1 RUNNING，均不得清理重置。升级前仍须重新确认目标库、权限、结构指纹、活动实例和写冻结。

## 3. 独立评审项处理

| 评审项 | 本文处理 |
|---|---|
| 深位不同储位同通道锁域、锁序和事务复核 | 第 7 节给出通道级持久锁、固定锁序和锁内版本复核方案。 |
| 装拆导致顶层槽数变化时容量同事务 | 第 8 节要求由根节点与占用明细重算，并在 APPLY_LOAD_CHANGE 单事务提交。 |
| 非空空间调整需要真实移库链而 P5 仅 fixture | 列为待主代理决定的 D02，非空调整在功能上保持禁用，除非范围被明确扩展。 |
| 物料正式写入口缺已注册父业务 | 列为待主代理决定的 D01；formal attach 必须校验父业务和允许挂载关系。 |
| 升级源态、中间态、目标态重入及 DDL 后检查点恢复 | 第 11.1 节冻结状态机；DDL 以元数据比对恢复，不承诺事务回滚。 |
| 旧容器唯一约束、旧列封存与能力开放顺序 | 第 11.2 节冻结为先回填和新权威切换，再删唯一索引、封存旧列，最后开放多根能力。 |
| 公司启用与生命周期分离 | 公司增加独立 enabled，默认 false；启停是受控本地命令，不改变 lifecycle_status。 |
| 可见范围父键及空集合语义 | 增加 visibility_key/parent_visibility_key；INHERIT、VALUE 空集合、CLEAR 和根权限分别定义。 |
| seed 唯一性与摘要冲突 | seed 台账唯一键改为 seed_code/seed_version，读取既有行后比较 canonical_digest。 |
| 占用事务与多根多维明细键 | 增加占用事务头；effectKey 只属于事务头，明细使用独立 detail_key 和活动键。 |
| 通道锁成功消费与释放证据 | LOCKED 只能终结为 CONSUMED 或 RELEASED；CONSUMED 是成功终态，不进入释放链。 |
| /wms/crud 的受控分流 | /wms/crud/{tableCode} 按登记处理器分流原子 CRUD、领域命令或计划，和原 Web 合同一致。 |
| 公司管理范围与库存可见范围 | 两者独立；管理范围使用 Sa-Token 功能权限、超级管理员旁路和显式角色-公司授权，库存可见范围使用对象可见部门和现有部门数据权限。 |
| 储位五项文本地址、NULL 同值与退役占组合 | 第 5.1、5.2、6.1 节冻结可空文本、精确比较、无碰撞唯一键、同仓跨设备区一致和旧库冲突阻断。 |
| 同仓 `warehouse_id` 与父链一致 | 第 5.2、6.1、11.3 节要求储位保存非空 `warehouse_id`，升级和空间变更同事务核对设备库区父链。 |
| 通道配置/成员变更与分配同锁协议 | 第 7 节要求普通储位加入/移出/跨通道锁旧新通道和储位；配置、成员、访问顺序和阻挡变更取得通道锁，活动锁或 UNKNOWN 时阻断；分配复核成员并处理锁域变化。 |
| 地址生成列与字节级 CHECK | 第 5.2、6.1、11.3 节冻结比较值列类型/排序规则/NOT NULL/表达式/STORED、元数据校验和 ASCII TRIM 字节检查。 |
| 父区跨仓与子储位父链锁 | 第 7、11.3 节要求两者共锁仓库/库区父链，跨仓锁旧新两侧，并纳入并发验收。 |
| QUEUE 默认与后台驱动冲突 | 第 10.1 节要求三开关门禁，不静默回退 IMMEDIATE；业务范围选择列为 D03。 |
| 当前直接写 schema 门禁与冻结锚点 | 第 4、5.7、11.0、14 节冻结 F0 可写集、固定 manifest、共享/排他锚点、直接冻结点和安全解除边界。 |
| 目录 SQL 限定切片与 Web 动态目录 | 目录 SQL 两处 P2 已有主 Review 证据；第 9.3、14 节只允许关闭限定 SQL 切片，动态目录消费和完整 TG08 仍需独立验收。 |
| F0 hardening 限定验收 | 主 Review 已读 report/code/registerCanonical，限定 CODE/SQL/local tests 通过，fence fixture 已修、17 项通过；fingerprint `1b236346b990fbf9410aeea49bb323f174112e1a44c133998baccb2ddac06909`。旧固定 JAR 的 12 个真实 LAN 场景通过；当前 SSE 修复另有 MVC 12 项，但尚未新 package/LAN。完整 F0/TG13/S2、外部 writer 和 B04/B05 未关闭。 |
| 规则发布限定验收与余项 | 主 Review 接受 3 个 Mapper 去除列端 CAST（保留参数 CAST BINARY）及规则 S/X 锁序修复，规则 21 项属于历史整体门禁。Scope Guard 的 21 文件及最终有效 `82/0/0/0` 已获主接受，`82=69` 首轮通过加 scopeGuard `13` 复验；锁后 current read、精确 zone 类型/已锁父链和 `lifecycle_status` 均在专项覆盖。正式 schema 尚未补列，Gauss 当前 15 个 WMS 类加 SSE 12 全当前回归待出结果。 |
| P3、Catalog 与 resumed Harness 限定证据 | 第 14 节分别绑定 P3 独立 12 表、core/init、Catalog 菜单、只读 consumer、S3 SQL 和 Harness todo 的范围；任一局部通过都不关闭 P3、S2 或 TG08 父任务。 |
| 六 SQL 精确 metadata 限定验收 | 主 Review 已接受[六 SQL 精确 metadata 修复报告](C:/code/company/innolux-iws/work/sql-metadata-exact-20261029/report.md)（SHA-256 `71465397782884bf3eef57d7e5b4bdf1f90341cd2fc1b263001c5d11f5e87f7c`）：实际 metadata 使用 JSON canonical/NULL-safe binary，修复 default 大小写及 INDEX_COMMENT 比较；target DDL 不变。MySQL 8.0.45 的 22 项定向和重新执行的 74 项 control/S1 回归通过；74 不是六份脚本所有行为的全量矩阵。控制五表目标指纹为 `5e8161d1e6a4182446624443de3992436b0a202faf8fe905c3cf7f4d901a905c`，与 F0 manifest `1b236346b990fbf9410aeea49bb323f174112e1a44c133998baccb2ddac06909` 分属不同合同。22/74 绑定报告列明的六份 SQL 哈希和执行时 runner `94e4fb6b…`；datadir 已可恢复迁到 Temp，当前 runner `e35a6040…` 的路径 smoke 未执行 SQL，未冒称已用新 runner 重跑 22/74。不关闭完整 P1 升级或 TG05。 |
| 真实 dev 旧库只读限定验收 | 主 Review 已接受[真实旧库只读报告](C:/code/company/innolux-iws/work/inventory-real-baseline-readonly-20261029/report.md)：MCP 首查 SHOW GRANTS 确认专用只读账号；dev `3306/innolux-iws/8.0.45` 当前 124 张基础表中，20 张 WMS 表逐表精确 COUNT(*) 均为 0，检查的新 23 个对象及 S1 列未部署。当前 dev 空源演练无需编造 B04/B05 行级映射，生产 B04/B05、完整 S2/S3/S4 和 cutover 仍未关闭；APK 3 条，process 3 COMPLETED + 1 RUNNING，均不得清理重置。只关闭本次只读核验，不关闭生产业务映射或完整 S2—S4。 |
| 规则结果内核限定切片 | `RuleResultKernelTest` 实际 Maven 为 `56/0/0/0`，完成于 `2026-10-29T13:10:29+08:00`；5 个结果 Java 文件与 ready 哈希一致，已验条件 8 文件未变。见[结果内核说明](C:/code/company/innolux-iws/work/rule-result-20261029/ready.md)。尚无生产用途注册、service/publication 接入或发布 binding 快照，不把纯内核通过写成完整 P3。 |
| P4 material 目标 DDL 与 core/init | 主 Review 已接受最新 11 表 `220` 列、`85` 条索引列元数据、`34` 个 CHECK；core/init 实测 `66/170/66/242/133`，三路 11 表同版，旧 `55/159` 结构和数据不变。旧 `78/86` 只复用未变部分，不能合并为当前源完整 `102` 项重跑；完整 P4、服务事务和部署未完成。 |
| P4 load 内核与 P3 scope 限定切片 | JDK 21 隔离装载内核最终 `23/0/0`，主已接受 SUM 用例替换旧 overflow 的纯 kernel 修复；单叶仍 `20,6`，整体 SUM 精确 `BigDecimal`。Scope Guard 21 文件及最终有效 `82/0/0/0` 已获主接受，其中 `82=69+13`；两者都未接正式服务、持久化或部署。Gauss 的 15 个 WMS 类加 SSE 12 当前回归及新包另在途。 |
| SSE 认证响应限定切片 | `SseAuthenticationMvcTest` 使用真实 MVC、异常 advice 与消息转换链，`12/0/0/0`、`2026-10-29T13:04:47+08:00` 通过。见[SSE 修复说明](C:/code/company/innolux-iws/work/sse-auth-fix-20261029/ready.md)。未 package、未跑新 LAN；旧 `3B44BC58…` JAR 的 12 个 LAN 场景继续只绑定旧包，真实 OAuth provider 未验。 |

## 4. 已冻结技术决定

| 编号 | 决定 |
|---|---|
| C01 | 新业务对象使用不可变 bigint 内部 ID；编码、名称和别名不能充当空间外键。 |
| C02 | 新状态使用稳定英文码，不再用新增 tinyint 混合表达生命周期、发布、占用、锁和设备状态。 |
| C03 | 物理储位是容量和占用唯一权威；逻辑归属只解释规则和历史，不复制物理容量。 |
| C04 | 新数量继续以 decimal(20,6) 为上限；请求量大于 0，实存可为 0，delta 可负，超精度拒绝。 |
| C05 | 混放默认关闭；混放键默认 materialCode，未知键、空值通配和不可比较值拒绝；顶层槽默认为 1。 |
| C06 | 深位按通道而不是单储位加锁，见第 7 节。 |
| C07 | 顶层槽数由根节点和占用明细推导，禁止可漂移的单槽计数器，见第 8 节。 |
| C08 | WMS taskKey 不重复携带 businessType；平台 businessType:taskKey 才是完整幂等身份。 |
| C09 | 正式 QUEUE 任务在三运行开关齐备前拒绝挂载，不自动改为 IMMEDIATE。 |
| C10 | 旧容器位置列和唯一索引保留到新装载模型回填、应用切换和并发验证后；目标结构不保留“一储位一容器”唯一约束，多根共享储位由容量、混放和独占规则判定。 |
| C11 | MySQL DDL 提交不可回滚，升级恢复以 INFORMATION_SCHEMA 比对和阶段检查点为准。 |
| C12 | 冻结/解冻、真实盘点、入出库、移库和物料拆合仍不属于当前底座执行验收。 |
| C13 | 公司 enabled 与 lifecycle_status 分离；enabled 默认 false，只通过受控启停命令改变。 |
| C14 | 可见范围保存显式 parent_visibility_key；INHERIT 读父有效集合，VALUE 空集合和 CLEAR 都表示显式无可见部门，不得回退。 |
| C15 | 占用 effectKey 绑定一次事务头；多根、多维和多次明细更新使用独立 detail_key，不重复占用 effectKey。 |
| C16 | /wms/crud/{tableCode} 是受控统一写入口，可按登记处理器委托原子 CRUD、领域命令或计划；前端不串联第二命令。 |
| C17 | 公司管理范围与库存可见范围分离；管理权限不能自动产生库存可见，库存可见也不能产生管理权限。 |
| C18 | 首家公司只允许现有超级管理员引导；后续公司访问必须通过显式角色-公司管理授权或显式全公司管理权限，不自动把创建者加入范围。 |
| C19 | 公司管理授权按完整 roleIds 集合替换，空集撤销；scope_revision 独立 CAS，提交即生效。库存权限必须在逐行 SQL 中保留现有 DataPermission 的 SELF 等全部分支。 |
| C20 | 储位地址为巷道、排、层、列、深五个独立可空文本属性，均可非数字；同一仓库五项组合唯一，NULL 按相等，五项全空同仓最多一条，退役行继续占组合。 |
| C21 | 地址比较使用精确语义：只清理首尾 ASCII 空格，空串转 NULL，不转数值、不折叠大小写/全半角/Unicode、不补前导零；唯一键用“是否为空标志 + 精确值”，不得只依赖摘要或 MySQL NULL 唯一行为。 |
| C22 | 多深位不设数量上限，深度只存于储位地址；访问顺序由独立 `access_order` 配置，阻挡和通道成员显式维护，候选与锁不得由地址文本排序推断。 |
| C23 | `storage_id` 是稳定物理锁对象；`wms_storage.warehouse_id` 非空且与当前父链仓库同事务一致；通道配置/成员/阻挡变更与分配共用通道锁协议，存在活动锁或 UNKNOWN 时阻断。 |
| C24 | 普通储位加入、移出或跨通道变更必须锁旧通道、新通道和储位，检查两侧活动分配锁及 UNKNOWN；分配取得储位锁后复核成员归属，锁域变化时释放并按全序重试。 |
| C25 | 五个地址比较值生成列使用 `VARCHAR(64) utf8mb4_0900_bin`，按 `AS (...) STORED NOT NULL` 定义；标志和值生成表达式、STORED 属性由元数据校验；每列用字节级 ASCII TRIM 检查禁止空串和首尾空格。 |
| C26 | 库区跨仓变更与子储位新增/改挂/地址更新共锁仓库/库区父链，跨仓锁旧新两侧；取得父链锁后再写 `warehouse_id`。 |
| C27 | `INV-S2-F0` 的公司写范围按实际调用分为四个类别：创建、改名备注、启停（`setEnabled` 一个命令族）和管理范围替换；供应商范围为 add/update/remove/import。其他 WMS 写命令和正式业务不借用 F0 证据。 |
| C28 | F0 登记身份固定为 component `WMS_DIRECT_WRITE`、schema version `1`、release `INV-WMS-DIRECT-WRITE-1`；发布标识只描述 DIRECT_WRITE 切片。 |
| C29 | 每个受控实际写事务只接受该身份下唯一、精确 `VERIFIED` 的登记；登记缺失、控制表缺失、源/目标指纹不匹配、签名冲突、状态不支持或存在未安全完成的 run 均拒绝写并返回 `SCHEMA_STATE_UNSUPPORTED`。查询、元数据和历史读取继续。 |
| C30 | 每个实际写事务在办理业务写前，最先对登记锚点执行 `FOR SHARE` 并持锁到提交；两笔均通过 READY 门禁的写事务可以共享，不以应用级全局锁串行。冻结使用同一锚点 `FOR UPDATE`。 |
| C31 | 已有登记锚点后，所有协议控制变更必须先取得该锚点排他锁；不得先写 run、checkpoint、版本或冻结状态再补锁。 |
| C32 | 直接冻结在写者排空后，于同一事务写 `wms_upgrade_run` 与 `S2_DIRECT_WRITE_FENCE/VERIFIED` checkpoint，提交才形成已知冻结点。任何未安全完成的 WMS run 均保守阻断直接写，不因 `schema_version` 或 `release_id` 不同于当前 F0 而漏检；多个未完成 run 判为冲突。 |
| C33 | run 生成后即存在 token，但 token 单独不能证明写者已排空；应用不得按 token 存在、超时、页面关闭或 UNKNOWN 自动放行。 |
| C34 | 失败或 UNKNOWN 不自动解除冻结。只有同 token、同 run、同一锚点排他锁及可核对验证证据都具备时才可设计安全解除；当前安全解除未交付，冻结保持关闭。 |
| C35 | 不扩展控制表 DDL 状态枚举。F0 使用现有 `PENDING/APPLIED/VERIFIED/FAILED`、run/checkpoint 现有状态和 `S2_DIRECT_WRITE_FENCE` 固定键，以数据关系表达冻结。 |
| C36 | 公司 schema 门禁在业务受理前拒绝时不创建 attempt；其他命令失败的审计、幂等和 UNKNOWN 原语义保留。供应商普通 CUD 补事务边界，使门禁、业务写及保留的审计/结果语义可同事务。 |
| C37 | 失败公司命令按同一 commandKey 重放时保留首次持久化的原始业务错误，不新增固定 `COMMAND_ALREADY_FAILED` 契约；成功命令同键同输入仍幂等，同键异输入返回 `INPUT_HASH_CONFLICT`。 |
| C38 | 实际 metadata 按 NULL-safe 字节比较或无歧义 `JSON_ARRAY` canonical 核对，SQL NULL 与字面 `'<NULL>'`、空串及不同大小写不混同；不要求区分 MySQL 已归一、metadata 无法观测的隐式 NULL 与显式 DEFAULT NULL。 |
| C39 | 持久冻结点已经成立后，后续失败或 UNKNOWN 不自动解除。freeze 提交结果未知时必须回读 run/checkpoint，不凭客户端异常判定未生效，也不继续 S3；明确整事务回滚且无冻结事实时不宣称排空成功，这不是自动解冻。不新增持久 blocking 状态或可靠投递承诺。 |
| C40 | 规则 publish-only 不隐式要求 query。主已接受 scopeGuard 补强：五类目标按当前 DB 管理授权与 physical 链校验，`storageIds` 允许空集，授权、目标/父链和纳入快照的 storage 行持 S 锁到发布提交；公司 `enabled=false` 不阻止配置发布。当前代码实现中，若产品 schema 缺 `wms_warehouse_zone.zone_kind` 或 `wms_storage.lifecycle_status` 必须 fail-closed。单独 fixture 缺列测试不证明产品 schema 已部署；新版本未验收前，旧等值检查不关闭目标对象、管理范围或 storage 快照。同一目标的 retire 与 publish 仍须串行化。 |
| C41 | 容量版本以 `active_flag` 和 `STORED NOT NULL` 生成键区分活动/历史行。活动键按 `storage_id + dimension_key` 唯一，历史键按 `capacity_id` 唯一；`capacity_id` 由服务显式分配，不使用 `AUTO_INCREMENT`。当前活动唯一域为 `(storage_id, dimension_key)`，同维度 `(storage_id, dimension_key, capacity_version)` 唯一。单位变化必须先关闭旧活动再创建新版本，不允许只改单位绕过活动唯一；不预置容量 seed。 |
| C42 | 空间当前版本的唯一权威是 `wms_location_effective`。仓库、库区、储位实体表都不保存 `current_location_version_id`，也不做双写、双读或视图投影；实体表只保留版本化所需的身份、状态和 CAS 列。 |
| C43 | 仓库新增 `enabled TINYINT(1) NOT NULL DEFAULT 0` 及独立 CHECK，与 `lifecycle_status` 分离；zone 新增 `revision` 参与版本写入 CAS；storage 使用既有 `version` 作为唯一 CAS 令牌，API 的 revision 显式映射该列，不新增第二令牌。新建 zone 默认停用，新建 storage 默认禁用，不能沿旧默认启用。 |
| C44 | 规则发布版本必须携带服务端生成的不可变属性绑定快照：`rule_version.attribute_bindings_json`，schema `iws.rule.attribute-bindings/1`，DRAFT 可为 NULL，ACTIVE 必须完整；版本摘要升级为 v2 并覆盖快照。快照只取已锁目录合同的 11 个字段，覆盖 condition 与 SORT 引用并集；未知来源、用途或合同版本 fail-closed，不允许 latest fallback，也不允许用最新目录重建历史版本。锁序固定为 activation version → definition → 目录精确键排序 → ACTIVE UPDATE 与快照同事务。首期只注册 `STORAGE` 用途，`ALLOCATION_CANDIDATE_FILTER/SORT` 仅保留 v1 代码。操作符先校验非法/重复，再 canonical 排序，不用去重修复非法目录。 |
| C45 | 内部物料 trace 追加使用 `Propagation.MANDATORY`，trace 头、item、edge 与调用者业务事实同事务提交或回滚；只允许 insert/select，不提供 update/delete。`effectKey` 固定 `^WMS:[0-9a-f]{64}$`，同键同摘要返回已提交事实，异摘要返回 `MATERIAL_TRACE_EFFECT_KEY_CONFLICT`；成功事实的 `effect_result` 固定 `APPLIED`，补偿只追加反向 trace。单叶数量严格 `decimal(20,6)`，原始 `BigDecimal` 不截断、不舍入。 |

## 5. 目标数据模型

### 5.1 通用约定

新表使用 InnoDB、utf8mb4；稳定编码使用 binary 语义。默认不建物理外键，采用逻辑关联、唯一键、索引和事务校验，与当前 WMS 高并发模型一致。新业务表包含 create_dept/create_by/create_time/update_by/update_time；追加事实表增加 recorded_at。瞬时字段用 datetime(3)，日期字段用 date。业务时区和周编号由规则版本冻结。

储位五项地址列使用 `utf8mb4_0900_bin`（NO PAD），按精确码点比较并区分大小写；写入、判重、列表筛选和精确定位共用服务端同一规范化。任何唯一键都必须按列长和字符集核算字节，超 InnoDB 单索引 3072 字节即阻断。

### 5.2 空间、访问通道与可见范围

| 表 | 冻结字段与约束 |
|---|---|
| wms_company | company_id PK、company_code UNIQUE、company_name、enabled NOT NULL DEFAULT 0、lifecycle_status、revision、审计。enabled 表示可被后续业务选择，生命周期表示身份是否存在；二者不得互相推导。 |
| wms_warehouse_type | warehouse_type_code PK、名称、status、contract_version、config_schema_json、revision。 |
| wms_warehouse_type_config | config_version_id PK、warehouse_type_code、version_no、config_json、canonical_digest、status；唯一 type/version。FLOOR 必须含 floorNo/floorLabel。 |
| wms_warehouse | warehouse_id PK、company_id NOT NULL、warehouse_code、名称、warehouse_type_code、type_config_version_id、enabled TINYINT(1) NOT NULL DEFAULT 0、lifecycle_status、revision；唯一 company/warehouse_code。当前配置版本只在 `wms_location_effective`，本表不设第二指针。 |
| wms_warehouse_zone（调整） | 保留 zone_id/zone_code；增加 warehouse_id NOT NULL、zone_kind=MATERIAL/DEVICE、lifecycle_status、revision。当前版本只在 `wms_location_effective`，本表不设第二指针。MATERIAL 的 parent_zone_id 为空；DEVICE 必须指向同仓 MATERIAL。 |
| wms_storage（调整） | 保留 `storage_id/storage_code/zone_id` 与旧 `row_no/col_no/level_no`（切换后只读）；增加 `warehouse_id NOT NULL`、`aisle_text/row_text/level_text/column_text/depth_text` 五个 `VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL`、`allow_multi_material`、`mixed_material_key`、`capacity_mode`、`lifecycle_status`、`version`。当前版本只在 `wms_location_effective`，本表不设第二指针；`version` 仅作行级 CAS。`zone_id` 必须是 DEVICE，`warehouse_id` 必须等于该设备库区所属仓库并在同一事务维护。 |
| wms_access_channel | channel_id PK、device_zone_id、channel_code、access_mode、status、revision；唯一 device_zone_id/channel_code。 |
| wms_storage_access_member | member_id PK、channel_id、storage_id UNIQUE、access_order、revision；唯一 channel/storage 和 channel/access_order。深度只在 `wms_storage.depth_text` 保存，成员表不复制深度，`access_order` 独立显式维护；一个储位只能属一个当前通道。 |
| wms_storage_access_edge | blocker_storage_id、blocked_storage_id、revision；唯一 blocker/blocked；同通道、无自环，应用事务拒绝环。 |
| wms_location_version | location_version_id PK、object_type、object_id、version_no、parent_location_version_id、config_json、canonical_digest、status；唯一 object/version。 |
| wms_location_effective | object_type、object_id UNIQUE、location_version_id、revision；CAS 更新。 |
| wms_object_visibility | visibility_key PK、parent_visibility_key、object_type、object_id UNIQUE、mode=INHERIT/VALUE/CLEAR、revision、updated_at。公司 parent 为空；仓库、库区、储位必须指向当前树父对象的 visibility_key。 |
| wms_object_visibility_department | visibility_key、dept_id；唯一 visibility_key/dept_id。没有子行表示该 VALUE 集合为空，不表示全部。 |
| wms_company_management_scope | company_id PK、scope_revision、updated_by/at、审计。每个公司恰一行，scope_revision 独立于公司 revision。 |
| wms_company_management_grant | grant_id PK、company_id、role_id、created_by/at；唯一 company_id/role_id。明细按目标集合全量替换；空集合即删除该公司全部明细。只授权公司管理对象范围，不授予菜单功能权限，也不产生库存可见范围。 |
| wms_storage_capacity | capacity_id PK（服务显式分配）、storage_id、dimension_key `VARCHAR(64) utf8mb4_0900_bin`、capacity_value decimal(38,12)、unit、scale 0..12、capacity_version 正数、active_flag `TINYINT(1) NOT NULL`；active_capacity_key `VARCHAR(531) utf8mb4_0900_bin AS (...) STORED NOT NULL` UNIQUE。活动行键为 `A:<16位storage_id十六进制>:<dimension_key UTF-8十六进制>`，历史行键为 `H:<16位capacity_id十六进制>`，因此活动唯一域是 `(storage_id, dimension_key)`、历史按 `capacity_id` 区分；另有 `UNIQUE(storage_id, dimension_key, capacity_version)`。531 覆盖 64 个四字节码点经 HEX 后的 512 字符加 19 字符前缀，索引上界 2124 字节。`capacity_value = TRUNCATE(capacity_value, scale)` 强制声明精度，拒绝超精度而不静默截断。单位变化关闭旧活动并升版，不 seed；`TOP_LEVEL_LOAD=1` 仅为未来配置默认语义，不插入容量行。 |

空间当前配置版本仅由 `wms_location_effective` 唯一指向，仓库、库区、储位实体表不保存 `current_location_version_id`，不双写、不双读、不做视图投影；API 的 revision 由各实体状态列显式映射。仓库 `enabled` 表示可被后续业务选择，与 `lifecycle_status` 分离；新建库区与储位默认停用，不能沿旧默认启用。

当前父关系只存在于实体当前父列；历史版本通过 wms_location_version 引用。旧 zone_type、warehouse_code、wms_storage.status/lock_status 在切换后只保留旧数据，不再作为新业务权威。

空间 foundation 10 表当前目标 DDL 绑定的隔离证据为：MySQL 8.0.45 下 10 表、109 列、51 条索引元数据、24 个 enforced CHECK；88 项执行断言全部通过，其中包含 27 项行为矩阵和 10 项容量边界。首次执行十表均为空，第二/三次重跑与 verify 的结构完整一致；带业务行重跑前后快照不变。证据见[主报告](C:/code/company/innolux-iws/work/space-foundation-20261029/report.md)与[原始通过轮次](C:/code/company/innolux-iws/work/space-foundation-20261029/capacity-fix-01/run02/results.json)。该结论只接受目标 DDL 结构，业务跨表同通道/无环、物理父链、visibility 继承、version CAS、服务分配与单位切换事务仍由应用层负责；没有写入真实 dev，不能关闭 P2/S2。10 表替换或覆盖其他源表的计数时，不同版本的总表数分别保留，不互相抹除。

`wms_storage` 的五项地址唯一键由 `warehouse_id`、五个非空 `*_is_null` 生成标志（`TINYINT AS (value IS NULL) STORED NOT NULL`）和五个 `VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin AS (IFNULL(*_text,'')) STORED NOT NULL` 生成值列组成，覆盖全部生命周期行。`(标志=1, 值='')` 只表示 NULL，`(标志=0, 值)` 表示非空文本，因此无碰撞；键内每列均非空，MySQL 的 NULL 唯一语义不参与；不建摘要键。元数据校验必须逐列核对类型、字符集、排序规则、空值、生成表达式和 STORED 属性。建议五个 `VARCHAR(64)` 时索引约 `8 + 5 × (1 + 64 × 4) = 1293` 字节，低于 3072；键为 11 列，低于单索引 16 列上限；改列长须重算。旧数值列 `row_no/col_no/level_no` 不进入新唯一键。

可见范围解析固定如下：

- 公司只能使用 VALUE，不能 INHERIT 或 CLEAR；公司没有 VALUE 行时有效范围为默认拒绝。
- 仓库、物料库区、设备库区和储位可使用 INHERIT、VALUE、CLEAR，但 parent_visibility_key 必须指向当前物理树父对象，缺失父键或跨树父键拒绝。
- INHERIT 读取父对象的有效部门集合及其来源 visibility_key/revision，不读取父对象原始局部值；父 revision 变化立即影响子级查询。
- VALUE 取自身部门子表；VALUE 没有任何子行时有效集合为空，不回退父级，也不表示全部允许。
- CLEAR 有效集合为空且明确阻断继承；根公司禁止 CLEAR。VALUE 空和 CLEAR 的有效集合可以相同，但审计、权限和展示原因必须区分。
- 公司根 VALUE 只能由具备 inventory:company:visibility 且通过公司对象范围校验的管理员设置；空集合也必须显式提交并记录原因。子级变更使用 inventory:space:visibility，并同时校验父、目标对象和部门管理范围。
- 查询返回 localMode、localDepartments、effectiveDepartments、sourceVisibilityKey、sourceRevision；前端隐藏权限不能替代后端解析。

公司最小闭环不开放可见范围变更；上述权限只进入后续空间配置阶段。

### 5.3 规则、逻辑归属与物料

| 表 | 冻结字段与约束 |
|---|---|
| wms_attribute_catalog | attribute_key PK、source_type、value_type、cardinality、operators_json、unit、scale、groupable、status、contract_version、revision。 |
| wms_rule_definition | rule_definition_id PK、rule_key UNIQUE、purpose、name、scope_type、status、current_version_id、revision。 |
| wms_rule_version | rule_version_id PK、rule_definition_id、version_no、condition_json、result_json、attribute_bindings_json（服务端写入，DRAFT 可 NULL）、canonical_digest、status、发布时间/人；唯一 definition/version；ACTIVE 后不可原位修改。 |
| wms_rule_package_version | package_version_id PK、package_code、version_no、canonical_digest、status；唯一 code/version。 |
| wms_rule_package_member | package_version_id、purpose、rule_version_id；唯一 package/purpose。 |
| wms_rule_effective_pointer | scope_type、scope_id UNIQUE、package_version_id、revision；发布事务锁定并 CAS。 |
| wms_rule_seed_ledger | seed_code、seed_version、canonical_digest、status、applied_at；唯一 key 为 seed_code/seed_version，digest 不进入唯一键。插入前按唯一键读取；不存在则新增，摘要相同则跳过，摘要不同立即阻断且不覆盖。 |
| wms_logical_scope | logical_scope_id PK、scope_code UNIQUE、名称、lifecycle_status、current_version_id、revision。 |
| wms_logical_scope_version | scope_version_id PK、logical_scope_id、version_no、rule_package_version_id、space_snapshot_digest、status；唯一 scope/version。 |
| wms_logical_scope_storage | scope_version_id、storage_id；唯一组合。多个逻辑版本可引用同一 storage_id。 |
| wms_material_logical_assignment | inventory_id UNIQUE、scope_version_id、assignment_revision、source_business_type、source_business_id、revision。 |
| wms_logical_assignment_history | history_id PK、inventory_id、前后 scope version、task_key/instance_id、effect_key UNIQUE、人员/部门快照、发生/记录时间。 |
| wms_production_info | production_info_id PK、inventory_id UNIQUE、料号/类别、生产日期、批次、供应商、客户、尺寸、BOM、最终成品、解析版本。 |
| wms_material_attribute_value | production_info_id、attribute_key、seq_no、value_text/value_number/value_date/value_datetime、unit、source_ref；唯一 info/key/seq。 |
| wms_inventory（调整） | 保留 inventory_id、stock_unit_code、material_code、qty、unit、label_snapshot 及旧业务键；增加 production_info_id、load_node_id、rule_package_version_id、logical_assignment_id。旧生产/位置/预留列在切换后封存。 |

条件合同只冻结纯 typed condition，不把任意 `result_json` 宣称为 typed。文档信封为 `{"schema":"iws.rule.condition/1","root":...}`，只允许 `schema/root`。根节点可以是任意条件节点；仅最终求值为 `TRUE` 才命中，`FALSE` 与 `UNKNOWN` 都不选择、锁定或放行。

节点及字段集合闭合：

- 复合节点 `AND/OR` 只含 `type/children`，`children` 至少 2 项；`NOT` 只含 `type/child`，恰有 1 个条件节点。
- 标量节点 `SCALAR_COMPARE` 只含 `type/attribute/operator/value`，目录基数必须为 `SCALAR`；`IS_NULL` 只含 `type/attribute`，不得携带 `value`。
- 集合节点 `ANY/ALL` 只含 `type/attribute/operator/value`，目录基数必须为 `SET`，`value` 是与集合元素同型的单个非 null 标量，只执行一次比较。`ANY/ALL` 不是 `IN` 别名。
- `IN` 只用于 `SCALAR_COMPARE` 的标量 membership，`value` 是 1..32 个与属性同型且非 null 的数组；它不是 `ANY` 的别名。
- 任何节点出现其他字段、客户端自报 `sourceType/valueType/cardinality/unit/scale/contractVersion`、列名、SQL 或脚本入口均拒绝。允许同一属性重复出现，以总节点限额约束，不为去重改写语义。

```json
{
  "schema": "iws.rule.condition/1",
  "root": {
    "type": "AND",
    "children": [
      {"type": "SCALAR_COMPARE", "attribute": "materialNo", "operator": "EQ", "value": "MAT-001"},
      {"type": "NOT", "child": {"type": "IS_NULL", "attribute": "productionDate"}}
    ]
  }
}
```

类型矩阵封闭为：

| 类型 | 字面量与比较 |
| --- | --- |
| `STRING` | JSON string；按 Unicode 码点精确比较，区分大小写、空白与组合形式，不 trim、不做 Unicode 归一化。 |
| `DECIMAL` | JSON string 定点十进制；按 `precision=20`、`scale=0..6` 校验，先检查原始小数位再处理末零，不舍入或静默裁零；比较时按数值，单位必须一致。 |
| `INTEGER` | JSON string；signed 64 位整数，禁止小数、指数、空白和前导零，精确比较。 |
| `BOOLEAN` | JSON boolean；只接受 `true/false`。 |
| `DATE` | 严格 ISO-8601 `yyyy-MM-dd` string。 |
| `DATE_TIME` | 带 offset 的 ISO-8601 string；转同一瞬时比较，不猜业务时区。 |

目录绑定使用 ACTIVE 行，并由服务端提供 `source_type/value_type/cardinality/operators/unit/scale/contractVersion/revision`；操作符必须在目录允许列表，单位不做隐式换算。事实状态严格区分 `ABSENT`（属性缺失）、`NULL`（属性存在且显式 null）与 `PRESENT`。`IS_NULL` 只对显式 `NULL` 返回 `TRUE`；`ABSENT`、`NULL` 的普通比较均为 `UNKNOWN`。空、`NULL` 或 `ABSENT` 的 SET 使 `ANY/ALL` 返回 `UNKNOWN`，集合 null 元素的比较为 `UNKNOWN`；空 SET 不采用 vacuous truth。`AND/OR/NOT` 使用 Kleene 三值逻辑。

发布绑定快照固定在 `wms_rule_version.attribute_bindings_json`，由服务端生成，客户端不得提交或覆盖。文档 schema 为 `{"schema":"iws.rule.attribute-bindings/1","bindings":[...]}`，每项只含目录合同的 11 个字段：`attributeKey/sourceType/valueType/cardinality/operators/unit/scale/groupable/status/contractVersion/revision`，不含创建人、时间等审计字段；覆盖 `condition_json` 与结果 SORT 引用的并集，同一属性只保留一条。DRAFT 阶段允许 NULL，ACTIVE 必须完整且目录行均处于允许状态；旧 NULL 版本只读，不得视为执行就绪。发布摘要升级为 v2 并覆盖该快照，历史版本不得用最新目录重新生成。锁序为 activation version → definition → 目录精确键排序 → ACTIVE UPDATE 与快照写入同事务；未知属性、来源、用途或合同版本一律 fail-closed，不做 latest fallback。首期只注册 `STORAGE` 用途，`ALLOCATION_CANDIDATE_FILTER/SORT` 仅保留 v1 代码，不扩充多 scope registry。

AST 限额独立配置，默认 `maxDepth=16`、`maxNodes=256`、`maxChildren=32`、`maxStringCodePoints=4096`，不复用装载树深度或作业批量常量。限定子切片 `INV-P3-02-CONDITION-KERNEL` 已由主 Review 接受为 `done`：7 个生产类与测试在 79 项输入版本通过解析、Kleene、不可变 binding 和纯 kernel 边界检查。原始 Maven 日志与 Surefire XML 记录 `79 tests / 0 failures / 0 errors / 0 skipped`，suite time `1.866 s`，完成于 `2026-10-29T07:52:05+08:00`；此前 `66` 项完成于 `07:46:56`，新增 `13` 项覆盖 literal 文本和空串，无共享源码冲突。测试后 8 个 Java 文件仅补统一文件头，去掉首头注后与 79 项输入逐字节一致；因此没有再次运行 Maven，不能声称文件头版本已重测。当前 `source-manifest.json` SHA-256 为 `ee4aa0499cf7248b228216aeba64e3db57247e4f851db07d3045fd782a5b3cf1`。证据见[实施说明](C:/code/company/innolux-iws/work/rule-dsl-20261029/implementation.md)、[验证摘要](C:/code/company/innolux-iws/work/rule-dsl-20261029/verification.json)与[源清单](C:/code/company/innolux-iws/work/rule-dsl-20261029/source-manifest.json)。既有全 WMS 106 与此处 79 属于不同版本和范围，不能相加成当前 `185`。父 `INV-P3-02`、`INV-P3-03` 与 P3 仍未关闭；条件 kernel 未接 Draft/Publish 成功门禁、Controller、Mapper、SQL、核心初始化、HTTP、正式任务绑定或事实快照持久化。限定结果 kernel 另有 56 项纯内核 Maven 通过，但尚无生产用途注册、service/publication 接入或发布 binding 快照，不能把任意 `result_json` 或 registry 对象解释为已持久化 typed 合同。旧固定 JAR `3B44BC58…` 的 12 个 LAN 场景仍只绑定旧包；当前 SSE 修复有 MVC 12 项、尚未新 package/LAN，也没有真实 DB 写入或部署。

限定结果 kernel 已增加不可变 `FILTER/TARGET_SET/SORT` payload、受信 purpose/version/scopeType 合同、结果用途注册和严格 JSON 校验。实际 Maven 为 `56 tests / 0 failures / 0 errors / 0 skipped`，完成于 `2026-10-29T13:10:29+08:00`；已验条件 8 个文件未变，5 个结果 Java 文件与 ready 时哈希一致。当前没有生产用途注册、service/publication 接入或发布 `binding` 快照，purpose registry 不能证明部署一致性，目标存在、权限、事实装载、安全用途 `ALL` 与任务绑定仍由后续适配器验收。

SSE 认证响应修复已用真实 MVC、异常 advice、消息转换器和 SseEmitter 路径验证 12 项，鉴权失败改为 HTTP 401 JSON，有效连接保持 200 event-stream；尚未生成新 package 或执行新 LAN。旧固定 JAR `3B44BC58…` 的 12 个 LAN 场景仍只对应旧包，不能覆盖当前 SSE 修复或 scope 改动。

装载树纯内核已限制节点为 `MATERIAL/CARRIER`、单父、无环、默认最大深度 16，并按不变快照生成稳定森林与属性三态汇总。单叶数量严格校验 `decimal(20,6)`；主已冻结物料 SUM 使用 `BigDecimal` 精确加法，不沿用单叶 `20,6` 作为整体总量上限，不新增 38 位上限、分批截断或舍入。最终仍为 `23` 项，主已读并接受 SUM 用例替换旧 overflow 的修复；该结果仍是隔离纯 kernel，未 Maven/DB，未接服务、事务、持久化、容量账或 SQL。

聚合键至少包含规则版本、稳定分组值和单位；空值独立桶，默认不跨单位、不跨规则版本相加，载具自身不贡献物料数量。

### 5.4 载具、装载与容量

| 表 | 冻结字段与约束 |
|---|---|
| wms_container（调整） | 保留旧容器主档；增加 load_node_id UNIQUE、lifecycle_status、revision。切换后 storage_id/zone_id/location_status 只读保留。 |
| wms_load_node | load_node_id PK、node_type=MATERIAL/CARRIER、inventory_id、container_id、status、revision；二者恰一且各自 UNIQUE。 |
| wms_load_relation | relation_id PK、child_node_id UNIQUE、parent_node_id、revision；每节点至多一个当前父载具；自包含和环由锁内应用校验拒绝。 |
| wms_load_relation_history | 追加前后父级、效果键、业务/计划/实例、操作者与时间。 |
| wms_storage_info | storage_info_id PK、root_node_id UNIQUE、location_kind=STORAGE/OUTSIDE/IN_TRANSIT/UNKNOWN、storage_id、revision；只有根节点有记录，子节点从根解析位置。 |
| wms_occupancy_effect | occupancy_effect_id PK、effect_key UNIQUE、business_type/business_id/task_key/instance_id/event_id/attempt_id、trace_id、结果、发生/记录时间。一次装载、拆载或位置事实事务只有一个事务头。 |
| wms_storage_occupancy | occupancy_id PK、occupancy_effect_id、storage_id、root_node_id、dimension_key、occupied_qty decimal(38,12)、unit、generation、status、detail_key UNIQUE、active_occupancy_key UNIQUE、revision；唯一 occupancy_effect_id/storage_id/root_node_id/dimension_key/unit/generation。ACTIVE 时活动键非空，CLOSED 时为空。 |
| wms_carrier_aggregate | 带版本的查询投影，单值/冲突集合/叶子引用由有效叶子重建，不作为持久权威。 |

顶层载具与独立物料标签各贡献一个顶层槽；载具内叶子不重复贡献槽。物料数量按库存单元另计。

目标结构不包含“一储位一容器”约束。同一储位可有多个根节点；`wms_storage_info.root_node_id UNIQUE` 只保证一个根只有一个位置记录，占用明细按 storage/root/dimension 分账，共存的合法性由容量、混放、禁存和独占规则判定。旧 `uk_wms_container_storage` 只作为切换期历史约束，必须按第 11.2 节退役，不能复制到新模型。

### 5.5 分配、锁、预留与 trace

| 表 | 冻结字段与约束 |
|---|---|
| wms_access_channel_lock | channel_lock_id PK、channel_id、business_type、task_key、instance_id、lock_effect_key UNIQUE、status、active_channel_key UNIQUE、path_member_ids_json、path_revision、evidence_revision、terminal_effect_key UNIQUE、terminal_release_id、consumption_trace_id、terminal_at、revision。 |
| wms_allocation_decision | 父业务/明细/不可拆单元、round/phase、task_key、instance_id、规则/空间/容量版本、input_hash、候选摘要、结果和审计。 |
| wms_allocation_candidate | decision_id、storage_id、排序键、残余向量、保护/阻挡原因、是否选中；唯一 decision/storage。 |
| wms_allocation_lock | lock_id PK、业务持有人、task_key、instance_id、storage_id、容量维度/单位/份额或实体槽、lock_kind、status=LOCKED/CONSUMED/RELEASED、effect_result、effect_key UNIQUE、evidence_revision、active_share_key UNIQUE、revision。 |
| wms_inventory_reservation | reservation_id PK、inventory_id、父业务/任务、数量/单位、status=ACTIVE/CONSUMED/RELEASED、effect_key UNIQUE、期限、revision。 |
| wms_allocation_release | 原锁、父任务、无效果证据版本、effect_key UNIQUE、释放前后状态、时间与操作者。 |
| wms_material_trace_history | trace_id PK、effect_key UNIQUE、父业务类型/名称/ID/明细、root_business_type/id、task_key/instance_id/event_id/attempt_id、计划类型/版本、input_hash、reverse_trace_id、结果、证据、时间、人员/部门/执行账号快照。 |
| wms_material_trace_item | trace_id、item_id、物料/库存单元、生产快照、数量/单位/前后值/delta、物理/逻辑前后位置、装载父/根前后值、生命周期前后值、规则包版本。 |
| wms_material_trace_edge | trace_id、source_item_id、target_item_id、数量、单位、group_no；唯一组合，表达 N:M 家谱。 |
| wms_object_audit | 对象类型/ID、动作、命令键、前后摘要、操作者/发起人/部门/执行主体、结果、证据、时间；只追加。 |
| wms_command_attempt | 非计划领域受控命令尝试；命令键、输入摘要、状态、错误、事实引用；只追加。 |
| wms_location_change_history、wms_load_relation_history | 分别记录位置版本和装载关系历史，不能互相替代。 |
| wms_inventory_flow（调整） | 保留旧行和唯一幂等键；增加物理源/目标 ID、业务/计划/实例/任务关联和 trace ID；不重写旧流水。 |

trace 只在成功物料事实事务中生成。失败、取消、重试和 UNKNOWN 只留命令/流程审计；补偿创建新反向 trace，不覆盖原事实。

### 5.6 业务目录、任务屏障与升级控制

| 表 | 冻结字段与约束 |
|---|---|
| wms_business_type | business_type_code PK、名称、status、contract_version、object_policy_key、revision。 |
| wms_business_plan_mount | mount_id PK、business_type_code、plan_type_code、schedule_code、contract_version、enabled、revision；唯一业务类型/计划类型/调度。 |
| wms_business_task | task_id PK、business_type、business_id、line_id、allocation_unit_id、round_no、phase、task_key、plan_type、plan_instance_id UNIQUE、input_hash、status、revision；唯一 business_type/task_key。 |
| wms_business_barrier、wms_business_barrier_item | 父业务期望任务集合、持久结果、阻碍原因和完成状态；页面状态不能代替屏障。 |
| wms_schema_version | 组件、schema_version、release_id、源/目标指纹、状态、应用人和时间。 |
| wms_upgrade_run | run_id PK、release、源/目标指纹、阶段、write_fence_token、状态、尝试、错误和时间。 |
| wms_upgrade_checkpoint | run_id、step_code、object_key、状态、前后摘要、尝试和时间；唯一 run/step/object。 |
| wms_legacy_id_map | 域、源表/键、目标类型/ID、源摘要、映射状态；唯一源键和目标身份。 |
| wms_upgrade_issue | 中文问题类别、脱敏源键、阻碍阶段、建议动作、是否阻断。 |

wms_inventory_hold、冻结/解冻专属业务和菜单不进入本期对象清单。

### 5.7 当前直接写 schema 门禁与冻结锚点

F0 不创建新表、不新增 DDL 状态。登记锚点是 `wms_schema_version(component_code, schema_version) = ('WMS_DIRECT_WRITE','1')` 的唯一行，必须精确满足：

- `release_id=INV-WMS-DIRECT-WRITE-1`；
- `source_fingerprint=target_fingerprint=<固定 manifest 中的 64 位小写 SHA-256>`，只表示当前结构与已接受的 DIRECT_WRITE 基线相同，不表示执行过迁移或达到完整 P1 目标；
- `status=VERIFIED`，且没有与之冲突的重复身份。

固定 manifest 的精确结构输入包括五张 schema 升级控制表、五张公司表及供应商当前已接受结构。离线登记 SQL 在任何业务写入前按 canonical 类型、列/索引/default、CHECK/trigger/FK 可见性等完整集合逐项核对这些结构；实际 metadata 使用 NULL-safe 字节比较或无歧义 `JSON_ARRAY` canonical，SQL NULL 与字面 `'<NULL>'`、空串及不同大小写不混同，不区分 MySQL 归一后不可观测的隐式 NULL 与显式 DEFAULT NULL。缺表、额外或漂移定义、指纹或签名冲突一律阻断，不以“同名表存在”或摘要截断结果代替。登记只写 schema 登记及必要的 VERIFIED checkpoint，不自动执行 DDL、业务 seed 或 role/menu grant；同身份同内容幂等，同身份异内容以既有唯一键和摘要比较阻断。

门禁分为查询与写入：

- 查询、列表、元数据和历史读取不依赖 F0 锚点，登记缺失或状态不支持时仍可继续。
- 实际写事务在参数、功能权限和对象范围等不落库预处理后，最先对该锚点执行 `SELECT ... FOR SHARE`，并持锁到事务提交；随后在同一事务内核对锚点、固定 manifest、未完成 run 和目标命令属于 F0 白名单。任一条件不满足返回 `SCHEMA_STATE_UNSUPPORTED`，不得降级为可写。
- 两笔已经通过 READY 门禁的 DIRECT_WRITE 事务可以同时持有共享锚点并继续，不增加全局串行；业务行锁、revision、唯一键和领域锁仍按各自合同执行。
- 公司 schema 门禁在业务受理前拒绝时，不创建 `wms_command_attempt`；其他命令在门禁通过后的审计、幂等、版本冲突和 UNKNOWN 原语义不变。供应商普通 CUD 必须有事务边界，门禁失败不得留下业务写入或审计成功的半事实。

协议控制变更必须先对同一锚点执行 `FOR UPDATE`。直接冻结流程为：

1. 排他锁等待已持共享锚点的写事务排空，持锁到冻结事务提交；
2. 检查全部未安全完成的 WMS run，不限定当前 `schema_version` 或 `release_id`；零个继续，一个以上按冲突阻断，不得因其他版本 release 绕过；
3. 同一事务插入新的 `wms_upgrade_run`，并把固定 `write_fence_token`、来源/目标指纹和有限范围状态持久化；
4. 同一事务写 `step_code=S2_DIRECT_WRITE_FENCE`、`object_key=WMS_DIRECT_WRITE`、`status=VERIFIED` 的 checkpoint；
5. 提交后才宣布直接写冻结点成立，此后所有 F0 写入口因存在未安全完成的 run 拒绝写。

该 checkpoint 只证明 F0 当前直接写入口在冻结时刻已排空并关闭，不证明数据库账号、旧应用、ETL、旁路 SQL、计划认领或其他进程全部停止，也不把完整 S2、S3 回填、B04/B05 或 S2 总签收写成完成。run 插入后有 token，但 token 本身不是排空证据。持久冻结点已经成立后，后续失败或 UNKNOWN 不自动解除；freeze 提交结果未知时必须回读 run/checkpoint，不凭客户端异常视为未生效，也不继续 S3。明确整事务回滚且无冻结事实时不宣称排空成功，这不是自动解冻，也不要求凭空新增持久 blocking 状态或可靠投递承诺。已成立冻结点的安全解除仍按 C34 交付。

## 6. 状态、键与精度

| 类别 | 稳定码 |
|---|---|
| 实体生命周期 | ACTIVE/RETIRING/RETIRED |
| 配置或规则版本 | DRAFT/ACTIVE/RETIRED |
| 业务单 | DRAFT/SUBMITTED/PROCESSING/BLOCKED/COMPLETED/CANCELLED/FAILED |
| 分配锁或通道锁 | LOCKED/CONSUMED/RELEASED |
| 占用 | ACTIVE/CLOSED |
| 预留 | ACTIVE/CONSUMED/RELEASED |
| trace | FACT/COMPENSATION |

平台实例状态另用 PlanStatus，不能反推业务状态。NO_CANDIDATE 和 UNKNOWN 是决策或结果说明，不扩展为锁状态。

控制表沿用既有状态，不因 F0 增加枚举：`wms_schema_version` 仍为 `PENDING/APPLIED/VERIFIED/FAILED`，升级 run 仍为 `RUNNING/BLOCKED/FAILED/COMPLETED`，checkpoint 仍为 `PENDING/RUNNING/APPLIED/VERIFIED/FAILED/SKIPPED`。“READY writer”只描述应用门禁已通过的请求，不持久化为新数据库状态。

WMS taskKey 固定为：

p<parentBusinessId>:l<lineId>:u<allocationUnitId>:r<roundNo>:<LOCK|RELEASE|MATERIAL|VERIFY>

各 ID 使用内部数字 ID，taskKey 上限 96。平台实际身份为 businessType:taskKey，不再重复携带 businessType。同一 businessType/taskKey 同 inputHash 返回原实例，异摘要拒绝。inputHash 为 64 位小写 SHA-256；领域 effectKey 为 WMS: 加规范化输入 SHA-256，长度不超过 128。

数量边界：作业请求量大于 0；实存允许 0；trace delta 可负；存储精度不得超过 decimal(20,6)；未登记单位不得换算或相加；空值、空集合、冲突集合分别解释，空集合不是允许全部。

混放与容量：默认 allowMultiMaterial=false、mixedMaterialKey=materialCode、TOP_LEVEL_LOAD=1；未知混放键或空值通配拒绝；安全、禁存和设备限制对全部叶子使用 ALL；载具内叶子数量不重复贡献顶层槽；容量或混放合同变更必须创建新版本。

### 6.1 储位地址键与精确匹配

地址规范化固定为：`null` 保持 `NULL`；字符串去掉首尾 ASCII 空格后为空则存 `NULL`，其余字符原样保存。比较区分大小写、全半角和 Unicode 码点，不做 NFC/NFKC 归一化，不去前导零；因此空串与 `NULL` 同键，` 01 ` 与 `01` 同键，`01` 与 `1`、`A1` 与 `a1`、全角与半角、单码点与组合形式均不同键。实现不得在写入、判重或查询任一层使用不同规范化；超过列长拒绝，不截断。五列加 `value IS NULL OR (value <> '' AND CAST(value AS BINARY) = CAST(TRIM(value) AS BINARY))` 检查，按字节禁止空串和首尾 ASCII 空格；写入适配仍按统一规范化置 `NULL`。验收核对生成键、空值标志和存值规范化一致。

唯一范围是同一仓库，包含该仓库所有设备库区以及 ACTIVE/RETIRING/RETIRED 全部行。五项全空的储位每仓最多一条；退役不释放组合。并发插入或改址由数据库唯一键判定，冲突返回 `ADDRESS_CONFLICT`，业务预检的“未发现”不能作为写成功依据；唯一键创建前必须完成旧库冲突预检，见第 11.3 节。

`storage_id` 是锁、占用、容量和历史的稳定资源键；地址文本、编码和名称变更不改写既有 ID。深度数量不设上限，也不要求数字或连续；候选只读通道成员、`access_order` 和阻挡边，不按任何地址文本排序。

精确定位与普通检索分开。通用查询中字段未出现表示不过滤，显式空串作为该字段的条件，不能表达 NULL；用户用删除键清空文本输入时前端不发送该筛选参数。通用空串条件按字面空字符串匹配，不匹配 NULL；地址列不保存空串，因此该条件不会命中空白地址。精确定位使用第 9.3 节专用 API，五项以显式 `{field, value}` 条目表达，`value:null` 匹配 NULL；每条必须含 `field` 与 `value` 键，空白值按同一规范化归一为 NULL，缺项、缺值键、重复项仍拒绝，不能依赖 DTO 将缺项折叠为 `null`。两者使用同一精确比较规则。

## 7. 通道、空间父链与锁协议

目标储位存在 wms_storage_access_member，或以深位/阻挡路径参与候选时，锁域为 channel_id；普通独立储位锁域为 storage_id。一个通道只允许一个活动通道锁，由 wms_access_channel_lock.active_channel_key 唯一约束保证。只持事务内通道行锁不够，因为事务结束后的另一任务仍可能占用或阻挡路径。

通道配置、成员集合、`access_order` 或阻挡边的变更也必须取得该通道锁并走同一全局锁序；存在 LOCKED 通道锁或 UNKNOWN 证据时拒绝变更并返回 `CHANNEL_BUSY` 或 `EVIDENCE_UNKNOWN`。变更提交递增 `wms_access_channel.revision`，锁定和分配在锁内按新 revision 复核；不能先改成员再等待锁，也不能以配置发布绕过活动锁。

普通储位加入、移出或跨通道变更必须锁定旧通道、新通道和储位：通道按 `channel_id` 升序，再锁 `storage_id`；新增成员时旧通道为空，只锁新通道和储位。锁内检查两侧通道和储位上的活动分配锁、容量锁、占用事务及 UNKNOWN 证据，存在任一项即拒绝成员变更。分配若先按 `storage_id` 取得储位锁，必须在取得后重读成员归属和通道 revision；发现储位已进入或离开通道、锁域由 storage 变为 channel 或反向时，释放已取得锁并回到全序重试，不能继续按旧锁域写入。

空间父链锁独立于通道锁：库区跨仓变更与子储位新增、改挂或地址更新必须共锁仓库/库区父链行，按 `warehouse_id`、`zone_id` 升序，跨仓时同时锁旧新两侧。父链锁先于通道锁和储位锁；取得父链锁后重新解析仓库并写 `wms_storage.warehouse_id`；不得先锁储位再改父区，也不能仅凭请求快照写父链仓库键。

全局锁序：

1. 按唯一数字 ID 升序锁定父业务任务/屏障；
2. 按 warehouse_id、zone_id 升序锁定受影响的空间父链；跨仓变更把旧新两侧合并后按同一顺序加锁；
3. 收集受影响通道，按 channel_id 升序 SELECT FOR UPDATE；
4. 按 storage_id 升序锁定储位和容量行；
5. 按锁 ID 升序锁定占用、预留和分配锁；
6. 最后追加历史和审计。

不得先锁储位再锁通道或父链，也不得按用户输入顺序跨通道加锁。父链、通道、储位和锁分别按上述稳定 ID 升序取得；短事务内取得行锁，设备或人工作业期间由持久通道锁和分配锁约束，数据库事务不跨外呼。

取得全部锁后必须重读并比对通道 revision、成员集合、访问顺序和阻挡边版本；储位启停、设备健康、安全门禁、维护状态；容量版本、有效占用、有效锁、预留和扣留；规则包、空间版本、单位/日历版本和任务 inputHash；父任务门禁、租约代次和外部效果证据版本。

任一核心版本变化时，在同一任务和同一规则版本下重新评估候选；不得切到最新规则，也不得覆盖并发修改。无候选返回 NO_CANDIDATE；路径不确定返回 CHANNEL_PATH_BLOCKED。

生命周期：

- RESERVE_STORAGE 取得通道和储位锁，验证路径后写通道锁、储位锁/容量份额和决策，全部成功才返回锁定。
- APPLY_MATERIAL_CHANGE 按相同锁序重验通道、路径、锁、占用和证据，在 SAME_TX 写真实位置/占用、锁消费和 trace；成功时通道锁从 LOCKED 直接终结为 CONSUMED。
- RELEASE_ALLOCATION_LOCK 按相同锁序重验无效果证据，只释放本任务份额；UNKNOWN/ACCEPTED 保持锁。
- 任何失败、超时或回滚保留原事实；不得因页面关闭或超时删除通道锁。

通道锁终结合同：

- LOCKED 只能转为 CONSUMED 或 RELEASED，二者都是终态；不得从 CONSUMED 改为 RELEASED，也不得反向。
- CONSUMED 由成功物料事实事务设置 terminal_effect_key、consumption_trace_id 和 terminal_at，同时清空 active_channel_key。它证明锁已被真实业务消费，ALLOCATION_RELEASE 只能返回 ALREADY_CONSUMED，不能撤销占用。
- RELEASED 仅在 CHECK_RELEASE_EVIDENCE 冻结并复核确定无效果或安全取消证据后设置 terminal_effect_key、terminal_release_id、evidence_revision 和 terminal_at，同时清空 active_channel_key；不得凭超时、页面关闭或 UNKNOWN 释放。
- terminal_effect_key 全局唯一；同一终结请求重复提交返回原终态，不重复写占用、trace 或历史。VERIFY 只回读终态和关联证据。

## 8. 装拆与顶层槽容量同事务

APPLY_LOAD_CHANGE 不维护单独“已用槽数”字段，而以 wms_storage_occupancy 的根节点明细推导容量：

1. 冻结受影响节点、旧根/新根、源/目标储位和不可拆组；
2. 按第 7 节锁序锁定所有受影响的父链（如有）、通道和储位；
3. 读取当前关系、根位置、占用、锁、预留、容量版本和安全门禁；
4. 在内存中计算变更前后的“根节点到储位”映射及全部容量维度；
5. 校验同一根不重复计槽、叶子不贡献顶层槽、全部维度不超容量、深浅路径仍可达；
6. 同一事务先写一个 wms_occupancy_effect 事务头，再批量关闭或创建 wms_storage_occupancy 明细并追加载荷历史；
7. 同事务写业务/计划关联和检查点；唯一键、版本或容量失败全部回滚；
8. VERIFY 从持久根关系和占用重算，不接受内存回报值作为成功证据。

拆分使一个根变成两个时在同一事务头下创建两条新根明细；合并使两个根变成一个时关闭原两条并创建一条新根明细，保留旧根 ID 历史。多根、多维、多单位各自生成不同 detail_key，但只共享一个 occupancy_effect_id/effect_key。多容量维度逐维校验；缺维度时拒绝，不以 0 或无限容量补齐。

## 9. API 与 DTO 合同

### 9.1 受控 /wms/crud 合同

/wms/crud/{tableCode} 保留为 Web 的统一受控写入口，与原 Web 合同一致。后端按已注册 tableCode 处理器、DTO 白名单、权限、状态和 revision 选择以下一种结果：

- 原子 CRUD 加同事务审计；
- 领域命令，必要时创建持久业务和计划任务；
- 只读或 FEATURE_NOT_OPEN。

一次 add/update/remove/import 请求只产生一次路由，前端不得自行串联第二条业务命令。领域分流响应必须返回 accepted/applied 区分、对象 revision、业务号、taskKey、instanceId、allowedActions 和待处理阻碍；前端只在同一入口展示受理与生效反馈。查询、分页、列设置、模板、导入预览和导出仍复用现有元数据与 hooks。

/wms/crud 仍是表名白名单处理器，不提供任意表、任意字段、任意 SQL 或绕过对象/部门权限的能力。普通名称/备注修改可原子 CRUD 加审计；空间关系、规则生命周期、装载、库存位置/数量、锁/释放、可见范围、退役和 trace 家谱必须由处理器委托领域命令或计划。meta 能力必须反映当前真实可写集合；未注册 tableCode 的按钮不得显示为可用。

`INV-S2-F0` 把 `wms_company` 的创建、改名备注、启停（`setEnabled` 一个命令族）、管理范围替换以及 `wms_supplier` 的 add/update/remove/import 纳入直接写门禁。其他 WMS 写命令即使已有工作树代码，也不由 F0 白名单或登记证据覆盖；不得据 F0 宣称其已接入 schema 锚点。

### 9.2 公司最小闭环

首个可写 tableCode 冻结为 wms_company，只开放公司身份与无关系副作用的基础操作：

当前工作树已形成 `wms_supplier` 与 `wms_company` 元数据和命令代码；F0 按下表范围登记元数据/查询以及公司创建、改名备注、启停和管理范围替换。以下 API 是合同，不等同当前门禁或 hardening 已验收。

| 操作 | 入口 | DTO 和 revision |
|---|---|---|
| 元数据/查询 | GET /wms/crud/wms_company/meta；POST /wms/crud/wms_company/list | 返回 companyId、companyCode、companyName、remark、enabled、lifecycleStatus、revision、allowedActions。 |
| 创建 | POST /wms/crud/wms_company | WmsCompanyCreateDTO：companyCode、companyName、remark、commandKey。服务端固定 lifecycle_status=ACTIVE、enabled=false、revision=1；DTO 不接收 enabled、lifecycleStatus、仓库关系或可见范围。 |
| 名称/备注修改 | PUT /wms/crud/wms_company | WmsCompanyUpdateDTO：companyId、companyName、remark、expectedRevision、commandKey、reason。companyCode 创建后不可改；只有变更字段写审计，成功后 revision 加 1。 |
| 启用 | POST /wms/crud/wms_company/{id}/enable | WmsCompanyEnableCommand：expectedRevision、commandKey、reason。要求 lifecycle_status=ACTIVE；只把 enabled 改为 true，不改变生命周期、不建立关系、不授予可见范围。 |
| 停用 | POST /wms/crud/wms_company/{id}/disable | 同一 DTO。只把 enabled 改为 false，不删除、不退役、不级联关系；后续新业务不再选择该状态，历史保持。 |
| 管理范围全量替换 | PUT /wms/crud/wms_company/{id}/management-grants | WmsCompanyManagementScopeReplaceCommand：companyId、roleIds（完整目标集合）、expectedScopeRevision、commandKey、reason。空 roleIds 表示撤销全部管理范围；不接收增量 add/remove 语义。 |

上表的启用与停用属于同一 `setEnabled` 受控命令族，在范围统计中合为一个类别；公司总类别为创建、改名备注、启停、管理范围替换。该分类只纠正文档，不改变现有接口、权限或行为。

权限：inventory:company:create、inventory:company:query、inventory:company:update、inventory:company:enable、inventory:company:disable、inventory:company:scope:all、inventory:company:scope:manage。公司名称/启停使用公司 expectedRevision；管理范围只使用独立的 expectedScopeRevision，二者不得混用。版本冲突分别返回 VERSION_CONFLICT 或 SCOPE_VERSION_CONFLICT/409，并保留用户输入。创建、修改、启停、管理范围替换成功分别与 wms_object_audit 同事务，失败 attempt 独立留存，不伪造成功历史。

F0 schema 门禁在上述四个公司写类别的业务受理前执行；门禁拒绝不创建 attempt。门禁通过后再按 commandKey 执行既有幂等和 attempt 合同；其他失败、版本冲突和 UNKNOWN 不得因本增量回退。

公司管理与库存可见的授权合同：

- 功能权限由 @SaCheckPermission 校验，权限集合沿用菜单权限解析；超级管理员按现有 *:*:* 旁路通过功能权限检查。
- 公司管理对象范围由 WmsCompanyAccessPolicy 校验：超级管理员直接放行；有 inventory:company:scope:all 的普通用户可跨公司管理；其他用户必须命中至少一条 wms_company_management_grant，且 granted role_id 属于当前 LoginUser.roles。scope:all 只替代逐公司 grant，不能替代 create/query/update/enable/disable 功能权限。
- 管理与库存可见完全分离。公司管理授权不写 wms_object_visibility；库存查询也不会因管理范围自动可见。库存查询先校验功能权限，再在每一行应用公司/空间有效 visibility 谓词和现有 DataPermission；不得用公司级部门交集替代行权限。
- 库存权限必须逐行应用现有 DataPermission，不得仅凭公司级部门交集做控制器准入。分页明细、汇总、容量展开、历史、家谱和导出都在 SQL 过滤后再分页/聚合；逐行条件保留 dataScopeRoleMap/DataScopeType 全部语义：ALL、CUSTOM、DEPT、DEPT_AND_CHILD、SELF（create_by=当前 userId）、DEPT_AND_CHILD_OR_SELF 的原 OR 分支。多个匹配角色取并集。公司有效 visibility 是附加的路径谓词，不能替代逐行数据权限。
- 仅校验功能权限即可进入查询路由；具体行是否返回由公司/空间路径谓词和现有逐行数据权限共同决定。不得提前因“公司部门交集为空”直接拒绝，也不得在同一请求中先按公司范围放行全部行。超级管理员沿用现有数据权限旁路。
- 公司根 visibility 缺失、VALUE 空集合或 CLEAR 均使库存可见集合为空；这不阻断公司管理者查询公司主档，但阻断库存/容量明细。
- 首家公司只允许现有超级管理员（LoginHelper.isSuperAdmin）创建，作为系统唯一的 bootstrap 路径；创建请求固定 enabled=false，同时创建 scope_revision=0 的空管理范围头，不自动创建角色菜单权限、管理 grant 或 visibility 行。创建后超级管理员可读取并提供显式授权，避免“创建即赋权”。
- 后续创建公司要求 inventory:company:create，且当前用户为超级管理员或具有 inventory:company:scope:all；单公司 grant 不用于给尚不存在的目标公司创建资格。创建仍不自动赋权。
- 管理范围替换要求当前用户为超级管理员，或同时具有 inventory:company:scope:all 与 inventory:company:scope:manage；目标 roleId 必须全部存在且去重。事务锁定 wms_company_management_scope 行，比较 expectedScopeRevision，全量删除旧明细并按 roleIds 插入，范围头 revision 加 1，并同事务写 wms_object_audit；空 roleIds 只删该公司全部明细。角色已有菜单功能权限与 grant 缺一不可。授权不自动创建或修改角色、菜单、用户或 department visibility。
- 授权范围查询不得使用登录会话中长期缓存的 roleIds。WmsCompanyAccessPolicy 每次受保护命令读取当前 scope_revision 和明细；如使用缓存，只能以 companyId/scopeRevision 为键并在提交后失效，失配时必须回源数据库。撤权事务提交后，下一请求立即失去管理资格；正在执行的公司写命令须在业务事务内再次读取范围并以 scope_revision CAS/锁复核，发现撤权即拒绝。
- inventory:company:scope:all 和 inventory:company:scope:manage 只作为菜单/按钮权限候选登记，不自动授予业务角色；超级管理员通过现有 *:*:* 通配权限使用。角色-公司 grant 也不由初始化 seed 自动分配，必须由授权后的管理员显式提交。
- 超级管理员旁路确保首家公司创建后至少存在一个可读带范围的主体。若部署环境没有可用的超级管理员账号，首家公司无法安全引导，必须在上线前补齐账号；不得退化为创建者自动全范围。

以下操作在本最小闭环明确不开放，meta 返回 allowedActions=false，调用返回 FEATURE_NOT_OPEN：仓库关系/初始子仓、公司可见范围、根权限配置、公司退役/删除、批量导入、跨公司配置。不得用通用 CUD 修改 warehouseIds、enabled、lifecycle_status、visibility 或退役状态。公司 enabled=true 只表示后续业务可选择，不代表授权、可见或已配置子仓。

未来开放关系、可见范围或退役时必须改为独立领域 DTO/命令和业务/计划，不属于本次公司最小闭环。

### 9.3 领域 API

| 能力 | API 或端口 | 权限与约束 |
|---|---|---|
| 能力目录 | GET /wms/inventory/catalog | 返回业务类型、计划类型、允许挂载、属性和状态字典；不返回未发布能力。 |
| 当前物理树 | GET /wms/inventory/space/tree | inventory:space:query；部门与对象范围由后端裁剪。 |
| 储位精确定位 | POST /wms/inventory/storage/locate | inventory:storage:query；请求含 warehouseId 与 addressFields，后者恰含五个 {field,value} 条目（AISLE/ROW/LEVEL/COLUMN/DEPTH 各一次），每条必须含 field 与 value 键；value:null 或空白值归一后的 NULL 表示 NULL，缺项、缺值键、重复、未知字段返回 400 ADDRESS_PRESENT_MISMATCH，超长等非法值返回 ADDRESS_VALUE_INVALID；返回 0 或 1 条。通用查询字段未出现不过滤，显式空串作为条件，清空输入不发送筛选参数。 |
| 独立逻辑归属 | GET /wms/inventory/logical-scopes | inventory:space:query；不得复制成第二物理树。 |
| 容量/可分配查询 | GET /wms/inventory/storage/{id}/capacity | inventory:allocation:query；只读授权信息。 |
| 分配预览 | POST /wms/inventory/allocation/preview | inventory:allocation:preview；不建实例、不写锁/占用。 |
| 域任务挂载 | POST /wms/inventory/tasks/attach | inventory:business:attach；必须存在父业务、注册关系、冻结输入和三运行开关。 |
| 任务/屏障查询 | GET /wms/inventory/tasks/{businessType}/{taskKey} | inventory:business:query；返回领域结果、实例、事件和阻碍。 |
| 空间变更 | POST /wms/inventory/space-changes | inventory:space:change；提交持久业务和任务，不直接改树。 |
| 规则预览/发布 | POST /wms/inventory/rules/preview 或 /publish | inventory:rule:preview/publish；发布校验通过才激活不可变版本。 |
| 装载变更 | POST /wms/inventory/load-changes | inventory:carrier:change；提交关系和容量事实。 |
| 物料事实查询/追踪 | POST /wms/inventory/traces/page | inventory:trace:query；只读稳定分页。 |

域任务挂载 DTO 至少包含 businessType、businessId、lineId、allocationUnitId、roundNo、phase、planType、scheduleCode、inputHash、rootSet、commandKey。转换到平台时原样映射 businessType/businessId/taskKey/inputHash/rootSet/commandKey；不得在 taskKey 再拼 businessType。

目录 SQL 当前只形成两张目录表的结构/守卫/验证切片。2026-10-28 主 Review 已核对 catalog SQL 为 `5642914E286578829CF26A74FA7982EF9E63938EE3E989BBEE29AB24C18B61BC`、verify SQL 为 `C5E12515775AE51FFD3B25B3F5E895DDAD534535ECA687568A49A20A0EC6D0EA`，完整集合改为逐表行数及逐字段匹配行数且不再使用 `GROUP_CONCAT`。该证据不关闭 `GET /wms/inventory/catalog` 到 Web 消费、停用历史恢复、服务端 capability 和完整 TG08；Web consumer 的派发与未验收状态只在任务图记录。

2026-10-29 增量另有 Catalog 菜单 SQL 的限定接受：`iws_inventory_catalog_menu.sql` SHA-256 `9a895033…`、verify `bb8dac9d…`，隔离矩阵 `105` 项断言、`98` 次 SQL 文件执行。菜单仅由页面 `1761400000000050300` 承载 `inventory:business:query`，不另建 F 权限，也不写 `sys_role_menu`。主 Review 阅读当前 Web 只读 consumer 源码后未见新阻断，但已有证据只是旧 `7 unit / 20 browser mock`，没有真实后端联调，因此不得写成完整 TG08 已通过。

固定错误码：PARENT_TASK_NOT_FOUND、PARENT_TYPE_NOT_REGISTERED、MOUNT_NOT_ALLOWED、TASK_KEY_CONFLICT、INPUT_HASH_CONFLICT、PLAN_NOT_PUBLISHED、QUEUE_RUNTIME_UNAVAILABLE、NO_CANDIDATE、CHANNEL_PATH_BLOCKED、CHANNEL_BUSY、ADDRESS_CONFLICT、ADDRESS_PRESENT_MISMATCH、ADDRESS_VALUE_INVALID、CAPACITY_EXCEEDED、VERSION_STALE、VERSION_CONFLICT、SCOPE_VERSION_CONFLICT、ALREADY_CONSUMED、FEATURE_NOT_OPEN、SEED_DIGEST_CONFLICT、EVIDENCE_UNKNOWN、LEGACY_FIELD_READ_ONLY、SCHEMA_STATE_UNSUPPORTED。

## 10. 计划事件挂载合同

所有本期模板默认 planRole=BUSINESS、mode=QUEUE、completionPolicy=ALL_SELECTED、selfSystemCode=IWS。复用计划定义的 business_type 为空，具体父业务类别由 AttachTaskCommand.businessType 和允许挂载目录解析。

事件类型统一 WMS_INTERNAL@1，方向 INTERNAL。每个事件唯一 LocalAction@1，transactionMode=SAME_TX、checkpointed=true；无调用合同、HTTP 效果键、端点、用途和完成合同；目标系统仅为 IWS。每个 planType 还必须发布唯一 SUPERVISOR 定义，监督动作复用平台能力。

以下 actionKey 与事件码相同，版本均为 1；localEffectKey 为 WMS:<eventCode>。

| 阶段 | planType | eventCode | 源到目标状态 | 唯一领域效果 |
|---|---|---|---|---|
| P2 | SPACE_CHANGE | VALIDATE_CHANGE | START 到 VALIDATED | 固定影响报告、版本和阻碍。 |
| P2 | SPACE_CHANGE | APPLY_CONFIGURATION | VALIDATED 到 APPLIED | CAS 提交一个配置版本及历史。 |
| P2 | SPACE_CHANGE | VERIFY_CHANGE | APPLIED 到 END | 回读生效指针和对象范围。 |
| P2 | SPACE_RETIRE | CHECK_RETIREMENT | START 到 CHECKED | 固定无子项、库存、锁和开放效果证据。 |
| P2 | SPACE_RETIRE | RETIRE_LOCATION | CHECKED 到 END | 锁内复核后退役身份。 |
| P2 | SPACE_REALLOCATION | CAPTURE_SPACE_RANGE | START 到 CAPTURED | 固定储位集合、不可拆单元和版本。 |
| P2 | SPACE_REALLOCATION | BLOCK_NEW_ALLOCATION | CAPTURED 到 GATED | 设置持久停新分配门禁。 |
| P2 | SPACE_REALLOCATION | VERIFY_SPACE_EMPTY | GATED 到 EMPTY | 读取移库屏障，验证物理、预留和任务清空。 |
| P2 | SPACE_REALLOCATION | APPLY_SPACE_BOUNDARY | EMPTY 到 APPLIED | 锁内一次提交父关系、版本和历史。 |
| P2 | SPACE_REALLOCATION | VERIFY_SPACE_BOUNDARY | APPLIED 到 VERIFIED | 验证集合守恒、唯一父级和生效版本。 |
| P2 | SPACE_REALLOCATION | RELEASE_SPACE_GATE | VERIFIED 到 END | 只释放本单门禁。 |
| P3 | RULE_PUBLISH | VALIDATE_RULESET | START 到 VALIDATED | 固化规则包、能力、范围和摘要。 |
| P3 | RULE_PUBLISH | ACTIVATE_RULESET | VALIDATED 到 END | CAS 切换生效指针并生成新归属入口。 |
| P4 | MATERIAL_CHANGE | CHECK_MATERIAL_CHANGE | START 到 CHECKED | 固化父业务、物料组、锁/占用和输入。 |
| P4 | MATERIAL_CHANGE | APPLY_MATERIAL_CHANGE | CHECKED 到 APPLIED | 同事务写位置/数量、装载、家谱、锁消费/占用和 trace。 |
| P4 | MATERIAL_CHANGE | VERIFY_MATERIAL_CHANGE | APPLIED 到 END | 回读事实、历史和 checkpoint。 |
| P4 | LOAD_CHANGE | CHECK_LOAD_CHANGE | START 到 CHECKED | 固定节点集合、旧/新根和目标关系。 |
| P4 | LOAD_CHANGE | APPLY_LOAD_CHANGE | CHECKED 到 END | 同事务写关系、根位置、容量占用和历史。 |
| P5 | ALLOCATION_LOCK | EVALUATE_ALLOCATION | START 到 EVALUATED | 固化版本、候选、排序和解释。 |
| P5 | ALLOCATION_LOCK | RESERVE_STORAGE | EVALUATED 到 RESERVED | 通道/储位锁内原子写容量份额或槽。 |
| P5 | ALLOCATION_LOCK | VERIFY_ALLOCATION | RESERVED 到 END | 回读持有人、目标、版本和效果键。 |
| P5 | ALLOCATION_RELEASE | CHECK_RELEASE_EVIDENCE | START 到 EVIDENCE_CHECKED | 固化失败/取消和无外部效果证据版本。 |
| P5 | ALLOCATION_RELEASE | RELEASE_ALLOCATION_LOCK | EVIDENCE_CHECKED 到 RELEASED | 重验锁序和证据后只释放本任务份额。 |
| P5 | ALLOCATION_RELEASE | VERIFY_RELEASE | RELEASED 到 END | 校验释放流水、余额和父屏障。 |
| P6 | LOGICAL_MERGE | CAPTURE_MERGE_SCOPE | START 到 CAPTURED | 固定一个批次范围和老归属 revision。 |
| P6 | LOGICAL_MERGE | CHECK_MERGE_COMPATIBILITY | CAPTURED 到 CHECKED | 按目标规则验证本批兼容性。 |
| P6 | LOGICAL_MERGE | APPLY_LOGICAL_MERGE | CHECKED 到 APPLIED | 同事务更新逻辑归属并追加历史，不搬实物。 |
| P6 | LOGICAL_MERGE | CLOSE_MERGE | APPLIED 到 END | 关闭本批；全局合并不是一次计划循环。 |

每个目标状态必须绑定独立 StateValidator@1，业务受理时校验已持久事实、版本、父任务和 checkpoint；不允许空校验器或只检查 HTTP/进程状态。

### 10.1 QUEUE 运行门禁

正式挂载前，WMS 域适配器必须检查 process、process.scheduler、snail-job 三开关、当前 environment 和唯一已发布定义；任一缺失返回 QUEUE_RUNTIME_UNAVAILABLE，不创建 stranded 实例，也不静默改用 IMMEDIATE。

IMMEDIATE 仅可用于以后明确声明安全失败合同和入站证据的短本地计划。本 P1 清单没有批准任何 IMMEDIATE 定义。fixture 可直接验证 LocalAction 事务、能力和状态器；若验证平台 attach/worker 链，必须使用显式启用调度器的隔离测试配置。fixture 通过不等于当前部署可运行 QUEUE。

## 11. 初始化、升级与旧容器切换

未来实施必须同 release 交付并同批验证：

- 更新后的 iws_wms_smt_core.sql 和 iws_init_from_empty.sql 内联 WMS 段；
- iws_inventory_management_upgrade.sql；
- iws_inventory_management_verify.sql；
- iws_inventory_management_concurrency_verify.sql；
- iws_inventory_management_rules_seed.sql；
- iws_inventory_management_site_template.sql；
- iws_inventory_management_acceptance.sql；
- 应用 release、menu/perms、dict、规则 manifest 和结构目标指纹。

`INV-WMS-DIRECT-WRITE-1` 不是上述完整产品 release。F0 先交付独立离线登记 SQL 和兼容应用代码；后续纳入 `iws_init_from_empty.sql` 时必须与同一新代码版本同批验证并生成新的完整验收证据。空间菜单 `241` 菜单空库入口已有主 Review 通过的限定集成，但 F0 登记仍未进入 empty；当前 F0 写入集不并行改写 init，任务保持 `in_progress`。

本轮没有创建或执行上述脚本。SQL 头部必须集中声明目标 schema、允许的源版本/指纹、目标指纹、批大小、写冻结 token 和站点模板；执行前验证 DATABASE()。

seed 台账必须按 seed_code/seed_version 唯一。脚本在事务中按唯一键读取：不存在则插入；存在且 canonical_digest 相同则跳过；存在但摘要不同返回 SEED_DIGEST_CONFLICT 并阻断，不更新既有行、不新增同 code/version 的第二行。manifest 中的 code/version/digest 和应用 release 必须一致。

### 11.0 当前直接写离线登记与直接冻结

F0 的登记 SQL 先只读核对控制五表、公司五表和供应商当前已接受结构，确认固定 manifest 与当前结构完全一致后，才在单一事务登记 `WMS_DIRECT_WRITE/1/INV-WMS-DIRECT-WRITE-1`。登记行使用 `source_fingerprint=target_fingerprint`，表示结构已匹配当前 DIRECT_WRITE 基线；它不代表存在升级源、完成数据回填或签收完整 P1。脚本不得创建/修改业务表，不写公司、供应商或其他业务 seed，不授予 role/menu 权限，也不自动修复结构。

同 component/schema version 重跑时，脚本必须在登记锚点排他保护下读取既有行：release、源/目标指纹、状态及范围备注完全相同则幂等；任一内容不同返回冲突并阻断，不覆盖旧行。所有后续 checkpoint、失败补偿、冻结 run 和安全解除的协议控制写都必须先锁同一锚点。首次锚点创建以唯一键和同一事务登记完成，不允许两个执行器同时创建不同内容。

离线登记通过只证明所核结构被登记为 DIRECT_WRITE 可用。应用仍需在实际写事务内按第 5.7 节复查锚点并持共享锁；只读查询不因冻结或登记不可用停机。应用代码门禁也不能覆盖数据库账号、旧应用、ETL、手工 SQL、其他服务直连或 process 认领等旁路，因此完整 S2 仍要求外部写主体清单、旁路停止证据和 S2 总签收。

### 11.1 源态、中间态、目标态重入

MySQL DDL 隐式提交，不能承诺整份脚本事务回滚。恢复状态：

| 观测态 | 重入动作 |
|---|---|
| SOURCE_VERIFIED | 源结构/数据摘要与 manifest 完全匹配，创建控制表并开始。 |
| CONTROL_READY | 校验控制表自身定义，继续附加 DDL。 |
| DDL_PARTIAL | 从 INFORMATION_SCHEMA 比对每个对象；已满足则跳过，未知或冲突即阻断。 |
| DDL_TARGET | 跳过附加 DDL，继续批回填。 |
| BACKFILL_PARTIAL | 按最终业务键读取已提交批，摘要一致跳过，异摘要阻断，从下一个稳定键续跑。 |
| DATA_TARGET | 执行父链、唯一关系、数量、预留、流水和历史验收。 |
| CUTOVER_READY | 应用、SQL、seed、menu/dict 同版；停止旧写入口后才切权威。 |
| COMPLETE | schema version、结构/数据验收和中文结果全部完成。 |
| BLOCKED | 未知结构、未知结果、数据冲突或权限失败，停止自动重试。 |

DDL 已提交但 checkpoint 未记录时，先按对象名和完整定义比对 INFORMATION_SCHEMA。目标定义完全一致则补记 APPLIED/VERIFIED；部分对象与目标不一致、存在额外列/索引或未知连接写入则阻断。不得清库重建、删除未知对象或把“表已存在”当成功。

回填使用小批事务、唯一业务键和摘要；不使用 REPLACE，不覆盖人工种子，不重放旧计划。失败恢复从已核实 checkpoint 前向修复；需要反向处置时另建经审计补偿。

### 11.2 旧容器唯一键与列封存顺序

uk_wms_container_storage 当前限制每储位至多一个容器，storage_id/zone_id/location_status 仍是旧权威。该限制不进入目标模型：目标允许多根共享储位，不能新建同义的单容器唯一键；切换顺序：

1. 附加新的 load_node/relation/storage_info/occupancy 表，不动旧索引和旧列；
2. S2 停止所有 WMS 写、计划认领和未知旁路连接；`S2_DIRECT_WRITE_FENCE` 只关闭 F0 当前应用直接写入口，不能单独满足本步骤；
3. 回填每个旧容器为 CARRIER 节点，旧 storage_id 映射为根位置；无位置时按已批准状态映射库外/在途，未知阻断；
4. 验证每个容器恰一节点、每节点至多一个根位置或父载具、无环、无第二位置权威；
5. 部署只读写新装载权威的应用版本，旧列由应用封存为只读；确认无旧写入后运行双实例/多根并发验证；
6. 保存旧索引完整定义，作为独立 S6 操作删除 uk_wms_container_storage，增加非唯一 idx_wms_container_storage_legacy 供历史查询；不删除 storage_id/zone_id/location_status 数据；
7. 最后打开多根或可配置顶层容量能力。

顺序不能反：先开放多根会使唯一索引持续阻塞；先删索引而未完成应用切换会留下双位置权威。当前 Java 主源码未发现容器位置写入，降低本地切换风险，但不能证明外部 SQL、历史任务或其他进程未使用旧列。

若新权威已产生多根行，回退旧版本并重建唯一索引可能失败，必须以前向修复为主。

### 11.3 储位地址唯一键上线

升级先在只读预检中把旧 `row_no/col_no/level_no` 按规范十进制文本映射（`1`→`'1'`，不补零），没有巷道或深度来源时保持 `NULL`；按目标仓库与规范化五项分组查重，包含全 `NULL` 组、退役/停用行，并核对 `warehouse_id` 与设备库区父链。重复组、父链不一致、旧值无法解析或索引字节超限均写入 `wms_upgrade_issue` 并阻断；不得自动改号、补零、合并、保留一条或删除旧行。

阶段固定为：S1 只加可空地址列和暂可空 `warehouse_id`，不建 NOT NULL、生成列、CHECK 或唯一键；S2 冻结旧 WMS 写入和计划认领；S3 回填地址与父链仓库、查重、核对 `warehouse_id` 父链；仅在冻结期间且全部行有效后收紧 `NOT NULL`、创建五个生成标志/值列、CHECK 和唯一键；S4 只读验收通过后才允许 cutover。S2 的 F0 checkpoint 只证明当前应用直接写入口已关闭；外部旁路、B04/B05 映射和 S3 数据承接未完成时不得进入完整 S2 完成态。S1 或 S3 的 DDL 已提交但 checkpoint 缺失时，按 `INFORMATION_SCHEMA` 逐对象比对列类型、排序规则、空值、生成表达式、索引和 CHECK；完全一致补记 APPLIED/VERIFIED，部分对象、额外定义或冲突阻断，不重复 ALTER，不跳过唯一键进入 cutover。

隔离并发验收至少覆盖两事务同时插入相同五项组合、同时把两行改成同一组合、退役与新建使用同一组合，期望均只有一条成功，其余返回明确冲突；还需验证 `01` 与 `1`、大小写、全半角、Unicode 形式、空串与 NULL 的判定与设计一致，生成列类型/表达式/STORED/CHECK 与元数据一致，生成键值等于规范化存值的组合。并发还覆盖普通储位加入/移出/跨通道与分配，锁域变化按全序重试；父库区跨仓变更与子储位新增/改挂/地址更新共锁父链，只产生一致 `warehouse_id`，无陈旧跨仓地址。真实库执行不在本轮授权范围。

## 12. 分阶段交付

| 阶段 | 实现 | 退出条件与边界 |
|---|---|---|
| P1 | 本文件冻结模型、字段、状态、键、API、事件和 SQL/升级 manifest。 | 评审关闭命名与阻碍；不建表、不发布计划。 |
| S2-F0 | 当前公司创建、改名备注、启停和管理范围替换四类写入口及供应商 CUD/import 的 schema 登记、共享写锚点、直接冻结和有限 checkpoint。 | 固定 `WMS_DIRECT_WRITE/1` 登记、fail-closed 门禁、两写并发、冻结排空、跨 release/version run 检查及多 run 冲突通过；只关闭当前直接写入口，不关闭完整 S2/S3。 |
| P2 | 公司最小闭环（默认禁用、受控启停、名称/备注）、仓库/两类库区/储位版本、访问通道、空间业务和事件。 | 公司最小闭环、隔离库约束、非法树、通道/阻挡、空范围生命周期通过；公司关系、公司可见范围和退役暂不开放；非空清空链待决。 |
| P3 | 属性目录、类型化规则包、生效 CAS、逻辑归属和读取冻结。 | V1/V2 并发、旧物料归版、规则解释通过。 |
| P4 | 生产/存放分离、装载树、装拆、容量账、trace/audit、MATERIAL_CHANGE 和 LOAD_CHANGE。 | 原子回滚、顶层槽变化、N:M 家谱和旧容器切换隔离验证通过。 |
| P5 | 分配候选、深位通道锁、锁/占用/预留、释放证据、QUEUE attach fixture。 | 并发竞争、UNKNOWN、同通道串行、装拆容量同事务通过；真实移库/入出库不要求。 |
| P6 | 逻辑归属合并、例外和扩展目录。 | 批次幂等、冲突保持旧归属、历史兼容通过；冻结/解冻仍排除。 |
| P7 | Web/PDA、CRUD 能力矩阵、特殊视图和审计查询。 | 权限、乐观并发、真实分页和受控写入口端到端通过。 |

每阶段新增对象、字段、约束、事件和种子必须同步进入完整初始化与幂等升级；后来阶段不能留下只在开发库存在的结构。

## 13. 阻碍与待主代理决定

### 13.1 待主代理决定的业务范围冲突

D01、D02、D03 只约束各自能力：D01 约束 MATERIAL_CHANGE 正式写入口，D02 约束非空空间调整，D03 约束正式 QUEUE 挂载。公司最小闭环是本地原子 CRUD、受控启停和管理范围授权，不创建 MATERIAL_CHANGE、不执行空间调整、不挂载 QUEUE 计划，因此不受这三项阻碍，可独立进入实现与验收。上述对应能力在决定前保持禁用。

| 编号 | 阻碍 | 可选方向 |
|---|---|---|
| D01 | MATERIAL_CHANGE 的正式写入口要求已注册父业务，但本期没有真实入库、出库、移库、盘点或拆合业务。 | 增加一个经批准的最小父业务及权限；或保持 fixture-only、关闭正式物料写入能力。不得用未注册类型或伪造父业务。 |
| D02 | 非空仓库/库区拆分、收缩或调整要求真实移库链，而 P5 只约定 fixture 和后台底座。 | 本文件不新增真实移库业务；是否扩展范围待主代理决定。未决定前只允许空范围或新建空间生效，非空调整保持禁用。 |
| D03 | 默认 QUEUE 与后台驱动关闭冲突。 | 正式启用前把三开关和任务全链路纳入部署验收；或现阶段只交付查询/预览和直接 LocalAction fixture，不开放正式 attach。 |

### 13.2 实施前必须补齐的事实

| 编号 | 阻碍 | 阻止内容 |
|---|---|---|
| B01 | 尚未提供具体哪些储位属于同一访问通道、深度/访问顺序和阻挡边。 | 可建通道模型，不能开放深位分配。 |
| B02 | 体积、重量、承重、设备可达和容量测量数据未提供。 | 只能启用默认 TOP_LEVEL_LOAD，不能宣称物理尺寸或承重已验证。 |
| B03 | 实际公司、仓库、楼层、站点时区、允许单位和外部别名未提供。 | 可建空结构，不能发布依赖这些值的正式规则。 |
| B04 | 生产旧库数据清单及公司、楼层、库区父链、单位和预留持有人映射仍未批准。 | 当前 dev 20 张 WMS 表精确全空，空源演练无行级数据映射阻碍，无需编造样本；生产承接和真正 cutover 仍无法验收。 |
| B05 | 生产旧 zone_type、wms_storage.status/lock_status 的业务含义仍未形成逐项批准映射。 | 本次 dev 精确全空，无实际旧码行需映射；不证明生产为空或业务含义已批准，不关闭生产 B05。 |
| B06 | 是否有外部进程直接写 wms_container.storage_id 或依赖旧索引未证明。 | S6 删除索引前的写冻结和回退方案不能签收。 |
| B07 | 各计划 activePlanLimit、worker batch/lease、恢复预算和站点吞吐未确定。 | 不影响事件身份，影响发布参数和生产压测。 |
| B08 | P5 是否使用隔离 SnailJob 环境执行真实 QUEUE worker 未确定。 | 只能证明本地动作和事务，不能证明后台调度链。 |
| B09 | 集中规约指向的产品工作区计划事件 Skill 缺失。 | 实施入口可追溯性不足；本轮已用方案工作区同版文件补证。 |
| B10 | 目标环境是否存在可登录、可用的超级管理员账号尚未核对。 | 首家公司安全引导依赖现有 LoginHelper.isSuperAdmin 路径；若没有可用超级管理员，必须在上线前恢复或明确批准替代 bootstrap 主体，不能自动把创建者设为全范围。 |
| B11 | 尚未形成数据库账号、旧应用、ETL、手工 SQL、其他服务直连和执行中的 process 认领的外部写主体清单及逐项停止证据。 | 应用代码的 `SCHEMA_STATE_UNSUPPORTED` 和 F0 checkpoint 只能关闭当前应用直接写入口，不能签收完整 S2；B04/B05 与 S3 回填也仍未完成。 |

## 14. P1 验收和本轮验证

P1 文档评审最低条件：

1. 表名、列名、类型、唯一键、索引意图和阶段归属无重名、无双权威。
2. taskKey 不重复携带 businessType，同任务异摘要拒绝。
3. INTERNAL 事件均绑定唯一 SAME_TX 本地动作、目标状态校验器和可核对 checkpoint。
4. 深位锁域、全局锁序、锁内复核和 UNKNOWN 保持锁定可实施并可并发测试。
5. 装拆后的顶层槽数由根节点占用明细同事务重算，跨维度全部校验。
6. 三运行开关未齐时正式 attach 失败，不创建无法推进的实例。
7. DDL 已提交但 checkpoint 缺失可通过元数据比对恢复，未知结构阻断。
8. 旧容器索引删除发生在回填、应用切换、写冻结和并发验证之后，旧列保留且只读。
9. 所有阶段与 SQL/seed 同版，未把 fixture、静态检查或空库结果冒充生产验收。
10. 公司 enabled 默认 false，与 lifecycle_status 分离；最小闭环不能通过通用 CUD 修改关系、可见范围、生命周期或退役。
11. 可见范围 INHERIT/VALUE 空/CLEAR 和公司根权限均有明确测试；父键缺失、跨树和根 INHERIT/CLEAR 被拒绝。
12. seed 唯一键只含 seed_code/seed_version；同键异摘要阻断且不覆盖。
13. 一次占用事务只有一个 effectKey 头，多根多维明细使用独立 detail_key 且不冲突。
14. 通道锁成功消费后为 CONSUMED 终态，释放只接受确定无效果证据；ALREADY_CONSUMED 不撤销占用。
15. /wms/crud 一次请求可按 tableCode 分流到原子 CRUD、领域命令或计划，并返回一致的受理/生效与 revision 反馈。
16. D01-D03 只阻塞对应能力，不阻塞公司最小闭环；需正式使用对应物料变更、非空空间调整或 QUEUE 挂载时，先由主代理或用户明确决定。
17. 公司管理范围使用功能权限加超级管理员旁路或显式角色-公司 grant；首家公司由当前超级管理员创建，创建者不自动获得管理或库存可见范围。
18. 库存分页、汇总和导出的每一行均保留现有 DataPermission 的 ALL、CUSTOM、DEPT、DEPT_AND_CHILD、SELF、DEPT_AND_CHILD_OR_SELF 分支；公司 visibility 只作附加路径条件，不单独作为准入。
19. 公司管理 roleIds 是全量目标集合，空集删除全部明细；scope_revision 独立 CAS，替换、审计和撤权同事务，提交后的下一请求立即按新集合授权。
20. 五项地址可空文本、NULL 同一性与退役占组合由数据库唯一键兜底，精确比较覆盖空串/空格/大小写/全半角/Unicode/`01` 与 `1`，索引字节核算未超限，`warehouse_id` 与父链一致。
21. 旧库重复、全空重复、不可解析地址和父链不一致在唯一键创建前阻断并可分页读出；并发插入、改址及退役/新建同组合在隔离库有唯一性证据。
22. 精确定位与通用查询语义分离：通用查询字段未出现不过滤，显式空串作为字段条件，清空输入不发送筛选参数，空串不匹配 NULL；精确定位恰含五条显式字段条目，缺项、缺值键、重复拒绝，空白值归一为 NULL，`value:null` 匹配 NULL。通道配置/成员/阻挡变更与分配共用通道锁，存在活动锁或 UNKNOWN 时变更阻断。
23. 普通储位加入、移出或跨通道变更锁旧通道、新通道和储位，检查活动分配锁及 UNKNOWN；分配取得储位锁后复核成员归属，锁域变化释放并按全序重试。
24. 五个地址比较值生成列使用 `VARCHAR(64) utf8mb4_0900_bin`，按 `AS (...) STORED NOT NULL` 定义；标志和值表达式、STORED 属性及 CHECK 经元数据校验；字节级 ASCII TRIM 检查禁止绕过适配写入空串/首尾空格，生成键值与存值规范化一致。
25. 库区跨仓变更与子储位新增/改挂/地址更新共锁仓库/库区父链，跨仓锁旧新两侧；并发后父链、`warehouse_id` 和地址唯一键一致，无陈旧跨仓键。
26. 地址升级按 S1 只加可空列、S2 冻结、S3 回填查重和父链验证、冻结期收紧非空与唯一键、S4 验收后切换；S1/S3 DDL 已提交但 checkpoint 缺失时按元数据恢复。
27. F0 的公司写类别为创建、改名备注、启停（`setEnabled` 一个命令族）和管理范围替换，供应商范围为 add/update/remove/import；component/schema/release 精确为 `WMS_DIRECT_WRITE/1/INV-WMS-DIRECT-WRITE-1`，其他写入口不借证据。
28. 离线登记 SQL 精确核对控制五表、公司五表和供应商当前结构；实际 metadata 按 NULL-safe 字节比较或无歧义 `JSON_ARRAY` canonical 核对，SQL NULL 与字面 `'<NULL>'`、空串及不同大小写不混同，不区分 MySQL 归一后不可观测的缺省类型。不自动 DDL、业务 seed 或 role/menu grant，同身份同内容幂等，异内容或签名冲突阻断。
29. 缺少精确 VERIFIED 登记、控制表缺失、指纹或 default 签名冲突、状态不支持或任一 release/version 存在未安全完成 run 时，所有 F0 写返回 `SCHEMA_STATE_UNSUPPORTED`，查询继续；公司受理前拒绝不落 attempt。
30. 每个 F0 实写事务最先持锚点 `FOR SHARE` 到提交，两笔 READY 写不全局串行；所有协议控制变更先持同一锚点 `FOR UPDATE`，无先写后补锁。
31. 冻结在写者排空后同事务写 run 和 `S2_DIRECT_WRITE_FENCE/VERIFIED` checkpoint，提交才是已知冻结点；任何 release/version 的未完成 WMS run 均阻断写，多个未完成 run 判冲突，token 不是独立排空证据。
32. checkpoint 只关闭当前直接写入口，不关闭完整 S2/S3、外部旁路、B04/B05 或 S2 总签收；持久冻结点成立后，后续失败/UNKNOWN 不自动解除。freeze 提交结果未知须回读 run/checkpoint 且不继续 S3；明确整事务回滚且无冻结事实时不宣称排空成功，不视为自动解冻。
33. 控制表 DDL 状态枚举不扩展；供应商普通 CUD 有事务，公司其他失败、审计、幂等和 UNKNOWN 原语义不回退。
34. 失败公司命令同 commandKey 重放保留原始持久错误，不要求固定 `COMMAND_ALREADY_FAILED`；成功重放保持幂等，异输入返回 `INPUT_HASH_CONFLICT`。
35. 目录 SQL 只按当前哈希和限定矩阵证据关闭结构切片；不得把旧 verify `D2A065F3...` 的 55 项矩阵绑定到当前 `C5E12515...`。Catalog 菜单 SQL 的 105 项断言和只读 consumer 源码切片分别限定接受，真实后端和完整 TG08 未验收不关闭动态目录父任务。
36. P3 独立 12 表结构、core/init 同版元数据、规则 Java 首切片、空间 S3 SQL 和 resumed Harness todo 均按各自限定证据记录。全 WMS 历史结果为 `105 tests / 103 PASS / 1 failure / 1 error`：跨 scope 并发 publish 仍 20 秒超时，新 fence 历史 `WMS_HISTORICAL-1` 登记跨用例未清；随后两项修复已由主读日志复核 106 项全绿，不反向关闭完整 P3，完整 typed DSL、无效 storage 快照和任务绑定仍须另验。
37. 规则 publish-only 不隐式需要 query。Scope Guard 已限定接受五类 scope、允许空 `storageIds`、事务内当前 DB 授权 S 锁、physical 链 S 锁及 storage 快照 S 锁；产品 schema 缺 `zone_kind`/`storage.lifecycle_status` 必须 fail-closed，fixture 不能冒充正式结构。21 个交付文件及最终有效 `82/0/0/0` XML 已由主接受，其中 `82=69+13`；尚未新 package，106 项仍只说明旧版本，完整 P3 保持未关闭。
38. 纯 typed condition kernel 使用 §5.3 的封闭 schema/节点/类型/空值/限额合同；仅 `TRUE` 命中，`IN` membership 与 `ANY/ALL` 的职责分开。最新 79 项原始 Surefire/Maven 证据属于限定子切片，前一轮 66 项保留为历史版本，既有 WMS 106 项另属整体门禁；三者分别记录，`66`/`79` 都不与 `106` 相加。该 `done` 只关闭纯条件 kernel，不证明接入、HTTP、发布、核心初始化或任务绑定。
39. 空间 foundation 10 表当前目标 DDL 的证据只绑定 source `c266744e…`、verify `e6592ed8…` 与 MySQL 8.0.45 隔离 `capacity-fix-01/run02` 的 88 项断言。10/109/51/24 和十表首执行为空来自该版本；权限/对象冲突等未重跑部分复用旧结构证据时，必须写明旧版本适用范围。该十表后续已单独完成并接受 core/init 同版整合，见第 40 项；独立 DDL 与整合证据分别保留，不用新表数覆盖旧断言。
40. 空间十表 core/init 整合已按 MySQL 8.0.45 限定接受：`core 55 / init 159 / WMS 55 / menu 242 / role-menu 133`，三路十表结构与 verify 同版，十表无数据；原 `45` WMS 与旧 `149` 表结构和 seed 摘要保持一致。它只关闭本切片，正式库仍未部署。
41. P4 material 最新目标 DDL 为 11 表、220 列、85 条索引列元数据、34 个 CHECK；当前受影响矩阵 24/24 符合预期。core/init 已实测并获主接受为 `core 66 / init 170 / WMS 66 / menu 242 / role-menu 133`，三路完整结构与 verify 同版，旧 `55/159` 结构和数据不变；两份当前 SHA-256 为 `194ed250…`/`252cded3…`。旧 78/86 仅复用未变化部分，完整 P4 仍未完成。
42. 规则结果 kernel 的 56 项 Maven、条件 kernel 的 79 项和装载 kernel 的 23 项分别绑定纯内存版本；SSE MVC 12 项与 Scope Guard 最终有效 82 项另属各自集成范围。上述计数与历史全 WMS `106`、旧 `38` 个 reactor 模块 package 不得相加为当前整体通过。
43. Scope Guard 的 RR 候选读取只用于收集 ID；锁后必须用 current read 重新校验目标、父链、授权和生命周期，不能把首次 MVCC 快照当作锁后事实。目标 zone 必须精确匹配请求类型，父链只使用已锁 ID 集合；缺目标列返回 409，NULL/未知状态返回 409；发布目标为 ACTIVE，历史读取接受 ACTIVE/RETIRING/RETIRED。21 个交付文件及最终有效 `82/0/0/0` XML 已由主接受；`82` 必须是首轮 `69` 项通过加 scopeGuard `13` 项复验，不得写成首轮整套全绿。
44. SSE 认证 MVC 通过不等同新 JAR LAN 通过。Gauss 当前正使用唯一根 Maven 槽执行 15 个 WMS 测试类加 SSE 12 项的全当前回归并准备新 package，结果未出。新包生成并核验哈希后，必须重测无 token、失效/过期 token、有效流、自定义路径和普通 JSON 错误；真实 OAuth provider、真实角色授权与 live 启动仍未验收。

本轮已完成：读取集中规约、中文写作、任务图、实施与文本编辑入口，复核 D/B 当前工作树及空间、P4、结果/装载内核、SSE 和 scope Guard 的原始报告；只修改授权文档，不执行产品代码、SQL、Maven、独立 Review 或数据库操作。

当前文档以 [实施前沿](C:/code/company/innolux-iws/work/implementation-frontier-20261029/report.md) 作为状态导航：已验切片、在途执行和父任务边界分列记录。历史 105 项失败、旧版本 hash、隔离 SQL 和 LAN 证据继续保留；它们不因本轮文档更新而重跑、升级为当前版本或扩大到 live 部署。
