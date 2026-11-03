# IWS 在库管理 P1—P7 重新评审与纠偏交接

日期：2026-11-02，Asia/Shanghai。复评时结论：**当时交付不能按完整 P1—P7 验收，空库脚本尚未与当前业务代码对齐。** 已有不少真实 MySQL、并发、回滚和 LAN 证据，不能据此跳过整合缺口。本轮只做评审、验证和文档纠偏，未修改业务代码、SQL 或测试预期。

> **2026-11-03 本期整合验收：** R1—R6 已按当前标准化范围收口，复评 REV-01—REV-08 已关闭。正式空库入口生成 `181` 表、`77` 张 WMS 表和 `256` 条菜单；最终 JAR `4C2515415A427CF1AC1A516DA05A408F7491B8E227CBEB9DCC6D5552BD95EBC3` 已从该库启动、登录、调用空间/规则/计划接口并完成同库重启。全 WMS `481/481`、Web Vitest `264/264`、空间生命周期 `70/70`，普通账号本机浏览器和 `192.168.1.21:18000` LAN 代理浏览器均通过。真实 MOVE、非空空间清空和现场入出库仍按后续范围关闭，不作为本期完成前置。详见第 9 节。

来源任务：[实施 IWS 在库管理 P1-P7](codex://threads/01a18752-bd19-72d3-b3ba-0202e5cdcc57)。已逐页核对该任务历史中的用户消息及关键交付，读取原始方案、P1 合同、任务图、2026-10-29 交接、当前前沿和相关源码。子代理未交付有效结论；用户要求停止使用子代理后，由主代理继续审查。本文不宣称完成独立双人 Review。

## 1. 当前边界与证据

本次用户明确允许 `iws-wms` 大规模重构，不要求历史版本兼容；这是后续设计的约束，**不是本轮直接实施重构、修改真实数据库或部署的授权**。logo 与统一管理员账号是用户指定扩展，不列为擅自扩围。用户通过角色管理/查看多家公司，不增加固定 `SysUser.companyId`，不把部门当公司。

| 工作区 | 分支 / HEAD | 本轮处理 |
| --- | --- | --- |
| `C:/code/company/innolux-iws` | `main` / `84bc57bc56739e0818a13515d3d5ae183a0e24dd` | 保留全部未提交实现，新增本评审工件并在前沿增加纠偏入口 |
| `C:/code/company/ui/innolux-iws-web` | `main` / `0c7ec33171c3e7cfff575d308211ec435a89f168` | 只读审查，运行已有 Vitest |
| `C:/code/company/ui/innolux-iws-pda-plus` | `master` / `c5d2e67d2706614e97e863c6e69c85f5de424d99` | 保留定位页等已有改动，未做真机验收 |
| `C:/code/company/innolux-iws-inventory-design` | `codex/inventory-design-20261026` / `2d15d09b219a3f48cd53b20012f6a2ca2548a6ef` | 原始方案保留，任务图增加本次纠偏指针 |

本轮事实与命令见 [evidence.json](evidence.json)、[测试编译日志](test-compile.log)、[内核测试日志](kernel-tests.log)、[Web 测试日志](web-tests.log)。SQL 名称统计为文本静态提取，不是执行后的实际表数。

- JDK 21.0.12.1，根 reactor `test-compile`：17 模块成功，增量检查；未执行测试，不能称为全量 clean build。
- 五个现有纯内核测试类：172 项，0 failure/error/skipped。只证明条件、结果、装载树、分配和合并规划内核。
- Web Vitest：31 文件、248 项通过。不能替代页面行为、HTTP 或后端鉴权验收。
- MCP 从当前 Spring `dev` 配置解析 `innolux-iws`，首查 `SHOW GRANTS`：仅 SELECT / SHOW DATABASES / SHOW VIEW。只读确认 MySQL 8.0.45、124 张基础表、20 张旧 WMS 表；新增模型未部署。未查询无关业务报文，未写库。
- P3 最新 15 文件 manifest 全部匹配。P7 的 93 文件 manifest 中，91 文件仍匹配，变化的是前沿和任务图两份文档；不能将这两项文档变化误判成产品源码失效。
- 未重新执行全新 MySQL 初始化、完整集成套件、新 LAN/浏览器、PDA 真机、部署、commit/push；以下缺陷以已核对的源码调用和 SQL 对照为证据，不包装为新运行时复现。

## 2. 优先修复的发现

### F01 · P1 · 新空库缺少当前规则实体必需的绑定列

[empty init](../../script/sql/iws_init_from_empty.sql:3124) 的 `wms_rule_version` 没有 `attribute_bindings_json`，[core](../../script/sql/iws_wms_smt_core.sql) 也未同步；[当前实体](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/rule/domain/WmsRuleVersion.java:47) 已映射该字段。[草稿服务](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/rule/service/impl/WmsRuleDraftServiceImpl.java:143) 使用 BaseMapper 的实体查询读回版本，激活还会写绑定快照。

触发：只执行正式空库入口后调用规则创建/版本读回/激活。ORM 生成 SQL 引用不存在的列，规则链不能正常工作。独立 `iws_inventory_rule_attribute_bindings.sql` 存在，但空库入口没有执行它。修复必须同时覆盖 core、empty init、实体和真实入口测试，不能只在测试中补列。

### F02 · P1 · 分配与 UNKNOWN 正式代码依赖空库入口未创建的表

正式 init/core 缺少 `wms_access_channel_lock`、`wms_allocation_candidate`、`wms_allocation_decision`、`wms_allocation_lock`、`wms_allocation_release`、`wms_inventory_reservation`。六表目标见 [allocation foundation](../../script/sql/iws_inventory_management_allocation_foundation.sql:57)。当前 init 已有 `wms_business_task`，不能继续沿用旧前沿“任务表尚未整合”的描述。

[分配服务](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/allocation/reservation/service/impl/WmsAllocationReservationServiceImpl.java:103) 与 [UNKNOWN Mapper](../../iws-modules/iws-wms/src/main/resources/mapper/wms/allocation/effect/investigation/WmsAllocationEffectUnknownMapper.xml:41) 直接访问这些表。触发：空库初始化后执行锁位，或有查证权限的用户打开 UNKNOWN 查询。结果是缺表错误；不能以“尚未部署”关闭源码与初始化不一致问题。六表都需进入统一空库交付，正式目录/挂载 seed 另按已接线能力审查。

### F03 · P1 · 物料详情展开装载树绕过逐库存行权限

[详情入口](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/inventory/query/service/impl/WmsInventoryReadServiceImpl.java:192) 先校验目标库存行，但 [loadTree](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/inventory/query/service/impl/WmsInventoryReadServiceImpl.java:534) 随后返回根节点下全部物料。[selectLoadSubtree](../../iws-modules/iws-wms/src/main/resources/mapper/wms/inventory/query/WmsInventoryReadMapper.xml:344) 返回兄弟库存的 ID、库存单元和料号；对应 [Mapper 方法](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/inventory/query/mapper/WmsInventoryReadMapper.java:81) 没有 DataPermission，也没有传入当前范围。

触发：同一载具混放 A、B 部门库存，当前用户只通过 A 行的 SELF/DEPT/CUSTOM 数据权限。打开 A 的详情即可取得 B 的标识，即使 B 的直接列表/详情被拒绝。同储位可见并不代表同载具所有库存行可见，主方案 §12 明确禁止这类泄露。

修复：详情聚合中每个叶子库存都应用相同的当前授权；受限分支应过滤或返回不泄露身份的受限节点，不能因根节点可见扩大权限。补“可见叶子 + 不可见兄弟叶子”的普通角色测试，不能只用超级管理员或双公司各一棵树。

### F04 · P2 · 物料详情绕过独立追踪查询权限

[getMaterialDetail](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/inventory/query/service/impl/WmsInventoryReadServiceImpl.java:212) 无条件调用 `selectTraceSummaries` 并把最近十条追踪记录返回。该入口只要求物料查询权限；独立 [queryTraces](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/inventory/query/service/impl/WmsInventoryReadServiceImpl.java:230) 则明确要求 `PERMISSION_TRACE_QUERY`。

触发：普通用户有物料查询权限、没有 trace 查询权限，但满足对应行数据范围。仍能通过物料详情看到业务类别、操作人、执行主体、证据引用等追踪摘要。修复应在聚合入口按 trace 功能权限裁剪，或明确统一权限合同后同步所有入口；页面隐藏追踪按钮不够。

### F05 · P2 · 可用量口径错误，现有测试把错误结果固化

[汇总 SQL](../../iws-modules/iws-wms/src/main/resources/mapper/wms/inventory/query/WmsInventoryReadMapper.xml:145) 和明细 SQL 使用 `inventory_qty - reserved_qty` 作为 `available_qty`，未扣除冻结/扣留限制。主方案 §6 要求按有效预留与扣留集合去重计算；排除冻结业务开发不等于忽略已有冻结事实。

[现有集成测试](../../iws-modules/iws-wms/src/test/java/com/innolux/iws/wms/inventory/query/WmsInventoryReadIntegrationTest.java:170) 同时断言“两条均冻结”和“仍可用 9.500000”。这证明测试预期追随了当前公式，没有独立验证业务不变量。不能据此证明故意造假，但该用例确实会放过错误实现。

修复：先冻结可用量、冻结量、预留量的集合定义；测试覆盖冻结与预留重叠、全冻结、部分预留、失效预留。依赖的事实底座未齐时，应返回不可计算状态，不能填零或用近似余额冒充可用量。

### F06 · P2 · 汇总跨规则归属版本合并

[汇总分组](../../iws-modules/iws-wms/src/main/resources/mapper/wms/inventory/query/WmsInventoryReadMapper.xml:150) 只按物料、仓库、储位和单位分组，没有关联当前逻辑归属及规则版本。触发：同料号同位置同单位的物料分别保留旧、新逻辑归属版本；页面将其合成一行，无法按冻结规则的不同分组语义解释。主方案 §6 明确默认不跨版本聚合。

修复：使用规则版本、规范化分组值和单位形成聚合键，并按物料单元去重。P4-03 不能以当前旧库存表的汇总页替代完整聚合验收。

### F07 · P2 · 追踪页提供任务键筛选但静默丢弃条件

[前端元数据](C:/code/company/ui/innolux-iws-web/src/api/wms/inventory/readonly.ts:160) 将 trace 的 `taskKey` 标为可筛选；[trace 请求适配](C:/code/company/ui/innolux-iws-web/src/api/wms/inventory/readonly.ts:325) 未发送该字段，前后端 TraceQuery 和 SQL 也未定义相应条件。用户按任务键查询后会得到其他任务结果。

修复：补齐完整查询合同或关闭该字段的筛选能力，不能只验证表格发出了请求。相邻的 `valueOf` 还把显式空串统一丢成未提供，与既定通用空串查询合同不同，需一并明确实际支持语义。

### F08 · P2 · EVALUATED 到锁位的路径漏掉稳定身份冲突校验

[reserve](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/allocation/reservation/service/impl/WmsAllocationReservationServiceImpl.java:146) 只有既有决策不为 `EVALUATED` 时才调用 [replay](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/allocation/reservation/service/impl/WmsAllocationReservationServiceImpl.java:303)。因此服务调用方先 `evaluate(A)`，再以相同 effectKey、不同父业务身份或输入摘要调用 `reserve(B)`，会绕过该方法中的冲突检查，继续替换候选和写锁。后置 verify 只核对锁与数量，未重新核对决策输入身份。

正常生产 Action 对 effectKey 与 inputHash 有额外绑定，降低了异摘要输入的触发面；这里是可复用领域服务合同缺陷，不能描述为任意 HTTP 请求都可利用。修复应在任何既有决策进入状态分支前统一校验身份与摘要，新增 EVALUATED 中间态的同键异输入用例。

## 3. 设计、复用与范围判断

没有发现另造完整流程引擎。现有实现复用了 `iws-process`、SAME_TX、LocalAction、平台 checkpoint 和 `IwsCrudPage`；纯条件、结果、装载、分配内核值得保留。不能因为允许重构就推倒这些已经清晰的边界。

主要问题是切片长期未整合、领域权威分散和查询投影重复规则：

| 证据 | 实际维护成本 | 纠偏设计 |
| --- | --- | --- |
| Reservation 1306 行、LoadChange 1288 行、Release 1109 行、InventoryRead 1061 行 | 同时承载 SQL、锁序、校验、结果映射、审计或读模型；F03/F05/F08 是跨职责规则漏接的具体例子，行数本身不是缺陷判据 | 领域服务保留一个事务用例；内部拆当前事实仓储、共享锁协议、数量/权限投影。保持同一外层事务，不按表拆事务 |
| allocation、load、space 各服务直接读写相同 capacity/occupancy/channel 表 | 修改锁序或有效状态需同步多处，容易产生业务规则分叉 | 固定一个资源锁协议和权威占用账接口；先用竞争/回滚用例约束，再移动代码，不新建泛化任意表引擎 |
| `inventory/query` 直接复算可用量、整树和规则摘要 | 查询侧形成第二套领域解释，当前已有可用量与行权限偏差 | 查询消费领域事实与统一计算口径，按用途拆 Material/Trace/Task/Rule 查询服务 |
| core/init、独立 SQL 和多个测试上下文分别维护结构 | 测试 fixture 比正式空库完整，局部绿灯不断增长但新安装仍不可用 | 一套目标结构清单；空库直接创建最终模型，集成验收以实际入口产物为起点 |

本期不承担旧版接口/结构兼容，但必须保留**当前系统业务中的版本事实**：旧物料沿用旧规则、不可变发布版本、追踪历史、UNKNOWN、稳定效果键和安全重试都不是可删除的历史兼容层。

原 SQL 专题、P1-03、S2-F0、P6-02/P6-04 中的旧库映射、回填、旧索引分阶段退役、跨 release 冻结协议，曾作为迁移路线设计；按本次用户边界，不再作为标准新项目的默认验收主线。保留既有文件供追溯，后续删除代码另在实施范围内办理，不在本轮清理真实库或旧数据。结构校验、事务和部署失败控制仍需保留，但不必维护旧模型运行路径。

原任务末尾将“完整 P7 的唯一入口”表述为先授权真实父业务/MOVE，过度收窄了可继续工作：原方案 §1、§10、§15、§16 已允许 fixture 验证物料溯源与挂载底座，并将真实 MOVE/入出库接入放到后续。真实物料写按钮应继续关闭，**P2 生命周期、P3 provider、P4/P5 原子组合、P6 逻辑合并和 P7 草稿/只读完善仍可继续，不能一并等待扩围**。非空清空链和现场写入本身仍未授权。

## 4. 应接入计划事件的业务

以业务步骤、持久状态和恢复事实决定接入，不按“调用几个方法/几条 SQL”机械拆事件。一次关系或库存原子效果的配套流水、容量和 checkpoint 必须同事务。

| 业务 | 当前状态 | 目标事件及完成证据 |
| --- | --- | --- |
| 空间创建/配置/退役、空范围调整 | 生命周期及配置命令未齐，不能只做表 CRUD | 持久变更单 → VALIDATE → APPLY_CONFIGURATION → VERIFY；当前版本指针、父链、历史和 CAS 一致。非空范围保持明确阻碍 |
| 规则包发布 | [publish Controller](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/rule/controller/WmsRuleController.java:173) 直接调用本地服务；[接口说明](../../iws-modules/iws-wms/src/main/java/com/innolux/iws/wms/rule/service/WmsRulePublishService.java:23) 明确用事务代替计划 | 规则配置任务 → VALIDATE_RULESET → ACTIVATE_RULESET；复用现有原子发布服务作为 APPLY 动作，冻结摘要和版本。若决定短本地计划用 IMMEDIATE，仍须采用平台发布和安全失败合同 |
| 逻辑归属合并 | 规划器与持久化存在，没有完整父单/批次恢复链 | CAPTURE → CHECK_COMPATIBILITY → 每个固定批次 APPLY → CLOSE；来源停新归属、开放任务阻碍、批次事实和退役证据持久化 |
| 分配锁定/释放、载具装拆 | 已有生产 Action 和隔离 worker 证据 | 保留 EVALUATE/RESERVE/VERIFY、CHECK/RELEASE/VERIFY、CHECK/APPLY_LOAD_CHANGE；补正式目录与任务完成屏障，不另造分配业务头 |
| MATERIAL_CHANGE | SAME_TX fixture 通过，正式入口未开放 | 持久父任务 → CHECK/APPLY/VERIFY；库存、位置、装载/家谱、锁消费/占用、trace、checkpoint、事件推进同事务。底座继续用 fixture 验证 |
| UNKNOWN 对账释放 | 已有查证和证据类型区分，缺权威来源/编排 | 查询/取得可信证据 → 显式受控决策 → 原键释放任务；普通超时不自动释放，不换键盲发。真实设备适配列后续 |

公司名称/备注等已明确允许的本地原子 CRUD、只读查询、无副作用预览，不应为了“纳入计划”增加空壳业务单。多步骤规则发布和逻辑合并则不能长期以直连服务替代原设计的可恢复业务流程。P6 当前服务会遍历全部 decisions，而不是按 `batchSize` 只执行一个持久批次；接编排时需收紧为真正的单批动作，避免一个事件处理无限集合。

## 5. 测试与空库交付判断

正式空库执行器 `iws_init_from_empty.ps1` 会检查目标无对象并传播 mysql 非零退出码，这部分有合理保护。本轮没有执行全新初始化，不能断言最新 SQL 的全部语句已在 MySQL 成功；**即使脚本执行零报错，F01/F02 也足以否定完整 P1—P7 的业务可用性**。

空间同样未达最终合同：init/core 仍保留可空 `storage.warehouse_id`、旧默认启用和仓库 `current_location_version_id`；缺 `zone_kind`、库区/储位生命周期、完整地址唯一性等。目标在 [空间契约提案](../space-target-contract-20261029/proposal.md) 和 P1 C42—C45 已有记录，不是需要再访谈的新业务。先按完整模型同版落地，不只给测试补三个字段。

[规则测试库](../../iws-modules/iws-wms/src/test/java/com/innolux/iws/wms/rule/WmsRuleTestDatabase.java:48) 显式 ALTER 补 scope 字段，之后执行独立规则/绑定脚本；[测试上下文](../../iws-modules/iws-wms/src/test/java/com/innolux/iws/wms/rule/WmsRuleTestContext.java:139) 注册的用途/来源也不同于生产首期 STORAGE FILTER/SORT。它们可证明目标合同或局部事务，不证明正式配置已注册。fixture 本身不是作弊；错误在于用它替代正式入口整合验收。

必须增加或纠正的验收：

1. 一次性空库只执行正式初始化入口，不额外补 DDL；启动当前同版应用，逐项验证公司、空间、规则、任务、trace、UNKNOWN、Catalog 的实际 SQL。检验预期表/列/索引/CHECK/必要种子，不用表数代替结构和能力。
2. 普通角色覆盖各 DataPermission 分支、同载具不同部门、无 trace 权限详情、撤权即时生效；不能只验证公司隔离或超级管理员。
3. 用独立业务预期验证冻结/预留重叠、跨规则版本汇总、叶子去重。先修正 F05 的错误断言，再改实现；不能只改期望数让测试通过。
4. 验证 EVALUATED 中间态异输入拒绝、原键重试；在各事实写入点和平台收口点注入失败，由独立连接检查整组回滚。
5. 持久任务与完成屏障验证预期子任务集合、定义/规则冻结、未完成/UNKNOWN 阻断、重启恢复。实例 COMPLETED 不等于父业务事实已完成。
6. 页面逐字段断言筛选载荷与结果集、KeepAlive/切号/迟到响应、导入预览和导出边界。PDA 设备扫描没有真机证据时保持未验。

## 6. P1—P7 真实剩余范围

| 阶段 | 可保留成果 | 本期仍须完成 |
| --- | --- | --- |
| P1 | 类型目录、部分命令审计、平台挂载接缝、持久任务 | 合同统一、正式目录/挂载关系、任务集合与业务完成屏障；迁移路线从默认主线移出 |
| P2 | 公司最小闭环、空间只读、十表 foundation、通道共锁 | 完整目标父链/版本/生命周期、地址唯一性、默认停用、统一当前指针、空范围配置命令与页面 |
| P3 | DSL/结果内核、发布 CAS、属性绑定快照 | 真实来源 provider、正式属性种子、生产用途配置、冻结执行与发布计划；F01/F06 修复 |
| P4 | trace、装载内核/部分持久化、SAME_TX fixture | 完整装载/容量权威、锁消费与物料事实组合、符合规则版本的聚合、F03/F04/F05 修复 |
| P5 | 分配/释放 Action、锁与 UNKNOWN、通道图、隔离 QUEUE worker | 六表初始化、统一候选事实与空间快照、F08、资源事实与 trace 组合、完成屏障 |
| P6 | 合并规划与持久化切片 | 来源停新归属、兼容证据、真正持久批次/暂停恢复/收敛/退役及计划适配 |
| P7 | 公司/空间/规则/物料只读、Catalog、trace/UNKNOWN、PDA 定位 | 空间配置与规则草稿能力、统一 capability/DTO 路由、F07、详情权限、导入导出/恢复行为及组合端到端 |

真实 MOVE、非空拆分清空、现场入出库等属于后续；不以虚构父单、伪造回执关闭它们，也不再把它们作为本期其他任务完成的统一前置。业务版本历史和安全门禁仍必须通过底座 fixture。

## 7. 纠偏任务与执行顺序

下表是本轮重排后的执行合同，复用原 INV 任务，不新增并行的父业务项目。`ready` 只表示可以继续处理，不表示本轮已执行代码修复或获得真实库写入权限。

| 编号 / 原任务 | 状态与依赖 | 交付与允许写集 | 验收 |
| --- | --- | --- | --- |
| R1 / INV-P1-01、P1-03、P6-02/04 | ready，无前置 | 文档收口为标准目标路线；历史迁移独立存档，真实扩展单列。仅方案/任务文档 | 不再要求为标准新库完成旧库映射或真实 MOVE；保留版本事实与数据库授权边界 |
| R2 / INV-P2-01/02、P3-03、P5-01 | ready，可与 R3 并行；SQL 单一执行者 | `iws-wms` 模型/Mapper/readiness、core、empty init、相应独立 SQL与测试。同版最终结构，解决 F01/F02 及空间契约 | 正式入口空库与目标结构一致；不额外补字段；当前应用 smoke，无未知列/缺表；必要目录/权限 seed 可核验 |
| R3 / INV-P4-03、P7-03/04 | ready；查询安全不等完整 R2 | `inventory/query`、对应 DTO/XML/Web 和测试；修 F03—F07，数量与聚合合同先确定 | 普通角色逐行隔离、功能权限独立；冻结/预留集合与版本分组正确；筛选不丢失 |
| R4 / INV-P1-04、P4-04、P5-03/04 | 依赖 R2 的相关结构；可先准备接口，整合待 R2 | task、allocation、material、load 及最小 process 接缝；修 F08，持久任务集合/屏障、真实占用与原子事实组合 | 失败/UNKNOWN 不推进父业务；同键冲突全阶段拒绝；故障注入、并发与重启恢复 |
| R5 / INV-P2-03、P3-03、P6-01 | 依赖 R2 与 R4 相应子合同 | 空间、规则、逻辑合并领域及计划适配；复用现有服务作原子动作，补业务单/任务，不复制引擎 | 发布后置事实、真正单批合并、来源停新归属、暂停恢复/收敛、空范围生命周期 |
| R6 / INV-P7-01/02S/03/04/06、INV-I-01 | 依赖 R3、R4、R5 对应可用切片 | Web/PDA 当前范围与整合测试、交付证据；补草稿/配置页面，不开放后续真实 MOVE | 同一源快照由正式空库启动；权限/并发/UNKNOWN/回滚/分页/页面实测。PDA 真机限制明确 |

R1 → R2；R3 与 R2 可并行。R2 + R4 的相关子交付 → R5；R3/R4/R5 的范围内行为 → R6。共享 core/init、公共测试上下文、process 接缝、根 Maven 属调度资源，不伪造业务依赖。下一步先修 R2 与 R3，不能再仅累计内核测试数字。

建议模块边界为 `space`、`rule`、`material/load`、`allocation`、`business/task`、`query`，共用当前授权、命令身份和资源锁协议。领域服务负责原子不变量，计划适配负责持久业务步骤，Controller/CRUD 只做 DTO、授权与命令路由。当前没有证据需要新增通用脚本引擎、第二套事件平台或更多任意类型抽象。

## 8. 恢复与交付约定

恢复先重新检查 B/W/P/D 的 status，保留全部 dirty；当前基线及修改前两份文档副本在本目录 `evidence.json`、`baseline/`。不要清理旧 work 证据来制造干净工作区。本轮没有可继续使用的子代理写集，用户明确要求不再开子代理。

集中规约指向的 `B/.agents/skills/iws-plan-event-project/SKILL.md` 当前缺失；可读取的同名项目指南位于 `D/.agents/skills/iws-plan-event-project/`。本轮用其硬约束对照原方案，并未修改 Skill；后续应修复权威入口指针，不能把找不到文件当作绕开计划事件的理由。

后续代码实施、数据库写入、部署、commit 和 push 分别按用户当时指令执行。本轮评审和纠偏文档不自动授予这些外部动作；已追溯的原任务本地编译/隔离验证授权不等于真实库写入。

### suggested skills

- [implement-spec](C:/Users/yohmi/.codex/skills/implement-spec/SKILL.md)：获得继续实施指令后，执行 R2/R3 修复。
- [boring-engineering](C:/Users/yohmi/.codex/skills/boring-engineering/SKILL.md)：实施时收拢已证实分散规则，控制重构范围。
- [sql-idempotent-upgrade](C:/Users/yohmi/.codex/skills/sql-idempotent-upgrade/SKILL.md)：R2 目标结构与初始化同版交付。
- [database-readonly-mcp](C:/Users/yohmi/.codex/skills/database-readonly-mcp/SKILL.md)：按当前配置重新核对真实结构，保持真实库只读。
- [patch-editing](C:/Users/yohmi/.codex/skills/patch-editing/SKILL.md)：保留已有文件格式与用户修改。
- [code-review](C:/Users/yohmi/.codex/skills/code-review/SKILL.md)：修复后的 Standards/Spec 复验；当前用户要求不使用子代理。

## 9. 2026-11-02 实施进展

### 已完成的纠偏

- R2/F01/F02：正式 core、empty init 与当前实体/Mapper 已同版；规则绑定列、六张分配/预留表、业务完成屏障表均进入正式初始化。空间最终字段、地址唯一键和 `zone_kind` 的测试夹具旧假设已纠正。
- R3/F03—F07：物料详情装载树按库存行重新授权；trace 摘要服从独立查询权限；冻结库存可用量为零；汇总按逻辑归属版本分组；trace 任务键筛选和显式空串条件已贯通前后端。
- R4/F08：任意既有分配决策在进入状态分支前统一校验 effectKey、父业务身份和输入摘要。物料事实组合入口现可同事务提交库存位置、流水、trace、分配锁消费和装载占用。
- R4 持久完成屏障：`wms_business_task_barrier` 预期子任务集合规范化、缺失/运行中/失败阻断、全部完成、原键回放和异集合冲突已有真实 MySQL 用例。
- R4 物料 action：`APPLY_MATERIAL_CHANGE` fixture 已使用组合服务；checkpoint 包含占用效果、活动占用、根节点、分配锁、锁代次、消费 trace。成功与目标容量失败回滚均有 LocalAction 级用例。
- R5 规则发布：新增 `VALIDATE_RULESET → ACTIVATE_RULESET` 计划合同、动作、状态校验与计划草稿工厂；发布 Controller 改为创建持久业务任务并挂载计划，不再直接调用规则包本地发布。Web 规则包抽屉可在 `DRAFT` 版本选择作用域并提交发布，显示受理后的计划实例。
- 逻辑合并：单动作已限制为一个持久批次，来源先进入 `RETIRING`，清空后支持幂等退役；未清空来源返回明确阻碍。
- R5 空间生命周期：空间创建、配置和退役已接入持久计划事件；Web 空间页可创建、配置和退役，并保留 revision/version 与持久受理状态。并发同命令键和原键回放已覆盖。
- R5/R6 逻辑合并：父单、固定批次集合、完成屏障和计划定义已落地；Web 规则页提供逻辑合并入口。早先版本的 LAN 浏览器已核对目标作用域、来源范围与批次屏障字段；本轮最终 LAN 浏览器验收范围是空间、规则详情 readiness 和规则包成员。
- 正式目录与挂载：空间变更、空间退役、规则包发布、逻辑合并及其监督定义为 `10` 个 ACTIVE 计划；首次 provision 与同库重启后均保持每个计划/角色唯一，不重复创建。
- 空库入口缺陷：`sys_user`、`sj_system_user`、`sai_user` 此前把需求密码明文直接写入哈希字段，导致新空库默认管理员无法登录。密码值本身不变；已在 `iws_init.sql` 与 `iws_init_from_empty.sql` 分别改为 BCrypt、SHA-256、PBKDF2 哈希，并由全新库重新初始化验证。

### 本轮验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 全 WMS 最终隔离套件 | `481` 项，`0` failure/error/skipped，`BUILD SUCCESS` | `C:/code/company/innolux-iws/work/verification-current-20261103/wms-full-test-final-current.log` |
| 根 reactor clean `test-compile` | `43/43` 个 reactor 项全部成功，JDK 21 | `C:/code/company/innolux-iws/work/verification-current-20261103/reactor-clean-test-compile-final-current.log` |
| 全应用打包 | `43/43` package 成功；最终 JAR `4C2515…` | `C:/code/company/innolux-iws/work/verification-current-20261103/package-final-current.log` |
| 正式空库最新入口 | `181` 表、`77` WMS 表、`256` 菜单；需求密码值不变，三个密码字段算法与长度正确 | `C:/code/company/innolux-iws/work/inventory-reassessment-20261102/empty-init-20261103-loginhash.log` |
| 最终 JAR 启动/登录/API | 健康 `200`；普通账号登录与 `getInfo` `200`；计划能力目录、10 条 ACTIVE 定义、空间与规则接口 `200` | `app-lan-final.out.log`、`browser-lan-final-20261103/summary.json` |
| 同库重启 | 重启前后 ACTIVE 定义均为 `10`，重复键 `0`，定义总数仍为 `10`，启动无非预期异常 | `app-loginhash-restart-20261103.out.log` |
| 规则详情 readiness | 详情 Mapper 补齐 `attribute_bindings_json`；生产绑定快照进入 `READY`，正式 API 返回 `executionReady=true` | 全 WMS 日志、`wms-inventory-read-focused-current.log` |
| Web | Vitest `264/264`、空间生命周期 `70/70`、`vue-tsc`、`oxlint`、生产构建全部通过 | 本轮命令结果 |
| 普通账号真实浏览器 | 本机地址和 `192.168.1.21:18000` LAN 代理各 13 checks、20 API，页面/控制台/网络/API 错误均为 0；绑定最终 JAR | `C:/code/company/innolux-iws/work/verification-current-20261103/browser-current-20261103/summary.json`、`browser-lan-final-20261103/summary.json` |
| PDA 静态检查 | `api/work/inventory.js` 语法通过，`pages.json` 定位路由存在，`locate.vue` 结构及只读提示存在 | 本轮命令结果 |

### 本期结论与后续范围

- P1—P7 本期底座、页面、正式空库、最终 JAR、隔离 MySQL、LAN 代理和浏览器整合验收已通过；复评 REV-01—REV-08 已关闭，`INV-I-01` 在任务图保持 `done`。
- 真实 MOVE、非空拆分/收缩清空、现场入出库和正式物料写入仍属于后续扩围，相关能力继续关闭。
- PDA 未连接真机，只完成源码、路由和静态语法检查；H5/真机运行、扫码设备和现场网络不作为本轮已验结论。
- 真实业务库未写入、应用未部署、未提交或推送。最终浏览器验收未再出现 `/resource/message` 404；该结果只绑定当前隔离运行配置，不把消息模块整体推断为已验收。
