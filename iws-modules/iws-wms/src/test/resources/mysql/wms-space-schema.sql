-- 空间只读查询隔离测试库；列名按权威 P1 使用 type_config_version_id。
CREATE TABLE IF NOT EXISTS wms_company (
    company_id       bigint       NOT NULL,
    company_code     varchar(64)  COLLATE utf8mb4_bin NOT NULL,
    company_name     varchar(100) NOT NULL,
    enabled          tinyint      NOT NULL DEFAULT 0,
    lifecycle_status varchar(16)  COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE',
    revision         int          NOT NULL DEFAULT 1,
    remark           varchar(500) DEFAULT NULL,
    create_dept      bigint       DEFAULT NULL,
    create_by        bigint       DEFAULT NULL,
    create_time      datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_by        bigint       DEFAULT NULL,
    update_time      datetime(3)  DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (company_id),
    UNIQUE KEY uk_wms_company_code (company_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS wms_company_management_grant (
    grant_id   bigint      NOT NULL,
    company_id bigint      NOT NULL,
    role_id    bigint      NOT NULL,
    created_by bigint      DEFAULT NULL,
    created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (grant_id),
    UNIQUE KEY uk_wms_company_grant (company_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS wms_warehouse (
    warehouse_id                bigint       NOT NULL,
    company_id                  bigint       NOT NULL,
    warehouse_code              varchar(64)  COLLATE utf8mb4_bin NOT NULL,
    warehouse_name              varchar(100) NOT NULL,
    warehouse_type_code         varchar(32)  COLLATE utf8mb4_bin NOT NULL,
    type_config_version_id      bigint       DEFAULT NULL,
    lifecycle_status            varchar(16)  COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE',
    enabled                     tinyint      NOT NULL DEFAULT 0,
    revision                    int          NOT NULL DEFAULT 1,
    remark                      varchar(500) DEFAULT NULL,
    create_dept                 bigint       DEFAULT NULL,
    create_by                   bigint       DEFAULT NULL,
    create_time                 datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_by                   bigint       DEFAULT NULL,
    update_time                 datetime(3)  DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (warehouse_id),
    UNIQUE KEY uk_wms_warehouse_company_code (company_id, warehouse_code),
    KEY idx_wms_warehouse_company_status (company_id, lifecycle_status, warehouse_type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS wms_warehouse_zone (
    zone_id        bigint       NOT NULL,
    zone_code      varchar(50)  NOT NULL,
    zone_name      varchar(100) NOT NULL,
    zone_type      tinyint      NOT NULL DEFAULT 1,
    zone_kind      varchar(16)  COLLATE utf8mb4_0900_bin NOT NULL,
    lifecycle_status varchar(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE',
    plant_code     varchar(50)  DEFAULT NULL,
    warehouse_code varchar(50)  DEFAULT NULL,
    parent_zone_id bigint       DEFAULT NULL,
    allow_inbound  tinyint      NOT NULL DEFAULT 1,
    allow_outbound tinyint      NOT NULL DEFAULT 1,
    status         tinyint      NOT NULL DEFAULT 0,
    revision       int          NOT NULL DEFAULT 1,
    sort_no        int          NOT NULL DEFAULT 0,
    del_flag       char(1)      NOT NULL DEFAULT '0',
    create_dept    bigint       DEFAULT NULL,
    create_by      bigint       DEFAULT NULL,
    create_time    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by      bigint       DEFAULT NULL,
    update_time    datetime     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    remark         varchar(500) DEFAULT NULL,
    warehouse_id   bigint       NOT NULL,
    PRIMARY KEY (zone_id),
    UNIQUE KEY uk_wms_zone_code (zone_code),
    KEY idx_wms_zone_parent (parent_zone_id),
    KEY idx_wms_zone_warehouse_status (warehouse_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS wms_storage (
    storage_id     bigint        NOT NULL,
    storage_code   varchar(64)   NOT NULL,
    zone_id        bigint        NOT NULL,
    storage_type   tinyint       NOT NULL DEFAULT 1,
    height_type    tinyint       DEFAULT NULL,
    height_desc    varchar(50)   DEFAULT NULL,
    row_no         int           DEFAULT NULL,
    col_no         int           DEFAULT NULL,
    level_no       int           DEFAULT NULL,
    x_coordinate   decimal(12,3) DEFAULT NULL,
    y_coordinate   decimal(12,3) DEFAULT NULL,
    port_code      varchar(64)   DEFAULT NULL,
    wcs_point_code varchar(64)   DEFAULT NULL,
    status         tinyint       NOT NULL DEFAULT 0,
    lock_status    tinyint       NOT NULL DEFAULT 0,
    disable_status tinyint       NOT NULL DEFAULT 1,
    lifecycle_status varchar(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE',
    version        int           NOT NULL DEFAULT 0,
    del_flag       char(1)       NOT NULL DEFAULT '0',
    create_dept    bigint        DEFAULT NULL,
    create_by      bigint        DEFAULT NULL,
    create_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by      bigint        DEFAULT NULL,
    update_time    datetime      DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    remark         varchar(500)  DEFAULT NULL,
    warehouse_id   bigint        NOT NULL,
    aisle_text     varchar(64)   COLLATE utf8mb4_0900_bin DEFAULT NULL,
    row_text       varchar(64)   COLLATE utf8mb4_0900_bin DEFAULT NULL,
    level_text     varchar(64)   COLLATE utf8mb4_0900_bin DEFAULT NULL,
    column_text    varchar(64)   COLLATE utf8mb4_0900_bin DEFAULT NULL,
    depth_text     varchar(64)   COLLATE utf8mb4_0900_bin DEFAULT NULL,
    aisle_text_is_null tinyint GENERATED ALWAYS AS (aisle_text IS NULL) STORED NOT NULL,
    row_text_is_null tinyint GENERATED ALWAYS AS (row_text IS NULL) STORED NOT NULL,
    level_text_is_null tinyint GENERATED ALWAYS AS (level_text IS NULL) STORED NOT NULL,
    column_text_is_null tinyint GENERATED ALWAYS AS (column_text IS NULL) STORED NOT NULL,
    depth_text_is_null tinyint GENERATED ALWAYS AS (depth_text IS NULL) STORED NOT NULL,
    aisle_text_cmp varchar(64) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(aisle_text,'')) STORED NOT NULL,
    row_text_cmp varchar(64) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(row_text,'')) STORED NOT NULL,
    level_text_cmp varchar(64) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(level_text,'')) STORED NOT NULL,
    column_text_cmp varchar(64) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(column_text,'')) STORED NOT NULL,
    depth_text_cmp varchar(64) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(depth_text,'')) STORED NOT NULL,
    PRIMARY KEY (storage_id),
    UNIQUE KEY uk_wms_storage_code (storage_code),
    UNIQUE KEY uk_wms_storage_warehouse_address (warehouse_id, aisle_text_is_null, row_text_is_null, level_text_is_null, column_text_is_null, depth_text_is_null, aisle_text_cmp, row_text_cmp, level_text_cmp, column_text_cmp, depth_text_cmp),
    KEY idx_wms_storage_zone_status (zone_id, storage_type, status, lock_status, disable_status),
    KEY idx_wms_storage_warehouse_status (warehouse_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_role (
    role_id   bigint      NOT NULL,
    role_name varchar(64) NOT NULL,
    role_key  varchar(64) DEFAULT NULL,
    status    char(1)     NOT NULL DEFAULT '0',
    del_flag  char(1)     NOT NULL DEFAULT '0',
    PRIMARY KEY (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_menu (
    menu_id   bigint       NOT NULL,
    menu_name varchar(50)  NOT NULL DEFAULT '',
    perms     varchar(100) DEFAULT NULL,
    status    char(1)      NOT NULL DEFAULT '0',
    PRIMARY KEY (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id bigint NOT NULL,
    menu_id bigint NOT NULL,
    PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
