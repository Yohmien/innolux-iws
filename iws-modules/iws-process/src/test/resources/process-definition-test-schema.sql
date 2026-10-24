-- 计划定义隔离测试结构；只用于 iws_process_definition_test 库。
DROP TABLE IF EXISTS iws_process_action_audit;
DROP TABLE IF EXISTS iws_process_instance;
DROP TABLE IF EXISTS iws_process_edge;
DROP TABLE IF EXISTS iws_process_event_definition;
DROP TABLE IF EXISTS iws_process_state;
DROP TABLE IF EXISTS iws_process_system_policy;
DROP TABLE IF EXISTS iws_process_definition;
DROP TABLE IF EXISTS iws_endpoint_system_address;
DROP TABLE IF EXISTS iws_endpoint_address;
DROP TABLE IF EXISTS iws_endpoint_environment;
DROP TABLE IF EXISTS iws_endpoint_system;
DROP TABLE IF EXISTS iws_endpoint_catalog_control;

CREATE TABLE iws_endpoint_catalog_control (
    singleton_key varchar(32) NOT NULL PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 0,
    update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_endpoint_system (
    system_code varchar(64) NOT NULL PRIMARY KEY,
    display_name varchar(128) NOT NULL,
    enabled tinyint(1) NOT NULL DEFAULT 1,
    revision bigint NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_endpoint_environment (
    environment_code varchar(64) NOT NULL PRIMARY KEY,
    display_name varchar(128) NOT NULL,
    enabled tinyint(1) NOT NULL DEFAULT 1,
    revision bigint NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_endpoint_address (
    id bigint NOT NULL PRIMARY KEY,
    environment_code varchar(64) NOT NULL,
    endpoint_ref varchar(128) NOT NULL,
    protocol varchar(16) NOT NULL,
    host varchar(255) NOT NULL,
    port int NULL,
    base_path varchar(255) NULL,
    enabled tinyint(1) NOT NULL DEFAULT 1,
    revision bigint NOT NULL DEFAULT 0,
    credential_ref varchar(128) NULL,
    UNIQUE KEY uk_test_address (environment_code, endpoint_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_endpoint_system_address (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    system_code varchar(64) NOT NULL,
    address_id bigint NOT NULL,
    enabled tinyint(1) NOT NULL DEFAULT 1,
    revision bigint NOT NULL DEFAULT 0,
    UNIQUE KEY uk_test_system_address (system_code, address_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_process_definition (
    id bigint NOT NULL PRIMARY KEY,
    plan_code varchar(64) NOT NULL,
    plan_version int NOT NULL,
    plan_type varchar(64) NULL,
    business_type varchar(64) NULL,
    business_schedule_code varchar(64) NULL,
    plan_name varchar(128) NOT NULL,
    plan_role varchar(16) NULL,
    status varchar(16) NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    latest_key varchar(64) NULL,
    active_key varchar(64) NULL,
    self_system_code varchar(64) NULL,
    mode varchar(16) NULL,
    active_plan_limit int NULL,
    fairness_window_size int NULL,
    max_execution_ms bigint NULL,
    completion_policy varchar(24) NOT NULL,
    exception_state_code varchar(64) NULL,
    round_batch_limit int NULL,
    round_max_duration_ms bigint NULL,
    schedule_interval_ms bigint NULL,
    schedule_cron varchar(64) NULL,
    job_timeout_ms bigint NULL,
    job_max_retry_times int NULL,
    worker_batch_limit int NULL,
    worker_max_duration_ms bigint NULL,
    lease_ms bigint NULL,
    retry_max int NULL,
    retry_backoff_ms bigint NULL,
    published_by bigint NULL,
    published_at datetime(3) NULL,
    published_summary varchar(500) NULL,
    description varchar(1000) NULL,
    del_flag char(1) NOT NULL DEFAULT '0',
    create_dept bigint NULL,
    create_by bigint NULL,
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_by bigint NULL,
    update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_test_definition_code_version (plan_code, plan_version),
    UNIQUE KEY uk_test_definition_latest (latest_key),
    UNIQUE KEY uk_test_definition_active (active_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_process_state (
    id bigint NOT NULL PRIMARY KEY,
    definition_version_id bigint NOT NULL,
    state_code varchar(64) NOT NULL,
    display_name varchar(128) NULL,
    state_kind varchar(16) NULL,
    validator_key varchar(128) NULL,
    validator_version varchar(32) NULL,
    condition_description varchar(1000) NULL,
    terminal tinyint(1) NOT NULL DEFAULT 0,
    sort_order int NOT NULL DEFAULT 0,
    create_dept bigint NULL,
    create_by bigint NULL,
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_by bigint NULL,
    update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_test_state (definition_version_id, state_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_process_event_definition (
    id bigint NOT NULL PRIMARY KEY,
    definition_version_id bigint NOT NULL,
    event_code varchar(64) NOT NULL,
    display_name varchar(128) NULL,
    event_role varchar(16) NULL,
    event_direction varchar(16) NULL,
    event_type_key varchar(128) NULL,
    event_type_version varchar(32) NULL,
    call_contract_key varchar(128) NULL,
    call_contract_version varchar(32) NULL,
    operation_kind varchar(32) NULL,
    local_action_key varchar(128) NULL,
    local_action_version varchar(32) NULL,
    effect_key varchar(128) NULL,
    local_effect_key varchar(128) NULL,
    system_code varchar(64) NULL,
    target_system_code varchar(64) NULL,
    endpoint_ref varchar(128) NULL,
    purpose varchar(32) NULL,
    completion_contract varchar(32) NULL,
    source_state_code varchar(64) NULL,
    target_state_code varchar(64) NULL,
    enabled tinyint(1) NOT NULL DEFAULT 1,
    skip_policy varchar(32) NULL,
    skip_contract_key varchar(128) NULL,
    recovery_for_event_code varchar(64) NULL,
    priority int NOT NULL DEFAULT 0,
    edit_policy varchar(24) NULL,
    graph_edit_policy varchar(24) NULL,
    locked_fields varchar(500) NULL,
    remark varchar(500) NULL,
    create_dept bigint NULL,
    create_by bigint NULL,
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_by bigint NULL,
    update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_test_event (definition_version_id, event_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_process_edge (
    id bigint NOT NULL PRIMARY KEY,
    definition_version_id bigint NOT NULL,
    predecessor_event_code varchar(64) NOT NULL,
    successor_event_code varchar(64) NOT NULL,
    branch_key varchar(64) NOT NULL,
    branch_kind varchar(16) NULL,
    condition_key varchar(128) NULL,
    condition_version varchar(32) NULL,
    default_branch tinyint(1) NULL,
    join_code varchar(64) NULL,
    priority int NOT NULL DEFAULT 0,
    create_dept bigint NULL,
    create_by bigint NULL,
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_by bigint NULL,
    update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_test_edge (definition_version_id, predecessor_event_code, successor_event_code, branch_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_process_system_policy (
    id bigint NOT NULL PRIMARY KEY,
    definition_version_id bigint NOT NULL,
    system_code varchar(64) NOT NULL,
    posting_mode varchar(24) NULL,
    first_confirm_policy varchar(32) NULL,
    failure_policy varchar(32) NULL,
    remark varchar(500) NULL,
    create_dept bigint NULL,
    create_by bigint NULL,
    create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_by bigint NULL,
    update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_test_policy (definition_version_id, system_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_process_instance (
    id bigint NOT NULL PRIMARY KEY,
    definition_version_id bigint NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE iws_process_action_audit (
    id bigint NOT NULL PRIMARY KEY,
    action_code varchar(64) NOT NULL,
    action_source varchar(24) NOT NULL,
    command_key varchar(128) NULL,
    receipt_key varchar(256) NULL,
    actor_user_id bigint NULL,
    actor_name varchar(128) NULL,
    trace_id varchar(128) NULL,
    schedule_id bigint NULL,
    definition_version_id bigint NULL,
    instance_id bigint NULL,
    token_id bigint NULL,
    event_id bigint NULL,
    attempt_id bigint NULL,
    supervisor_instance_id bigint NULL,
    round_no bigint NULL,
    dispatch_id varchar(128) NULL,
    child_instance_id bigint NULL,
    request_id varchar(64) NULL,
    before_status varchar(64) NULL,
    before_version bigint NULL,
    after_status varchar(64) NULL,
    after_version bigint NULL,
    result varchar(16) NOT NULL,
    reason varchar(1000) NULL,
    payload_summary varchar(2000) NULL,
    decision_summary varchar(2000) NULL,
    slot_before int NULL,
    slot_after int NULL,
    epoch bigint NULL,
    control_revision bigint NULL,
    platform_job_id varchar(128) NULL,
    job_batch_id varchar(128) NULL,
    occurred_at datetime(3) NOT NULL,
    duration_ms bigint NULL,
    request_started_at datetime(3) NULL,
    request_finished_at datetime(3) NULL,
    client_ip varchar(64) NULL,
    user_agent varchar(500) NULL,
    entry_path varchar(500) NULL,
    UNIQUE KEY uk_test_audit_receipt (receipt_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO iws_endpoint_catalog_control (singleton_key, revision) VALUES ('CATALOG', 0);
INSERT INTO iws_endpoint_environment (environment_code, display_name, enabled, revision)
VALUES ('TEST', '隔离测试环境', 1, 0);
INSERT INTO iws_endpoint_system (system_code, display_name, enabled, revision) VALUES
('IWS', '本系统', 1, 0), ('MID_WMS', '中间仓', 1, 0), ('MES', '制造执行', 1, 0), ('SAP', '企业资源', 1, 0);
INSERT INTO iws_endpoint_address (id, environment_code, endpoint_ref, protocol, host, port, base_path, enabled, revision)
VALUES (1001, 'TEST', 'mid', 'http', '127.0.0.1', 18081, '/mid', 1, 0),
       (1002, 'TEST', 'mes', 'http', '127.0.0.1', 18082, '/mes', 1, 0),
       (1003, 'TEST', 'sap', 'http', '127.0.0.1', 18083, '/sap', 1, 0);
INSERT INTO iws_endpoint_system_address (system_code, address_id, enabled, revision) VALUES
('MID_WMS', 1001, 1, 0), ('MES', 1002, 1, 0), ('SAP', 1003, 1, 0);
