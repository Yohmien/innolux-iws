# IWS+ SMT 电子仓标准数据模型

## 目标边界

本文只设计标准产品的基础数据模型，不把参考项目里的历史补丁、页面实现和异常处理入口直接搬进 IWS+。

本次参考内容包括：

| 来源 | 用途 |
| --- | --- |
| 用户提供的两张关系图 | 确认库区、储位、容器、容器类型、库存、物料之间的主链路 |
| `E:\Liteon` 后端代码与 Mapper | 确认实际查询场景、状态字段和高频索引方向 |
| 参考库 `liteon` 表结构 | 校验老表字段、外键和索引 |
| `E:\vnm-liteon-web` 前端页面与 API | 确认业务入口：基础资料、储位、库存、合箱、盘点、异常处理 |

参考库当前核心表量级约为：`t_inventory` 40万、`t_compartment` 66万、`t_storage_allocate_log` 40万、`t_material` 1.6万、`t_container` 1.5万。标准模型按千万级库存行设计。

## 设计结论

标准产品不继续沿用老项目 `t_` 前缀和字符串主键做内部主键，统一使用：

| 类型 | 标准 |
| --- | --- |
| 表名 | `wms_` 前缀，小写下划线 |
| 内部主键 | `bigint *_id` |
| 业务编码 | `*_code`，如 `material_code`、`container_code`、`storage_code` |
| 状态字段 | 高频表用 `tinyint`，通过字典解释 |
| 审计字段 | `create_dept/create_by/create_time/update_by/update_time/remark/del_flag` |

最关键的一点：老项目同时在 `t_container.storage_id` 和 `t_storage_position.container_id` 维护位置，容易双写不一致。标准模型新增 `wms_container_location` 作为“容器当前所在位置”的权威表，储位表只描述储位，容器表只描述容器。

## 核心表

| 标准表 | 对应老表 | 职责 |
| --- | --- | --- |
| `wms_warehouse_zone` | `t_warehouse_zones` | 库区、厂别、仓别、混料区、品质区、退料区、亮灯货架区等 |
| `wms_storage_position` | `t_storage_position` | 物理储位、货架点位、播种墙、端口位 |
| `wms_container_type` | `t_container_type` | 料箱、货架、托盘等容器规格 |
| `wms_container` | `t_container` | 容器主档、箱码、A/B面码、空满箱、锁定状态 |
| `wms_container_compartment` | `t_compartment` | 容器格口/储格 |
| `wms_container_location` | 老表双写字段拆分 | 容器当前库内、库外、在途位置 |
| `wms_container_location_log` | `t_storage_allocate_log` 等 | 容器位置变化审计流水 |
| `wms_supplier` | `t_supplier` | 供应商主数据 |
| `wms_material_grade` | `t_material_grade` | MSL、贵重料、是否允许大厅入库等等级规则 |
| `wms_material` | `t_material` | 物料主数据 |
| `wms_material_lot` | `t_inventory` 批次字段拆出 | 批次、Date Code、供应商、品质状态、有效期 |
| `wms_material_container_rule` | `t_material_container_type` | 物料可使用哪些容器类型 |
| `wms_inventory` | `t_inventory` | 当前库存事实表，千万级热表 |
| `wms_inventory_transaction` | 多处库存变化逻辑 | 库存事务流水，入库、出库、移库、盘盈亏、截料 |
| `wms_inventory_reservation` | `used_by_code/lock_num` 标准化 | 出库、调拨、Return PO 等预留 |
| `wms_inventory_hold_order` | 盘点锁、冻结、品质扣留 | 冻结/扣留单头 |
| `wms_inventory_hold_detail` | 盘点锁、冻结明细 | 冻结/扣留明细 |

## 主关系

```text
wms_warehouse_zone 1 -- N wms_storage_position
wms_warehouse_zone 1 -- N wms_container.current_zone_id
wms_container_type 1 -- N wms_container
wms_container_type 1 -- N wms_container_compartment
wms_container 1 -- N wms_container_compartment
wms_container 1 -- 1 wms_container_location
wms_storage_position 1 -- 0/1 wms_container_location
wms_material_grade 1 -- N wms_material
wms_supplier 1 -- N wms_material
wms_material 1 -- N wms_material_lot
wms_material 1 -- N wms_material_container_rule
wms_container_type 1 -- N wms_material_container_rule
wms_material 1 -- N wms_inventory
wms_material_lot 1 -- N wms_inventory
wms_container 1 -- N wms_inventory
wms_container_compartment 1 -- N wms_inventory
wms_storage_position 1 -- N wms_inventory
wms_inventory 1 -- N wms_inventory_transaction
wms_inventory 1 -- N wms_inventory_reservation
wms_inventory_hold_order 1 -- N wms_inventory_hold_detail
wms_inventory 1 -- N wms_inventory_hold_detail
```

## 字段拆分原则

`wms_material` 存“不会随单个 REEL 变化的物料主档”，如名称、尺寸、重量、等级、包规、有效期规则。

`wms_material_lot` 存“同一物料批次共有属性”，如批次号、供应商、Date Code、生产日期、收料日期、模穴、版本、品质状态。

`wms_inventory` 存“当前库存事实”，每条库存单元通常对应一个 REEL。为了千万级查询性能，库存表保留 `material_code/material_batch/container_code/storage_code/plant_code/warehouse_type/production_date` 等快照字段，不要求每次列表、出库候选、看板都 join 主数据。

`wms_container_location` 是容器位置权威表。`wms_container.current_zone_id` 只是展示和过滤快照，业务写入时必须与当前位置事务一起维护。

## 关键索引

### 出库候选

`wms_inventory`：

```sql
idx_wms_inventory_outbound_pick
(material_code, plant_code, warehouse_type, inventory_status, frozen_state,
 inbound_completed, return_po_prelock, production_date, container_id)
```

用途：支撑老项目 `queryReelIdsByParam`、`queryMixZoneReelIdsByParam` 一类按物料、厂别、仓别、状态、冻结、入库完成、D/C 先进先出的查询。

### 批次/Return PO

`wms_inventory`：

```sql
idx_wms_inventory_outbound_lot
(material_code, material_batch, plant_code, warehouse_type,
 inventory_status, inbound_completed, return_po_prelock)
```

用途：按 Lot、厂别、仓别做 Return PO 预锁或指定批次发料。

### 容器详情和合箱

`wms_inventory`：

```sql
idx_wms_inventory_container_status
(container_id, inventory_status, inbound_completed, compartment_code)
```

`wms_container`：

```sql
idx_wms_container_type_location
(container_type_id, location_status, lock_status, container_id)
```

用途：支撑容器详情、格口占用、合箱候选、空箱异常、按容器找库存。

### 储位分配

`wms_storage_position`：

```sql
idx_wms_storage_position_zone_status
(zone_id, position_type, box_height_type, position_status, lock_status, disable_status)
```

用途：按库区、储位类型、箱高、空闲/占用/锁定/禁用状态快速找可用储位。

`wms_container_location`：

```sql
uk_wms_container_location_container(container_id)
uk_wms_container_location_storage(storage_id)
```

用途：保证“一箱一个当前位置”和“一储位最多一个容器”。

### 库存审计

`wms_inventory_transaction`：

```sql
uk_wms_inventory_transaction_idempotency(idempotency_key)
idx_wms_inventory_transaction_source(source_order_type, source_order_code)
idx_wms_inventory_transaction_stock_unit(stock_unit_code)
```

用途：保证接口重试不重复记账，并按来源单据或 REEL 追踪库存变化。

## 外键策略

当前初始化脚本已建立物理外键，便于标准产品早期保证数据一致性。

实际到千万级写入或大批量迁移时建议压测两种模式：

| 模式 | 做法 | 适用场景 |
| --- | --- | --- |
| 强一致模式 | 保留物理外键 | 标准产品初期、主数据量较小、并发压力可控 |
| 高吞吐模式 | 保留同名索引，去掉物理外键，由应用校验 | 大批量导入、频繁库存移动、现场性能优先 |

即使去掉物理外键，也不能去掉关联字段索引。

## 状态建议

| 字段 | 建议值 |
| --- | --- |
| `container_status` | `0空箱`、`1非空`、`2报废` |
| `location_status` | `0库外`、`1库内`、`2在途` |
| `position_status` | `0空闲`、`1占用`、`2预占`、`3维护` |
| `inventory_status` | `0待入库`、`1可用`、`2预留`、`3出库中`、`4已出库`、`5虚拟仓`、`6异常`、`7报废` |
| `quality_status` | `0未检`、`1OK`、`2NG`、`3待检` |
| `frozen_state` | `0未冻结`、`1冻结` |
| `lock_status` | `0未锁`、`1已锁` |

这些值后续应补到 `sys_dict_type/sys_dict_data`，前后端都使用字典展示，不在代码里散落硬编码。

## 后续建议

下一步不建议马上铺满所有业务表，而是按顺序推进：

1. 先生成 `wms` 后端模块骨架，落库区、储位、容器类型、容器、物料、库存 6 条主链路。
2. 再补标准枚举、字典、状态流转校验和幂等库存事务服务。
3. 前端先做基础资料和库存查询，不急着复制老系统的所有异常处理入口。
4. 等核心链路跑通后，再扩展 ESD、MSD、亮灯货架点位、虚拟仓、合箱、盘点、Return PO 等专项表。
