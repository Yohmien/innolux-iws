/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件 process 运行表升级；目标 MySQL 8.0，可重复执行并进行完整结构核验；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数见下一行。
-- 执行：先执行 iws_plan_event_endpoint_schema.sql；本脚本要求 endpoint 6 张目录表已存在。
-- 安全：所有持久化写入均受目标、endpoint 依赖、预检与后置核验门控；客户端即使续错也不会继续落写。
-- 说明：不写业务计划、实例、事件、调度控制或真实任务配置，仅创建结构。

SET @upgrade_schema = 'innolux_iws';
SET @upgrade_schema_q = CONCAT('`', REPLACE(@upgrade_schema, '`', '``'), '`');

SET @pp_target_ok = (SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY));
SET @pp_database_matches = (DATABASE() IS NOT NULL AND CAST(DATABASE() AS BINARY) = CAST(@upgrade_schema AS BINARY));
SET @pp_version_ok = (VERSION() LIKE '8.0.%');
SET @pp_schema_collation_ok = (SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY) AND DEFAULT_COLLATION_NAME = 'utf8mb4_general_ci');
SET @pp_table_case_setting_ok = (@@lower_case_table_names IN (0, 1, 2));
SET @pp_endpoint_ready = (SELECT COUNT(*) = 6 FROM information_schema.TABLES WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY) AND TABLE_NAME IN ('iws_endpoint_system','iws_endpoint_environment','iws_endpoint_address','iws_endpoint_system_address','iws_endpoint_catalog_control','iws_endpoint_audit'));
SET @pp_precheck_ok = (@pp_target_ok = 1 AND @pp_database_matches = 1 AND @pp_version_ok = 1 AND @pp_schema_collation_ok = 1 AND @pp_table_case_setting_ok = 1 AND @pp_endpoint_ready = 1);

SELECT
    @pp_target_ok AS `目标库存在`,
    @pp_database_matches AS `当前连接库一致`,
    @pp_version_ok AS `MySQL8版本`,
    @pp_schema_collation_ok AS `目标库排序规则`,
    @pp_table_case_setting_ok AS `表名大小写设置合法`,
    @@lower_case_table_names AS `lower_case_table_names`,
    @pp_endpoint_ready AS `endpoint目录表齐全`,
    IF(@pp_precheck_ok = 1, '前置校验通过', '前置校验失败，禁止写入') AS `执行说明`;

SELECT JSON_EXTRACT(IF(@pp_precheck_ok = 1, '{}', 'invalid'), '$') AS `前置校验`;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_pp_expected_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_pp_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_pp_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_pp_conflicts;

CREATE TEMPORARY TABLE tmp_iws_pp_expected_tables (table_name varchar(64) NOT NULL, PRIMARY KEY (table_name));
CREATE TEMPORARY TABLE tmp_iws_pp_expected_columns (
    table_name varchar(64) NOT NULL, ordinal_position int NOT NULL, column_name varchar(64) NOT NULL,
    column_type varchar(255) NOT NULL, is_nullable varchar(3) NOT NULL, column_default varchar(1024) DEFAULT NULL,
    extra varchar(64) NOT NULL, character_set_name varchar(64) DEFAULT NULL, collation_name varchar(64) DEFAULT NULL,
    PRIMARY KEY (table_name, ordinal_position), UNIQUE KEY uk_tmp_iws_pp_column (table_name, column_name)
);
CREATE TEMPORARY TABLE tmp_iws_pp_expected_indexes (
    table_name varchar(64) NOT NULL, index_name varchar(64) NOT NULL, non_unique int NOT NULL,
    seq_in_index int NOT NULL, column_name varchar(64) NOT NULL, sub_part int DEFAULT NULL, index_type varchar(16) NOT NULL,
    PRIMARY KEY (table_name, index_name, seq_in_index)
);
CREATE TEMPORARY TABLE tmp_iws_pp_conflicts (table_name varchar(64) NOT NULL, reason varchar(255) NOT NULL, KEY idx_tmp_iws_pp_conflict (table_name));

INSERT INTO tmp_iws_pp_expected_tables (table_name) VALUES
('iws_business_schedule'),
('iws_business_schedule_plan_type'),
('iws_process_definition'),
('iws_process_system_policy'),
('iws_process_state'),
('iws_process_event_definition'),
('iws_process_edge'),
('iws_process_instance'),
('iws_process_system_context'),
('iws_process_token'),
('iws_process_event'),
('iws_process_attempt'),
('iws_process_trigger_receipt'),
('iws_process_request_detail'),
('iws_process_action_audit'),
('iws_process_schedule_control');

-- 精确对象名核验：允许 lower_case_table_names=0/1/2，不接受仅大小写近似的新表名。
DELETE FROM tmp_iws_pp_conflicts;
INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在大小写不一致对象：', i.TABLE_NAME)
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON CAST(i.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
 AND LOWER(i.TABLE_NAME) = LOWER(t.table_name)
WHERE CAST(i.TABLE_NAME AS BINARY) <> CAST(t.table_name AS BINARY);

INSERT INTO tmp_iws_pp_expected_columns
(table_name, ordinal_position, column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name) VALUES
('iws_business_schedule', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_business_schedule', 2, 'business_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_business_schedule', 3, 'business_name', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_business_schedule', 4, 'enabled', 'tinyint(1)', 'NO', '1', '', NULL, NULL),
('iws_business_schedule', 5, 'fairness_window_size', 'int', 'YES', NULL, '', NULL, NULL),
('iws_business_schedule', 6, 'revision', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_business_schedule', 7, 'remark', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_business_schedule', 8, 'del_flag', 'char(1)', 'NO', '0', '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_business_schedule', 9, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_business_schedule', 10, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_business_schedule', 11, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_business_schedule', 12, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_business_schedule', 13, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_business_schedule_plan_type', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_business_schedule_plan_type', 2, 'schedule_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_business_schedule_plan_type', 3, 'plan_type', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_business_schedule_plan_type', 4, 'plan_type_name', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_business_schedule_plan_type', 5, 'priority', 'int', 'NO', '0', '', NULL, NULL),
('iws_business_schedule_plan_type', 6, 'enabled', 'tinyint(1)', 'NO', '1', '', NULL, NULL),
('iws_business_schedule_plan_type', 7, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_business_schedule_plan_type', 8, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_business_schedule_plan_type', 9, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_business_schedule_plan_type', 10, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_business_schedule_plan_type', 11, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_definition', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_definition', 2, 'plan_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 3, 'plan_version', 'int', 'NO', NULL, '', NULL, NULL),
('iws_process_definition', 4, 'plan_type', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 5, 'business_type', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 6, 'business_schedule_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 7, 'plan_name', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 8, 'plan_role', 'varchar(16)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 9, 'status', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 10, 'revision', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_definition', 11, 'latest_key', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 12, 'active_key', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 13, 'self_system_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 14, 'mode', 'varchar(16)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 15, 'active_plan_limit', 'int', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 16, 'fairness_window_size', 'int', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 17, 'max_execution_ms', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 18, 'completion_policy', 'varchar(24)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 19, 'exception_state_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 20, 'round_batch_limit', 'int', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 21, 'round_max_duration_ms', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 22, 'schedule_interval_ms', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 23, 'schedule_cron', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 24, 'job_timeout_ms', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 25, 'job_max_retry_times', 'int', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 26, 'worker_batch_limit', 'int', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 27, 'worker_max_duration_ms', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 28, 'lease_ms', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 29, 'retry_max', 'int', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 30, 'retry_backoff_ms', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 31, 'published_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 32, 'published_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 33, 'published_summary', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 34, 'description', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 35, 'del_flag', 'char(1)', 'NO', '0', '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_definition', 36, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 37, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 38, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_definition', 39, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_definition', 40, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_system_policy', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_system_policy', 2, 'definition_version_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_system_policy', 3, 'system_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_system_policy', 4, 'posting_mode', 'varchar(24)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_system_policy', 5, 'first_confirm_policy', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_system_policy', 6, 'failure_policy', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_system_policy', 7, 'remark', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_system_policy', 8, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_system_policy', 9, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_system_policy', 10, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_system_policy', 11, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_system_policy', 12, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_state', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_state', 2, 'definition_version_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_state', 3, 'state_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_state', 4, 'display_name', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_state', 5, 'state_kind', 'varchar(16)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_state', 6, 'validator_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_state', 7, 'validator_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_state', 8, 'condition_description', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_state', 9, 'terminal', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_state', 10, 'sort_order', 'int', 'NO', '0', '', NULL, NULL),
('iws_process_state', 11, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_state', 12, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_state', 13, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_state', 14, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_state', 15, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_event_definition', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_event_definition', 2, 'definition_version_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_event_definition', 3, 'event_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 4, 'display_name', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 5, 'event_role', 'varchar(16)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 6, 'event_direction', 'varchar(16)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 7, 'event_type_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 8, 'event_type_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 9, 'call_contract_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 10, 'call_contract_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 11, 'operation_kind', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 12, 'local_action_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 13, 'local_action_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 14, 'effect_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 15, 'local_effect_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 16, 'system_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 17, 'target_system_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 18, 'endpoint_ref', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 19, 'purpose', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 20, 'completion_contract', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 21, 'source_state_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 22, 'target_state_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 23, 'enabled', 'tinyint(1)', 'NO', '1', '', NULL, NULL),
('iws_process_event_definition', 24, 'skip_policy', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 25, 'skip_contract_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 26, 'recovery_for_event_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 27, 'priority', 'int', 'NO', '0', '', NULL, NULL),
('iws_process_event_definition', 28, 'edit_policy', 'varchar(24)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 29, 'graph_edit_policy', 'varchar(24)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 30, 'locked_fields', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 31, 'remark', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event_definition', 32, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_event_definition', 33, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_event_definition', 34, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_event_definition', 35, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_event_definition', 36, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_edge', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_edge', 2, 'definition_version_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_edge', 3, 'predecessor_event_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_edge', 4, 'successor_event_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_edge', 5, 'branch_key', 'varchar(64)', 'NO', '', '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_edge', 6, 'branch_kind', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_edge', 7, 'condition_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_edge', 8, 'condition_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_edge', 9, 'default_branch', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_edge', 10, 'join_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_edge', 11, 'priority', 'int', 'NO', '0', '', NULL, NULL),
('iws_process_edge', 12, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_edge', 13, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_edge', 14, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_edge', 15, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_edge', 16, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_instance', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_instance', 2, 'plan_role', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 3, 'definition_version_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_instance', 4, 'definition_revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_instance', 5, 'schedule_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_instance', 6, 'plan_type', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 7, 'business_schedule_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 8, 'supervisor_instance_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_instance', 9, 'supervisor_key', 'varchar(256)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 10, 'business_task_key', 'varchar(256)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 11, 'business_type', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 12, 'business_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 13, 'task_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 14, 'schedule_scope', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 15, 'mode_snapshot', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 16, 'hold_start', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_instance', 17, 'first_admitted_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_instance', 18, 'plan_status', 'varchar(24)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 19, 'instance_priority', 'int', 'NO', '0', '', NULL, NULL),
('iws_process_instance', 20, 'selected_root_set', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 21, 'input_hash', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 22, 'execution_deadline', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_instance', 23, 'round_no', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_instance', 24, 'stop_requested', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_instance', 25, 'cancel_requested', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_instance', 26, 'exception_summary', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_instance', 27, 'started_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_instance', 28, 'ended_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_instance', 29, 'instance_version', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_instance', 30, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_instance', 31, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_instance', 32, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_instance', 33, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_instance', 34, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_system_context', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_system_context', 2, 'instance_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_system_context', 3, 'system_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_system_context', 4, 'posting_mode', 'varchar(24)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_system_context', 5, 'first_confirmed', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_system_context', 6, 'confirmed_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_system_context', 7, 'confirmed_name', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_system_context', 8, 'confirmed_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_system_context', 9, 'revision', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_system_context', 10, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_system_context', 11, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_system_context', 12, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_system_context', 13, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_system_context', 14, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_token', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_token', 2, 'instance_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_token', 3, 'token_role', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 4, 'token_status', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 5, 'state_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 6, 'decision_node_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 7, 'parent_scope_key', 'varchar(256)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 8, 'scope_key', 'varchar(256)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 9, 'branch_key', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 10, 'decision_input_hash', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 11, 'rule_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 12, 'selected_keys', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 13, 'expected_keys', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 14, 'joined_keys', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_token', 15, 'barrier_closed', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_token', 16, 'blocked_event_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_token', 17, 'last_attempt_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_token', 18, 'token_version', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_token', 19, 'round_no', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_token', 20, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_token', 21, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_token', 22, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_token', 23, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_token', 24, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_event', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_event', 2, 'instance_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_event', 3, 'token_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_event', 4, 'definition_version_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_event', 5, 'event_definition_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_event', 6, 'event_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 7, 'event_key', 'varchar(256)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 8, 'round_no', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_event', 9, 'branch_identity', 'varchar(256)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 10, 'event_role', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 11, 'event_direction', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 12, 'system_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 13, 'call_contract_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 14, 'call_contract_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 15, 'local_action_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 16, 'local_action_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 17, 'effect_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 18, 'local_effect_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 19, 'source_state_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 20, 'target_state_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 21, 'event_status', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 22, 'wait_reason', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 23, 'outcome', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 24, 'effect_result', 'varchar(24)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 25, 'sent', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_event', 26, 'payload_ready', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_event', 27, 'input_hash', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 28, 'idempotency_key', 'varchar(256)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 29, 'origin_outbound_event_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 30, 'recovery_for_event_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 31, 'execution_purpose', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 32, 'priority', 'int', 'NO', '0', '', NULL, NULL),
('iws_process_event', 33, 'next_retry_time', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 34, 'retry_count', 'int', 'NO', '0', '', NULL, NULL),
('iws_process_event', 35, 'owner', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 36, 'lease_epoch', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_event', 37, 'lease_until', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 38, 'event_version', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_event', 39, 'started_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 40, 'ended_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 41, 'last_attempt_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 42, 'recovery_requested', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_event', 43, 'recovery_deadline', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 44, 'error_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 45, 'error_message', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_event', 46, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 47, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 48, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_event', 49, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_event', 50, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_attempt', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_attempt', 2, 'event_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_attempt', 3, 'attempt_no', 'int', 'NO', NULL, '', NULL, NULL),
('iws_process_attempt', 4, 'resume_from_attempt_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_attempt', 5, 'checkpoint_status', 'varchar(24)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 6, 'sent', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_attempt', 7, 'request_id', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 8, 'input_hash', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 9, 'idempotency_key', 'varchar(256)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 10, 'operation_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 11, 'local_action_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 12, 'local_effect_key', 'varchar(256)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 13, 'evidence_ref', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 14, 'effect_result', 'varchar(24)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 15, 'error_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 16, 'error_message', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 17, 'source_state_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 18, 'target_state_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 19, 'owner', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 20, 'lease_epoch', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_attempt', 21, 'execution_purpose', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 22, 'checkpoint_json', 'mediumtext', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 23, 'started_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_attempt', 24, 'ended_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_attempt', 25, 'transaction_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_attempt', 26, 'recorded_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_attempt', 27, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_attempt', 28, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_attempt', 29, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_attempt', 30, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_attempt', 31, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 2, 'caller_system_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 3, 'module_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 4, 'call_contract_key', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 5, 'call_contract_version', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 6, 'source_request_id', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 7, 'payload_hash', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 8, 'effect_key', 'varchar(256)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 9, 'logical_call_key', 'varchar(256)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 10, 'instance_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 11, 'event_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 12, 'external_task_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 13, 'origin_outbound_event_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 14, 'purpose', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 15, 'canonical', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_trigger_receipt', 16, 'processing_status', 'varchar(24)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 17, 'ack_summary', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 18, 'evidence_summary', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_trigger_receipt', 19, 'received_at', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 20, 'applied_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 21, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 22, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 23, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 24, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_trigger_receipt', 25, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_request_detail', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_request_detail', 2, 'request_id', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 3, 'direction', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 4, 'purpose', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 5, 'source_request_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 6, 'correlation_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 7, 'parent_request_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 8, 'peer_request_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 9, 'caller_system_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 10, 'receiver_system_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 11, 'module_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 12, 'contract_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 13, 'contract_version', 'varchar(32)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 14, 'effect_key', 'varchar(256)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 15, 'instance_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 16, 'event_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 17, 'attempt_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 18, 'receipt_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 19, 'http_status', 'int', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 20, 'result_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 21, 'effect_result', 'varchar(24)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 22, 'sent', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_request_detail', 23, 'request_summary', 'varchar(2000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 24, 'response_summary', 'varchar(2000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 25, 'started_at', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_request_detail', 26, 'ended_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 27, 'evidence_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 28, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 29, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 30, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_request_detail', 31, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_request_detail', 32, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_action_audit', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_action_audit', 2, 'action_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 3, 'action_source', 'varchar(24)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 4, 'command_key', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 5, 'receipt_key', 'varchar(256)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 6, 'actor_user_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 7, 'actor_name', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 8, 'trace_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 9, 'schedule_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 10, 'definition_version_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 11, 'instance_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 12, 'token_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 13, 'event_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 14, 'attempt_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 15, 'supervisor_instance_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 16, 'round_no', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 17, 'dispatch_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 18, 'child_instance_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 19, 'request_id', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 20, 'before_status', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 21, 'before_version', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 22, 'after_status', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 23, 'after_version', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 24, 'result', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 25, 'reason', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 26, 'payload_summary', 'varchar(2000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 27, 'decision_summary', 'varchar(2000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 28, 'slot_before', 'int', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 29, 'slot_after', 'int', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 30, 'epoch', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 31, 'control_revision', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 32, 'platform_job_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 33, 'job_batch_id', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 34, 'occurred_at', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_action_audit', 35, 'duration_ms', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 36, 'request_started_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 37, 'request_finished_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_action_audit', 38, 'client_ip', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 39, 'user_agent', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_action_audit', 40, 'entry_path', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 1, 'id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_process_schedule_control', 2, 'scope_type', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 3, 'scope_key', 'varchar(256)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 4, 'duty', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 5, 'control_state', 'varchar(24)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 6, 'desired_state', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 7, 'observed_state', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 8, 'revision', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_schedule_control', 9, 'epoch', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_schedule_control', 10, 'owner', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 11, 'lease_until', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_schedule_control', 12, 'wake_pending', 'tinyint(1)', 'NO', '0', '', NULL, NULL),
('iws_process_schedule_control', 13, 'round_no', 'bigint', 'NO', '0', '', NULL, NULL),
('iws_process_schedule_control', 14, 'round_status', 'varchar(16)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 15, 'supervisor_instance_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_schedule_control', 16, 'definition_version_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_schedule_control', 17, 'child_instance_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_schedule_control', 18, 'slot_state', 'varchar(16)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 19, 'permit_state', 'varchar(24)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 20, 'resource_key', 'varchar(256)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 21, 'fairness_count', 'int', 'NO', '0', '', NULL, NULL),
('iws_process_schedule_control', 22, 'last_admitted_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_schedule_control', 23, 'last_activity_at', 'datetime(3)', 'YES', NULL, '', NULL, NULL),
('iws_process_schedule_control', 24, 'error_message', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_schedule_control', 25, 'create_dept', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_schedule_control', 26, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_schedule_control', 27, 'create_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL),
('iws_process_schedule_control', 28, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_process_schedule_control', 29, 'update_time', 'datetime(3)', 'NO', NULL, '', NULL, NULL);

INSERT INTO tmp_iws_pp_expected_indexes
(table_name, index_name, non_unique, seq_in_index, column_name, sub_part, index_type) VALUES
('iws_business_schedule', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_business_schedule', 'uk_iws_business_schedule_code', 0, 1, 'business_code', NULL, 'BTREE'),
('iws_business_schedule', 'idx_iws_business_schedule_list', 1, 1, 'enabled', NULL, 'BTREE'),
('iws_business_schedule', 'idx_iws_business_schedule_list', 1, 2, 'del_flag', NULL, 'BTREE'),
('iws_business_schedule', 'idx_iws_business_schedule_list', 1, 3, 'business_name', NULL, 'BTREE'),
('iws_business_schedule_plan_type', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_business_schedule_plan_type', 'uk_iws_business_schedule_plan_type', 0, 1, 'schedule_id', NULL, 'BTREE'),
('iws_business_schedule_plan_type', 'uk_iws_business_schedule_plan_type', 0, 2, 'plan_type', NULL, 'BTREE'),
('iws_business_schedule_plan_type', 'idx_iws_business_schedule_plan_type_order', 1, 1, 'schedule_id', NULL, 'BTREE'),
('iws_business_schedule_plan_type', 'idx_iws_business_schedule_plan_type_order', 1, 2, 'enabled', NULL, 'BTREE'),
('iws_business_schedule_plan_type', 'idx_iws_business_schedule_plan_type_order', 1, 3, 'priority', NULL, 'BTREE'),
('iws_process_definition', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_definition', 'uk_iws_process_definition_code_version', 0, 1, 'plan_code', NULL, 'BTREE'),
('iws_process_definition', 'uk_iws_process_definition_code_version', 0, 2, 'plan_version', NULL, 'BTREE'),
('iws_process_definition', 'uk_iws_process_definition_latest', 0, 1, 'latest_key', NULL, 'BTREE'),
('iws_process_definition', 'uk_iws_process_definition_active', 0, 1, 'active_key', NULL, 'BTREE'),
('iws_process_definition', 'idx_iws_process_definition_code_status', 1, 1, 'plan_code', NULL, 'BTREE'),
('iws_process_definition', 'idx_iws_process_definition_code_status', 1, 2, 'status', NULL, 'BTREE'),
('iws_process_definition', 'idx_iws_process_definition_business', 1, 1, 'business_type', NULL, 'BTREE'),
('iws_process_definition', 'idx_iws_process_definition_business', 1, 2, 'plan_type', NULL, 'BTREE'),
('iws_process_definition', 'idx_iws_process_definition_business', 1, 3, 'status', NULL, 'BTREE'),
('iws_process_definition', 'idx_iws_process_definition_role', 1, 1, 'plan_role', NULL, 'BTREE'),
('iws_process_definition', 'idx_iws_process_definition_role', 1, 2, 'status', NULL, 'BTREE'),
('iws_process_system_policy', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_system_policy', 'uk_iws_process_system_policy', 0, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_system_policy', 'uk_iws_process_system_policy', 0, 2, 'system_code', NULL, 'BTREE'),
('iws_process_system_policy', 'idx_iws_process_system_policy_system', 1, 1, 'system_code', NULL, 'BTREE'),
('iws_process_state', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_state', 'uk_iws_process_state', 0, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_state', 'uk_iws_process_state', 0, 2, 'state_code', NULL, 'BTREE'),
('iws_process_state', 'idx_iws_process_state_order', 1, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_state', 'idx_iws_process_state_order', 1, 2, 'sort_order', NULL, 'BTREE'),
('iws_process_event_definition', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_event_definition', 'uk_iws_process_event_definition', 0, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_event_definition', 'uk_iws_process_event_definition', 0, 2, 'event_code', NULL, 'BTREE'),
('iws_process_event_definition', 'idx_iws_process_event_definition_order', 1, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_event_definition', 'idx_iws_process_event_definition_order', 1, 2, 'enabled', NULL, 'BTREE'),
('iws_process_event_definition', 'idx_iws_process_event_definition_order', 1, 3, 'priority', NULL, 'BTREE'),
('iws_process_event_definition', 'idx_iws_process_event_definition_system', 1, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_event_definition', 'idx_iws_process_event_definition_system', 1, 2, 'system_code', NULL, 'BTREE'),
('iws_process_event_definition', 'idx_iws_process_event_definition_role', 1, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_event_definition', 'idx_iws_process_event_definition_role', 1, 2, 'event_role', NULL, 'BTREE'),
('iws_process_edge', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_edge', 'uk_iws_process_edge', 0, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_edge', 'uk_iws_process_edge', 0, 2, 'predecessor_event_code', NULL, 'BTREE'),
('iws_process_edge', 'uk_iws_process_edge', 0, 3, 'successor_event_code', NULL, 'BTREE'),
('iws_process_edge', 'uk_iws_process_edge', 0, 4, 'branch_key', NULL, 'BTREE'),
('iws_process_edge', 'idx_iws_process_edge_route', 1, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_edge', 'idx_iws_process_edge_route', 1, 2, 'branch_kind', NULL, 'BTREE'),
('iws_process_edge', 'idx_iws_process_edge_route', 1, 3, 'priority', NULL, 'BTREE'),
('iws_process_edge', 'idx_iws_process_edge_join', 1, 1, 'definition_version_id', NULL, 'BTREE'),
('iws_process_edge', 'idx_iws_process_edge_join', 1, 2, 'join_code', NULL, 'BTREE'),
('iws_process_instance', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_instance', 'uk_iws_process_instance_business_identity', 0, 1, 'business_task_key', NULL, 'BTREE'),
('iws_process_instance', 'uk_iws_process_instance_supervisor_identity', 0, 1, 'supervisor_key', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_business_task', 1, 1, 'business_type', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_business_task', 1, 2, 'task_key', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_supervisor_scope', 1, 1, 'schedule_id', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_supervisor_scope', 1, 2, 'plan_type', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_supervisor_scope', 1, 3, 'schedule_scope', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_supervisor_scope', 1, 4, 'plan_role', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_schedule', 1, 1, 'business_schedule_code', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_schedule', 1, 2, 'plan_status', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_schedule', 1, 3, 'instance_priority', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_supervisor', 1, 1, 'supervisor_instance_id', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_supervisor', 1, 2, 'plan_role', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_deadline', 1, 1, 'plan_status', NULL, 'BTREE'),
('iws_process_instance', 'idx_iws_process_instance_deadline', 1, 2, 'execution_deadline', NULL, 'BTREE'),
('iws_process_system_context', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_system_context', 'uk_iws_process_system_context', 0, 1, 'instance_id', NULL, 'BTREE'),
('iws_process_system_context', 'uk_iws_process_system_context', 0, 2, 'system_code', NULL, 'BTREE'),
('iws_process_system_context', 'idx_iws_process_system_context_system', 1, 1, 'system_code', NULL, 'BTREE'),
('iws_process_token', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_token', 'uk_iws_process_token_scope_branch', 0, 1, 'instance_id', NULL, 'BTREE'),
('iws_process_token', 'uk_iws_process_token_scope_branch', 0, 2, 'scope_key', NULL, 'BTREE'),
('iws_process_token', 'uk_iws_process_token_scope_branch', 0, 3, 'branch_key', NULL, 'BTREE'),
('iws_process_token', 'idx_iws_process_token_state', 1, 1, 'instance_id', NULL, 'BTREE'),
('iws_process_token', 'idx_iws_process_token_state', 1, 2, 'token_role', NULL, 'BTREE'),
('iws_process_token', 'idx_iws_process_token_state', 1, 3, 'state_code', NULL, 'BTREE'),
('iws_process_token', 'idx_iws_process_token_blocked', 1, 1, 'blocked_event_id', NULL, 'BTREE'),
('iws_process_event', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_event', 'uk_iws_process_event_stable', 0, 1, 'instance_id', NULL, 'BTREE'),
('iws_process_event', 'uk_iws_process_event_stable', 0, 2, 'event_key', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_ready', 1, 1, 'event_status', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_ready', 1, 2, 'next_retry_time', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_ready', 1, 3, 'priority', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_ready', 1, 4, 'id', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_instance', 1, 1, 'instance_id', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_instance', 1, 2, 'event_status', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_system', 1, 1, 'system_code', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_system', 1, 2, 'event_status', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_lease', 1, 1, 'event_status', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_lease', 1, 2, 'lease_until', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_lease', 1, 3, 'id', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_recovery', 1, 1, 'execution_purpose', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_recovery', 1, 2, 'event_status', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_recovery', 1, 3, 'recovery_deadline', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_recovery', 1, 4, 'id', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_origin', 1, 1, 'origin_outbound_event_id', NULL, 'BTREE'),
('iws_process_event', 'idx_iws_process_event_token', 1, 1, 'token_id', NULL, 'BTREE'),
('iws_process_attempt', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_attempt', 'uk_iws_process_attempt_no', 0, 1, 'event_id', NULL, 'BTREE'),
('iws_process_attempt', 'uk_iws_process_attempt_no', 0, 2, 'attempt_no', NULL, 'BTREE'),
('iws_process_attempt', 'idx_iws_process_attempt_effect', 1, 1, 'event_id', NULL, 'BTREE'),
('iws_process_attempt', 'idx_iws_process_attempt_effect', 1, 2, 'local_effect_key', NULL, 'BTREE'),
('iws_process_attempt', 'idx_iws_process_attempt_request', 1, 1, 'request_id', NULL, 'BTREE'),
('iws_process_attempt', 'idx_iws_process_attempt_idempotency', 1, 1, 'idempotency_key', NULL, 'BTREE'),
('iws_process_trigger_receipt', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_trigger_receipt', 'uk_iws_process_trigger_receipt_source', 0, 1, 'caller_system_code', NULL, 'BTREE'),
('iws_process_trigger_receipt', 'uk_iws_process_trigger_receipt_source', 0, 2, 'call_contract_key', NULL, 'BTREE'),
('iws_process_trigger_receipt', 'uk_iws_process_trigger_receipt_source', 0, 3, 'call_contract_version', NULL, 'BTREE'),
('iws_process_trigger_receipt', 'uk_iws_process_trigger_receipt_source', 0, 4, 'source_request_id', NULL, 'BTREE'),
('iws_process_trigger_receipt', 'idx_iws_process_trigger_receipt_event', 1, 1, 'event_id', NULL, 'BTREE'),
('iws_process_trigger_receipt', 'idx_iws_process_trigger_receipt_instance', 1, 1, 'instance_id', NULL, 'BTREE'),
('iws_process_trigger_receipt', 'idx_iws_process_trigger_receipt_logical', 1, 1, 'logical_call_key', NULL, 'BTREE'),
('iws_process_request_detail', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_request_detail', 'uk_iws_process_request_detail_id', 0, 1, 'request_id', NULL, 'BTREE'),
('iws_process_request_detail', 'idx_iws_process_request_detail_event', 1, 1, 'event_id', NULL, 'BTREE'),
('iws_process_request_detail', 'idx_iws_process_request_detail_event', 1, 2, 'direction', NULL, 'BTREE'),
('iws_process_request_detail', 'idx_iws_process_request_detail_correlation', 1, 1, 'correlation_id', NULL, 'BTREE'),
('iws_process_request_detail', 'idx_iws_process_request_detail_instance', 1, 1, 'instance_id', NULL, 'BTREE'),
('iws_process_request_detail', 'idx_iws_process_request_detail_instance', 1, 2, 'started_at', NULL, 'BTREE'),
('iws_process_action_audit', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_action_audit', 'uk_iws_process_action_audit_receipt', 0, 1, 'receipt_key', NULL, 'BTREE'),
('iws_process_action_audit', 'idx_iws_process_action_audit_instance', 1, 1, 'instance_id', NULL, 'BTREE'),
('iws_process_action_audit', 'idx_iws_process_action_audit_instance', 1, 2, 'occurred_at', NULL, 'BTREE'),
('iws_process_action_audit', 'idx_iws_process_action_audit_event', 1, 1, 'event_id', NULL, 'BTREE'),
('iws_process_action_audit', 'idx_iws_process_action_audit_event', 1, 2, 'occurred_at', NULL, 'BTREE'),
('iws_process_action_audit', 'idx_iws_process_action_audit_command', 1, 1, 'command_key', NULL, 'BTREE'),
('iws_process_action_audit', 'idx_iws_process_action_audit_schedule', 1, 1, 'schedule_id', NULL, 'BTREE'),
('iws_process_action_audit', 'idx_iws_process_action_audit_schedule', 1, 2, 'occurred_at', NULL, 'BTREE'),
('iws_process_action_audit', 'idx_iws_process_action_audit_supervisor', 1, 1, 'supervisor_instance_id', NULL, 'BTREE'),
('iws_process_action_audit', 'idx_iws_process_action_audit_supervisor', 1, 2, 'round_no', NULL, 'BTREE'),
('iws_process_schedule_control', 'PRIMARY', 0, 1, 'id', NULL, 'BTREE'),
('iws_process_schedule_control', 'uk_iws_process_schedule_control_scope', 0, 1, 'scope_type', NULL, 'BTREE'),
('iws_process_schedule_control', 'uk_iws_process_schedule_control_scope', 0, 2, 'scope_key', NULL, 'BTREE'),
('iws_process_schedule_control', 'uk_iws_process_schedule_control_scope', 0, 3, 'duty', NULL, 'BTREE'),
('iws_process_schedule_control', 'uk_iws_process_schedule_control_slot', 0, 1, 'supervisor_instance_id', NULL, 'BTREE'),
('iws_process_schedule_control', 'uk_iws_process_schedule_control_slot', 0, 2, 'child_instance_id', NULL, 'BTREE'),
('iws_process_schedule_control', 'uk_iws_process_schedule_control_slot', 0, 3, 'duty', NULL, 'BTREE'),
('iws_process_schedule_control', 'idx_iws_process_schedule_control_state', 1, 1, 'scope_type', NULL, 'BTREE'),
('iws_process_schedule_control', 'idx_iws_process_schedule_control_state', 1, 2, 'control_state', NULL, 'BTREE'),
('iws_process_schedule_control', 'idx_iws_process_schedule_control_state', 1, 3, 'last_activity_at', NULL, 'BTREE'),
('iws_process_schedule_control', 'idx_iws_process_schedule_control_round', 1, 1, 'supervisor_instance_id', NULL, 'BTREE'),
('iws_process_schedule_control', 'idx_iws_process_schedule_control_round', 1, 2, 'round_no', NULL, 'BTREE'),
('iws_process_schedule_control', 'idx_iws_process_schedule_control_round', 1, 3, 'round_status', NULL, 'BTREE'),
('iws_process_schedule_control', 'idx_iws_process_schedule_control_child', 1, 1, 'child_instance_id', NULL, 'BTREE'),
('iws_process_schedule_control', 'idx_iws_process_schedule_control_child', 1, 2, 'slot_state', NULL, 'BTREE');

-- 预检只比较已存在表；缺表由后续门控创建，已存在但定义不同则禁止任何创建。
DELETE FROM tmp_iws_pp_conflicts;
INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, '表不是 InnoDB 基础表或排序规则不一致'
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
WHERE i.TABLE_TYPE <> 'BASE TABLE' OR i.ENGINE <> 'InnoDB' OR NOT (i.TABLE_COLLATION <=> 'utf8mb4_general_ci');

INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('列定义不匹配：', e.column_name)
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_pp_expected_columns e ON e.table_name = t.table_name
LEFT JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR c.ORDINAL_POSITION <> e.ordinal_position
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable)
   OR NOT (c.COLUMN_DEFAULT <=> e.column_default)
   OR NOT (c.EXTRA <=> e.extra)
   OR NOT (c.CHARACTER_SET_NAME <=> e.character_set_name)
   OR NOT (c.COLLATION_NAME <=> e.collation_name);

INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记列：', c.COLUMN_NAME)
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_pp_expected_columns e
  ON e.table_name = t.table_name AND e.column_name = c.COLUMN_NAME
WHERE e.column_name IS NULL;

INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('索引定义不匹配：', e.index_name)
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_pp_expected_indexes e ON e.table_name = t.table_name
LEFT JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
 AND s.INDEX_NAME = e.index_name AND s.SEQ_IN_INDEX = e.seq_in_index
WHERE s.INDEX_NAME IS NULL
   OR s.NON_UNIQUE <> e.non_unique
   OR NOT (s.COLUMN_NAME <=> e.column_name)
   OR NOT (s.SUB_PART <=> e.sub_part)
   OR NOT (s.INDEX_TYPE <=> e.index_type);

INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记索引：', s.INDEX_NAME)
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_pp_expected_indexes e
  ON e.table_name = t.table_name AND e.index_name = s.INDEX_NAME AND e.seq_in_index = s.SEQ_IN_INDEX
WHERE e.index_name IS NULL;

SELECT COUNT(*) INTO @pp_conflicts FROM tmp_iws_pp_conflicts;
SET @pp_preflight_ok = IF(@pp_precheck_ok = 1 AND @pp_conflicts = 0, 1, 0);
SET @pp_ddl_chain_ok = @pp_preflight_ok;

SELECT `表名`, `冲突说明`
FROM (
    SELECT table_name AS `表名`, reason AS `冲突说明`, 1 AS sort_no FROM tmp_iws_pp_conflicts
    UNION ALL
    SELECT '--', '无结构冲突', 2
) detail
WHERE (@pp_conflicts > 0 AND sort_no = 1) OR (@pp_conflicts = 0 AND sort_no = 2)
ORDER BY `表名`, `冲突说明`;

SELECT JSON_EXTRACT(IF(@pp_preflight_ok = 1, '{}', 'invalid'), '$') AS `预检结果`;

-- 建表：表缺失且预检通过时才执行。无物理外键，字段关联由事务与业务唯一键保证。
SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_business_schedule");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_business_schedule` (',
    "`id` bigint NOT NULL,",
    "`business_code` varchar(64) NOT NULL,",
    "`business_name` varchar(128) NOT NULL,",
    "`enabled` tinyint(1) NOT NULL DEFAULT 1,",
    "`fairness_window_size` int NULL,",
    "`revision` bigint NOT NULL DEFAULT 0,",
    "`remark` varchar(500) NULL,",
    "`del_flag` char(1) NOT NULL DEFAULT '0',",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_business_schedule_code` (`business_code`),",
    "KEY `idx_iws_business_schedule_list` (`enabled`, `del_flag`, `business_name`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_business_schedule'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_business_schedule_plan_type");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_business_schedule_plan_type` (',
    "`id` bigint NOT NULL,",
    "`schedule_id` bigint NOT NULL,",
    "`plan_type` varchar(64) NOT NULL,",
    "`plan_type_name` varchar(128) NULL,",
    "`priority` int NOT NULL DEFAULT 0,",
    "`enabled` tinyint(1) NOT NULL DEFAULT 1,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_business_schedule_plan_type` (`schedule_id`, `plan_type`),",
    "KEY `idx_iws_business_schedule_plan_type_order` (`schedule_id`, `enabled`, `priority`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_business_schedule_plan_type'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_definition");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_definition` (',
    "`id` bigint NOT NULL,",
    "`plan_code` varchar(64) NOT NULL,",
    "`plan_version` int NOT NULL,",
    "`plan_type` varchar(64) NULL,",
    "`business_type` varchar(64) NULL,",
    "`business_schedule_code` varchar(64) NULL,",
    "`plan_name` varchar(128) NOT NULL,",
    "`plan_role` varchar(16) NULL,",
    "`status` varchar(16) NOT NULL,",
    "`revision` bigint NOT NULL DEFAULT 0,",
    "`latest_key` varchar(64) NULL,",
    "`active_key` varchar(64) NULL,",
    "`self_system_code` varchar(64) NULL,",
    "`mode` varchar(16) NULL,",
    "`active_plan_limit` int NULL,",
    "`fairness_window_size` int NULL,",
    "`max_execution_ms` bigint NULL,",
    "`completion_policy` varchar(24) NOT NULL,",
    "`exception_state_code` varchar(64) NULL,",
    "`round_batch_limit` int NULL,",
    "`round_max_duration_ms` bigint NULL,",
    "`schedule_interval_ms` bigint NULL,",
    "`schedule_cron` varchar(64) NULL,",
    "`job_timeout_ms` bigint NULL,",
    "`job_max_retry_times` int NULL,",
    "`worker_batch_limit` int NULL,",
    "`worker_max_duration_ms` bigint NULL,",
    "`lease_ms` bigint NULL,",
    "`retry_max` int NULL,",
    "`retry_backoff_ms` bigint NULL,",
    "`published_by` bigint NULL,",
    "`published_at` datetime(3) NULL,",
    "`published_summary` varchar(500) NULL,",
    "`description` varchar(1000) NULL,",
    "`del_flag` char(1) NOT NULL DEFAULT '0',",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_definition_code_version` (`plan_code`, `plan_version`),",
    "UNIQUE KEY `uk_iws_process_definition_latest` (`latest_key`),",
    "UNIQUE KEY `uk_iws_process_definition_active` (`active_key`),",
    "KEY `idx_iws_process_definition_code_status` (`plan_code`, `status`),",
    "KEY `idx_iws_process_definition_business` (`business_type`, `plan_type`, `status`),",
    "KEY `idx_iws_process_definition_role` (`plan_role`, `status`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_definition'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_system_policy");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_system_policy` (',
    "`id` bigint NOT NULL,",
    "`definition_version_id` bigint NOT NULL,",
    "`system_code` varchar(64) NOT NULL,",
    "`posting_mode` varchar(24) NULL,",
    "`first_confirm_policy` varchar(32) NULL,",
    "`failure_policy` varchar(32) NULL,",
    "`remark` varchar(500) NULL,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_system_policy` (`definition_version_id`, `system_code`),",
    "KEY `idx_iws_process_system_policy_system` (`system_code`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_system_policy'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_state");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_state` (',
    "`id` bigint NOT NULL,",
    "`definition_version_id` bigint NOT NULL,",
    "`state_code` varchar(64) NOT NULL,",
    "`display_name` varchar(128) NULL,",
    "`state_kind` varchar(16) NULL,",
    "`validator_key` varchar(128) NULL,",
    "`validator_version` varchar(32) NULL,",
    "`condition_description` varchar(1000) NULL,",
    "`terminal` tinyint(1) NOT NULL DEFAULT 0,",
    "`sort_order` int NOT NULL DEFAULT 0,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_state` (`definition_version_id`, `state_code`),",
    "KEY `idx_iws_process_state_order` (`definition_version_id`, `sort_order`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_state'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_event_definition");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_event_definition` (',
    "`id` bigint NOT NULL,",
    "`definition_version_id` bigint NOT NULL,",
    "`event_code` varchar(64) NOT NULL,",
    "`display_name` varchar(128) NULL,",
    "`event_role` varchar(16) NULL,",
    "`event_direction` varchar(16) NULL,",
    "`event_type_key` varchar(128) NULL,",
    "`event_type_version` varchar(32) NULL,",
    "`call_contract_key` varchar(128) NULL,",
    "`call_contract_version` varchar(32) NULL,",
    "`operation_kind` varchar(32) NULL,",
    "`local_action_key` varchar(128) NULL,",
    "`local_action_version` varchar(32) NULL,",
    "`effect_key` varchar(128) NULL,",
    "`local_effect_key` varchar(128) NULL,",
    "`system_code` varchar(64) NULL,",
    "`target_system_code` varchar(64) NULL,",
    "`endpoint_ref` varchar(128) NULL,",
    "`purpose` varchar(32) NULL,",
    "`completion_contract` varchar(32) NULL,",
    "`source_state_code` varchar(64) NULL,",
    "`target_state_code` varchar(64) NULL,",
    "`enabled` tinyint(1) NOT NULL DEFAULT 1,",
    "`skip_policy` varchar(32) NULL,",
    "`skip_contract_key` varchar(128) NULL,",
    "`recovery_for_event_code` varchar(64) NULL,",
    "`priority` int NOT NULL DEFAULT 0,",
    "`edit_policy` varchar(24) NOT NULL,",
    "`graph_edit_policy` varchar(24) NOT NULL,",
    "`locked_fields` varchar(1000) NULL,",
    "`remark` varchar(500) NULL,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_event_definition` (`definition_version_id`, `event_code`),",
    "KEY `idx_iws_process_event_definition_order` (`definition_version_id`, `enabled`, `priority`),",
    "KEY `idx_iws_process_event_definition_system` (`definition_version_id`, `system_code`),",
    "KEY `idx_iws_process_event_definition_role` (`definition_version_id`, `event_role`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_event_definition'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_edge");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_edge` (',
    "`id` bigint NOT NULL,",
    "`definition_version_id` bigint NOT NULL,",
    "`predecessor_event_code` varchar(64) NULL,",
    "`successor_event_code` varchar(64) NULL,",
    "`branch_key` varchar(64) NOT NULL DEFAULT '',",
    "`branch_kind` varchar(16) NOT NULL,",
    "`condition_key` varchar(128) NULL,",
    "`condition_version` varchar(32) NULL,",
    "`default_branch` tinyint(1) NOT NULL DEFAULT 0,",
    "`join_code` varchar(64) NULL,",
    "`priority` int NOT NULL DEFAULT 0,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_edge` (`definition_version_id`, `predecessor_event_code`, `successor_event_code`, `branch_key`),",
    "KEY `idx_iws_process_edge_route` (`definition_version_id`, `branch_kind`, `priority`),",
    "KEY `idx_iws_process_edge_join` (`definition_version_id`, `join_code`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_edge'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_instance");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_instance` (',
    "`id` bigint NOT NULL,",
    "`plan_role` varchar(16) NOT NULL,",
    "`definition_version_id` bigint NOT NULL,",
    "`definition_revision` bigint NOT NULL,",
    "`schedule_id` bigint NOT NULL,",
    "`plan_type` varchar(64) NOT NULL,",
    "`business_schedule_code` varchar(64) NOT NULL,",
    "`supervisor_instance_id` bigint NULL,",
    "`supervisor_key` varchar(256) NULL,",
    "`business_task_key` varchar(256) NULL,",
    "`business_type` varchar(64) NULL,",
    "`business_id` varchar(128) NULL,",
    "`task_key` varchar(128) NULL,",
    "`schedule_scope` varchar(128) NOT NULL,",
    "`mode_snapshot` varchar(16) NOT NULL,",
    "`hold_start` tinyint(1) NOT NULL DEFAULT 0,",
    "`first_admitted_at` datetime(3) NULL,",
    "`plan_status` varchar(24) NOT NULL,",
    "`instance_priority` int NOT NULL DEFAULT 0,",
    "`selected_root_set` text NULL,",
    "`input_hash` varchar(128) NULL,",
    "`execution_deadline` datetime(3) NULL,",
    "`round_no` bigint NOT NULL DEFAULT 0,",
    "`stop_requested` tinyint(1) NOT NULL DEFAULT 0,",
    "`cancel_requested` tinyint(1) NOT NULL DEFAULT 0,",
    "`exception_summary` varchar(1000) NULL,",
    "`started_at` datetime(3) NULL,",
    "`ended_at` datetime(3) NULL,",
    "`instance_version` bigint NOT NULL DEFAULT 0,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_instance_business_identity` (`business_task_key`),",
    "UNIQUE KEY `uk_iws_process_instance_supervisor_identity` (`supervisor_key`),",
    "KEY `idx_iws_process_instance_business_task` (`business_type`, `task_key`),",
    "KEY `idx_iws_process_instance_supervisor_scope` (`schedule_id`, `plan_type`, `schedule_scope`, `plan_role`),",
    "KEY `idx_iws_process_instance_schedule` (`business_schedule_code`, `plan_status`, `instance_priority`),",
    "KEY `idx_iws_process_instance_supervisor` (`supervisor_instance_id`, `plan_role`),",
    "KEY `idx_iws_process_instance_deadline` (`plan_status`, `execution_deadline`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_instance'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_system_context");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_system_context` (',
    "`id` bigint NOT NULL,",
    "`instance_id` bigint NOT NULL,",
    "`system_code` varchar(64) NOT NULL,",
    "`posting_mode` varchar(24) NOT NULL,",
    "`first_confirmed` tinyint(1) NOT NULL DEFAULT 0,",
    "`confirmed_by` bigint NULL,",
    "`confirmed_name` varchar(128) NULL,",
    "`confirmed_at` datetime(3) NULL,",
    "`revision` bigint NOT NULL DEFAULT 0,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_system_context` (`instance_id`, `system_code`),",
    "KEY `idx_iws_process_system_context_system` (`system_code`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_system_context'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_token");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_token` (',
    "`id` bigint NOT NULL,",
    "`instance_id` bigint NOT NULL,",
    "`token_role` varchar(16) NOT NULL,",
    "`token_status` varchar(16) NOT NULL,",
    "`state_code` varchar(64) NOT NULL,",
    "`decision_node_code` varchar(64) NULL,",
    "`parent_scope_key` varchar(256) NOT NULL,",
    "`scope_key` varchar(256) NOT NULL,",
    "`branch_key` varchar(128) NOT NULL,",
    "`decision_input_hash` varchar(128) NULL,",
    "`rule_version` varchar(32) NULL,",
    "`selected_keys` text NULL,",
    "`expected_keys` text NULL,",
    "`joined_keys` text NULL,",
    "`barrier_closed` tinyint(1) NOT NULL DEFAULT 0,",
    "`blocked_event_id` bigint NULL,",
    "`last_attempt_id` bigint NULL,",
    "`token_version` bigint NOT NULL DEFAULT 0,",
    "`round_no` bigint NOT NULL DEFAULT 0,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_token_scope_branch` (`instance_id`, `scope_key`, `branch_key`),",
    "KEY `idx_iws_process_token_state` (`instance_id`, `token_role`, `state_code`),",
    "KEY `idx_iws_process_token_blocked` (`blocked_event_id`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_token'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_event");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_event` (',
    "`id` bigint NOT NULL,",
    "`instance_id` bigint NOT NULL,",
    "`token_id` bigint NOT NULL,",
    "`definition_version_id` bigint NOT NULL,",
    "`event_definition_id` bigint NOT NULL,",
    "`event_code` varchar(64) NOT NULL,",
    "`event_key` varchar(256) NOT NULL,",
    "`round_no` bigint NOT NULL DEFAULT 0,",
    "`branch_identity` varchar(256) NOT NULL,",
    "`event_role` varchar(16) NOT NULL,",
    "`event_direction` varchar(16) NOT NULL,",
    "`system_code` varchar(64) NOT NULL,",
    "`call_contract_key` varchar(128) NULL,",
    "`call_contract_version` varchar(32) NULL,",
    "`local_action_key` varchar(128) NULL,",
    "`local_action_version` varchar(32) NULL,",
    "`effect_key` varchar(128) NULL,",
    "`local_effect_key` varchar(128) NULL,",
    "`source_state_code` varchar(64) NOT NULL,",
    "`target_state_code` varchar(64) NOT NULL,",
    "`event_status` varchar(32) NOT NULL,",
    "`wait_reason` varchar(32) NULL,",
    "`outcome` varchar(64) NULL,",
    "`effect_result` varchar(24) NULL,",
    "`sent` tinyint(1) NOT NULL DEFAULT 0,",
    "`payload_ready` tinyint(1) NOT NULL DEFAULT 0,",
    "`input_hash` varchar(128) NULL,",
    "`idempotency_key` varchar(256) NULL,",
    "`origin_outbound_event_id` bigint NULL,",
    "`recovery_for_event_code` varchar(64) NULL,",
    "`execution_purpose` varchar(32) NOT NULL,",
    "`priority` int NOT NULL DEFAULT 0,",
    "`next_retry_time` datetime(3) NULL,",
    "`retry_count` int NOT NULL DEFAULT 0,",
    "`owner` varchar(128) NULL,",
    "`lease_epoch` bigint NOT NULL DEFAULT 0,",
    "`lease_until` datetime(3) NULL,",
    "`event_version` bigint NOT NULL DEFAULT 0,",
    "`started_at` datetime(3) NULL,",
    "`ended_at` datetime(3) NULL,",
    "`last_attempt_id` bigint NULL,",
    "`recovery_requested` tinyint(1) NOT NULL DEFAULT 0,",
    "`recovery_deadline` datetime(3) NULL,",
    "`error_code` varchar(64) NULL,",
    "`error_message` varchar(1000) NULL,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_event_stable` (`instance_id`, `event_key`),",
    "KEY `idx_iws_process_event_ready` (`event_status`, `next_retry_time`, `priority`, `id`),",
    "KEY `idx_iws_process_event_instance` (`instance_id`, `event_status`),",
    "KEY `idx_iws_process_event_system` (`system_code`, `event_status`),",
    "KEY `idx_iws_process_event_lease` (`event_status`, `lease_until`, `id`),",
    "KEY `idx_iws_process_event_recovery` (`execution_purpose`, `event_status`, `recovery_deadline`, `id`),",
    "KEY `idx_iws_process_event_origin` (`origin_outbound_event_id`),",
    "KEY `idx_iws_process_event_token` (`token_id`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_event'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_attempt");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_attempt` (',
    "`id` bigint NOT NULL,",
    "`event_id` bigint NOT NULL,",
    "`attempt_no` int NOT NULL,",
    "`resume_from_attempt_id` bigint NULL,",
    "`checkpoint_status` varchar(24) NOT NULL,",
    "`sent` tinyint(1) NOT NULL DEFAULT 0,",
    "`request_id` varchar(64) NULL,",
    "`input_hash` varchar(128) NULL,",
    "`idempotency_key` varchar(256) NULL,",
    "`operation_version` varchar(32) NULL,",
    "`local_action_version` varchar(32) NULL,",
    "`local_effect_key` varchar(256) NULL,",
    "`evidence_ref` varchar(500) NULL,",
    "`effect_result` varchar(24) NULL,",
    "`error_code` varchar(64) NULL,",
    "`error_message` varchar(1000) NULL,",
    "`source_state_code` varchar(64) NOT NULL,",
    "`target_state_code` varchar(64) NOT NULL,",
    "`owner` varchar(128) NULL,",
    "`lease_epoch` bigint NOT NULL DEFAULT 0,",
    "`execution_purpose` varchar(32) NOT NULL,",
    "`checkpoint_json` mediumtext NULL,",
    "`started_at` datetime(3) NULL,",
    "`ended_at` datetime(3) NULL,",
    "`transaction_id` varchar(128) NULL,",
    "`recorded_at` datetime(3) NULL,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_attempt_no` (`event_id`, `attempt_no`),",
    "KEY `idx_iws_process_attempt_effect` (`event_id`, `local_effect_key`),",
    "KEY `idx_iws_process_attempt_request` (`request_id`),",
    "KEY `idx_iws_process_attempt_idempotency` (`idempotency_key`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_attempt'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_trigger_receipt");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_trigger_receipt` (',
    "`id` bigint NOT NULL,",
    "`caller_system_code` varchar(64) NOT NULL,",
    "`module_code` varchar(64) NOT NULL,",
    "`call_contract_key` varchar(128) NOT NULL,",
    "`call_contract_version` varchar(32) NOT NULL,",
    "`source_request_id` varchar(128) NOT NULL,",
    "`payload_hash` varchar(128) NOT NULL,",
    "`effect_key` varchar(256) NOT NULL,",
    "`logical_call_key` varchar(256) NOT NULL,",
    "`instance_id` bigint NULL,",
    "`event_id` bigint NULL,",
    "`external_task_id` varchar(128) NULL,",
    "`origin_outbound_event_id` bigint NULL,",
    "`purpose` varchar(32) NOT NULL,",
    "`canonical` tinyint(1) NOT NULL DEFAULT 0,",
    "`processing_status` varchar(24) NOT NULL,",
    "`ack_summary` varchar(500) NULL,",
    "`evidence_summary` varchar(1000) NULL,",
    "`received_at` datetime(3) NOT NULL,",
    "`applied_at` datetime(3) NULL,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_trigger_receipt_source` (`caller_system_code`, `call_contract_key`, `call_contract_version`, `source_request_id`),",
    "KEY `idx_iws_process_trigger_receipt_event` (`event_id`),",
    "KEY `idx_iws_process_trigger_receipt_instance` (`instance_id`),",
    "KEY `idx_iws_process_trigger_receipt_logical` (`logical_call_key`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_trigger_receipt'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_request_detail");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_request_detail` (',
    "`id` bigint NOT NULL,",
    "`request_id` varchar(64) NOT NULL,",
    "`direction` varchar(16) NOT NULL,",
    "`purpose` varchar(32) NOT NULL,",
    "`source_request_id` varchar(128) NULL,",
    "`correlation_id` varchar(128) NULL,",
    "`parent_request_id` varchar(128) NULL,",
    "`peer_request_id` varchar(128) NULL,",
    "`caller_system_code` varchar(64) NULL,",
    "`receiver_system_code` varchar(64) NULL,",
    "`module_code` varchar(64) NULL,",
    "`contract_key` varchar(128) NULL,",
    "`contract_version` varchar(32) NULL,",
    "`effect_key` varchar(256) NULL,",
    "`instance_id` bigint NULL,",
    "`event_id` bigint NULL,",
    "`attempt_id` bigint NULL,",
    "`receipt_id` bigint NULL,",
    "`http_status` int NULL,",
    "`result_code` varchar(64) NULL,",
    "`effect_result` varchar(24) NULL,",
    "`sent` tinyint(1) NOT NULL DEFAULT 0,",
    "`request_summary` varchar(2000) NULL,",
    "`response_summary` varchar(2000) NULL,",
    "`started_at` datetime(3) NOT NULL,",
    "`ended_at` datetime(3) NULL,",
    "`evidence_at` datetime(3) NULL,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_request_detail_id` (`request_id`),",
    "KEY `idx_iws_process_request_detail_event` (`event_id`, `direction`),",
    "KEY `idx_iws_process_request_detail_correlation` (`correlation_id`),",
    "KEY `idx_iws_process_request_detail_instance` (`instance_id`, `started_at`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_request_detail'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_action_audit");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_action_audit` (',
    "`id` bigint NOT NULL,",
    "`action_code` varchar(64) NOT NULL,",
    "`action_source` varchar(24) NOT NULL,",
    "`command_key` varchar(128) NULL,",
    "`receipt_key` varchar(256) NULL,",
    "`actor_user_id` bigint NULL,",
    "`actor_name` varchar(128) NULL,",
    "`trace_id` varchar(128) NULL,",
    "`schedule_id` bigint NULL,",
    "`definition_version_id` bigint NULL,",
    "`instance_id` bigint NULL,",
    "`token_id` bigint NULL,",
    "`event_id` bigint NULL,",
    "`attempt_id` bigint NULL,",
    "`supervisor_instance_id` bigint NULL,",
    "`round_no` bigint NULL,",
    "`dispatch_id` varchar(128) NULL,",
    "`child_instance_id` bigint NULL,",
    "`request_id` varchar(64) NULL,",
    "`before_status` varchar(64) NULL,",
    "`before_version` bigint NULL,",
    "`after_status` varchar(64) NULL,",
    "`after_version` bigint NULL,",
    "`result` varchar(16) NOT NULL,",
    "`reason` varchar(1000) NULL,",
    "`payload_summary` varchar(2000) NULL,",
    "`decision_summary` varchar(2000) NULL,",
    "`slot_before` int NULL,",
    "`slot_after` int NULL,",
    "`epoch` bigint NULL,",
    "`control_revision` bigint NULL,",
    "`platform_job_id` varchar(128) NULL,",
    "`job_batch_id` varchar(128) NULL,",
    "`occurred_at` datetime(3) NOT NULL,",
    "`duration_ms` bigint NULL,",
    "`request_started_at` datetime(3) NULL,",
    "`request_finished_at` datetime(3) NULL,",
    "`client_ip` varchar(64) NULL,",
    "`user_agent` varchar(500) NULL,",
    "`entry_path` varchar(500) NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_action_audit_receipt` (`receipt_key`),",
    "KEY `idx_iws_process_action_audit_instance` (`instance_id`, `occurred_at`),",
    "KEY `idx_iws_process_action_audit_event` (`event_id`, `occurred_at`),",
    "KEY `idx_iws_process_action_audit_command` (`command_key`),",
    "KEY `idx_iws_process_action_audit_schedule` (`schedule_id`, `occurred_at`),",
    "KEY `idx_iws_process_action_audit_supervisor` (`supervisor_instance_id`, `round_no`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_action_audit'), 1, 0);

SET @pp_table_exists = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = "iws_process_schedule_control");
SET @pp_ddl = IF(@pp_ddl_chain_ok = 1 AND @pp_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_process_schedule_control` (',
    "`id` bigint NOT NULL,",
    "`scope_type` varchar(16) NOT NULL,",
    "`scope_key` varchar(256) NOT NULL,",
    "`duty` varchar(32) NOT NULL,",
    "`control_state` varchar(24) NOT NULL,",
    "`desired_state` varchar(64) NULL,",
    "`observed_state` varchar(64) NULL,",
    "`revision` bigint NOT NULL DEFAULT 0,",
    "`epoch` bigint NOT NULL DEFAULT 0,",
    "`owner` varchar(128) NULL,",
    "`lease_until` datetime(3) NULL,",
    "`wake_pending` tinyint(1) NOT NULL DEFAULT 0,",
    "`round_no` bigint NOT NULL DEFAULT 0,",
    "`round_status` varchar(16) NULL,",
    "`supervisor_instance_id` bigint NULL,",
    "`definition_version_id` bigint NULL,",
    "`child_instance_id` bigint NULL,",
    "`slot_state` varchar(16) NULL,",
    "`permit_state` varchar(24) NULL,",
    "`resource_key` varchar(256) NULL,",
    "`fairness_count` int NOT NULL DEFAULT 0,",
    "`last_admitted_at` datetime(3) NULL,",
    "`last_activity_at` datetime(3) NULL,",
    "`error_message` varchar(1000) NULL,",
    "`create_dept` bigint NULL,",
    "`create_by` bigint NULL,",
    "`create_time` datetime(3) NOT NULL,",
    "`update_by` bigint NULL,",
    "`update_time` datetime(3) NOT NULL,",
    "PRIMARY KEY (`id`),",
    "UNIQUE KEY `uk_iws_process_schedule_control_scope` (`scope_type`, `scope_key`, `duty`),",
    "UNIQUE KEY `uk_iws_process_schedule_control_slot` (`supervisor_instance_id`, `child_instance_id`, `duty`),",
    "KEY `idx_iws_process_schedule_control_state` (`scope_type`, `control_state`, `last_activity_at`),",
    "KEY `idx_iws_process_schedule_control_round` (`supervisor_instance_id`, `round_no`, `round_status`),",
    "KEY `idx_iws_process_schedule_control_child` (`child_instance_id`, `slot_state`)"
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE pp_stmt FROM @pp_ddl;
EXECUTE pp_stmt;
DEALLOCATE PREPARE pp_stmt;
SET @pp_ddl_chain_ok = IF(@pp_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_process_schedule_control'), 1, 0);

-- 后置结构核验：重复比较，并额外要求 16 张表全部存在。
DELETE FROM tmp_iws_pp_conflicts;
INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, '表缺失'
FROM tmp_iws_pp_expected_tables t
LEFT JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
WHERE i.TABLE_NAME IS NULL;

INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, '表不是 InnoDB 基础表或排序规则不一致'
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
WHERE i.TABLE_TYPE <> 'BASE TABLE' OR i.ENGINE <> 'InnoDB' OR NOT (i.TABLE_COLLATION <=> 'utf8mb4_general_ci');

INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('列定义不匹配：', e.column_name)
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_pp_expected_columns e ON e.table_name = t.table_name
LEFT JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR c.ORDINAL_POSITION <> e.ordinal_position
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable)
   OR NOT (c.COLUMN_DEFAULT <=> e.column_default)
   OR NOT (c.EXTRA <=> e.extra)
   OR NOT (c.CHARACTER_SET_NAME <=> e.character_set_name)
   OR NOT (c.COLLATION_NAME <=> e.collation_name);

INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记列：', c.COLUMN_NAME)
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_pp_expected_columns e
  ON e.table_name = t.table_name AND e.column_name = c.COLUMN_NAME
WHERE e.column_name IS NULL;

INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('索引定义不匹配：', e.index_name)
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_pp_expected_indexes e ON e.table_name = t.table_name
LEFT JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
 AND s.INDEX_NAME = e.index_name AND s.SEQ_IN_INDEX = e.seq_in_index
WHERE s.INDEX_NAME IS NULL
   OR s.NON_UNIQUE <> e.non_unique
   OR NOT (s.COLUMN_NAME <=> e.column_name)
   OR NOT (s.SUB_PART <=> e.sub_part)
   OR NOT (s.INDEX_TYPE <=> e.index_type);

INSERT INTO tmp_iws_pp_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记索引：', s.INDEX_NAME)
FROM tmp_iws_pp_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_pp_expected_indexes e
  ON e.table_name = t.table_name AND e.index_name = s.INDEX_NAME AND e.seq_in_index = s.SEQ_IN_INDEX
WHERE e.index_name IS NULL;

SELECT COUNT(*) INTO @pp_conflicts FROM tmp_iws_pp_conflicts;
SET @pp_post_ok = IF(@pp_ddl_chain_ok = 1 AND @pp_preflight_ok = 1 AND @pp_conflicts = 0, 1, 0);

SELECT `表名`, `冲突说明`
FROM (
    SELECT table_name AS `表名`, reason AS `冲突说明`, 1 AS sort_no FROM tmp_iws_pp_conflicts
    UNION ALL
    SELECT '--', '无结构冲突', 2
) detail
WHERE (@pp_conflicts > 0 AND sort_no = 1) OR (@pp_conflicts = 0 AND sort_no = 2)
ORDER BY `表名`, `冲突说明`;

SELECT JSON_EXTRACT(IF(@pp_post_ok = 1, '{}', 'invalid'), '$') AS `后置结构校验`;

SELECT
    IF(@pp_post_ok = 1, '计划事件运行结构升级完成', '计划事件运行结构升级失败') AS `执行结果`,
    @upgrade_schema AS `目标库`;

SELECT table_name AS `表名`, engine AS `存储引擎`, table_collation AS `排序规则`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @upgrade_schema
  AND table_name IN ('iws_business_schedule',
      'iws_business_schedule_plan_type',
      'iws_process_definition',
      'iws_process_system_policy',
      'iws_process_state',
      'iws_process_event_definition',
      'iws_process_edge',
      'iws_process_instance',
      'iws_process_system_context',
      'iws_process_token',
      'iws_process_event',
      'iws_process_attempt',
      'iws_process_trigger_receipt',
      'iws_process_request_detail',
      'iws_process_action_audit',
      'iws_process_schedule_control')
ORDER BY table_name;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_pp_conflicts;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_pp_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_pp_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_pp_expected_tables;
