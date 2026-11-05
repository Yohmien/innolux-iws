-- 隔离集成测试使用的 MySQL 结构，对齐 script/sql/iws_inventory_management_upgrade.sql
-- 中 WMS 公司最小闭环的五张表，保留主键、业务唯一键、默认值和 InnoDB/utf8mb4 语义。
CREATE TABLE IF NOT EXISTS wms_company (
    company_id       bigint       NOT NULL COMMENT '公司主键，系统生成',
    company_code     varchar(64)  COLLATE utf8mb4_bin NOT NULL COMMENT '公司编码，业务唯一，创建后不可修改',
    company_name     varchar(100) NOT NULL COMMENT '公司名称',
    enabled          tinyint      NOT NULL DEFAULT 0 COMMENT '是否可被后续业务选择：0否，1是；与生命周期分离',
    lifecycle_status varchar(16)  COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '生命周期：ACTIVE/RETIRING/RETIRED',
    revision         int          NOT NULL DEFAULT 1 COMMENT '公司乐观锁版本，创建为1',
    remark           varchar(500) DEFAULT NULL COMMENT '备注',
    create_dept      bigint       DEFAULT NULL COMMENT '创建部门',
    create_by        bigint       DEFAULT NULL COMMENT '创建者',
    create_time      datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_by        bigint       DEFAULT NULL COMMENT '更新者',
    update_time      datetime(3)  DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (company_id),
    UNIQUE KEY uk_wms_company_code (company_code),
    KEY idx_wms_company_lifecycle (lifecycle_status, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS公司主档';

CREATE TABLE IF NOT EXISTS wms_company_management_scope (
    company_id     bigint      NOT NULL COMMENT '公司主键，逻辑关联 wms_company.company_id；每公司恰一行',
    scope_revision int         NOT NULL DEFAULT 0 COMMENT '管理范围独立乐观锁版本，创建为0',
    updated_by     bigint      DEFAULT NULL COMMENT '最后更新人',
    updated_at     datetime(3) DEFAULT NULL COMMENT '最后更新时间',
    PRIMARY KEY (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS公司管理范围头';

CREATE TABLE IF NOT EXISTS wms_company_management_grant (
    grant_id   bigint      NOT NULL COMMENT '授权明细主键，系统生成',
    company_id bigint      NOT NULL COMMENT '公司主键，逻辑关联 wms_company.company_id',
    role_id    bigint      NOT NULL COMMENT '角色主键，逻辑关联 sys_role.role_id',
    created_by bigint      DEFAULT NULL COMMENT '创建者',
    created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (grant_id),
    UNIQUE KEY uk_wms_company_grant (company_id, role_id),
    KEY idx_wms_company_grant_role (role_id, company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS公司管理角色授权明细';

CREATE TABLE IF NOT EXISTS wms_object_audit (
    audit_id          bigint        NOT NULL COMMENT '审计主键，系统生成',
    object_type       varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT '对象类型，如 WMS_COMPANY',
    object_id         bigint        NOT NULL COMMENT '对象内部ID',
    action            varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT '动作',
    command_key       varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT '命令键，用于幂等与追责',
    before_summary    varchar(2000) DEFAULT NULL COMMENT '变更前摘要',
    after_summary     varchar(2000) DEFAULT NULL COMMENT '变更后摘要',
    actor_user_id     bigint        DEFAULT NULL COMMENT '当前操作者',
    actor_name        varchar(64)   DEFAULT NULL COMMENT '操作者名称快照',
    initiator_type    varchar(32)   COLLATE utf8mb4_bin DEFAULT NULL COMMENT '发起人类型',
    initiator_id      varchar(128)  DEFAULT NULL COMMENT '发起人标识',
    initiator_name    varchar(64)   DEFAULT NULL COMMENT '发起人名称快照',
    dept_id           bigint        DEFAULT NULL COMMENT '操作者部门快照',
    dept_name         varchar(128)  DEFAULT NULL COMMENT '操作者部门名称快照',
    execution_subject varchar(64)   DEFAULT NULL COMMENT '执行主体名称快照',
    execution_subject_type varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '执行主体类型',
    execution_account varchar(64)   DEFAULT NULL COMMENT '执行账号快照',
    result            varchar(16)   COLLATE utf8mb4_bin NOT NULL COMMENT '结果',
    evidence_json     varchar(2000) DEFAULT NULL COMMENT '证据引用',
    occurred_at       datetime(3)   NOT NULL COMMENT '业务发生时间',
    recorded_at       datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审计记录时间',
    PRIMARY KEY (audit_id),
    KEY idx_wms_object_audit_object (object_type, object_id, occurred_at),
    KEY idx_wms_object_audit_command_key (command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS对象变更审计，只追加';

CREATE TABLE IF NOT EXISTS wms_command_attempt (
    attempt_id        bigint        NOT NULL COMMENT '尝试主键，系统生成',
    command_key       varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT '命令键',
    action            varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT '命令动作',
    object_type       varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT '对象类型',
    object_id         bigint        DEFAULT NULL COMMENT '对象内部ID，创建前可空',
    input_digest      char(64)      NOT NULL COMMENT '规范化请求摘要',
    input_summary     varchar(2000) DEFAULT NULL COMMENT '脱敏请求摘要',
    status            varchar(16)   COLLATE utf8mb4_bin NOT NULL COMMENT '状态',
    error_code        varchar(64)   COLLATE utf8mb4_bin DEFAULT NULL COMMENT '错误码',
    error_message     varchar(500)  DEFAULT NULL COMMENT '脱敏错误消息',
    fact_type         varchar(64)   COLLATE utf8mb4_bin DEFAULT NULL COMMENT '成功事实类型',
    fact_id           varchar(128)  DEFAULT NULL COMMENT '成功事实标识',
    actor_user_id     bigint        DEFAULT NULL COMMENT '操作者',
    actor_name        varchar(64)   DEFAULT NULL COMMENT '操作者名称快照',
    dept_id           bigint        DEFAULT NULL COMMENT '操作者部门快照',
    dept_name         varchar(128)  DEFAULT NULL COMMENT '操作者部门名称快照',
    initiator_name    varchar(64)   DEFAULT NULL COMMENT '发起人名称快照',
    execution_subject varchar(64)   DEFAULT NULL COMMENT '执行主体名称快照',
    execution_subject_type varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '执行主体类型',
    execution_account varchar(64)   DEFAULT NULL COMMENT '执行账号快照',
    occurred_at       datetime(3)   NOT NULL COMMENT '命令发生时间',
    recorded_at       datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
    PRIMARY KEY (attempt_id),
    UNIQUE KEY uk_wms_command_attempt_command (command_key),
    KEY idx_wms_command_attempt_object (object_type, object_id, occurred_at),
    KEY idx_wms_command_attempt_status (status, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS非计划受控命令尝试';

-- 公司授权复核会实时读取系统角色关系；测试库只保留验证所需的稳定列。
CREATE TABLE IF NOT EXISTS sys_role (
    role_id   bigint      NOT NULL COMMENT '角色主键',
    role_name varchar(64) NOT NULL COMMENT '角色名称',
    role_key  varchar(64) DEFAULT NULL COMMENT '角色权限字符串',
    status    char(1)     NOT NULL DEFAULT '0' COMMENT '状态：0正常',
    del_flag  char(1)     NOT NULL DEFAULT '0' COMMENT '删除标记：0存在',
    PRIMARY KEY (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统角色';

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id bigint NOT NULL COMMENT '用户主键',
    role_id bigint NOT NULL COMMENT '角色主键',
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关系';

-- scope 权限按实时有效角色菜单核验，测试库只保留验证所需的稳定列。
CREATE TABLE IF NOT EXISTS sys_menu (
    menu_id   bigint       NOT NULL COMMENT '菜单主键',
    menu_name varchar(50)  NOT NULL DEFAULT '' COMMENT '菜单名称',
    perms     varchar(100) DEFAULT NULL COMMENT '权限标识',
    status    char(1)      NOT NULL DEFAULT '0' COMMENT '状态：0正常，1停用',
    PRIMARY KEY (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单权限';

CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id bigint NOT NULL COMMENT '角色主键',
    menu_id bigint NOT NULL COMMENT '菜单主键',
    PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色菜单关系';
