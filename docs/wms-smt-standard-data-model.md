# IWS+ SMT 电子仓标准数据模型

## 当前基线与边界

本文按 2026-10-19 仓库中的 `script/sql/iws_wms_smt_core.sql` 校正，描述当前第一阶段模型。SQL 文件说明仓库设计，不证明目标数据库已执行；真实环境需另行只读核对结构和数据。

当前目标是复用库区、储位、容器、格口、库存单元与物料主链，承载基础资料、当前库存、库存流水、入出库单据、归档和上层推送记录。Return PO、虚拟仓、MSD/ESD、复杂冻结扣留和独立容器位置轨迹不属于当前核心表。

## 脚本职责

| 脚本 | 职责 |
| --- | --- |
| `script/sql/iws_init.sql` | 创建数据库并准备框架、系统基础表及平台初始化数据 |
| `script/sql/iws_wms_smt_core.sql` | 创建 WMS 核心业务表，需明确目标数据库上下文 |
| `script/sql/iws_wms_smt_demo_data.sql` | 演示数据，依赖核心表，仅供明确允许的演示环境 |

初始化准备顺序为框架 → WMS 核心表 → 可选演示数据。现有环境升级需要对照实际结构设计迁移，不以重跑整套初始化替代升级。

## 命名与核心关系

当前表名使用 `wms_` 前缀和小写下划线，内部主键为 `bigint *_id`，业务编码使用 `*_code` 或单据的 `*_no`。状态、审计、逻辑删除和版本字段按具体表定义使用，不把字段列表机械套到所有表。

容器当前位置以 `wms_container.storage_id` 为权威字段，关联 `wms_storage.storage_id`。容器的 `zone_id` 是库区快照；库存中的容器、格口和储位信息服务于在库查询。业务移动时需同步相关快照，不能另建一套互相竞争的位置事实。

| 当前表 | 职责 |
| --- | --- |
| `wms_warehouse_zone` | 库区、厂别、仓库及区域类型 |
| `wms_storage` | 物理储位、亮灯货架位、播种墙位、端口位 |
| `wms_container_type` | 容器规格、分格方式与容量 |
| `wms_container` | 容器主档、当前位置、A/B 面码、锁定状态 |
| `wms_compartment` | 容器格口 |
| `wms_supplier` | 供应商主数据 |
| `wms_material` | 物料主档 |
| `wms_inventory` | 当前库存、批次和位置快照、预留与冻结字段 |
| `wms_inventory_flow` | 库存数量及位置变化流水 |
| `wms_inbound_order`、`wms_inbound_order_line` | 入库计划与执行信息 |
| `wms_outbound_order`、`wms_outbound_order_line` | 出库计划与执行信息 |
| `wms_inventory_history` | 已归档库存 |
| `wms_inbound_order_history`、`wms_inbound_order_line_history` | 已归档入库单据 |
| `wms_outbound_order_history`、`wms_outbound_order_line_history` | 已归档出库单据 |
| `wms_inbound_order_push_log`、`wms_outbound_order_push_log` | 上层系统推送请求、响应、接收结果与失败明细 |

```text
wms_warehouse_zone 1 -- N wms_storage
wms_storage 1 -- 0/1 wms_container.storage_id
wms_container_type 1 -- N wms_container
wms_container 1 -- N wms_compartment
wms_supplier.supplier_code -> wms_material / wms_inventory 的供应商编码
wms_material.material_code -> wms_inventory.material_code
wms_inventory -> container_id / compartment_id / storage_id
入出库单头 1 -- N 入出库单明细
库存、单据 -> 对应 history 归档表
wms_inventory_flow -> 库存单元与来源单据快照
```

这些关系采用逻辑关联与明确索引，核心脚本没有默认建立物理外键。历史表保留追溯信息，不参与当前可用库存计算。

## 库存字段职责

`stock_unit_code` 是库存单元编码，SMT 场景通常对应 REEL ID、盘号或唯一标签。物料主档保存在 `wms_material`，批次、Date Code、供应商、品质、生产日期等当前库存属性保存在 `wms_inventory`，当前没有独立批次主表。

`inventory_qty`、`reserved_qty` 使用 `decimal(20,6)`。当前预留通过 `reserved_qty/reserved_order_no/reserved_order_line_id` 表达；冻结通过 `frozen_status` 表达，没有独立预留表或扣留单头、明细表。

库存中的物料名称、供应商、厂别、仓库、容器和储位等快照用于高频查询，不意味着每次查询都必须联表。入出库单据表达计划与执行状态，库存流水表达事实变化，两者不能互相替代。

## 当前关键索引

| 表 | 索引 | 用途 |
| --- | --- | --- |
| `wms_storage` | `idx_wms_storage_zone_status` | 按库区、储位类型、状态、锁定及禁用筛选 |
| `wms_container` | `uk_wms_container_storage` | 一个非空储位最多关联一个容器；NULL 位置允许多个容器 |
| `wms_container` | `idx_wms_container_type_status` | 按类型与容器状态筛选 |
| `wms_inventory` | `uk_wms_inventory_stock_unit` | 库存单元唯一 |
| `wms_inventory` | `idx_wms_inventory_pick_condition` | 物料、厂别、仓库、状态、冻结、入库完成、生产日期及容器候选 |
| `wms_inventory` | `idx_wms_inventory_container_comp` | 按容器、状态、入库完成和格口查询 |
| `wms_inventory` | `idx_wms_inventory_reserved` | 按预留单号、明细和状态追踪 |
| `wms_inventory` | `idx_wms_inventory_batch` | 物料、批次与 Date Code 查询 |
| `wms_inventory_flow` | `uk_wms_inventory_flow_idem` | 库存流水幂等键唯一 |
| `wms_inventory_flow` | `idx_wms_inventory_flow_source` | 按来源类型、单号与明细追踪 |
| `wms_inventory_flow` | `idx_wms_inventory_flow_stock_time` | 库存单元与业务时间追踪 |

完整列顺序以核心 SQL 为准。唯一索引提供结构约束，但不单独证明服务已实现完整幂等、事务或并发控制；高数据量性能需按真实查询和数据量验证。

## 当前状态含义

| 表与字段 | 当前 SQL 注释定义 |
| --- | --- |
| `wms_container.container_status` | 0 空容器、1 有库存、2 报废 |
| `wms_container.location_status` | 0 库外、1 库内、2 在途 |
| `wms_storage.status` | 0 空闲、1 占用、2 预占、3 维护 |
| `wms_inventory.status` | 0 待入库、1 可用、2 已预留、3 出库中、4 已出库、5 异常、6 报废 |
| `wms_inventory.frozen_status` | 0 未冻结、1 冻结 |
| `wms_inventory.inbound_status` | 0 未完成、1 已完成 |
| `wms_inventory.quality_status` | 0 未检、1 合格、2 不合格、3 让步 |

字典和 UI 必须与目标字段的真实值一致，不用旧版枚举替换，不把这些定义当作状态转换规则。业务状态机仍需由对应服务需求和实现确认。

## 与旧设计的差异

原文的独立位置表、批次表、物料等级表、物料容器规则表、预留表、扣留表和物理外键描述未落在当前核心脚本，本次以当前 SQL 修正。储位、格口、库存流水分别统一为 `wms_storage`、`wms_compartment`、`wms_inventory_flow`。

后续需要更复杂模型时，先明确当前需求与迁移影响，再补方案、代码与脚本；不能把旧设计中的设想视为已经存在的业务能力。
