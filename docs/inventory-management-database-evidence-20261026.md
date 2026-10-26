# 在库管理数据库证据附录（2026-10-26）

## 证据范围

静态基线为独立工作区 `codex/inventory-design-20261026` 的 `75602e4ce`。运行目标来自原后端工作区 `iws-admin/src/main/resources/application.yml` 的 active profile `dev`，以及 `iws-admin/src/main/resources/application-dev.yml` 的 datasource `master=jdbc:mysql://localhost:3306/innolux-iws`。

| 项目 | 本轮证据 | 可支持的结论 |
| --- | --- | --- |
| 数据库身份 | 目标为 `localhost:3306` 的 `innolux-iws` | 以下只读结果对应上述 `dev` 数据源 |
| 权限 | 实际执行 `SHOW GRANTS`，返回 `codex@localhost` 的全库 `SELECT`、`SHOW DATABASES`、`SHOW VIEW` | 可做查询核验；不具备本任务所需写权限，也没有执行 DDL/DML |
| 结构 | 五张目标表的 `INFORMATION_SCHEMA` 列查询共 128 行、索引查询共 36 行，MCP 输出均未截断 | 五表结构已做总量与关键约束核验；本附录未展开完整逐列明细，不能仅凭聚合数断言逐项完全一致 |
| 静态对应 | `script/sql/iws_wms_smt_core.sql:28-369` 中五表定义合计 128 列、36 个具名索引 | 聚合规模与只读 MCP 一致，但这不等于逐列、逐索引文本完全一致 |
| 数据量 | `COUNT(*)` 核验 `wms_warehouse_zone`、`wms_storage`、`wms_container`、`wms_inventory`、`wms_inventory_flow` 均为 0 | 可排除当前库存在上述未归档业务数据；不能证明生产数据质量或实际使用情况 |
| 版本 | `VERSION()` 被纯读 allowlist 以 `CURRENT_VERSION` 拒绝，未取得准确版本 | 迁移语法、默认隔离级别及版本相关并发行为仍待目标环境核验 |
| 执行边界 | 未做数据库写入、方案 SQL 执行或隔离事务测试 | 本附录只提供结构证据和验收要求，不构成实施或上线验证 |

## 当前结构约束

| 表 | 只读核验与静态基线 | 结构含义 |
| --- | --- | --- |
| `wms_warehouse_zone` | `zone_id` 主键；`zone_code` 唯一；`zone_type` 为 `tinyint NOT NULL`；`warehouse_code varchar(50)` 与 `parent_zone_id bigint` 均可空（`script/sql/iws_wms_smt_core.sql:28-52`）。 | 库区有单层上级引用和可选仓别文本；没有独立仓库主表。`parent_zone_id` 仅建立普通索引，脚本未声明层级成环或父记录存在的数据库约束。 |
| `wms_storage` | `zone_id NOT NULL`；`row_no/col_no/level_no int` 均可空；不存在 `depth_no`；`storage_code` 唯一（`script/sql/iws_wms_smt_core.sql:62-93`）。 | 储位必须归属库区；矩阵坐标是可选展示/检索属性，不是必填的唯一储位坐标。 |
| `wms_container` | `storage_id` 可空并受 `uk_wms_container_storage` 唯一约束；不存在父容器字段（`script/sql/iws_wms_smt_core.sql:136-166`）。 | `storage_id` 是容器当前位置权威。一个非空 `storage_id` 只能对应一个容器；多个未定位容器可保留空值。当前不能表达容器套容器。 |
| `wms_inventory` | `stock_unit_code` 唯一；`inventory_qty/reserved_qty decimal(20,6) NOT NULL`；物料、供应商、厂别、仓库、库区、储位、容器及格口属性内联；单个 `reserved_order_no/reserved_order_line_id` 表示当前预留（`script/sql/iws_wms_smt_core.sql:276-330`）。 | 一个唯一库存单元对应一条当前库存；预留以聚合数量和单组单据引用表达，没有独立预留分配明细。 |
| `wms_inventory_flow` | `idempotency_key` 唯一；来源/目标仅为 `from/to_storage_code`、`from/to_container_code`；数量含 `before_qty/change_qty/after_qty`（`script/sql/iws_wms_smt_core.sql:339-369`）。 | 可记录数量与编码快照变化；流水未直接保存来源/目标主键、库区、格口、层号或父容器快照。 |
| 表间关系 | 脚本明确采用逻辑外键和索引，未默认创建物理外键（`script/sql/iws_wms_smt_core.sql:11`、`docs/wms-smt-standard-data-model.md:56`）。 | 引用完整性、状态合法性和位置快照一致性需要应用事务、锁与校验共同保证，唯一索引本身不覆盖这些规则。 |

当前模型文档把主链限定为“库区 -> 储位 -> 容器 -> 格口 -> 库存单元（REEL/盘） -> 物料”（`docs/wms-smt-standard-data-model.md:7`、`script/sql/iws_wms_smt_core.sql:8-10`）。容器当前位置只以 `wms_container.storage_id` 为准，库存中的位置字段是查询快照，业务移动必须同步两者（`docs/wms-smt-standard-data-model.md:23`）。

## 与完整在库管理方案的差距

以下项目是形成完整方案时必须裁定的模型边界，尚未据此写成已批准 DDL。

| 方案能力 | 当前基线 | 方案需补足的决定 |
| --- | --- | --- |
| 仓库与空间层级 | 仓别是可空文本，库区只有可空 `parent_zone_id`；储位必须挂库区。 | 明确是否需要仓库主表、几级空间层级、跨仓编码范围及父子层级成环防护。 |
| 精确定位 | 无 `depth_no`；排、列、层允许空值；只有 `storage_code` 唯一。 | 明确物理坐标必填程度、深度维度、位置自然键及同仓/同区唯一范围，避免仅靠可空展示字段确定位置。 |
| 容器承载 | 容器只有一个 `storage_id`，没有父容器；非空储位最多一个容器。 | 明确箱内盒、托盘/料车载容器等嵌套规则，子容器位置继承方式，以及顶层容器独占储位的边界。 |
| 预留与扣占 | 一条库存只有聚合 `reserved_qty` 和一组预留单号/明细引用。 | 明确是否允许多订单并行预留、部分分配、改派与释放；若需要，应增加可审计的预留分配记录，并以事务维护数量守恒。 |
| 冻结、盘点和调整 | 当前只有 `frozen_status` 及流水枚举 `ADJUST/FREEZE/UNFREEZE`，没有独立冻结和盘点单据（`script/sql/iws_wms_smt_core.sql:299`、`script/sql/iws_wms_smt_core.sql:342`）。 | 明确冻结原因、范围、期限、审批与释放，以及盘点计划、盘点明细、差异确认和调整凭证是否进入本期模型。 |
| 位置变更审计 | 流水保存来源/目标编码及前后数量，不保存位置主键和完整层级。 | 明确移库、换容器、库区调整是否需要独立位置轨迹、主键级追溯，以及编码变更后的历史可读性。 |
| 状态与约束 | `tinyint` 字段由注释定义语义；无物理外键、`CHECK` 或由 MCP 证明的触发器约束。 | 明确合法状态转换、数量不等式（如 `0 <= reserved_qty <= inventory_qty`）及跨表引用的落点：数据库约束还是服务事务与巡检。 |
| 容量与性能 | 唯一索引和查询索引已存在；五表在目标库均为 0 行。 | 新方案需按真实查询、增长量和并发目标补索引与分页/归档策略；空表无法验证性能或热点竞争。 |

## 后续 DDL 验收清单

- 先取得逐表列、索引、主键、排序规则与准确 MySQL 版本；本轮只有五表聚合数量和关键约束，不能代替完整结构快照。
- 将仓库层级、深度坐标、父容器、预留分配和位置轨迹逐项裁决为纳入或暂不纳入，再形成目标字段、唯一键、索引与状态约束清单。
- 按[主规格](inventory-management-design-20261026.md)调整位置权威：物理储位与装载根关系唯一，逻辑归属单独保留版本；旧 `wms_container.storage_id` 不再作为并行可编辑的第二权威。
- 对新增层级和容器关系定义成环防护、父记录存在校验、删除/停用规则及批量修复方式。
- 使用可重复执行的升级脚本；DDL 前预检结构，DDL 后核对列、索引、字符集、注释和目标版本，并保留回退或前向修复路径。
- 在与目标同版本的隔离库中空跑、重跑并再跑一次：重复执行不得报错，不得重复增加列/索引，不得产生隐式数据删除。
- 在隔离库导入覆盖合法、空值、边界数量和异常层级的样本，执行结构与业务不变量查询；目标库当前零行不能替代这组样本。
- 对应用分批上线场景核对旧版本应用与新结构、新版本应用与旧结构的读写边界；公共契约变化另做接口和持久化回归。

## 隔离并发验收清单

- 固定测试环境、数据版本、事务隔离级别和并发会话数，记录应用实际使用的事务边界。
- 两会话同时将不同容器放入同一储位：按目标容量与混放规则原子判定；独占配置下只能一方成功，允许混放时总占用不得越界，不能继续机械使用旧的一储位一容器唯一约束。
- 并发建立父容器关系与移动容器：拒绝自引用和环，父容器与子容器的位置规则只能得到一种合法结果。
- 并发预留同一库存：`reserved_qty` 不得超过 `inventory_qty`，订单明细与库存汇总在提交后守恒，失败方不得留下半成品。
- 并发释放、改派与出库：状态、预留引用、可用数量和流水必须来自同一成功提交，禁止旧版本覆盖新版本。
- 并发移库并同步查询投影：装载根的物理位置与物料有效位置一致；陈旧写入应失败或在原规则版本下重试，物理占用不随逻辑归属重复计算。
- 同一 `idempotency_key` 并发重放：只产生一条库存流水和一个业务结果；任一流水写入或库存更新失败时，整个事务回滚。
- 对每次场景执行终态查询，核对库存数量、预留数量、容器占位、位置快照、流水数量与幂等键；再以死锁/锁等待日志确认失败可重试且没有孤儿记录。

上述 DDL 和并发验收用于证明实施后的行为；本轮只交付设计，未运行这些验收。真实库迁移、业务数据写入及上线仍需单独授权。
