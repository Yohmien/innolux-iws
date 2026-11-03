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
--    2026-10-28 同版补充升级控制 5 张表与库存业务类型目录 2 张表。
--    2026-10-29 同版补充库存 P3 规则、规则包与逻辑归属 12 张目标结构；
--    只建表，不写规则、种子台账、生效指针或角色授权。
--    同日补充空间、访问通道、位置版本、可见范围与容量 10 张目标结构；
--    不写业务、容量或权限种子。
--    S1 空间切片补充公司 -> 仓库 -> 库区 -> 储位的可空父链字段，以及储位
--    巷道/排/层/列/深五个可空文本地址列；唯一键、CHECK 和回填属于后续阶段。
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
    zone_kind      varchar(16)   collate utf8mb4_0900_bin not null comment '库区种类：MATERIAL/DEVICE',
    lifecycle_status varchar(16) collate utf8mb4_0900_bin not null default 'ACTIVE' comment '生命周期：ACTIVE/RETIRING/RETIRED',
    plant_code     varchar(50)   default null                     comment '厂别编码，跨厂区部署时使用',
    warehouse_code varchar(50)   default null                     comment '仓库编码，对应现场仓别或仓库类型',
    parent_zone_id bigint(20)    default null                     comment '上级库区主键，逻辑关联 wms_warehouse_zone.zone_id',
    allow_inbound  tinyint       not null default 1               comment '是否允许入库：0否，1是',
    allow_outbound tinyint       not null default 1               comment '是否允许出库：0否，1是',
    status         tinyint       not null default 0               comment '状态：0停用，1启用',
    revision       int           not null default 1               comment '库区乐观锁修订号',
    sort_no        int           not null default 0               comment '显示顺序',
    del_flag       char(1)       not null default '0'             comment '删除标志：0存在，1删除',
    create_dept    bigint(20)    default null                     comment '创建部门',
    create_by      bigint(20)    default null                     comment '创建者',
    create_time    datetime      not null default current_timestamp comment '创建时间',
    update_by      bigint(20)    default null                     comment '更新者',
    update_time    datetime      default null on update current_timestamp comment '更新时间',
    remark         varchar(500)  default null                     comment '备注',
    warehouse_id   bigint(20)    not null                         comment '所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；最终结构非空',
    primary key (zone_id),
    unique key uk_wms_zone_code (zone_code),
    key idx_wms_zone_plant_wh_status (plant_code, warehouse_code, status),
    key idx_wms_zone_type_status (zone_type, status),
    key idx_wms_zone_parent (parent_zone_id),
    key idx_wms_zone_warehouse_status (warehouse_id, status),
    key idx_wms_zone_warehouse_kind_lifecycle (warehouse_id, zone_kind, lifecycle_status),
    constraint ck_wms_zone_kind check (zone_kind in ('MATERIAL','DEVICE')),
    constraint ck_wms_warehouse_zone_lifecycle_status check (lifecycle_status in ('ACTIVE','RETIRING','RETIRED')),
    constraint ck_wms_zone_revision check (revision >= 1)
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
    disable_status tinyint       not null default 1               comment '禁用状态：0启用，1禁用',
    lifecycle_status varchar(16) collate utf8mb4_0900_bin not null default 'ACTIVE' comment '生命周期：ACTIVE/RETIRING/RETIRED',
    version        int           not null default 0               comment '乐观锁版本号',
    del_flag       char(1)       not null default '0'             comment '删除标志：0存在，1删除',
    create_dept    bigint(20)    default null                     comment '创建部门',
    create_by      bigint(20)    default null                     comment '创建者',
    create_time    datetime      not null default current_timestamp comment '创建时间',
    update_by      bigint(20)    default null                     comment '更新者',
    update_time    datetime      default null on update current_timestamp comment '更新时间',
    remark         varchar(500)  default null                     comment '备注',
    warehouse_id   bigint(20)    not null                         comment '所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；最终结构非空',
    aisle_text     varchar(64)   collate utf8mb4_0900_bin default null comment '巷道文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
    row_text       varchar(64)   collate utf8mb4_0900_bin default null comment '排文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
    level_text     varchar(64)   collate utf8mb4_0900_bin default null comment '层文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
    column_text    varchar(64)   collate utf8mb4_0900_bin default null comment '列文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
    depth_text     varchar(64)   collate utf8mb4_0900_bin default null comment '深文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
    aisle_text_is_null tinyint generated always as (aisle_text is null) stored not null comment '巷道地址空值标志；NULL按相等参与同仓地址唯一键',
    row_text_is_null tinyint generated always as (row_text is null) stored not null comment '排地址空值标志；NULL按相等参与同仓地址唯一键',
    level_text_is_null tinyint generated always as (level_text is null) stored not null comment '层地址空值标志；NULL按相等参与同仓地址唯一键',
    column_text_is_null tinyint generated always as (column_text is null) stored not null comment '列地址空值标志；NULL按相等参与同仓地址唯一键',
    depth_text_is_null tinyint generated always as (depth_text is null) stored not null comment '深地址空值标志；NULL按相等参与同仓地址唯一键',
    aisle_text_cmp varchar(64) collate utf8mb4_0900_bin generated always as (ifnull(aisle_text,'')) stored not null comment '巷道地址精确比较值；NULL统一为空串且使用二进制排序规则',
    row_text_cmp varchar(64) collate utf8mb4_0900_bin generated always as (ifnull(row_text,'')) stored not null comment '排地址精确比较值；NULL统一为空串且使用二进制排序规则',
    level_text_cmp varchar(64) collate utf8mb4_0900_bin generated always as (ifnull(level_text,'')) stored not null comment '层地址精确比较值；NULL统一为空串且使用二进制排序规则',
    column_text_cmp varchar(64) collate utf8mb4_0900_bin generated always as (ifnull(column_text,'')) stored not null comment '列地址精确比较值；NULL统一为空串且使用二进制排序规则',
    depth_text_cmp varchar(64) collate utf8mb4_0900_bin generated always as (ifnull(depth_text,'')) stored not null comment '深地址精确比较值；NULL统一为空串且使用二进制排序规则',
    primary key (storage_id),
    unique key uk_wms_storage_code (storage_code),
    unique key uk_wms_storage_warehouse_address (warehouse_id, aisle_text_is_null, row_text_is_null, level_text_is_null, column_text_is_null, depth_text_is_null, aisle_text_cmp, row_text_cmp, level_text_cmp, column_text_cmp, depth_text_cmp),
    key idx_wms_storage_zone_status (zone_id, storage_type, status, lock_status, disable_status),
    key idx_wms_storage_matrix (zone_id, row_no, col_no, level_no),
    key idx_wms_storage_port (port_code, status),
    key idx_wms_storage_wcs_point (wcs_point_code),
    key idx_wms_storage_warehouse_status (warehouse_id, status),
    key idx_wms_storage_warehouse_lifecycle_zone (warehouse_id, lifecycle_status, zone_id),
    constraint ck_wms_storage_aisle_text_trim check (aisle_text is null or (aisle_text <> '' and cast(aisle_text as binary) = cast(trim(aisle_text) as binary))),
    constraint ck_wms_storage_row_text_trim check (row_text is null or (row_text <> '' and cast(row_text as binary) = cast(trim(row_text) as binary))),
    constraint ck_wms_storage_level_text_trim check (level_text is null or (level_text <> '' and cast(level_text as binary) = cast(trim(level_text) as binary))),
    constraint ck_wms_storage_column_text_trim check (column_text is null or (column_text <> '' and cast(column_text as binary) = cast(trim(column_text) as binary))),
    constraint ck_wms_storage_depth_text_trim check (depth_text is null or (depth_text <> '' and cast(depth_text as binary) = cast(trim(depth_text) as binary))),
    constraint ck_wms_storage_lifecycle_status check (lifecycle_status in ('ACTIVE','RETIRING','RETIRED'))
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

-- ----------------------------
-- 21、公司主档表
-- ----------------------------
-- 用途：
-- 1. 保存公司稳定身份、启停状态和生命周期；enabled 与 lifecycle_status 不互相推导。
-- 2. 公司编码创建后不可修改；默认不创建公司，由受控业务显式创建。
-- 3. 公司最小闭环只写身份、名称/备注和启停；仓库关系、可见范围和退役另行开放。
create table if not exists wms_company (
    company_id       bigint        not null                         comment '公司主键，系统生成',
    company_code     varchar(64)   collate utf8mb4_bin not null     comment '公司编码，业务唯一，创建后不可修改',
    company_name     varchar(100)  not null                         comment '公司名称',
    enabled          tinyint       not null default 0               comment '是否可被后续业务选择：0否，1是；与生命周期分离',
    lifecycle_status varchar(16)   collate utf8mb4_bin not null default 'ACTIVE' comment '生命周期：ACTIVE/RETIRING/RETIRED',
    revision         int           not null default 1               comment '公司乐观锁版本，创建为1',
    remark           varchar(500)  default null                     comment '备注',
    create_dept      bigint        default null                     comment '创建部门',
    create_by        bigint        default null                     comment '创建者',
    create_time      datetime(3)   not null default current_timestamp(3) comment '创建时间',
    update_by        bigint        default null                     comment '更新者',
    update_time      datetime(3)   default null on update current_timestamp(3) comment '更新时间',
    primary key (company_id),
    unique key uk_wms_company_code (company_code),
    key idx_wms_company_lifecycle (lifecycle_status, enabled)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS公司主档';

-- ----------------------------
-- 22、公司管理范围头表
-- ----------------------------
-- 用途：
-- 1. 每个公司恰一行，scope_revision 独立于公司 revision 做全量授权替换的 CAS。
-- 2. 不保存角色明细；明细见 wms_company_management_grant。
create table if not exists wms_company_management_scope (
    company_id     bigint      not null                             comment '公司主键，逻辑关联 wms_company.company_id；每公司恰一行',
    scope_revision int         not null default 0                   comment '管理范围独立乐观锁版本，创建为0',
    updated_by     bigint      default null                         comment '最后更新人',
    updated_at     datetime(3) default null                         comment '最后更新时间',
    primary key (company_id)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS公司管理范围头';

-- ----------------------------
-- 23、公司管理角色授权明细表
-- ----------------------------
-- 用途：
-- 1. 角色-公司管理范围；唯一 company_id/role_id，按完整目标集合替换。
-- 2. 只授权公司管理对象范围，不授予菜单功能权限，也不产生库存可见范围。
create table if not exists wms_company_management_grant (
    grant_id   bigint      not null                                 comment '授权明细主键，系统生成',
    company_id bigint      not null                                 comment '公司主键，逻辑关联 wms_company.company_id',
    role_id    bigint      not null                                 comment '角色主键，逻辑关联 sys_role.role_id',
    created_by bigint      default null                             comment '创建者',
    created_at datetime(3) not null default current_timestamp(3)    comment '创建时间',
    primary key (grant_id),
    unique key uk_wms_company_grant (company_id, role_id),
    key idx_wms_company_grant_role (role_id, company_id)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS公司管理角色授权明细';

-- ----------------------------
-- 24、对象变更审计表
-- ----------------------------
-- 用途：
-- 1. 成功对象变更审计，只追加；失败事实写 wms_command_attempt，不伪造成功历史。
-- 2. before_summary/after_summary 承载变更前后脱敏快照，超长由应用截断；用户原因并入 evidence_json。
create table if not exists wms_object_audit (
    audit_id          bigint        not null                         comment '审计主键，系统生成',
    object_type       varchar(64)   collate utf8mb4_bin not null     comment '对象类型，如 WMS_COMPANY',
    object_id         bigint        not null                         comment '对象内部ID',
    action            varchar(64)   collate utf8mb4_bin not null     comment '动作：CREATE/UPDATE/ENABLE/DISABLE/MANAGEMENT_SCOPE_REPLACE',
    command_key       varchar(64)   collate utf8mb4_bin not null     comment '命令键，用于幂等与追责',
    before_summary    varchar(2000) default null                     comment '变更前摘要（脱敏快照，超长截断）',
    after_summary     varchar(2000) default null                     comment '变更后摘要（脱敏快照，超长截断）',
    actor_user_id     bigint        default null                     comment '当前操作者',
    actor_name        varchar(64)   default null                     comment '操作者名称快照',
    initiator_type    varchar(32)   collate utf8mb4_bin default null comment '发起人类型：USER/PLAN/SYSTEM',
    initiator_id      varchar(128)  default null                     comment '发起人标识',
    initiator_name    varchar(64)   default null                     comment '发起人名称快照',
    dept_id           bigint        default null                     comment '操作者部门快照',
    dept_name         varchar(128)  default null                     comment '操作者部门名称快照',
    execution_subject varchar(64)   default null                     comment '执行主体名称快照',
    execution_subject_type varchar(32) collate utf8mb4_bin default null comment '执行主体类型：USER/PLAN/SYSTEM',
    execution_account varchar(64)   default null                     comment '执行账号快照',
    result            varchar(16)   collate utf8mb4_bin not null     comment '结果：SUCCESS/REJECTED/FAILED/UNKNOWN',
    evidence_json     varchar(2000) default null                     comment '证据引用或幂等摘要',
    occurred_at       datetime(3)   not null                         comment '业务发生时间',
    recorded_at       datetime(3)   not null default current_timestamp(3) comment '审计记录时间',
    primary key (audit_id),
    key idx_wms_object_audit_object (object_type, object_id, occurred_at),
    key idx_wms_object_audit_command_key (command_key)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS对象变更审计，只追加';

-- ----------------------------
-- 25、受控命令尝试表
-- ----------------------------
-- 用途：
-- 1. 非计划领域受控命令的幂等事实；同一 command_key 只允许一行，重试需使用新 command_key。
-- 2. 成功时 fact_type/fact_id 指向 wms_object_audit；失败独立留存错误码与脱敏消息。
create table if not exists wms_command_attempt (
    attempt_id        bigint        not null                         comment '尝试主键，系统生成',
    command_key       varchar(64)   collate utf8mb4_bin not null     comment '命令键',
    action            varchar(64)   collate utf8mb4_bin not null     comment '命令动作，如 COMPANY_CREATE/COMPANY_UPDATE/COMPANY_ENABLE/COMPANY_DISABLE/COMPANY_SCOPE_REPLACE',
    object_type       varchar(64)   collate utf8mb4_bin not null     comment '对象类型，如 WMS_COMPANY',
    object_id         bigint        default null                     comment '对象内部ID，创建前可空',
    input_digest      char(64)      not null                         comment '规范化请求摘要，SHA-256小写',
    input_summary     varchar(2000) default null                     comment '脱敏请求摘要',
    status            varchar(16)   collate utf8mb4_bin not null     comment '状态：ACCEPTED/PROCESSING/APPLIED/REJECTED/FAILED/UNKNOWN',
    error_code        varchar(64)   collate utf8mb4_bin default null comment '错误码，如 VERSION_CONFLICT/SCOPE_VERSION_CONFLICT/FEATURE_NOT_OPEN',
    error_message     varchar(500)  default null                     comment '脱敏错误消息',
    fact_type         varchar(64)   collate utf8mb4_bin default null comment '成功事实类型，如 WMS_OBJECT_AUDIT',
    fact_id           varchar(128)  default null                     comment '成功事实标识',
    actor_user_id     bigint        default null                     comment '操作者',
    actor_name        varchar(64)   default null                     comment '操作者名称快照',
    dept_id           bigint        default null                     comment '操作者部门快照',
    dept_name         varchar(128)  default null                     comment '操作者部门名称快照',
    initiator_name    varchar(64)   default null                     comment '发起人名称快照',
    execution_subject varchar(64)   default null                     comment '执行主体名称快照',
    execution_subject_type varchar(32) collate utf8mb4_bin default null comment '执行主体类型：USER/PLAN/SYSTEM',
    execution_account varchar(64)   default null                     comment '执行账号快照',
    occurred_at       datetime(3)   not null                         comment '命令发生时间',
    recorded_at       datetime(3)   not null default current_timestamp(3) comment '记录时间',
    primary key (attempt_id),
    unique key uk_wms_command_attempt_command (command_key),
    key idx_wms_command_attempt_object (object_type, object_id, occurred_at),
    key idx_wms_command_attempt_status (status, occurred_at)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS非计划受控命令尝试';

-- ----------------------------
-- 26、仓库主表（S1空间切片）
-- ----------------------------
-- 用途：
-- 1. 建立公司 -> 仓库 -> 库区 -> 储位的最小父链；S1 只建立可空引用，不回填旧行。
-- 2. warehouse_name 与 warehouse_code 分离；编码使用精确 binary 语义，同公司内唯一。
-- 3. 类型配置版本、当前位置版本指针暂不建表，后续阶段通过逻辑关联补齐。
create table if not exists wms_warehouse (
    warehouse_id                    bigint        not null                         comment '仓库主键，系统生成',
    company_id                      bigint        not null                         comment '所属公司主键，逻辑关联 wms_company.company_id',
    warehouse_code                  varchar(64)   collate utf8mb4_bin not null     comment '仓库编码，同公司内唯一且使用精确比较',
    warehouse_name                  varchar(100)  not null                         comment '仓库名称，创建后可按受控业务修改',
    warehouse_type_code             varchar(32)   collate utf8mb4_bin not null     comment '仓库类型编码，如 GENERAL/FLOOR；楼层只存于类型配置',
    type_config_version_id          bigint       default null                     comment '仓库类型配置版本主键；配置版本表后续建立',
    lifecycle_status                varchar(16)   collate utf8mb4_bin not null default 'ACTIVE' comment '生命周期：ACTIVE/RETIRING/RETIRED',
    enabled                         tinyint       not null default 0               comment '启用状态：0停用，1启用；与生命周期独立',
    revision                        int           not null default 1               comment '仓库乐观锁版本，创建为1',
    remark                          varchar(500)  default null                     comment '备注',
    create_dept                     bigint        default null                     comment '创建部门',
    create_by                       bigint        default null                     comment '创建者',
    create_time                     datetime(3)   not null default current_timestamp(3) comment '创建时间',
    update_by                       bigint        default null                     comment '更新者',
    update_time                     datetime(3)   default null on update current_timestamp(3) comment '更新时间',
    primary key (warehouse_id),
    unique key uk_wms_warehouse_company_code (company_id, warehouse_code),
    key idx_wms_warehouse_company_status (company_id, lifecycle_status, warehouse_type_code),
    constraint ck_wms_warehouse_enabled check (enabled in (0,1)),
    constraint ck_wms_warehouse_lifecycle_status check (lifecycle_status in ('ACTIVE','RETIRING','RETIRED'))
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS仓库主表';
-- ----------------------------
-- 27、库存业务类型目录表
-- ----------------------------
-- 用途：
-- 1. 登记库存业务类型编码、名称与合同版本，是计划挂载的父目录。
-- 2. 只建结构与主键，不预置 MOVE/FREEZE 等业务类型或样例行。
-- 3. 计划类型和调度编码由挂载表只读解析，不在本表复制调度主数据。
create table if not exists wms_business_type (
    business_type_code              varchar(64)   character set utf8mb4 collate utf8mb4_0900_bin not null comment '业务类型编码',
    business_type_name              varchar(128)  not null comment '业务类型名称',
    status                          varchar(16)   character set utf8mb4 collate utf8mb4_0900_bin not null default 'ACTIVE' comment '状态：ACTIVE/DISABLED',
    contract_version                varchar(32)   character set utf8mb4 collate utf8mb4_0900_bin not null comment '业务合同版本',
    object_policy_key               varchar(64)   character set utf8mb4 collate utf8mb4_0900_bin not null comment '对象策略键；本切面不注册策略',
    revision                        int           not null default 1 comment '修订号',
    create_dept                     bigint        default null comment '创建部门',
    create_by                       bigint        default null comment '创建者',
    create_time                     datetime(3)   not null default current_timestamp(3) comment '创建时间',
    update_by                       bigint        default null comment '更新者',
    update_time                     datetime(3)   default null on update current_timestamp(3) comment '更新时间',
    primary key (business_type_code),
    key idx_wms_business_type_status (status, business_type_code)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS业务类型目录';

-- ----------------------------
-- 28、库存业务类型计划挂载目录表
-- ----------------------------
-- 用途：
-- 1. 把库存业务类型绑定到计划事件调度业务编码和计划类型编码。
-- 2. plan_type_code / schedule_code 只读解析到 iws_business_schedule_plan_type 与 iws_business_schedule。
-- 3. 只建结构与索引，不创建 MOVE/FREEZE 挂载，也不调用 attach。
create table if not exists wms_business_plan_mount (
    mount_id                        bigint        not null comment '挂载主键，系统生成',
    business_type_code              varchar(64)   character set utf8mb4 collate utf8mb4_0900_bin not null comment '业务类型编码',
    plan_type_code                  varchar(64)   character set utf8mb4 collate utf8mb4_0900_bin not null comment '计划类型编码',
    schedule_code                   varchar(64)   character set utf8mb4 collate utf8mb4_0900_bin not null comment '调度业务编码，等于iws_business_schedule.business_code',
    contract_version                varchar(32)   character set utf8mb4 collate utf8mb4_0900_bin not null comment '挂载合同版本',
    enabled                         tinyint       not null default 1 comment '是否启用',
    revision                        int           not null default 1 comment '修订号',
    create_dept                     bigint        default null comment '创建部门',
    create_by                       bigint        default null comment '创建者',
    create_time                     datetime(3)   not null default current_timestamp(3) comment '创建时间',
    update_by                       bigint        default null comment '更新者',
    update_time                     datetime(3)   default null on update current_timestamp(3) comment '更新时间',
    primary key (mount_id),
    unique key uk_wms_business_plan_mount (business_type_code, plan_type_code, schedule_code),
    key idx_wms_business_plan_mount_resolve (schedule_code, plan_type_code, enabled)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS业务类型计划挂载目录';

-- ----------------------------
-- 29、组件结构与发布版本登记表
-- ----------------------------
-- 用途：
-- 1. 登记组件结构版本、发布标识和升级前后结构指纹。
-- 2. 同一组件的同一结构版本只能登记一次，状态限定为四种。
-- 3. 初始化和升级脚本只建表，不写发布实例行。
create table if not exists wms_schema_version (
    schema_version_id               bigint        not null auto_increment comment '主键，系统生成',
    component_code                  varchar(64)   character set ascii collate ascii_bin not null comment '组件编码，机器稳定标识',
    schema_version                  varchar(64)   character set ascii collate ascii_bin not null comment '组件结构版本',
    release_id                      varchar(64)   character set ascii collate ascii_bin not null comment '发布标识',
    source_fingerprint              char(64)      character set ascii collate ascii_bin not null comment '升级前源结构指纹，SHA-256 小写十六进制',
    target_fingerprint              char(64)      character set ascii collate ascii_bin not null comment '目标结构指纹，SHA-256 小写十六进制',
    status                          varchar(16)   character set ascii collate ascii_bin not null comment '状态：PENDING/APPLIED/VERIFIED/FAILED',
    applied_by                      varchar(64)   not null comment '应用人或执行器标识',
    applied_at                      datetime(3)   not null default current_timestamp(3) comment '应用时间',
    remark                          varchar(500)  default null comment '备注',
    primary key (schema_version_id),
    unique key uk_wms_schema_version_component_version (component_code, schema_version),
    key idx_wms_schema_version_component_status (component_code, status, applied_at),
    constraint ck_wms_schema_version_status check (status in ('PENDING','APPLIED','VERIFIED','FAILED'))
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS组件结构与发布版本登记';

-- ----------------------------
-- 30、升级运行表
-- ----------------------------
-- 用途：
-- 1. 记录一次在库空间升级的发布标识、结构指纹、阶段和写冻结令牌。
-- 2. 阶段和状态使用稳定机器码，便于执行器断点判断。
-- 3. 不在初始化阶段写入任何运行行。
create table if not exists wms_upgrade_run (
    run_id                          bigint        not null auto_increment comment '升级运行主键，系统生成',
    release_id                      varchar(64)   character set ascii collate ascii_bin not null comment '发布标识',
    source_fingerprint              char(64)      character set ascii collate ascii_bin not null comment '源结构指纹，SHA-256 小写十六进制',
    target_fingerprint              char(64)      character set ascii collate ascii_bin not null comment '目标结构指纹，SHA-256 小写十六进制',
    phase                           varchar(32)   character set ascii collate ascii_bin not null default 'SOURCE_VERIFIED' comment '阶段：SOURCE_VERIFIED/CONTROL_READY/DDL_PARTIAL/DDL_TARGET/BACKFILL_PARTIAL/DATA_TARGET/CUTOVER_READY/COMPLETE/BLOCKED',
    write_fence_token               varchar(128)  character set ascii collate ascii_bin not null comment '写冻结令牌，用于关联停止旧写入的证据',
    status                          varchar(16)   character set ascii collate ascii_bin not null default 'RUNNING' comment '状态：RUNNING/BLOCKED/FAILED/COMPLETED',
    attempt_count                   int           not null default 0 comment '已尝试次数',
    error_code                      varchar(64)   character set ascii collate ascii_bin default null comment '失败或阻断机器码',
    error_message                   varchar(1000) default null comment '中文失败或阻断说明，禁止写入敏感原文',
    started_by                      varchar(64)   not null comment '启动人或执行器标识',
    started_at                      datetime(3)   not null default current_timestamp(3) comment '开始时间',
    updated_at                      datetime(3)   not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    completed_at                    datetime(3)   default null comment '完成时间',
    primary key (run_id),
    key idx_wms_upgrade_run_release_started (release_id, started_at),
    key idx_wms_upgrade_run_status_phase (status, phase, updated_at),
    constraint ck_wms_upgrade_run_attempt check (attempt_count >= 0),
    constraint ck_wms_upgrade_run_phase check (phase in ('SOURCE_VERIFIED','CONTROL_READY','DDL_PARTIAL','DDL_TARGET','BACKFILL_PARTIAL','DATA_TARGET','CUTOVER_READY','COMPLETE','BLOCKED')),
    constraint ck_wms_upgrade_run_status check (status in ('RUNNING','BLOCKED','FAILED','COMPLETED'))
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS库存升级运行';

-- ----------------------------
-- 31、升级检查点表
-- ----------------------------
-- 用途：
-- 1. 记录升级运行内每个步骤和对象批次的检查点、摘要与尝试次数。
-- 2. run_id 逻辑关联 wms_upgrade_run.run_id，不建物理外键。
-- 3. 状态限定为六种，attempt_count 不允许为负。
create table if not exists wms_upgrade_checkpoint (
    checkpoint_id                   bigint        not null auto_increment comment '升级检查点主键，系统生成',
    run_id                          bigint        not null comment '所属升级运行，逻辑关联 wms_upgrade_run.run_id',
    step_code                       varchar(64)   character set ascii collate ascii_bin not null comment '步骤编码，机器稳定标识',
    object_key                      varchar(255)  character set utf8mb4 collate utf8mb4_0900_bin not null comment '对象或批次稳定键，精确比较',
    status                          varchar(16)   character set ascii collate ascii_bin not null default 'PENDING' comment '状态：PENDING/RUNNING/APPLIED/VERIFIED/FAILED/SKIPPED',
    before_digest                   char(64)      character set ascii collate ascii_bin default null comment '执行前摘要，SHA-256 小写十六进制',
    after_digest                    char(64)      character set ascii collate ascii_bin default null comment '执行后摘要，SHA-256 小写十六进制',
    attempt_count                   int           not null default 0 comment '该步骤已尝试次数',
    error_code                      varchar(64)   character set ascii collate ascii_bin default null comment '失败或阻断机器码',
    error_message                   varchar(1000) default null comment '中文失败或阻断说明，禁止写入敏感原文',
    started_at                      datetime(3)   not null default current_timestamp(3) comment '开始时间',
    updated_at                      datetime(3)   not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    completed_at                    datetime(3)   default null comment '完成时间',
    primary key (checkpoint_id),
    unique key uk_wms_upgrade_checkpoint_run_step_object (run_id, step_code, object_key),
    key idx_wms_upgrade_checkpoint_run_status (run_id, status, updated_at),
    constraint ck_wms_upgrade_checkpoint_attempt check (attempt_count >= 0),
    constraint ck_wms_upgrade_checkpoint_status check (status in ('PENDING','RUNNING','APPLIED','VERIFIED','FAILED','SKIPPED'))
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS库存升级步骤检查点';

-- ----------------------------
-- 32、旧业务标识映射表
-- ----------------------------
-- 用途：
-- 1. 记录旧业务标识到目标内部标识的映射及源业务摘要。
-- 2. 源键与目标键各自唯一，同一目标内部标识只允许映射一次。
-- 3. 初始化和升级脚本只建表，不写映射行。
create table if not exists wms_legacy_id_map (
    map_id                          bigint        not null auto_increment comment '旧标识映射主键，系统生成',
    domain                          varchar(64)   character set ascii collate ascii_bin not null comment '映射域，如 COMPANY/WAREHOUSE/STORAGE',
    source_table                    varchar(64)   character set ascii collate ascii_bin not null comment '源表名',
    source_key                      varchar(512)  character set utf8mb4 collate utf8mb4_0900_bin not null comment '源业务键，精确比较',
    source_digest                   char(64)      character set ascii collate ascii_bin not null comment '源业务摘要，SHA-256 小写十六进制',
    target_type                     varchar(64)   character set ascii collate ascii_bin not null comment '目标对象类型',
    target_id                       bigint        not null comment '目标内部主键',
    mapping_status                  varchar(16)   character set ascii collate ascii_bin not null default 'MAPPED' comment '状态：MAPPED/VERIFIED',
    created_at                      datetime(3)   not null default current_timestamp(3) comment '创建时间',
    updated_at                      datetime(3)   not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (map_id),
    unique key uk_wms_legacy_id_map_source (domain, source_table, source_key),
    unique key uk_wms_legacy_id_map_target (target_type, target_id),
    constraint ck_wms_legacy_id_map_status check (mapping_status in ('MAPPED','VERIFIED'))
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS旧业务标识到目标内部标识映射';

-- ----------------------------
-- 33、升级阻碍清单表
-- ----------------------------
-- 用途：
-- 1. 记录阻断升级阶段的问题类别、脱敏源键和中文处置建议。
-- 2. 同一运行的问题码与脱敏源键唯一，is_blocking 只允许 0/1。
-- 3. 初始化和升级脚本只建表，不写问题行。
create table if not exists wms_upgrade_issue (
    issue_id                        bigint        not null auto_increment comment '升级问题主键，系统生成',
    run_id                          bigint        not null comment '所属升级运行，逻辑关联 wms_upgrade_run.run_id',
    issue_code                      varchar(64)   character set ascii collate ascii_bin not null comment '问题机器码，稳定标识',
    issue_category                  varchar(255)  not null comment '中文问题类别',
    redacted_source_key             varchar(512)  character set utf8mb4 collate utf8mb4_0900_bin not null comment '脱敏源业务键，禁止写入凭据或完整敏感报文',
    detail_summary                  varchar(1000) not null comment '中文脱敏问题摘要',
    blocking_phase                  varchar(32)   character set ascii collate ascii_bin not null comment '被阻止的阶段',
    suggested_action                varchar(1000) not null comment '建议人工动作',
    is_blocking                     tinyint(1)    not null default 1 comment '是否阻断后续阶段：1 阻断，0 仅提示',
    detected_at                     datetime(3)   not null default current_timestamp(3) comment '检测时间',
    primary key (issue_id),
    unique key uk_wms_upgrade_issue_run_code_key (run_id, issue_code, redacted_source_key),
    key idx_wms_upgrade_issue_run_blocking (run_id, blocking_phase, is_blocking),
    constraint ck_wms_upgrade_issue_blocking check (is_blocking in (0,1)),
    constraint ck_wms_upgrade_issue_phase check (blocking_phase in ('SOURCE_VERIFIED','CONTROL_READY','DDL_PARTIAL','DDL_TARGET','BACKFILL_PARTIAL','DATA_TARGET','CUTOVER_READY','COMPLETE','BLOCKED'))
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='WMS库存升级中文阻碍清单';

-- ----------------------------
-- 34-45、库存 P3 规则与逻辑归属目标结构
-- ----------------------------
-- 本组只内联 12 张目标表结构；不写规则、种子台账、生效指针或角色授权。
-- 定义取自独立规则 SQL 在 MySQL 8.0.45 隔离库的 SHOW CREATE TABLE 目标态。
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_attribute_catalog` (
  `attribute_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '属性键',
  `source_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '来源类型',
  `value_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '值类型',
  `cardinality` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '基数',
  `operators_json` json NOT NULL COMMENT '允许操作符',
  `unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '单位',
  `scale` int DEFAULT NULL COMMENT '小数位',
  `groupable` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否可分组',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
  `contract_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '属性合同版本',
  `revision` int NOT NULL DEFAULT '1' COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`attribute_key`),
  KEY `idx_wms_attribute_catalog_status` (`status`,`source_type`),
  CONSTRAINT `ck_wms_attribute_catalog_groupable` CHECK ((`groupable` in (0,1))),
  CONSTRAINT `ck_wms_attribute_catalog_revision` CHECK ((`revision` >= 1)),
  CONSTRAINT `ck_wms_attribute_catalog_scale` CHECK (((`scale` is null) or (`scale` >= 0))),
  CONSTRAINT `ck_wms_attribute_catalog_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'DISABLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS属性目录';
INSERT INTO `wms_attribute_catalog`
    (`attribute_key`, `source_type`, `value_type`, `cardinality`,
     `operators_json`, `unit`, `scale`, `groupable`, `status`,
     `contract_version`, `revision`)
VALUES
    ('storageCode', 'STORAGE', 'STRING', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('warehouseCode', 'STORAGE', 'STRING', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('zoneCode', 'STORAGE', 'STRING', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('storageType', 'STORAGE', 'INTEGER', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'LT', 'LE', 'GT', 'GE', 'IS_NULL'),
     NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('allowInbound', 'STORAGE', 'BOOLEAN', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1),
    ('allowOutbound', 'STORAGE', 'BOOLEAN', 'SCALAR',
     JSON_ARRAY('EQ', 'NE', 'IN', 'IS_NULL'), NULL, NULL, 1, 'ACTIVE', 'STORAGE-1', 1)
ON DUPLICATE KEY UPDATE
    `source_type` = VALUES(`source_type`),
    `value_type` = VALUES(`value_type`),
    `cardinality` = VALUES(`cardinality`),
    `operators_json` = VALUES(`operators_json`),
    `unit` = VALUES(`unit`),
    `scale` = VALUES(`scale`),
    `groupable` = VALUES(`groupable`),
    `status` = VALUES(`status`),
    `contract_version` = VALUES(`contract_version`),
    `revision` = VALUES(`revision`);
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_rule_definition` (
  `rule_definition_id` bigint NOT NULL AUTO_INCREMENT COMMENT '规则定义主键',
  `rule_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规则键',
  `purpose` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '用途',
  `rule_name` varchar(128) NOT NULL COMMENT '规则名称',
  `scope_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '作用域类型',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
  `current_version_id` bigint DEFAULT NULL COMMENT '当前版本ID',
  `revision` int NOT NULL DEFAULT '1' COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`rule_definition_id`),
  UNIQUE KEY `uk_wms_rule_definition_key` (`rule_key`),
  KEY `idx_wms_rule_definition_resolve` (`purpose`,`status`),
  KEY `idx_wms_rule_definition_current_version` (`current_version_id`),
  CONSTRAINT `ck_wms_rule_definition_revision` CHECK ((`revision` >= 1)),
  CONSTRAINT `ck_wms_rule_definition_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'DISABLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS规则定义';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_rule_version` (
  `rule_version_id` bigint NOT NULL AUTO_INCREMENT COMMENT '规则版本主键',
  `rule_definition_id` bigint NOT NULL COMMENT '规则定义ID',
  `version_no` int NOT NULL COMMENT '版本号',
  `condition_json` json NOT NULL COMMENT '条件表达式',
  `result_json` json NOT NULL COMMENT '结果表达式',
  `attribute_bindings_json` json DEFAULT NULL COMMENT '服务端属性绑定、用途和限额快照',
  `canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规范化内容SHA-256小写十六进制',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/ACTIVE/RETIRED',
  `published_at` datetime(3) DEFAULT NULL COMMENT '发布时间',
  `published_by` bigint DEFAULT NULL COMMENT '发布人',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`rule_version_id`),
  UNIQUE KEY `uk_wms_rule_version_owner_version` (`rule_definition_id`,`version_no`),
  KEY `idx_wms_rule_version_status` (`status`,`rule_definition_id`),
  KEY `idx_wms_rule_version_digest` (`canonical_digest`),
  CONSTRAINT `ck_wms_rule_version_digest` CHECK (regexp_like(`canonical_digest`,_utf8mb4'^[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_rule_version_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'ACTIVE',_utf8mb4'RETIRED'))),
  CONSTRAINT `ck_wms_rule_version_version_no` CHECK ((`version_no` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS规则内容版本';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_rule_package_version` (
  `package_version_id` bigint NOT NULL AUTO_INCREMENT COMMENT '规则包版本主键',
  `package_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规则包编码',
  `version_no` int NOT NULL COMMENT '版本号',
  `canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规范化内容SHA-256小写十六进制',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/ACTIVE/RETIRED',
  `published_at` datetime(3) DEFAULT NULL COMMENT '发布时间',
  `published_by` bigint DEFAULT NULL COMMENT '发布人',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`package_version_id`),
  UNIQUE KEY `uk_wms_rule_package_version_code_version` (`package_code`,`version_no`),
  KEY `idx_wms_rule_package_version_status` (`status`,`package_code`),
  KEY `idx_wms_rule_package_version_digest` (`canonical_digest`),
  CONSTRAINT `ck_wms_rule_package_version_digest` CHECK (regexp_like(`canonical_digest`,_utf8mb4'^[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_rule_package_version_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'ACTIVE',_utf8mb4'RETIRED'))),
  CONSTRAINT `ck_wms_rule_package_version_version_no` CHECK ((`version_no` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS规则包版本';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_rule_package_member` (
  `package_member_id` bigint NOT NULL AUTO_INCREMENT COMMENT '规则包成员主键',
  `package_version_id` bigint NOT NULL COMMENT '规则包版本ID',
  `purpose` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '成员用途',
  `rule_version_id` bigint NOT NULL COMMENT '规则版本ID',
  `revision` int NOT NULL DEFAULT '1' COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`package_member_id`),
  UNIQUE KEY `uk_wms_rule_package_member_purpose` (`package_version_id`,`purpose`),
  KEY `idx_wms_rule_package_member_rule` (`rule_version_id`),
  CONSTRAINT `ck_wms_rule_package_member_revision` CHECK ((`revision` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS规则包成员';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_rule_effective_pointer` (
  `rule_effective_pointer_id` bigint NOT NULL AUTO_INCREMENT COMMENT '生效指针主键',
  `scope_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '作用域类型',
  `scope_id` bigint NOT NULL COMMENT '作用域内部ID',
  `package_version_id` bigint NOT NULL COMMENT '规则包版本ID',
  `revision` int NOT NULL DEFAULT '1' COMMENT 'CAS修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`rule_effective_pointer_id`),
  UNIQUE KEY `uk_wms_rule_effective_pointer_scope` (`scope_type`,`scope_id`),
  KEY `idx_wms_rule_effective_pointer_package` (`package_version_id`),
  CONSTRAINT `ck_wms_rule_effective_pointer_revision` CHECK ((`revision` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS规则包生效指针';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_rule_seed_ledger` (
  `rule_seed_ledger_id` bigint NOT NULL AUTO_INCREMENT COMMENT '种子台账主键',
  `seed_code` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '种子编码',
  `seed_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '种子版本',
  `canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规范化内容SHA-256小写十六进制',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '状态：APPLIED',
  `applied_at` datetime(3) NOT NULL COMMENT '应用时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`rule_seed_ledger_id`),
  UNIQUE KEY `uk_wms_rule_seed_ledger_code_version` (`seed_code`,`seed_version`),
  KEY `idx_wms_rule_seed_ledger_status` (`status`,`applied_at`),
  CONSTRAINT `ck_wms_rule_seed_ledger_digest` CHECK (regexp_like(`canonical_digest`,_utf8mb4'^[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_rule_seed_ledger_status` CHECK ((`status` = _utf8mb4'APPLIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS规则种子台账';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_logical_scope` (
  `logical_scope_id` bigint NOT NULL AUTO_INCREMENT COMMENT '逻辑归属主键',
  `scope_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '逻辑归属编码',
  `scope_name` varchar(128) NOT NULL COMMENT '逻辑归属名称',
  `lifecycle_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '生命周期：ACTIVE/RETIRING/RETIRED',
  `current_version_id` bigint DEFAULT NULL COMMENT '当前版本ID',
  `revision` int NOT NULL DEFAULT '1' COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`logical_scope_id`),
  UNIQUE KEY `uk_wms_logical_scope_code` (`scope_code`),
  KEY `idx_wms_logical_scope_status` (`lifecycle_status`,`scope_code`),
  KEY `idx_wms_logical_scope_current_version` (`current_version_id`),
  CONSTRAINT `ck_wms_logical_scope_lifecycle_status` CHECK ((`lifecycle_status` in (_utf8mb4'ACTIVE',_utf8mb4'RETIRING',_utf8mb4'RETIRED'))),
  CONSTRAINT `ck_wms_logical_scope_revision` CHECK ((`revision` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS逻辑归属';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_logical_scope_version` (
  `scope_version_id` bigint NOT NULL AUTO_INCREMENT COMMENT '逻辑归属版本主键',
  `logical_scope_id` bigint NOT NULL COMMENT '逻辑归属ID',
  `version_no` int NOT NULL COMMENT '版本号',
  `rule_package_version_id` bigint NOT NULL COMMENT '规则包版本ID',
  `space_snapshot_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '空间快照SHA-256小写十六进制',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/ACTIVE/RETIRED',
  `published_at` datetime(3) DEFAULT NULL COMMENT '发布时间',
  `published_by` bigint DEFAULT NULL COMMENT '发布人',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`scope_version_id`),
  UNIQUE KEY `uk_wms_logical_scope_version_owner_version` (`logical_scope_id`,`version_no`),
  KEY `idx_wms_logical_scope_version_package` (`rule_package_version_id`,`status`),
  CONSTRAINT `ck_wms_logical_scope_version_digest` CHECK (regexp_like(`space_snapshot_digest`,_utf8mb4'^[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_logical_scope_version_status` CHECK ((`status` in (_utf8mb4'DRAFT',_utf8mb4'ACTIVE',_utf8mb4'RETIRED'))),
  CONSTRAINT `ck_wms_logical_scope_version_version_no` CHECK ((`version_no` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS逻辑归属版本';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_logical_scope_storage` (
  `logical_scope_storage_id` bigint NOT NULL AUTO_INCREMENT COMMENT '逻辑归属储位关系主键',
  `scope_version_id` bigint NOT NULL COMMENT '逻辑归属版本ID',
  `storage_id` bigint NOT NULL COMMENT '稳定储位ID',
  `revision` int NOT NULL DEFAULT '1' COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`logical_scope_storage_id`),
  UNIQUE KEY `uk_wms_logical_scope_storage` (`scope_version_id`,`storage_id`),
  KEY `idx_wms_logical_scope_storage_storage` (`storage_id`,`scope_version_id`),
  CONSTRAINT `ck_wms_logical_scope_storage_revision` CHECK ((`revision` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS逻辑归属储位集合';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_material_logical_assignment` (
  `logical_assignment_id` bigint NOT NULL AUTO_INCREMENT COMMENT '物料逻辑归属主键',
  `inventory_id` bigint NOT NULL COMMENT '稳定库存ID',
  `scope_version_id` bigint NOT NULL COMMENT '逻辑归属版本ID',
  `assignment_revision` int NOT NULL DEFAULT '1' COMMENT '归属修订号',
  `source_business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '来源业务类型',
  `source_business_id` bigint NOT NULL COMMENT '来源业务内部ID',
  `revision` int NOT NULL DEFAULT '1' COMMENT '对象修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`logical_assignment_id`),
  UNIQUE KEY `uk_wms_material_logical_assignment_inventory` (`inventory_id`),
  KEY `idx_wms_material_logical_assignment_scope` (`scope_version_id`),
  KEY `idx_wms_material_logical_assignment_source` (`source_business_type`,`source_business_id`),
  CONSTRAINT `ck_wms_material_logical_assignment_assignment_revision` CHECK ((`assignment_revision` >= 1)),
  CONSTRAINT `ck_wms_material_logical_assignment_revision` CHECK ((`revision` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS物料当前逻辑归属';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_logical_assignment_history` (
  `logical_assignment_history_id` bigint NOT NULL AUTO_INCREMENT COMMENT '逻辑归属历史主键',
  `inventory_id` bigint NOT NULL COMMENT '稳定库存ID',
  `from_scope_version_id` bigint DEFAULT NULL COMMENT '变更前逻辑归属版本ID',
  `to_scope_version_id` bigint NOT NULL COMMENT '变更后逻辑归属版本ID',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'WMS任务键',
  `plan_instance_id` bigint DEFAULT NULL COMMENT '计划实例ID',
  `effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '领域效果键WMS:SHA-256',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者ID快照',
  `operator_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '操作者名称快照',
  `operator_department_id` bigint DEFAULT NULL COMMENT '操作者部门ID快照',
  `operator_department_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '操作者部门名称快照',
  `occurred_at` datetime(3) NOT NULL COMMENT '业务发生时间',
  `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事实记录时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`logical_assignment_history_id`),
  UNIQUE KEY `uk_wms_logical_assignment_history_effect` (`effect_key`),
  KEY `idx_wms_logical_assignment_history_inventory_time` (`inventory_id`,`occurred_at`),
  KEY `idx_wms_logical_assignment_history_instance` (`plan_instance_id`),
  KEY `idx_wms_logical_assignment_history_to_scope` (`to_scope_version_id`),
  CONSTRAINT `ck_wms_logical_assignment_history_effect_format` CHECK (regexp_like(`effect_key`,_utf8mb4'^WMS:[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_logical_assignment_history_effect_length` CHECK ((char_length(`effect_key`) <= 128))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS逻辑归属变更历史';
/*!40101 SET character_set_client = @saved_cs_client */;

-- ============================================================
-- 空间、访问通道、位置版本、可见范围与容量十表目标结构
-- 定义取自已接受独立 DDL；仅建结构，不写业务、容量或权限种子。
-- ============================================================
CREATE TABLE `wms_warehouse_type` (
    `warehouse_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '仓库类型稳定编码',
    `warehouse_type_name` varchar(128) NOT NULL COMMENT '仓库类型名称',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    `contract_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '类型合同版本',
    `config_schema_json` json NOT NULL COMMENT '类型配置JSON Schema',
    `revision` int NOT NULL DEFAULT 1 COMMENT '仓库类型修订号',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`warehouse_type_code`),
    KEY `idx_wms_warehouse_type_status` (`status`),
    CONSTRAINT `ck_wms_warehouse_type_revision` CHECK (`revision` >= 1),
    CONSTRAINT `ck_wms_warehouse_type_status` CHECK (`status` IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS仓库类型';

CREATE TABLE `wms_warehouse_type_config` (
    `config_version_id` bigint NOT NULL COMMENT '类型配置版本主键',
    `warehouse_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '仓库类型稳定编码',
    `version_no` int NOT NULL COMMENT '类型配置版本号',
    `config_json` json NOT NULL COMMENT '类型配置内容',
    `canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规范化配置SHA-256小写十六进制',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/ACTIVE/RETIRED',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`config_version_id`),
    UNIQUE KEY `uk_wms_warehouse_type_config_type_version` (`warehouse_type_code`, `version_no`),
    KEY `idx_wms_warehouse_type_config_status` (`warehouse_type_code`, `status`),
    CONSTRAINT `ck_wms_warehouse_type_config_digest` CHECK (REGEXP_LIKE(`canonical_digest`, _utf8mb4'^[0-9a-f]{64}$', 'c')),
    CONSTRAINT `ck_wms_warehouse_type_config_status` CHECK (`status` IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT `ck_wms_warehouse_type_config_version_no` CHECK (`version_no` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS仓库类型配置版本';

CREATE TABLE `wms_access_channel` (
    `channel_id` bigint NOT NULL COMMENT '访问通道主键',
    `device_zone_id` bigint NOT NULL COMMENT '设备库区ID，逻辑关联 wms_warehouse_zone.zone_id',
    `channel_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '通道稳定编码',
    `access_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '访问模式，未闭合稳定代码由目录约束',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/RETIRING/RETIRED',
    `revision` int NOT NULL DEFAULT 1 COMMENT '访问通道修订号',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`channel_id`),
    UNIQUE KEY `uk_wms_access_channel_zone_code` (`device_zone_id`, `channel_code`),
    KEY `idx_wms_access_channel_status` (`device_zone_id`, `status`),
    CONSTRAINT `ck_wms_access_channel_revision` CHECK (`revision` >= 1),
    CONSTRAINT `ck_wms_access_channel_status` CHECK (`status` IN ('ACTIVE','RETIRING','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS设备访问通道';

CREATE TABLE `wms_storage_access_member` (
    `member_id` bigint NOT NULL COMMENT '通道成员主键',
    `channel_id` bigint NOT NULL COMMENT '访问通道ID',
    `storage_id` bigint NOT NULL COMMENT '稳定储位ID',
    `access_order` int NOT NULL COMMENT '显式访问顺序',
    `revision` int NOT NULL DEFAULT 1 COMMENT '通道成员修订号',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`member_id`),
    UNIQUE KEY `uk_wms_storage_access_member_storage` (`storage_id`),
    UNIQUE KEY `uk_wms_storage_access_member_channel_storage` (`channel_id`, `storage_id`),
    UNIQUE KEY `uk_wms_storage_access_member_order` (`channel_id`, `access_order`),
    CONSTRAINT `ck_wms_storage_access_member_order` CHECK (`access_order` >= 0),
    CONSTRAINT `ck_wms_storage_access_member_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS通道储位成员';

CREATE TABLE `wms_storage_access_edge` (
    `access_edge_id` bigint NOT NULL COMMENT '阻挡边主键',
    `blocker_storage_id` bigint NOT NULL COMMENT '阻挡储位ID',
    `blocked_storage_id` bigint NOT NULL COMMENT '被阻挡储位ID',
    `revision` int NOT NULL DEFAULT 1 COMMENT '阻挡边修订号',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`access_edge_id`),
    UNIQUE KEY `uk_wms_storage_access_edge_pair` (`blocker_storage_id`, `blocked_storage_id`),
    KEY `idx_wms_storage_access_edge_blocked` (`blocked_storage_id`),
    CONSTRAINT `ck_wms_storage_access_edge_no_self` CHECK (`blocker_storage_id` <> `blocked_storage_id`),
    CONSTRAINT `ck_wms_storage_access_edge_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS通道储位阻挡边';

CREATE TABLE `wms_location_version` (
    `location_version_id` bigint NOT NULL COMMENT '位置版本主键',
    `object_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '空间对象类型',
    `object_id` bigint NOT NULL COMMENT '空间对象内部ID',
    `version_no` int NOT NULL COMMENT '对象位置版本号',
    `parent_location_version_id` bigint NULL COMMENT '父对象位置版本ID',
    `config_json` json NOT NULL COMMENT '位置不可变配置',
    `canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规范化配置SHA-256小写十六进制',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/ACTIVE/RETIRED',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`location_version_id`),
    UNIQUE KEY `uk_wms_location_version_object_version` (`object_type`, `object_id`, `version_no`),
    KEY `idx_wms_location_version_parent` (`parent_location_version_id`),
    KEY `idx_wms_location_version_status` (`status`, `object_type`),
    CONSTRAINT `ck_wms_location_version_digest` CHECK (REGEXP_LIKE(`canonical_digest`, _utf8mb4'^[0-9a-f]{64}$', 'c')),
    CONSTRAINT `ck_wms_location_version_status` CHECK (`status` IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT `ck_wms_location_version_version_no` CHECK (`version_no` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS空间位置配置版本';

CREATE TABLE `wms_location_effective` (
    `location_effective_id` bigint NOT NULL COMMENT '位置生效指针主键',
    `object_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '空间对象类型',
    `object_id` bigint NOT NULL COMMENT '空间对象内部ID',
    `location_version_id` bigint NOT NULL COMMENT '当前位置版本ID',
    `revision` int NOT NULL DEFAULT 1 COMMENT '生效指针CAS修订号',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`location_effective_id`),
    UNIQUE KEY `uk_wms_location_effective_object` (`object_type`, `object_id`),
    KEY `idx_wms_location_effective_version` (`location_version_id`),
    CONSTRAINT `ck_wms_location_effective_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS空间位置当前生效版本';

CREATE TABLE `wms_object_visibility` (
    `visibility_key` bigint NOT NULL COMMENT '对象可见范围主键',
    `parent_visibility_key` bigint NULL COMMENT '当前物理父对象可见范围主键',
    `object_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '空间对象类型',
    `object_id` bigint NOT NULL COMMENT '空间对象内部ID',
    `mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '可见范围模式：INHERIT/VALUE/CLEAR',
    `revision` int NOT NULL DEFAULT 1 COMMENT '可见范围修订号',
    `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '可见范围更新时间',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`visibility_key`),
    UNIQUE KEY `uk_wms_object_visibility_object` (`object_type`, `object_id`),
    KEY `idx_wms_object_visibility_parent` (`parent_visibility_key`),
    CONSTRAINT `ck_wms_object_visibility_company_mode` CHECK (`object_type` <> 'COMPANY' OR `mode` = 'VALUE'),
    CONSTRAINT `ck_wms_object_visibility_company_parent` CHECK (`object_type` <> 'COMPANY' OR `parent_visibility_key` IS NULL),
    CONSTRAINT `ck_wms_object_visibility_mode` CHECK (`mode` IN ('INHERIT','VALUE','CLEAR')),
    CONSTRAINT `ck_wms_object_visibility_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS对象可见范围三态头';

CREATE TABLE `wms_object_visibility_department` (
    `visibility_department_id` bigint NOT NULL COMMENT '可见部门关系主键',
    `visibility_key` bigint NOT NULL COMMENT '对象可见范围主键',
    `dept_id` bigint NOT NULL COMMENT '部门ID，逻辑关联 sys_dept.dept_id',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`visibility_department_id`),
    UNIQUE KEY `uk_wms_object_visibility_department` (`visibility_key`, `dept_id`),
    KEY `idx_wms_object_visibility_department_dept` (`dept_id`, `visibility_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS对象可见部门集合';

CREATE TABLE `wms_storage_capacity` (
    `capacity_id` bigint NOT NULL COMMENT '储位容量主键，由服务分配',
    `storage_id` bigint NOT NULL COMMENT '稳定储位ID，逻辑关联 wms_storage.storage_id',
    `dimension_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '容量维度键',
    `capacity_value` decimal(38,12) NOT NULL COMMENT '容量值',
    `unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '容量单位',
    `scale` int NOT NULL COMMENT '容量小数位',
    `capacity_version` int NOT NULL COMMENT '容量版本号',
    `active_flag` tinyint(1) NOT NULL DEFAULT 1 COMMENT '活动版本标志：1活动，0历史',
    `active_capacity_key` varchar(531) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (
      IF(`active_flag` = 1,
         CONCAT('A:', LPAD(HEX(`storage_id`), 16, '0'), ':', HEX(CAST(`dimension_key` AS BINARY))),
         CONCAT('H:', LPAD(HEX(`capacity_id`), 16, '0')))
    ) STORED NOT NULL COMMENT '活动或历史容量无碰撞唯一键',
    `create_dept` bigint NULL COMMENT '创建部门',
    `create_by` bigint NULL COMMENT '创建者',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_by` bigint NULL COMMENT '更新者',
    `update_time` datetime(3) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`capacity_id`),
    UNIQUE KEY `uk_wms_storage_capacity_active` (`active_capacity_key`),
    UNIQUE KEY `uk_wms_storage_capacity_version` (`storage_id`, `dimension_key`, `capacity_version`),
    KEY `idx_wms_storage_capacity_dimension` (`storage_id`, `dimension_key`, `active_flag`, `capacity_version`),
    CONSTRAINT `ck_wms_storage_capacity_active_flag` CHECK (`active_flag` IN (0,1)),
    CONSTRAINT `ck_wms_storage_capacity_scale_consistent` CHECK (`capacity_value` = TRUNCATE(`capacity_value`, `scale`)),
    CONSTRAINT `ck_wms_storage_capacity_scale` CHECK (`scale` BETWEEN 0 AND 12),
    CONSTRAINT `ck_wms_storage_capacity_value` CHECK (`capacity_value` >= 0),
    CONSTRAINT `ck_wms_storage_capacity_version` CHECK (`capacity_version` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS储位容量';

-- ============================================================
-- P4 生产信息、装载、占用与物料追踪十一表目标结构
-- 仅内联已接受 DDL，不写业务、权限种子或结构版本注册。
-- ============================================================
CREATE TABLE `wms_production_info` (
  `production_info_id` bigint NOT NULL COMMENT '生产信息主键，系统生成',
  `inventory_id` bigint NOT NULL COMMENT '稳定库存单元ID，逻辑关联 wms_inventory.inventory_id',
  `material_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '料号，逻辑关联 wms_material.material_code',
  `material_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '物料类别快照',
  `production_date` date DEFAULT NULL COMMENT '生产日期',
  `material_batch` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '生产批次',
  `supplier_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '供应商编码快照',
  `supplier_name` varchar(100) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '供应商名称快照',
  `customer_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '客户编码快照',
  `customer_name` varchar(100) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '客户名称快照',
  `length_value` decimal(38,12) DEFAULT NULL COMMENT '长度值',
  `width_value` decimal(38,12) DEFAULT NULL COMMENT '宽度值',
  `height_value` decimal(38,12) DEFAULT NULL COMMENT '高度值',
  `dimension_unit` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '尺寸单位',
  `bom_level` int DEFAULT NULL COMMENT 'BOM层级',
  `bom_qty` decimal(20,6) DEFAULT NULL COMMENT 'BOM用量，与库存数量分开',
  `bom_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'BOM版本',
  `finished_material_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '最终成品料号',
  `finished_material_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '最终成品类别',
  `parse_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '标签生产信息解析版本',
  `revision` int NOT NULL DEFAULT 1 COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`production_info_id`),
  UNIQUE KEY `uk_wms_production_info_inventory` (`inventory_id`),
  KEY `idx_wms_production_info_material_batch` (`material_code`,`material_batch`,`production_date`),
  CONSTRAINT `ck_wms_production_info_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS库存单元生产信息';

CREATE TABLE `wms_material_attribute_value` (
  `attribute_value_id` bigint NOT NULL COMMENT '物料属性值主键，系统生成',
  `production_info_id` bigint NOT NULL COMMENT '生产信息ID，逻辑关联 wms_production_info.production_info_id',
  `attribute_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '属性键，逻辑关联 wms_attribute_catalog.attribute_key',
  `seq_no` int NOT NULL COMMENT '同属性多值序号，从1开始',
  `value_text` longtext COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '文本属性值',
  `value_number` decimal(38,12) DEFAULT NULL COMMENT '数值属性值',
  `value_date` date DEFAULT NULL COMMENT '日期属性值',
  `value_datetime` datetime(3) DEFAULT NULL COMMENT '日期时间属性值',
  `unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '数值属性单位',
  `source_ref` varchar(500) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '脱敏来源或证据引用',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`attribute_value_id`),
  UNIQUE KEY `uk_wms_material_attribute_value_seq` (`production_info_id`,`attribute_key`,`seq_no`),
  KEY `idx_wms_material_attribute_value_key` (`attribute_key`,`production_info_id`),
  CONSTRAINT `ck_wms_material_attribute_value_seq` CHECK (`seq_no` >= 1),
  CONSTRAINT `ck_wms_material_attribute_value_at_most_one_value` CHECK (
    (`value_text` IS NOT NULL) + (`value_number` IS NOT NULL)
    + (`value_date` IS NOT NULL) + (`value_datetime` IS NOT NULL) <= 1
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS库存单元可扩展属性值';

CREATE TABLE `wms_load_node` (
  `load_node_id` bigint NOT NULL COMMENT '统一装载节点主键，系统生成',
  `node_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '节点类型：MATERIAL/CARRIER',
  `inventory_id` bigint DEFAULT NULL COMMENT '物料节点库存ID，逻辑关联 wms_inventory.inventory_id',
  `container_id` bigint DEFAULT NULL COMMENT '载具节点容器ID，逻辑关联 wms_container.container_id',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '节点状态，未闭合代码由后续目录约束',
  `revision` int NOT NULL DEFAULT 1 COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`load_node_id`),
  UNIQUE KEY `uk_wms_load_node_inventory` (`inventory_id`),
  UNIQUE KEY `uk_wms_load_node_container` (`container_id`),
  KEY `idx_wms_load_node_type_status` (`node_type`,`status`),
  CONSTRAINT `ck_wms_load_node_exactly_one_ref` CHECK (
    (`inventory_id` IS NOT NULL) + (`container_id` IS NOT NULL) = 1
  ),
  CONSTRAINT `ck_wms_load_node_type` CHECK (`node_type` IN ('MATERIAL','CARRIER')),
  CONSTRAINT `ck_wms_load_node_type_ref` CHECK (
    (`node_type` = 'MATERIAL' AND `inventory_id` IS NOT NULL AND `container_id` IS NULL)
    OR
    (`node_type` = 'CARRIER' AND `inventory_id` IS NULL AND `container_id` IS NOT NULL)
  ),
  CONSTRAINT `ck_wms_load_node_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS统一装载节点';

CREATE TABLE `wms_load_relation` (
  `relation_id` bigint NOT NULL COMMENT '当前装载关系主键，系统生成',
  `child_node_id` bigint NOT NULL COMMENT '子节点ID，逻辑关联 wms_load_node.load_node_id',
  `parent_node_id` bigint NOT NULL COMMENT '父载具节点ID，逻辑关联 wms_load_node.load_node_id',
  `revision` int NOT NULL DEFAULT 1 COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`relation_id`),
  UNIQUE KEY `uk_wms_load_relation_child` (`child_node_id`),
  KEY `idx_wms_load_relation_parent` (`parent_node_id`,`child_node_id`),
  CONSTRAINT `ck_wms_load_relation_not_self` CHECK (`child_node_id` <> `parent_node_id`),
  CONSTRAINT `ck_wms_load_relation_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS当前装载父子关系';

CREATE TABLE `wms_load_relation_history` (
  `relation_history_id` bigint NOT NULL COMMENT '装载关系历史主键，系统生成',
  `child_node_id` bigint NOT NULL COMMENT '子节点ID快照',
  `from_parent_node_id` bigint DEFAULT NULL COMMENT '变更前父载具ID，空表示根节点',
  `to_parent_node_id` bigint DEFAULT NULL COMMENT '变更后父载具ID，空表示拆为根节点',
  `relation_revision` int NOT NULL COMMENT '变更后关系修订号',
  `effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '领域效果键WMS:SHA-256',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型快照',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID快照',
  `business_line_id` bigint DEFAULT NULL COMMENT '父业务明细ID快照',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'WMS任务键',
  `plan_instance_id` bigint DEFAULT NULL COMMENT '计划实例ID',
  `event_id` bigint DEFAULT NULL COMMENT '计划事件ID',
  `attempt_id` bigint DEFAULT NULL COMMENT '计划尝试ID',
  `operator_id` bigint DEFAULT NULL COMMENT '实际操作者ID快照',
  `operator_name` varchar(128) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '实际操作者名称快照',
  `operator_department_id` bigint DEFAULT NULL COMMENT '实际操作者部门ID快照',
  `operator_department_name` varchar(128) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '实际操作者部门名称快照',
  `occurred_at` datetime(3) NOT NULL COMMENT '关系业务发生时间',
  `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事实记录时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者，追加事实由服务禁止更新',
  `update_time` datetime(3) DEFAULT NULL COMMENT '更新时间，追加事实由服务禁止更新',
  PRIMARY KEY (`relation_history_id`),
  KEY `idx_wms_load_relation_history_effect` (`effect_key`),
  KEY `idx_wms_load_relation_history_child_time` (`child_node_id`,`occurred_at`),
  KEY `idx_wms_load_relation_history_task` (`business_type`,`business_id`,`task_key`),
  KEY `idx_wms_load_relation_history_instance` (`plan_instance_id`),
  CONSTRAINT `ck_wms_load_relation_history_revision` CHECK (`relation_revision` >= 1),
  CONSTRAINT `ck_wms_load_relation_history_effect_format` CHECK (
    REGEXP_LIKE(`effect_key`, _utf8mb4'^WMS:[0-9a-f]{64}$', 'c')
  ),
  CONSTRAINT `ck_wms_load_relation_history_effect_length` CHECK (CHAR_LENGTH(`effect_key`) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS装载关系变更历史';

CREATE TABLE `wms_storage_info` (
  `storage_info_id` bigint NOT NULL COMMENT '根节点存放信息主键，系统生成',
  `root_node_id` bigint NOT NULL COMMENT '装载根节点ID，逻辑关联 wms_load_node.load_node_id',
  `location_kind` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '位置类型：STORAGE/OUTSIDE/IN_TRANSIT/UNKNOWN',
  `storage_id` bigint DEFAULT NULL COMMENT '物理储位ID，仅STORAGE逻辑关联 wms_storage.storage_id',
  `revision` int NOT NULL DEFAULT 1 COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`storage_info_id`),
  UNIQUE KEY `uk_wms_storage_info_root` (`root_node_id`),
  KEY `idx_wms_storage_info_location` (`location_kind`,`storage_id`),
  KEY `idx_wms_storage_info_storage` (`storage_id`,`root_node_id`),
  CONSTRAINT `ck_wms_storage_info_location_kind` CHECK (
    `location_kind` IN ('STORAGE','OUTSIDE','IN_TRANSIT','UNKNOWN')
  ),
  CONSTRAINT `ck_wms_storage_info_storage_ref` CHECK (
    (`location_kind` = 'STORAGE' AND `storage_id` IS NOT NULL)
    OR
    (`location_kind` IN ('OUTSIDE','IN_TRANSIT','UNKNOWN') AND `storage_id` IS NULL)
  ),
  CONSTRAINT `ck_wms_storage_info_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS装载根节点存放信息';

CREATE TABLE `wms_occupancy_effect` (
  `occupancy_effect_id` bigint NOT NULL COMMENT '占用事务头主键，系统生成',
  `effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '领域效果键WMS:SHA-256',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型快照',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID快照',
  `business_line_id` bigint DEFAULT NULL COMMENT '父业务明细ID快照',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'WMS任务键',
  `plan_instance_id` bigint DEFAULT NULL COMMENT '计划实例ID',
  `event_id` bigint DEFAULT NULL COMMENT '计划事件ID',
  `attempt_id` bigint DEFAULT NULL COMMENT '计划尝试ID',
  `trace_id` bigint DEFAULT NULL COMMENT '物料trace事实ID，逻辑关联 wms_material_trace_history.trace_id',
  `effect_result` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '事务结果，未闭合代码由后续合同约束',
  `reason` varchar(500) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '业务原因或脱敏说明',
  `evidence_ref` varchar(500) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '脱敏证据引用',
  `occurred_at` datetime(3) NOT NULL COMMENT '业务发生时间',
  `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事实记录时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者，追加事实由服务禁止更新',
  `update_time` datetime(3) DEFAULT NULL COMMENT '更新时间，追加事实由服务禁止更新',
  PRIMARY KEY (`occupancy_effect_id`),
  UNIQUE KEY `uk_wms_occupancy_effect_effect` (`effect_key`),
  KEY `idx_wms_occupancy_effect_business` (`business_type`,`business_id`,`occurred_at`),
  KEY `idx_wms_occupancy_effect_instance` (`plan_instance_id`),
  KEY `idx_wms_occupancy_effect_trace` (`trace_id`),
  CONSTRAINT `ck_wms_occupancy_effect_effect_format` CHECK (
    REGEXP_LIKE(`effect_key`, _utf8mb4'^WMS:[0-9a-f]{64}$', 'c')
  ),
  CONSTRAINT `ck_wms_occupancy_effect_effect_length` CHECK (CHAR_LENGTH(`effect_key`) <= 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS储位占用事务效果头';

CREATE TABLE `wms_storage_occupancy` (
  `occupancy_id` bigint NOT NULL COMMENT '储位占用明细主键，服务显式分配，不使用AUTO_INCREMENT',
  `occupancy_effect_id` bigint NOT NULL COMMENT '占用事务头ID，逻辑关联 wms_occupancy_effect.occupancy_effect_id',
  `storage_id` bigint NOT NULL COMMENT '物理储位ID，逻辑关联 wms_storage.storage_id',
  `root_node_id` bigint NOT NULL COMMENT '装载根节点ID，逻辑关联 wms_load_node.load_node_id',
  `dimension_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '容量维度键',
  `occupied_qty` decimal(38,12) NOT NULL COMMENT '当前占用数量快照',
  `unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '占用单位',
  `generation` int NOT NULL COMMENT '同一根物理占用的代次',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '状态：ACTIVE/CLOSED',
  `detail_key` bigint GENERATED ALWAYS AS (`occupancy_id`) STORED NOT NULL COMMENT '明细ID独立别名，组合事实由六列唯一键保证',
  `active_occupancy_key` bigint GENERATED ALWAYS AS (IF(`status` = 'ACTIVE', `occupancy_id`, NULL)) STORED COMMENT '活动明细ID别名，CLOSED为NULL',
  `active_marker` tinyint GENERATED ALWAYS AS (IF(`status` = 'ACTIVE', 1, NULL)) STORED COMMENT '活动组合标志，NULL只豁免CLOSED历史',
  `revision` int NOT NULL DEFAULT 1 COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`occupancy_id`),
  UNIQUE KEY `uk_wms_storage_occupancy_fact` (
    `occupancy_effect_id`,`storage_id`,`root_node_id`,`dimension_key`,`unit`,`generation`
  ),
  UNIQUE KEY `uk_wms_storage_occupancy_detail` (`detail_key`),
  UNIQUE KEY `uk_wms_storage_occupancy_active_key` (`active_occupancy_key`),
  UNIQUE KEY `uk_wms_storage_occupancy_active_fact` (
    `storage_id`,`root_node_id`,`dimension_key`,`unit`,`active_marker`
  ),
  KEY `idx_wms_storage_occupancy_effect` (`occupancy_effect_id`),
  KEY `idx_wms_storage_occupancy_storage_status` (`storage_id`,`status`,`dimension_key`),
  KEY `idx_wms_storage_occupancy_root_status` (`root_node_id`,`status`),
  CONSTRAINT `ck_wms_storage_occupancy_status` CHECK (`status` IN ('ACTIVE','CLOSED')),
  CONSTRAINT `ck_wms_storage_occupancy_active_key` CHECK (
    (`status` = 'ACTIVE' AND `active_occupancy_key` IS NOT NULL)
    OR
    (`status` = 'CLOSED' AND `active_occupancy_key` IS NULL)
  ),
  CONSTRAINT `ck_wms_storage_occupancy_qty` CHECK (`occupied_qty` >= 0),
  CONSTRAINT `ck_wms_storage_occupancy_generation` CHECK (`generation` >= 1),
  CONSTRAINT `ck_wms_storage_occupancy_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS物理储位占用明细';

CREATE TABLE IF NOT EXISTS `wms_business_task` (
  `task_id` bigint NOT NULL COMMENT 'WMS业务任务主键，系统生成',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型稳定编码',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID',
  `line_id` bigint DEFAULT NULL COMMENT '父业务明细ID',
  `allocation_unit_id` bigint NOT NULL COMMENT '分配单元ID',
  `round_no` int NOT NULL DEFAULT 0 COMMENT '轮次号',
  `phase` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '阶段编码',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '业务任务唯一键',
  `plan_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '计划类型编码',
  `plan_instance_id` bigint NOT NULL COMMENT '计划实例ID',
  `input_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规范化输入SHA-256小写十六进制',
  `input_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '完整冻结业务命令JSON',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/RUNNING/COMPLETED/FAILED/CANCELLED',
  `revision` int NOT NULL DEFAULT 1 COMMENT '业务任务修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `uk_wms_business_task_key` (`business_type`,`task_key`),
  UNIQUE KEY `uk_wms_business_task_instance` (`plan_instance_id`),
  KEY `idx_wms_business_task_business` (`business_type`,`business_id`,`phase`),
  CONSTRAINT `ck_wms_business_task_round_no` CHECK (`round_no` >= 0),
  CONSTRAINT `ck_wms_business_task_revision` CHECK (`revision` >= 1),
  CONSTRAINT `ck_wms_business_task_input_hash` CHECK (
    REGEXP_LIKE(`input_hash`, _utf8mb4'^[0-9a-f]{64}$', 'c')
  ),
  CONSTRAINT `ck_wms_business_task_input_json` CHECK (JSON_VALID(`input_json`)),
  CONSTRAINT `ck_wms_business_task_status` CHECK (
    `status` IN ('ACTIVE','RUNNING','COMPLETED','FAILED','CANCELLED')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS业务任务与冻结输入';

CREATE TABLE IF NOT EXISTS `wms_business_task_barrier` (
  `barrier_id` bigint NOT NULL COMMENT '业务任务完成屏障主键，系统生成',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型稳定编码',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID',
  `barrier_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务屏障稳定键',
  `expected_task_keys_json` json NOT NULL COMMENT '预期taskKey字符串数组',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN/COMPLETED',
  `revision` int NOT NULL DEFAULT 1 COMMENT '屏障修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`barrier_id`),
  UNIQUE KEY `uk_wms_business_task_barrier_key` (`business_type`,`business_id`,`barrier_key`),
  KEY `idx_wms_business_task_barrier_status` (`business_type`,`business_id`,`status`),
  CONSTRAINT `ck_wms_business_task_barrier_tasks` CHECK (JSON_TYPE(`expected_task_keys_json`) = 'ARRAY'),
  CONSTRAINT `ck_wms_business_task_barrier_status` CHECK (`status` IN ('OPEN','COMPLETED')),
  CONSTRAINT `ck_wms_business_task_barrier_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS父业务任务完成屏障';

CREATE TABLE IF NOT EXISTS `wms_space_change_order` (
  `change_order_id` bigint NOT NULL COMMENT '空间变更单主键，系统生成',
  `command_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定命令键',
  `change_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'CREATE/UPDATE/RETIRE',
  `object_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'WAREHOUSE/ZONE/STORAGE',
  `object_id` bigint DEFAULT NULL COMMENT '空间对象ID，创建时由校验动作分配',
  `expected_revision` int DEFAULT NULL COMMENT '更新或退役前对象修订号',
  `company_id` bigint DEFAULT NULL COMMENT '解析后的公司ID',
  `warehouse_id` bigint DEFAULT NULL COMMENT '解析后的仓库ID',
  `zone_id` bigint DEFAULT NULL COMMENT '解析后的库区ID',
  `input_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '冻结输入SHA-256',
  `input_json` json NOT NULL COMMENT '冻结命令',
  `validated_config_json` json DEFAULT NULL COMMENT '校验后完整配置',
  `canonical_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '完整配置SHA-256',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/VALIDATED/CHECKED/APPLIED/RETIRED/COMPLETED/FAILED/CANCELLED',
  `revision` int NOT NULL DEFAULT 1 COMMENT '变更单修订号',
  `plan_instance_id` bigint DEFAULT NULL COMMENT '业务计划实例ID',
  `business_task_id` bigint DEFAULT NULL COMMENT 'WMS业务任务ID',
  `location_version_id` bigint DEFAULT NULL COMMENT '本次提交的位置版本ID',
  `location_effective_revision` int DEFAULT NULL COMMENT '生效指针修订号',
  `object_revision` int DEFAULT NULL COMMENT '本次提交后的空间对象修订号',
  `reason` varchar(500) DEFAULT NULL COMMENT '业务原因',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`change_order_id`),
  UNIQUE KEY `uk_wms_space_change_order_command` (`command_key`),
  UNIQUE KEY `uk_wms_space_change_order_instance` (`plan_instance_id`),
  UNIQUE KEY `uk_wms_space_change_order_task` (`business_task_id`),
  KEY `idx_wms_space_change_order_status` (`status`,`object_type`,`update_time`),
  KEY `idx_wms_space_change_order_object` (`object_type`,`object_id`),
  CONSTRAINT `ck_wms_space_change_order_type` CHECK (`change_type` IN ('CREATE','UPDATE','RETIRE')),
  CONSTRAINT `ck_wms_space_change_order_object_type` CHECK (`object_type` IN ('WAREHOUSE','ZONE','STORAGE')),
  CONSTRAINT `ck_wms_space_change_order_status` CHECK (`status` IN ('CREATED','VALIDATED','CHECKED','APPLIED','RETIRED','COMPLETED','FAILED','CANCELLED')),
  CONSTRAINT `ck_wms_space_change_order_hash` CHECK (REGEXP_LIKE(`input_hash`, _utf8mb4'^[0-9a-f]{64}$', 'c')),
  CONSTRAINT `ck_wms_space_change_order_digest` CHECK (`canonical_digest` IS NULL OR REGEXP_LIKE(`canonical_digest`, _utf8mb4'^[0-9a-f]{64}$', 'c')),
  CONSTRAINT `ck_wms_space_change_order_revision` CHECK (`revision` >= 1),
  CONSTRAINT `ck_wms_space_change_order_expected_revision` CHECK (`expected_revision` IS NULL OR `expected_revision` >= 0),
  CONSTRAINT `ck_wms_space_change_order_pointer_revision` CHECK (`location_effective_revision` IS NULL OR `location_effective_revision` >= 1),
  CONSTRAINT `ck_wms_space_change_order_object_revision` CHECK (`object_revision` IS NULL OR `object_revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS空间生命周期变更单';

CREATE TABLE IF NOT EXISTS `wms_logical_merge_order` (
  `merge_order_id` bigint NOT NULL COMMENT '逻辑合并父单主键',
  `command_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父单稳定命令键',
  `target_scope_version_id` bigint NOT NULL COMMENT '目标逻辑作用域版本',
  `source_scope_version_ids_json` json NOT NULL COMMENT '固定来源版本ID数组',
  `compatible_scope_version_ids_json` json NOT NULL COMMENT '显式兼容来源版本ID数组',
  `batch_size` int NOT NULL COMMENT '固定批大小',
  `input_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父单冻结输入摘要',
  `input_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父单冻结命令原文',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'BATCHING' COMMENT 'BATCHING/RUNNING/VERIFYING/COMPLETED/FAILED/CANCELLED',
  `expected_batch_count` int NOT NULL COMMENT '预期固定批次数',
  `conflict_count` int NOT NULL DEFAULT 0 COMMENT '保留旧归属的冲突物料数',
  `revision` int NOT NULL DEFAULT 1 COMMENT '父单修订号',
  `parent_plan_instance_id` bigint DEFAULT NULL COMMENT '父计划实例ID',
  `parent_business_task_id` bigint DEFAULT NULL COMMENT '父业务任务ID',
  `barrier_id` bigint DEFAULT NULL COMMENT '完成屏障ID',
  `reason` varchar(500) DEFAULT NULL COMMENT '业务原因',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`merge_order_id`),
  UNIQUE KEY `uk_wms_logical_merge_order_command` (`command_key`),
  UNIQUE KEY `uk_wms_logical_merge_order_parent_instance` (`parent_plan_instance_id`),
  UNIQUE KEY `uk_wms_logical_merge_order_parent_task` (`parent_business_task_id`),
  UNIQUE KEY `uk_wms_logical_merge_order_barrier` (`barrier_id`),
  KEY `idx_wms_logical_merge_order_status` (`status`,`update_time`),
  CONSTRAINT `ck_wms_logical_merge_order_batch_size` CHECK (`batch_size` BETWEEN 1 AND 500),
  CONSTRAINT `ck_wms_logical_merge_order_status` CHECK (`status` IN ('BATCHING','RUNNING','VERIFYING','COMPLETED','FAILED','CANCELLED')),
  CONSTRAINT `ck_wms_logical_merge_order_hash` CHECK (REGEXP_LIKE(`input_hash`, _utf8mb4'^[0-9a-f]{64}$', 'c')),
  CONSTRAINT `ck_wms_logical_merge_order_batches` CHECK (`expected_batch_count` >= 1),
  CONSTRAINT `ck_wms_logical_merge_order_conflicts` CHECK (`conflict_count` >= 0),
  CONSTRAINT `ck_wms_logical_merge_order_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS逻辑归属合并父单';

CREATE TABLE IF NOT EXISTS `wms_logical_merge_batch` (
  `batch_id` bigint NOT NULL COMMENT '固定批次主键',
  `merge_order_id` bigint NOT NULL COMMENT '逻辑合并父单ID',
  `batch_no` int NOT NULL COMMENT '从1开始的固定批号',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '批次业务任务键',
  `plan_instance_id` bigint NOT NULL COMMENT '批次计划实例ID',
  `business_task_id` bigint NOT NULL COMMENT '批次业务任务ID',
  `input_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '批次冻结输入摘要',
  `input_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '批次冻结命令原文',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/RUNNING/APPLIED/CLOSED/CONFLICT/FAILED/CANCELLED',
  `migrated_count` int NOT NULL DEFAULT 0 COMMENT '本批迁移数量',
  `conflict_retained_count` int NOT NULL DEFAULT 0 COMMENT '本批保留旧归属数量',
  `revision` int NOT NULL DEFAULT 1 COMMENT '批次修订号',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`batch_id`),
  UNIQUE KEY `uk_wms_logical_merge_batch_no` (`merge_order_id`,`batch_no`),
  UNIQUE KEY `uk_wms_logical_merge_batch_task_key` (`task_key`),
  UNIQUE KEY `uk_wms_logical_merge_batch_instance` (`plan_instance_id`),
  UNIQUE KEY `uk_wms_logical_merge_batch_business_task` (`business_task_id`),
  KEY `idx_wms_logical_merge_batch_status` (`merge_order_id`,`status`),
  CONSTRAINT `ck_wms_logical_merge_batch_no` CHECK (`batch_no` >= 1),
  CONSTRAINT `ck_wms_logical_merge_batch_status` CHECK (`status` IN ('CREATED','RUNNING','APPLIED','CLOSED','CONFLICT','FAILED','CANCELLED')),
  CONSTRAINT `ck_wms_logical_merge_batch_hash` CHECK (REGEXP_LIKE(`input_hash`, _utf8mb4'^[0-9a-f]{64}$', 'c')),
  CONSTRAINT `ck_wms_logical_merge_batch_counts` CHECK (`migrated_count` >= 0 AND `conflict_retained_count` >= 0),
  CONSTRAINT `ck_wms_logical_merge_batch_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS逻辑归属合并固定批次';

CREATE TABLE `wms_material_trace_history` (
  `trace_id` bigint NOT NULL COMMENT '物料事实trace主键，系统生成',
  `effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '领域效果键WMS:SHA-256',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型稳定编码',
  `business_name` varchar(128) COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '父业务类型名称快照',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID',
  `business_no` varchar(100) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '父业务号快照',
  `business_line_id` bigint DEFAULT NULL COMMENT '父业务明细ID',
  `root_business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '根父业务类型稳定编码',
  `root_business_id` bigint NOT NULL COMMENT '根父业务内部ID',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'WMS任务键',
  `plan_instance_id` bigint DEFAULT NULL COMMENT '计划实例ID',
  `plan_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '计划编码',
  `plan_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '计划类型',
  `definition_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '计划定义版本',
  `event_id` bigint DEFAULT NULL COMMENT '计划事件ID',
  `attempt_id` bigint DEFAULT NULL COMMENT '计划尝试ID',
  `input_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规范化输入SHA-256小写十六进制',
  `reverse_trace_id` bigint DEFAULT NULL COMMENT '新COMPENSATION行引用被补偿的原trace ID，不更新原FACT',
  `trace_kind` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '事实类型：FACT/COMPENSATION',
  `effect_result` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '成功事实结果，未闭合代码由后续合同约束',
  `reason` varchar(500) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '业务原因',
  `evidence_ref` varchar(500) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '脱敏证据引用',
  `actor_id` bigint DEFAULT NULL COMMENT '实际操作者ID快照',
  `actor_name` varchar(128) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '实际操作者名称快照',
  `initiator_id` varchar(128) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '发起人标识快照',
  `initiator_name` varchar(128) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '发起人名称快照',
  `department_id` bigint DEFAULT NULL COMMENT '操作者部门ID快照',
  `department_name` varchar(128) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '操作者部门名称快照',
  `execution_subject` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '执行主体快照，代码集待后续合同确定',
  `execution_account` varchar(64) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '执行账号快照',
  `on_behalf_of` varchar(128) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '授权代办身份快照',
  `source_system` varchar(64) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '来源系统',
  `terminal_id` varchar(128) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '来源终端标识',
  `request_id` varchar(64) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '请求ID',
  `source_trace_id` varchar(128) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '上游链路追踪ID',
  `occurred_at` datetime(3) NOT NULL COMMENT '真实业务发生时间',
  `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事实记录时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者，追加事实由服务禁止更新',
  `update_time` datetime(3) DEFAULT NULL COMMENT '更新时间，追加事实由服务禁止更新',
  PRIMARY KEY (`trace_id`),
  UNIQUE KEY `uk_wms_material_trace_history_effect` (`effect_key`),
  KEY `idx_wms_material_trace_history_business` (`business_type`,`business_id`,`occurred_at`),
  KEY `idx_wms_material_trace_history_task` (`task_key`,`occurred_at`),
  KEY `idx_wms_material_trace_history_instance` (`plan_instance_id`),
  KEY `idx_wms_material_trace_history_reverse` (`reverse_trace_id`),
  CONSTRAINT `ck_wms_material_trace_history_effect_format` CHECK (
    REGEXP_LIKE(`effect_key`, _utf8mb4'^WMS:[0-9a-f]{64}$', 'c')
  ),
  CONSTRAINT `ck_wms_material_trace_history_effect_length` CHECK (CHAR_LENGTH(`effect_key`) <= 128),
  CONSTRAINT `ck_wms_material_trace_history_input_hash` CHECK (
    REGEXP_LIKE(`input_hash`, _utf8mb4'^[0-9a-f]{64}$', 'c')
  ),
  CONSTRAINT `ck_wms_material_trace_history_kind` CHECK (`trace_kind` IN ('FACT','COMPENSATION')),
  CONSTRAINT `ck_wms_material_trace_history_reverse_not_self` CHECK (`reverse_trace_id` IS NULL OR `reverse_trace_id` <> `trace_id`),
  CONSTRAINT `ck_wms_material_trace_history_reverse_shape` CHECK (
    (`trace_kind` = 'FACT' AND `reverse_trace_id` IS NULL)
    OR
    (`trace_kind` = 'COMPENSATION' AND `reverse_trace_id` IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS成功物料事实trace头';

CREATE TABLE `wms_material_trace_item` (
  `item_id` bigint NOT NULL COMMENT 'trace物料项主键，系统生成',
  `trace_id` bigint NOT NULL COMMENT '物料事实trace头ID，逻辑关联 wms_material_trace_history.trace_id',
  `inventory_id` bigint NOT NULL COMMENT '库存单元ID，逻辑关联 wms_inventory.inventory_id',
  `stock_unit_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '原始标签/库存单元编码快照',
  `material_code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '料号快照，可空，不伪造默认料号',
  `production_info_id` bigint DEFAULT NULL COMMENT '生产信息ID快照',
  `production_revision` int DEFAULT NULL COMMENT '生产信息修订号快照',
  `production_snapshot` json DEFAULT NULL COMMENT '事实发生时生产属性完整快照，后续服务构造并与事实同事务落盘',
  `quantity` decimal(20,6) DEFAULT NULL COMMENT '事实数量',
  `unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '事实单位',
  `before_qty` decimal(20,6) DEFAULT NULL COMMENT '变更前数量，可为0',
  `delta_qty` decimal(20,6) DEFAULT NULL COMMENT '数量增量，可为负',
  `after_qty` decimal(20,6) DEFAULT NULL COMMENT '变更后数量，可为0',
  `physical_from_storage_id` bigint DEFAULT NULL COMMENT '物理源储位ID快照',
  `physical_from_storage_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '物理源储位编码快照',
  `physical_to_storage_id` bigint DEFAULT NULL COMMENT '物理目标储位ID快照',
  `physical_to_storage_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '物理目标储位编码快照',
  `logical_from_scope_version_id` bigint DEFAULT NULL COMMENT '变更前逻辑归属版本ID快照',
  `logical_to_scope_version_id` bigint DEFAULT NULL COMMENT '变更后逻辑归属版本ID快照',
  `load_parent_before_id` bigint DEFAULT NULL COMMENT '变更前装载父级节点ID快照',
  `load_parent_after_id` bigint DEFAULT NULL COMMENT '变更后装载父级节点ID快照',
  `load_root_before_id` bigint DEFAULT NULL COMMENT '变更前装载根节点ID快照',
  `load_root_after_id` bigint DEFAULT NULL COMMENT '变更后装载根节点ID快照',
  `lifecycle_before` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '变更前生命周期快照',
  `lifecycle_after` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '变更后生命周期快照',
  `rule_package_version_id` bigint DEFAULT NULL COMMENT '适用规则包版本ID快照',
  `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事实记录时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者，追加事实由服务禁止更新',
  `update_time` datetime(3) DEFAULT NULL COMMENT '更新时间，追加事实由服务禁止更新',
  PRIMARY KEY (`item_id`),
  KEY `idx_wms_material_trace_item_trace` (`trace_id`,`inventory_id`),
  KEY `idx_wms_material_trace_item_inventory_time` (`inventory_id`,`recorded_at`),
  KEY `idx_wms_material_trace_item_material` (`material_code`,`recorded_at`),
  KEY `idx_wms_material_trace_item_production` (`production_info_id`),
  CONSTRAINT `ck_wms_material_trace_item_production_revision` CHECK (
    `production_revision` IS NULL OR `production_revision` >= 1
  ),
  CONSTRAINT `ck_wms_material_trace_item_before_qty` CHECK (`before_qty` IS NULL OR `before_qty` >= 0),
  CONSTRAINT `ck_wms_material_trace_item_quantity` CHECK (`quantity` IS NULL OR `quantity` >= 0),
  CONSTRAINT `ck_wms_material_trace_item_after_qty` CHECK (`after_qty` IS NULL OR `after_qty` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS物料事实逐物料项';

CREATE TABLE `wms_material_trace_edge` (
  `trace_edge_id` bigint NOT NULL COMMENT '生产家谱边主键，系统生成',
  `trace_id` bigint NOT NULL COMMENT '所属物料事实trace头ID，逻辑关联 wms_material_trace_history.trace_id',
  `source_item_id` bigint NOT NULL COMMENT '来源物料项ID，逻辑关联 wms_material_trace_item.item_id',
  `target_item_id` bigint NOT NULL COMMENT '目标物料项ID，逻辑关联 wms_material_trace_item.item_id',
  `quantity` decimal(20,6) NOT NULL COMMENT '来源到目标的正物料数量',
  `unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '家谱数量单位',
  `group_no` int NOT NULL COMMENT '拆合关系组序号',
  `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事实记录时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者，追加事实由服务禁止更新',
  `update_time` datetime(3) DEFAULT NULL COMMENT '更新时间，追加事实由服务禁止更新',
  PRIMARY KEY (`trace_edge_id`),
  UNIQUE KEY `uk_wms_material_trace_edge` (`trace_id`,`source_item_id`,`target_item_id`,`group_no`),
  KEY `idx_wms_material_trace_edge_source` (`source_item_id`),
  KEY `idx_wms_material_trace_edge_target` (`target_item_id`),
  CONSTRAINT `ck_wms_material_trace_edge_group` CHECK (`group_no` >= 1),
  CONSTRAINT `ck_wms_material_trace_edge_qty` CHECK (`quantity` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS物料生产家谱N:M边';

-- P5 分配、锁、释放与数量预留六表目标结构。
-- 定义取自 iws_inventory_management_allocation_foundation.sql 在 MySQL 8.0.45
-- 隔离执行后的 INFORMATION_SCHEMA 目标态；空库直接建最终结构，不写业务 seed。
CREATE TABLE `wms_access_channel_lock` (
  `channel_lock_id` bigint NOT NULL COMMENT '通道锁主键，服务显式分配',
  `channel_id` bigint NOT NULL COMMENT '访问通道ID，逻辑关联 wms_access_channel.channel_id',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型稳定编码',
  `business_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '父业务类型名称快照',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID',
  `business_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '父业务号快照',
  `business_line_id` bigint DEFAULT NULL COMMENT '父业务明细ID',
  `allocation_unit_id` bigint NOT NULL COMMENT '不可拆分配单元ID',
  `round_no` int NOT NULL COMMENT '分配轮次',
  `phase` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '任务阶段：LOCK/RELEASE',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定任务键',
  `plan_instance_id` bigint NOT NULL COMMENT '计划实例ID',
  `lock_effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '通道锁效果键WMS:SHA-256',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '状态：LOCKED/CONSUMED/RELEASED',
  `effect_result` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'NONE' COMMENT '外部效果：NONE/SEND_INTENT/ACCEPTED/UNKNOWN/CONSUMED/NO_EFFECT',
  `active_channel_key` bigint GENERATED ALWAYS AS (if((`status` = _utf8mb4'LOCKED'),`channel_id`,NULL)) STORED COMMENT '活动通道ID，终态为NULL',
  `path_member_ids_json` json NOT NULL COMMENT '锁内路径成员ID数组快照',
  `path_revision` int NOT NULL COMMENT '通道路径修订号快照',
  `evidence_revision` int NOT NULL DEFAULT '0' COMMENT '无效果或外部效果证据修订号',
  `terminal_effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '通道锁终态效果键',
  `terminal_release_id` bigint DEFAULT NULL COMMENT '终态释放流水ID',
  `consumption_trace_id` bigint DEFAULT NULL COMMENT '消费trace头ID',
  `terminal_at` datetime(3) DEFAULT NULL COMMENT '终态时间',
  `revision` int NOT NULL DEFAULT '1' COMMENT '通道锁修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`channel_lock_id`),
  UNIQUE KEY `uk_wms_access_channel_lock_effect` (`lock_effect_key`),
  UNIQUE KEY `uk_wms_access_channel_lock_terminal_effect` (`terminal_effect_key`),
  UNIQUE KEY `uk_wms_access_channel_lock_terminal_release` (`terminal_release_id`),
  UNIQUE KEY `uk_wms_access_channel_lock_active` (`active_channel_key`),
  KEY `idx_wms_access_channel_lock_business` (`business_type`,`business_id`,`business_line_id`),
  KEY `idx_wms_access_channel_lock_task` (`business_type`,`task_key`),
  KEY `idx_wms_access_channel_lock_channel_status` (`channel_id`,`status`,`evidence_revision`),
  CONSTRAINT `ck_wms_access_channel_lock_active` CHECK ((((`status` = _utf8mb4'LOCKED') and (`active_channel_key` is not null) and (`terminal_effect_key` is null) and (`terminal_release_id` is null) and (`consumption_trace_id` is null) and (`terminal_at` is null) and (`effect_result` in (_utf8mb4'NONE',_utf8mb4'SEND_INTENT',_utf8mb4'ACCEPTED',_utf8mb4'UNKNOWN'))) or ((`status` = _utf8mb4'CONSUMED') and (`active_channel_key` is null) and (`terminal_effect_key` is not null) and (`consumption_trace_id` is not null) and (`terminal_release_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4'CONSUMED')) or ((`status` = _utf8mb4'RELEASED') and (`active_channel_key` is null) and (`terminal_effect_key` is not null) and (`terminal_release_id` is not null) and (`consumption_trace_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4'NO_EFFECT')))),
  CONSTRAINT `ck_wms_access_channel_lock_effect` CHECK (regexp_like(`lock_effect_key`,_utf8mb4'^WMS:[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_access_channel_lock_evidence_revision` CHECK ((`evidence_revision` >= 0)),
  CONSTRAINT `ck_wms_access_channel_lock_path_json` CHECK ((json_type(`path_member_ids_json`) = _utf8mb4'ARRAY')),
  CONSTRAINT `ck_wms_access_channel_lock_path_revision` CHECK ((`path_revision` >= 1)),
  CONSTRAINT `ck_wms_access_channel_lock_phase` CHECK ((`phase` in (_utf8mb4'LOCK',_utf8mb4'RELEASE'))),
  CONSTRAINT `ck_wms_access_channel_lock_result` CHECK ((`effect_result` in (_utf8mb4'NONE',_utf8mb4'SEND_INTENT',_utf8mb4'ACCEPTED',_utf8mb4'UNKNOWN',_utf8mb4'CONSUMED',_utf8mb4'NO_EFFECT'))),
  CONSTRAINT `ck_wms_access_channel_lock_revision` CHECK ((`revision` >= 1)),
  CONSTRAINT `ck_wms_access_channel_lock_round` CHECK ((`round_no` >= 1)),
  CONSTRAINT `ck_wms_access_channel_lock_status` CHECK ((`status` in (_utf8mb4'LOCKED',_utf8mb4'CONSUMED',_utf8mb4'RELEASED'))),
  CONSTRAINT `ck_wms_access_channel_lock_terminal_effect` CHECK (((`terminal_effect_key` is null) or regexp_like(`terminal_effect_key`,_utf8mb4'^WMS:[0-9a-f]{64}$',_utf8mb4'c')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS持久通道锁';
CREATE TABLE `wms_allocation_decision` (
  `decision_id` bigint NOT NULL COMMENT '分配决策主键，服务显式分配',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型稳定编码',
  `business_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '父业务类型名称快照',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID',
  `business_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '父业务号快照',
  `business_line_id` bigint DEFAULT NULL COMMENT '父业务明细ID',
  `allocation_unit_id` bigint NOT NULL COMMENT '不可拆分配单元ID',
  `round_no` int NOT NULL COMMENT '分配轮次',
  `phase` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '任务阶段：LOCK/RELEASE',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定任务键',
  `plan_instance_id` bigint NOT NULL COMMENT '计划实例ID',
  `effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '决策效果键WMS:SHA-256',
  `input_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '规范化输入SHA-256小写十六进制',
  `rule_package_version_id` bigint NOT NULL COMMENT '规则包版本ID快照',
  `logical_scope_version_id` bigint NOT NULL COMMENT '逻辑归属版本ID快照',
  `space_snapshot_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '空间配置快照SHA-256',
  `capacity_snapshot_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '容量账快照SHA-256',
  `candidate_summary_json` json NOT NULL COMMENT '候选摘要与筛选解释JSON',
  `result` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '结果：EVALUATED/LOCKED/NO_CANDIDATE/BLOCKED',
  `result_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '无解或阻断原因',
  `decided_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '决策时间',
  `revision` int NOT NULL DEFAULT '1' COMMENT '决策修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`decision_id`),
  UNIQUE KEY `uk_wms_allocation_decision_effect` (`effect_key`),
  UNIQUE KEY `uk_wms_allocation_decision_task` (`business_type`,`task_key`),
  UNIQUE KEY `uk_wms_allocation_decision_instance` (`plan_instance_id`),
  KEY `idx_wms_allocation_decision_business` (`business_type`,`business_id`,`business_line_id`),
  KEY `idx_wms_allocation_decision_unit` (`business_type`,`business_id`,`allocation_unit_id`,`round_no`),
  KEY `idx_wms_allocation_decision_result` (`result`,`decided_at`),
  CONSTRAINT `ck_wms_allocation_decision_capacity_hash` CHECK (regexp_like(`capacity_snapshot_hash`,_utf8mb4'^[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_allocation_decision_effect` CHECK (regexp_like(`effect_key`,_utf8mb4'^WMS:[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_allocation_decision_input_hash` CHECK (regexp_like(`input_hash`,_utf8mb4'^[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_allocation_decision_phase` CHECK ((`phase` in (_utf8mb4'LOCK',_utf8mb4'RELEASE'))),
  CONSTRAINT `ck_wms_allocation_decision_result` CHECK ((`result` in (_utf8mb4'EVALUATED',_utf8mb4'LOCKED',_utf8mb4'NO_CANDIDATE',_utf8mb4'BLOCKED'))),
  CONSTRAINT `ck_wms_allocation_decision_revision` CHECK ((`revision` >= 1)),
  CONSTRAINT `ck_wms_allocation_decision_round` CHECK ((`round_no` >= 1)),
  CONSTRAINT `ck_wms_allocation_decision_space_hash` CHECK (regexp_like(`space_snapshot_hash`,_utf8mb4'^[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_allocation_decision_summary` CHECK ((json_type(`candidate_summary_json`) = _utf8mb4'OBJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS分配决策';
CREATE TABLE `wms_allocation_candidate` (
  `candidate_id` bigint NOT NULL COMMENT '候选主键，服务显式分配',
  `decision_id` bigint NOT NULL COMMENT '分配决策ID，逻辑关联 wms_allocation_decision.decision_id',
  `storage_id` bigint NOT NULL COMMENT '候选储位ID，逻辑关联 wms_storage.storage_id',
  `rank_no` int NOT NULL COMMENT '稳定排序序号',
  `eligible_flag` tinyint(1) NOT NULL DEFAULT '1' COMMENT '硬筛选后是否可用：1可用，0不可用',
  `selected_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否选中：1选中，0未选中',
  `sort_key_json` json NOT NULL COMMENT '冻结排序键数组',
  `residual_vector_json` json NOT NULL COMMENT '多维残余向量对象',
  `protection_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '稀缺/深位保护原因',
  `block_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '硬阻挡原因',
  `selected_decision_key` bigint GENERATED ALWAYS AS (if((`selected_flag` = 1),`decision_id`,NULL)) STORED COMMENT '选中候选决策唯一键，未选中为NULL',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`candidate_id`),
  UNIQUE KEY `uk_wms_allocation_candidate_decision_storage` (`decision_id`,`storage_id`),
  UNIQUE KEY `uk_wms_allocation_candidate_decision_rank` (`decision_id`,`rank_no`),
  UNIQUE KEY `uk_wms_allocation_candidate_selected` (`selected_decision_key`),
  KEY `idx_wms_allocation_candidate_storage` (`storage_id`,`decision_id`),
  KEY `idx_wms_allocation_candidate_eligible` (`decision_id`,`eligible_flag`,`rank_no`),
  CONSTRAINT `ck_wms_allocation_candidate_eligible` CHECK ((`eligible_flag` in (0,1))),
  CONSTRAINT `ck_wms_allocation_candidate_rank` CHECK ((`rank_no` >= 1)),
  CONSTRAINT `ck_wms_allocation_candidate_residual_json` CHECK ((json_type(`residual_vector_json`) = _utf8mb4'OBJECT')),
  CONSTRAINT `ck_wms_allocation_candidate_selected` CHECK ((`selected_flag` in (0,1))),
  CONSTRAINT `ck_wms_allocation_candidate_selected_eligible` CHECK (((`selected_flag` = 0) or ((`eligible_flag` = 1) and (`block_reason` is null)))),
  CONSTRAINT `ck_wms_allocation_candidate_sort_json` CHECK ((json_type(`sort_key_json`) = _utf8mb4'ARRAY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS分配候选与排序证据';
CREATE TABLE `wms_allocation_lock` (
  `allocation_lock_id` bigint NOT NULL COMMENT '分配锁主键，服务显式分配',
  `decision_id` bigint NOT NULL COMMENT '分配决策ID，逻辑关联 wms_allocation_decision.decision_id',
  `candidate_id` bigint NOT NULL COMMENT '候选ID，逻辑关联 wms_allocation_candidate.candidate_id',
  `channel_lock_id` bigint DEFAULT NULL COMMENT '通道锁ID，资源域为CHANNEL时必填',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型稳定编码',
  `business_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '父业务类型名称快照',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID',
  `business_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '父业务号快照',
  `business_line_id` bigint DEFAULT NULL COMMENT '父业务明细ID',
  `allocation_unit_id` bigint NOT NULL COMMENT '不可拆分配单元ID',
  `round_no` int NOT NULL COMMENT '分配轮次',
  `phase` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '任务阶段：LOCK/RELEASE',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定任务键',
  `plan_instance_id` bigint NOT NULL COMMENT '计划实例ID',
  `storage_id` bigint NOT NULL COMMENT '锁定的物理储位ID',
  `resource_domain` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '锁域：STORAGE/CHANNEL',
  `lock_kind` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '锁类型：CAPACITY_SHARE/ENTITY_SLOT',
  `dimension_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '容量份额维度键',
  `unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '容量份额单位',
  `locked_qty` decimal(38,12) DEFAULT NULL COMMENT '容量份额数量',
  `slot_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '实体槽稳定键',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '状态：LOCKED/CONSUMED/RELEASED',
  `effect_result` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'NONE' COMMENT '外部效果：NONE/SEND_INTENT/ACCEPTED/UNKNOWN/CONSUMED/NO_EFFECT',
  `effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '分配锁效果键WMS:SHA-256',
  `evidence_revision` int NOT NULL DEFAULT '0' COMMENT '证据版本：无效果或外部效果',
  `candidate_share_key` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS ((case when (`lock_kind` = _utf8mb4'CAPACITY_SHARE') then concat(_utf8mb4'C:',lpad(hex(`candidate_id`),16,_utf8mb4'0'),_utf8mb4':',hex(cast(`dimension_key` as char charset binary))) when (`lock_kind` = _utf8mb4'ENTITY_SLOT') then concat(_utf8mb4'E:',lpad(hex(`candidate_id`),16,_utf8mb4'0'),_utf8mb4':',hex(cast(`slot_key` as char charset binary))) end)) STORED COMMENT '候选容量维度/实体槽唯一键，支持同一候选多维或多槽',
  `active_share_key` varchar(560) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS ((case when ((`status` = _utf8mb4'LOCKED') and (`lock_kind` = _utf8mb4'ENTITY_SLOT')) then concat(_utf8mb4'S:',lpad(hex(`storage_id`),16,_utf8mb4'0'),_utf8mb4':',hex(cast(`slot_key` as char charset binary))) when (`status` = _utf8mb4'LOCKED') then concat(_utf8mb4'C:',lpad(hex(`allocation_lock_id`),16,_utf8mb4'0')) else NULL end)) STORED COMMENT '活动份额/实体槽唯一键，终态为NULL',
  `terminal_effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '锁终态效果键',
  `consumption_trace_id` bigint DEFAULT NULL COMMENT '消费trace头ID',
  `terminal_release_id` bigint DEFAULT NULL COMMENT '终态释放流水ID',
  `terminal_at` datetime(3) DEFAULT NULL COMMENT '终态时间',
  `revision` int NOT NULL DEFAULT '1' COMMENT '分配锁修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`allocation_lock_id`),
  UNIQUE KEY `uk_wms_allocation_lock_effect` (`effect_key`),
  UNIQUE KEY `uk_wms_allocation_lock_candidate_share` (`candidate_share_key`),
  UNIQUE KEY `uk_wms_allocation_lock_terminal_effect` (`terminal_effect_key`),
  UNIQUE KEY `uk_wms_allocation_lock_terminal_release` (`terminal_release_id`),
  UNIQUE KEY `uk_wms_allocation_lock_active_share` (`active_share_key`),
  KEY `idx_wms_allocation_lock_decision` (`decision_id`,`status`),
  KEY `idx_wms_allocation_lock_business` (`business_type`,`business_id`,`business_line_id`),
  KEY `idx_wms_allocation_lock_task` (`business_type`,`task_key`),
  KEY `idx_wms_allocation_lock_storage` (`storage_id`,`status`,`resource_domain`),
  KEY `idx_wms_allocation_lock_channel` (`channel_lock_id`),
  CONSTRAINT `ck_wms_allocation_lock_active_share` CHECK ((((`status` = _utf8mb4'LOCKED') and (`active_share_key` is not null) and (`terminal_effect_key` is null) and (`terminal_release_id` is null) and (`consumption_trace_id` is null) and (`terminal_at` is null) and (`effect_result` in (_utf8mb4'NONE',_utf8mb4'SEND_INTENT',_utf8mb4'ACCEPTED',_utf8mb4'UNKNOWN'))) or ((`status` = _utf8mb4'CONSUMED') and (`active_share_key` is null) and (`terminal_effect_key` is not null) and (`consumption_trace_id` is not null) and (`terminal_release_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4'CONSUMED')) or ((`status` = _utf8mb4'RELEASED') and (`active_share_key` is null) and (`terminal_effect_key` is not null) and (`terminal_release_id` is not null) and (`consumption_trace_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4'NO_EFFECT')))),
  CONSTRAINT `ck_wms_allocation_lock_domain` CHECK ((`resource_domain` in (_utf8mb4'STORAGE',_utf8mb4'CHANNEL'))),
  CONSTRAINT `ck_wms_allocation_lock_domain_ref` CHECK ((((`resource_domain` = _utf8mb4'CHANNEL') and (`channel_lock_id` is not null)) or ((`resource_domain` = _utf8mb4'STORAGE') and (`channel_lock_id` is null)))),
  CONSTRAINT `ck_wms_allocation_lock_effect` CHECK (regexp_like(`effect_key`,_utf8mb4'^WMS:[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_allocation_lock_evidence_revision` CHECK ((`evidence_revision` >= 0)),
  CONSTRAINT `ck_wms_allocation_lock_kind` CHECK ((`lock_kind` in (_utf8mb4'CAPACITY_SHARE',_utf8mb4'ENTITY_SLOT'))),
  CONSTRAINT `ck_wms_allocation_lock_phase` CHECK ((`phase` in (_utf8mb4'LOCK',_utf8mb4'RELEASE'))),
  CONSTRAINT `ck_wms_allocation_lock_result` CHECK ((`effect_result` in (_utf8mb4'NONE',_utf8mb4'SEND_INTENT',_utf8mb4'ACCEPTED',_utf8mb4'UNKNOWN',_utf8mb4'CONSUMED',_utf8mb4'NO_EFFECT'))),
  CONSTRAINT `ck_wms_allocation_lock_revision` CHECK ((`revision` >= 1)),
  CONSTRAINT `ck_wms_allocation_lock_round` CHECK ((`round_no` >= 1)),
  CONSTRAINT `ck_wms_allocation_lock_share_shape` CHECK ((((`lock_kind` = _utf8mb4'CAPACITY_SHARE') and (`dimension_key` is not null) and (`unit` is not null) and (`locked_qty` is not null) and (`locked_qty` > 0) and (`slot_key` is null)) or ((`lock_kind` = _utf8mb4'ENTITY_SLOT') and (`dimension_key` is null) and (`unit` is null) and (`locked_qty` is null) and (`slot_key` is not null)))),
  CONSTRAINT `ck_wms_allocation_lock_status` CHECK ((`status` in (_utf8mb4'LOCKED',_utf8mb4'CONSUMED',_utf8mb4'RELEASED'))),
  CONSTRAINT `ck_wms_allocation_lock_terminal_effect` CHECK (((`terminal_effect_key` is null) or regexp_like(`terminal_effect_key`,_utf8mb4'^WMS:[0-9a-f]{64}$',_utf8mb4'c')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS分配锁/实体槽锁';
CREATE TABLE `wms_inventory_reservation` (
  `reservation_id` bigint NOT NULL COMMENT '数量预留主键，服务显式分配',
  `inventory_id` bigint NOT NULL COMMENT '库存单元ID，逻辑关联 wms_inventory.inventory_id',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型稳定编码',
  `business_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '父业务类型名称快照',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID',
  `business_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '父业务号快照',
  `business_line_id` bigint DEFAULT NULL COMMENT '父业务明细ID',
  `allocation_unit_id` bigint NOT NULL COMMENT '不可拆分配单元ID',
  `round_no` int NOT NULL COMMENT '分配轮次',
  `phase` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '任务阶段：LOCK/RELEASE',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定任务键',
  `plan_instance_id` bigint NOT NULL COMMENT '计划实例ID',
  `reserved_qty` decimal(20,6) NOT NULL COMMENT '预留数量',
  `unit` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '预留单位',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '状态：ACTIVE/CONSUMED/RELEASED',
  `effect_result` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'NONE' COMMENT '外部效果：NONE/SEND_INTENT/ACCEPTED/UNKNOWN/CONSUMED/NO_EFFECT',
  `effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '预留效果键WMS:SHA-256',
  `evidence_revision` int NOT NULL DEFAULT '0' COMMENT '证据修订号',
  `expires_at` datetime(3) DEFAULT NULL COMMENT '预留到期时间，不自动释放依据',
  `active_marker` tinyint GENERATED ALWAYS AS (if((`status` = _utf8mb4'ACTIVE'),1,NULL)) STORED COMMENT '活动预留标志，终态为NULL',
  `terminal_effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '预留终态效果键',
  `consumption_trace_id` bigint DEFAULT NULL COMMENT '消费trace头ID',
  `terminal_release_id` bigint DEFAULT NULL COMMENT '终态释放流水ID',
  `terminal_at` datetime(3) DEFAULT NULL COMMENT '终态时间',
  `revision` int NOT NULL DEFAULT '1' COMMENT '预留修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`reservation_id`),
  UNIQUE KEY `uk_wms_inventory_reservation_effect` (`effect_key`),
  UNIQUE KEY `uk_wms_inventory_reservation_terminal_effect` (`terminal_effect_key`),
  UNIQUE KEY `uk_wms_inventory_reservation_terminal_release` (`terminal_release_id`),
  UNIQUE KEY `uk_wms_inventory_reservation_active_task` (`business_type`,`task_key`,`inventory_id`,`active_marker`),
  KEY `idx_wms_inventory_reservation_inventory` (`inventory_id`,`status`),
  KEY `idx_wms_inventory_reservation_business` (`business_type`,`business_id`,`business_line_id`),
  CONSTRAINT `ck_wms_inventory_reservation_active` CHECK ((((`status` = _utf8mb4'ACTIVE') and (`active_marker` is not null) and (`terminal_effect_key` is null) and (`terminal_release_id` is null) and (`consumption_trace_id` is null) and (`terminal_at` is null) and (`effect_result` in (_utf8mb4'NONE',_utf8mb4'SEND_INTENT',_utf8mb4'ACCEPTED',_utf8mb4'UNKNOWN'))) or ((`status` = _utf8mb4'CONSUMED') and (`active_marker` is null) and (`terminal_effect_key` is not null) and (`consumption_trace_id` is not null) and (`terminal_release_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4'CONSUMED')) or ((`status` = _utf8mb4'RELEASED') and (`active_marker` is null) and (`terminal_effect_key` is not null) and (`terminal_release_id` is not null) and (`consumption_trace_id` is null) and (`terminal_at` is not null) and (`effect_result` = _utf8mb4'NO_EFFECT')))),
  CONSTRAINT `ck_wms_inventory_reservation_effect` CHECK (regexp_like(`effect_key`,_utf8mb4'^WMS:[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_inventory_reservation_evidence_revision` CHECK ((`evidence_revision` >= 0)),
  CONSTRAINT `ck_wms_inventory_reservation_phase` CHECK ((`phase` in (_utf8mb4'LOCK',_utf8mb4'RELEASE'))),
  CONSTRAINT `ck_wms_inventory_reservation_qty` CHECK ((`reserved_qty` > 0)),
  CONSTRAINT `ck_wms_inventory_reservation_result` CHECK ((`effect_result` in (_utf8mb4'NONE',_utf8mb4'SEND_INTENT',_utf8mb4'ACCEPTED',_utf8mb4'UNKNOWN',_utf8mb4'CONSUMED',_utf8mb4'NO_EFFECT'))),
  CONSTRAINT `ck_wms_inventory_reservation_revision` CHECK ((`revision` >= 1)),
  CONSTRAINT `ck_wms_inventory_reservation_round` CHECK ((`round_no` >= 1)),
  CONSTRAINT `ck_wms_inventory_reservation_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'CONSUMED',_utf8mb4'RELEASED'))),
  CONSTRAINT `ck_wms_inventory_reservation_terminal_effect` CHECK (((`terminal_effect_key` is null) or regexp_like(`terminal_effect_key`,_utf8mb4'^WMS:[0-9a-f]{64}$',_utf8mb4'c')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS数量预留账';
CREATE TABLE `wms_allocation_release` (
  `release_id` bigint NOT NULL COMMENT '释放流水主键，服务显式分配',
  `allocation_lock_id` bigint NOT NULL COMMENT '被释放分配锁ID，逻辑关联 wms_allocation_lock.allocation_lock_id',
  `channel_lock_id` bigint DEFAULT NULL COMMENT '关联通道锁ID',
  `business_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '父业务类型稳定编码',
  `business_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '父业务类型名称快照',
  `business_id` bigint NOT NULL COMMENT '父业务内部ID',
  `business_no` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '父业务号快照',
  `business_line_id` bigint DEFAULT NULL COMMENT '父业务明细ID',
  `allocation_unit_id` bigint NOT NULL COMMENT '不可拆分配单元ID',
  `round_no` int NOT NULL COMMENT '分配轮次',
  `phase` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '释放阶段：RELEASE',
  `task_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定任务键',
  `plan_instance_id` bigint NOT NULL COMMENT '计划实例ID',
  `evidence_revision` int NOT NULL COMMENT '无效果证据修订号',
  `evidence_ref` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '脱敏证据引用',
  `effect_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '释放效果键WMS:SHA-256',
  `before_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '释放前锁状态',
  `after_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '释放后锁状态',
  `release_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '释放原因',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者ID',
  `occurred_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '释放发生时间',
  `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '释放事实记录时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者，释放事实由服务禁止更新',
  `update_time` datetime(3) DEFAULT NULL COMMENT '更新时间，释放事实由服务禁止更新',
  PRIMARY KEY (`release_id`),
  UNIQUE KEY `uk_wms_allocation_release_effect` (`effect_key`),
  UNIQUE KEY `uk_wms_allocation_release_lock` (`allocation_lock_id`),
  KEY `idx_wms_allocation_release_channel` (`channel_lock_id`),
  KEY `idx_wms_allocation_release_business` (`business_type`,`business_id`,`business_line_id`),
  KEY `idx_wms_allocation_release_task` (`business_type`,`task_key`),
  KEY `idx_wms_allocation_release_time` (`occurred_at`,`release_id`),
  CONSTRAINT `ck_wms_allocation_release_after_status` CHECK ((`after_status` = _utf8mb4'RELEASED')),
  CONSTRAINT `ck_wms_allocation_release_before_status` CHECK ((`before_status` = _utf8mb4'LOCKED')),
  CONSTRAINT `ck_wms_allocation_release_effect` CHECK (regexp_like(`effect_key`,_utf8mb4'^WMS:[0-9a-f]{64}$',_utf8mb4'c')),
  CONSTRAINT `ck_wms_allocation_release_evidence_revision` CHECK ((`evidence_revision` >= 0)),
  CONSTRAINT `ck_wms_allocation_release_phase` CHECK ((`phase` = _utf8mb4'RELEASE')),
  CONSTRAINT `ck_wms_allocation_release_round` CHECK ((`round_no` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS分配锁释放流水'
;

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
-- 14. wms_company_management_scope.company_id -> wms_company.company_id
--    每个公司恰一行，scope_revision 与公司 revision 独立做 CRUD/授权 CAS。
-- 15. wms_company_management_grant.company_id/role_id -> wms_company/sys_role
--    只授权公司管理对象范围；按完整目标集合替换，空集合只删不插。
-- 16. wms_object_audit 只追加成功对象事实；wms_command_attempt 以 command_key 幂等，
--    失败尝试独立留存，不用审计行伪造成功历史。
-- 17. wms_warehouse.company_id -> wms_company.company_id；
--    wms_warehouse_zone.warehouse_id / wms_storage.warehouse_id -> wms_warehouse.warehouse_id。
-- 18. wms_business_plan_mount.business_type_code -> wms_business_type.business_type_code；
--    plan_type_code/schedule_code 只读解析计划事件调度表，不建物理外键。
-- 19. wms_upgrade_run.run_id -> wms_upgrade_checkpoint.run_id / wms_upgrade_issue.run_id
--    逻辑关联；检查点与问题清单不建物理外键。
-- 20. wms_schema_version 只登记结构与发布版本；初始化不写运行、检查点、映射或问题行。
-- 21. wms_legacy_id_map 以 domain/source_table/source_key 与 target_type/target_id 双唯一键映射旧标识。
-- 22. wms_rule_definition -> wms_rule_version -> wms_rule_package_member
--    -> wms_rule_package_version -> wms_rule_effective_pointer 使用逻辑关联；
--    规则内容版本与规则包版本保持不可变，生效指针保存当前规则包版本。
-- 23. wms_logical_scope -> wms_logical_scope_version -> wms_logical_scope_storage；
--    wms_material_logical_assignment 保存当前归属，wms_logical_assignment_history 保存变更事实。
-- ============================================================
