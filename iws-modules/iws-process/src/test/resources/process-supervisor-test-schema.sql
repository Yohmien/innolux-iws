-- 监督隔离测试补充结构；只用于 iws_process_supervisor_test。
DROP TABLE IF EXISTS iws_business_schedule_plan_type;
DROP TABLE IF EXISTS iws_business_schedule;

CREATE TABLE iws_business_schedule (
    id bigint NOT NULL PRIMARY KEY,
    business_code varchar(64) NOT NULL,
    business_name varchar(128) NOT NULL,
    enabled tinyint(1) NOT NULL DEFAULT 1,
    fairness_window_size int NULL,
    revision bigint NOT NULL DEFAULT 0,
    remark varchar(500) NULL,
    del_flag char(1) NOT NULL DEFAULT '0',
    create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NULL,
    update_by bigint NULL, update_time datetime(3) NULL,
    UNIQUE KEY uk_test_schedule_code (business_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_business_schedule_plan_type (
    id bigint NOT NULL PRIMARY KEY,
    schedule_id bigint NOT NULL,
    plan_type varchar(64) NOT NULL,
    plan_type_name varchar(128) NULL,
    priority int NOT NULL DEFAULT 0,
    enabled tinyint(1) NOT NULL DEFAULT 1,
    create_dept bigint NULL, create_by bigint NULL, create_time datetime(3) NULL,
    update_by bigint NULL, update_time datetime(3) NULL,
    UNIQUE KEY uk_test_schedule_type (schedule_id, plan_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 与生产 DDL 对齐：control_state 无默认值且 NOT NULL，测试不得靠 nullable 规避漏字段。
ALTER TABLE iws_process_schedule_control MODIFY control_state varchar(24) NOT NULL;
