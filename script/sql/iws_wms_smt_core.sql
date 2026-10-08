-- ============================================================
-- IWS+ SMT 电子料仓库核心业务表初始化脚本
-- ============================================================
-- 说明：
-- 1. 本脚本独立于 script/sql/iws_init.sql。
--    iws_init.sql 只保留标准化框架、系统基础表与平台初始化数据；
--    SMT 电子料仓业务表统一放在本脚本，避免框架 SQL 与业务 SQL 混杂。
-- 2. 第一阶段模型遵循“简约、标准、可复用”原则，保留出入库与在库管理主链：
--    库区 -> 储位 -> 容器 -> 格口 -> 库存单元(REEL/盘) -> 物料，
--    并补充供应商、归档历史表、上层系统入库单推送记录与出库单推送记录。
-- 3. 本脚本以逻辑外键 + 明确索引为主，不默认创建物理外键。
--    原因：SMT 库存与流水可能达到千万级，高并发入库、出库、移库、批量导入时，
--    物理外键会放大锁等待、迁移成本和历史数据清洗成本。
-- 4. Return PO、虚拟仓、MSD/ESD、复杂冻结扣留、容器位置轨迹独立表等能力
--    不进入第一阶段核心表，后续按业务成熟度扩展。
-- 5. 建议执行顺序：先执行 iws_init.sql，再执行本脚本。
-- ============================================================

set names utf8mb4;

-- ----------------------------
-- 1、库区表
-- ----------------------------
-- 用途：
-- 1. 定义仓库内的业务区域，例如普通货物区、品质区、退料区、播种墙、亮灯货架区等。
-- 2. 被储位表 wms_storage.zone_id 引用。
-- 3. 库区是库存查询、出库策略、权限隔离和现场看板统计的基础维度。
create table if not exists wms_warehouse_zone (
    zone_id        bigint(20)    not null                         comment '库区主键，系统生成',
    zone_code      varchar(50)   not null                         comment '库区编码，业务唯一',
    zone_name      varchar(100)  not null                         comment '库区名称',
    zone_type      tinyint       not null default 1               comment '库区类型：1普通库区，2混料区，3品质区，4退料区，5播种墙，6亮灯货架区，7虚拟区',
    plant_code     varchar(50)   default null                     comment '厂别编码，跨厂区部署时使用',
    warehouse_code varchar(50)   default null                     comment '仓库编码，对应现场仓别或仓库类型',
    parent_zone_id bigint(20)    default null                     comment '上级库区主键，逻辑关联 wms_warehouse_zone.zone_id',
    allow_inbound  tinyint       not null default 1               comment '是否允许入库：0否，1是',
    allow_outbound tinyint       not null default 1               comment '是否允许出库：0否，1是',
    status         tinyint       not null default 1               comment '状态：0停用，1启用',
    sort_no        int           not null default 0               comment '显示顺序',
    del_flag       char(1)       not null default '0'             comment '删除标志：0存在，1删除',
    create_dept    bigint(20)    default null                     comment '创建部门',
    create_by      bigint(20)    default null                     comment '创建者',
    create_time    datetime      not null default current_timestamp comment '创建时间',
    update_by      bigint(20)    default null                     comment '更新者',
    update_time    datetime      default null on update current_timestamp comment '更新时间',
    remark         varchar(500)  default null                     comment '备注',
    primary key (zone_id),
    unique key uk_wms_zone_code (zone_code),
    key idx_wms_zone_plant_wh_status (plant_code, warehouse_code, status),
    key idx_wms_zone_type_status (zone_type, status),
    key idx_wms_zone_parent (parent_zone_id)
) engine=innodb default charset=utf8mb4 comment='WMS库区表';

-- ----------------------------
-- 2、储位表
-- ----------------------------
-- 用途：
-- 1. 定义货架、亮灯格位、播种墙格位、端口位等可放置容器的位置。
-- 2. 储位不保存 container_id，避免与容器表双写。
-- 3. 容器当前位置以 wms_container.storage_id 为准；
--    wms_container.storage_id 上有唯一索引，保证一个储位最多只有一个容器。
create table if not exists wms_storage (
    storage_id     bigint(20)    not null                         comment '储位主键，系统生成',
    storage_code   varchar(64)   not null                         comment '储位编码，业务唯一',
    zone_id        bigint(20)    not null                         comment '所属库区主键，逻辑关联 wms_warehouse_zone.zone_id',
    storage_type   tinyint       not null default 1               comment '储位类型：1普通储位，2亮灯货架位，3播种墙位，4端口位',
    height_type    tinyint       default null                     comment '高度类型，用于容器高度匹配',
    height_desc    varchar(50)   default null                     comment '高度描述',
    row_no         int           default null                     comment '排/行号',
    col_no         int           default null                     comment '列号',
    level_no       int           default null                     comment '层号',
    x_coordinate   decimal(12,3) default null                     comment 'X坐标，用于WCS/地图展示',
    y_coordinate   decimal(12,3) default null                     comment 'Y坐标，用于WCS/地图展示',
    port_code      varchar(64)   default null                     comment '端口编码，端口位或设备交互位使用',
    wcs_point_code varchar(64)   default null                     comment 'WCS点位编码',
    status         tinyint       not null default 0               comment '储位状态：0空闲，1占用，2预占，3维护',
    lock_status    tinyint       not null default 0               comment '锁定状态：0未锁定，1已锁定',
    disable_status tinyint       not null default 0               comment '禁用状态：0启用，1禁用',
    version        int           not null default 0               comment '乐观锁版本号',
    del_flag       char(1)       not null default '0'             comment '删除标志：0存在，1删除',
    create_dept    bigint(20)    default null                     comment '创建部门',
    create_by      bigint(20)    default null                     comment '创建者',
    create_time    datetime      not null default current_timestamp comment '创建时间',
    update_by      bigint(20)    default null                     comment '更新者',
    update_time    datetime      default null on update current_timestamp comment '更新时间',
    remark         varchar(500)  default null                     comment '备注',
    primary key (storage_id),
    unique key uk_wms_storage_code (storage_code),
    key idx_wms_storage_zone_status (zone_id, storage_type, status, lock_status, disable_status),
    key idx_wms_storage_matrix (zone_id, row_no, col_no, level_no),
    key idx_wms_storage_port (port_code, status),
    key idx_wms_storage_wcs_point (wcs_point_code)
) engine=innodb default charset=utf8mb4 comment='WMS储位表';

-- ----------------------------
-- 3、容器类型表
-- ----------------------------
-- 用途：
-- 1. 定义料盒、料箱、托盘、料车等容器规格。
-- 2. 被容器表 wms_container.container_type_id 引用。
-- 3. is_divided 与 compartment_count 决定是否生成格口。
create table if not exists wms_container_type (
    container_type_id   bigint(20)    not null                         comment '容器类型主键，系统生成',
    container_type_code varchar(50)   not null                         comment '容器类型编码，业务唯一',
    container_type_name varchar(100)  not null                         comment '容器类型名称',
    container_category  tinyint       not null default 1               comment '容器大类：1料盒，2料箱，3托盘，4料车，9其他',
    length_value        decimal(12,3) default null                     comment '长度',
    width_value         decimal(12,3) default null                     comment '宽度',
    height_value        decimal(12,3) default null                     comment '高度',
    dimension_unit      varchar(16)   default 'mm'                     comment '尺寸单位',
    weight_limit        decimal(18,3) default null                     comment '承重上限',
    weight_unit         varchar(16)   default 'kg'                     comment '重量单位',
    is_divided          tinyint       not null default 0               comment '是否分格：0否，1是',
    compartment_count   int           default null                     comment '格口数量，is_divided=1时使用',
    status              tinyint       not null default 1               comment '状态：0停用，1启用',
    del_flag            char(1)       not null default '0'             comment '删除标志：0存在，1删除',
    create_dept         bigint(20)    default null                     comment '创建部门',
    create_by           bigint(20)    default null                     comment '创建者',
    create_time         datetime      not null default current_timestamp comment '创建时间',
    update_by           bigint(20)    default null                     comment '更新者',
    update_time         datetime      default null on update current_timestamp comment '更新时间',
    remark              varchar(500)  default null                     comment '备注',
    primary key (container_type_id),
    unique key uk_wms_container_type_code (container_type_code),
    key idx_wms_container_type_category (container_category, status),
    key idx_wms_container_type_divided (is_divided, compartment_count, status)
) engine=innodb default charset=utf8mb4 comment='WMS容器类型表';

-- ----------------------------
-- 4、容器表
-- ----------------------------
-- 用途：
-- 1. 记录每一个实物容器，例如一个料盒、一个料箱、一个托盘。
-- 2. storage_id 是容器当前位置的权威字段，逻辑关联 wms_storage.storage_id。
-- 3. zone_id 是当前位置库区快照，便于库存查询和看板统计，移动时由业务服务同步。
create table if not exists wms_container (
    container_id      bigint(20)   not null                         comment '容器主键，系统生成',
    container_code    varchar(64)  not null                         comment '容器编码，业务唯一',
    container_type_id bigint(20)   not null                         comment '容器类型主键，逻辑关联 wms_container_type.container_type_id',
    storage_id        bigint(20)   default null                     comment '当前储位主键，逻辑关联 wms_storage.storage_id；为空表示库外或在途',
    zone_id           bigint(20)   default null                     comment '当前库区主键快照，逻辑关联 wms_warehouse_zone.zone_id',
    container_status  tinyint      not null default 0               comment '容器状态：0空容器，1有库存，2报废',
    location_status   tinyint      not null default 0               comment '位置状态：0库外，1库内，2在途',
    lock_status       tinyint      not null default 0               comment '锁定状态：0未锁定，1已锁定',
    a_face_code       varchar(64)  default null                     comment 'A面标签/面码',
    b_face_code       varchar(64)  default null                     comment 'B面标签/面码',
    end_point_code    varchar(64)  default null                     comment '终点/设备点位编码',
    last_in_time      datetime     default null                     comment '最近入库时间',
    last_out_time     datetime     default null                     comment '最近出库时间',
    version           int          not null default 0               comment '乐观锁版本号',
    del_flag          char(1)      not null default '0'             comment '删除标志：0存在，1删除',
    create_dept       bigint(20)   default null                     comment '创建部门',
    create_by         bigint(20)   default null                     comment '创建者',
    create_time       datetime     not null default current_timestamp comment '创建时间',
    update_by         bigint(20)   default null                     comment '更新者',
    update_time       datetime     default null on update current_timestamp comment '更新时间',
    remark            varchar(500) default null                     comment '备注',
    primary key (container_id),
    unique key uk_wms_container_code (container_code),
    unique key uk_wms_container_storage (storage_id),
    unique key uk_wms_container_a_face (a_face_code),
    unique key uk_wms_container_b_face (b_face_code),
    key idx_wms_container_type_status (container_type_id, container_status),
    key idx_wms_container_zone_location (zone_id, location_status, container_status),
    key idx_wms_container_lock (lock_status, location_status)
) engine=innodb default charset=utf8mb4 comment='WMS容器表';

-- ----------------------------
-- 5、容器格口表
-- ----------------------------
-- 用途：
-- 1. 当容器需要分格管理时，记录容器内部格口。
-- 2. 库存可直接落在容器，也可落在容器格口。
-- 3. 不分格容器可以不生成格口，库存的 compartment_id/compartment_code 为空。
create table if not exists wms_compartment (
    compartment_id   bigint(20)   not null                         comment '格口主键，系统生成',
    container_id     bigint(20)   not null                         comment '所属容器主键，逻辑关联 wms_container.container_id',
    container_code   varchar(64)  not null                         comment '所属容器编码快照',
    container_type_id bigint(20)  default null                     comment '容器类型主键快照',
    compartment_code varchar(50)  not null                         comment '格口编码，同一容器内唯一',
    status           tinyint      not null default 0               comment '格口状态：0空闲，1占用，2禁用',
    sort_no          int          not null default 0               comment '格口顺序',
    create_time      datetime     not null default current_timestamp comment '创建时间',
    update_time      datetime     default null on update current_timestamp comment '更新时间',
    remark           varchar(500) default null                     comment '备注',
    primary key (compartment_id),
    unique key uk_wms_compartment_container_code (container_id, compartment_code),
    key idx_wms_compartment_container_status (container_id, status),
    key idx_wms_compartment_code_status (container_code, status)
) engine=innodb default charset=utf8mb4 comment='WMS容器格口表';

-- ----------------------------
-- 6、供应商表
-- ----------------------------
-- 用途：
-- 1. 定义供应商基础资料，参考项目存在 t_supplier，并被物料、入库、库存、出库条件引用。
-- 2. 业务表保留 supplier_code / supplier_name 快照，避免历史查询时强依赖主数据当前值。
-- 3. 第一阶段只保留通用联系信息，不引入供应商资质、账期、等级等扩展字段。
create table if not exists wms_supplier (
    supplier_id     bigint(20)   not null                         comment '供应商主键，系统生成',
    supplier_code   varchar(50)  not null                         comment '供应商编码，业务唯一',
    supplier_name   varchar(100) not null                         comment '供应商名称',
    contact_person  varchar(50)  default null                     comment '联系人',
    contact_phone   varchar(30)  default null                     comment '联系电话',
    contact_email   varchar(100) default null                     comment '联系邮箱',
    address         varchar(255) default null                     comment '地址',
    status          tinyint      not null default 1               comment '状态：0停用，1启用',
    version         int          not null default 0               comment '乐观锁版本号',
    del_flag        char(1)      not null default '0'             comment '删除标志：0存在，1删除',
    create_dept     bigint(20)   default null                     comment '创建部门',
    create_by       bigint(20)   default null                     comment '创建者',
    create_time     datetime     not null default current_timestamp comment '创建时间',
    update_by       bigint(20)   default null                     comment '更新者',
    update_time     datetime     default null on update current_timestamp comment '更新时间',
    remark          varchar(500) default null                     comment '备注',
    primary key (supplier_id),
    unique key uk_wms_supplier_code (supplier_code),
    key idx_wms_supplier_name (supplier_name),
    key idx_wms_supplier_status (status, del_flag),
    key idx_wms_supplier_update_time (update_time, supplier_id)
) engine=innodb default charset=utf8mb4 comment='WMS供应商表';

-- ----------------------------
-- 7、物料表
-- ----------------------------
-- 用途：
-- 1. 定义 SMT 电子料的基础资料。
-- 2. 库存表以 material_code 关联物料，保留 material_name 等快照字段用于历史查询。
-- 3. 默认供应商通过 supplier_code 逻辑关联 wms_supplier.supplier_code，物料表同时保留供应商名称快照。
create table if not exists wms_material (
    material_id        bigint(20)    not null                         comment '物料主键，系统生成',
    material_code      varchar(50)   not null                         comment '物料编码，业务唯一',
    material_name      varchar(200)  not null                         comment '物料名称',
    material_type      varchar(50)   default null                     comment '物料类型',
    material_grade     varchar(50)   default null                     comment '物料等级',
    package_quantity   decimal(20,6) default null                     comment '标准包装数量',
    unit               varchar(16)   default null                     comment '库存单位',
    length_value       decimal(12,3) default null                     comment '长度',
    width_value        decimal(12,3) default null                     comment '宽度',
    height_value       decimal(12,3) default null                     comment '高度',
    dimension_unit     varchar(16)   default 'mm'                     comment '尺寸单位',
    weight_value       decimal(18,6) default null                     comment '单件重量',
    weight_unit        varchar(16)   default 'g'                      comment '重量单位',
    volume_value       decimal(18,6) default null                     comment '体积',
    validity_period    int           default null                     comment '有效期数值',
    validity_unit      varchar(16)   default null                     comment '有效期单位：DAY、MONTH、YEAR',
    exposure_time      int           default null                     comment '暴露时长',
    exposure_unit      varchar(16)   default null                     comment '暴露时长单位：HOUR、DAY',
    supplier_id        bigint(20)    default null                     comment '默认供应商主键，逻辑关联 wms_supplier.supplier_id',
    supplier_code      varchar(50)   default null                     comment '默认供应商编码，逻辑关联 wms_supplier.supplier_code',
    supplier_name      varchar(100)  default null                     comment '默认供应商名称快照',
    status             tinyint       not null default 1               comment '状态：0停用，1启用',
    del_flag           char(1)       not null default '0'             comment '删除标志：0存在，1删除',
    create_dept        bigint(20)    default null                     comment '创建部门',
    create_by          bigint(20)    default null                     comment '创建者',
    create_time        datetime      not null default current_timestamp comment '创建时间',
    update_by          bigint(20)    default null                     comment '更新者',
    update_time        datetime      default null on update current_timestamp comment '更新时间',
    remark             varchar(500)  default null                     comment '备注',
    primary key (material_id),
    unique key uk_wms_material_code (material_code),
    key idx_wms_material_type_status (material_type, status),
    key idx_wms_material_grade_status (material_grade, status),
    key idx_wms_material_supplier_id (supplier_id),
    key idx_wms_material_supplier_status (supplier_code, status)
) engine=innodb default charset=utf8mb4 comment='WMS物料表';

-- ----------------------------
-- 8、库存表
-- ----------------------------
-- 用途：
-- 1. 记录在库、待入库、预留、出库中的库存单元。
-- 2. SMT 场景下 stock_unit_code 通常是 REEL ID、盘号、箱号或唯一标签号。
-- 3. 为高频出库排序、亮灯找料、容器盘点、库存看板保留必要快照字段。
-- 4. reserved_qty 对应参考项目中的 lock_num 概念，用于出库扣占。
create table if not exists wms_inventory (
    inventory_id          bigint(20)    not null                         comment '库存主键，系统生成',
    stock_unit_code       varchar(100)  not null                         comment '库存单元编码，SMT通常为REEL ID/盘号/唯一标签号',
    material_code         varchar(50)   not null                         comment '物料编码，逻辑关联 wms_material.material_code',
    material_name         varchar(200)  default null                     comment '物料名称快照',
    material_batch        varchar(64)   default null                     comment '物料批次号',
    date_code             varchar(50)   default null                     comment 'Date Code',
    supplier_code         varchar(50)   default null                     comment '供应商编码',
    supplier_name         varchar(100)  default null                     comment '供应商名称快照',
    plant_code            varchar(50)   default null                     comment '厂别编码',
    warehouse_code        varchar(50)   default null                     comment '仓库编码/仓别',
    zone_id               bigint(20)    default null                     comment '当前库区主键快照，逻辑关联 wms_warehouse_zone.zone_id',
    zone_code             varchar(50)   default null                     comment '当前库区编码快照',
    storage_id            bigint(20)    default null                     comment '当前储位主键快照，逻辑关联 wms_storage.storage_id',
    storage_code          varchar(64)   default null                     comment '当前储位编码快照',
    container_id          bigint(20)    default null                     comment '当前容器主键，逻辑关联 wms_container.container_id',
    container_code        varchar(64)   default null                     comment '当前容器编码快照',
    compartment_id        bigint(20)    default null                     comment '当前格口主键，逻辑关联 wms_compartment.compartment_id',
    compartment_code      varchar(50)   default null                     comment '当前格口编码快照',
    inventory_qty         decimal(20,6) not null default 0               comment '库存数量',
    reserved_qty          decimal(20,6) not null default 0               comment '已预留/已扣占数量',
    unit                  varchar(16)   default null                     comment '库存单位',
    status                tinyint       not null default 0               comment '库存状态：0待入库，1可用，2已预留，3出库中，4已出库，5异常，6报废',
    frozen_status         tinyint       not null default 0               comment '冻结状态：0未冻结，1冻结',
    inbound_status        tinyint       not null default 0               comment '入库完成状态：0未完成，1已完成',
    quality_status        tinyint       not null default 0               comment '品质状态：0未检，1合格，2不合格，3让步',
    production_date       date          default null                     comment '生产日期',
    receiving_time        datetime      default null                     comment '收料时间',
    inbound_time          datetime      default null                     comment '入库时间',
    reserved_order_no     varchar(100)  default null                     comment '当前预留出库单号',
    reserved_order_line_id bigint(20)   default null                     comment '当前预留出库单明细主键',
    port_code             varchar(64)   default null                     comment '所在端口/作业口编码',
    source_order_no       varchar(100)  default null                     comment '来源入库单号',
    source_order_line_id  bigint(20)    default null                     comment '来源入库单明细主键',
    target_place          varchar(64)   default null                     comment '目标投递位置/机台/工位',
    label_snapshot        varchar(1000) default null                     comment '标签关键字段快照，存储轻量文本，不存大对象',
    version               int           not null default 0               comment '乐观锁版本号',
    del_flag              char(1)       not null default '0'             comment '删除标志：0存在，1删除',
    create_dept           bigint(20)    default null                     comment '创建部门',
    create_by             bigint(20)    default null                     comment '创建者',
    create_time           datetime      not null default current_timestamp comment '创建时间',
    update_by             bigint(20)    default null                     comment '更新者',
    update_time           datetime      default null on update current_timestamp comment '更新时间',
    remark                varchar(500)  default null                     comment '备注',
    primary key (inventory_id),
    unique key uk_wms_inventory_stock_unit (stock_unit_code),
    key idx_wms_inventory_material (material_code, status, inbound_status),
    key idx_wms_inventory_pick_condition (material_code, plant_code, warehouse_code, status, frozen_status, inbound_status, production_date, container_id),
    key idx_wms_inventory_reel_light (stock_unit_code, container_id, status, inbound_status),
    key idx_wms_inventory_container_comp (container_id, status, inbound_status, compartment_code),
    key idx_wms_inventory_storage (storage_id, status),
    key idx_wms_inventory_reserved (reserved_order_no, reserved_order_line_id, status),
    key idx_wms_inventory_batch (material_code, material_batch, date_code),
    key idx_wms_inventory_supplier (supplier_code, material_code, status)
) engine=innodb default charset=utf8mb4 comment='WMS库存表';

-- ----------------------------
-- 9、库存流水表
-- ----------------------------
-- 用途：
-- 1. 记录库存数量和位置变化，是入库、移库、预留、释放、拣选、出库、调整的审计依据。
-- 2. 业务单据只表达“计划/执行要求”，库存流水表达“库存事实变化”。
-- 3. idempotency_key 用于防止接口重试、WCS回调重放造成重复流水。
create table if not exists wms_inventory_flow (
    flow_id              bigint(20)    not null                         comment '库存流水主键，系统生成',
    flow_no              varchar(64)   not null                         comment '库存流水号，业务唯一',
    flow_type            varchar(32)   not null                         comment '流水类型：INBOUND、PUTAWAY、RESERVE、RELEASE、PICK、OUTBOUND、MOVE、ADJUST、FREEZE、UNFREEZE',
    inventory_id         bigint(20)    default null                     comment '库存主键快照，逻辑关联 wms_inventory.inventory_id',
    stock_unit_code      varchar(100)  not null                         comment '库存单元编码快照',
    material_code        varchar(50)   not null                         comment '物料编码快照',
    material_batch       varchar(64)   default null                     comment '物料批次快照',
    from_container_code  varchar(64)   default null                     comment '来源容器编码',
    to_container_code    varchar(64)   default null                     comment '目标容器编码',
    from_storage_code    varchar(64)   default null                     comment '来源储位编码',
    to_storage_code      varchar(64)   default null                     comment '目标储位编码',
    before_qty           decimal(20,6) default null                     comment '变更前数量',
    change_qty           decimal(20,6) not null                         comment '变更数量，增加为正，减少为负',
    after_qty            decimal(20,6) default null                     comment '变更后数量',
    source_order_type    varchar(32)   default null                     comment '来源单据类型：INBOUND、OUTBOUND、ADJUST、MOVE等',
    source_order_no      varchar(100)  default null                     comment '来源单号',
    source_order_line_id bigint(20)    default null                     comment '来源单据明细主键',
    idempotency_key      varchar(128)  not null                         comment '幂等键，同一业务动作唯一',
    operator_id          bigint(20)    default null                     comment '操作人',
    occurred_at          datetime      not null                         comment '业务发生时间',
    create_time          datetime      not null default current_timestamp comment '创建时间',
    remark               varchar(500)  default null                     comment '备注',
    primary key (flow_id),
    unique key uk_wms_inventory_flow_no (flow_no),
    unique key uk_wms_inventory_flow_idem (idempotency_key),
    key idx_wms_inventory_flow_stock_time (stock_unit_code, occurred_at),
    key idx_wms_inventory_flow_source (source_order_type, source_order_no, source_order_line_id),
    key idx_wms_inventory_flow_type_time (flow_type, occurred_at),
    key idx_wms_inventory_flow_material_time (material_code, occurred_at)
) engine=innodb default charset=utf8mb4 comment='WMS库存流水表';

-- ----------------------------
-- 10、入库单头表
-- ----------------------------
-- 用途：
-- 1. 表达采购收料、退料入库、调整入库、人工补录等入库单据和执行要求。
-- 2. 不直接代表库存事实变化，库存变化以 wms_inventory 与 wms_inventory_flow 为准。
create table if not exists wms_inbound_order (
    inbound_order_id bigint(20)   not null                         comment '入库单头主键，系统生成',
    inbound_order_no varchar(100) not null                         comment '入库单号，业务唯一',
    inbound_type     tinyint      not null default 1               comment '入库类型：1采购入库，2退料入库，3调整入库，4人工入库',
    status           tinyint      not null default 0               comment '单据状态：0新建，1执行中，2已完成，3已取消，4异常',
    priority         tinyint      not null default 5               comment '优先级，数字越小优先级越高',
    source_system    varchar(32)  default null                     comment '来源系统，例如MES、ERP、WCS、MANUAL',
    source_order_no  varchar(100) default null                     comment '外部来源单号',
    start_time       datetime     default null                     comment '开始执行时间',
    complete_time    datetime     default null                     comment '完成时间',
    del_flag         char(1)      not null default '0'             comment '删除标志：0存在，1删除',
    create_dept      bigint(20)   default null                     comment '创建部门',
    create_by        bigint(20)   default null                     comment '创建者',
    create_time      datetime     not null default current_timestamp comment '创建时间',
    update_by        bigint(20)   default null                     comment '更新者',
    update_time      datetime     default null on update current_timestamp comment '更新时间',
    remark           varchar(500) default null                     comment '备注',
    primary key (inbound_order_id),
    unique key uk_wms_inbound_order_no (inbound_order_no),
    key idx_wms_inbound_order_status (status, priority, create_time),
    key idx_wms_inbound_order_type_status (inbound_type, status),
    key idx_wms_inbound_order_source (source_system, source_order_no)
) engine=innodb default charset=utf8mb4 comment='WMS入库单头表';

-- ----------------------------
-- 11、入库单明细表
-- ----------------------------
-- 用途：
-- 1. 表达每个 REEL/标签/物料批次的入库明细。
-- 2. 入库完成后可生成或更新 wms_inventory，并写入 wms_inventory_flow。
create table if not exists wms_inbound_order_line (
    inbound_line_id  bigint(20)    not null                         comment '入库单明细主键，系统生成',
    inbound_order_id bigint(20)    not null                         comment '入库单头主键，逻辑关联 wms_inbound_order.inbound_order_id',
    inbound_order_no varchar(100)  not null                         comment '入库单号快照',
    line_no          int           not null default 1               comment '行号',
    stock_unit_code  varchar(100)  default null                     comment '库存单元编码，SMT通常为REEL ID/盘号/唯一标签号',
    material_code    varchar(50)   not null                         comment '物料编码',
    material_name    varchar(200)  default null                     comment '物料名称快照',
    material_batch   varchar(64)   default null                     comment '物料批次号',
    date_code        varchar(50)   default null                     comment 'Date Code',
    supplier_code    varchar(50)   default null                     comment '供应商编码',
    supplier_name    varchar(100)  default null                     comment '供应商名称快照',
    plant_code       varchar(50)   default null                     comment '厂别编码',
    warehouse_code   varchar(50)   default null                     comment '仓库编码/仓别',
    plan_qty         decimal(20,6) not null default 0               comment '计划入库数量',
    actual_qty       decimal(20,6) not null default 0               comment '实际入库数量',
    unit             varchar(16)   default null                     comment '单位',
    status           tinyint       not null default 0               comment '明细状态：0待执行，1执行中，2已完成，3已取消，4异常',
    production_date  date          default null                     comment '生产日期',
    receiving_time   datetime      default null                     comment '收料时间',
    complete_time    datetime      default null                     comment '完成时间',
    create_time      datetime      not null default current_timestamp comment '创建时间',
    update_time      datetime      default null on update current_timestamp comment '更新时间',
    remark           varchar(500)  default null                     comment '备注',
    primary key (inbound_line_id),
    key idx_wms_inbound_line_order_status (inbound_order_id, status),
    key idx_wms_inbound_line_no_status (inbound_order_no, status),
    key idx_wms_inbound_line_stock (stock_unit_code),
    key idx_wms_inbound_line_material (material_code, material_batch, date_code),
    key idx_wms_inbound_line_wh (plant_code, warehouse_code, status)
) engine=innodb default charset=utf8mb4 comment='WMS入库单明细表';

-- ----------------------------
-- 12、出库单头表
-- ----------------------------
-- 用途：
-- 1. 表达工单领料、叫料出库、退供出库、调整出库等出库单据和执行要求。
-- 2. 出库单负责计划和执行状态，库存扣占与实际出库由库存表和库存流水落事实。
create table if not exists wms_outbound_order (
    outbound_order_id bigint(20)   not null                         comment '出库单头主键，系统生成',
    outbound_order_no varchar(100) not null                         comment '出库单号，业务唯一',
    outbound_type     tinyint      not null default 1               comment '出库类型：1工单领料，2叫料出库，3退供出库，4调整出库',
    status            tinyint      not null default 0               comment '单据状态：0新建，1已预留，2执行中，3已完成，4已取消，5异常',
    priority          tinyint      not null default 5               comment '优先级，数字越小优先级越高',
    pick_mode         tinyint      not null default 1               comment '拣选模式：1按物料条件，2指定库存单元，3指定容器',
    port_code         varchar(64)  default null                     comment '出库口/投递口编码',
    work_order_no     varchar(100) default null                     comment '工单号',
    side_code         varchar(50)  default null                     comment '线边/面别/站别编码',
    source_system     varchar(32)  default null                     comment '来源系统，例如MES、ERP、WCS、MANUAL',
    source_order_no   varchar(100) default null                     comment '外部来源单号',
    start_time        datetime     default null                     comment '开始执行时间',
    complete_time     datetime     default null                     comment '完成时间',
    del_flag          char(1)      not null default '0'             comment '删除标志：0存在，1删除',
    create_dept       bigint(20)   default null                     comment '创建部门',
    create_by         bigint(20)   default null                     comment '创建者',
    create_time       datetime     not null default current_timestamp comment '创建时间',
    update_by         bigint(20)   default null                     comment '更新者',
    update_time       datetime     default null on update current_timestamp comment '更新时间',
    remark            varchar(500) default null                     comment '备注',
    primary key (outbound_order_id),
    unique key uk_wms_outbound_order_no (outbound_order_no),
    key idx_wms_outbound_order_status (status, priority, create_time),
    key idx_wms_outbound_order_work (work_order_no, side_code, status),
    key idx_wms_outbound_order_source (source_system, source_order_no),
    key idx_wms_outbound_order_port (port_code, status)
) engine=innodb default charset=utf8mb4 comment='WMS出库单头表';

-- ----------------------------
-- 13、出库单明细表
-- ----------------------------
-- 用途：
-- 1. 表达出库需求行，支持按物料条件找料，也支持指定 REEL/容器出库。
-- 2. 预留成功后回写 reserved_qty、reserved_order_no、reserved_order_line_id。
-- 3. 实际出库后写入 wms_inventory_flow，并更新库存状态。
create table if not exists wms_outbound_order_line (
    outbound_line_id  bigint(20)    not null                         comment '出库单明细主键，系统生成',
    outbound_order_id bigint(20)    not null                         comment '出库单头主键，逻辑关联 wms_outbound_order.outbound_order_id',
    outbound_order_no varchar(100)  not null                         comment '出库单号快照',
    line_no           int           not null default 1               comment '行号',
    material_code     varchar(50)   not null                         comment '需求物料编码',
    material_name     varchar(200)  default null                     comment '物料名称快照',
    request_qty       decimal(20,6) not null default 0               comment '需求数量',
    reserved_qty      decimal(20,6) not null default 0               comment '已预留数量',
    issued_qty        decimal(20,6) not null default 0               comment '已出库数量',
    unit              varchar(16)   default null                     comment '单位',
    stock_unit_code   varchar(100)  default null                     comment '指定库存单元编码，指定REEL出库时使用',
    container_code    varchar(64)   default null                     comment '指定容器编码，指定容器出库时使用',
    material_batch    varchar(64)   default null                     comment '批次条件',
    date_code         varchar(50)   default null                     comment 'Date Code条件',
    supplier_code     varchar(50)   default null                     comment '供应商条件',
    supplier_name     varchar(100)  default null                     comment '供应商名称快照',
    plant_code        varchar(50)   default null                     comment '厂别条件',
    warehouse_code    varchar(50)   default null                     comment '仓库编码/仓别条件',
    target_place      varchar(64)   default null                     comment '目标投递位置/机台/工位',
    status            tinyint       not null default 0               comment '明细状态：0待预留，1已预留，2执行中，3已完成，4短缺，5已取消，6异常',
    shortage_flag     tinyint       not null default 0               comment '是否短缺：0否，1是',
    complete_time     datetime      default null                     comment '完成时间',
    create_time       datetime      not null default current_timestamp comment '创建时间',
    update_time       datetime      default null on update current_timestamp comment '更新时间',
    remark            varchar(500)  default null                     comment '备注',
    primary key (outbound_line_id),
    key idx_wms_outbound_line_order_status (outbound_order_id, status),
    key idx_wms_outbound_line_no_status (outbound_order_no, status),
    key idx_wms_outbound_line_pick (material_code, plant_code, warehouse_code, status),
    key idx_wms_outbound_line_stock (stock_unit_code),
    key idx_wms_outbound_line_container (container_code, status),
    key idx_wms_outbound_line_target (target_place, status)
) engine=innodb default charset=utf8mb4 comment='WMS出库单明细表';

-- ----------------------------
-- 14、库存历史表
-- ----------------------------
-- 用途：
-- 1. 参考项目 t_inventory_history：库存完成出库、异常关闭、人工归档后，从当前库存表迁移到库存历史表。
-- 2. 历史表保存库存归档时的业务快照，不再参与当前可用库存计算。
-- 3. 支撑按 REEL、物料、容器、储位、出库单、下架时间查询历史库存。
create table if not exists wms_inventory_history (
    inventory_history_id  bigint(20)    not null                         comment '库存历史主键，系统生成',
    source_inventory_id   bigint(20)    default null                     comment '来源库存主键，归档前的 wms_inventory.inventory_id',
    stock_unit_code       varchar(100)  not null                         comment '库存单元编码快照，SMT通常为REEL ID/盘号/唯一标签号',
    material_code         varchar(50)   not null                         comment '物料编码快照',
    material_name         varchar(200)  default null                     comment '物料名称快照',
    material_batch        varchar(64)   default null                     comment '物料批次号快照',
    date_code             varchar(50)   default null                     comment 'Date Code快照',
    supplier_code         varchar(50)   default null                     comment '供应商编码快照',
    supplier_name         varchar(100)  default null                     comment '供应商名称快照',
    plant_code            varchar(50)   default null                     comment '厂别编码快照',
    warehouse_code        varchar(50)   default null                     comment '仓库编码/仓别快照',
    zone_id               bigint(20)    default null                     comment '归档前库区主键快照',
    zone_code             varchar(50)   default null                     comment '归档前库区编码快照',
    storage_id            bigint(20)    default null                     comment '归档前储位主键快照',
    storage_code          varchar(64)   default null                     comment '归档前储位编码快照',
    container_id          bigint(20)    default null                     comment '归档前容器主键快照',
    container_code        varchar(64)   default null                     comment '归档前容器编码快照',
    compartment_id        bigint(20)    default null                     comment '归档前格口主键快照',
    compartment_code      varchar(50)   default null                     comment '归档前格口编码快照',
    inventory_qty         decimal(20,6) not null default 0               comment '归档数量',
    reserved_qty          decimal(20,6) not null default 0               comment '归档时已预留/已扣占数量',
    unit                  varchar(16)   default null                     comment '库存单位快照',
    inventory_status      tinyint       not null                         comment '归档前库存状态：参考 wms_inventory.status',
    frozen_status         tinyint       not null default 0               comment '归档前冻结状态：0未冻结，1冻结',
    inbound_status        tinyint       not null default 0               comment '归档前入库完成状态：0未完成，1已完成',
    quality_status        tinyint       not null default 0               comment '归档前品质状态：0未检，1合格，2不合格，3让步',
    production_date       date          default null                     comment '生产日期快照',
    receiving_time        datetime      default null                     comment '收料时间快照',
    inbound_time          datetime      default null                     comment '入库时间快照',
    off_shelf_time        datetime      default null                     comment '下架/出库完成时间',
    used_by_order_no      varchar(100)  default null                     comment '使用该库存的出库单号，参考项目 used_by_code',
    used_by_order_line_id bigint(20)    default null                     comment '使用该库存的出库单明细主键，参考项目 used_by_body_code',
    port_code             varchar(64)   default null                     comment '出库口/作业口编码快照',
    source_order_no       varchar(100)  default null                     comment '来源入库单号快照',
    source_order_line_id  bigint(20)    default null                     comment '来源入库单明细主键快照',
    target_place          varchar(64)   default null                     comment '目标投递位置/机台/工位快照',
    archive_reason        varchar(255)  default null                     comment '归档原因：OUTBOUND、CANCEL、ADJUST、EXCEPTION、MANUAL等',
    archive_time          datetime      not null default current_timestamp comment '归档时间',
    archived_by           bigint(20)    default null                     comment '归档操作人',
    source_create_time    datetime      default null                     comment '来源库存创建时间',
    source_update_time    datetime      default null                     comment '来源库存最后更新时间',
    remark                varchar(500)  default null                     comment '备注',
    primary key (inventory_history_id),
    key idx_wms_inv_hist_source_inventory (source_inventory_id),
    key idx_wms_inv_hist_stock_archive (stock_unit_code, archive_time),
    key idx_wms_inv_hist_material_archive (material_code, archive_time, inventory_history_id),
    key idx_wms_inv_hist_batch (material_code, material_batch, date_code),
    key idx_wms_inv_hist_container_archive (container_code, archive_time),
    key idx_wms_inv_hist_storage_archive (storage_code, archive_time),
    key idx_wms_inv_hist_used_order (used_by_order_no, used_by_order_line_id, stock_unit_code),
    key idx_wms_inv_hist_off_shelf (off_shelf_time, used_by_order_no, stock_unit_code),
    key idx_wms_inv_hist_supplier (supplier_code, material_code, archive_time)
) engine=innodb default charset=utf8mb4 comment='WMS库存历史表';

-- ----------------------------
-- 15、入库单头历史表
-- ----------------------------
-- 用途：
-- 1. 参考项目 t_inbound_order_header_history：入库单完成、取消或人工关闭后归档。
-- 2. 历史头表用于历史单据查询、看板统计、追溯单据生命周期。
create table if not exists wms_inbound_order_history (
    inbound_history_id    bigint(20)   not null                         comment '入库单头历史主键，系统生成',
    source_inbound_order_id bigint(20) default null                     comment '来源入库单头主键，归档前的 wms_inbound_order.inbound_order_id',
    inbound_order_no      varchar(100) not null                         comment '入库单号快照，业务唯一',
    inbound_type          tinyint      not null                         comment '入库类型快照：1采购入库，2退料入库，3调整入库，4人工入库',
    status                tinyint      not null                         comment '归档时单据状态：参考 wms_inbound_order.status',
    priority              tinyint      not null default 5               comment '优先级快照，数字越小优先级越高',
    source_system         varchar(32)  default null                     comment '来源系统快照',
    source_order_no       varchar(100) default null                     comment '外部来源单号快照',
    start_time            datetime     default null                     comment '开始执行时间快照',
    complete_time         datetime     default null                     comment '完成时间快照',
    archive_reason        varchar(255) default null                     comment '归档原因：COMPLETE、CANCEL、MANUAL_CLOSE、EXCEPTION等',
    archive_time          datetime     not null default current_timestamp comment '归档时间',
    archived_by           bigint(20)   default null                     comment '归档操作人',
    source_create_time    datetime     default null                     comment '来源单据创建时间',
    source_update_time    datetime     default null                     comment '来源单据最后更新时间',
    remark                varchar(500) default null                     comment '备注',
    primary key (inbound_history_id),
    unique key uk_wms_inbound_hist_no (inbound_order_no),
    key idx_wms_inbound_hist_source_id (source_inbound_order_id),
    key idx_wms_inbound_hist_status (status, priority, archive_time),
    key idx_wms_inbound_hist_type_status (inbound_type, status, archive_time),
    key idx_wms_inbound_hist_source (source_system, source_order_no)
) engine=innodb default charset=utf8mb4 comment='WMS入库单头历史表';

-- ----------------------------
-- 16、入库单明细历史表
-- ----------------------------
-- 用途：
-- 1. 参考项目 t_inbound_order_body_history：保存归档后的入库明细。
-- 2. 支撑按入库单、REEL、物料、厂别、仓别查询历史入库记录。
create table if not exists wms_inbound_order_line_history (
    inbound_line_history_id bigint(20)    not null                         comment '入库单明细历史主键，系统生成',
    source_inbound_line_id  bigint(20)    default null                     comment '来源入库单明细主键，归档前的 wms_inbound_order_line.inbound_line_id',
    inbound_history_id      bigint(20)    default null                     comment '入库单头历史主键，逻辑关联 wms_inbound_order_history.inbound_history_id',
    source_inbound_order_id bigint(20)    default null                     comment '来源入库单头主键快照',
    inbound_order_no        varchar(100)  not null                         comment '入库单号快照',
    line_no                 int           not null default 1               comment '行号快照',
    stock_unit_code         varchar(100)  default null                     comment '库存单元编码快照，SMT通常为REEL ID/盘号/唯一标签号',
    material_code           varchar(50)   not null                         comment '物料编码快照',
    material_name           varchar(200)  default null                     comment '物料名称快照',
    material_batch          varchar(64)   default null                     comment '物料批次号快照',
    date_code               varchar(50)   default null                     comment 'Date Code快照',
    supplier_code           varchar(50)   default null                     comment '供应商编码快照',
    supplier_name           varchar(100)  default null                     comment '供应商名称快照',
    plant_code              varchar(50)   default null                     comment '厂别编码快照',
    warehouse_code          varchar(50)   default null                     comment '仓库编码/仓别快照',
    plan_qty                decimal(20,6) not null default 0               comment '计划入库数量快照',
    actual_qty              decimal(20,6) not null default 0               comment '实际入库数量快照',
    unit                    varchar(16)   default null                     comment '单位快照',
    status                  tinyint       not null                         comment '归档时明细状态：参考 wms_inbound_order_line.status',
    production_date         date          default null                     comment '生产日期快照',
    receiving_time          datetime      default null                     comment '收料时间快照',
    complete_time           datetime      default null                     comment '完成时间快照',
    archive_time            datetime      not null default current_timestamp comment '归档时间',
    source_create_time      datetime      default null                     comment '来源明细创建时间',
    source_update_time      datetime      default null                     comment '来源明细最后更新时间',
    remark                  varchar(500)  default null                     comment '备注',
    primary key (inbound_line_history_id),
    key idx_wms_inbound_line_hist_source (source_inbound_line_id),
    key idx_wms_inbound_line_hist_order_status (inbound_order_no, status),
    key idx_wms_inbound_line_hist_stock (stock_unit_code, inbound_order_no),
    key idx_wms_inbound_line_hist_material (material_code, material_batch, date_code),
    key idx_wms_inbound_line_hist_wh (plant_code, warehouse_code, status),
    key idx_wms_inbound_line_hist_archive (archive_time, inbound_line_history_id)
) engine=innodb default charset=utf8mb4 comment='WMS入库单明细历史表';

-- ----------------------------
-- 17、出库单头历史表
-- ----------------------------
-- 用途：
-- 1. 参考项目 t_outbound_order_header_history：出库单完成、取消或人工关闭后归档。
-- 2. 头表保存单据执行状态、工单、端口、搬运类型、完成原因等信息。
create table if not exists wms_outbound_order_history (
    outbound_history_id     bigint(20)   not null                         comment '出库单头历史主键，系统生成',
    source_outbound_order_id bigint(20)  default null                     comment '来源出库单头主键，归档前的 wms_outbound_order.outbound_order_id',
    outbound_order_no       varchar(100) not null                         comment '出库单号快照，业务唯一',
    outbound_type           tinyint      not null                         comment '出库类型快照：1工单领料，2叫料出库，3退供出库，4调整出库',
    status                  tinyint      not null                         comment '归档时单据状态：参考 wms_outbound_order.status',
    priority                tinyint      not null default 5               comment '优先级快照，数字越小优先级越高',
    pick_mode               tinyint      not null default 1               comment '拣选模式快照：1按物料条件，2指定库存单元，3指定容器',
    port_code               varchar(64)  default null                     comment '出库口/投递口编码快照',
    other_port_code         varchar(64)  default null                     comment '备用出库口/其他出库端口',
    work_order_no           varchar(100) default null                     comment '工单号快照',
    side_code               varchar(50)  default null                     comment '线边/面别/站别编码快照',
    carry_type              tinyint      default null                     comment '搬运类型：0人工，1CTU/自动搬运',
    source_system           varchar(32)  default null                     comment '来源系统快照',
    source_order_no         varchar(100) default null                     comment '外部来源单号快照',
    start_time              datetime     default null                     comment '开始执行时间快照',
    complete_time           datetime     default null                     comment '完成时间快照',
    finish_reason           varchar(255) default null                     comment '完成/关闭原因',
    archive_reason          varchar(255) default null                     comment '归档原因：COMPLETE、CANCEL、MES_COMPLETE、MANUAL_CLOSE、EXCEPTION等',
    archive_time            datetime     not null default current_timestamp comment '归档时间',
    archived_by             bigint(20)   default null                     comment '归档操作人',
    source_create_time      datetime     default null                     comment '来源单据创建时间',
    source_update_time      datetime     default null                     comment '来源单据最后更新时间',
    remark                  varchar(500) default null                     comment '备注',
    primary key (outbound_history_id),
    unique key uk_wms_outbound_hist_no (outbound_order_no),
    key idx_wms_outbound_hist_source_id (source_outbound_order_id),
    key idx_wms_outbound_hist_status (status, priority, archive_time),
    key idx_wms_outbound_hist_work_side (work_order_no, side_code, outbound_order_no),
    key idx_wms_outbound_hist_source (source_system, source_order_no),
    key idx_wms_outbound_hist_port (port_code, status)
) engine=innodb default charset=utf8mb4 comment='WMS出库单头历史表';

-- ----------------------------
-- 18、出库单明细历史表
-- ----------------------------
-- 用途：
-- 1. 参考项目 t_outbound_order_body_history：保存归档后的出库需求行与执行结果。
-- 2. source_outbound_line_id 对应参考项目 source_body_id，用于归档后仍能把历史库存关联回原明细。
-- 3. 支撑按出库单、物料、REEL、容器、目的地、缺料状态查询历史出库记录。
create table if not exists wms_outbound_order_line_history (
    outbound_line_history_id bigint(20)    not null                         comment '出库单明细历史主键，系统生成',
    source_outbound_line_id  bigint(20)    default null                     comment '来源出库单明细主键，归档前的 wms_outbound_order_line.outbound_line_id',
    outbound_history_id      bigint(20)    default null                     comment '出库单头历史主键，逻辑关联 wms_outbound_order_history.outbound_history_id',
    source_outbound_order_id bigint(20)    default null                     comment '来源出库单头主键快照',
    outbound_order_no        varchar(100)  not null                         comment '出库单号快照',
    line_no                  int           not null default 1               comment '行号快照',
    material_code            varchar(50)   not null                         comment '需求物料编码快照',
    material_name            varchar(200)  default null                     comment '物料名称快照',
    request_qty              decimal(20,6) not null default 0               comment '需求数量快照',
    reserved_qty             decimal(20,6) not null default 0               comment '已预留数量快照',
    issued_qty               decimal(20,6) not null default 0               comment '已出库数量快照',
    unit                     varchar(16)   default null                     comment '单位快照',
    stock_unit_code          varchar(100)  default null                     comment '指定或实际出库库存单元编码快照',
    container_code           varchar(64)   default null                     comment '指定或实际出库容器编码快照',
    material_batch           varchar(64)   default null                     comment '批次条件/批次快照',
    date_code                varchar(50)   default null                     comment 'Date Code条件/快照',
    supplier_code            varchar(50)   default null                     comment '供应商编码条件/快照',
    supplier_name            varchar(100)  default null                     comment '供应商名称快照',
    plant_code               varchar(50)   default null                     comment '来源厂别条件/快照',
    warehouse_code           varchar(50)   default null                     comment '来源仓库编码/仓别条件/快照',
    dst_plant_code           varchar(50)   default null                     comment '目的厂别编码快照',
    dst_warehouse_code       varchar(50)   default null                     comment '目的仓库编码/目的仓别快照',
    production_line          varchar(50)   default null                     comment '生产线快照',
    target_place             varchar(64)   default null                     comment '目标投递位置/机台/工位快照',
    status                   tinyint       not null                         comment '归档时明细状态：参考 wms_outbound_order_line.status',
    shortage_flag            tinyint       not null default 0               comment '是否短缺：0否，1是',
    complete_time            datetime      default null                     comment '完成时间快照',
    archive_time             datetime      not null default current_timestamp comment '归档时间',
    source_create_time       datetime      default null                     comment '来源明细创建时间',
    source_update_time       datetime      default null                     comment '来源明细最后更新时间',
    remark                   varchar(500)  default null                     comment '备注',
    primary key (outbound_line_history_id),
    key idx_wms_out_line_hist_source (source_outbound_line_id),
    key idx_wms_out_line_hist_order_status (outbound_order_no, status),
    key idx_wms_out_line_hist_pick (material_code, plant_code, warehouse_code, status),
    key idx_wms_out_line_hist_stock (stock_unit_code, outbound_order_no),
    key idx_wms_out_line_hist_container (container_code, status),
    key idx_wms_out_line_hist_target (target_place, status),
    key idx_wms_out_line_hist_shortage (shortage_flag, archive_time),
    key idx_wms_out_line_hist_archive (archive_time, outbound_line_history_id)
) engine=innodb default charset=utf8mb4 comment='WMS出库单明细历史表';

-- ----------------------------
-- 19、入库单推送记录表
-- ----------------------------
-- 用途：
-- 1. 参考项目 t_inbound_receive_log，用于记录 MES/ERP 等上层系统推送入库单到 WMS 的接收过程。
-- 2. 本表保存“上层系统推了什么、WMS接收结果是什么”，不保存设备侧执行指令或控制记录。
-- 3. 入库单业务数据落到 wms_inbound_order / wms_inbound_order_line，本表只做接口审计、失败排查和重推追踪。
create table if not exists wms_inbound_order_push_log (
    inbound_push_log_id bigint(20)    not null                         comment '入库单推送记录主键，系统生成',
    external_request_no varchar(100)  default null                     comment '上层系统请求流水号/消息ID',
    source_system       varchar(32)   not null default 'MES'           comment '来源上层系统：MES、ERP、SRM、MANUAL等',
    interface_code      varchar(64)   not null                         comment '接口编码，例如IWS203-1、IWS203-2、INBOUND_ORDER_PUSH',
    interface_name      varchar(100)  default null                     comment '接口名称',
    inbound_order_no    varchar(100)  default null                     comment '入库单号，接收报文中可能为空或失败时解析不到',
    source_order_no     varchar(100)  default null                     comment '上层系统原始单号',
    inbound_type        tinyint       default null                     comment '入库类型快照：1采购入库，2退料入库，3调整入库，4人工入库',
    stock_unit_code     varchar(100)  default null                     comment '库存单元编码，SMT通常为REEL ID；单笔推送或主查询条件使用',
    stock_unit_list     longtext      default null                     comment '本次推送涉及的库存单元清单JSON，批量推送时使用',
    push_stage          tinyint       not null default 1               comment '推送阶段：1整单，2单头，3明细，4状态确认',
    attempt_no          int           not null default 1               comment '同一来源单据同一接口第几次推送/接收',
    total_count         int           default null                     comment '本次报文总明细数',
    success_count       int           default null                     comment 'WMS本次成功接收明细数',
    failed_count        int           default null                     comment 'WMS本次失败明细数',
    failed_stock_units  longtext      default null                     comment '失败库存单元清单JSON，例如失败REEL清单',
    failed_items        longtext      default null                     comment '失败明细清单JSON，例如失败REEL/行号/错误原因',
    request_payload     longtext      default null                     comment '上层系统推送请求报文JSON',
    response_payload    longtext      default null                     comment 'WMS响应报文JSON',
    status              tinyint       not null default 0               comment '接收状态：0处理中，1成功，2部分成功，3失败',
    error_code          varchar(64)   default null                     comment '错误码/上层系统返回码',
    error_message       longtext      default null                     comment '错误信息/异常摘要',
    retry_count         int           not null default 0               comment '已重试次数',
    idempotency_key     varchar(128)  default null                     comment '幂等键，同一上层系统推送动作唯一',
    cost_time_ms        bigint(20)    default null                     comment '接口处理耗时，单位毫秒',
    received_time       datetime      default null                     comment 'WMS接收到推送的时间',
    create_by           bigint(20)    default null                     comment '创建者/处理人',
    create_time         datetime      not null default current_timestamp comment '创建时间',
    update_time         datetime      default null on update current_timestamp comment '更新时间',
    remark              varchar(500)  default null                     comment '备注',
    primary key (inbound_push_log_id),
    unique key uk_wms_inbound_push_idem (idempotency_key),
    key idx_wms_inbound_push_request (external_request_no),
    key idx_wms_inbound_push_order (inbound_order_no, interface_code, create_time),
    key idx_wms_inbound_push_source (source_system, source_order_no),
    key idx_wms_inbound_push_stock (stock_unit_code, create_time),
    key idx_wms_inbound_push_status (status, create_time),
    key idx_wms_inbound_push_time (create_time, inbound_push_log_id)
) engine=innodb default charset=utf8mb4 comment='WMS入库单推送记录表';

-- ----------------------------
-- 20、出库单推送记录表
-- ----------------------------
-- 用途：
-- 1. 参考项目 t_outbound_receive_log，用于记录 MES/ERP 等上层系统推送出库单到 WMS 的接收过程。
-- 2. 本表保存“上层系统推了什么、WMS接收结果是什么”，不保存设备侧执行指令或控制记录。
-- 3. 出库单业务数据落到 wms_outbound_order / wms_outbound_order_line，本表只做接口审计、失败排查和重推追踪。
create table if not exists wms_outbound_order_push_log (
    outbound_push_log_id bigint(20)    not null                         comment '出库单推送记录主键，系统生成',
    external_request_no  varchar(100)  default null                     comment '上层系统请求流水号/消息ID',
    source_system        varchar(32)   not null default 'MES'           comment '来源上层系统：MES、ERP、SRM、MANUAL等',
    interface_code       varchar(64)   not null                         comment '接口编码，例如IWS108-1、IWS108-2、IWS108-3、OUTBOUND_ORDER_PUSH',
    interface_name       varchar(100)  default null                     comment '接口名称',
    outbound_order_no    varchar(100)  default null                     comment '出库单号，接收报文中可能为空或失败时解析不到',
    source_order_no      varchar(100)  default null                     comment '上层系统原始单号',
    outbound_type        tinyint       default null                     comment '出库类型快照：1工单领料，2叫料出库，3退供出库，4调整出库',
    work_order_no        varchar(100)  default null                     comment '工单号快照',
    side_code            varchar(50)   default null                     comment '线边/面别/站别编码快照',
    stock_unit_code      varchar(100)  default null                     comment '库存单元编码，SMT通常为REEL ID；指定REEL出库或主查询条件使用',
    stock_unit_list      longtext      default null                     comment '本次推送涉及的库存单元清单JSON，批量或指定REEL出库时使用',
    push_stage           tinyint       not null default 1               comment '推送阶段：1整单，2单头，3明细，4状态确认',
    attempt_no           int           not null default 1               comment '同一来源单据同一接口第几次推送/接收',
    total_count          int           default null                     comment '本次报文总明细数',
    success_count        int           default null                     comment 'WMS本次成功接收明细数',
    failed_count         int           default null                     comment 'WMS本次失败明细数',
    failed_stock_units   longtext      default null                     comment '失败库存单元清单JSON，例如失败REEL清单',
    failed_items         longtext      default null                     comment '失败明细清单JSON，例如失败REEL/行号/错误原因',
    request_payload      longtext      default null                     comment '上层系统推送请求报文JSON',
    response_payload     longtext      default null                     comment 'WMS响应报文JSON',
    status               tinyint       not null default 0               comment '接收状态：0处理中，1成功，2部分成功，3失败',
    error_code           varchar(64)   default null                     comment '错误码/上层系统返回码',
    error_message        longtext      default null                     comment '错误信息/异常摘要',
    retry_count          int           not null default 0               comment '已重试次数',
    idempotency_key      varchar(128)  default null                     comment '幂等键，同一上层系统推送动作唯一',
    cost_time_ms         bigint(20)    default null                     comment '接口处理耗时，单位毫秒',
    received_time        datetime      default null                     comment 'WMS接收到推送的时间',
    create_by            bigint(20)    default null                     comment '创建者/处理人',
    create_time          datetime      not null default current_timestamp comment '创建时间',
    update_time          datetime      default null on update current_timestamp comment '更新时间',
    remark               varchar(500)  default null                     comment '备注',
    primary key (outbound_push_log_id),
    unique key uk_wms_outbound_push_idem (idempotency_key),
    key idx_wms_outbound_push_request (external_request_no),
    key idx_wms_outbound_push_order (outbound_order_no, interface_code, create_time),
    key idx_wms_outbound_push_source (source_system, source_order_no),
    key idx_wms_outbound_push_work (work_order_no, side_code, create_time),
    key idx_wms_outbound_push_stock (stock_unit_code, create_time),
    key idx_wms_outbound_push_status (status, create_time),
    key idx_wms_outbound_push_time (create_time, outbound_push_log_id)
) engine=innodb default charset=utf8mb4 comment='WMS出库单推送记录表';

-- ============================================================
-- 第一阶段核心关系说明
-- ============================================================
-- 1. wms_warehouse_zone.zone_id -> wms_storage.zone_id
--    一个库区下有多个储位。
-- 2. wms_storage.storage_id -> wms_container.storage_id
--    一个储位最多放一个容器，由 uk_wms_container_storage 保证。
-- 3. wms_container.container_type_id -> wms_container_type.container_type_id
--    一个容器属于一个容器类型。
-- 4. wms_container.container_id -> wms_compartment.container_id
--    一个分格容器下有多个格口。
-- 5. wms_supplier.supplier_code -> wms_material.supplier_code / wms_inventory.supplier_code
--    供应商作为轻量主数据存在，业务表保留供应商编码和名称快照。
-- 6. wms_material.material_code -> wms_inventory.material_code
--    库存按物料编码关联物料，同时保留物料名称快照。
-- 7. wms_inventory.container_id / compartment_id / storage_id
--    表示库存当前所在容器、格口、储位，是在库查询、亮灯找料、出库排序的核心字段。
-- 8. wms_inventory_flow
--    记录库存事实变化；出入库单据只记录计划和执行状态。
-- 9. wms_inventory_history
--    归档已出库、异常关闭或人工归档的库存，不参与当前可用库存计算。
-- 10. wms_inbound_order_history / wms_inbound_order_line_history
--    归档已完成、取消或关闭的入库单头与明细。
-- 11. wms_outbound_order_history / wms_outbound_order_line_history
--    归档已完成、取消或关闭的出库单头与明细，并通过 source_outbound_line_id 与库存历史追溯。
-- 12. wms_inbound_order_push_log
--    记录 MES/ERP 等上层系统推送入库单到 WMS 的请求、响应、接收结果和失败明细。
-- 13. wms_outbound_order_push_log
--    记录 MES/ERP 等上层系统推送出库单到 WMS 的请求、响应、接收结果和失败明细。
-- ============================================================
