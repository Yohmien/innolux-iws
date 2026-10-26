# IWS+ 在库管理 SQL 初始化、旧库升级与基础规则种子计划

日期：2026-10-26。状态：方案初稿，供实施拆分与评审；当前只写本文件，未创建可执行迁移、未实施业务、未连接或写入数据库、未执行 SQL，也未暂存或提交 Git。

## 1. 交付边界

主方案[《IWS+ 在库管理完整方案》](inventory-management-design-20261026.md)仍是业务与模型权威，[储位分配计划](inventory-allocation-rules-20261026.md)仍是锁位、释放和 UNKNOWN 的权威，[Web 与 CRUD 修订](inventory-management-web-crud-plan-20261026.md)仍是对象级 CRUD/业务判定和菜单权限的权威。本文只补齐初始化、旧库承接、幂等升级、规则/menu 种子与验收合同。

集中规约的“不承担历史接口/结构兼容”只说明不保留双读双写或兼容代码，不能推出可以删除旧库。未来升级必须保留旧表、旧键、旧流水和业务事实，不重跑 `iws_init_from_empty.sql`，不以清理数据换取结构对齐。

空间目标层级固定为：公司 → 仓库 → 物料库区 → 设备库区 → 储位。`company_id` 是仓库必填归属；公司是上位管理对象，不另增“公司类别”空间节点。仓库类型包含通用“楼层”选项及其楼层参数（建议 `warehouse_type_code` 与受类型约束的 `warehouse_type_config`），楼层不独立到仓库类别之外；参考项目名称不成为业务分类或界面标签。

本期 SQL 范围：交付已确认核心空间、规则、锁定与安全释放、物料变更、业务/计划类型目录与允许挂载关系、trace/audit 及其幂等初始化、升级和验收。`FREEZE/UNFREEZE` 的业务种子、专用菜单/权限、流程定义和执行验收均不在本期。

后续范围：移库、盘点、入库、出库、拆分、合并及冻结等具体作业模板只文档预留；其类型注册、菜单/权限、流程发布和执行另行设计验收。若 `wms_inventory_hold` 只服务未来冻结/扣留，则列为后续候选，本期不建、不初始化、不发布。

## 2. 修改前字节与 Git 基线

下列值在本次新增文档前核对，并与主代理保存的仓库外 manifest 一致；当前工作树 hash 未相对 manifest 变化。快照位于 `C:/Users/yohmi/.codex/visualizations/2026/10/26/01a17856-5d6c-70a1-9216-d7994d5efdea/material-trace-followup/sql-baseline/`，`manifest.json` 和三份 `script/sql/` 原字节均可用于恢复 dirty 源，不把该快照或额外基线 JSON 复制进仓库。

| 文件 | 工作树 SHA256 | 字节 | HEAD blob | 远端 blob | dirty/比较 |
| --- | --- | ---: | --- | --- | --- |
| `script/sql/iws_init_from_empty.sql` | `95720f35dad2596df259d2df4c3b1d64450625c1ff90ef91274db1061866881f` | 533217 | `768279e80fdac4d339b9ea4dce4b5962d6c2d3eb` | `34127028b19a11d195164d1fcc8438eeb09c715b` | `M`；与 `origin/main` blob 相同，与 HEAD 不同 |
| `script/sql/iws_wms_smt_core.sql` | `98bf277b32ae16fecb3fff4bfcfc56307118a622053499cc99ca5364ce9ae709` | 76368 | `0df70bc553dac84939263c3929e10f796e25c605` | `0df70bc553dac84939263c3929e10f796e25c605` | clean；HEAD 与远端相同 |
| `script/sql/iws_init.sql` | `87671be8133358527e7c6fac61b17e66b53fe8c8dcfd08eeda32a9f353397daf` | 204217 | `d4e0daf90bb6e4bd3ea37e48a0fe0b96673a15dd` | `e325aab32205b33291a2a52d9f097f841bc0801f` | `M`；与 `origin/main` blob 相同，与 HEAD 不同 |

本次只读核对的仓库为 `personal/plan-event-public-platform-20261024`，HEAD `ffe7aa94b631ddd770a6455ec917b4e80853f33a`，跟踪 `origin/main`；状态显示 `[ahead 5, behind 63]`，且仓库整体还有多项任务外修改。`git ls-remote` 的实际 `main` 为：`origin`/`github` = `75602e4ce5c266a5f1a433b7e00a085702407e10`，`upstream` = `15e915f2295572ff3b473f5dc06cf1a27016d445`。表中三份 SQL 在三个远端的 blob 均相同。

基线证据只用于结构和业务键承接，不把旧 SQL 中的账号、令牌、连接值或完整 INSERT 种子复制进本文。

## 3. 当前 SQL 事实与目标清单

`iws_init_from_empty.sql` 是当前唯一的自足空库入口：头注释声明一次性执行，顺序包含框架、WMS、endpoint/process、菜单和最终验收；WMS 20 表在 `script/sql/iws_init_from_empty.sql:1834-2530`，必要种子在 `:2556` 后，endpoint/process 菜单权限在 `:5294-5537`，APK 与最终 124 表/230 菜单检查在 `:5546-5759`。未来新结构必须直接同步进这一入口，不能让执行者另行拼接 `iws_wms_smt_core.sql`。

`iws_wms_smt_core.sql` 当前是独立 WMS DDL 基线，`iws_init.sql` 是旧框架初始化入口；两者继续保留。构建时对 `iws_wms_smt_core.sql` 与 `iws_init_from_empty.sql` 内联 WMS 段的规范化结构摘要做一致性校验，格式差异可忽略，列、索引、默认值、字符集和注释差异必须阻断。

当前库结构的关键限制是：库区直接持有可空 `warehouse_code` 和单层 `parent_zone_id`（`script/sql/iws_wms_smt_core.sql:28-52`）；储位只挂库区且没有 depth（`:62-93`）；容器只有一个可空 `storage_id` 和唯一索引 `uk_wms_container_storage`（`:136-166`）；库存内联生产与位置字段、聚合预留字段（`:276-330`）；流水只保存编码快照（`:339-369`）。这些表均不能宣称已具备目标模型。

| 领域族 | 拟新增/拟调整对象 | 目标事实 |
| --- | --- | --- |
| 公司、仓库与空间版本 | 新增 `wms_company`；新增 `wms_warehouse`，必填 `company_id/warehouse_type_code`，楼层保存在类型配置中；调整 `wms_warehouse_zone`、`wms_storage`；新增 `wms_location_version` 及版本节点/生效指针和空间重分区业务表 | 公司→仓库→物料库区→设备库区→储位唯一父链；类型内楼层配置参与规则与历史快照，不强加公司类别必填字段 |
| 逻辑归属 | 新增 `wms_logical_scope`、`wms_logical_scope_version`、`wms_logical_scope_storage`、`wms_material_logical_assignment`、`wms_logical_assignment_history` | 当前物理位置与历史逻辑归属分离，旧物料持久使用旧版本 |
| 规则包 | 新增 `wms_rule_definition`、`wms_rule_version`、条件/结果/作用域表、`wms_rule_package_version`、生效指针和 `wms_rule_seed_ledger` | 不可变版本、CAS 生效、摘要冲突、只补缺不覆盖人工修改 |
| 物料、生产与存放 | 调整 `wms_inventory` 增加生产/存放/装载/规则引用；新增 `wms_production_info`、`wms_storage_info` | 保留旧 `inventory_id/material_code/qty/unit/business key`，不把生产属性混入空间 |
| 载具与关系 | 调整 `wms_container` 为载具主档；新增 `wms_load_node`、`wms_load_relation`、`wms_load_relation_history`、格口/槽位记录 | MATERIAL/CARRIER 统一节点，单当前父关系，装载门谱与生产家谱分离 |
| 锁与占用 | 本期新增 `wms_allocation_decision`、候选明细、`wms_allocation_lock`、`wms_allocation_release`、`wms_storage_occupancy`、`wms_inventory_reservation`；`wms_inventory_hold` 仅作为后续冻结/扣留扩展候选，本期不建 | 锁、物理占用和数量预留分账；UNKNOWN 保持锁定；既有冻结仅只读取证，后续扣留类型另行确认 |
| 库存与物料 trace | 调整 `wms_inventory_flow`；新增 `wms_material_trace_history`、`wms_material_trace_item`、`wms_material_trace_edge`；新增 `wms_object_audit`、`wms_command_attempt` | 旧流水不改写；新事实头、逐物料项、N:M 家谱同事务；普通 CRUD 审计独立留证 |
| 业务协调 | 复用或新增各领域业务单/明细，持久任务与完成屏障，以及空间重分区、逻辑合并等必要专表 | 父业务保留实际businessType，分配和物料变更仅挂载计划、不新增对应业务头；只关联process实例/事件，不复制计划引擎 |
| 升级控制 | 新增 `wms_schema_version`、`wms_upgrade_run`、`wms_upgrade_checkpoint`、`wms_legacy_id_map`、`wms_upgrade_issue` | 应用门禁、批次断点、源目标 ID 映射和中文阻碍报告 |

以上对象名是当前规划的定稿候选，**尚无任何一张已创建**；标注后续的候选本期不建，P1 实施时统一冻结本期名称、列、唯一键、索引和审计字段。

## 4. 初始化与升级发布合同

空库入口只在目标库完全为空时执行，包含本期最终表、菜单/按钮权限、字典、通用规则种子和空业务表检查；后续候选表不进入本期初始化。实际公司、仓库、楼层仓和厂区映射不进入空库种子；首次上线由受控配置业务创建。旧库升级不执行该入口，而按下列阶段执行。

| 阶段 | 执行内容 | 写入与门禁 |
| --- | --- | --- |
| S0 只读预检 | 核对数据库身份、MySQL 版本、旧 schema 指纹、三份源码摘要、表/数据量与最终用户确认的 manifest | 不写；任何不支持源、结构漂移或未知对象即停止 |
| S1 附加 DDL | 新增本期公司/仓库/规则/装载/锁/追溯/升级控制表，增加必要列和索引；后续冻结/扣留候选不建 | DDL 与回填分文件；保留旧表和旧索引，不执行 `DROP TABLE/COLUMN`、`TRUNCATE` 或 `REPLACE` |
| S2 写冻结与快照 | 停止旧 WMS 写入和计划认领，记录每表高水位、count/sum/业务键摘要，建立升级运行和检查点 | 不能只停页面；入口网关、任务认领和旧实例写入都要受控停止 |
| S3 事务回填 | 按小批承接公司→仓库→空间、归属、生产/存放、装载、库存、预留和流水关联 | 每批事务；按业务键 insert-if-missing + 摘要核对，不覆盖人工修改；不自动重放旧计划 |
| S4 验收 | 检查结构、中文阻碍清单、数量守恒、父链、唯一当前关系、库存/预留平衡、流水与业务键；既有冻结只作只读取证 | 结构或关系失败即失败；受影响行数只作辅助 |
| S5 同版切换 | 新应用与 SQL/seed/menu/dict 发布同一 release，启动时读 `wms_schema_version` 和完成状态 | 新应用只认新位置权威；旧应用禁止接入，未知连接/写入不自动重连 |
| S6 旧约束退役 | 新并发约束和隔离并发测试通过、写入口已切换后，执行独立授权的 `DROP INDEX uk_wms_container_storage` | 先保存索引定义；永久保留列和数据，不留下第二套可编辑位置权威 |

预检不能只检查对象是否存在。需从 `INFORMATION_SCHEMA` 读取列顺序/类型/空值/默认值、主键/唯一键/普通索引、字符集/排序规则和注释，形成排序摘要，并与批准的旧版基线逐项比较缺失、已满足、兼容和冲突。未知表、额外可写入口或状态枚举也必须列为阻碍；不能因表名存在而跳过。

升级脚本发现连接中断、执行结果未知、批摘要未知或门禁版本不明时立即停止，不自动重试、不切换数据源、不把旧库当默认库。恢复只能从已核实的 `wms_upgrade_run/checkpoint` 状态继续；失败采用前向修复或经审计的补偿，不能删表重建。

## 5. 旧库数据承接

1. **公司与仓库**：`warehouse_code` 先按原始大小写 trim 后作为候选业务键，并按配置的 `(plant/source, warehouse_code) → company_id` 审核映射创建 `wms_company`/`wms_warehouse`；空值、同码多公司、无公司映射、未知仓库类型均阻断。不得创建默认公司，不得把 `floor` 变成公司或仓库的额外父级。
2. **类型内楼层配置**：当前旧库并非两个参考项目的业务数据迁移目标，不自动导入参考数据。若实际旧库/现场确有楼层规则，需提供经批准的 `(company_id, site, floor_no) → warehouse_id` 映射，将楼层存入对应仓库的类型配置，目标规则输出解析后的 `targetWarehouseId`。缺映射或跨公司映射即阻断，不复制参考代码默认楼层、比例或设备地址。
3. **库区父链**：旧 `zone_type` 与可空 `parent_zone_id` 不能机械换算成新 MATERIAL/DEVICE。以逐库区审核映射确定目标类型、所属仓库和父库区；父级缺失、成环、同类嵌套、跨仓或类型不符全部进入阻碍清单。无法证明时保留源行，不猜父级。
4. **储位**：保留 `storage_id/storage_code/row_no/col_no/level_no` 和真实启停状态，生成从公司到当前设备库区的完整唯一链；不伪造 depth。`storage_code` 或父链冲突时停止相关批，不把未分配区伪装成父级为空的储位。
5. **载具与装载**：每个旧容器生成一个 CARRIER `wms_load_node`。有 `storage_id` 时生成到该储位的当前 location 关系；无 `storage_id` 时按 `location_status` 映射库外/在途，未知状态阻断。旧容器无父子字段，因此不虚构多层关系。旧格口作为载具槽位标识保留，并与库存引用核对。
6. **库存、生产与存放**：按 `inventory_id` 建稳定映射和 MATERIAL 节点，保留 `stock_unit_code/material_code/inventory_qty/unit/status/label_snapshot` 与全部业务键。`material_batch/date_code/supplier/production_date` 等进入生产信息；仓库/库区/储位/容器/格口进入存放信息和装载关系。容器位置与库存快照不一致、物料不存在、单位为空或位置无法唯一证明时阻断，不补猜生产属性。
7. **旧预留**：`reserved_qty > 0` 时，仅当 `reserved_order_no + reserved_order_line_id` 可唯一关联旧出库明细或已登记持有人，且 `0 < reserved_qty <= inventory_qty` 时生成逐任务预留明细。缺持有人、数量越界、零数量仍带占用人或多持有人歧义均阻断；不把单号当持有人，不拆造预留事实。
8. **旧流水与历史**：`wms_inventory_flow` 原行、原 `idempotency_key`、发生时间和数量保持不可变；物理源/目标 ID 通过独立关联/索引表补证，不重写旧流水，不从旧流水伪造新 trace 历史。旧流水业务键冲突或缺失时只报告，不能覆盖。
9. **旧业务单与任务**：旧入出库、历史、推送、冻结及计划证据保留原身份和状态；旧冻结仅作只读取证和阻碍展示，本期不建冻结/解冻命令，不删除、改写、释放或重放旧记录。升级不重放旧计划、不生成已完成实例、不补造事件/尝试。需要继续处理的开放事实必须经显式承接业务确认，再创建新任务。
10. **中文阻碍清单**：统一列出“问题类别、源业务键/对象、缺失或冲突摘要、阻止阶段、建议人工动作、是否阻断切换”。清单只展示脱敏摘要，不打印凭据、完整标签或大字段。

所有映射批次使用稳定源键和摘要：目标不存在则插入；目标已存在且摘要一致则跳过；同键不同摘要立即冲突停止。任何旧数据都不能被自动删除、清空或由“新结构更标准”解释为废弃。

## 6. 基础规则、公司/仓库字典与种子

逐项配置键、初始值、作用域、覆盖方式及启用前置条件以[初始化配置值表](inventory-initial-configuration-20261026.md)为唯一值表。硬不变量由代码与结构保障，不能因规则草稿未发布而失效；可配置规则才采用DRAFT/未启用初值。表内现场必填项缺失时返回明确阻碍，不用默认公司、单位或设备数据补齐。

规则正文、公司、仓库和楼层映射都不从参考仓库直接搬值。下表记录实际读到的源码行为及其可复用边界；厂区料号、设备编码、比例、优先楼层和仓库 ID 仅是参考实现事实，不能作为本库生效参数。

| 规则来源 | 实际源码与行为 | 本库建议 |
| --- | --- | --- |
| AVC 库区 | `vietnam_avc/.../warehouse/enums/AreaTypeEnums.java:9-20` 定义区域类型；`:48-55` 仅类型 4/5 判为可混放；`:89-107` 类型 2/3 整区锁、4/5 单储位锁；`:125-140` 虚拟库/退料/客供料边界 | 抽成“混合策略、锁粒度、安全门禁”模板；类型码和值必须由站点映射，初始不激活 |
| AVC FIFO/盘点（后续参考） | `.../outbound/support/InventoryCountReelSelectSupport.java:28-29,42-111` 按 FIFO 取整盘；`:155-206,242-277` 盘盈允许末盘截料；`mapper/InventoryCountLockableReelQueryMapper.xml:16-43` 以 `inbound_time,reel_id` 等稳定排序 | 后续接入时可采用入库时间升序加唯一键、整盘不拆等模板；本期不导入出库/盘点执行种子，不预置截料流程 |
| AVC 冻结/占用（后续参考） | `.../WmsInventoryFreezeServiceImpl.java:83-170` 行锁取候选、排除非正常/出库占用后同事务更新；`:173-215` 解冻重验；`:528-570` 待处理/已下发占用阻断；`SMTInfoMapper.xml:156-191` 使用 `FOR UPDATE` | 仅作未来扣留/解除扣留的一致性设计参考，本期不实施、不生成业务或流程种子；不复制单一状态字段同时表达冻结和出库锁 |
| PCBA 楼层分配 | `.../InboundDestinationServiceImpl.java:56-124` 最长料号前缀、比例差、优先楼层和稳定兜底；`MaterialFloorMatchMapper.xml:29-35` 以 `material_code` 最长前缀取规则 | 规则输入仍可规范化楼层，但执行输出必须是已解析 `targetWarehouseId`；比例和楼层须站点配置 |
| PCBA 规则维护 | `.../MaterialFloorMatchServiceImpl.java:85-145` 保存/修改采删后重插；`:148-180` 删除后重分比例；`:244-283` 校验合计/优先唯一 | 只吸收校验思想；本库更新规则必须新版本，禁止先删后插造成窗口 |
| PCBA 模式冻结 | `.../PcbaOutboundPostingModeService.java:39-49` 订单首次过账上下文加锁冻结；`:72-112` 开关 CAS/一致更新与提交后缓存失效 | 任务快照固定模式、规则和门禁版本；配置切换不影响已启动任务 |
| PCBA trace | `.../MaterialTraceServiceImpl.java:110-145` 先 insert-if-absent 再按业务快照核对；`MaterialTraceHistory.java:20-41` 头级母子箱/数量/源目标；`MaterialTraceKeyFactory.java:12-76` 稳定业务幂等键 | 扩展为 trace 头、item 和 N:M 家谱；“时间变化不算内容冲突”，相同键不同业务快照拒绝 |
| 用户/主方案新增 | 主方案 `inventory-management-design-20261026.md:61-78,136-164,180-184,202-221,233-256` 与分配方案 `inventory-allocation-rules-20261026.md:59-77,79-118` 定义完整公司/仓库树、不可拆、全叶安全、旧归属版本、best-fit 稳定排序、入库转占用、释放证据和 UNKNOWN 门禁 | 这些是方案要求，不能说成参考代码已实现；其中安全不变量不得被普通规则关闭 |

候选code清单见初始化值表：本期登记底座策略/硬约束标识及所需可配置模板；`inv.floor.target_warehouse` 需现场映射后才可发布。`inv.outbound.fifo_stable`、`inv.count.whole_reel` 仅后续参考，不进入本期执行种子或业务发布清单；冻结/解冻同样排除。硬约束标识不是可被禁用的规则开关。

所有可配置规则模板先以 `DRAFT` 可见且未启用，不占生效指针；运行时能力、现场必填项及完整依赖包通过校验，再由规则发布业务显式发布，才成为可供新任务选择的ACTIVE版本。真实设备/外部端点不随种子启用，不创建运行实例。硬不变量始终执行，可配置种子只保存筛选/排序参数；配置向导可复用预置值完成发布，但不能绕过必填门禁或自动接通后续业务。

种子以 `seed_code + seed_version + canonical_digest` 幂等：不存在则插入；同 code/version/digest 跳过；同 code/version 不同 digest 冲突停止；人工修改不被覆盖。调整已存在种子必须生成新 version，并记录旧版本引用。

基础字典只放中立仓库类型建议值 `GENERAL`、`FLOOR`（楼层），最终码表在 P1 冻结；不植入真实公司、仓库或厂区记录，不凭“公司类别”用词新增必填分类字段。`company_id NOT NULL`、`warehouse_type_code` 必须存在；楼层参数受仓库类型配置约束，同公司多个仓库可配置相同楼层，不能以楼层建立唯一索引。外部别名映射须在系统/站点范围唯一解析目标，歧义需增加仓库键。`inv.floor.target_warehouse` 初始无目标 ID，站点配置后才能发布。

`ALLOCATION_LOCK`、`ALLOCATION_RELEASE`、`MATERIAL_CHANGE` 的流程定义只交付注册/发布清单和显式平台动作，不能通过 SQL 插入已发布图、状态、边、运行实例或伪造事件。SQL 可写规则/menu/dict 的安全种子，不能代替 `validate`/`publish`。

本期只交付可扩展业务目录、计划目录和业务允许挂载关系；移库/盘点/入出库/拆合等仅作未来接入样例，不在本计划注册、发布或执行。`businessType` 与 `planType` 必须分离，以目录和允许挂载关系维护，不生成只允许少数作业的 `CHECK` 约束或封闭枚举；fixture 可验证目录形状和挂载合同，但不等于真实业务接入。后续增量类型只补缺、冲突阻断；已有实例和审计保留旧编码、名称快照及版本，停用不破坏恢复。

本期目录不预置 `FREEZE/UNFREEZE` 业务、专用菜单/权限、扣留参数、流程定义或验收，也不生成真实冻结单、运行实例或虚构冻结历史。后续扣留/解除扣留及具体作业模板只在文档预留，另行注册、发布和执行；既有冻结记录与审计保留原样并只读展示。

菜单与按钮种子按对象区分：普通字典、无关系副作用的名称/备注和载具普通展示字段可保留 CRUD 并在同事务写 `wms_object_audit`；空间、规则生命周期、载具装载、库存生产/位置、锁位/释放、业务任务和 trace 家谱走领域业务或只读入口。建议权限码沿用 CRUD 文档 `inventory:warehouse:*`、`inventory:zone:*`、`inventory:storage:*`、`inventory:material:*`、`inventory:carrier:*`、`inventory:rule:*`、`inventory:allocation:*`、`inventory:business:*`、`inventory:trace:*`；历史/审计/家谱不提供 edit/delete/import。新增 menu/perms 只补缺，不按名称覆盖现有记录，不自动授予角色；前端隐藏不能代替服务端对象和部门校验。
本期 menu/perms 不新增冻结/解冻专属项，也不注册后续作业模板。

新增仓储对象同时交付标准CRUD模板所需表/列元数据、查询与导出白名单及受控写入适配登记。没有特殊要求优先复用IwsCrudPage，内置业务或计划不等于拆除标准新增/修改/删除/导入入口；这些入口由后端登记处理器实施对应校验、审计和计划挂载。用户列调整、导出及模板下载继续按权限提供，历史只读和已发布配置的受控变更边界不变。初始化与升级需使菜单、元数据、适配合同和实际能力一致，不能只有菜单可见却没有可用服务。

## 7. 拟定脚本与产物

以下均待实施，本轮没有创建：

| 产物 | 职责 |
| --- | --- |
| `script/sql/iws_inventory_management_upgrade.sql` | 集中 schema/环境参数、S0—S4 门禁、附加 DDL 编排、批回填入口和中文状态；不执行旧计划 |
| `script/sql/iws_inventory_management_verify.sql` | 结构指纹、父链、ID 映射、数量/预留守恒、唯一当前关系、流水/业务键与阻碍清单只读验收 |
| `script/sql/iws_inventory_management_concurrency_verify.sql` | 两事务储位锁、多容器位置、装载环、预留竞争、释放/消费互斥；只在隔离库执行 |
| `script/sql/iws_inventory_management_rules_seed.sql` | 通用规则/字典/menu 种子与摘要台账，只补缺、不覆盖 |
| `script/sql/iws_inventory_management_site_template.sql` | 公司、FLOOR 仓库、楼层、料号比例等站点映射模板；默认不激活 |
| `script/sql/iws_inventory_management_acceptance.sql` | 空库、旧基线、二次/三次重跑、人工改种子保护和最终切换证据 |

每份脚本头部集中声明 `@target_schema`、允许的旧 schema/版本、manifest 摘要、环境/profile、批大小、写冻结 token 和站点模板标识；执行前校验 `DATABASE()` 与唯一目标库。标识符与数据值使用 MySQL 合适的静态 DDL 或参数化入口，不按文本替换散落环境字面量。所有人员结果集用中文，机器状态码保留原值。

应用 release 同时发布代码、SQL、menu/dict、规则 manifest，并写入 `wms_schema_version`。新应用遇到旧结构、半完成批、未知 schema 摘要或未完成 cutover 时拒绝启动写流量；旧应用在 cutover 后禁止接入。应用检查失败时保持只读/停机，读取数据库门禁和检查点后再由人工决定，不自动迁移、不自动重连到默认 schema。

## 8. 验收矩阵

| 场景 | 通过标准 |
| --- | --- |
| 隔离空库 | 执行完整自足初始化一次；表、WMS 内联段摘要、menu/perms、dict/seed、无业务数据和最终中文结果一致；不要求执行升级 |
| 旧基线初始建库 | 用 manifest 原字节建同版隔离库；执行升级、二次和三次重跑，结构/摘要/业务键不变，不重复菜单、种子或回填 |
| 本期目录与边界 | 业务/计划类型目录和允许挂载关系可幂等初始化，fixture 验证分离与挂载；归档清单不含 `FREEZE/UNFREEZE` 业务种子、专用 menu/perms 或流程，也不含后续作业模板注册、发布或实例；锁定与安全释放仍按本期合同验收 |
| 公司/楼层映射 | 无公司映射阻断；楼层属于仓库类型配置；规则输出已解析仓库 ID；允许同公司同楼层多仓库，歧义外部映射阻断，不出现参考项目专属类别或额外楼层父级 |
| 空间承接 | 每个仓库 `company_id` 非空，每条物料库区/设备库区/储位父链完整、唯一、成环为 0；旧 ID/编码/坐标可追 |
| 装载与位置 | 每个 MATERIAL/CARRIER 节点至多一个当前父；容器、格口、库存引用一致；新并发约束先通过再退役旧 `uk_wms_container_storage`；无双位置权威 |
| 数量与预留 | 按物料、单位、状态比较旧库存与目标库存总量守恒；`reserved_qty` 恰等于可证明预留明细合计；缺持有人/越界为零容忍 |
| 流水与业务 | 旧流水行数、幂等键、发生时间和数量不变；旧业务单/历史/推送/冻结仍在且冻结只可读；旧计划和实例数不增加 |
| 安全与顺序 | 非法树/停用/全叶 ALL/不可拆/旧归属/best-fit 稳定键/锁转占用/释放证据/UNKNOWN 保留均覆盖；安全规则不能被关闭 |
| 人工修改种子 | 同 code/version 人工摘要不同即阻断；人工行值不变；显式新 version 才产生新版本，不覆盖旧版本 |
| 失败恢复 | 任一批 DDL/回填/验收失败停止，中文阻碍可查；检查点可从已核实状态前向续跑，不自动重放、不删旧数据 |

成功判定依据是目标结构、数量守恒、父子/引用一致、业务键与预留证据；客户端 affected rows、无异常退出或“表已存在”不能单独作为成功。

## 9. 实际读取范围

| 材料 | 关键行 |
| --- | --- |
| `C:/code/company/doc/innolux-iws/agent.md` | 兼容、数据库写入、SQL 交付与项目边界 |
| `C:/code/company/doc/innolux-iws/references/data.md` | 初始化、旧库升级、当前模型与禁止重跑说明 |
| `sql-idempotent-upgrade/SKILL.md`、`chinese-technical-writing/SKILL.md`、`patch-editing/SKILL.md` | 幂等、目标结构比对、中文结果、文档与新增文件格式 |
| `docs/inventory-management-design-20261026.md` | `:32-46,61-94,136-164,176-184,202-256,288-304` |
| `docs/inventory-allocation-rules-20261026.md` | `:28-41,59-77,79-118` |
| `docs/inventory-management-web-crud-plan-20261026.md` | 三类身份、页面/对象、阶段与验收边界 |
| `script/sql/iws_wms_smt_core.sql` | `:28-93,136-190,230-369,377-824` |
| `script/sql/iws_init_from_empty.sql` | `:1-84,124-130,2556,5294-5537,5546-5759` |
| `vietnam_avc` 当前源 | `innolux-wms/src/main/java/com/ruoyi/wms/inventory/warehouse/enums/AreaTypeEnums.java:9-20,48-55,89-107,125-140`；`innolux-wms/src/main/java/com/ruoyi/wms/outbound/support/InventoryCountReelSelectSupport.java:28-29,42-111,155-206,242-277`；`innolux-wms/src/main/resources/mapper/InventoryCountLockableReelQueryMapper.xml:16-43`；`innolux-wms/src/main/java/com/ruoyi/wms/inventory/freeze/service/impl/WmsInventoryFreezeServiceImpl.java:83-170,173-215,528-570`；`innolux-wms/src/main/resources/mapper/SMTInfoMapper.xml:156-191`；`innolux-wms/src/main/java/com/ruoyi/wms/inventory/warehouse/service/impl/AreaPositionInfoServiceImpl.java:99-111` |
| `vietnam_pcba_avc` 当前源 | `innolux-wms/src/main/java/com/ruoyi/wms/inbound/service/impl/InboundDestinationServiceImpl.java:56-124`；`innolux-wms/src/main/resources/mapper/wms/MaterialFloorMatchMapper.xml:29-35`；`innolux-wms/src/main/java/com/ruoyi/wms/inbound/service/impl/MaterialFloorMatchServiceImpl.java:85-180,244-283`；`innolux-wms/src/main/java/com/ruoyi/wms/outbound/posting/service/PcbaOutboundPostingModeService.java:39-112`；`innolux-wms/src/main/java/com/ruoyi/wms/common/materialtrace/MaterialTraceServiceImpl.java:110-145`；`innolux-wms/src/main/java/com/ruoyi/wms/common/materialtrace/MaterialTraceHistory.java:20-41`；`innolux-wms/src/main/java/com/ruoyi/wms/common/materialtrace/MaterialTraceKeyFactory.java:12-76` |

本文只完成方案修订和只读源码核对。未来新增表写入完整初始化入口、旧库幂等升级、公司/楼层映射、规则/menu 种子、隔离与并发验收，均需另行授权实施；真实库执行、Git 暂存/提交和上线不在本次范围内。
