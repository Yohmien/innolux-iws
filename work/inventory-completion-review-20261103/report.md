# IWS 在库管理 P1—P7 完成交付复评

评审日期：2026-11-03。对象：[宣称完成的实施会话](codex://threads/01a19bf9-f81c-72b1-93b3-6f135c8c0c0c)及本地当前工作树。由主代理独立完成，没有启动子代理。

## 1. 结论

**复评时不能接受“本期 P1—P7 全部完成”，当时 `INV-I-01 = done` 缺乏成立条件。** 本轮确认 8 项需整改问题：6 项 P1、2 项 P2。7 项有针对性执行复现，另一项由正式挂载授权链和生产策略注册情况直接确认。

上一轮缺列、缺表、查询权限、冻结可用量、规则版本分组、任务键筛选和 EVALUATED 重放问题已有实际修正，应保留这些成果。新增空间配置、退役、规则发布和合并持久编排也有实现；问题集中在正式组合路径、空间不变量、异步身份以及验收替代配置，不能用测试总数或页面可打开来覆盖。

本轮没有修复业务源码、修改任务状态或覆盖原交接文档，没有提交、推送、部署或写真实开发库。只增加本目录评审材料和隔离复现程序。

> **整改结果：** 2026-11-03 复评后的修复与正式执行证据见 [复评整改与验收摘要](../verification-current-20261103/summary.md)。REV-01—REV-08 已全部关闭；全 WMS `481/481`、根 reactor `43/43` clean `test-compile`、全应用 `43/43` package、Web `264/264`、空间生命周期 `70/70` 通过。最终 JAR `4C2515415A427CF1AC1A516DA05A408F7491B8E227CBEB9DCC6D5552BD95EBC3` 已完成普通账号本机与 `192.168.1.21:18000` LAN 代理浏览器验收，均为 13 checks、20 API、0 错误。当前源码快照另存于 [整改后源码清单](post-remediation-source-manifest.json)。本报告保留原始问题与关闭条件，不覆盖复评时的证据边界。

## 2. 问题清单

| 编号 | 级别 | 触发条件 | 结果 | 证据类型 |
|---|---|---|---|---|
| REV-01 | P1 | 空间计划由无 HTTP 登录会话的后台 worker 执行 VALIDATE | 公司权限仍从当前 LoginHelper 读取，合法已受理计划不能推进 | 受信上下文服务复现、生产调用链 |
| REV-02 | P1 | 正式配置编译、激活带属性的规则 | 生产来源 provider 为 0，精确来源解析失败；测试注册了替代来源 | 生产配置启动复现、全 main 源码搜索 |
| REV-03 | P1 | 储位直接挂物料库区，或库区跳层/同类嵌套 | 后端接受不合法空间树，后续规则范围守卫与写入口语义冲突 | 隔离 MySQL 复现 |
| REV-04 | P1 | 有库存库区通过通用 UPDATE 更换 warehouseId | 未清空便跨仓，子储位 warehouse_id 留在原仓库 | 隔离 MySQL 复现 |
| REV-05 | P1 | VALIDATE 后、APPLY 前父仓库被另一个事务退役 | APPLY 只锁父行、不复核生命周期，产生 RETIRED 父节点下的 ACTIVE 子节点 | 隔离 MySQL 分事务复现 |
| REV-06 | P1 | 有公司范围和业务功能权限、无 process:object:all 的普通用户启动 WMS 计划 | 没有 WMS ProcessAccessPolicy，正式 attach 授权拒绝 | 正式挂载调用链、生产策略注册搜索 |
| REV-07 | P2 | 空间退役计划首事件 START → CHECKED | 状态校验表只允许 START → VALIDATED，退役首步固定失败 | 生产状态校验器直接执行 |
| REV-08 | P2 | 对已 RETIRED 空间对象提交符合当前 revision 的 UPDATE | 更新 SQL 无条件写回 ACTIVE，普通修改隐式复活对象 | 隔离 MySQL 复现 |

### REV-01：后台执行身份与授权合同未接通

[空间 VALIDATE Action](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/process/WmsSpaceValidateAction.java:77)直接调用领域校验；[resolve](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/service/impl/WmsSpaceChangeServiceImpl.java:397)继续调用当前会话式公司授权。[公司策略](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/support/company/WmsCompanyAccessPolicy.java:306)按 `LoginHelper.getUserId()` 查询当前角色；[worker](C:/code/company/innolux-iws/iws-modules/iws-process/src/main/java/com/innolux/iws/process/runtime/PlanEventWorkerServiceImpl.java:76)仅设置 `ProcessTrustedInvocation`，该标记不恢复发起人登录，也不提供持久执行授权。

复现六用真实空间 Service、真实公司策略，在无登录的受信上下文执行已有单据 VALIDATE，返回 `COMPANY_SCOPE_DENIED`。其中公司范围头和角色 Mapper 是模拟对象，用于隔离“没有用户身份”的问题；这不是完整 worker/真实授权表端到端测试。生产 worker 调用链与复现前提一致，不能把原有 mock 整个公司策略的空间测试当成这一链已通过。

修正：受理时冻结发起人、公司、对象、输入、功能权限及执行授权依据；worker 通过业务专用受信执行上下文读取和复核持久授权。明确权限撤销后的业务决定，复用已有平台信任边界。不要伪造 HTTP 登录或将所有无登录调用自动放行。执行者审计应区分后台主体与发起人。

### REV-02：生产属性来源仍为空，测试配置不能代替生产配置

[生产配置](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/rule/binding/WmsRuleBindingConfiguration.java:21)从容器收集 `RuleAttributeSourceProvider`，但当前生产 main 代码没有一个真实实现；[registry](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/rule/binding/RuleAttributeSourceRegistry.java:57)缺失时明确拒绝。[规则绑定服务](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/rule/binding/WmsRuleBindingService.java:67)逐属性解析来源；`requireReady` 又要求绑定列表非空，所以无属性规则也不是可绕过的正式交付方案。

复现二直接启动生产 `WmsRuleBindingConfiguration`，provider 数量为 `0`，来源解析报 `RULE_SOURCE_CONTRACT_NOT_FOUND`。同时，[测试配置](C:/code/company/innolux-iws/iws-modules/iws-wms/src/test/java/com/innolux/iws/wms/rule/WmsRuleTestContext.java:138)注册的用途、作用域版本和 `TestRuleAttributeSourceProvider` 并非生产组合。因此已有发布测试能证明部分事务/摘要逻辑，不能证明正式配置可发布真实规则。

修正：交付本期实际需要的最小生产来源、精确用途选择、属性目录和事实读取，明确空库属性登记路径。将真实生产 Bean、正式 SQL、真实草稿/发布入口放入同一个集成用例；不能仅加测试专用 Bean。公司、仓库、物料库区、设备库区作用域是否属于当前交付，应按已接受设计和任务逐项对齐，不能让 STORAGE FILTER/SORT 的注册替代其余作用域完成状态。

### REV-03：空间写入口没有维护 D01 的严格父链

[库区解析](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/service/impl/WmsSpaceChangeServiceImpl.java:427)只检查 zoneKind 枚举、自身父节点和同仓库，没有要求 MATERIAL 无父库区、DEVICE 必须直属 MATERIAL；[储位解析](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/service/impl/WmsSpaceChangeServiceImpl.java:473)没有要求所属库区为 DEVICE。

复现三运行现有成功用例 [createsWarehouseZoneStorageAndSwitchesOneCurrentVersion](C:/code/company/innolux-iws/iws-modules/iws-wms/src/test/java/com/innolux/iws/wms/rule/WmsSpaceChangeIntegrationTest.java:87)，数据库确实产生一个直属 MATERIAL 的储位。该测试断言与[原设计 D01](C:/code/company/innolux-iws-inventory-design/docs/inventory-management-design-20261026.md:21)相反。后续 [规则范围守卫](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/rule/support/WmsRuleScopeGuard.java:285)又检查严格父链，导致“前面创建成功、后面规则不可用”。

此外，空间 Service 的 `parentVersionId` 对所有 ZONE 都指向 WAREHOUSE，未依据 DEVICE 的 parentZoneId 指向物料库区版本，版本父链也需要一并对齐。

修正：将空间类型、父类型和父链校验合并为一个有类型的领域规则，供配置、规则范围、分配和读模型共同调用。补合法六层树以及每一种跳层、同类嵌套、跨仓父链的反向用例。

### REV-04：通用 UPDATE 绕过非空调整关闭边界，并写出跨仓不一致

[ZONE 更新 SQL](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/service/impl/WmsSpaceChangeServiceImpl.java:570)允许修改 warehouse_id、parent_zone_id、zone_kind；普通 UPDATE 不经过占用/清空门禁，也不迁移整棵子树。`lockParents` 只覆盖解析出来的新侧父节点，不能替代 D20 的旧新两侧固定锁序。

复现四先插入储位中的实存库存，再修改所属库区 warehouseId，操作成功；结果库区 warehouse_id 为 `500014`，子储位 warehouse_id 仍为 `500002`。公司归属、地址唯一性、规则父链和查询范围由此不再描述同一个物理位置。

修正：普通配置 UPDATE 只接收本期允许的属性；父链、范围、种类变更走明确领域命令。本期非空调整未授权实现，所有相关写入口应拒绝，不能只关闭专用拆分/移库按钮。空范围调整也需锁旧新父链、复核不可拆单元、原子维护子树冗余字段与版本历史。

### REV-05：VALIDATE/APPLY 之间的变化没有在写事务中复核

[APPLY](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/service/impl/WmsSpaceChangeServiceImpl.java:169)直接使用已冻结 resolved 配置；[父锁](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/service/impl/WmsSpaceChangeServiceImpl.java:664)只执行锁行查询，没有核验父节点仍 ACTIVE、归属和父版本仍符合预期。

复现七按三个独立事务执行：新库区 VALIDATE → 空父仓库退役 → 原库区 APPLY。最终父仓库 `RETIRED`、子库区 `ACTIVE`，不需要线程竞争或修改数据库状态来伪造结果。

修正：VALIDATE 保存意图与期望版本，不替代 APPLY 的写前门禁。APPLY 取得完整固定顺序锁后，复核父链、生命周期、公司/授权、占用、开放效果和版本；变化时返回明确冲突，不悄悄采用最新父节点。

### REV-06：普通角色与 WMS 计划对象授权之间缺少适配

[正式 attach](C:/code/company/innolux-iws/iws-modules/iws-process/src/main/java/com/innolux/iws/process/service/impl/PlanInstanceServiceImpl.java:211)先校验调度对象授权。[ProcessObjectAuthorizer](C:/code/company/innolux-iws/iws-modules/iws-process/src/main/java/com/innolux/iws/process/runtime/ProcessObjectAuthorizer.java:157)要求 `process:object:all` 或注册策略明确允许。当前生产策略只有 APK 的实现，且 APK 策略明确拒绝非 APK 调度；WMS 没有 `ProcessAccessPolicy`。

因此，仅授予 WMS 功能权限和公司范围的普通业务用户不能走正式空间、规则发布或逻辑合并挂载路径。超级管理员冒烟、mock PlanInstanceService 或 mock ObjectAuthorizer 会掩盖此问题。

修正：为正式 WMS 调度、业务类型、实例、监督范围和事件实现对象授权适配，复用公司及现有对象范围规则。不要通过给普通用户授全局 `process:object:all` 来修通流程。最小验收同时覆盖“本公司可执行”“其他公司拒绝”“只有查询权限拒绝变更”。

### REV-07：退役首迁移不在状态合同里

[退役定义](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/process/WmsSpaceChangePlanDefinitionFactory.java:90)要求 `START → CHECKED → END`；[状态校验器](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/process/WmsSpaceChangeStateValidator.java:24)用一对一 Map 定义 `START → VALIDATED`，没有 START → CHECKED。复现一调用真实校验器，直接返回 `SPACE_STATE_TRANSITION`。

修正：按计划类型或合法边集合判断转移；同时验证空间变更和退役的完整正式执行路径，不能只直接调用领域退役方法后断言数据库变成 RETIRED。

### REV-08：普通更新可隐式复活已退役对象

[更新 SQL](C:/code/company/innolux-iws/iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/space/change/service/impl/WmsSpaceChangeServiceImpl.java:615)无条件写 `lifecycle_status = 'ACTIVE'`。加载、锁定目标和 UPDATE 分支没有先拒绝 RETIRED 对象。WAREHOUSE、ZONE 更新也存在同类写法。

复现五通过真实退役 Service 将储位退役至 revision 2，再提交 expectedRevision=2 的名称更新，最终生命周期变回 ACTIVE。

修正：退役对象更新应按已接受生命周期合同拒绝；若未来需要恢复，应是独立授权业务，重检父链、引用、唯一性、规则与新版本。普通属性更新不得决定生命周期恢复。

## 3. 上一轮缺陷的关闭情况

以下是本轮源码核对及原交付日志能支持的范围，不代表本轮重新运行全 WMS 464 项测试。

| 原问题 | 当前核对结果 |
|---|---|
| F01 绑定字段缺失 | core、规则 SQL 已有 attribute_bindings_json；不能继续沿用旧缺列结论 |
| F02 六张分配/预留表缺失 | 正式 core/empty init 已纳入相关表；当前初始化可执行性见后文 |
| F03 装载树叶子权限 | loadTree 已调用 selectVisibleInventoryIds 再过滤叶子，并保留结构载具 |
| F04 详情 trace 独立权限 | getMaterialDetail 已按 trace 查询权限控制摘要 |
| F05 冻结可用量 | 汇总和明细 SQL 已将冻结库存 available_qty 置零 |
| F06 跨规则版本合并 | 汇总输出及分组已包含 logical_scope_version_id |
| F07 trace taskKey | Mapper 已处理 taskKey，包括显式空字符串 |
| F08 EVALUATED 稳定身份 | reserve 在所有既有决策的状态分支之前调用 replay 校验 |

持久 `wms_business_task`、屏障、合并父/批次编排和空间/规则计划目录也确已增加；不能再简单评价为“只有内核/没有持久编排”。尚需证明的是正式生产组合能够使用，以及领域事实是否符合原合同。

## 4. 测试与空库证据边界

本轮执行的 [run-probe.ps1](C:/code/company/innolux-iws/work/inventory-completion-review-20261103/run-probe.ps1)将当前空间 Service、状态校验器、生产规则配置/registry 和空间测试源码重新编译到本目录独立 classes，优先加载这些类；其他依赖复用现有 reactor 产物。数据库由 Testcontainers 使用 `mysql:8.0.45` 新建并在结束后清理；未连接真实开发库写入。

[probe.log](C:/code/company/innolux-iws/work/inventory-completion-review-20261103/probe.log)确认七种问题行为。复现程序退出 0 表示“七种当前错误行为已确认”，**不是业务验收通过**。空间层级、跨仓、复活和父退役窗口验证使用真实隔离 MySQL；为定位领域不变量，这部分沿用原测试的公司策略 mock；后台身份单独使用真实公司策略与模拟 Mapper 验证，证据边界不可混淆。

已有 [交付汇总](C:/code/company/innolux-iws/work/verification-current-20261102/summary.md)与全 WMS 日志确实记录 464 项零失败、零跳过，不能无依据称数字伪造。但存在以下可核实的验收失真：

1. 空间成功测试直接接受 MATERIAL → STORAGE，测试预期违反 D01。
2. 空间集成测试 mock 整个公司策略；规则测试替换用途和来源。它们没有验证生产装配及后台身份。
3. 退役测试直调 check/retire Service，绕过会失败的正式状态迁移。
4. [浏览器记录](C:/code/company/innolux-iws/work/verification-current-20261102/browser-real/summary.json)证明登录、页面查询和合并对话框可打开，没有空间配置提交、规则发布或合并启动接口的执行证据，也没有相应最终领域事实核对。
5. 同库重启后登录成功不等于“运行中业务在重启后恢复完成”。需留下中间态单据/事件、重新启动并核对其恢复结果。

这些是客观覆盖和证据适用范围问题，不据此推断实施者主观偷懒或造假。以总测试数替代任务验收、把错误实现写成成功预期，确实会导致“为了通过而通过”的效果。

空库单独复核由 [verify-empty-init.ps1](C:/code/company/innolux-iws/work/inventory-completion-review-20261103/verify-empty-init.ps1)执行：新建无宿主端口映射的独立 MySQL 8.0.45，SQL 目录只读挂载，唯一目标库为专用 `iws_empty_review_20261103`，直接运行当前正式 empty init SQL；执行后清理该专用容器。结果及源码 SHA256 分别保存到 [empty-init.log](C:/code/company/innolux-iws/work/inventory-completion-review-20261103/empty-init.log) 和 [empty-init-source-hashes.json](C:/code/company/innolux-iws/work/inventory-completion-review-20261103/empty-init-source-hashes.json)。**本轮退出码 0，最终验收得到 181 张表、77 张 WMS 表、256 个菜单，输出“空库全量初始化完成”。** 这证明当前 SQL 的标准空库入口可执行；本轮未用此新库启动应用或验收写业务，不能扩大为完整功能通过。

真实 dev 配置的只读探测结果：MySQL 8.0.45，WMS 表 20 张；`SHOW GRANTS` 为 SELECT/SHOW 权限。本轮没有把隔离库 77 张表当作真实开发库已经部署，也没有修改真实库。

## 5. 架构与规划判断

复用 iws-process、SAME_TX、稳定键、checkpoint、业务任务和屏障的方向正确，没必要另建调度或流程平台。当前主要设计问题是职责和权威重复：空间 Service 用通用 Map 同时处理三类对象、三种操作、权限、父链、SQL、版本和退役；规则范围另有一套严格父链，测试配置又维护一套来源与用途。约束在不同入口中各自解释，已经造成 REV-03、REV-04、REV-05、REV-08。

建议在 iws-wms 内重构为有类型的空间意图、共享父链/锁/生命周期校验、事务用例和持久化层；规则单独维护生产用途与来源注册，执行身份由业务接缝适配平台。按这些职责建立少量深模块，不按每张表拆事务、不为减少行数机械拆类。版本快照、UNKNOWN、幂等和审计是当前业务合同，不能以“无历史兼容要求”为由删除。

与原规划的实质偏离是：允许跳层空间树、普通 UPDATE 取得范围调整能力、普通 UPDATE 复活退役对象、缺失生产来源却关闭规则任务，以及用冒烟结果关闭整体验收。Logo 与统一管理员账号属于用户指定扩展，不列为擅自扩围。

真实内部 MOVE、非空拆分清空链、真实入出库和正式物料父业务写入仍按后续范围处理，不要求为本次修复扩围；但其能力关闭必须覆盖通用 UPDATE 等旁路，不能仅依赖前端按钮。

## 6. 后续执行交接及关闭条件

建议重新打开 `INV-I-01` 和受影响的 P2/P3/P6/P7 正式链路验收，保留已经成立的 SQL/内核/查询修复结论。本轮只在此记录建议，没有改动原任务图状态。

| 顺序 | 工作 | 必须交付的可验证结果 |
|---|---|---|
| 1 | 修空间不变量与意图边界 | 合法六层树可创建；非法父链拒绝；非空改挂拒绝；跨仓旧新锁序与子树一致；退役对象不可 UPDATE 复活；VALIDATE/APPLY 窗口冲突可复现并拒绝 |
| 2 | 接通公司/对象/后台执行授权 | 使用正式 WMS ProcessAccessPolicy 和真实授权表；本公司普通角色经正式 API 可挂载并由无 HTTP 会话 worker 完成，跨公司拒绝；权限撤销、角色停用、重试和重启语义明确 |
| 3 | 完成生产规则绑定与发布 | 正式来源、目录、精确用途版本、物料/空间事实读取；真实草稿经生产发布计划生效；目标包/指针、摘要、失败回滚与执行冻结均核对 |
| 4 | 修空间状态合同，补正式退役链 | 经正式 API 和发布定义走 CHECK_RETIREMENT → RETIRE_LOCATION；开放引用/UNKNOWN 拒绝，事件后写业务与 checkpoint 同事务回滚 |
| 5 | 合并与恢复组合验收 | 正式逻辑合并启动而非只调底层 Service；核对固定批次、来源冲突保留、父屏障、逐条归属历史和安全退役；中间态重启后由真实 worker 收敛 |
| 6 | 同版验收并同步状态 | 绑定源码散列、正式 SQL、JAR 和前端构建；完成真实写流程及只读查询，复跑必要回归；再同步合同、设计、SQL/Web 计划、任务图、前沿记录和交接，不能只追加“全部完成” |

可纳入计划事件的业务规范：空间配置的校验/应用/核验、退役检查/退役、规则校验/激活、逻辑合并固定批次/父收敛。普通分页查询和只读权限过滤无需计划化；授权、父链和生命周期不变量必须由领域写事务维护，不能只在某个计划动作中检查一次。

验收断言必须来自 D01、D08—D09、D20 及已接受生命周期/授权合同。以正确领域事实、未完成和 UNKNOWN 的阻断、失败后的数据库原子状态为判据；不得通过修改断言迁就现有错误行为。
