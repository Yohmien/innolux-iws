-- ===================================================
-- 升级脚本：V1.0.0__wms_inventory_tables
-- 描述：创建库存相关表（库存表、库存事务流水表、库存预留表、库存冻结/扣留单头、库存冻结/扣留明细）
-- 创建日期：2026-09-16
-- 依赖：无
-- 说明：
--   1. 表名在模板表名基础上统一使用 wms_ 前缀，字段名、类型与说明取自《仓储系统数据库模型审阅》
--      （C:\code\private\innolux-rule\仓储系统数据库模型审阅.md）。
--   2. 模板中标注的“外键”仅表示关联关系，本脚本不建立数据库外键约束，被引用表
--      （wms_material、wms_material_lot、wms_container、wms_storage_position、wms_outbound_order_body、
--      wms_transfer_order_body、wms_cutting_task、wms_cutting_task_detail 等）后续再建，关联一致性由应用层保证。
--   3. 索引按模板标注建立：标注“唯一索引”的列建唯一键，标注“普通索引”的列建普通索引。
--   4. 全部语句使用 CREATE TABLE IF NOT EXISTS，可重复执行。
-- ===================================================

-- ----------------------------
-- 1、库存表
-- ----------------------------
create table if not exists wms_inventory (
    inventory_id        bigint           not null                    comment '库存主键',
    material_lot_id     bigint           default null                comment '物料批次主键',
    material_code       varchar(50)      not null                    comment '物料编码',
    container_id        varchar(50)      default null                comment '容器编码',
    original_label_info longtext                                     comment '首次入库采集的原始标签报文或标签字段快照',
    inventory_qty       decimal(20,6)    not null default 0.000000   comment '当前库存数量',
    inbound_time        datetime         default null                comment '入库时间',
    frozen_state        int              not null default 0          comment '当前冻结状态投影，冻结事实以扣留单明细为准',
    note                varchar(500)     default null                comment '备注',
    status              varchar(32)      not null                    comment '库存状态',
    production_date     date             default null                comment '生产日期',
    created_by          bigint           not null                    comment '创建人',
    create_time         datetime         not null                    comment '创建时间',
    update_by           bigint           default null                comment '更新人',
    update_time         datetime         default null                comment '更新时间',
    corporation         varchar(50)      default null                comment '库存所属法人',
    compartment_code    varchar(50)      default null                comment '容器格口编号',
    primary key (inventory_id),
    key idx_wms_inventory_material_lot_id (material_lot_id),
    key idx_wms_inventory_material_code   (material_code),
    key idx_wms_inventory_container_id    (container_id),
    key idx_wms_inventory_inbound_time    (inbound_time),
    key idx_wms_inventory_frozen_state    (frozen_state),
    key idx_wms_inventory_status          (status)
) engine=innodb comment = '库存表';

-- ----------------------------
-- 2、库存事务流水表
-- ----------------------------
create table if not exists wms_inventory_transaction (
    id                     bigint         not null auto_increment   comment '主键',
    transaction_no         varchar(64)    not null                   comment '库存事务号',
    transaction_type       varchar(32)    not null                   comment '事务类型：入库、出库、移库、盘盈盘亏、CUTTING_MOTHER_OUTBOUND、CUTTING_MOTHER_RETURN 或 CUTTING_CHILD_OUTBOUND',
    transaction_group_no   varchar(64)    not null                   comment '截料时关联母盘出库、母盘余料回库和多条子盘出库流水',
    movement_scope         varchar(16)    not null                   comment 'PHYSICAL-物理移动或 LOGICAL-逻辑库存变化',
    stock_unit_code        varchar(100)   not null                   comment '本条流水对应的母盘或子盘唯一标识',
    parent_stock_unit_code varchar(100)   default null               comment '子盘对应的母盘唯一标识',
    root_stock_unit_code   varchar(100)   not null                   comment '物料谱系根盘唯一标识',
    inventory_id           bigint         not null                   comment '库存主键',
    material_code          varchar(50)    not null                   comment '物料编码快照',
    material_lot_id        bigint         default null               comment '物料批次主键',
    container_id           varchar(50)    default null               comment '容器',
    storage_id             bigint         default null               comment '储位',
    original_label_info    longtext                                 comment '事务发生时的原始标签快照，用于库存删除后的审计追溯',
    before_qty             decimal(20,6)  not null                   comment '变更前数量',
    change_qty             decimal(20,6)  not null                   comment '本次变更数量，增加为正、减少为负',
    after_qty              decimal(20,6)  not null                   comment '变更后数量',
    source_order_type      varchar(32)    not null                   comment '来源单据类型',
    source_order_code      varchar(100)   not null                   comment '来源单号',
    source_task_type       varchar(32)    default null               comment '来源任务类型，如 CUTTING',
    source_task_id         bigint         default null               comment '来源任务主键',
    operator_id            bigint         default null               comment '操作人',
    occurred_at            datetime       not null                   comment '业务发生时间',
    idempotency_key        varchar(128)   not null                   comment '防止库存重复记账的稳定键',
    remark                 varchar(500)   default null               comment '备注',
    primary key (id),
    unique key uk_wms_inventory_transaction_no (transaction_no),
    unique key uk_wms_inventory_transaction_idempotency_key (idempotency_key),
    key idx_wms_inventory_transaction_type (transaction_type),
    key idx_wms_inventory_transaction_group_no (transaction_group_no),
    key idx_wms_inventory_transaction_movement_scope (movement_scope),
    key idx_wms_inventory_transaction_stock_unit_code (stock_unit_code),
    key idx_wms_inventory_transaction_parent_stock_unit (parent_stock_unit_code),
    key idx_wms_inventory_transaction_root_stock_unit (root_stock_unit_code),
    key idx_wms_inventory_transaction_source_order_type (source_order_type),
    key idx_wms_inventory_transaction_source_order_code (source_order_code),
    key idx_wms_inventory_transaction_occurred_at (occurred_at)
) engine=innodb comment = '库存事务流水表';

-- ----------------------------
-- 3、库存预留表
-- ----------------------------
create table if not exists wms_inventory_reservation (
    id                   bigint         not null auto_increment   comment '主键',
    reservation_no       varchar(64)    not null                   comment '预留编号',
    inventory_id         bigint         not null                   comment '被预留库存',
    material_code        varchar(50)    not null                   comment '物料编码',
    material_lot_id      bigint         default null               comment '物料批次主键',
    reserved_qty         decimal(20,6)  not null                   comment '预留数量',
    source_order_type    varchar(32)    not null                   comment '来源单据类型',
    source_order_code    varchar(100)   not null                   comment '来源单号',
    source_order_line_id bigint         default null               comment '来源单体主键',
    status               varchar(32)    not null                   comment '状态：已预留、已转实物、已释放或已过期',
    expires_at           datetime       default null               comment '预留过期时间',
    created_by           bigint         not null                   comment '创建人',
    create_time          datetime       not null                   comment '创建时间',
    released_by          bigint         default null               comment '释放人',
    released_at          datetime       default null               comment '释放时间',
    primary key (id),
    unique key uk_wms_inventory_reservation_no (reservation_no),
    key idx_wms_inventory_reservation_inventory_id (inventory_id),
    key idx_wms_inventory_reservation_source_order_code (source_order_code),
    key idx_wms_inventory_reservation_status (status),
    key idx_wms_inventory_reservation_expires_at (expires_at)
) engine=innodb comment = '库存预留表';

-- ----------------------------
-- 4、库存冻结/扣留单头
-- ----------------------------
create table if not exists wms_inventory_hold_order (
    id                 bigint         not null auto_increment   comment '主键',
    hold_order_no      varchar(64)    not null                   comment '冻结或扣留单号',
    hold_type          varchar(32)    not null                   comment '冻结类型：质量、盘点、异常、人工或合规扣留',
    scope_type         varchar(32)    not null                   comment '范围类型：按库存、物料、批次、容器、库位或条件范围',
    reason_code        varchar(64)    not null                   comment '原因编码',
    reason_description varchar(500)   not null                   comment '原因说明',
    status             varchar(32)    not null                   comment '状态：待执行、已生效、部分释放或已释放',
    requested_by       bigint         not null                   comment '发起人',
    requested_at       datetime       not null                   comment '发起时间',
    released_by        bigint         default null               comment '解除人',
    released_at        datetime       default null               comment '解除时间',
    create_time        datetime       not null                   comment '创建时间',
    update_time        datetime       default null               comment '更新时间',
    primary key (id),
    unique key uk_wms_inventory_hold_order_no (hold_order_no),
    key idx_wms_inventory_hold_order_reason_code (reason_code),
    key idx_wms_inventory_hold_order_status (status)
) engine=innodb comment = '库存冻结/扣留单头';

-- ----------------------------
-- 5、库存冻结/扣留明细
-- ----------------------------
create table if not exists wms_inventory_hold_detail (
    id             bigint         not null auto_increment   comment '主键',
    hold_order_no  varchar(64)    not null                   comment '冻结或扣留单号',
    inventory_id   bigint         not null                   comment '目标库存',
    material_code  varchar(50)    not null                   comment '物料编码快照',
    material_lot_id bigint        default null               comment '物料批次主键',
    container_id   varchar(50)    default null               comment '容器',
    storage_id     bigint         default null               comment '储位',
    held_qty       decimal(20,6)  not null                   comment '被冻结或扣留数量',
    status_before  varchar(32)    not null                   comment '生效前库存状态',
    status_after   varchar(32)    not null                   comment '生效后库存状态',
    release_status varchar(32)    not null                   comment '释放状态：未释放、部分释放或已释放',
    release_reason varchar(500)   default null               comment '释放原因',
    create_time    datetime       not null                   comment '创建时间',
    update_time    datetime       default null               comment '更新时间',
    primary key (id),
    key idx_wms_inventory_hold_detail_hold_order_no (hold_order_no),
    key idx_wms_inventory_hold_detail_inventory_id (inventory_id)
) engine=innodb comment = '库存冻结/扣留明细';
