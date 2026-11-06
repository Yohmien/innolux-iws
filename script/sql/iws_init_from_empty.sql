-- ============================================================================
-- IWS+ 从空库全量初始化
-- ============================================================================
-- 目标数据库：MySQL 8.0
-- 目标状态：当前仓库标准结构、系统必要种子、WMS 核心表、计划事件平台表与菜单
-- 执行性质：一次性空库初始化；不用于存量库升级或数据迁移
--
-- 推荐从后端仓库根目录使用配套入口（目标库为唯一必填参数）：
--   pwsh -NoProfile -File script/sql/iws_init_from_empty.ps1 \
--     -Database <目标库> -Server <主机> -Port <端口> -User <账号>
--
-- 该入口在目标库不存在时以 utf8mb4_general_ci 创建；已存在时只在无任何表、
-- 视图、存储程序、触发器和事件的前提下继续，任何现存对象都会使入口停止。
-- MySQL 8.0.45 不支持在 PREPARE 中执行 USE（ERROR 1295），因此建库后选择目标库
-- 必须由 mysql 连接入口完成；wrapper 只承担这一层部署职责，不复制业务初始化逻辑。
-- 若 schema 已由部署流程创建且为空，也可直接执行本 SQL，当前连接库是唯一目标：
--   mysql --protocol=TCP --host=<主机> --port=<端口> --user=<账号> \
--     --default-character-set=utf8mb4 --database=<目标库> --batch --raw \
--     --execute="source script/sql/iws_init_from_empty.sql"
--
-- SQL 从 DATABASE() 识别当前库，确认 MySQL 8.0 且库完全为空后，自动将默认
-- 字符集/排序规则调整为 utf8mb4/utf8mb4_general_ci（需要 ALTER 权限）。
-- 未选库或已有表/视图/存储程序/触发器/事件时拒绝；不会调整非空库。
-- mysql 执行不得添加 --force；首个错误必须停止。
-- 该脚本按顺序包含：
--   1. 框架、系统、代码生成、调度、工作流与 AI 平台结构（81 张表）
--   2. WMS SMT 核心结构（66 张表）
--   3. endpoint 结构（6 张表）、唯一 CATALOG 控制行、默认环境 TEST 与 IWS 系统
--   4. process 结构（16 张表）
--   5. endpoint/process 菜单和按钮权限（53 行）
--   6. APK 发布记录表（1 张表）；菜单只由认证中心初始化脚本创建
--   7. 在库管理公司最小闭环菜单（7 行）、空间页面菜单（6 行）与业务目录页面（1 行，复用既有根）
-- 共 181 张表，其中 WMS 77 张。
-- 菜单种子共 255 行。
--
-- 种子只保留系统运行所需的默认模板配置：部门/岗位/角色/菜单/字典/参数、
-- 默认管理员、SnailJob 初始管理员与默认组、默认客户端、OSS 模板、流程分类和流程表达式、
-- AI 模型提供者、默认对话模型、SnailAI 默认应用（app_id=1）、默认 AI 助手
-- （IWS+ 助手，显式绑定默认对话模型 id=1）、默认环境 TEST（测试环境）与 IWS 系统。
-- 默认 AI 助手绑定默认对话模型，首次使用补 API Key 即可对话。
-- 不包含测试账号、演示供应商、WMS 业务单据、计划/事件/实例、真实系统地址、
-- 真实环境凭据或外部系统密钥。默认管理员及默认客户端属于初始化模板，
-- 部署后应在受控流程中立即更换；默认 SnailAI 应用 token 使用主服务 dev/prod
-- 已配置的默认接入值，属于开发环境模板，生产部署应同时更换应用记录和主服务配置。
-- 禁止把真实凭据写入本文件。
-- ============================================================================

SET NAMES utf8mb4;
SET @init_schema = DATABASE();
SET @init_schema_ok = (
    @init_schema IS NOT NULL
    AND (SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA
         WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@init_schema AS BINARY))
);
SET @init_version_ok = (VERSION() LIKE '8.0.%');
SET @init_collation_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@init_schema AS BINARY)
      AND DEFAULT_CHARACTER_SET_NAME = 'utf8mb4'
      AND DEFAULT_COLLATION_NAME = 'utf8mb4_general_ci'
);
SET @init_table_case_setting_ok = (@@lower_case_table_names IN (0, 1, 2));
SET @init_existing_tables = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@init_schema AS BINARY)
);
SET @init_existing_routines = (
    SELECT COUNT(*) FROM information_schema.ROUTINES
    WHERE CAST(ROUTINE_SCHEMA AS BINARY) = CAST(@init_schema AS BINARY)
);
SET @init_existing_triggers = (
    SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE CAST(TRIGGER_SCHEMA AS BINARY) = CAST(@init_schema AS BINARY)
);
SET @init_existing_events = (
    SELECT COUNT(*) FROM information_schema.EVENTS
    WHERE CAST(EVENT_SCHEMA AS BINARY) = CAST(@init_schema AS BINARY)
);
SET @init_empty_ok = (
    @init_schema IS NOT NULL
    AND @init_existing_tables = 0
    AND @init_existing_routines = 0
    AND @init_existing_triggers = 0
    AND @init_existing_events = 0
);
-- 仅在所有基础条件成立且目标完全为空时调整默认规则；不转换已有表。
SET @init_prepare_ok = (
    @init_schema_ok = 1
    AND @init_version_ok = 1
    AND @init_table_case_setting_ok = 1
    AND @init_empty_ok = 1
);
-- MySQL 8.0 不支持 PREPARE ALTER DATABASE；省略库名即操作当前连接库，
-- 与上方 DATABASE() 目标一致。执行器必须在门禁失败时停止，禁止 --force/GUI 续错。
SELECT JSON_EXTRACT(IF(@init_prepare_ok = 1, '{}', 'invalid'), '$') AS `空库调整前校验`;
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SET @init_collation_ok = (
    SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@init_schema AS BINARY)
      AND DEFAULT_CHARACTER_SET_NAME = 'utf8mb4'
      AND DEFAULT_COLLATION_NAME = 'utf8mb4_general_ci'
);
SET @init_preflight_ok = (
    @init_schema_ok = 1
    AND @init_version_ok = 1
    AND @init_collation_ok = 1
    AND @init_table_case_setting_ok = 1
    AND @init_empty_ok = 1
);

SELECT
    @init_schema AS `目标库`,
    @init_schema_ok AS `目标库存在`,
    @init_version_ok AS `MySQL8版本`,
    @init_collation_ok AS `utf8mb4_general_ci`,
    @init_table_case_setting_ok AS `表名大小写设置合法`,
    @init_existing_tables AS `已有表或视图数`,
    @init_existing_routines AS `已有存储程序数`,
    @init_existing_triggers AS `已有触发器数`,
    @init_existing_events AS `已有事件数`,
    IF(@init_preflight_ok = 1, '空库前置校验通过', '前置校验失败，禁止初始化') AS `执行说明`;

SELECT JSON_EXTRACT(IF(@init_preflight_ok = 1, '{}', 'invalid'), '$') AS `空库前置校验`;

-- ----------------------------------------------------------------------------
-- 第一部分：框架、系统、代码生成、调度、工作流与 AI 平台结构
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
-- 框架与 WMS 表结构：2026-10-23 在独立 MySQL 8.0.45 空库执行当前仓库
-- iws_init.sql 与 iws_wms_smt_core.sql 后导出；不代表任何真实部署库状态
-- 2026-10-27 追加公司最小闭环 5 张表与空间 S1 仓库父链；S1 只加可空引用和地址列，
-- 2026-10-28 同版纳入在库空间升级控制 5 张表与库存业务类型目录 2 张表；
-- 定义取自 upgrade_control 与 type_catalog 升级脚本在 MySQL 8.0.45 的目标态，
-- 不写入升级运行、检查点、映射、问题或目录样例数据。
-- 2026-10-29 同版内联空间、访问通道、位置版本、可见范围与容量 10 张目标表；
-- 只建结构，不写业务、容量或权限种子。
-- ----------------------------------------------------------------------------
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_category` (
  `category_id` bigint NOT NULL COMMENT '流程分类ID',
  `parent_id` bigint DEFAULT '0' COMMENT '父流程分类id',
  `ancestors` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '祖级列表',
  `category_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程分类名称',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程分类';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_definition` (
  `id` bigint NOT NULL COMMENT '主键id',
  `flow_code` varchar(40) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程编码',
  `flow_name` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程名称',
  `model_value` varchar(40) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'CLASSICS' COMMENT '设计器模型（CLASSICS经典模型 MIMIC仿钉钉模型）',
  `category` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '流程类别',
  `version` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程版本',
  `is_publish` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否发布（0未发布 1已发布 9失效）',
  `form_custom` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
  `form_path` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批表单路径',
  `activity_status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '流程激活状态（0挂起 1激活）',
  `listener_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '监听器类型',
  `listener_path` varchar(400) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '监听器路径',
  `ext` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '业务详情 存业务表对象json字符串',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程定义表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_his_task` (
  `id` bigint NOT NULL COMMENT '主键id',
  `definition_id` bigint NOT NULL COMMENT '对应flow_definition表的id',
  `instance_id` bigint NOT NULL COMMENT '对应flow_instance表的id',
  `task_id` bigint NOT NULL COMMENT '对应flow_task表的id',
  `node_code` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '开始节点编码',
  `node_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '开始节点名称',
  `node_type` tinyint(1) DEFAULT NULL COMMENT '开始节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `target_node_code` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '目标节点编码',
  `target_node_name` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '结束节点名称',
  `approver` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批人',
  `cooperate_type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '协作方式(1审批 2转办 3委派 4会签 5票签 6加签 7减签)',
  `collaborator` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '协作人',
  `skip_type` varchar(10) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流转类型（PASS通过 REJECT退回 NONE无动作）',
  `flow_status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
  `form_custom` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
  `form_path` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批表单路径',
  `message` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批意见',
  `variable` text COLLATE utf8mb4_general_ci COMMENT '任务变量',
  `ext` text COLLATE utf8mb4_general_ci COMMENT '业务详情 存业务表对象json字符串',
  `create_time` datetime DEFAULT NULL COMMENT '任务开始时间',
  `update_time` datetime DEFAULT NULL COMMENT '审批完成时间',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='历史任务记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_instance` (
  `id` bigint NOT NULL COMMENT '主键id',
  `definition_id` bigint NOT NULL COMMENT '对应flow_definition表的id',
  `business_id` varchar(40) COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务id',
  `node_type` tinyint(1) NOT NULL COMMENT '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `node_code` varchar(40) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程节点编码',
  `node_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '流程节点名称',
  `variable` text COLLATE utf8mb4_general_ci COMMENT '任务变量',
  `flow_status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
  `activity_status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '流程激活状态（0挂起 1激活）',
  `def_json` text COLLATE utf8mb4_general_ci COMMENT '流程定义json',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `ext` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '扩展字段，预留给业务系统使用',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程实例表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_instance_biz_ext` (
  `id` bigint NOT NULL COMMENT '主键id',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `business_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '业务编码',
  `business_title` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '业务标题',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `instance_id` bigint DEFAULT NULL COMMENT '流程实例Id',
  `business_id` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '业务Id',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程实例业务扩展表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_node` (
  `id` bigint NOT NULL COMMENT '主键id',
  `node_type` tinyint(1) NOT NULL COMMENT '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `definition_id` bigint NOT NULL COMMENT '流程定义id',
  `node_code` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程节点编码',
  `node_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '流程节点名称',
  `permission_flag` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限标识（权限类型:权限标识，可以多个，用@@隔开)',
  `node_ratio` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '流程签署比例值',
  `coordinate` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '坐标',
  `any_node_skip` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '任意结点跳转',
  `listener_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '监听器类型',
  `listener_path` varchar(400) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '监听器路径',
  `form_custom` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
  `form_path` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批表单路径',
  `version` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '版本',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `ext` text COLLATE utf8mb4_general_ci COMMENT '节点扩展属性',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程节点表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_skip` (
  `id` bigint NOT NULL COMMENT '主键id',
  `definition_id` bigint NOT NULL COMMENT '流程定义id',
  `now_node_code` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '当前流程节点的编码',
  `now_node_type` tinyint(1) DEFAULT NULL COMMENT '当前节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `next_node_code` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '下一个流程节点的编码',
  `next_node_type` tinyint(1) DEFAULT NULL COMMENT '下一个节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `skip_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '跳转名称',
  `skip_type` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '跳转类型（PASS审批通过 REJECT退回）',
  `skip_condition` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '跳转条件',
  `coordinate` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '坐标',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='节点跳转关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_spel` (
  `id` bigint NOT NULL COMMENT '主键id',
  `component_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组件名称',
  `method_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '方法名',
  `method_params` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '参数',
  `view_spel` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '预览spel表达式',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程spel表达式定义表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_task` (
  `id` bigint NOT NULL COMMENT '主键id',
  `definition_id` bigint NOT NULL COMMENT '对应flow_definition表的id',
  `instance_id` bigint NOT NULL COMMENT '对应flow_instance表的id',
  `node_code` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '节点编码',
  `node_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '节点名称',
  `node_type` tinyint(1) NOT NULL COMMENT '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）',
  `flow_status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）',
  `form_custom` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '审批表单是否自定义（Y是 N否）',
  `form_path` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审批表单路径',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新人',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='待办任务表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flow_user` (
  `id` bigint NOT NULL COMMENT '主键id',
  `type` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '人员类型（1待办任务的审批人权限 2待办任务的转办人权限 3待办任务的委托人权限）',
  `processed_by` varchar(80) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限人',
  `associated` bigint NOT NULL COMMENT '任务表id',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` varchar(80) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建人',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `tenant_id` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '租户id',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `user_processed_type` (`processed_by`,`type`),
  KEY `user_associated` (`associated`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='流程用户表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gen_table` (
  `table_id` bigint NOT NULL COMMENT '编号',
  `data_name` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '数据源名称',
  `table_name` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '表名称',
  `table_comment` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '表描述',
  `class_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '实体类名称',
  `tpl_category` varchar(200) COLLATE utf8mb4_general_ci DEFAULT 'crud' COMMENT '使用的模板（crud单表操作 tree树表操作）',
  `frontend_type` varchar(50) COLLATE utf8mb4_general_ci DEFAULT 'vue' COMMENT '前端模板类型，对应 vm 下的模板目录',
  `package_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成包路径',
  `module_name` varchar(30) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成模块名',
  `business_name` varchar(30) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成业务名',
  `function_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成功能名',
  `function_author` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成功能作者',
  `gen_type` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '生成代码方式（0zip压缩包 1自定义路径）',
  `gen_path` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '/' COMMENT '生成路径（不填默认项目路径）',
  `options` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '其它生成选项',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='代码生成业务表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gen_table_column` (
  `column_id` bigint NOT NULL COMMENT '编号',
  `table_id` bigint DEFAULT NULL COMMENT '归属表编号',
  `column_name` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '列名称',
  `column_comment` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '列描述',
  `column_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '列类型',
  `java_type` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'JAVA类型',
  `java_field` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'JAVA字段名',
  `is_pk` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否主键（1是）',
  `is_increment` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否自增（1是）',
  `is_required` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否必填（1是）',
  `is_insert` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否为插入字段（1是）',
  `is_edit` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否编辑字段（1是）',
  `is_list` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否列表字段（1是）',
  `is_query` char(1) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否查询字段（1是）',
  `query_type` varchar(200) COLLATE utf8mb4_general_ci DEFAULT 'EQ' COMMENT '查询方式（等于、不等于、大于、小于、范围）',
  `html_type` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）',
  `dict_type` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `sort` int DEFAULT NULL COMMENT '排序',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`column_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='代码生成业务表字段';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_agent` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '智能体名称',
  `description` text COLLATE utf8mb4_unicode_ci COMMENT '智能体描述',
  `avatar` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像URL',
  `instruction` text COLLATE utf8mb4_unicode_ci COMMENT '系统指令(System Prompt)',
  `greeting` text COLLATE utf8mb4_unicode_ci COMMENT '欢迎语',
  `preset_questions` text COLLATE utf8mb4_unicode_ci COMMENT '预设问题列表（JSON数组字符串）',
  `chat_model_id` bigint DEFAULT NULL COMMENT '关联的对话模型ID',
  `memory_enabled` tinyint(1) DEFAULT '0' COMMENT '是否启用记忆库',
  `mcp_enabled` tinyint(1) DEFAULT '0' COMMENT '是否启用MCP',
  `skill_enabled` tinyint(1) DEFAULT '0' COMMENT '是否启用Skill',
  `web_search_enabled` tinyint(1) DEFAULT '0' COMMENT '是否启用联网搜索',
  `rag_enabled` tinyint(1) DEFAULT '0' COMMENT '是否启用RAG',
  `rag_ids` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '绑定的RAG ID列表，逗号分隔，最多5个',
  `rag_call_mode` tinyint DEFAULT '1' COMMENT 'RAG调用方式: 1=智能调用 2=强制调用',
  `short_term_memory_size` int DEFAULT '20' COMMENT '短期记忆滑动窗口保留条数',
  `creator_id` bigint DEFAULT NULL COMMENT '创建者用户ID',
  `is_featured` tinyint(1) DEFAULT '0' COMMENT '是否精选',
  `view_count` int DEFAULT '0' COMMENT '浏览次数',
  `status` tinyint DEFAULT '1' COMMENT '状态: 1-活跃 2-非活跃 3-已废弃 4-已禁用',
  `config` text COLLATE utf8mb4_unicode_ci COMMENT '扩展配置(预留)',
  `app_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联应用ID(NULL=本地执行)',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent_creator` (`creator_id`),
  KEY `idx_agent_featured` (`is_featured`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_agent_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `conversation_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对话ID(UUID)',
  `title` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '对话标题',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conv_id` (`conversation_id`),
  KEY `idx_agent_conv_agent` (`agent_id`),
  KEY `idx_agent_conv_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体对话表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_agent_conversation_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `conversation_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT 'user' COMMENT 'user/assistant',
  `content` text COLLATE utf8mb4_unicode_ci COMMENT '消息内容',
  `thinking` text COLLATE utf8mb4_unicode_ci COMMENT '思考过程（仅assistant）',
  `metadata` longtext COLLATE utf8mb4_unicode_ci COMMENT '消息扩展元数据JSON',
  `status` int DEFAULT '1' COMMENT '1=成功,2=失败,3=进行中',
  `input_tokens` int DEFAULT '0' COMMENT '输入Token数（prompt）',
  `output_tokens` int DEFAULT '0' COMMENT '输出Token数（completion）',
  `cache_tokens` int DEFAULT '0' COMMENT '缓存命中Token数',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent_rec_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体对话消息记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_agent_mcp_server` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `mcp_server_id` bigint NOT NULL COMMENT 'MCP服务ID',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_mcp` (`agent_id`,`mcp_server_id`),
  KEY `idx_agent_mcp_agent` (`agent_id`),
  KEY `idx_agent_mcp_server` (`mcp_server_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体MCP服务关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_agent_skill` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `skill_id` bigint NOT NULL COMMENT 'Skill ID',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_skill` (`agent_id`,`skill_id`),
  KEY `idx_agent_skill_agent` (`agent_id`),
  KEY `idx_agent_skill_skill` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体Skill关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_agent_usage_stat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `message_count` int DEFAULT '0' COMMENT '消息条数',
  `conversation_count` int DEFAULT '0' COMMENT '对话轮次',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_user_date` (`agent_id`,`user_id`,`stat_date`),
  KEY `idx_usage_agent` (`agent_id`),
  KEY `idx_usage_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体使用统计';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_app` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '应用唯一标识',
  `app_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '应用名称',
  `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '应用描述',
  `token` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '通信认证令牌',
  `route_strategy` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT 'LEAST_LOAD' COMMENT '路由策略',
  `status` tinyint(1) DEFAULT '1' COMMENT '1=启用, 0=停用',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_id` (`app_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户端应用';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_client_node` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属应用ID',
  `host_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户端实例唯一标识',
  `host_ip` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户端IP',
  `grpc_port` int NOT NULL COMMENT '客户端gRPC端口',
  `max_concurrent` int DEFAULT '10' COMMENT '最大并发对话数',
  `active_chats` int DEFAULT '0' COMMENT '当前活跃对话数',
  `supported_providers` text COLLATE utf8mb4_unicode_ci COMMENT '支持的模型提供商(JSON数组)',
  `labels` text COLLATE utf8mb4_unicode_ci COMMENT '路由标签',
  `expire_dt` datetime NOT NULL COMMENT '过期时间(心跳更新)',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_client_node` (`app_id`,`host_id`),
  KEY `idx_app_expire` (`app_id`,`expire_dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI客户端实例节点';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_mcp_server` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MCP服务名称',
  `description` text COLLATE utf8mb4_unicode_ci COMMENT 'MCP服务描述',
  `transport_type` tinyint(1) DEFAULT '1' COMMENT '传输类型: 1-SSE 2-Streamable HTTP 3-Stdio',
  `base_uri` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '服务基础地址(SSE/Streamable HTTP时使用)',
  `endpoint` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '端点路径(SSE/Streamable HTTP时可选)',
  `command` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stdio命令(Stdio时必填)',
  `args` text COLLATE utf8mb4_unicode_ci COMMENT 'Stdio命令参数(JSON数组)',
  `env_vars` text COLLATE utf8mb4_unicode_ci COMMENT 'Stdio环境变量(JSON对象)',
  `timeout` bigint DEFAULT '60000' COMMENT '超时时间(毫秒)',
  `headers` text COLLATE utf8mb4_unicode_ci COMMENT '请求头(JSON对象)',
  `last_connect_dt` timestamp NULL DEFAULT NULL COMMENT '最后连接时间',
  `creator_id` bigint DEFAULT NULL COMMENT '创建者用户ID',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mcp_server_creator` (`creator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP服务配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_model_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider_id` bigint NOT NULL COMMENT '提供商ID',
  `model_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型名称',
  `model_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型标识符',
  `model_type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型类型(CHAT/EMBEDDING/RERANKER/IMAGE/SPEECH)',
  `adapter_key` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '底层协议适配器标识(openai-compatible/http等)',
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型描述',
  `api_key` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API密钥(加密存储)',
  `api_endpoint` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API端点URL',
  `config_json` text COLLATE utf8mb4_unicode_ci COMMENT '模型参数配置(JSON格式)',
  `owner_id` bigint DEFAULT NULL COMMENT '所有者ID(NULL=全局,具体值=用户ID)',
  `scope` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'GLOBAL' COMMENT '作用域(GLOBAL/PERSONAL)',
  `is_default` tinyint(1) DEFAULT '0' COMMENT '是否为默认模型',
  `is_enabled` tinyint(1) DEFAULT '1' COMMENT '是否启用',
  `created_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `fk_provider_id` (`provider_id`),
  KEY `idx_provider_model_type` (`provider_id`,`model_type`),
  KEY `idx_model_type_enabled` (`model_type`,`is_enabled`),
  KEY `idx_owner_id` (`owner_id`),
  KEY `idx_is_default` (`is_default`),
  KEY `idx_scope` (`scope`),
  KEY `idx_model_key` (`model_key`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI模型配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_model_provider` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提供商名称',
  `provider_key` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提供商标识符',
  `description` text COLLATE utf8mb4_unicode_ci COMMENT '提供商描述',
  `icon_url` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'LOGO图标URL',
  `is_enabled` tinyint(1) DEFAULT '1' COMMENT '是否启用',
  `created_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_name` (`provider_name`),
  UNIQUE KEY `uk_provider_key` (`provider_key`),
  KEY `idx_provider_key` (`provider_key`),
  KEY `idx_is_enabled` (`is_enabled`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI模型提供商表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_model_usage_stat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model_id` bigint NOT NULL COMMENT '模型ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `total_calls` bigint DEFAULT '0' COMMENT '总调用次数',
  `success_calls` bigint DEFAULT '0' COMMENT '成功调用次数',
  `failed_calls` bigint DEFAULT '0' COMMENT '失败调用次数',
  `total_tokens_used` bigint DEFAULT '0' COMMENT '总Token使用量',
  `total_cost` decimal(18,8) DEFAULT '0.00000000' COMMENT '总费用',
  `avg_response_time` bigint DEFAULT '0' COMMENT '平均响应时间(毫秒)',
  `last_used_dt` timestamp NULL DEFAULT NULL COMMENT '最后使用时间',
  `created_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_model_user` (`model_id`,`user_id`),
  KEY `fk_stat_model_id` (`model_id`),
  KEY `idx_model_id` (`model_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_last_used_dt` (`last_used_dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型使用统计表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_openapi_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '关联 sai_app.app_id',
  `open_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '平台分配的唯一标识（UUID）',
  `platform_user_id` bigint NOT NULL COMMENT '关联 sai_user.id，注册时自动创建',
  `external_id` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '外部系统的用户标识（可选，幂等用）',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_open` (`app_id`,`open_id`),
  UNIQUE KEY `uk_app_external` (`app_id`,`external_id`),
  KEY `idx_open_id` (`open_id`),
  KEY `idx_platform_user` (`platform_user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OpenAPI 外部用户映射表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_rag` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `icon` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `embedding_model_id` bigint NOT NULL,
  `dimension_of_vector_model` int NOT NULL COMMENT '向量维度',
  `rerank_model_id` bigint DEFAULT NULL,
  `search_engine_instance_id` bigint DEFAULT NULL,
  `vector_store_instance_id` bigint DEFAULT NULL,
  `search_engine_enable` tinyint(1) DEFAULT '0',
  `delimiter` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT '\n\n',
  `rag_enhancement` text COLLATE utf8mb4_unicode_ci,
  `config` text COLLATE utf8mb4_unicode_ci COMMENT 'RAG检索和问答的页面配置参数',
  `dedup_strategy` tinyint(1) NOT NULL DEFAULT '2' COMMENT '去重策略: 0=NONE 1=BY_NAME 2=BY_CONTENT 3=BY_NAME_OR_CONTENT',
  `dedup_action` tinyint(1) NOT NULL DEFAULT '0' COMMENT '冲突动作: 0=REJECT 1=SKIP 2=OVERWRITE',
  `upload_confirm` tinyint(1) NOT NULL DEFAULT '1' COMMENT '上传前二次确认: 0-关 1-开',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_rag_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rag_id` bigint NOT NULL,
  `document_id` bigint NOT NULL,
  `paragraph_index` int DEFAULT NULL,
  `chunk_index` int DEFAULT NULL,
  `content` text COLLATE utf8mb4_unicode_ci,
  `token_count` int DEFAULT NULL,
  `vector_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'chunk内容SHA-256，用于向量去重',
  `source_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'TEXT' COMMENT 'chunk来源类型：TEXT文本、IMAGE图片',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rag_chunk_rag` (`rag_id`),
  KEY `idx_rag_chunk_document` (`document_id`),
  KEY `idx_chunk_rag_hash` (`rag_id`,`content_hash`),
  KEY `idx_chunk_rag_source_type` (`rag_id`,`source_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_rag_document` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rag_id` bigint NOT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `file_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_path` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `storage_path` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `storage_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT 'LOCAL',
  `file_size` bigint DEFAULT '0',
  `content` text COLLATE utf8mb4_unicode_ci,
  `status` tinyint(1) DEFAULT '0' COMMENT '状态: 0-待处理 1-解析中 2-处理中 3-处理完成 4-处理失败',
  `error_msg` text COLLATE utf8mb4_unicode_ci,
  `chunk_count` int DEFAULT '0',
  `page_count` int DEFAULT '0' COMMENT 'Docling parse page count',
  `element_count` int DEFAULT '0' COMMENT 'Docling parse element count',
  `table_count` int DEFAULT '0' COMMENT 'Docling parse table count',
  `image_count` int DEFAULT '0' COMMENT 'Docling parse image count',
  `parse_time` int DEFAULT '0' COMMENT 'Parse duration in milliseconds',
  `md_content` longtext COLLATE utf8mb4_unicode_ci COMMENT 'Docling markdown content',
  `doc_metadata` longtext COLLATE utf8mb4_unicode_ci COMMENT 'Docling document metadata JSON',
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件内容SHA-256哈希，用于去重',
  `resource_id` bigint DEFAULT NULL COMMENT '关联资源库 sai_resource.id',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rag_doc_rag` (`rag_id`),
  KEY `idx_rag_content_hash` (`rag_id`,`content_hash`),
  KEY `idx_rag_name` (`rag_id`,`name`),
  KEY `idx_rag_doc_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_rag_document_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rag_id` bigint NOT NULL,
  `document_id` bigint NOT NULL,
  `chunk_id` bigint DEFAULT NULL,
  `resource_id` bigint DEFAULT NULL,
  `image_index` int DEFAULT NULL,
  `image_url` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `caption` text COLLATE utf8mb4_unicode_ci,
  `figure_no` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `figure_title` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `section_title` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_page` int DEFAULT NULL,
  `document_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ocr_text` text COLLATE utf8mb4_unicode_ci,
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rag_doc_image_rag` (`rag_id`),
  KEY `idx_rag_doc_image_document` (`document_id`),
  KEY `idx_rag_doc_image_chunk` (`chunk_id`),
  KEY `idx_rag_doc_image_resource` (`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_resource` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `storage_key` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '存储键（相对路径或对象Key）',
  `original_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '原始文件名',
  `file_size` bigint DEFAULT '0' COMMENT '文件大小(bytes)',
  `mime_type` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'MIME类型',
  `storage_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LOCAL' COMMENT '存储类型: LOCAL/MINIO',
  `access_url` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '访问URL',
  `biz_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'GENERAL' COMMENT '业务类型: AVATAR/ATTACHMENT/DOCUMENT/GENERAL',
  `biz_id` bigint DEFAULT NULL COMMENT '关联业务ID',
  `creator_id` bigint DEFAULT NULL COMMENT '上传者ID',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_key` (`storage_key`),
  KEY `idx_biz` (`biz_type`,`biz_id`),
  KEY `idx_creator` (`creator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通用资源存储';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_skill` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Skill名称(从SKILL.md解析)',
  `description` text COLLATE utf8mb4_unicode_ci COMMENT 'Skill描述(从SKILL.md解析)',
  `file_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上传的zip文件名',
  `file_path` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '解压后存储路径',
  `file_size` bigint DEFAULT '0' COMMENT '文件大小(字节)',
  `skill_content` longtext COLLATE utf8mb4_unicode_ci COMMENT 'SKILL.md正文内容(去除frontmatter)',
  `storage_path` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '对象存储相对路径前缀（如 skills/123/）',
  `version` bigint DEFAULT '0' COMMENT '版本号，文件变更时自增，用于缓存一致性校验',
  `has_files` tinyint(1) DEFAULT '0' COMMENT '是否包含支撑文件（0=仅SKILL.md，1=有scripts/references等）',
  `creator_id` bigint DEFAULT NULL COMMENT '创建者用户ID',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_skill_creator` (`creator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill技能包表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_skill_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `skill_id` bigint NOT NULL COMMENT 'Skill ID',
  `file_path` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件相对路径',
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件内容',
  `file_size` int NOT NULL COMMENT '文件大小(字节)',
  `encoding` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'utf-8' COMMENT '编码方式',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_path` (`skill_id`,`file_path`) COMMENT '同一Skill不能有重复的文件路径',
  KEY `idx_skill_id` (`skill_id`) COMMENT '技能ID索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill支撑文件内容表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_store_instance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '实例名称',
  `category` tinyint(1) NOT NULL COMMENT '分类: 1-向量库 2-搜索引擎',
  `type` tinyint(1) NOT NULL COMMENT '类型: 1-PG_VECTOR 2-MILVUS 3-ELASTICSEARCH 4-PG_FULLTEXT',
  `config` text COLLATE utf8mb4_unicode_ci COMMENT '连接参数 JSON',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态: 0-停用 1-启用',
  `is_default` tinyint(1) DEFAULT '0' COMMENT '是否为该 category 下默认实例',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_store_instance_category` (`category`),
  KEY `idx_store_instance_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='存储实例';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role` int DEFAULT NULL,
  `totals` int DEFAULT NULL,
  `username` varchar(255) DEFAULT NULL,
  `nickname` varchar(128) DEFAULT NULL COMMENT '用户昵称',
  `email` varchar(64) DEFAULT NULL,
  `password` varchar(255) NOT NULL,
  `resource_id` bigint DEFAULT NULL COMMENT '头像资源ID，关联 sai_resource.id',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sai_user_agent` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `create_dt` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_agent` (`user_id`,`agent_id`),
  KEY `idx_user_agent_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户订阅的智能体';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_distributed_lock` (
  `name` varchar(64) NOT NULL COMMENT '锁名称',
  `lock_until` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '锁定时长',
  `locked_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '锁定时间',
  `locked_by` varchar(255) NOT NULL COMMENT '锁定者',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='锁定表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_group_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL DEFAULT '' COMMENT '组名称',
  `description` varchar(256) NOT NULL DEFAULT '' COMMENT '组描述',
  `token` varchar(64) NOT NULL DEFAULT 'SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT' COMMENT 'token',
  `group_status` tinyint NOT NULL DEFAULT '0' COMMENT '组状态 0、未启用 1、启用',
  `version` int NOT NULL COMMENT '版本号',
  `group_partition` int NOT NULL COMMENT '分区',
  `id_generator_mode` tinyint NOT NULL DEFAULT '1' COMMENT '唯一id生成模式 默认号段模式',
  `init_scene` tinyint NOT NULL DEFAULT '0' COMMENT '是否初始化场景 0:否 1:是',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_namespace_id_group_name` (`namespace_id`,`group_name`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组配置';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_job` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `biz_id` varchar(64) NOT NULL COMMENT '业务ID',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `job_name` varchar(64) NOT NULL COMMENT '名称',
  `args_str` text COMMENT '执行方法参数',
  `args_type` tinyint NOT NULL DEFAULT '1' COMMENT '参数类型 ',
  `next_trigger_at` bigint NOT NULL COMMENT '下次触发时间',
  `job_status` tinyint NOT NULL DEFAULT '1' COMMENT '任务状态 0、关闭、1、开启',
  `task_type` tinyint NOT NULL DEFAULT '1' COMMENT '任务类型 1、集群 2、广播 3、切片',
  `route_key` tinyint NOT NULL DEFAULT '4' COMMENT '路由策略',
  `executor_type` tinyint NOT NULL DEFAULT '1' COMMENT '执行器类型',
  `executor_info` varchar(255) DEFAULT NULL COMMENT '执行器名称',
  `trigger_type` tinyint NOT NULL COMMENT '触发类型 1.CRON 表达式 2. 固定时间',
  `trigger_interval` varchar(255) NOT NULL COMMENT '间隔时长',
  `block_strategy` tinyint NOT NULL DEFAULT '1' COMMENT '阻塞策略 1、丢弃 2、覆盖 3、并行 4、恢复',
  `executor_timeout` int NOT NULL DEFAULT '0' COMMENT '任务执行超时时间，单位秒',
  `max_retry_times` int NOT NULL DEFAULT '0' COMMENT '最大重试次数',
  `parallel_num` int NOT NULL DEFAULT '1' COMMENT '并行数',
  `retry_interval` int NOT NULL DEFAULT '0' COMMENT '重试间隔(s)',
  `bucket_index` int NOT NULL DEFAULT '0' COMMENT 'bucket',
  `resident` tinyint NOT NULL DEFAULT '0' COMMENT '是否是常驻任务',
  `notify_ids` varchar(128) NOT NULL DEFAULT '' COMMENT '通知告警场景配置id列表',
  `owner_id` bigint DEFAULT NULL COMMENT '负责人id',
  `labels` varchar(512) DEFAULT '' COMMENT '标签',
  `description` varchar(256) NOT NULL DEFAULT '' COMMENT '描述',
  `ext_attrs` varchar(256) DEFAULT '' COMMENT '扩展字段',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 1、删除',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sj_job_01` (`namespace_id`,`biz_id`),
  KEY `idx_namespace_id_group_name` (`namespace_id`,`group_name`),
  KEY `idx_job_status_bucket_index` (`job_status`,`bucket_index`),
  KEY `idx_create_dt` (`create_dt`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务信息';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_job_executor` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `executor_info` varchar(256) NOT NULL COMMENT '任务执行器名称',
  `executor_type` varchar(3) NOT NULL COMMENT '1:java 2:python 3:go',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_namespace_id_group_name` (`namespace_id`,`group_name`),
  KEY `idx_create_dt` (`create_dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务执行器信息';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_job_log_message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `job_id` bigint NOT NULL COMMENT '任务信息id',
  `task_batch_id` bigint NOT NULL COMMENT '任务批次id',
  `task_id` bigint NOT NULL COMMENT '调度任务id',
  `message` longtext NOT NULL COMMENT '调度信息',
  `log_num` int NOT NULL DEFAULT '1' COMMENT '日志数量',
  `real_time` bigint NOT NULL DEFAULT '0' COMMENT '上报时间',
  `ext_attrs` varchar(256) DEFAULT '' COMMENT '扩展字段',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_batch_id_task_id` (`task_batch_id`,`task_id`),
  KEY `idx_create_dt` (`create_dt`),
  KEY `idx_namespace_id_group_name` (`namespace_id`,`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调度日志';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_job_summary` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL DEFAULT '' COMMENT '组名称',
  `business_id` bigint NOT NULL COMMENT '业务id (job_id或workflow_id)',
  `system_task_type` tinyint NOT NULL DEFAULT '3' COMMENT '任务类型 3、JOB任务 4、WORKFLOW任务',
  `trigger_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '统计时间',
  `success_num` int NOT NULL DEFAULT '0' COMMENT '执行成功-日志数量',
  `fail_num` int NOT NULL DEFAULT '0' COMMENT '执行失败-日志数量',
  `fail_reason` varchar(512) NOT NULL DEFAULT '' COMMENT '失败原因',
  `stop_num` int NOT NULL DEFAULT '0' COMMENT '执行失败-日志数量',
  `stop_reason` varchar(512) NOT NULL DEFAULT '' COMMENT '失败原因',
  `cancel_num` int NOT NULL DEFAULT '0' COMMENT '执行失败-日志数量',
  `cancel_reason` varchar(512) NOT NULL DEFAULT '' COMMENT '失败原因',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trigger_at_system_task_type_business_id` (`trigger_at`,`system_task_type`,`business_id`) USING BTREE,
  KEY `idx_namespace_id_group_name_business_id` (`namespace_id`,`group_name`,`business_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DashBoard_Job';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_job_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `job_id` bigint NOT NULL COMMENT '任务信息id',
  `task_batch_id` bigint NOT NULL COMMENT '调度任务id',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父执行器id',
  `task_status` tinyint NOT NULL DEFAULT '0' COMMENT '执行的状态 0、失败 1、成功',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
  `mr_stage` tinyint DEFAULT NULL COMMENT '动态分片所处阶段 1:map 2:reduce 3:mergeReduce',
  `leaf` tinyint NOT NULL DEFAULT '1' COMMENT '叶子节点',
  `task_name` varchar(255) NOT NULL DEFAULT '' COMMENT '任务名称',
  `client_info` varchar(128) DEFAULT NULL COMMENT '客户端地址 clientId#ip:port',
  `wf_context` text COMMENT '工作流全局上下文',
  `result_message` text NOT NULL COMMENT '执行结果',
  `args_str` text COMMENT '执行方法参数',
  `args_type` tinyint NOT NULL DEFAULT '1' COMMENT '参数类型 ',
  `ext_attrs` varchar(256) DEFAULT '' COMMENT '扩展字段',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_batch_id_task_status` (`task_batch_id`,`task_status`),
  KEY `idx_create_dt` (`create_dt`),
  KEY `idx_namespace_id_group_name` (`namespace_id`,`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务实例';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_job_task_batch` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `job_id` bigint NOT NULL COMMENT '任务id',
  `workflow_node_id` bigint NOT NULL DEFAULT '0' COMMENT '工作流节点id',
  `parent_workflow_node_id` bigint NOT NULL DEFAULT '0' COMMENT '工作流任务父批次id',
  `workflow_task_batch_id` bigint NOT NULL DEFAULT '0' COMMENT '工作流任务批次id',
  `task_batch_status` tinyint NOT NULL DEFAULT '0' COMMENT '任务批次状态 0、失败 1、成功',
  `operation_reason` tinyint NOT NULL DEFAULT '0' COMMENT '操作原因',
  `execution_at` bigint NOT NULL DEFAULT '0' COMMENT '任务执行时间',
  `system_task_type` tinyint NOT NULL DEFAULT '3' COMMENT '任务类型 3、JOB任务 4、WORKFLOW任务',
  `parent_id` varchar(64) NOT NULL DEFAULT '' COMMENT '父节点',
  `ext_attrs` varchar(256) DEFAULT '' COMMENT '扩展字段',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 1、删除',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_job_id_task_batch_status` (`job_id`,`task_batch_status`),
  KEY `idx_create_dt` (`create_dt`),
  KEY `idx_namespace_id_group_name` (`namespace_id`,`group_name`),
  KEY `idx_workflow_task_batch_id_workflow_node_id` (`workflow_task_batch_id`,`workflow_node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务批次';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_namespace` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) NOT NULL COMMENT '名称',
  `unique_id` varchar(64) NOT NULL COMMENT '唯一id',
  `description` varchar(256) NOT NULL DEFAULT '' COMMENT '描述',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 1、删除',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_unique_id` (`unique_id`),
  KEY `idx_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='命名空间';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_notify_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `notify_name` varchar(64) NOT NULL DEFAULT '' COMMENT '通知名称',
  `system_task_type` tinyint NOT NULL DEFAULT '3' COMMENT '任务类型 1. 重试任务 2. 重试回调 3、JOB任务 4、WORKFLOW任务',
  `notify_status` tinyint NOT NULL DEFAULT '0' COMMENT '通知状态 0、未启用 1、启用',
  `recipient_ids` varchar(128) NOT NULL COMMENT '接收人id列表',
  `notify_threshold` int NOT NULL DEFAULT '0' COMMENT '通知阈值',
  `notify_scene` tinyint NOT NULL DEFAULT '0' COMMENT '通知场景',
  `rate_limiter_status` tinyint NOT NULL DEFAULT '0' COMMENT '限流状态 0、未启用 1、启用',
  `rate_limiter_threshold` int NOT NULL DEFAULT '0' COMMENT '每秒限流阈值',
  `description` varchar(256) NOT NULL DEFAULT '' COMMENT '描述',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_namespace_id_group_name_scene_name` (`namespace_id`,`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知配置';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_notify_recipient` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `recipient_name` varchar(64) NOT NULL COMMENT '接收人名称',
  `notify_type` tinyint NOT NULL DEFAULT '0' COMMENT '通知类型 1、钉钉 2、邮件 3、企业微信 4 飞书 5 webhook',
  `notify_attribute` varchar(512) NOT NULL COMMENT '配置属性',
  `description` varchar(256) NOT NULL DEFAULT '' COMMENT '描述',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_namespace_id` (`namespace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警通知接收人';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_retry` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `group_id` bigint NOT NULL COMMENT '组Id',
  `scene_name` varchar(64) NOT NULL COMMENT '场景名称',
  `scene_id` bigint NOT NULL COMMENT '场景ID',
  `idempotent_id` varchar(64) NOT NULL COMMENT '幂等id',
  `biz_no` varchar(64) NOT NULL DEFAULT '' COMMENT '业务编号',
  `executor_name` varchar(512) NOT NULL DEFAULT '' COMMENT '执行器名称',
  `args_str` text NOT NULL COMMENT '执行方法参数',
  `ext_attrs` text NOT NULL COMMENT '扩展字段',
  `serializer_name` varchar(32) NOT NULL DEFAULT 'jackson' COMMENT '执行方法参数序列化器名称',
  `next_trigger_at` bigint NOT NULL COMMENT '下次触发时间',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
  `retry_status` tinyint NOT NULL DEFAULT '0' COMMENT '重试状态 0、重试中 1、成功 2、最大重试次数',
  `task_type` tinyint NOT NULL DEFAULT '1' COMMENT '任务类型 1、重试数据 2、回调数据',
  `bucket_index` int NOT NULL DEFAULT '0' COMMENT 'bucket',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父节点id',
  `deleted` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scene_tasktype_idempotentid_deleted` (`scene_id`,`task_type`,`idempotent_id`,`deleted`),
  KEY `idx_biz_no` (`biz_no`),
  KEY `idx_idempotent_id` (`idempotent_id`),
  KEY `idx_retry_status_bucket_index` (`retry_status`,`bucket_index`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_create_dt` (`create_dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='重试信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_retry_dead_letter` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `group_id` bigint NOT NULL COMMENT '组Id',
  `scene_name` varchar(64) NOT NULL COMMENT '场景名称',
  `scene_id` bigint NOT NULL COMMENT '场景ID',
  `idempotent_id` varchar(64) NOT NULL COMMENT '幂等id',
  `biz_no` varchar(64) NOT NULL DEFAULT '' COMMENT '业务编号',
  `executor_name` varchar(512) NOT NULL DEFAULT '' COMMENT '执行器名称',
  `serializer_name` varchar(32) NOT NULL DEFAULT 'jackson' COMMENT '执行方法参数序列化器名称',
  `args_str` text NOT NULL COMMENT '执行方法参数',
  `ext_attrs` text NOT NULL COMMENT '扩展字段',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_namespace_id_group_name_scene_name` (`namespace_id`,`group_name`,`scene_name`),
  KEY `idx_idempotent_id` (`idempotent_id`),
  KEY `idx_biz_no` (`biz_no`),
  KEY `idx_create_dt` (`create_dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='死信队列表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_retry_scene_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `scene_name` varchar(64) NOT NULL COMMENT '场景名称',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `scene_status` tinyint NOT NULL DEFAULT '0' COMMENT '组状态 0、未启用 1、启用',
  `max_retry_count` int NOT NULL DEFAULT '5' COMMENT '最大重试次数',
  `back_off` tinyint NOT NULL DEFAULT '1' COMMENT '1、默认等级 2、固定间隔时间 3、CRON 表达式',
  `trigger_interval` varchar(16) NOT NULL DEFAULT '' COMMENT '间隔时长',
  `notify_ids` varchar(128) NOT NULL DEFAULT '' COMMENT '通知告警场景配置id列表',
  `deadline_request` bigint unsigned NOT NULL DEFAULT '60000' COMMENT 'Deadline Request 调用链超时 单位毫秒',
  `executor_timeout` int unsigned NOT NULL DEFAULT '5' COMMENT '任务执行超时时间，单位秒',
  `route_key` tinyint NOT NULL DEFAULT '4' COMMENT '路由策略',
  `block_strategy` tinyint NOT NULL DEFAULT '1' COMMENT '阻塞策略 1、丢弃 2、覆盖 3、并行',
  `cb_status` tinyint NOT NULL DEFAULT '0' COMMENT '回调状态 0、不开启 1、开启',
  `cb_trigger_type` tinyint NOT NULL DEFAULT '1' COMMENT '1、默认等级 2、固定间隔时间 3、CRON 表达式',
  `cb_max_count` int NOT NULL DEFAULT '16' COMMENT '回调的最大执行次数',
  `cb_trigger_interval` varchar(16) NOT NULL DEFAULT '' COMMENT '回调的最大执行次数',
  `owner_id` bigint DEFAULT NULL COMMENT '负责人id',
  `labels` varchar(512) DEFAULT '' COMMENT '标签',
  `description` varchar(256) NOT NULL DEFAULT '' COMMENT '描述',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_namespace_id_group_name_scene_name` (`namespace_id`,`group_name`,`scene_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场景配置';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_retry_summary` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL DEFAULT '' COMMENT '组名称',
  `scene_name` varchar(64) NOT NULL DEFAULT '' COMMENT '场景名称',
  `trigger_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '统计时间',
  `running_num` int NOT NULL DEFAULT '0' COMMENT '重试中-日志数量',
  `finish_num` int NOT NULL DEFAULT '0' COMMENT '重试完成-日志数量',
  `max_count_num` int NOT NULL DEFAULT '0' COMMENT '重试到达最大次数-日志数量',
  `suspend_num` int NOT NULL DEFAULT '0' COMMENT '暂停重试-日志数量',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scene_name_trigger_at` (`namespace_id`,`group_name`,`scene_name`,`trigger_at`) USING BTREE,
  KEY `idx_trigger_at` (`trigger_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DashBoard_Retry';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_retry_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `scene_name` varchar(64) NOT NULL COMMENT '场景名称',
  `retry_id` bigint NOT NULL COMMENT '重试信息Id',
  `ext_attrs` text NOT NULL COMMENT '扩展字段',
  `task_status` tinyint NOT NULL DEFAULT '1' COMMENT '重试状态',
  `task_type` tinyint NOT NULL DEFAULT '1' COMMENT '任务类型 1、重试数据 2、回调数据',
  `operation_reason` tinyint NOT NULL DEFAULT '0' COMMENT '操作原因',
  `client_info` varchar(128) DEFAULT NULL COMMENT '客户端地址 clientId#ip:port',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_group_name_scene_name` (`namespace_id`,`group_name`,`scene_name`),
  KEY `task_status` (`task_status`),
  KEY `idx_create_dt` (`create_dt`),
  KEY `idx_retry_id` (`retry_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='重试任务表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_retry_task_log_message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `retry_id` bigint NOT NULL COMMENT '重试信息Id',
  `retry_task_id` bigint NOT NULL COMMENT '重试任务Id',
  `message` longtext NOT NULL COMMENT '异常信息',
  `log_num` int NOT NULL DEFAULT '1' COMMENT '日志数量',
  `real_time` bigint NOT NULL DEFAULT '0' COMMENT '上报时间',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_namespace_id_group_name_retry_task_id` (`namespace_id`,`group_name`,`retry_task_id`),
  KEY `idx_create_dt` (`create_dt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务调度日志信息记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_server_node` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `host_id` varchar(64) NOT NULL COMMENT '主机id',
  `host_ip` varchar(64) NOT NULL COMMENT '机器ip',
  `host_port` int NOT NULL COMMENT '机器端口',
  `expire_at` datetime NOT NULL COMMENT '过期时间',
  `node_type` tinyint NOT NULL COMMENT '节点类型 1、客户端 2、是服务端',
  `ext_attrs` varchar(256) DEFAULT '' COMMENT '扩展字段',
  `labels` varchar(512) DEFAULT '' COMMENT '标签',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_host_id_host_ip` (`host_id`,`host_ip`),
  KEY `idx_namespace_id_group_name` (`namespace_id`,`group_name`),
  KEY `idx_expire_at_node_type` (`expire_at`,`node_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='服务器节点';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_system_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(64) NOT NULL COMMENT '账号',
  `password` varchar(128) NOT NULL COMMENT '密码',
  `role` tinyint NOT NULL DEFAULT '0' COMMENT '角色：1-普通用户、2-管理员',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_system_user_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `system_user_id` bigint NOT NULL COMMENT '系统用户id',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_namespace_id_group_name_system_user_id` (`namespace_id`,`group_name`,`system_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户权限表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_workflow` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `workflow_name` varchar(64) NOT NULL COMMENT '工作流名称',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `biz_id` varchar(64) NOT NULL COMMENT '业务ID',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `workflow_status` tinyint NOT NULL DEFAULT '1' COMMENT '工作流状态 0、关闭、1、开启',
  `trigger_type` tinyint NOT NULL COMMENT '触发类型 1.CRON 表达式 2. 固定时间',
  `trigger_interval` varchar(255) NOT NULL COMMENT '间隔时长',
  `next_trigger_at` bigint NOT NULL COMMENT '下次触发时间',
  `block_strategy` tinyint NOT NULL DEFAULT '1' COMMENT '阻塞策略 1、丢弃 2、覆盖 3、并行',
  `executor_timeout` int NOT NULL DEFAULT '0' COMMENT '任务执行超时时间，单位秒',
  `description` varchar(256) NOT NULL DEFAULT '' COMMENT '描述',
  `flow_info` text COMMENT '流程信息',
  `wf_context` text COMMENT '上下文',
  `notify_ids` varchar(128) NOT NULL DEFAULT '' COMMENT '通知告警场景配置id列表',
  `bucket_index` int NOT NULL DEFAULT '0' COMMENT 'bucket',
  `version` int NOT NULL COMMENT '版本号',
  `owner_id` bigint DEFAULT NULL COMMENT '负责人id',
  `ext_attrs` varchar(256) DEFAULT '' COMMENT '扩展字段',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 1、删除',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sj_workflow_01` (`namespace_id`,`biz_id`),
  KEY `idx_create_dt` (`create_dt`),
  KEY `idx_namespace_id_group_name` (`namespace_id`,`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作流';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_workflow_node` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `node_name` varchar(64) NOT NULL COMMENT '节点名称',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `job_id` bigint NOT NULL COMMENT '任务信息id',
  `workflow_id` bigint NOT NULL COMMENT '工作流ID',
  `node_type` tinyint NOT NULL DEFAULT '1' COMMENT '1、任务节点 2、条件节点',
  `expression_type` tinyint NOT NULL DEFAULT '0' COMMENT '1、SpEl、2、Aviator 3、QL',
  `fail_strategy` tinyint NOT NULL DEFAULT '1' COMMENT '失败策略 1、跳过 2、阻塞',
  `workflow_node_status` tinyint NOT NULL DEFAULT '1' COMMENT '工作流节点状态 0、关闭、1、开启',
  `priority_level` int NOT NULL DEFAULT '1' COMMENT '优先级',
  `node_info` text COMMENT '节点信息 ',
  `version` int NOT NULL COMMENT '版本号',
  `ext_attrs` varchar(256) DEFAULT '' COMMENT '扩展字段',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 1、删除',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_create_dt` (`create_dt`),
  KEY `idx_namespace_id_group_name` (`namespace_id`,`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作流节点';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sj_workflow_task_batch` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `namespace_id` varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a' COMMENT '命名空间id',
  `group_name` varchar(64) NOT NULL COMMENT '组名称',
  `workflow_id` bigint NOT NULL COMMENT '工作流任务id',
  `task_batch_status` tinyint NOT NULL DEFAULT '0' COMMENT '任务批次状态 0、失败 1、成功',
  `operation_reason` tinyint NOT NULL DEFAULT '0' COMMENT '操作原因',
  `flow_info` text COMMENT '流程信息',
  `wf_context` text COMMENT '全局上下文',
  `execution_at` bigint NOT NULL DEFAULT '0' COMMENT '任务执行时间',
  `ext_attrs` varchar(256) DEFAULT '' COMMENT '扩展字段',
  `version` int NOT NULL DEFAULT '1' COMMENT '版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 1、删除',
  `create_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_dt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_job_id_task_batch_status` (`workflow_id`,`task_batch_status`),
  KEY `idx_create_dt` (`create_dt`),
  KEY `idx_namespace_id_group_name` (`namespace_id`,`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作流批次';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_client` (
  `id` bigint NOT NULL COMMENT 'id',
  `client_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户端id',
  `client_key` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户端key',
  `client_secret` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户端秘钥',
  `grant_type` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '授权类型',
  `device_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '设备类型',
  `access_path` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '允许访问路径',
  `ip_whitelist` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'IP白名单',
  `active_timeout` int DEFAULT '1800' COMMENT 'token活跃超时时间',
  `timeout` int DEFAULT '604800' COMMENT 'token固定超时',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统授权表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_config` (
  `config_id` bigint NOT NULL COMMENT '参数主键',
  `config_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数键名',
  `config_value` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数键值',
  `config_type` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='参数配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dept` (
  `dept_id` bigint NOT NULL COMMENT '部门id',
  `parent_id` bigint DEFAULT '0' COMMENT '父部门id',
  `ancestors` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '祖级列表',
  `dept_name` varchar(30) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '部门名称',
  `dept_category` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '部门类别编码',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `leader` bigint DEFAULT NULL COMMENT '负责人',
  `phone` varchar(11) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '联系电话',
  `email` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '部门状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`dept_id`),
  KEY `idx_sys_dept_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='部门表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_data` (
  `dict_code` bigint NOT NULL COMMENT '字典编码',
  `dict_sort` int DEFAULT '0' COMMENT '字典排序',
  `dict_label` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典标签',
  `dict_value` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典键值',
  `dict_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `css_class` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '样式属性（其他样式扩展）',
  `list_class` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '表格回显样式',
  `is_default` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否默认（Y是 N否）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_code`),
  KEY `idx_sys_dict_data_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典数据表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_type` (
  `dict_id` bigint NOT NULL COMMENT '字典主键',
  `dict_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典名称',
  `dict_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_id`),
  UNIQUE KEY `dict_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典类型表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_login_info` (
  `info_id` bigint NOT NULL COMMENT '访问ID',
  `user_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户账号',
  `client_key` varchar(32) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '客户端',
  `device_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '设备类型',
  `ipaddr` varchar(128) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录IP地址',
  `login_location` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录地点',
  `browser` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '浏览器类型',
  `os` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作系统',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '登录状态（0正常 1异常）',
  `msg` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '提示消息',
  `login_time` datetime DEFAULT NULL COMMENT '访问时间',
  PRIMARY KEY (`info_id`),
  KEY `idx_sys_login_info_s` (`status`),
  KEY `idx_sys_login_info_lt` (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统访问记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu` (
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  `menu_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜单名称',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `path` varchar(200) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组件路径',
  `query_param` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '路由参数',
  `is_frame` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否为外链（Y是 N否）',
  `is_cache` char(1) COLLATE utf8mb4_general_ci DEFAULT 'Y' COMMENT '是否缓存（Y缓存 N不缓存）',
  `menu_type` char(1) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '显示状态（0显示 1隐藏）',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限标识',
  `icon` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '#' COMMENT '菜单图标',
  `active_menu` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '激活菜单路径',
  `ext` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '扩展字段',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '备注',
  `application_id` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'iws-a' COMMENT '所属应用，* 表示共享',
  PRIMARY KEY (`menu_id`),
  KEY `idx_sys_menu_application` (`application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='菜单权限表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_message` (
  `message_id` bigint NOT NULL COMMENT '消息ID',
  `category` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息分组(system/notice/workflow)',
  `type` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息类型',
  `source` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息来源',
  `title` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '标题',
  `message` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '摘要消息',
  `content` longtext COLLATE utf8mb4_general_ci COMMENT '详细内容',
  `data_json` longtext COLLATE utf8mb4_general_ci COMMENT '扩展数据JSON',
  `path` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '前端跳转路径',
  `send_user_ids` varchar(2000) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '目标用户ID串，0表示全局',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`message_id`),
  KEY `idx_sys_message_category_time` (`category`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='消息记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_notice` (
  `notice_id` bigint NOT NULL COMMENT '公告ID',
  `notice_title` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告标题',
  `notice_type` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告类型（1通知 2公告）',
  `notice_content` longblob COMMENT '公告内容',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`notice_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通知公告表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_oper_log` (
  `oper_id` bigint NOT NULL COMMENT '日志主键',
  `title` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '模块标题',
  `business_type` int DEFAULT '0' COMMENT '业务类型（0其它 1新增 2修改 3删除）',
  `method` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '方法名称',
  `request_method` varchar(10) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求方式',
  `operator_type` int DEFAULT '0' COMMENT '操作类别（0其它 1后台用户 2手机端用户）',
  `oper_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作人员',
  `user_id` bigint DEFAULT NULL COMMENT '操作用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '操作部门ID',
  `dept_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '部门名称',
  `client_key` varchar(32) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '客户端',
  `device_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '设备类型',
  `browser` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '浏览器类型',
  `os` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作系统',
  `oper_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求URL',
  `oper_ip` varchar(128) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '主机地址',
  `oper_location` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作地点',
  `oper_param` varchar(4000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求参数',
  `json_result` varchar(4000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '返回参数',
  `status` int DEFAULT '0' COMMENT '操作状态（0正常 1异常）',
  `error_msg` varchar(4000) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '错误消息',
  `oper_time` datetime DEFAULT NULL COMMENT '操作时间',
  `cost_time` bigint DEFAULT '0' COMMENT '消耗时间',
  PRIMARY KEY (`oper_id`),
  KEY `idx_sys_oper_log_bt` (`business_type`),
  KEY `idx_sys_oper_log_uid` (`user_id`),
  KEY `idx_sys_oper_log_s` (`status`),
  KEY `idx_sys_oper_log_ot` (`oper_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作日志记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_oss` (
  `oss_id` bigint NOT NULL COMMENT '对象存储主键',
  `file_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '文件名',
  `original_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '原名',
  `file_suffix` varchar(10) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '文件后缀名',
  `url` varchar(500) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'URL地址',
  `ext1` text COLLATE utf8mb4_general_ci COMMENT '扩展字段',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` bigint DEFAULT NULL COMMENT '上传人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `service` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'minio' COMMENT '服务商',
  PRIMARY KEY (`oss_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='OSS对象存储表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_oss_config` (
  `oss_config_id` bigint NOT NULL COMMENT '主键',
  `config_key` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '配置key',
  `access_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT 'accessKey',
  `secret_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '秘钥',
  `bucket_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '桶名称',
  `prefix` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '前缀',
  `endpoint` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '访问站点',
  `domain_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '自定义域名',
  `is_https` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否https（Y=是,N=否）',
  `region` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '域',
  `access_policy` char(1) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '1' COMMENT '桶权限类型(0=private 1=public 2=custom)',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否默认（Y=是,N=否）',
  `ext1` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '扩展字段',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`oss_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='对象存储配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_post` (
  `post_id` bigint NOT NULL COMMENT '岗位ID',
  `dept_id` bigint NOT NULL COMMENT '部门id',
  `post_code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '岗位编码',
  `post_category` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '岗位类别编码',
  `post_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '岗位名称',
  `post_sort` int NOT NULL COMMENT '显示顺序',
  `status` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`post_id`),
  KEY `idx_sys_post_dept_id` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='岗位信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `role_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色名称',
  `role_key` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色权限字符串',
  `role_sort` int NOT NULL COMMENT '显示顺序',
  `data_scope` char(1) COLLATE utf8mb4_general_ci DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限 5：仅本人数据权限 6：部门及以下或本人数据权限）',
  `menu_check_strictly` tinyint(1) DEFAULT '1' COMMENT '菜单树选择项是否关联显示',
  `dept_check_strictly` tinyint(1) DEFAULT '1' COMMENT '部门树选择项是否关联显示',
  `status` char(1) COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`role_id`),
  KEY `idx_sys_role_create_dept` (`create_dept`),
  KEY `idx_sys_role_create_by` (`create_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_dept` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  PRIMARY KEY (`role_id`,`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色和部门关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`,`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色和菜单关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu_scope` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `application_id` varchar(64) NOT NULL COMMENT '统一身份应用标识',
  PRIMARY KEY (`role_id`,`application_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色应用菜单作用域';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu_application` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `application_id` varchar(64) NOT NULL COMMENT '统一身份应用标识',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`,`application_id`,`menu_id`),
  KEY `idx_role_menu_application_menu` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色应用菜单关联';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_social` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `auth_id` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '平台+平台唯一id',
  `source` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户来源',
  `open_id` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '平台编号唯一id',
  `user_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录账号',
  `nick_name` varchar(30) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户昵称',
  `email` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户邮箱',
  `avatar` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '头像地址',
  `access_token` varchar(2000) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户的授权令牌',
  `expire_in` int DEFAULT NULL COMMENT '用户的授权令牌的有效期，部分平台可能没有',
  `refresh_token` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '刷新令牌，部分平台可能没有',
  `access_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '平台的授权信息，部分平台可能没有',
  `union_id` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用户的 unionid',
  `scope` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '授予的权限，部分平台可能没有',
  `token_type` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '个别平台的授权信息，部分平台可能没有',
  `id_token` varchar(2000) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'id token，部分平台可能没有',
  `mac_algorithm` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '小米平台用户的附带属性，部分平台可能没有',
  `mac_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '小米平台用户的附带属性，部分平台可能没有',
  `code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用户的授权code，部分平台可能没有',
  `oauth_token` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Twitter平台用户的附带属性，部分平台可能没有',
  `oauth_token_secret` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Twitter平台用户的附带属性，部分平台可能没有',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='社会化关系表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `user_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户账号',
  `nick_name` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户昵称',
  `user_type` varchar(10) COLLATE utf8mb4_general_ci DEFAULT 'sys_user' COMMENT '用户类型（sys_user系统用户）',
  `email` varchar(50) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户邮箱',
  `phone_number` varchar(11) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '手机号码',
  `gender` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
  `avatar` bigint DEFAULT NULL COMMENT '头像地址',
  `password` varchar(100) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '密码',
  `status` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '账号状态（0正常 1停用）',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 1代表删除）',
  `login_ip` varchar(128) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime DEFAULT NULL COMMENT '最后登录时间',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`user_id`),
  KEY `idx_sys_user_dept_id` (`dept_id`),
  KEY `idx_sys_user_create_by` (`create_by`),
  KEY `idx_sys_user_user_name` (`user_name`),
  KEY `idx_sys_user_phone` (`phone_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_post` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `post_id` bigint NOT NULL COMMENT '岗位ID',
  PRIMARY KEY (`user_id`,`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户与岗位关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`,`role_id`),
  KEY `idx_sys_user_role_rid` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户和角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `test_demo` (
  `id` bigint NOT NULL COMMENT '主键',
  `dept_id` bigint DEFAULT NULL COMMENT '部门id',
  `user_id` bigint DEFAULT NULL COMMENT '用户id',
  `order_num` int DEFAULT '0' COMMENT '排序号',
  `test_key` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'key键',
  `value` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '值',
  `version` int DEFAULT '0' COMMENT '版本',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `del_flag` int DEFAULT '0' COMMENT '删除标志',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='测试单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `test_leave` (
  `id` bigint NOT NULL COMMENT 'id',
  `apply_code` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '申请编号',
  `leave_type` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '请假类型',
  `start_date` datetime NOT NULL COMMENT '开始时间',
  `end_date` datetime NOT NULL COMMENT '结束时间',
  `leave_days` int NOT NULL COMMENT '请假天数',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '请假原因',
  `status` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '状态',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='请假申请表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `test_tree` (
  `id` bigint NOT NULL COMMENT '主键',
  `parent_id` bigint DEFAULT '0' COMMENT '父id',
  `dept_id` bigint DEFAULT NULL COMMENT '部门id',
  `user_id` bigint DEFAULT NULL COMMENT '用户id',
  `tree_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '值',
  `version` int DEFAULT '0' COMMENT '版本',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `del_flag` int DEFAULT '0' COMMENT '删除标志',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='测试树表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_business_plan_mount` (
  `mount_id` bigint NOT NULL COMMENT '挂载主键，系统生成',
  `business_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '业务类型编码',
  `plan_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '计划类型编码',
  `schedule_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '调度业务编码，等于iws_business_schedule.business_code',
  `contract_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '挂载合同版本',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用',
  `revision` int NOT NULL DEFAULT '1' COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`mount_id`),
  UNIQUE KEY `uk_wms_business_plan_mount` (`business_type_code`,`plan_type_code`,`schedule_code`),
  KEY `idx_wms_business_plan_mount_resolve` (`schedule_code`,`plan_type_code`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS业务类型计划挂载目录'
;
/*!40101 SET character_set_client = @saved_cs_client */;
CREATE TABLE `wms_business_type` (
  `business_type_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '业务类型编码',
  `business_type_name` varchar(128) NOT NULL COMMENT '业务类型名称',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
  `contract_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '业务合同版本',
  `object_policy_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '对象策略键；本切面不注册策略',
  `revision` int NOT NULL DEFAULT '1' COMMENT '修订号',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`business_type_code`),
  KEY `idx_wms_business_type_status` (`status`,`business_type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS业务类型目录'
;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_command_attempt` (
  `attempt_id` bigint NOT NULL COMMENT '尝试主键，系统生成',
  `command_key` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '命令键',
  `action` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '命令动作，如 COMPANY_CREATE/COMPANY_UPDATE/COMPANY_ENABLE/COMPANY_DISABLE/COMPANY_SCOPE_REPLACE',
  `object_type` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '对象类型，如 WMS_COMPANY',
  `object_id` bigint DEFAULT NULL COMMENT '对象内部ID，创建前可空',
  `input_digest` char(64) NOT NULL COMMENT '规范化请求摘要，SHA-256小写',
  `input_summary` varchar(2000) DEFAULT NULL COMMENT '脱敏请求摘要',
  `status` varchar(16) COLLATE utf8mb4_bin NOT NULL COMMENT '状态：ACCEPTED/PROCESSING/APPLIED/REJECTED/FAILED/UNKNOWN',
  `error_code` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '错误码，如 VERSION_CONFLICT/SCOPE_VERSION_CONFLICT/FEATURE_NOT_OPEN',
  `error_message` varchar(500) DEFAULT NULL COMMENT '脱敏错误消息',
  `fact_type` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '成功事实类型，如 WMS_OBJECT_AUDIT',
  `fact_id` varchar(128) DEFAULT NULL COMMENT '成功事实标识',
  `actor_user_id` bigint DEFAULT NULL COMMENT '操作者',
  `actor_name` varchar(64) DEFAULT NULL COMMENT '操作者名称快照',
  `dept_id` bigint DEFAULT NULL COMMENT '操作者部门快照',
  `dept_name` varchar(128) DEFAULT NULL COMMENT '操作者部门名称快照',
  `initiator_name` varchar(64) DEFAULT NULL COMMENT '发起人名称快照',
  `execution_subject` varchar(64) DEFAULT NULL COMMENT '执行主体名称快照',
  `execution_subject_type` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '执行主体类型：USER/PLAN/SYSTEM',
  `execution_account` varchar(64) DEFAULT NULL COMMENT '执行账号快照',
  `occurred_at` datetime(3) NOT NULL COMMENT '命令发生时间',
  `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (`attempt_id`),
  UNIQUE KEY `uk_wms_command_attempt_command` (`command_key`),
  KEY `idx_wms_command_attempt_object` (`object_type`,`object_id`,`occurred_at`),
  KEY `idx_wms_command_attempt_status` (`status`,`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS非计划受控命令尝试';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_company` (
  `company_id` bigint NOT NULL COMMENT '公司主键，系统生成',
  `company_code` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '公司编码，业务唯一，创建后不可修改',
  `company_name` varchar(100) NOT NULL COMMENT '公司名称',
  `enabled` tinyint NOT NULL DEFAULT '0' COMMENT '是否可被后续业务选择：0否，1是；与生命周期分离',
  `lifecycle_status` varchar(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '生命周期：ACTIVE/RETIRING/RETIRED',
  `revision` int NOT NULL DEFAULT '1' COMMENT '公司乐观锁版本，创建为1',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`company_id`),
  UNIQUE KEY `uk_wms_company_code` (`company_code`),
  KEY `idx_wms_company_lifecycle` (`lifecycle_status`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS公司主档';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_company_management_grant` (
  `grant_id` bigint NOT NULL COMMENT '授权明细主键，系统生成',
  `company_id` bigint NOT NULL COMMENT '公司主键，逻辑关联 wms_company.company_id',
  `role_id` bigint NOT NULL COMMENT '角色主键，逻辑关联 sys_role.role_id',
  `created_by` bigint DEFAULT NULL COMMENT '创建者',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`grant_id`),
  UNIQUE KEY `uk_wms_company_grant` (`company_id`,`role_id`),
  KEY `idx_wms_company_grant_role` (`role_id`,`company_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS公司管理角色授权明细';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_company_management_scope` (
  `company_id` bigint NOT NULL COMMENT '公司主键，逻辑关联 wms_company.company_id；每公司恰一行',
  `scope_revision` int NOT NULL DEFAULT '0' COMMENT '管理范围独立乐观锁版本，创建为0',
  `updated_by` bigint DEFAULT NULL COMMENT '最后更新人',
  `updated_at` datetime(3) DEFAULT NULL COMMENT '最后更新时间',
  PRIMARY KEY (`company_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS公司管理范围头';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_compartment` (
  `compartment_id` bigint NOT NULL COMMENT '格口主键，系统生成',
  `container_id` bigint NOT NULL COMMENT '所属容器主键，逻辑关联 wms_container.container_id',
  `container_code` varchar(64) NOT NULL COMMENT '所属容器编码快照',
  `container_type_id` bigint DEFAULT NULL COMMENT '容器类型主键快照',
  `compartment_code` varchar(50) NOT NULL COMMENT '格口编码，同一容器内唯一',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '格口状态：0空闲，1占用，2禁用',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '格口顺序',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`compartment_id`),
  UNIQUE KEY `uk_wms_compartment_container_code` (`container_id`,`compartment_code`),
  KEY `idx_wms_compartment_container_status` (`container_id`,`status`),
  KEY `idx_wms_compartment_code_status` (`container_code`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS容器格口表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_container` (
  `container_id` bigint NOT NULL COMMENT '容器主键，系统生成',
  `container_code` varchar(64) NOT NULL COMMENT '容器编码，业务唯一',
  `container_type_id` bigint NOT NULL COMMENT '容器类型主键，逻辑关联 wms_container_type.container_type_id',
  `storage_id` bigint DEFAULT NULL COMMENT '当前储位主键，逻辑关联 wms_storage.storage_id；为空表示库外或在途',
  `zone_id` bigint DEFAULT NULL COMMENT '当前库区主键快照，逻辑关联 wms_warehouse_zone.zone_id',
  `container_status` tinyint NOT NULL DEFAULT '0' COMMENT '容器状态：0空容器，1有库存，2报废',
  `location_status` tinyint NOT NULL DEFAULT '0' COMMENT '位置状态：0库外，1库内，2在途',
  `lock_status` tinyint NOT NULL DEFAULT '0' COMMENT '锁定状态：0未锁定，1已锁定',
  `a_face_code` varchar(64) DEFAULT NULL COMMENT 'A面标签/面码',
  `b_face_code` varchar(64) DEFAULT NULL COMMENT 'B面标签/面码',
  `end_point_code` varchar(64) DEFAULT NULL COMMENT '终点/设备点位编码',
  `last_in_time` datetime DEFAULT NULL COMMENT '最近入库时间',
  `last_out_time` datetime DEFAULT NULL COMMENT '最近出库时间',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0存在，1删除',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`container_id`),
  UNIQUE KEY `uk_wms_container_code` (`container_code`),
  UNIQUE KEY `uk_wms_container_storage` (`storage_id`),
  UNIQUE KEY `uk_wms_container_a_face` (`a_face_code`),
  UNIQUE KEY `uk_wms_container_b_face` (`b_face_code`),
  KEY `idx_wms_container_type_status` (`container_type_id`,`container_status`),
  KEY `idx_wms_container_zone_location` (`zone_id`,`location_status`,`container_status`),
  KEY `idx_wms_container_lock` (`lock_status`,`location_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS容器表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_container_type` (
  `container_type_id` bigint NOT NULL COMMENT '容器类型主键，系统生成',
  `container_type_code` varchar(50) NOT NULL COMMENT '容器类型编码，业务唯一',
  `container_type_name` varchar(100) NOT NULL COMMENT '容器类型名称',
  `container_category` tinyint NOT NULL DEFAULT '1' COMMENT '容器大类：1料盒，2料箱，3托盘，4料车，9其他',
  `length_value` decimal(12,3) DEFAULT NULL COMMENT '长度',
  `width_value` decimal(12,3) DEFAULT NULL COMMENT '宽度',
  `height_value` decimal(12,3) DEFAULT NULL COMMENT '高度',
  `dimension_unit` varchar(16) DEFAULT 'mm' COMMENT '尺寸单位',
  `weight_limit` decimal(18,3) DEFAULT NULL COMMENT '承重上限',
  `weight_unit` varchar(16) DEFAULT 'kg' COMMENT '重量单位',
  `is_divided` tinyint NOT NULL DEFAULT '0' COMMENT '是否分格：0否，1是',
  `compartment_count` int DEFAULT NULL COMMENT '格口数量，is_divided=1时使用',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0停用，1启用',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0存在，1删除',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`container_type_id`),
  UNIQUE KEY `uk_wms_container_type_code` (`container_type_code`),
  KEY `idx_wms_container_type_category` (`container_category`,`status`),
  KEY `idx_wms_container_type_divided` (`is_divided`,`compartment_count`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS容器类型表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_inbound_order` (
  `inbound_order_id` bigint NOT NULL COMMENT '入库单头主键，系统生成',
  `inbound_order_no` varchar(100) NOT NULL COMMENT '入库单号，业务唯一',
  `inbound_type` tinyint NOT NULL DEFAULT '1' COMMENT '入库类型：1采购入库，2退料入库，3调整入库，4人工入库',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '单据状态：0新建，1执行中，2已完成，3已取消，4异常',
  `priority` tinyint NOT NULL DEFAULT '5' COMMENT '优先级，数字越小优先级越高',
  `source_system` varchar(32) DEFAULT NULL COMMENT '来源系统，例如MES、ERP、WCS、MANUAL',
  `source_order_no` varchar(100) DEFAULT NULL COMMENT '外部来源单号',
  `start_time` datetime DEFAULT NULL COMMENT '开始执行时间',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0存在，1删除',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`inbound_order_id`),
  UNIQUE KEY `uk_wms_inbound_order_no` (`inbound_order_no`),
  KEY `idx_wms_inbound_order_status` (`status`,`priority`,`create_time`),
  KEY `idx_wms_inbound_order_type_status` (`inbound_type`,`status`),
  KEY `idx_wms_inbound_order_source` (`source_system`,`source_order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS入库单头表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_inbound_order_history` (
  `inbound_history_id` bigint NOT NULL COMMENT '入库单头历史主键，系统生成',
  `source_inbound_order_id` bigint DEFAULT NULL COMMENT '来源入库单头主键，归档前的 wms_inbound_order.inbound_order_id',
  `inbound_order_no` varchar(100) NOT NULL COMMENT '入库单号快照，业务唯一',
  `inbound_type` tinyint NOT NULL COMMENT '入库类型快照：1采购入库，2退料入库，3调整入库，4人工入库',
  `status` tinyint NOT NULL COMMENT '归档时单据状态：参考 wms_inbound_order.status',
  `priority` tinyint NOT NULL DEFAULT '5' COMMENT '优先级快照，数字越小优先级越高',
  `source_system` varchar(32) DEFAULT NULL COMMENT '来源系统快照',
  `source_order_no` varchar(100) DEFAULT NULL COMMENT '外部来源单号快照',
  `start_time` datetime DEFAULT NULL COMMENT '开始执行时间快照',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间快照',
  `archive_reason` varchar(255) DEFAULT NULL COMMENT '归档原因：COMPLETE、CANCEL、MANUAL_CLOSE、EXCEPTION等',
  `archive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
  `archived_by` bigint DEFAULT NULL COMMENT '归档操作人',
  `source_create_time` datetime DEFAULT NULL COMMENT '来源单据创建时间',
  `source_update_time` datetime DEFAULT NULL COMMENT '来源单据最后更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`inbound_history_id`),
  UNIQUE KEY `uk_wms_inbound_hist_no` (`inbound_order_no`),
  KEY `idx_wms_inbound_hist_source_id` (`source_inbound_order_id`),
  KEY `idx_wms_inbound_hist_status` (`status`,`priority`,`archive_time`),
  KEY `idx_wms_inbound_hist_type_status` (`inbound_type`,`status`,`archive_time`),
  KEY `idx_wms_inbound_hist_source` (`source_system`,`source_order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS入库单头历史表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_inbound_order_line` (
  `inbound_line_id` bigint NOT NULL COMMENT '入库单明细主键，系统生成',
  `inbound_order_id` bigint NOT NULL COMMENT '入库单头主键，逻辑关联 wms_inbound_order.inbound_order_id',
  `inbound_order_no` varchar(100) NOT NULL COMMENT '入库单号快照',
  `line_no` int NOT NULL DEFAULT '1' COMMENT '行号',
  `stock_unit_code` varchar(100) DEFAULT NULL COMMENT '库存单元编码，SMT通常为REEL ID/盘号/唯一标签号',
  `material_code` varchar(50) NOT NULL COMMENT '物料编码',
  `material_name` varchar(200) DEFAULT NULL COMMENT '物料名称快照',
  `material_batch` varchar(64) DEFAULT NULL COMMENT '物料批次号',
  `date_code` varchar(50) DEFAULT NULL COMMENT 'Date Code',
  `supplier_code` varchar(50) DEFAULT NULL COMMENT '供应商编码',
  `supplier_name` varchar(100) DEFAULT NULL COMMENT '供应商名称快照',
  `plant_code` varchar(50) DEFAULT NULL COMMENT '厂别编码',
  `warehouse_code` varchar(50) DEFAULT NULL COMMENT '仓库编码/仓别',
  `plan_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '计划入库数量',
  `actual_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '实际入库数量',
  `unit` varchar(16) DEFAULT NULL COMMENT '单位',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '明细状态：0待执行，1执行中，2已完成，3已取消，4异常',
  `production_date` date DEFAULT NULL COMMENT '生产日期',
  `receiving_time` datetime DEFAULT NULL COMMENT '收料时间',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`inbound_line_id`),
  KEY `idx_wms_inbound_line_order_status` (`inbound_order_id`,`status`),
  KEY `idx_wms_inbound_line_no_status` (`inbound_order_no`,`status`),
  KEY `idx_wms_inbound_line_stock` (`stock_unit_code`),
  KEY `idx_wms_inbound_line_material` (`material_code`,`material_batch`,`date_code`),
  KEY `idx_wms_inbound_line_wh` (`plant_code`,`warehouse_code`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS入库单明细表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_inbound_order_line_history` (
  `inbound_line_history_id` bigint NOT NULL COMMENT '入库单明细历史主键，系统生成',
  `source_inbound_line_id` bigint DEFAULT NULL COMMENT '来源入库单明细主键，归档前的 wms_inbound_order_line.inbound_line_id',
  `inbound_history_id` bigint DEFAULT NULL COMMENT '入库单头历史主键，逻辑关联 wms_inbound_order_history.inbound_history_id',
  `source_inbound_order_id` bigint DEFAULT NULL COMMENT '来源入库单头主键快照',
  `inbound_order_no` varchar(100) NOT NULL COMMENT '入库单号快照',
  `line_no` int NOT NULL DEFAULT '1' COMMENT '行号快照',
  `stock_unit_code` varchar(100) DEFAULT NULL COMMENT '库存单元编码快照，SMT通常为REEL ID/盘号/唯一标签号',
  `material_code` varchar(50) NOT NULL COMMENT '物料编码快照',
  `material_name` varchar(200) DEFAULT NULL COMMENT '物料名称快照',
  `material_batch` varchar(64) DEFAULT NULL COMMENT '物料批次号快照',
  `date_code` varchar(50) DEFAULT NULL COMMENT 'Date Code快照',
  `supplier_code` varchar(50) DEFAULT NULL COMMENT '供应商编码快照',
  `supplier_name` varchar(100) DEFAULT NULL COMMENT '供应商名称快照',
  `plant_code` varchar(50) DEFAULT NULL COMMENT '厂别编码快照',
  `warehouse_code` varchar(50) DEFAULT NULL COMMENT '仓库编码/仓别快照',
  `plan_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '计划入库数量快照',
  `actual_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '实际入库数量快照',
  `unit` varchar(16) DEFAULT NULL COMMENT '单位快照',
  `status` tinyint NOT NULL COMMENT '归档时明细状态：参考 wms_inbound_order_line.status',
  `production_date` date DEFAULT NULL COMMENT '生产日期快照',
  `receiving_time` datetime DEFAULT NULL COMMENT '收料时间快照',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间快照',
  `archive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
  `source_create_time` datetime DEFAULT NULL COMMENT '来源明细创建时间',
  `source_update_time` datetime DEFAULT NULL COMMENT '来源明细最后更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`inbound_line_history_id`),
  KEY `idx_wms_inbound_line_hist_source` (`source_inbound_line_id`),
  KEY `idx_wms_inbound_line_hist_order_status` (`inbound_order_no`,`status`),
  KEY `idx_wms_inbound_line_hist_stock` (`stock_unit_code`,`inbound_order_no`),
  KEY `idx_wms_inbound_line_hist_material` (`material_code`,`material_batch`,`date_code`),
  KEY `idx_wms_inbound_line_hist_wh` (`plant_code`,`warehouse_code`,`status`),
  KEY `idx_wms_inbound_line_hist_archive` (`archive_time`,`inbound_line_history_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS入库单明细历史表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_inbound_order_push_log` (
  `inbound_push_log_id` bigint NOT NULL COMMENT '入库单推送记录主键，系统生成',
  `external_request_no` varchar(100) DEFAULT NULL COMMENT '上层系统请求流水号/消息ID',
  `source_system` varchar(32) NOT NULL DEFAULT 'MES' COMMENT '来源上层系统：MES、ERP、SRM、MANUAL等',
  `interface_code` varchar(64) NOT NULL COMMENT '接口编码，例如IWS203-1、IWS203-2、INBOUND_ORDER_PUSH',
  `interface_name` varchar(100) DEFAULT NULL COMMENT '接口名称',
  `inbound_order_no` varchar(100) DEFAULT NULL COMMENT '入库单号，接收报文中可能为空或失败时解析不到',
  `source_order_no` varchar(100) DEFAULT NULL COMMENT '上层系统原始单号',
  `inbound_type` tinyint DEFAULT NULL COMMENT '入库类型快照：1采购入库，2退料入库，3调整入库，4人工入库',
  `stock_unit_code` varchar(100) DEFAULT NULL COMMENT '库存单元编码，SMT通常为REEL ID；单笔推送或主查询条件使用',
  `stock_unit_list` longtext COMMENT '本次推送涉及的库存单元清单JSON，批量推送时使用',
  `push_stage` tinyint NOT NULL DEFAULT '1' COMMENT '推送阶段：1整单，2单头，3明细，4状态确认',
  `attempt_no` int NOT NULL DEFAULT '1' COMMENT '同一来源单据同一接口第几次推送/接收',
  `total_count` int DEFAULT NULL COMMENT '本次报文总明细数',
  `success_count` int DEFAULT NULL COMMENT 'WMS本次成功接收明细数',
  `failed_count` int DEFAULT NULL COMMENT 'WMS本次失败明细数',
  `failed_stock_units` longtext COMMENT '失败库存单元清单JSON，例如失败REEL清单',
  `failed_items` longtext COMMENT '失败明细清单JSON，例如失败REEL/行号/错误原因',
  `request_payload` longtext COMMENT '上层系统推送请求报文JSON',
  `response_payload` longtext COMMENT 'WMS响应报文JSON',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '接收状态：0处理中，1成功，2部分成功，3失败',
  `error_code` varchar(64) DEFAULT NULL COMMENT '错误码/上层系统返回码',
  `error_message` longtext COMMENT '错误信息/异常摘要',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `idempotency_key` varchar(128) DEFAULT NULL COMMENT '幂等键，同一上层系统推送动作唯一',
  `cost_time_ms` bigint DEFAULT NULL COMMENT '接口处理耗时，单位毫秒',
  `received_time` datetime DEFAULT NULL COMMENT 'WMS接收到推送的时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建者/处理人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`inbound_push_log_id`),
  UNIQUE KEY `uk_wms_inbound_push_idem` (`idempotency_key`),
  KEY `idx_wms_inbound_push_request` (`external_request_no`),
  KEY `idx_wms_inbound_push_order` (`inbound_order_no`,`interface_code`,`create_time`),
  KEY `idx_wms_inbound_push_source` (`source_system`,`source_order_no`),
  KEY `idx_wms_inbound_push_stock` (`stock_unit_code`,`create_time`),
  KEY `idx_wms_inbound_push_status` (`status`,`create_time`),
  KEY `idx_wms_inbound_push_time` (`create_time`,`inbound_push_log_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS入库单推送记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_inventory` (
  `inventory_id` bigint NOT NULL COMMENT '库存主键，系统生成',
  `stock_unit_code` varchar(100) NOT NULL COMMENT '库存单元编码，SMT通常为REEL ID/盘号/唯一标签号',
  `material_code` varchar(50) NOT NULL COMMENT '物料编码，逻辑关联 wms_material.material_code',
  `material_name` varchar(200) DEFAULT NULL COMMENT '物料名称快照',
  `material_batch` varchar(64) DEFAULT NULL COMMENT '物料批次号',
  `date_code` varchar(50) DEFAULT NULL COMMENT 'Date Code',
  `supplier_code` varchar(50) DEFAULT NULL COMMENT '供应商编码',
  `supplier_name` varchar(100) DEFAULT NULL COMMENT '供应商名称快照',
  `plant_code` varchar(50) DEFAULT NULL COMMENT '厂别编码',
  `warehouse_code` varchar(50) DEFAULT NULL COMMENT '仓库编码/仓别',
  `zone_id` bigint DEFAULT NULL COMMENT '当前库区主键快照，逻辑关联 wms_warehouse_zone.zone_id',
  `zone_code` varchar(50) DEFAULT NULL COMMENT '当前库区编码快照',
  `storage_id` bigint DEFAULT NULL COMMENT '当前储位主键快照，逻辑关联 wms_storage.storage_id',
  `storage_code` varchar(64) DEFAULT NULL COMMENT '当前储位编码快照',
  `container_id` bigint DEFAULT NULL COMMENT '当前容器主键，逻辑关联 wms_container.container_id',
  `container_code` varchar(64) DEFAULT NULL COMMENT '当前容器编码快照',
  `compartment_id` bigint DEFAULT NULL COMMENT '当前格口主键，逻辑关联 wms_compartment.compartment_id',
  `compartment_code` varchar(50) DEFAULT NULL COMMENT '当前格口编码快照',
  `inventory_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '库存数量',
  `reserved_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '已预留/已扣占数量',
  `unit` varchar(16) DEFAULT NULL COMMENT '库存单位',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '库存状态：0待入库，1可用，2已预留，3出库中，4已出库，5异常，6报废',
  `frozen_status` tinyint NOT NULL DEFAULT '0' COMMENT '冻结状态：0未冻结，1冻结',
  `inbound_status` tinyint NOT NULL DEFAULT '0' COMMENT '入库完成状态：0未完成，1已完成',
  `quality_status` tinyint NOT NULL DEFAULT '0' COMMENT '品质状态：0未检，1合格，2不合格，3让步',
  `production_date` date DEFAULT NULL COMMENT '生产日期',
  `receiving_time` datetime DEFAULT NULL COMMENT '收料时间',
  `inbound_time` datetime DEFAULT NULL COMMENT '入库时间',
  `reserved_order_no` varchar(100) DEFAULT NULL COMMENT '当前预留出库单号',
  `reserved_order_line_id` bigint DEFAULT NULL COMMENT '当前预留出库单明细主键',
  `port_code` varchar(64) DEFAULT NULL COMMENT '所在端口/作业口编码',
  `source_order_no` varchar(100) DEFAULT NULL COMMENT '来源入库单号',
  `source_order_line_id` bigint DEFAULT NULL COMMENT '来源入库单明细主键',
  `target_place` varchar(64) DEFAULT NULL COMMENT '目标投递位置/机台/工位',
  `label_snapshot` varchar(1000) DEFAULT NULL COMMENT '标签关键字段快照，存储轻量文本，不存大对象',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0存在，1删除',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`inventory_id`),
  UNIQUE KEY `uk_wms_inventory_stock_unit` (`stock_unit_code`),
  KEY `idx_wms_inventory_material` (`material_code`,`status`,`inbound_status`),
  KEY `idx_wms_inventory_pick_condition` (`material_code`,`plant_code`,`warehouse_code`,`status`,`frozen_status`,`inbound_status`,`production_date`,`container_id`),
  KEY `idx_wms_inventory_reel_light` (`stock_unit_code`,`container_id`,`status`,`inbound_status`),
  KEY `idx_wms_inventory_container_comp` (`container_id`,`status`,`inbound_status`,`compartment_code`),
  KEY `idx_wms_inventory_storage` (`storage_id`,`status`),
  KEY `idx_wms_inventory_reserved` (`reserved_order_no`,`reserved_order_line_id`,`status`),
  KEY `idx_wms_inventory_batch` (`material_code`,`material_batch`,`date_code`),
  KEY `idx_wms_inventory_supplier` (`supplier_code`,`material_code`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS库存表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_inventory_flow` (
  `flow_id` bigint NOT NULL COMMENT '库存流水主键，系统生成',
  `flow_no` varchar(64) NOT NULL COMMENT '库存流水号，业务唯一',
  `flow_type` varchar(32) NOT NULL COMMENT '流水类型：INBOUND、PUTAWAY、RESERVE、RELEASE、PICK、OUTBOUND、MOVE、ADJUST、FREEZE、UNFREEZE',
  `inventory_id` bigint DEFAULT NULL COMMENT '库存主键快照，逻辑关联 wms_inventory.inventory_id',
  `stock_unit_code` varchar(100) NOT NULL COMMENT '库存单元编码快照',
  `material_code` varchar(50) NOT NULL COMMENT '物料编码快照',
  `material_batch` varchar(64) DEFAULT NULL COMMENT '物料批次快照',
  `from_container_code` varchar(64) DEFAULT NULL COMMENT '来源容器编码',
  `to_container_code` varchar(64) DEFAULT NULL COMMENT '目标容器编码',
  `from_storage_code` varchar(64) DEFAULT NULL COMMENT '来源储位编码',
  `to_storage_code` varchar(64) DEFAULT NULL COMMENT '目标储位编码',
  `before_qty` decimal(20,6) DEFAULT NULL COMMENT '变更前数量',
  `change_qty` decimal(20,6) NOT NULL COMMENT '变更数量，增加为正，减少为负',
  `after_qty` decimal(20,6) DEFAULT NULL COMMENT '变更后数量',
  `source_order_type` varchar(32) DEFAULT NULL COMMENT '来源单据类型：INBOUND、OUTBOUND、ADJUST、MOVE等',
  `source_order_no` varchar(100) DEFAULT NULL COMMENT '来源单号',
  `source_order_line_id` bigint DEFAULT NULL COMMENT '来源单据明细主键',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键，同一业务动作唯一',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人',
  `occurred_at` datetime NOT NULL COMMENT '业务发生时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`flow_id`),
  UNIQUE KEY `uk_wms_inventory_flow_no` (`flow_no`),
  UNIQUE KEY `uk_wms_inventory_flow_idem` (`idempotency_key`),
  KEY `idx_wms_inventory_flow_stock_time` (`stock_unit_code`,`occurred_at`),
  KEY `idx_wms_inventory_flow_source` (`source_order_type`,`source_order_no`,`source_order_line_id`),
  KEY `idx_wms_inventory_flow_type_time` (`flow_type`,`occurred_at`),
  KEY `idx_wms_inventory_flow_material_time` (`material_code`,`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS库存流水表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_inventory_history` (
  `inventory_history_id` bigint NOT NULL COMMENT '库存历史主键，系统生成',
  `source_inventory_id` bigint DEFAULT NULL COMMENT '来源库存主键，归档前的 wms_inventory.inventory_id',
  `stock_unit_code` varchar(100) NOT NULL COMMENT '库存单元编码快照，SMT通常为REEL ID/盘号/唯一标签号',
  `material_code` varchar(50) NOT NULL COMMENT '物料编码快照',
  `material_name` varchar(200) DEFAULT NULL COMMENT '物料名称快照',
  `material_batch` varchar(64) DEFAULT NULL COMMENT '物料批次号快照',
  `date_code` varchar(50) DEFAULT NULL COMMENT 'Date Code快照',
  `supplier_code` varchar(50) DEFAULT NULL COMMENT '供应商编码快照',
  `supplier_name` varchar(100) DEFAULT NULL COMMENT '供应商名称快照',
  `plant_code` varchar(50) DEFAULT NULL COMMENT '厂别编码快照',
  `warehouse_code` varchar(50) DEFAULT NULL COMMENT '仓库编码/仓别快照',
  `zone_id` bigint DEFAULT NULL COMMENT '归档前库区主键快照',
  `zone_code` varchar(50) DEFAULT NULL COMMENT '归档前库区编码快照',
  `storage_id` bigint DEFAULT NULL COMMENT '归档前储位主键快照',
  `storage_code` varchar(64) DEFAULT NULL COMMENT '归档前储位编码快照',
  `container_id` bigint DEFAULT NULL COMMENT '归档前容器主键快照',
  `container_code` varchar(64) DEFAULT NULL COMMENT '归档前容器编码快照',
  `compartment_id` bigint DEFAULT NULL COMMENT '归档前格口主键快照',
  `compartment_code` varchar(50) DEFAULT NULL COMMENT '归档前格口编码快照',
  `inventory_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '归档数量',
  `reserved_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '归档时已预留/已扣占数量',
  `unit` varchar(16) DEFAULT NULL COMMENT '库存单位快照',
  `inventory_status` tinyint NOT NULL COMMENT '归档前库存状态：参考 wms_inventory.status',
  `frozen_status` tinyint NOT NULL DEFAULT '0' COMMENT '归档前冻结状态：0未冻结，1冻结',
  `inbound_status` tinyint NOT NULL DEFAULT '0' COMMENT '归档前入库完成状态：0未完成，1已完成',
  `quality_status` tinyint NOT NULL DEFAULT '0' COMMENT '归档前品质状态：0未检，1合格，2不合格，3让步',
  `production_date` date DEFAULT NULL COMMENT '生产日期快照',
  `receiving_time` datetime DEFAULT NULL COMMENT '收料时间快照',
  `inbound_time` datetime DEFAULT NULL COMMENT '入库时间快照',
  `off_shelf_time` datetime DEFAULT NULL COMMENT '下架/出库完成时间',
  `used_by_order_no` varchar(100) DEFAULT NULL COMMENT '使用该库存的出库单号，参考项目 used_by_code',
  `used_by_order_line_id` bigint DEFAULT NULL COMMENT '使用该库存的出库单明细主键，参考项目 used_by_body_code',
  `port_code` varchar(64) DEFAULT NULL COMMENT '出库口/作业口编码快照',
  `source_order_no` varchar(100) DEFAULT NULL COMMENT '来源入库单号快照',
  `source_order_line_id` bigint DEFAULT NULL COMMENT '来源入库单明细主键快照',
  `target_place` varchar(64) DEFAULT NULL COMMENT '目标投递位置/机台/工位快照',
  `archive_reason` varchar(255) DEFAULT NULL COMMENT '归档原因：OUTBOUND、CANCEL、ADJUST、EXCEPTION、MANUAL等',
  `archive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
  `archived_by` bigint DEFAULT NULL COMMENT '归档操作人',
  `source_create_time` datetime DEFAULT NULL COMMENT '来源库存创建时间',
  `source_update_time` datetime DEFAULT NULL COMMENT '来源库存最后更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`inventory_history_id`),
  KEY `idx_wms_inv_hist_source_inventory` (`source_inventory_id`),
  KEY `idx_wms_inv_hist_stock_archive` (`stock_unit_code`,`archive_time`),
  KEY `idx_wms_inv_hist_material_archive` (`material_code`,`archive_time`,`inventory_history_id`),
  KEY `idx_wms_inv_hist_batch` (`material_code`,`material_batch`,`date_code`),
  KEY `idx_wms_inv_hist_container_archive` (`container_code`,`archive_time`),
  KEY `idx_wms_inv_hist_storage_archive` (`storage_code`,`archive_time`),
  KEY `idx_wms_inv_hist_used_order` (`used_by_order_no`,`used_by_order_line_id`,`stock_unit_code`),
  KEY `idx_wms_inv_hist_off_shelf` (`off_shelf_time`,`used_by_order_no`,`stock_unit_code`),
  KEY `idx_wms_inv_hist_supplier` (`supplier_code`,`material_code`,`archive_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS库存历史表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_legacy_id_map` (
  `map_id` bigint NOT NULL AUTO_INCREMENT COMMENT '旧标识映射主键，系统生成',
  `domain` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '映射域，如 COMPANY/WAREHOUSE/STORAGE',
  `source_table` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '源表名',
  `source_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '源业务键，精确比较',
  `source_digest` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '源业务摘要，SHA-256 小写十六进制',
  `target_type` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标对象类型',
  `target_id` bigint NOT NULL COMMENT '目标内部主键',
  `mapping_status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MAPPED' COMMENT '状态：MAPPED/VERIFIED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`map_id`),
  UNIQUE KEY `uk_wms_legacy_id_map_source` (`domain`,`source_table`,`source_key`),
  UNIQUE KEY `uk_wms_legacy_id_map_target` (`target_type`,`target_id`),
  CONSTRAINT `ck_wms_legacy_id_map_status` CHECK ((`mapping_status` in (_utf8mb4'MAPPED',_utf8mb4'VERIFIED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS旧业务标识到目标内部标识映射'
;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_material` (
  `material_id` bigint NOT NULL COMMENT '物料主键，系统生成',
  `material_code` varchar(50) NOT NULL COMMENT '物料编码，业务唯一',
  `material_name` varchar(200) NOT NULL COMMENT '物料名称',
  `material_type` varchar(50) DEFAULT NULL COMMENT '物料类型',
  `material_grade` varchar(50) DEFAULT NULL COMMENT '物料等级',
  `package_quantity` decimal(20,6) DEFAULT NULL COMMENT '标准包装数量',
  `unit` varchar(16) DEFAULT NULL COMMENT '库存单位',
  `length_value` decimal(12,3) DEFAULT NULL COMMENT '长度',
  `width_value` decimal(12,3) DEFAULT NULL COMMENT '宽度',
  `height_value` decimal(12,3) DEFAULT NULL COMMENT '高度',
  `dimension_unit` varchar(16) DEFAULT 'mm' COMMENT '尺寸单位',
  `weight_value` decimal(18,6) DEFAULT NULL COMMENT '单件重量',
  `weight_unit` varchar(16) DEFAULT 'g' COMMENT '重量单位',
  `volume_value` decimal(18,6) DEFAULT NULL COMMENT '体积',
  `validity_period` int DEFAULT NULL COMMENT '有效期数值',
  `validity_unit` varchar(16) DEFAULT NULL COMMENT '有效期单位：DAY、MONTH、YEAR',
  `exposure_time` int DEFAULT NULL COMMENT '暴露时长',
  `exposure_unit` varchar(16) DEFAULT NULL COMMENT '暴露时长单位：HOUR、DAY',
  `supplier_id` bigint DEFAULT NULL COMMENT '默认供应商主键，逻辑关联 wms_supplier.supplier_id',
  `supplier_code` varchar(50) DEFAULT NULL COMMENT '默认供应商编码，逻辑关联 wms_supplier.supplier_code',
  `supplier_name` varchar(100) DEFAULT NULL COMMENT '默认供应商名称快照',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0停用，1启用',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0存在，1删除',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`material_id`),
  UNIQUE KEY `uk_wms_material_code` (`material_code`),
  KEY `idx_wms_material_type_status` (`material_type`,`status`),
  KEY `idx_wms_material_grade_status` (`material_grade`,`status`),
  KEY `idx_wms_material_supplier_id` (`supplier_id`),
  KEY `idx_wms_material_supplier_status` (`supplier_code`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS物料表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_object_audit` (
  `audit_id` bigint NOT NULL COMMENT '审计主键，系统生成',
  `object_type` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '对象类型，如 WMS_COMPANY',
  `object_id` bigint NOT NULL COMMENT '对象内部ID',
  `action` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '动作：CREATE/UPDATE/ENABLE/DISABLE/MANAGEMENT_SCOPE_REPLACE',
  `command_key` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '命令键，用于幂等与追责',
  `before_summary` varchar(2000) DEFAULT NULL COMMENT '变更前摘要（脱敏快照，超长截断）',
  `after_summary` varchar(2000) DEFAULT NULL COMMENT '变更后摘要（脱敏快照，超长截断）',
  `actor_user_id` bigint DEFAULT NULL COMMENT '当前操作者',
  `actor_name` varchar(64) DEFAULT NULL COMMENT '操作者名称快照',
  `initiator_type` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '发起人类型：USER/PLAN/SYSTEM',
  `initiator_id` varchar(128) DEFAULT NULL COMMENT '发起人标识',
  `initiator_name` varchar(64) DEFAULT NULL COMMENT '发起人名称快照',
  `dept_id` bigint DEFAULT NULL COMMENT '操作者部门快照',
  `dept_name` varchar(128) DEFAULT NULL COMMENT '操作者部门名称快照',
  `execution_subject` varchar(64) DEFAULT NULL COMMENT '执行主体名称快照',
  `execution_subject_type` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '执行主体类型：USER/PLAN/SYSTEM',
  `execution_account` varchar(64) DEFAULT NULL COMMENT '执行账号快照',
  `result` varchar(16) COLLATE utf8mb4_bin NOT NULL COMMENT '结果：SUCCESS/REJECTED/FAILED/UNKNOWN',
  `evidence_json` varchar(2000) DEFAULT NULL COMMENT '证据引用或幂等摘要',
  `occurred_at` datetime(3) NOT NULL COMMENT '业务发生时间',
  `recorded_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审计记录时间',
  PRIMARY KEY (`audit_id`),
  KEY `idx_wms_object_audit_object` (`object_type`,`object_id`,`occurred_at`),
  KEY `idx_wms_object_audit_command_key` (`command_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS对象变更审计，只追加';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_outbound_order` (
  `outbound_order_id` bigint NOT NULL COMMENT '出库单头主键，系统生成',
  `outbound_order_no` varchar(100) NOT NULL COMMENT '出库单号，业务唯一',
  `outbound_type` tinyint NOT NULL DEFAULT '1' COMMENT '出库类型：1工单领料，2叫料出库，3退供出库，4调整出库',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '单据状态：0新建，1已预留，2执行中，3已完成，4已取消，5异常',
  `priority` tinyint NOT NULL DEFAULT '5' COMMENT '优先级，数字越小优先级越高',
  `pick_mode` tinyint NOT NULL DEFAULT '1' COMMENT '拣选模式：1按物料条件，2指定库存单元，3指定容器',
  `port_code` varchar(64) DEFAULT NULL COMMENT '出库口/投递口编码',
  `work_order_no` varchar(100) DEFAULT NULL COMMENT '工单号',
  `side_code` varchar(50) DEFAULT NULL COMMENT '线边/面别/站别编码',
  `source_system` varchar(32) DEFAULT NULL COMMENT '来源系统，例如MES、ERP、WCS、MANUAL',
  `source_order_no` varchar(100) DEFAULT NULL COMMENT '外部来源单号',
  `start_time` datetime DEFAULT NULL COMMENT '开始执行时间',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0存在，1删除',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`outbound_order_id`),
  UNIQUE KEY `uk_wms_outbound_order_no` (`outbound_order_no`),
  KEY `idx_wms_outbound_order_status` (`status`,`priority`,`create_time`),
  KEY `idx_wms_outbound_order_work` (`work_order_no`,`side_code`,`status`),
  KEY `idx_wms_outbound_order_source` (`source_system`,`source_order_no`),
  KEY `idx_wms_outbound_order_port` (`port_code`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS出库单头表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_outbound_order_history` (
  `outbound_history_id` bigint NOT NULL COMMENT '出库单头历史主键，系统生成',
  `source_outbound_order_id` bigint DEFAULT NULL COMMENT '来源出库单头主键，归档前的 wms_outbound_order.outbound_order_id',
  `outbound_order_no` varchar(100) NOT NULL COMMENT '出库单号快照，业务唯一',
  `outbound_type` tinyint NOT NULL COMMENT '出库类型快照：1工单领料，2叫料出库，3退供出库，4调整出库',
  `status` tinyint NOT NULL COMMENT '归档时单据状态：参考 wms_outbound_order.status',
  `priority` tinyint NOT NULL DEFAULT '5' COMMENT '优先级快照，数字越小优先级越高',
  `pick_mode` tinyint NOT NULL DEFAULT '1' COMMENT '拣选模式快照：1按物料条件，2指定库存单元，3指定容器',
  `port_code` varchar(64) DEFAULT NULL COMMENT '出库口/投递口编码快照',
  `other_port_code` varchar(64) DEFAULT NULL COMMENT '备用出库口/其他出库端口',
  `work_order_no` varchar(100) DEFAULT NULL COMMENT '工单号快照',
  `side_code` varchar(50) DEFAULT NULL COMMENT '线边/面别/站别编码快照',
  `carry_type` tinyint DEFAULT NULL COMMENT '搬运类型：0人工，1CTU/自动搬运',
  `source_system` varchar(32) DEFAULT NULL COMMENT '来源系统快照',
  `source_order_no` varchar(100) DEFAULT NULL COMMENT '外部来源单号快照',
  `start_time` datetime DEFAULT NULL COMMENT '开始执行时间快照',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间快照',
  `finish_reason` varchar(255) DEFAULT NULL COMMENT '完成/关闭原因',
  `archive_reason` varchar(255) DEFAULT NULL COMMENT '归档原因：COMPLETE、CANCEL、MES_COMPLETE、MANUAL_CLOSE、EXCEPTION等',
  `archive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
  `archived_by` bigint DEFAULT NULL COMMENT '归档操作人',
  `source_create_time` datetime DEFAULT NULL COMMENT '来源单据创建时间',
  `source_update_time` datetime DEFAULT NULL COMMENT '来源单据最后更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`outbound_history_id`),
  UNIQUE KEY `uk_wms_outbound_hist_no` (`outbound_order_no`),
  KEY `idx_wms_outbound_hist_source_id` (`source_outbound_order_id`),
  KEY `idx_wms_outbound_hist_status` (`status`,`priority`,`archive_time`),
  KEY `idx_wms_outbound_hist_work_side` (`work_order_no`,`side_code`,`outbound_order_no`),
  KEY `idx_wms_outbound_hist_source` (`source_system`,`source_order_no`),
  KEY `idx_wms_outbound_hist_port` (`port_code`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS出库单头历史表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_outbound_order_line` (
  `outbound_line_id` bigint NOT NULL COMMENT '出库单明细主键，系统生成',
  `outbound_order_id` bigint NOT NULL COMMENT '出库单头主键，逻辑关联 wms_outbound_order.outbound_order_id',
  `outbound_order_no` varchar(100) NOT NULL COMMENT '出库单号快照',
  `line_no` int NOT NULL DEFAULT '1' COMMENT '行号',
  `material_code` varchar(50) NOT NULL COMMENT '需求物料编码',
  `material_name` varchar(200) DEFAULT NULL COMMENT '物料名称快照',
  `request_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '需求数量',
  `reserved_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '已预留数量',
  `issued_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '已出库数量',
  `unit` varchar(16) DEFAULT NULL COMMENT '单位',
  `stock_unit_code` varchar(100) DEFAULT NULL COMMENT '指定库存单元编码，指定REEL出库时使用',
  `container_code` varchar(64) DEFAULT NULL COMMENT '指定容器编码，指定容器出库时使用',
  `material_batch` varchar(64) DEFAULT NULL COMMENT '批次条件',
  `date_code` varchar(50) DEFAULT NULL COMMENT 'Date Code条件',
  `supplier_code` varchar(50) DEFAULT NULL COMMENT '供应商条件',
  `supplier_name` varchar(100) DEFAULT NULL COMMENT '供应商名称快照',
  `plant_code` varchar(50) DEFAULT NULL COMMENT '厂别条件',
  `warehouse_code` varchar(50) DEFAULT NULL COMMENT '仓库编码/仓别条件',
  `target_place` varchar(64) DEFAULT NULL COMMENT '目标投递位置/机台/工位',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '明细状态：0待预留，1已预留，2执行中，3已完成，4短缺，5已取消，6异常',
  `shortage_flag` tinyint NOT NULL DEFAULT '0' COMMENT '是否短缺：0否，1是',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`outbound_line_id`),
  KEY `idx_wms_outbound_line_order_status` (`outbound_order_id`,`status`),
  KEY `idx_wms_outbound_line_no_status` (`outbound_order_no`,`status`),
  KEY `idx_wms_outbound_line_pick` (`material_code`,`plant_code`,`warehouse_code`,`status`),
  KEY `idx_wms_outbound_line_stock` (`stock_unit_code`),
  KEY `idx_wms_outbound_line_container` (`container_code`,`status`),
  KEY `idx_wms_outbound_line_target` (`target_place`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS出库单明细表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_outbound_order_line_history` (
  `outbound_line_history_id` bigint NOT NULL COMMENT '出库单明细历史主键，系统生成',
  `source_outbound_line_id` bigint DEFAULT NULL COMMENT '来源出库单明细主键，归档前的 wms_outbound_order_line.outbound_line_id',
  `outbound_history_id` bigint DEFAULT NULL COMMENT '出库单头历史主键，逻辑关联 wms_outbound_order_history.outbound_history_id',
  `source_outbound_order_id` bigint DEFAULT NULL COMMENT '来源出库单头主键快照',
  `outbound_order_no` varchar(100) NOT NULL COMMENT '出库单号快照',
  `line_no` int NOT NULL DEFAULT '1' COMMENT '行号快照',
  `material_code` varchar(50) NOT NULL COMMENT '需求物料编码快照',
  `material_name` varchar(200) DEFAULT NULL COMMENT '物料名称快照',
  `request_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '需求数量快照',
  `reserved_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '已预留数量快照',
  `issued_qty` decimal(20,6) NOT NULL DEFAULT '0.000000' COMMENT '已出库数量快照',
  `unit` varchar(16) DEFAULT NULL COMMENT '单位快照',
  `stock_unit_code` varchar(100) DEFAULT NULL COMMENT '指定或实际出库库存单元编码快照',
  `container_code` varchar(64) DEFAULT NULL COMMENT '指定或实际出库容器编码快照',
  `material_batch` varchar(64) DEFAULT NULL COMMENT '批次条件/批次快照',
  `date_code` varchar(50) DEFAULT NULL COMMENT 'Date Code条件/快照',
  `supplier_code` varchar(50) DEFAULT NULL COMMENT '供应商编码条件/快照',
  `supplier_name` varchar(100) DEFAULT NULL COMMENT '供应商名称快照',
  `plant_code` varchar(50) DEFAULT NULL COMMENT '来源厂别条件/快照',
  `warehouse_code` varchar(50) DEFAULT NULL COMMENT '来源仓库编码/仓别条件/快照',
  `dst_plant_code` varchar(50) DEFAULT NULL COMMENT '目的厂别编码快照',
  `dst_warehouse_code` varchar(50) DEFAULT NULL COMMENT '目的仓库编码/目的仓别快照',
  `production_line` varchar(50) DEFAULT NULL COMMENT '生产线快照',
  `target_place` varchar(64) DEFAULT NULL COMMENT '目标投递位置/机台/工位快照',
  `status` tinyint NOT NULL COMMENT '归档时明细状态：参考 wms_outbound_order_line.status',
  `shortage_flag` tinyint NOT NULL DEFAULT '0' COMMENT '是否短缺：0否，1是',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间快照',
  `archive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
  `source_create_time` datetime DEFAULT NULL COMMENT '来源明细创建时间',
  `source_update_time` datetime DEFAULT NULL COMMENT '来源明细最后更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`outbound_line_history_id`),
  KEY `idx_wms_out_line_hist_source` (`source_outbound_line_id`),
  KEY `idx_wms_out_line_hist_order_status` (`outbound_order_no`,`status`),
  KEY `idx_wms_out_line_hist_pick` (`material_code`,`plant_code`,`warehouse_code`,`status`),
  KEY `idx_wms_out_line_hist_stock` (`stock_unit_code`,`outbound_order_no`),
  KEY `idx_wms_out_line_hist_container` (`container_code`,`status`),
  KEY `idx_wms_out_line_hist_target` (`target_place`,`status`),
  KEY `idx_wms_out_line_hist_shortage` (`shortage_flag`,`archive_time`),
  KEY `idx_wms_out_line_hist_archive` (`archive_time`,`outbound_line_history_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS出库单明细历史表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_outbound_order_push_log` (
  `outbound_push_log_id` bigint NOT NULL COMMENT '出库单推送记录主键，系统生成',
  `external_request_no` varchar(100) DEFAULT NULL COMMENT '上层系统请求流水号/消息ID',
  `source_system` varchar(32) NOT NULL DEFAULT 'MES' COMMENT '来源上层系统：MES、ERP、SRM、MANUAL等',
  `interface_code` varchar(64) NOT NULL COMMENT '接口编码，例如IWS108-1、IWS108-2、IWS108-3、OUTBOUND_ORDER_PUSH',
  `interface_name` varchar(100) DEFAULT NULL COMMENT '接口名称',
  `outbound_order_no` varchar(100) DEFAULT NULL COMMENT '出库单号，接收报文中可能为空或失败时解析不到',
  `source_order_no` varchar(100) DEFAULT NULL COMMENT '上层系统原始单号',
  `outbound_type` tinyint DEFAULT NULL COMMENT '出库类型快照：1工单领料，2叫料出库，3退供出库，4调整出库',
  `work_order_no` varchar(100) DEFAULT NULL COMMENT '工单号快照',
  `side_code` varchar(50) DEFAULT NULL COMMENT '线边/面别/站别编码快照',
  `stock_unit_code` varchar(100) DEFAULT NULL COMMENT '库存单元编码，SMT通常为REEL ID；指定REEL出库或主查询条件使用',
  `stock_unit_list` longtext COMMENT '本次推送涉及的库存单元清单JSON，批量或指定REEL出库时使用',
  `push_stage` tinyint NOT NULL DEFAULT '1' COMMENT '推送阶段：1整单，2单头，3明细，4状态确认',
  `attempt_no` int NOT NULL DEFAULT '1' COMMENT '同一来源单据同一接口第几次推送/接收',
  `total_count` int DEFAULT NULL COMMENT '本次报文总明细数',
  `success_count` int DEFAULT NULL COMMENT 'WMS本次成功接收明细数',
  `failed_count` int DEFAULT NULL COMMENT 'WMS本次失败明细数',
  `failed_stock_units` longtext COMMENT '失败库存单元清单JSON，例如失败REEL清单',
  `failed_items` longtext COMMENT '失败明细清单JSON，例如失败REEL/行号/错误原因',
  `request_payload` longtext COMMENT '上层系统推送请求报文JSON',
  `response_payload` longtext COMMENT 'WMS响应报文JSON',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '接收状态：0处理中，1成功，2部分成功，3失败',
  `error_code` varchar(64) DEFAULT NULL COMMENT '错误码/上层系统返回码',
  `error_message` longtext COMMENT '错误信息/异常摘要',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `idempotency_key` varchar(128) DEFAULT NULL COMMENT '幂等键，同一上层系统推送动作唯一',
  `cost_time_ms` bigint DEFAULT NULL COMMENT '接口处理耗时，单位毫秒',
  `received_time` datetime DEFAULT NULL COMMENT 'WMS接收到推送的时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建者/处理人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`outbound_push_log_id`),
  UNIQUE KEY `uk_wms_outbound_push_idem` (`idempotency_key`),
  KEY `idx_wms_outbound_push_request` (`external_request_no`),
  KEY `idx_wms_outbound_push_order` (`outbound_order_no`,`interface_code`,`create_time`),
  KEY `idx_wms_outbound_push_source` (`source_system`,`source_order_no`),
  KEY `idx_wms_outbound_push_work` (`work_order_no`,`side_code`,`create_time`),
  KEY `idx_wms_outbound_push_stock` (`stock_unit_code`,`create_time`),
  KEY `idx_wms_outbound_push_status` (`status`,`create_time`),
  KEY `idx_wms_outbound_push_time` (`create_time`,`outbound_push_log_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS出库单推送记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_schema_version` (
  `schema_version_id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键，系统生成',
  `component_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '组件编码，机器稳定标识',
  `schema_version` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '组件结构版本',
  `release_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发布标识',
  `source_fingerprint` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '升级前源结构指纹，SHA-256 小写十六进制',
  `target_fingerprint` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标结构指纹，SHA-256 小写十六进制',
  `status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '状态：PENDING/APPLIED/VERIFIED/FAILED',
  `applied_by` varchar(64) NOT NULL COMMENT '应用人或执行器标识',
  `applied_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '应用时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`schema_version_id`),
  UNIQUE KEY `uk_wms_schema_version_component_version` (`component_code`,`schema_version`),
  KEY `idx_wms_schema_version_component_status` (`component_code`,`status`,`applied_at`),
  CONSTRAINT `ck_wms_schema_version_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'APPLIED',_utf8mb4'VERIFIED',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS组件结构与发布版本登记'
;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_storage` (
  `storage_id` bigint NOT NULL COMMENT '储位主键，系统生成',
  `storage_code` varchar(64) NOT NULL COMMENT '储位编码，业务唯一',
  `zone_id` bigint NOT NULL COMMENT '所属库区主键，逻辑关联 wms_warehouse_zone.zone_id',
  `storage_type` tinyint NOT NULL DEFAULT '1' COMMENT '储位类型：1普通储位，2亮灯货架位，3播种墙位，4端口位',
  `height_type` tinyint DEFAULT NULL COMMENT '高度类型，用于容器高度匹配',
  `height_desc` varchar(50) DEFAULT NULL COMMENT '高度描述',
  `row_no` int DEFAULT NULL COMMENT '排/行号',
  `col_no` int DEFAULT NULL COMMENT '列号',
  `level_no` int DEFAULT NULL COMMENT '层号',
  `x_coordinate` decimal(12,3) DEFAULT NULL COMMENT 'X坐标，用于WCS/地图展示',
  `y_coordinate` decimal(12,3) DEFAULT NULL COMMENT 'Y坐标，用于WCS/地图展示',
  `port_code` varchar(64) DEFAULT NULL COMMENT '端口编码，端口位或设备交互位使用',
  `wcs_point_code` varchar(64) DEFAULT NULL COMMENT 'WCS点位编码',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '储位状态：0空闲，1占用，2预占，3维护',
  `lock_status` tinyint NOT NULL DEFAULT '0' COMMENT '锁定状态：0未锁定，1已锁定',
  `disable_status` tinyint NOT NULL DEFAULT '1' COMMENT '禁用状态：0启用，1禁用',
  `lifecycle_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '生命周期：ACTIVE/RETIRING/RETIRED',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0存在，1删除',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `warehouse_id` bigint NOT NULL COMMENT '所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；最终结构非空',
  `aisle_text` varchar(64) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '巷道文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
  `row_text` varchar(64) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '排文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
  `level_text` varchar(64) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '层文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
  `column_text` varchar(64) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '列文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
  `depth_text` varchar(64) COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '深文本地址；S1可空，写入前去首尾ASCII空格并将空串置NULL',
  `aisle_text_is_null` tinyint GENERATED ALWAYS AS (`aisle_text` IS NULL) STORED NOT NULL COMMENT '巷道地址空值标志；NULL按相等参与同仓地址唯一键',
  `row_text_is_null` tinyint GENERATED ALWAYS AS (`row_text` IS NULL) STORED NOT NULL COMMENT '排地址空值标志；NULL按相等参与同仓地址唯一键',
  `level_text_is_null` tinyint GENERATED ALWAYS AS (`level_text` IS NULL) STORED NOT NULL COMMENT '层地址空值标志；NULL按相等参与同仓地址唯一键',
  `column_text_is_null` tinyint GENERATED ALWAYS AS (`column_text` IS NULL) STORED NOT NULL COMMENT '列地址空值标志；NULL按相等参与同仓地址唯一键',
  `depth_text_is_null` tinyint GENERATED ALWAYS AS (`depth_text` IS NULL) STORED NOT NULL COMMENT '深地址空值标志；NULL按相等参与同仓地址唯一键',
  `aisle_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(`aisle_text`,'')) STORED NOT NULL COMMENT '巷道地址精确比较值；NULL统一为空串且使用二进制排序规则',
  `row_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(`row_text`,'')) STORED NOT NULL COMMENT '排地址精确比较值；NULL统一为空串且使用二进制排序规则',
  `level_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(`level_text`,'')) STORED NOT NULL COMMENT '层地址精确比较值；NULL统一为空串且使用二进制排序规则',
  `column_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(`column_text`,'')) STORED NOT NULL COMMENT '列地址精确比较值；NULL统一为空串且使用二进制排序规则',
  `depth_text_cmp` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (IFNULL(`depth_text`,'')) STORED NOT NULL COMMENT '深地址精确比较值；NULL统一为空串且使用二进制排序规则',
  PRIMARY KEY (`storage_id`),
  UNIQUE KEY `uk_wms_storage_code` (`storage_code`),
  UNIQUE KEY `uk_wms_storage_warehouse_address` (`warehouse_id`,`aisle_text_is_null`,`row_text_is_null`,`level_text_is_null`,`column_text_is_null`,`depth_text_is_null`,`aisle_text_cmp`,`row_text_cmp`,`level_text_cmp`,`column_text_cmp`,`depth_text_cmp`),
  KEY `idx_wms_storage_zone_status` (`zone_id`,`storage_type`,`status`,`lock_status`,`disable_status`),
  KEY `idx_wms_storage_matrix` (`zone_id`,`row_no`,`col_no`,`level_no`),
  KEY `idx_wms_storage_port` (`port_code`,`status`),
  KEY `idx_wms_storage_wcs_point` (`wcs_point_code`),
  KEY `idx_wms_storage_warehouse_status` (`warehouse_id`,`status`),
  KEY `idx_wms_storage_warehouse_lifecycle_zone` (`warehouse_id`,`lifecycle_status`,`zone_id`),
  CONSTRAINT `ck_wms_storage_aisle_text_trim` CHECK (`aisle_text` IS NULL OR (`aisle_text` <> '' AND CAST(`aisle_text` AS BINARY) = CAST(TRIM(`aisle_text`) AS BINARY))),
  CONSTRAINT `ck_wms_storage_row_text_trim` CHECK (`row_text` IS NULL OR (`row_text` <> '' AND CAST(`row_text` AS BINARY) = CAST(TRIM(`row_text`) AS BINARY))),
  CONSTRAINT `ck_wms_storage_level_text_trim` CHECK (`level_text` IS NULL OR (`level_text` <> '' AND CAST(`level_text` AS BINARY) = CAST(TRIM(`level_text`) AS BINARY))),
  CONSTRAINT `ck_wms_storage_column_text_trim` CHECK (`column_text` IS NULL OR (`column_text` <> '' AND CAST(`column_text` AS BINARY) = CAST(TRIM(`column_text`) AS BINARY))),
  CONSTRAINT `ck_wms_storage_depth_text_trim` CHECK (`depth_text` IS NULL OR (`depth_text` <> '' AND CAST(`depth_text` AS BINARY) = CAST(TRIM(`depth_text`) AS BINARY))),
  CONSTRAINT `ck_wms_storage_lifecycle_status` CHECK (`lifecycle_status` IN (_utf8mb4'ACTIVE',_utf8mb4'RETIRING',_utf8mb4'RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS储位表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_supplier` (
  `supplier_id` bigint NOT NULL COMMENT '供应商主键，系统生成',
  `supplier_code` varchar(50) NOT NULL COMMENT '供应商编码，业务唯一',
  `supplier_name` varchar(100) NOT NULL COMMENT '供应商名称',
  `contact_person` varchar(50) DEFAULT NULL COMMENT '联系人',
  `contact_phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `contact_email` varchar(100) DEFAULT NULL COMMENT '联系邮箱',
  `address` varchar(255) DEFAULT NULL COMMENT '地址',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0停用，1启用',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0存在，1删除',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`supplier_id`),
  UNIQUE KEY `uk_wms_supplier_code` (`supplier_code`),
  KEY `idx_wms_supplier_name` (`supplier_name`),
  KEY `idx_wms_supplier_status` (`status`,`del_flag`),
  KEY `idx_wms_supplier_update_time` (`update_time`,`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS供应商表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_upgrade_checkpoint` (
  `checkpoint_id` bigint NOT NULL AUTO_INCREMENT COMMENT '升级检查点主键，系统生成',
  `run_id` bigint NOT NULL COMMENT '所属升级运行，逻辑关联 wms_upgrade_run.run_id',
  `step_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '步骤编码，机器稳定标识',
  `object_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '对象或批次稳定键，精确比较',
  `status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/APPLIED/VERIFIED/FAILED/SKIPPED',
  `before_digest` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '执行前摘要，SHA-256 小写十六进制',
  `after_digest` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '执行后摘要，SHA-256 小写十六进制',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '该步骤已尝试次数',
  `error_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '失败或阻断机器码',
  `error_message` varchar(1000) DEFAULT NULL COMMENT '中文失败或阻断说明，禁止写入敏感原文',
  `started_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开始时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `completed_at` datetime(3) DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`checkpoint_id`),
  UNIQUE KEY `uk_wms_upgrade_checkpoint_run_step_object` (`run_id`,`step_code`,`object_key`),
  KEY `idx_wms_upgrade_checkpoint_run_status` (`run_id`,`status`,`updated_at`),
  CONSTRAINT `ck_wms_upgrade_checkpoint_attempt` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `ck_wms_upgrade_checkpoint_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'RUNNING',_utf8mb4'APPLIED',_utf8mb4'VERIFIED',_utf8mb4'FAILED',_utf8mb4'SKIPPED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS库存升级步骤检查点'
;
/*!40101 SET character_set_client = @saved_cs_client */;
CREATE TABLE `wms_upgrade_issue` (
  `issue_id` bigint NOT NULL AUTO_INCREMENT COMMENT '升级问题主键，系统生成',
  `run_id` bigint NOT NULL COMMENT '所属升级运行，逻辑关联 wms_upgrade_run.run_id',
  `issue_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '问题机器码，稳定标识',
  `issue_category` varchar(255) NOT NULL COMMENT '中文问题类别',
  `redacted_source_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '脱敏源业务键，禁止写入凭据或完整敏感报文',
  `detail_summary` varchar(1000) NOT NULL COMMENT '中文脱敏问题摘要',
  `blocking_phase` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被阻止的阶段',
  `suggested_action` varchar(1000) NOT NULL COMMENT '建议人工动作',
  `is_blocking` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否阻断后续阶段：1 阻断，0 仅提示',
  `detected_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '检测时间',
  PRIMARY KEY (`issue_id`),
  UNIQUE KEY `uk_wms_upgrade_issue_run_code_key` (`run_id`,`issue_code`,`redacted_source_key`),
  KEY `idx_wms_upgrade_issue_run_blocking` (`run_id`,`blocking_phase`,`is_blocking`),
  CONSTRAINT `ck_wms_upgrade_issue_blocking` CHECK ((`is_blocking` in (0,1))),
  CONSTRAINT `ck_wms_upgrade_issue_phase` CHECK ((`blocking_phase` in (_utf8mb4'SOURCE_VERIFIED',_utf8mb4'CONTROL_READY',_utf8mb4'DDL_PARTIAL',_utf8mb4'DDL_TARGET',_utf8mb4'BACKFILL_PARTIAL',_utf8mb4'DATA_TARGET',_utf8mb4'CUTOVER_READY',_utf8mb4'COMPLETE',_utf8mb4'BLOCKED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS库存升级中文阻碍清单'
;
/*!40101 SET character_set_client = @saved_cs_client */;
CREATE TABLE `wms_upgrade_run` (
  `run_id` bigint NOT NULL AUTO_INCREMENT COMMENT '升级运行主键，系统生成',
  `release_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发布标识',
  `source_fingerprint` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '源结构指纹，SHA-256 小写十六进制',
  `target_fingerprint` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标结构指纹，SHA-256 小写十六进制',
  `phase` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SOURCE_VERIFIED' COMMENT '阶段：SOURCE_VERIFIED/CONTROL_READY/DDL_PARTIAL/DDL_TARGET/BACKFILL_PARTIAL/DATA_TARGET/CUTOVER_READY/COMPLETE/BLOCKED',
  `write_fence_token` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '写冻结令牌，用于关联停止旧写入的证据',
  `status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING/BLOCKED/FAILED/COMPLETED',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '已尝试次数',
  `error_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '失败或阻断机器码',
  `error_message` varchar(1000) DEFAULT NULL COMMENT '中文失败或阻断说明，禁止写入敏感原文',
  `started_by` varchar(64) NOT NULL COMMENT '启动人或执行器标识',
  `started_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开始时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `completed_at` datetime(3) DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`run_id`),
  KEY `idx_wms_upgrade_run_release_started` (`release_id`,`started_at`),
  KEY `idx_wms_upgrade_run_status_phase` (`status`,`phase`,`updated_at`),
  CONSTRAINT `ck_wms_upgrade_run_attempt` CHECK ((`attempt_count` >= 0)),
  CONSTRAINT `ck_wms_upgrade_run_phase` CHECK ((`phase` in (_utf8mb4'SOURCE_VERIFIED',_utf8mb4'CONTROL_READY',_utf8mb4'DDL_PARTIAL',_utf8mb4'DDL_TARGET',_utf8mb4'BACKFILL_PARTIAL',_utf8mb4'DATA_TARGET',_utf8mb4'CUTOVER_READY',_utf8mb4'COMPLETE',_utf8mb4'BLOCKED'))),
  CONSTRAINT `ck_wms_upgrade_run_status` CHECK ((`status` in (_utf8mb4'RUNNING',_utf8mb4'BLOCKED',_utf8mb4'FAILED',_utf8mb4'COMPLETED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS库存升级运行'
;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_warehouse` (
  `warehouse_id` bigint NOT NULL COMMENT '仓库主键，系统生成',
  `company_id` bigint NOT NULL COMMENT '所属公司主键，逻辑关联 wms_company.company_id',
  `warehouse_code` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库编码，同公司内唯一且使用精确比较',
  `warehouse_name` varchar(100) NOT NULL COMMENT '仓库名称，创建后可按受控业务修改',
  `warehouse_type_code` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库类型编码，如 GENERAL/FLOOR；楼层只存于类型配置',
  `type_config_version_id` bigint DEFAULT NULL COMMENT '仓库类型配置版本主键；配置版本表后续建立',
  `lifecycle_status` varchar(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '生命周期：ACTIVE/RETIRING/RETIRED',
  `enabled` tinyint NOT NULL DEFAULT '0' COMMENT '启用状态：0停用，1启用；与生命周期独立',
  `revision` int NOT NULL DEFAULT '1' COMMENT '仓库乐观锁版本，创建为1',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime(3) DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`warehouse_id`),
  UNIQUE KEY `uk_wms_warehouse_company_code` (`company_id`,`warehouse_code`),
  KEY `idx_wms_warehouse_company_status` (`company_id`,`lifecycle_status`,`warehouse_type_code`),
  CONSTRAINT `ck_wms_warehouse_enabled` CHECK (`enabled` IN (0,1)),
  CONSTRAINT `ck_wms_warehouse_lifecycle_status` CHECK (`lifecycle_status` IN (_utf8mb4'ACTIVE',_utf8mb4'RETIRING',_utf8mb4'RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS仓库主表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wms_warehouse_zone` (
  `zone_id` bigint NOT NULL COMMENT '库区主键，系统生成',
  `zone_code` varchar(50) NOT NULL COMMENT '库区编码，业务唯一',
  `zone_name` varchar(100) NOT NULL COMMENT '库区名称',
  `zone_type` tinyint NOT NULL DEFAULT '1' COMMENT '库区类型：1普通库区，2混料区，3品质区，4退料区，5播种墙，6亮灯货架区，7虚拟区',
  `zone_kind` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '库区种类：MATERIAL/DEVICE',
  `lifecycle_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '生命周期：ACTIVE/RETIRING/RETIRED',
  `plant_code` varchar(50) DEFAULT NULL COMMENT '厂别编码，跨厂区部署时使用',
  `warehouse_code` varchar(50) DEFAULT NULL COMMENT '仓库编码，对应现场仓别或仓库类型',
  `parent_zone_id` bigint DEFAULT NULL COMMENT '上级库区主键，逻辑关联 wms_warehouse_zone.zone_id',
  `allow_inbound` tinyint NOT NULL DEFAULT '1' COMMENT '是否允许入库：0否，1是',
  `allow_outbound` tinyint NOT NULL DEFAULT '1' COMMENT '是否允许出库：0否，1是',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0停用，1启用',
  `revision` int NOT NULL DEFAULT '1' COMMENT '库区乐观锁修订号',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '显示顺序',
  `del_flag` char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0存在，1删除',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `warehouse_id` bigint NOT NULL COMMENT '所属仓库主键，逻辑关联 wms_warehouse.warehouse_id；最终结构非空',
  PRIMARY KEY (`zone_id`),
  UNIQUE KEY `uk_wms_zone_code` (`zone_code`),
  KEY `idx_wms_zone_plant_wh_status` (`plant_code`,`warehouse_code`,`status`),
  KEY `idx_wms_zone_type_status` (`zone_type`,`status`),
  KEY `idx_wms_zone_parent` (`parent_zone_id`),
  KEY `idx_wms_zone_warehouse_status` (`warehouse_id`,`status`),
  KEY `idx_wms_zone_warehouse_kind_lifecycle` (`warehouse_id`,`zone_kind`,`lifecycle_status`),
  CONSTRAINT `ck_wms_zone_kind` CHECK (`zone_kind` IN (_utf8mb4'MATERIAL',_utf8mb4'DEVICE')),
  CONSTRAINT `ck_wms_warehouse_zone_lifecycle_status` CHECK (`lifecycle_status` IN (_utf8mb4'ACTIVE',_utf8mb4'RETIRING',_utf8mb4'RETIRED')),
  CONSTRAINT `ck_wms_zone_revision` CHECK (`revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS库区表';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!50503 SET character_set_client = utf8mb4 */;
-- 空间、访问通道、位置版本、可见范围与容量十表目标结构。
-- 仅建结构，不写业务、容量或权限种子。
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;

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

-- ----------------------------------------------------------------------------
-- 系统必要种子数据：默认模板账号、角色、菜单、字典、参数、客户端、OSS 模板与流程基础配置
-- ----------------------------------------------------------------------------

-- SnailJob 控制台默认账号：innolux；部署后更换默认密码。
INSERT INTO `sj_system_user` (`username`, `password`, `role`)
VALUES ('innolux', 'b60d758e3a5e5fcd41375f0aea397e43ae731bc9c2251396550908c1fae79a76', 2);

-- SnailJob 命名空间与默认组：客户端按 snail-job.namespace（默认取 profile 名）和 group 注册；
-- 组缺失或令牌不一致时执行器注册失败，QUEUE 事件与监督任务无处可派。
SET @init_snailjob_group_token = 'SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT';
INSERT INTO `sj_namespace` (`id`, `name`, `unique_id`, `description`, `deleted`, `create_dt`, `update_dt`)
VALUES (1, 'Development', 'dev', '', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       (2, 'Production', 'prod', '', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO `sj_group_config` (`id`, `namespace_id`, `group_name`, `description`, `token`,
                               `group_status`, `version`, `group_partition`, `id_generator_mode`,
                               `init_scene`, `create_dt`, `update_dt`)
VALUES (1, 'dev', 'iws_group', '', @init_snailjob_group_token, 1, 1, 0, 1, 1,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       (2, 'prod', 'iws_group', '', @init_snailjob_group_token, 1, 1, 0, 1, 1,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000100,0,'0','IWS+',NULL,0,NULL,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000101,1761000000000000100,'0,1761000000000000100','INNOLUX',NULL,1,NULL,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000102,1761000000000000100,'0,1761000000000000100','WMS运营中心',NULL,2,NULL,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000103,1761000000000000101,'0,1761000000000000100,1761000000000000101','系统管理部',NULL,1,1761100000000000001,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000104,1761000000000000101,'0,1761000000000000100,1761000000000000101','仓储作业部',NULL,2,NULL,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000105,1761000000000000101,'0,1761000000000000100,1761000000000000101','品质管控部',NULL,3,NULL,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000106,1761000000000000101,'0,1761000000000000100,1761000000000000101','数据监控部',NULL,4,NULL,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000107,1761000000000000101,'0,1761000000000000100,1761000000000000101','设备运维部',NULL,5,NULL,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000108,1761000000000000102,'0,1761000000000000100,1761000000000000102','入库管理组',NULL,1,NULL,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_dept` (`dept_id`, `parent_id`, `ancestors`, `dept_name`, `dept_category`, `order_num`, `leader`, `phone`, `email`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1761000000000000109,1761000000000000102,'0,1761000000000000100,1761000000000000102','出库管理组',NULL,2,NULL,'15888888888','wms@innolux.com','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:51',NULL,NULL);
INSERT INTO `sys_user` (`user_id`, `dept_id`, `user_name`, `nick_name`, `user_type`, `email`, `phone_number`, `gender`, `avatar`, `password`, `status`, `del_flag`, `login_ip`, `login_date`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761100000000000001,1761000000000000103,'innolux','INNOLUX管理员','sys_user','wms@innolux.com','15888888888','1',NULL,'$2a$10$RSIyc3MpGVMKPGxfE6no3.EV3Hn0iq88rjqPt1xToGkwuXSZ0wfVm','0','0','127.0.0.1','2026-10-23 21:05:52',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'管理员');
INSERT INTO `sys_post` (`post_id`, `dept_id`, `post_code`, `post_category`, `post_name`, `post_sort`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761200000000000001,1761000000000000103,'ceo',NULL,'董事长',1,'0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'');
INSERT INTO `sys_post` (`post_id`, `dept_id`, `post_code`, `post_category`, `post_name`, `post_sort`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761200000000000002,1761000000000000100,'se',NULL,'项目经理',2,'0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'');
INSERT INTO `sys_post` (`post_id`, `dept_id`, `post_code`, `post_category`, `post_name`, `post_sort`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761200000000000003,1761000000000000100,'hr',NULL,'人力资源',3,'0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'');
INSERT INTO `sys_post` (`post_id`, `dept_id`, `post_code`, `post_category`, `post_name`, `post_sort`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761200000000000004,1761000000000000100,'user',NULL,'普通员工',4,'0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'');
INSERT INTO `sys_role` (`role_id`, `role_name`, `role_key`, `role_sort`, `data_scope`, `menu_check_strictly`, `dept_check_strictly`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761300000000000001,'超级管理员','superadmin',1,'1',1,1,'0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'超级管理员');
INSERT INTO `sys_role` (`role_id`, `role_name`, `role_key`, `role_sort`, `data_scope`, `menu_check_strictly`, `dept_check_strictly`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761300000000000003,'本部门及以下','test1',3,'4',1,1,'0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'');
INSERT INTO `sys_role` (`role_id`, `role_name`, `role_key`, `role_sort`, `data_scope`, `menu_check_strictly`, `dept_check_strictly`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761300000000000004,'仅本人','test2',4,'5',1,1,'0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000001,'系统管理',0,1,'system',NULL,'','N','Y','M','0','0','','system','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'系统管理目录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000002,'系统监控',0,3,'monitor',NULL,'','N','Y','M','0','0','','monitor','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'系统监控目录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000003,'系统工具',0,4,'tool',NULL,'','N','Y','M','0','0','','tool','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'系统工具目录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000004,'INNOLUX官网',0,9999,'https://www.innolux.com',NULL,'','Y','Y','M','0','0','','guide','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'INNOLUX官网地址，固定排在全部菜单之后', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000005,'测试菜单',0,5,'demo',NULL,'','N','Y','M','0','0','','star','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'测试菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000008,'AI会话',0,8,'aichat','ai/chat/index','','N','Y','C','0','0','','checkbox','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'AI聊天菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000100,'用户管理',1761400000000000001,1,'user','system/user/index','','N','Y','C','1','0','system:user:list','user','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'用户管理菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000101,'角色管理',1761400000000000001,2,'role','system/role/index','','N','Y','C','1','0','system:role:list','peoples','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'角色管理菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000102,'菜单管理',1761400000000000001,3,'menu','system/menu/index','','N','Y','C','1','0','system:menu:list','tree-table','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'菜单管理菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000103,'部门管理',1761400000000000001,4,'dept','system/dept/index','','N','Y','C','1','0','system:dept:list','tree','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'部门管理菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000104,'岗位管理',1761400000000000001,5,'post','system/post/index','','N','Y','C','1','0','system:post:list','post','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'岗位管理菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000105,'字典管理',1761400000000000001,6,'dict','system/dict/index','','N','Y','C','1','0','system:dict:list','dict','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'字典管理菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000106,'参数设置',1761400000000000001,7,'config','system/config/index','','N','Y','C','1','0','system:config:list','edit','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'参数设置菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000107,'通知公告',1761400000000000001,8,'notice','system/notice/index','','N','Y','C','0','0','system:notice:list','message','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'通知公告菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000108,'日志管理',1761400000000000001,9,'log','','','N','Y','M','0','0','','log','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'日志管理菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000109,'在线用户',1761400000000000002,1,'online','monitor/online/index','','N','Y','C','1','0','monitor:online:list','online','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'在线用户菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000113,'缓存监控',1761400000000000002,5,'cache','monitor/cache/index','','N','Y','C','0','0','monitor:cache:list','redis','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'缓存监控菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000115,'代码生成',1761400000000000003,2,'gen','tool/gen/index','','N','Y','C','0','0','tool:gen:list','code','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'代码生成菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000132,'接口文档',1761400000000000003,9,'api-doc','monitor/apiDoc/index','','N','Y','C','0','0','monitor:apidoc:list','documentation','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'接口文档菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000116,'修改生成配置',1761400000000000003,2,'gen-edit/index/:tableId','tool/gen/editTable','','N','N','C','1','0','tool:gen:edit','#','/tool/gen','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000117,'Admin监控',1761400000000000002,5,'Admin','monitor/admin/index','','N','Y','C','0','0','monitor:admin:list','dashboard','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'Admin监控菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000118,'文件管理',1761400000000000001,10,'oss','system/oss/index','','N','Y','C','0','0','system:oss:list','upload','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'文件管理菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000120,'任务调度中心',1761400000000000002,6,'snailjob','monitor/snailjob/index','','N','Y','C','0','0','monitor:snailjob:list','job','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'SnailJob控制台菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000121,'AI控制台',1761400000000000002,7,'snailai','monitor/snailai/index','','N','Y','C','0','0','monitor:snailai:list','checkbox','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'AI控制台菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000190001,'IWS A 接入',1761400000000000001,90,'','','','N','Y','F','1','0','iam:app:iws:a','#','','',1761000000000000103,1761100000000000001,'2026-11-03 00:00:00',NULL,NULL,'统一身份多 IWS 接入权限', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000190002,'IWS B 接入',1761400000000000001,91,'','','','N','Y','F','1','0','iam:app:iws:b','#','','',1761000000000000103,1761100000000000001,'2026-11-03 00:00:00',NULL,NULL,'统一身份多 IWS 接入权限', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000190003,'PDA 接入',1761400000000000001,92,'','','','N','Y','F','1','0','iam:app:pda','#','','',1761000000000000103,1761100000000000001,'2026-11-03 00:00:00',NULL,NULL,'统一身份 PDA 接入权限', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000123,'客户端管理',1761400000000000001,11,'client','system/client/index','','N','Y','C','1','0','system:client:list','international','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'客户端管理菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000130,'分配用户',1761400000000000001,2,'role-auth/user/:roleId','system/role/authUser','','N','N','C','1','0','system:role:edit','#','/system/role','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000131,'分配角色',1761400000000000001,1,'user-auth/role/:userId','system/user/authRole','','N','N','C','1','0','system:user:edit','#','/system/user','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000133,'文件配置管理',1761400000000000001,10,'oss-config/index','system/oss/config','','N','N','C','1','0','system:ossConfig:list','#','/system/oss','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000500,'操作日志',1761400000000000108,1,'operlog','monitor/operlog/index','','N','Y','C','0','0','monitor:operlog:list','form','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'操作日志菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000000501,'登录日志',1761400000000000108,2,'logininfo','monitor/logininfo/index','','N','Y','C','0','0','monitor:logininfo:list','logininfo','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'登录日志菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001001,'用户查询',1761400000000000100,1,'','','','N','Y','F','0','0','system:user:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001002,'用户新增',1761400000000000100,2,'','','','N','Y','F','0','0','system:user:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001003,'用户修改',1761400000000000100,3,'','','','N','Y','F','0','0','system:user:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001004,'用户删除',1761400000000000100,4,'','','','N','Y','F','0','0','system:user:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001005,'用户导出',1761400000000000100,5,'','','','N','Y','F','0','0','system:user:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001006,'用户导入',1761400000000000100,6,'','','','N','Y','F','0','0','system:user:import','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001007,'重置密码',1761400000000000100,7,'','','','N','Y','F','0','0','system:user:resetPwd','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001008,'角色查询',1761400000000000101,1,'','','','N','Y','F','0','0','system:role:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001009,'角色新增',1761400000000000101,2,'','','','N','Y','F','0','0','system:role:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001010,'角色修改',1761400000000000101,3,'','','','N','Y','F','0','0','system:role:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001011,'角色删除',1761400000000000101,4,'','','','N','Y','F','0','0','system:role:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001012,'角色导出',1761400000000000101,5,'','','','N','Y','F','0','0','system:role:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001013,'菜单查询',1761400000000000102,1,'','','','N','Y','F','0','0','system:menu:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001014,'菜单新增',1761400000000000102,2,'','','','N','Y','F','0','0','system:menu:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001015,'菜单修改',1761400000000000102,3,'','','','N','Y','F','0','0','system:menu:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001016,'菜单删除',1761400000000000102,4,'','','','N','Y','F','0','0','system:menu:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001017,'部门查询',1761400000000000103,1,'','','','N','Y','F','0','0','system:dept:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001018,'部门新增',1761400000000000103,2,'','','','N','Y','F','0','0','system:dept:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001019,'部门修改',1761400000000000103,3,'','','','N','Y','F','0','0','system:dept:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001020,'部门删除',1761400000000000103,4,'','','','N','Y','F','0','0','system:dept:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001021,'岗位查询',1761400000000000104,1,'','','','N','Y','F','0','0','system:post:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001022,'岗位新增',1761400000000000104,2,'','','','N','Y','F','0','0','system:post:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001023,'岗位修改',1761400000000000104,3,'','','','N','Y','F','0','0','system:post:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001024,'岗位删除',1761400000000000104,4,'','','','N','Y','F','0','0','system:post:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001025,'岗位导出',1761400000000000104,5,'','','','N','Y','F','0','0','system:post:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001026,'字典查询',1761400000000000105,1,'#','','','N','Y','F','0','0','system:dict:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001027,'字典新增',1761400000000000105,2,'#','','','N','Y','F','0','0','system:dict:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001028,'字典修改',1761400000000000105,3,'#','','','N','Y','F','0','0','system:dict:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001029,'字典删除',1761400000000000105,4,'#','','','N','Y','F','0','0','system:dict:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001030,'字典导出',1761400000000000105,5,'#','','','N','Y','F','0','0','system:dict:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001031,'参数查询',1761400000000000106,1,'#','','','N','Y','F','0','0','system:config:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001032,'参数新增',1761400000000000106,2,'#','','','N','Y','F','0','0','system:config:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001033,'参数修改',1761400000000000106,3,'#','','','N','Y','F','0','0','system:config:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001034,'参数删除',1761400000000000106,4,'#','','','N','Y','F','0','0','system:config:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001035,'参数导出',1761400000000000106,5,'#','','','N','Y','F','0','0','system:config:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001036,'公告查询',1761400000000000107,1,'#','','','N','Y','F','0','0','system:notice:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001037,'公告新增',1761400000000000107,2,'#','','','N','Y','F','0','0','system:notice:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001038,'公告修改',1761400000000000107,3,'#','','','N','Y','F','0','0','system:notice:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001039,'公告删除',1761400000000000107,4,'#','','','N','Y','F','0','0','system:notice:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001040,'操作查询',1761400000000000500,1,'#','','','N','Y','F','0','0','monitor:operlog:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001041,'操作删除',1761400000000000500,2,'#','','','N','Y','F','0','0','monitor:operlog:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001042,'日志导出',1761400000000000500,4,'#','','','N','Y','F','0','0','monitor:operlog:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001043,'登录查询',1761400000000000501,1,'#','','','N','Y','F','0','0','monitor:logininfo:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001044,'登录删除',1761400000000000501,2,'#','','','N','Y','F','0','0','monitor:logininfo:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001045,'日志导出',1761400000000000501,3,'#','','','N','Y','F','0','0','monitor:logininfo:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001046,'在线查询',1761400000000000109,1,'#','','','N','Y','F','0','0','monitor:online:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001047,'批量强退',1761400000000000109,2,'#','','','N','Y','F','0','0','monitor:online:batchLogout','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001048,'单条强退',1761400000000000109,3,'#','','','N','Y','F','0','0','monitor:online:forceLogout','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001050,'账户解锁',1761400000000000501,4,'#','','','N','Y','F','0','0','monitor:logininfo:unlock','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001055,'生成查询',1761400000000000115,1,'#','','','N','Y','F','0','0','tool:gen:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001056,'生成修改',1761400000000000115,2,'#','','','N','Y','F','0','0','tool:gen:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001057,'生成删除',1761400000000000115,3,'#','','','N','Y','F','0','0','tool:gen:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001058,'导入代码',1761400000000000115,2,'#','','','N','Y','F','0','0','tool:gen:import','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001059,'预览代码',1761400000000000115,4,'#','','','N','Y','F','0','0','tool:gen:preview','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001060,'生成代码',1761400000000000115,5,'#','','','N','Y','F','0','0','tool:gen:code','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001061,'客户端管理查询',1761400000000000123,1,'#','','','N','Y','F','0','0','system:client:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001062,'客户端管理新增',1761400000000000123,2,'#','','','N','Y','F','0','0','system:client:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001063,'客户端管理修改',1761400000000000123,3,'#','','','N','Y','F','0','0','system:client:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001064,'客户端管理删除',1761400000000000123,4,'#','','','N','Y','F','0','0','system:client:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001065,'客户端管理导出',1761400000000000123,5,'#','','','N','Y','F','0','0','system:client:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001500,'测试单表',1761400000000000005,1,'demo','demo/demo/index','','N','Y','C','0','0','demo:demo:list','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'测试单表菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001501,'测试单表查询',1761400000000001500,1,'#','','','N','Y','F','0','0','demo:demo:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001502,'测试单表新增',1761400000000001500,2,'#','','','N','Y','F','0','0','demo:demo:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001503,'测试单表修改',1761400000000001500,3,'#','','','N','Y','F','0','0','demo:demo:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001504,'测试单表删除',1761400000000001500,4,'#','','','N','Y','F','0','0','demo:demo:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001505,'测试单表导出',1761400000000001500,5,'#','','','N','Y','F','0','0','demo:demo:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001506,'测试树表',1761400000000000005,1,'tree','demo/tree/index','','N','Y','C','0','0','demo:tree:list','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'测试树表菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001507,'测试树表查询',1761400000000001506,1,'#','','','N','Y','F','0','0','demo:tree:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001508,'测试树表新增',1761400000000001506,2,'#','','','N','Y','F','0','0','demo:tree:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001509,'测试树表修改',1761400000000001506,3,'#','','','N','Y','F','0','0','demo:tree:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001510,'测试树表删除',1761400000000001506,4,'#','','','N','Y','F','0','0','demo:tree:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001511,'测试树表导出',1761400000000001506,5,'#','','','N','Y','F','0','0','demo:tree:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001600,'文件查询',1761400000000000118,1,'#','','','N','Y','F','0','0','system:oss:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001601,'文件上传',1761400000000000118,2,'#','','','N','Y','F','0','0','system:oss:upload','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001602,'文件下载',1761400000000000118,3,'#','','','N','Y','F','0','0','system:oss:download','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001603,'文件删除',1761400000000000118,4,'#','','','N','Y','F','0','0','system:oss:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001620,'配置列表',1761400000000000118,5,'#','','','N','Y','F','0','0','system:ossConfig:list','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001621,'配置添加',1761400000000000118,6,'#','','','N','Y','F','0','0','system:ossConfig:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001622,'配置编辑',1761400000000000118,6,'#','','','N','Y','F','0','0','system:ossConfig:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000001623,'配置删除',1761400000000000118,6,'#','','','N','Y','F','0','0','system:ossConfig:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011616,'工作流',0,6,'workflow','','','N','Y','M','0','0','','workflow','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011618,'我的任务',0,7,'task','','','N','Y','M','0','0','','my-task','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011619,'我的待办',1761400000000011618,2,'taskWaiting','workflow/task/taskWaiting','','N','N','C','0','0','','waiting','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011620,'流程定义',1761400000000011616,3,'processDefinition','workflow/processDefinition/index','','N','N','C','0','0','workflow:definition:list','process-definition','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011621,'流程实例',1761400000000011630,1,'processInstance','workflow/processInstance/index','','N','N','C','0','0','workflow:instance:list','tree-table','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011622,'流程分类',1761400000000011616,1,'category','workflow/category/index','','N','Y','C','0','0','workflow:category:list','category','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011623,'流程分类查询',1761400000000011622,1,'#','','','N','Y','F','0','0','workflow:category:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011624,'流程分类新增',1761400000000011622,2,'#','','','N','Y','F','0','0','workflow:category:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011625,'流程分类修改',1761400000000011622,3,'#','','','N','Y','F','0','0','workflow:category:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011626,'流程分类删除',1761400000000011622,4,'#','','','N','Y','F','0','0','workflow:category:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011627,'流程分类导出',1761400000000011622,5,'#','','','N','Y','F','0','0','workflow:category:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011629,'我发起的',1761400000000011618,1,'myDocument','workflow/task/myDocument','','N','N','C','0','0','workflow:instance:currentList','guide','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011630,'流程监控',1761400000000011616,4,'processMonitor','','','N','Y','M','0','0','','monitor','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011631,'待办任务',1761400000000011630,2,'allTaskWaiting','workflow/task/allTaskWaiting','','N','N','C','0','0','workflow:task:list','waiting','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011632,'我的已办',1761400000000011618,3,'taskFinish','workflow/task/taskFinish','','N','N','C','0','0','','finish','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011633,'我的抄送',1761400000000011618,4,'taskCopyList','workflow/task/taskCopyList','','N','N','C','0','0','','my-copy','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011638,'请假申请',1761400000000000005,1,'leave','workflow/leave/index','','N','Y','C','0','0','workflow:leave:list','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'请假申请菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011639,'请假申请查询',1761400000000011638,1,'#','','','N','Y','F','0','0','workflow:leave:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011640,'请假申请新增',1761400000000011638,2,'#','','','N','Y','F','0','0','workflow:leave:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011641,'请假申请修改',1761400000000011638,3,'#','','','N','Y','F','0','0','workflow:leave:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011642,'请假申请删除',1761400000000011638,4,'#','','','N','Y','F','0','0','workflow:leave:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011643,'请假申请导出',1761400000000011638,5,'#','','','N','Y','F','0','0','workflow:leave:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011644,'流程定义查询',1761400000000011620,1,'#','','','N','Y','F','0','0','workflow:definition:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011645,'流程定义新增',1761400000000011620,2,'#','','','N','Y','F','0','0','workflow:definition:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011646,'流程定义修改',1761400000000011620,3,'#','','','N','Y','F','0','0','workflow:definition:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011647,'流程定义删除',1761400000000011620,4,'#','','','N','Y','F','0','0','workflow:definition:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011648,'流程定义导出',1761400000000011620,5,'#','','','N','Y','F','0','0','workflow:definition:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011649,'流程定义导入',1761400000000011620,6,'#','','','N','Y','F','0','0','workflow:definition:import','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011650,'流程定义发布/取消发布',1761400000000011620,7,'#','','','N','Y','F','0','0','workflow:definition:publish','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011651,'流程定义复制',1761400000000011620,8,'#','','','N','Y','F','0','0','workflow:definition:copy','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011652,'流程定义激活/挂起',1761400000000011620,9,'#','','','N','Y','F','0','0','workflow:definition:active','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011653,'流程实例查询',1761400000000011621,1,'#','','','N','Y','F','0','0','workflow:instance:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011654,'流程变量查询',1761400000000011621,2,'#','','','N','Y','F','0','0','workflow:instance:variableQuery','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011655,'流程变量修改',1761400000000011621,3,'#','','','N','Y','F','0','0','workflow:instance:variable','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011656,'流程实例激活/挂起',1761400000000011621,4,'#','','','N','Y','F','0','0','workflow:instance:active','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011657,'流程实例删除',1761400000000011621,5,'#','','','N','Y','F','0','0','workflow:instance:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011658,'流程实例作废',1761400000000011621,6,'#','','','N','Y','F','0','0','workflow:instance:invalid','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011659,'流程实例撤销',1761400000000011621,7,'#','','','N','Y','F','0','0','workflow:instance:cancel','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011660,'待办任务修改',1761400000000011631,1,'#','','','N','Y','F','0','0','workflow:task:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011700,'流程设计',1761400000000011616,5,'design/index','workflow/processDefinition/design','','N','N','C','1','0','workflow:leave:edit','#','/workflow/processDefinition','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011701,'请假申请',1761400000000011616,6,'leaveEdit/index','workflow/leave/leaveEdit','','N','N','C','1','0','workflow:leave:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011801,'流程表达式',1761400000000011616,2,'spel','workflow/spel/index','','N','Y','C','0','0','workflow:spel:list','input','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',1761100000000000001,'2026-10-23 21:05:57','流程达式定义菜单', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011802,'流程达式定义查询',1761400000000011801,1,'#','',NULL,'N','Y','F','0','0','workflow:spel:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011803,'流程达式定义新增',1761400000000011801,2,'#','',NULL,'N','Y','F','0','0','workflow:spel:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011804,'流程达式定义修改',1761400000000011801,3,'#','',NULL,'N','Y','F','0','0','workflow:spel:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011805,'流程达式定义删除',1761400000000011801,4,'#','',NULL,'N','Y','F','0','0','workflow:spel:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000011806,'流程达式定义导出',1761400000000011801,5,'#','',NULL,'N','Y','F','0','0','workflow:spel:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020001,'基础数据',0,10,'iws-base',NULL,'','N','Y','M','0','0','','table','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'IWS+基础数据目录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020002,'入库管理',0,11,'iws-inbound',NULL,'','N','Y','M','0','0','','download','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'IWS+入库管理目录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020003,'储位管理',0,12,'iws-storage',NULL,'','N','Y','M','0','0','','box','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'IWS+储位管理目录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020004,'在库管理',0,13,'iws-inventory',NULL,'','N','Y','M','0','0','','list','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'IWS+在库管理目录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020005,'出库管理',0,14,'iws-outbound',NULL,'','N','Y','M','0','0','','upload','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'IWS+出库管理目录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020100,'供应商标准页',1761400000000020001,1,'supplier','wms/basic/supplier/index','','N','Y','C','0','0','wms:supplier:list','table','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:52',NULL,NULL,'IWS+供应商标准CRUD样板页', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020101,'供应商查询',1761400000000020100,1,'#','','','N','Y','F','0','0','wms:supplier:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020102,'供应商新增',1761400000000020100,2,'#','','','N','Y','F','0','0','wms:supplier:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020103,'供应商修改',1761400000000020100,3,'#','','','N','Y','F','0','0','wms:supplier:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020104,'供应商删除',1761400000000020100,4,'#','','','N','Y','F','0','0','wms:supplier:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020105,'供应商批量删除',1761400000000020100,5,'#','','','N','Y','F','0','0','wms:supplier:batchRemove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020106,'供应商导入',1761400000000020100,6,'#','','','N','Y','F','0','0','wms:supplier:import','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020107,'供应商下载模板',1761400000000020100,7,'#','','','N','Y','F','0','0','wms:supplier:template','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020108,'供应商导出',1761400000000020100,8,'#','','','N','Y','F','0','0','wms:supplier:export','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020109,'供应商列设置',1761400000000020100,9,'#','','','N','Y','F','0','0','wms:supplier:column','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000020110,'供应商批量搜索',1761400000000020100,10,'#','','','N','Y','F','0','0','wms:supplier:bulkQuery','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:53',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030001,'系统接入配置',0,6,'endpoint',NULL,NULL,'N','Y','M','0','0','','connection','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'IWS系统定义、环境与共享地址基础配置', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030002,'系统定义',1761400000000030001,1,'system','endpoint/system/index',NULL,'N','Y','C','0','0','endpoint:system:list','server','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'逻辑系统目录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030003,'环境配置',1761400000000030001,2,'environment','endpoint/environment/index',NULL,'N','Y','C','0','0','endpoint:environment:list','tree','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'环境目录，当前运行环境只读展示', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030004,'系统地址',1761400000000030001,3,'address','endpoint/address/index',NULL,'N','Y','C','0','0','endpoint:address:list','link','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'环境共享地址与系统关联', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030011,'系统查询',1761400000000030002,1,'',NULL,NULL,'N','Y','F','0','0','endpoint:system:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030012,'系统新增',1761400000000030002,2,'',NULL,NULL,'N','Y','F','0','0','endpoint:system:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030013,'系统修改',1761400000000030002,3,'',NULL,NULL,'N','Y','F','0','0','endpoint:system:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030014,'配置审计查询',1761400000000030002,4,'',NULL,NULL,'N','Y','F','0','0','endpoint:audit:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030015,'环境查询',1761400000000030003,1,'',NULL,NULL,'N','Y','F','0','0','endpoint:environment:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030016,'环境新增',1761400000000030003,2,'',NULL,NULL,'N','Y','F','0','0','endpoint:environment:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030017,'环境修改',1761400000000030003,3,'',NULL,NULL,'N','Y','F','0','0','endpoint:environment:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030018,'地址查询',1761400000000030004,1,'',NULL,NULL,'N','Y','F','0','0','endpoint:address:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030019,'地址新增',1761400000000030004,2,'',NULL,NULL,'N','Y','F','0','0','endpoint:address:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000030020,'地址修改',1761400000000030004,3,'',NULL,NULL,'N','Y','F','0','0','endpoint:address:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031001,'计划事件',0,7,'process',NULL,NULL,'N','Y','M','0','0','','tree-table','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'计划定义、事件执行与监督调度', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031002,'业务调度',1761400000000031001,1,'schedule','process/schedule/index',NULL,'N','Y','C','0','0','process:schedule:list','calendar','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'业务统一调度与监督控制', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031003,'计划定义',1761400000000031001,2,'definition','process/definition/index',NULL,'N','Y','C','0','0','process:definition:list','tree','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'计划版本、状态、事件与边', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031004,'执行总览',1761400000000031001,3,'execution','process/execution/index',NULL,'N','Y','C','0','0','process:execution:list','dashboard','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'执行、队列、异常与接口抛账记录', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031005,'计划详情',1761400000000031001,4,'instance/detail','process/instance/detail',NULL,'N','Y','C','1','0','process:instance:query','#','/process/execution','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'隐藏计划执行链详情', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031006,'计划配置编辑',1761400000000031001,5,'definition/edit','process/definition/edit',NULL,'N','Y','C','1','0','process:definition:edit','#','/process/definition','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'隐藏计划配置编辑页', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031011,'调度查询',1761400000000031002,1,'',NULL,NULL,'N','Y','F','0','0','process:schedule:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031012,'调度新增',1761400000000031002,2,'',NULL,NULL,'N','Y','F','0','0','process:schedule:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031013,'调度修改',1761400000000031002,3,'',NULL,NULL,'N','Y','F','0','0','process:schedule:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031014,'调度删除',1761400000000031002,4,'',NULL,NULL,'N','Y','F','0','0','process:schedule:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031015,'监督暂停',1761400000000031002,5,'',NULL,NULL,'N','Y','F','0','0','process:schedule:pause','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031016,'监督恢复',1761400000000031002,6,'',NULL,NULL,'N','Y','F','0','0','process:schedule:resume','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031017,'监督重启worker',1761400000000031002,7,'',NULL,NULL,'N','Y','F','0','0','process:schedule:restart','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031018,'计划定义查询',1761400000000031003,1,'',NULL,NULL,'N','Y','F','0','0','process:definition:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031019,'计划草稿新增',1761400000000031003,2,'',NULL,NULL,'N','Y','F','0','0','process:definition:add','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031020,'计划草稿修改',1761400000000031003,3,'',NULL,NULL,'N','Y','F','0','0','process:definition:edit','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031021,'计划版本复制',1761400000000031003,4,'',NULL,NULL,'N','Y','F','0','0','process:definition:copy','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031022,'计划发布',1761400000000031003,5,'',NULL,NULL,'N','Y','F','0','0','process:definition:publish','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031023,'计划草稿删除',1761400000000031003,6,'',NULL,NULL,'N','Y','F','0','0','process:definition:remove','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031024,'执行链查询',1761400000000031004,1,'',NULL,NULL,'N','Y','F','0','0','process:instance:chain','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031025,'任务补挂',1761400000000031004,2,'',NULL,NULL,'N','Y','F','0','0','process:instance:attach','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031026,'首次确认',1761400000000031004,3,'',NULL,NULL,'N','Y','F','0','0','process:instance:confirm','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031027,'实例暂停',1761400000000031004,4,'',NULL,NULL,'N','Y','F','0','0','process:instance:pause','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031028,'实例恢复',1761400000000031004,5,'',NULL,NULL,'N','Y','F','0','0','process:instance:resume','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031029,'实例取消',1761400000000031004,6,'',NULL,NULL,'N','Y','F','0','0','process:instance:cancel','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031030,'挂起计划',1761400000000031004,7,'',NULL,NULL,'N','Y','F','0','0','process:instance:hold','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031031,'放行计划',1761400000000031004,8,'',NULL,NULL,'N','Y','F','0','0','process:instance:release','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031032,'实例优先级',1761400000000031004,9,'',NULL,NULL,'N','Y','F','0','0','process:instance:priority','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031033,'即时执行',1761400000000031004,10,'',NULL,NULL,'N','Y','F','0','0','process:instance:execute','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031034,'触发受理',1761400000000031004,11,'',NULL,NULL,'N','Y','F','0','0','process:trigger:receive','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031035,'事件查询',1761400000000031004,12,'',NULL,NULL,'N','Y','F','0','0','process:event:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031036,'事件重试',1761400000000031004,13,'',NULL,NULL,'N','Y','F','0','0','process:event:retry','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031037,'事件重新校验',1761400000000031004,14,'',NULL,NULL,'N','Y','F','0','0','process:event:revalidate','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031038,'事件最终失败',1761400000000031004,15,'',NULL,NULL,'N','Y','F','0','0','process:event:finalize','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031039,'事件插队',1761400000000031004,16,'',NULL,NULL,'N','Y','F','0','0','process:event:priority','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031040,'请求报文查看',1761400000000031004,17,'',NULL,NULL,'N','Y','F','0','0','process:event:request','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031041,'审计时间线',1761400000000031004,18,'',NULL,NULL,'N','Y','F','0','0','process:audit:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031042,'抛账记录列表',1761400000000031004,19,'',NULL,NULL,'N','Y','F','0','0','process:request:list','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000031043,'抛账记录详情',1761400000000031004,20,'',NULL,NULL,'N','Y','F','0','0','process:request:query','#','','',1761000000000000103,1761100000000000001,'2026-10-23 21:06:15',NULL,NULL,'', 'iws-a');
INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES (1761100000000000001,1761300000000000001);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000001);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000005);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000100);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000101);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000102);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000103);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000104);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000105);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000106);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000107);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000108);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000118);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000123);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000130);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000131);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000133);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000500);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000000501);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001001);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001002);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001003);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001004);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001005);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001006);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001007);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001008);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001009);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001010);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001011);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001012);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001013);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001014);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001015);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001016);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001017);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001018);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001019);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001020);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001021);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001022);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001023);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001024);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001025);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001026);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001027);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001028);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001029);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001030);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001031);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001032);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001033);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001034);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001035);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001036);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001037);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001038);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001039);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001040);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001041);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001042);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001043);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001044);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001045);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001050);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001061);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001062);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001063);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001064);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001065);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001500);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001501);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001502);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001503);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001504);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001505);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001506);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001507);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001508);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001509);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001510);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001511);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001600);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001601);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001602);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001603);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001620);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001621);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001622);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000001623);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011616);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011618);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011619);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011622);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011623);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011629);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011632);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011633);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011638);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011639);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011640);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011641);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011642);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011643);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000011701);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020001);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020002);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020003);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020004);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020005);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020100);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020101);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020102);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020103);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020104);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020105);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020106);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020107);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020108);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020109);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000003,1761400000000020110);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000000005);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001500);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001501);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001502);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001503);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001504);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001505);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001506);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001507);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001508);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001509);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001510);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1761300000000000004,1761400000000001511);
INSERT INTO `sys_user_post` (`user_id`, `post_id`) VALUES (1761100000000000001,1761200000000000001);
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000001,'用户性别','sys_user_gender',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'用户性别列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000002,'菜单状态','sys_show_hide',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'菜单状态列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000003,'系统开关','sys_normal_disable',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'系统开关列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000006,'系统是否','sys_yes_no',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'系统是否列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000007,'通知类型','sys_notice_type',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'通知类型列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000008,'通知状态','sys_notice_status',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'通知状态列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000009,'操作类型','sys_oper_type',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'操作类型列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000010,'系统状态','sys_common_status',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'登录状态列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000011,'授权类型','sys_grant_type',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'认证授权类型');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000012,'设备类型','sys_device_type',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'客户端设备类型');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000013,'业务状态','wf_business_status',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'业务状态列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000014,'表单类型','wf_form_type',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'表单类型列表');
INSERT INTO `sys_dict_type` (`dict_id`, `dict_name`, `dict_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761500000000000015,'任务状态','wf_task_status',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'任务状态');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000001,1,'男','0','sys_user_gender','','','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'性别男');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000002,2,'女','1','sys_user_gender','','','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'性别女');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000003,3,'未知','2','sys_user_gender','','','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'性别未知');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000004,1,'显示','0','sys_show_hide','','primary','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'显示菜单');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000005,2,'隐藏','1','sys_show_hide','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'隐藏菜单');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000006,1,'正常','0','sys_normal_disable','','primary','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'正常状态');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000007,2,'停用','1','sys_normal_disable','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'停用状态');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000012,1,'是','Y','sys_yes_no','','primary','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'系统默认是');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000013,2,'否','N','sys_yes_no','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'系统默认否');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000014,1,'通知','1','sys_notice_type','','warning','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'通知');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000015,2,'公告','2','sys_notice_type','','success','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'公告');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000016,1,'正常','0','sys_notice_status','','primary','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'正常状态');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000017,2,'关闭','1','sys_notice_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'关闭状态');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000018,1,'新增','1','sys_oper_type','','info','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'新增操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000019,2,'修改','2','sys_oper_type','','info','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'修改操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000020,3,'删除','3','sys_oper_type','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'删除操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000021,4,'授权','4','sys_oper_type','','primary','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'授权操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000022,5,'导出','5','sys_oper_type','','warning','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'导出操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000023,6,'导入','6','sys_oper_type','','warning','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'导入操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000024,7,'强退','7','sys_oper_type','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'强退操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000025,8,'生成代码','8','sys_oper_type','','warning','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'生成操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000026,9,'清空数据','9','sys_oper_type','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'清空操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000027,1,'成功','0','sys_common_status','','primary','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'正常状态');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000028,2,'失败','1','sys_common_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'停用状态');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000029,99,'其他','0','sys_oper_type','','info','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'其他操作');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000030,0,'密码认证','password','sys_grant_type','el-check-tag','default','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'密码认证');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000031,0,'短信认证','sms','sys_grant_type','el-check-tag','default','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'短信认证');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000032,0,'邮件认证','email','sys_grant_type','el-check-tag','default','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'邮件认证');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000033,0,'小程序认证','xcx','sys_grant_type','el-check-tag','default','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'小程序认证');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000034,0,'三方登录认证','social','sys_grant_type','el-check-tag','default','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'三方登录认证');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000035,0,'PC','pc','sys_device_type','','default','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'PC');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000036,0,'安卓','android','sys_device_type','','default','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'安卓');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000037,0,'iOS','ios','sys_device_type','','default','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'iOS');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000038,0,'小程序','xcx','sys_device_type','','default','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'小程序');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000039,1,'已撤销','cancel','wf_business_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'已撤销');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000040,2,'草稿','draft','wf_business_status','','info','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'草稿');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000041,3,'待审核','waiting','wf_business_status','','primary','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'待审核');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000042,4,'已完成','finish','wf_business_status','','success','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'已完成');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000043,5,'已作废','invalid','wf_business_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'已作废');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000044,6,'已退回','back','wf_business_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'已退回');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000045,7,'已终止','termination','wf_business_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'已终止');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000046,1,'自定义表单','static','wf_form_type','','success','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'自定义表单');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000047,2,'动态表单','dynamic','wf_form_type','','primary','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'动态表单');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000048,1,'撤销','cancel','wf_task_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'撤销');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000049,2,'通过','pass','wf_task_status','','success','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'通过');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000050,3,'待审核','waiting','wf_task_status','','primary','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'待审核');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000051,4,'作废','invalid','wf_task_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'作废');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000052,5,'退回','back','wf_task_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'退回');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000053,6,'终止','termination','wf_task_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'终止');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000054,7,'转办','transfer','wf_task_status','','primary','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'转办');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000055,8,'委托','depute','wf_task_status','','primary','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'委托');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000056,9,'抄送','copy','wf_task_status','','primary','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'抄送');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000057,10,'加签','sign','wf_task_status','','primary','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'加签');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000058,11,'减签','sign_off','wf_task_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'减签');
INSERT INTO `sys_dict_data` (`dict_code`, `dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761600000000000059,11,'超时','timeout','wf_task_status','','danger','N',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL,'超时');
INSERT INTO `sys_config` (`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761700000000000001,'用户管理-账号初始密码','sys.user.initPassword','123456','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'初始化密码 123456');
INSERT INTO `sys_config` (`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761700000000000002,'账号自助-是否开启用户注册功能','sys.account.registerUser','false','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'是否开启注册用户功能（true开启，false关闭）');
INSERT INTO `sys_config` (`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761700000000000003,'OSS预览列表资源开关','sys.oss.previewListResource','true','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'true:开启, false:关闭');
INSERT INTO `sys_config` (`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761700000000000004,'登录安全-是否开启验证码','sys.login.captchaEnabled','false','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'true开启登录验证码，false关闭；默认关闭');
INSERT INTO `sys_config` (`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761700000000000005,'登录安全-Token活跃超时秒数','sys.login.activeTimeout','1800','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'指定时间无操作则过期，单位秒；空值时使用客户端配置');
INSERT INTO `sys_config` (`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761700000000000006,'登录安全-Token固定超时秒数','sys.login.timeout','604800','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'Token固定有效期，单位秒；空值时使用客户端配置');
INSERT INTO `sys_config` (`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761700000000000007,'界面体验-标准提示自动关闭秒数','sys.ui.messageCloseSeconds','30','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'标准消息提示自动关闭时间，单位秒；默认30秒，可手动关闭');
INSERT INTO `sys_config` (`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761700000000000008,'界面体验-菜单编号前缀开关','sys.ui.menuPrefixEnabled','false','Y',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',NULL,NULL,'true显示菜单编号前缀，false隐藏；默认关闭，刷新页面后生效');
INSERT INTO `sys_client` (`id`, `client_id`, `client_key`, `client_secret`, `grant_type`, `device_type`, `access_path`, `ip_whitelist`, `active_timeout`, `timeout`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762000000000000001,'e5cd7e4891bf95d1d19206ce24a7b32e','pc','pc123','password,social','pc',NULL,NULL,1800,604800,'0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:55',1761100000000000001,'2026-10-23 21:05:55');
INSERT INTO `sys_client` (`id`, `client_id`, `client_key`, `client_secret`, `grant_type`, `device_type`, `access_path`, `ip_whitelist`, `active_timeout`, `timeout`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762000000000000002,'428a8310cd442757ae699df5d894f051','app','app123','password,sms,social','android','/app/**',NULL,1800,604800,'0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:55',1761100000000000001,'2026-10-23 21:05:55');
INSERT INTO `sys_oss_config` (`oss_config_id`, `config_key`, `access_key`, `secret_key`, `bucket_name`, `prefix`, `endpoint`, `domain_url`, `is_https`, `region`, `access_policy`, `status`, `ext1`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761900000000000001,'minio','','','innolux-iws','','127.0.0.1:9000','','N','','0','Y','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',1761100000000000001,'2026-10-23 21:05:54',NULL);
INSERT INTO `sys_oss_config` (`oss_config_id`, `config_key`, `access_key`, `secret_key`, `bucket_name`, `prefix`, `endpoint`, `domain_url`, `is_https`, `region`, `access_policy`, `status`, `ext1`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761900000000000002,'qiniu','XXXXXXXXXXXXXXX','XXXXXXXXXXXXXXX','innolux-wms','','s3-cn-north-1.qiniucs.com','','N','','1','N','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',1761100000000000001,'2026-10-23 21:05:54',NULL);
INSERT INTO `sys_oss_config` (`oss_config_id`, `config_key`, `access_key`, `secret_key`, `bucket_name`, `prefix`, `endpoint`, `domain_url`, `is_https`, `region`, `access_policy`, `status`, `ext1`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761900000000000003,'aliyun','XXXXXXXXXXXXXXX','XXXXXXXXXXXXXXX','innolux-wms','','oss-cn-beijing.aliyuncs.com','','N','','1','N','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',1761100000000000001,'2026-10-23 21:05:54',NULL);
INSERT INTO `sys_oss_config` (`oss_config_id`, `config_key`, `access_key`, `secret_key`, `bucket_name`, `prefix`, `endpoint`, `domain_url`, `is_https`, `region`, `access_policy`, `status`, `ext1`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761900000000000004,'qcloud','XXXXXXXXXXXXXXX','XXXXXXXXXXXXXXX','innolux-wms-1240000000','','cos.ap-beijing.myqcloud.com','','N','ap-beijing','1','N','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',1761100000000000001,'2026-10-23 21:05:54',NULL);
INSERT INTO `sys_oss_config` (`oss_config_id`, `config_key`, `access_key`, `secret_key`, `bucket_name`, `prefix`, `endpoint`, `domain_url`, `is_https`, `region`, `access_policy`, `status`, `ext1`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1761900000000000005,'image','','','innolux-iws','image','127.0.0.1:9000','','N','','0','N','',1761000000000000103,1761100000000000001,'2026-10-23 21:05:54',1761100000000000001,'2026-10-23 21:05:54',NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000100,0,'0','OA审批',0,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:56',NULL,NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000101,1762300000000000100,'0,1762300000000000100','假勤管理',0,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:56',NULL,NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000102,1762300000000000100,'0,1762300000000000100','人事管理',1,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:56',NULL,NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000103,1762300000000000101,'0,1762300000000000100,1762300000000000101','请假',0,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:56',NULL,NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000104,1762300000000000101,'0,1762300000000000100,1762300000000000101','出差',1,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:56',NULL,NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000105,1762300000000000101,'0,1762300000000000100,1762300000000000101','加班',2,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:56',NULL,NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000106,1762300000000000101,'0,1762300000000000100,1762300000000000101','换班',3,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:56',NULL,NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000107,1762300000000000101,'0,1762300000000000100,1762300000000000101','外出',4,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000108,1762300000000000102,'0,1762300000000000100,1762300000000000102','转正',1,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL);
INSERT INTO `flow_category` (`category_id`, `parent_id`, `ancestors`, `category_name`, `order_num`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762300000000000109,1762300000000000102,'0,1762300000000000100,1762300000000000102','离职',2,'0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',NULL,NULL);
INSERT INTO `flow_spel` (`id`, `component_name`, `method_name`, `method_params`, `view_spel`, `remark`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762400000000000001,'spelRuleComponent','selectDeptLeaderById','initiatorDeptId','#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}','根据部门id获取部门负责人','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',1761100000000000001,'2026-10-23 21:05:57');
INSERT INTO `flow_spel` (`id`, `component_name`, `method_name`, `method_params`, `view_spel`, `remark`, `status`, `del_flag`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`) VALUES (1762400000000000002,NULL,NULL,'initiator','${initiator}','流程发起人','0','0',1761000000000000103,1761100000000000001,'2026-10-23 21:05:57',1761100000000000001,'2026-10-23 21:05:57');
INSERT INTO `sai_user` (`id`, `role`, `totals`, `username`, `nickname`, `email`, `password`, `resource_id`, `create_dt`, `update_dt`) VALUES (1,2,NULL,'innolux','innolux','','pbkdf2$120000$OoM9lF194wXQ9YrojI/J7g==$uw/uCpY4k9OmdLTb4ij4i0siMnb5EzJnjoOpeuRD1Y0=',NULL,'2026-10-23 21:05:59','2026-10-23 21:05:59');
INSERT INTO `sai_model_provider` (`id`, `provider_name`, `provider_key`, `description`, `icon_url`, `is_enabled`, `created_dt`, `updated_dt`) VALUES (1,'OpenAI','openai','OpenAI官方模型 (GPT-4, GPT-3.5等)',NULL,1,'2026-10-23 13:05:59','2026-10-23 13:05:59');
INSERT INTO `sai_model_provider` (`id`, `provider_name`, `provider_key`, `description`, `icon_url`, `is_enabled`, `created_dt`, `updated_dt`) VALUES (2,'Claude','claude','Anthropic Claude模型',NULL,1,'2026-10-23 13:05:59','2026-10-23 13:05:59');
INSERT INTO `sai_model_provider` (`id`, `provider_name`, `provider_key`, `description`, `icon_url`, `is_enabled`, `created_dt`, `updated_dt`) VALUES (3,'Ollama','ollama','本地开源模型 (Llama, Mistral等)',NULL,1,'2026-10-23 13:05:59','2026-10-23 13:05:59');
INSERT INTO `sai_model_provider` (`id`, `provider_name`, `provider_key`, `description`, `icon_url`, `is_enabled`, `created_dt`, `updated_dt`) VALUES (4,'Google Gemini','gemini','Google Gemini模型',NULL,1,'2026-10-23 13:05:59','2026-10-23 13:05:59');
INSERT INTO `sai_model_provider` (`id`, `provider_name`, `provider_key`, `description`, `icon_url`, `is_enabled`, `created_dt`, `updated_dt`) VALUES (5,'阿里云百炼','qwen','阿里云百炼 OpenAI 兼容模型 (Qwen等)',NULL,1,'2026-10-23 13:05:59','2026-10-23 13:05:59');
INSERT INTO `sai_model_provider` (`id`, `provider_name`, `provider_key`, `description`, `icon_url`, `is_enabled`, `created_dt`, `updated_dt`) VALUES (6,'DeepSeek','deepseek','DeepSeek OpenAI 兼容模型',NULL,1,'2026-10-23 13:05:59','2026-10-23 13:05:59');
INSERT INTO `sai_model_provider` (`id`, `provider_name`, `provider_key`, `description`, `icon_url`, `is_enabled`, `created_dt`, `updated_dt`) VALUES (7,'智谱AI','zhipu','智谱AI OpenAI 兼容模型 (GLM等)',NULL,1,'2026-10-23 13:05:59','2026-10-23 13:05:59');
INSERT INTO `sai_model_config` (`id`, `provider_id`, `model_name`, `model_key`, `model_type`, `adapter_key`, `description`, `api_key`, `api_endpoint`, `config_json`, `owner_id`, `scope`, `is_default`, `is_enabled`, `created_dt`, `updated_dt`) VALUES (1,5,'glm-5.1','glm-5.1','CHAT','openai-compatible','','','https://dashscope.aliyuncs.com/compatible-mode/v1','{\"frequencyPenalty\":0.0,\"maxTokens\":20000,\"presencePenalty\":0.0,\"stopSequences\":[],\"stream\":true,\"temperature\":0.7,\"timeoutMs\":300000,\"topK\":1,\"topP\":1.0}',NULL,'GLOBAL',1,1,'2026-10-23 13:05:59','2026-10-23 13:05:59');

-- 默认 AI 助手：显式绑定默认对话模型 id=1；首次使用只需补 API Key。
INSERT INTO `sai_agent` (`id`, `name`, `description`, `avatar`, `instruction`, `greeting`, `preset_questions`, `chat_model_id`, `memory_enabled`, `mcp_enabled`, `skill_enabled`, `web_search_enabled`, `rag_enabled`, `rag_ids`, `rag_call_mode`, `short_term_memory_size`, `creator_id`, `is_featured`, `view_count`, `status`, `config`, `app_id`, `create_dt`, `update_dt`)
VALUES (1, 'IWS+ 助手', 'IWS+ 默认通用助手，首次使用只需为默认对话模型补充 API Key。', NULL, '你是 IWS+ 的默认通用助手。请用简体中文准确、简洁地回答用户问题，必要时给出操作步骤和示例。你不直接执行仓储、生产或外部系统中的真实操作；涉及实际变更时，请提示用户通过对应业务页面确认和处理。', '你好，我是 IWS+ 助手，可以为你解答系统使用和日常工作中的问题。', '["IWS+ 可以帮我处理哪些工作？","怎么查看指定仓库的库存？","计划事件平台怎么使用？"]', 1, 1, 0, 0, 0, 0, NULL, 1, 20, 1, 1, 0, 1, NULL, '1', NOW(), NOW());

-- SnailAI 默认应用：业务键为 app_id，不依赖自增 id；token 与主服务默认接入配置一致。
SET @init_snailai_default_app_token = 'SAI_566a6bfbc26e4998b4841cc927d50c5d';
INSERT INTO `sai_app` (`app_id`, `app_name`, `description`, `token`, `route_strategy`, `status`)
VALUES ('1', 'IWS+', 'IWS+ 主应用，供主服务 SnailAI 客户端接入', @init_snailai_default_app_token, 'LEAST_LOAD', 1);

-- 已存在的 OpenAPI 用户若尚未订阅任何智能体，回填默认助手订阅；
-- 新库没有 OpenAPI 用户，该段应为 0 行，不影响用户后续的订阅选择。
INSERT INTO `sai_user_agent` (`user_id`, `agent_id`, `create_dt`)
SELECT u.`platform_user_id`, 1, NOW()
FROM `sai_openapi_user` u
WHERE u.`app_id` = '1'
  AND NOT EXISTS (
      SELECT 1 FROM `sai_user_agent` ua WHERE ua.`user_id` = u.`platform_user_id`
  );

-- ----------------------------------------------------------------------------
-- 系统接入配置 endpoint 表与唯一目录控制行
-- ----------------------------------------------------------------------------

/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件与系统接入配置
 * @Version      : V1.0
 * @Description  : 系统接入配置独立目录表升级；目标 MySQL 8.0，可重复执行并进行完整结构核验；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数见下一行。
-- 执行：由同一会话顺序执行；支持 mysql 客户端与可执行 PREPARE 的 SQL 客户端。
-- 安全：所有持久化写入均受目标、预检与后置核验门控；客户端即使续错也不会继续落写。
-- 说明：本脚本不创建业务系统、环境、地址或凭据，仅创建结构及唯一目录控制行。

SET @upgrade_schema = @init_schema;

SET @upgrade_schema_q = CONCAT('`', REPLACE(@upgrade_schema, '`', '``'), '`');

SET @ep_target_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY)
);
SET @ep_database_matches = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@upgrade_schema AS BINARY)
);
SET @ep_version_ok = (VERSION() LIKE '8.0.%');
SET @ep_schema_collation_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.SCHEMATA
    WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY)
      AND DEFAULT_COLLATION_NAME = 'utf8mb4_general_ci'
);
SET @ep_table_case_setting_ok = (@@lower_case_table_names IN (0, 1, 2));
SET @ep_precheck_ok = (
    @ep_target_ok = 1
    AND @ep_database_matches = 1
    AND @ep_version_ok = 1
    AND @ep_schema_collation_ok = 1
    AND @ep_table_case_setting_ok = 1
);

SELECT
    @ep_target_ok AS `目标库存在`,
    @ep_database_matches AS `当前连接库一致`,
    @ep_version_ok AS `MySQL8版本`,
    @ep_schema_collation_ok AS `目标库排序规则`,
    @ep_table_case_setting_ok AS `表名大小写设置合法`,
    @@lower_case_table_names AS `lower_case_table_names`,
    IF(@ep_precheck_ok = 1, '前置校验通过', '前置校验失败，禁止写入') AS `执行说明`;

-- 执行器应在此停止；继续执行的客户端也会因无效 JSON 报错，后续写入仍受门控。
SELECT JSON_EXTRACT(IF(@ep_precheck_ok = 1, '{}', 'invalid'), '$') AS `前置校验`;

SET @ep_conflicts = 0;
SET @ep_control_count = NULL;
SET @ep_control_bad = NULL;
SET @ep_final_count = NULL;
SET @ep_final_revision = NULL;
SET @ep_final_ok = 0;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_conflicts;

CREATE TEMPORARY TABLE tmp_iws_ep_expected_tables (
    table_name varchar(64) NOT NULL,
    PRIMARY KEY (table_name)
);

CREATE TEMPORARY TABLE tmp_iws_ep_expected_columns (
    table_name varchar(64) NOT NULL,
    ordinal_position int NOT NULL,
    column_name varchar(64) NOT NULL,
    column_type varchar(255) NOT NULL,
    is_nullable varchar(3) NOT NULL,
    column_default varchar(1024) DEFAULT NULL,
    extra varchar(64) NOT NULL,
    character_set_name varchar(64) DEFAULT NULL,
    collation_name varchar(64) DEFAULT NULL,
    PRIMARY KEY (table_name, ordinal_position),
    UNIQUE KEY uk_tmp_iws_ep_column (table_name, column_name)
);

CREATE TEMPORARY TABLE tmp_iws_ep_expected_indexes (
    table_name varchar(64) NOT NULL,
    index_name varchar(64) NOT NULL,
    non_unique int NOT NULL,
    seq_in_index int NOT NULL,
    column_name varchar(64) NOT NULL,
    sub_part int DEFAULT NULL,
    index_type varchar(16) NOT NULL,
    PRIMARY KEY (table_name, index_name, seq_in_index)
);

CREATE TEMPORARY TABLE tmp_iws_ep_conflicts (
    table_name varchar(64) NOT NULL,
    reason varchar(255) NOT NULL,
    KEY idx_tmp_iws_ep_conflict (table_name)
);

INSERT INTO tmp_iws_ep_expected_tables (table_name) VALUES
('iws_endpoint_system'),
('iws_endpoint_environment'),
('iws_endpoint_address'),
('iws_endpoint_system_address'),
('iws_endpoint_catalog_control'),
('iws_endpoint_audit');

-- 精确对象名核验：允许 Linux 的 lower_case_table_names=0，但不接受仅大小写近似的新表名。
DELETE FROM tmp_iws_ep_conflicts;
INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在大小写不一致对象：', i.TABLE_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON CAST(i.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
 AND LOWER(i.TABLE_NAME) = LOWER(t.table_name)
WHERE CAST(i.TABLE_NAME AS BINARY) <> CAST(t.table_name AS BINARY);

INSERT INTO tmp_iws_ep_expected_columns
(table_name, ordinal_position, column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name) VALUES
('iws_endpoint_system', 1, 'system_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_system', 2, 'display_name', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_system', 3, 'description', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_system', 4, 'enabled', 'tinyint(1)', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system', 5, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system', 6, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_system', 7, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system', 8, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_system', 9, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_environment', 1, 'environment_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_environment', 2, 'display_name', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_environment', 3, 'description', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_environment', 4, 'enabled', 'tinyint(1)', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_environment', 5, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_environment', 6, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_environment', 7, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_environment', 8, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_environment', 9, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_address', 1, 'address_id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL),
('iws_endpoint_address', 2, 'environment_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 3, 'endpoint_ref', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 4, 'protocol', 'varchar(8)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 5, 'host', 'varchar(253)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 6, 'port', 'smallint unsigned', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_address', 7, 'base_path', 'varchar(512)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 8, 'credential_ref', 'varchar(128)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_address', 9, 'enabled', 'tinyint(1)', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_address', 10, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_address', 11, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_address', 12, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_address', 13, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_address', 14, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 1, 'relation_id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL),
('iws_endpoint_system_address', 2, 'system_code', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_system_address', 3, 'address_id', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 4, 'enabled', 'tinyint(1)', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 5, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 6, 'create_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 7, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 8, 'update_by', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_system_address', 9, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_catalog_control', 1, 'singleton_key', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_catalog_control', 2, 'revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_catalog_control', 3, 'update_time', 'datetime', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_audit', 1, 'audit_id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL),
('iws_endpoint_audit', 2, 'object_type', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 3, 'object_key', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 4, 'environment_code', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 5, 'action', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 6, 'before_summary', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 7, 'after_summary', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 8, 'operator_id', 'bigint', 'YES', NULL, '', NULL, NULL),
('iws_endpoint_audit', 9, 'operator_name', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 10, 'command_key', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 11, 'request_hash', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 12, 'reason', 'varchar(500)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 13, 'catalog_revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_audit', 14, 'result_revision', 'bigint', 'NO', NULL, '', NULL, NULL),
('iws_endpoint_audit', 15, 'result_object_key', 'varchar(128)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_endpoint_audit', 16, 'create_time', 'datetime', 'NO', NULL, '', NULL, NULL);

INSERT INTO tmp_iws_ep_expected_indexes
(table_name, index_name, non_unique, seq_in_index, column_name, sub_part, index_type) VALUES
('iws_endpoint_system', 'PRIMARY', 0, 1, 'system_code', NULL, 'BTREE'),
('iws_endpoint_system', 'idx_iws_endpoint_system_page', 1, 1, 'enabled', NULL, 'BTREE'),
('iws_endpoint_system', 'idx_iws_endpoint_system_page', 1, 2, 'update_time', NULL, 'BTREE'),
('iws_endpoint_system', 'idx_iws_endpoint_system_page', 1, 3, 'system_code', NULL, 'BTREE'),
('iws_endpoint_environment', 'PRIMARY', 0, 1, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_environment', 'idx_iws_endpoint_environment_page', 1, 1, 'enabled', NULL, 'BTREE'),
('iws_endpoint_environment', 'idx_iws_endpoint_environment_page', 1, 2, 'update_time', NULL, 'BTREE'),
('iws_endpoint_environment', 'idx_iws_endpoint_environment_page', 1, 3, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_address', 'PRIMARY', 0, 1, 'address_id', NULL, 'BTREE'),
('iws_endpoint_address', 'uk_iws_endpoint_address_ref', 0, 1, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_address', 'uk_iws_endpoint_address_ref', 0, 2, 'endpoint_ref', NULL, 'BTREE'),
('iws_endpoint_address', 'idx_iws_endpoint_address_page', 1, 1, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_address', 'idx_iws_endpoint_address_page', 1, 2, 'enabled', NULL, 'BTREE'),
('iws_endpoint_address', 'idx_iws_endpoint_address_page', 1, 3, 'update_time', NULL, 'BTREE'),
('iws_endpoint_address', 'idx_iws_endpoint_address_page', 1, 4, 'address_id', NULL, 'BTREE'),
('iws_endpoint_system_address', 'PRIMARY', 0, 1, 'relation_id', NULL, 'BTREE'),
('iws_endpoint_system_address', 'uk_iws_endpoint_system_address', 0, 1, 'system_code', NULL, 'BTREE'),
('iws_endpoint_system_address', 'uk_iws_endpoint_system_address', 0, 2, 'address_id', NULL, 'BTREE'),
('iws_endpoint_system_address', 'idx_iws_endpoint_system_address_addr', 1, 1, 'address_id', NULL, 'BTREE'),
('iws_endpoint_system_address', 'idx_iws_endpoint_system_address_addr', 1, 2, 'enabled', NULL, 'BTREE'),
('iws_endpoint_catalog_control', 'PRIMARY', 0, 1, 'singleton_key', NULL, 'BTREE'),
('iws_endpoint_audit', 'PRIMARY', 0, 1, 'audit_id', NULL, 'BTREE'),
('iws_endpoint_audit', 'uk_iws_endpoint_audit_command', 0, 1, 'command_key', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_object', 1, 1, 'object_type', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_object', 1, 2, 'object_key', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_object', 1, 3, 'audit_id', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_environment', 1, 1, 'environment_code', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_environment', 1, 2, 'audit_id', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_operator', 1, 1, 'operator_name', NULL, 'BTREE'),
('iws_endpoint_audit', 'idx_iws_endpoint_audit_operator', 1, 2, 'audit_id', NULL, 'BTREE');

-- 预检只比较已存在表；缺表由后续门控创建，已存在但定义不同则禁止任何创建。
-- 大小写冲突条目保留；其余检查追加。

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, '表不是 InnoDB 基础表或排序规则不一致'
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
WHERE i.TABLE_TYPE <> 'BASE TABLE'
   OR i.ENGINE <> 'InnoDB'
   OR NOT (i.TABLE_COLLATION <=> 'utf8mb4_general_ci');

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('列定义不匹配：', e.column_name)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_ep_expected_columns e ON e.table_name = t.table_name
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

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记列：', c.COLUMN_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_ep_expected_columns e
  ON e.table_name = t.table_name AND e.column_name = c.COLUMN_NAME
WHERE e.column_name IS NULL;

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('索引定义不匹配：', e.index_name)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_ep_expected_indexes e ON e.table_name = t.table_name
LEFT JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
 AND s.INDEX_NAME = e.index_name AND s.SEQ_IN_INDEX = e.seq_in_index
WHERE s.INDEX_NAME IS NULL
   OR s.NON_UNIQUE <> e.non_unique
   OR NOT (s.COLUMN_NAME <=> e.column_name)
   OR NOT (s.SUB_PART <=> e.sub_part)
   OR NOT (s.INDEX_TYPE <=> e.index_type);

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记索引：', s.INDEX_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_ep_expected_indexes e
  ON e.table_name = t.table_name AND e.index_name = s.INDEX_NAME AND e.seq_in_index = s.SEQ_IN_INDEX
WHERE e.index_name IS NULL;

SELECT COUNT(*) INTO @ep_conflicts FROM tmp_iws_ep_conflicts;
SET @ep_preflight_ok = IF(@ep_precheck_ok = 1 AND @ep_conflicts = 0, 1, 0);
SET @ep_ddl_chain_ok = @ep_preflight_ok;

SELECT `表名`, `冲突说明`
FROM (
    SELECT table_name AS `表名`, reason AS `冲突说明`, 1 AS sort_no FROM tmp_iws_ep_conflicts
    UNION ALL
    SELECT '--', '无结构冲突', 2
) detail
WHERE (@ep_conflicts > 0 AND sort_no = 1)
   OR (@ep_conflicts = 0 AND sort_no = 2)
ORDER BY `表名`, `冲突说明`;

SELECT JSON_EXTRACT(IF(@ep_preflight_ok = 1, '{}', 'invalid'), '$') AS `预检结果`;

-- 建表：表缺失且预检通过时才执行；按依赖顺序创建，不建立物理外键。
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_system'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_system` (',
    '`system_code` varchar(64) NOT NULL,',
    '`display_name` varchar(128) NOT NULL,',
    '`description` varchar(500) DEFAULT NULL,',
    '`enabled` tinyint(1) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`create_by` bigint DEFAULT NULL,',
    '`create_time` datetime NOT NULL,',
    '`update_by` bigint DEFAULT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`system_code`),',
    'KEY `idx_iws_endpoint_system_page` (`enabled`, `update_time`, `system_code`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_system'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_environment'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_environment` (',
    '`environment_code` varchar(64) NOT NULL,',
    '`display_name` varchar(128) NOT NULL,',
    '`description` varchar(500) DEFAULT NULL,',
    '`enabled` tinyint(1) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`create_by` bigint DEFAULT NULL,',
    '`create_time` datetime NOT NULL,',
    '`update_by` bigint DEFAULT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`environment_code`),',
    'KEY `idx_iws_endpoint_environment_page` (`enabled`, `update_time`, `environment_code`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_environment'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_address'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_address` (',
    '`address_id` bigint NOT NULL AUTO_INCREMENT,',
    '`environment_code` varchar(64) NOT NULL,',
    '`endpoint_ref` varchar(64) NOT NULL,',
    '`protocol` varchar(8) NOT NULL,',
    '`host` varchar(253) NOT NULL,',
    '`port` smallint unsigned DEFAULT NULL,',
    '`base_path` varchar(512) NOT NULL,',
    '`credential_ref` varchar(128) DEFAULT NULL,',
    '`enabled` tinyint(1) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`create_by` bigint DEFAULT NULL,',
    '`create_time` datetime NOT NULL,',
    '`update_by` bigint DEFAULT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`address_id`),',
    'UNIQUE KEY `uk_iws_endpoint_address_ref` (`environment_code`, `endpoint_ref`),',
    'KEY `idx_iws_endpoint_address_page` (`environment_code`, `enabled`, `update_time`, `address_id`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_address'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_system_address'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_system_address` (',
    '`relation_id` bigint NOT NULL AUTO_INCREMENT,',
    '`system_code` varchar(64) NOT NULL,',
    '`address_id` bigint NOT NULL,',
    '`enabled` tinyint(1) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`create_by` bigint DEFAULT NULL,',
    '`create_time` datetime NOT NULL,',
    '`update_by` bigint DEFAULT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`relation_id`),',
    'UNIQUE KEY `uk_iws_endpoint_system_address` (`system_code`, `address_id`),',
    'KEY `idx_iws_endpoint_system_address_addr` (`address_id`, `enabled`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_system_address'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_catalog_control'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_catalog_control` (',
    '`singleton_key` varchar(16) NOT NULL,',
    '`revision` bigint NOT NULL,',
    '`update_time` datetime NOT NULL,',
    'PRIMARY KEY (`singleton_key`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_catalog_control'), 1, 0);
SET @ep_table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_audit'
);
SET @ep_ddl = IF(@ep_ddl_chain_ok = 1 AND @ep_table_exists = 0, CONCAT(
    'CREATE TABLE ', @upgrade_schema_q, '.`iws_endpoint_audit` (',
    '`audit_id` bigint NOT NULL AUTO_INCREMENT,',
    '`object_type` varchar(32) NOT NULL,',
    '`object_key` varchar(128) NOT NULL,',
    '`environment_code` varchar(64) DEFAULT NULL,',
    '`action` varchar(32) NOT NULL,',
    '`before_summary` text DEFAULT NULL,',
    '`after_summary` text DEFAULT NULL,',
    '`operator_id` bigint DEFAULT NULL,',
    '`operator_name` varchar(128) NOT NULL,',
    '`command_key` varchar(128) NOT NULL,',
    '`request_hash` varchar(64) NOT NULL,',
    '`reason` varchar(500) NOT NULL,',
    '`catalog_revision` bigint NOT NULL,',
    '`result_revision` bigint NOT NULL,',
    '`result_object_key` varchar(128) NOT NULL,',
    '`create_time` datetime NOT NULL,',
    'PRIMARY KEY (`audit_id`),',
    'UNIQUE KEY `uk_iws_endpoint_audit_command` (`command_key`),',
    'KEY `idx_iws_endpoint_audit_object` (`object_type`, `object_key`, `audit_id`),',
    'KEY `idx_iws_endpoint_audit_environment` (`environment_code`, `audit_id`),',
    'KEY `idx_iws_endpoint_audit_operator` (`operator_name`, `audit_id`)',
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl_chain_ok = IF(@ep_ddl_chain_ok = 1 AND (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = @upgrade_schema AND TABLE_NAME = 'iws_endpoint_audit'), 1, 0);
-- 后置结构核验：重复比较，并额外要求 6 张表全部存在。
DELETE FROM tmp_iws_ep_conflicts;

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, '表缺失'
FROM tmp_iws_ep_expected_tables t
LEFT JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
WHERE i.TABLE_NAME IS NULL;

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, '表不是 InnoDB 基础表或排序规则不一致'
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
WHERE i.TABLE_TYPE <> 'BASE TABLE'
   OR i.ENGINE <> 'InnoDB'
   OR NOT (i.TABLE_COLLATION <=> 'utf8mb4_general_ci');

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('列定义不匹配：', e.column_name)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_ep_expected_columns e ON e.table_name = t.table_name
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

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记列：', c.COLUMN_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA = @upgrade_schema AND c.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_ep_expected_columns e
  ON e.table_name = t.table_name AND e.column_name = c.COLUMN_NAME
WHERE e.column_name IS NULL;

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('索引定义不匹配：', e.index_name)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN tmp_iws_ep_expected_indexes e ON e.table_name = t.table_name
LEFT JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
 AND s.INDEX_NAME = e.index_name AND s.SEQ_IN_INDEX = e.seq_in_index
WHERE s.INDEX_NAME IS NULL
   OR s.NON_UNIQUE <> e.non_unique
   OR NOT (s.COLUMN_NAME <=> e.column_name)
   OR NOT (s.SUB_PART <=> e.sub_part)
   OR NOT (s.INDEX_TYPE <=> e.index_type);

INSERT INTO tmp_iws_ep_conflicts (table_name, reason)
SELECT t.table_name, CONCAT('存在未登记索引：', s.INDEX_NAME)
FROM tmp_iws_ep_expected_tables t
JOIN information_schema.TABLES i
  ON i.TABLE_SCHEMA = @upgrade_schema AND i.TABLE_NAME = t.table_name
JOIN information_schema.STATISTICS s
  ON s.TABLE_SCHEMA = @upgrade_schema AND s.TABLE_NAME = t.table_name
LEFT JOIN tmp_iws_ep_expected_indexes e
  ON e.table_name = t.table_name AND e.index_name = s.INDEX_NAME AND e.seq_in_index = s.SEQ_IN_INDEX
WHERE e.index_name IS NULL;

SELECT COUNT(*) INTO @ep_conflicts FROM tmp_iws_ep_conflicts;
SET @ep_post_ok = IF(@ep_ddl_chain_ok = 1 AND @ep_preflight_ok = 1 AND @ep_conflicts = 0, 1, 0);

SELECT `表名`, `冲突说明`
FROM (
    SELECT table_name AS `表名`, reason AS `冲突说明`, 1 AS sort_no FROM tmp_iws_ep_conflicts
    UNION ALL
    SELECT '--', '无结构冲突', 2
) detail
WHERE (@ep_conflicts > 0 AND sort_no = 1)
   OR (@ep_conflicts = 0 AND sort_no = 2)
ORDER BY `表名`, `冲突说明`;

SELECT JSON_EXTRACT(IF(@ep_post_ok = 1, '{}', 'invalid'), '$') AS `后置结构校验`;

-- 唯一目录控制行：只允许 CATALOG 一行，初始 revision=0。
SET @ep_control_count_sql = CONCAT(
    'SELECT COUNT(*) INTO @ep_control_count FROM ', @upgrade_schema_q, '.`iws_endpoint_catalog_control`'
);
SET @ep_ddl = IF(@ep_post_ok = 1, @ep_control_count_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_control_bad_sql = CONCAT(
    'SELECT COUNT(*) INTO @ep_control_bad FROM ', @upgrade_schema_q,
    '.`iws_endpoint_catalog_control` WHERE `singleton_key` <> ''CATALOG'' OR `revision` < 0'
);
SET @ep_ddl = IF(@ep_post_ok = 1, @ep_control_bad_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_control_ok = IF(@ep_post_ok = 1 AND COALESCE(@ep_control_count, 0) IN (0, 1)
    AND COALESCE(@ep_control_bad, 0) = 0, 1, 0);

SELECT COUNT(*) AS `控制行矛盾数`
FROM (
    SELECT 1 AS bad WHERE @ep_control_ok = 0
) conflict_rows;

SELECT JSON_EXTRACT(IF(@ep_control_ok = 1, '{}', 'invalid'), '$') AS `目录控制行校验`;

SET @ep_ddl = IF(@ep_control_ok = 1 AND @ep_control_count = 0, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.`iws_endpoint_catalog_control`',
    ' (`singleton_key`, `revision`, `update_time`)',
    ' VALUES (''CATALOG'', 0, CURRENT_TIMESTAMP)'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_final_sql = CONCAT(
    'SELECT COUNT(*), COALESCE(MAX(`revision`), -1) INTO @ep_final_count, @ep_final_revision FROM ',
    @upgrade_schema_q, '.`iws_endpoint_catalog_control` WHERE `singleton_key` = ''CATALOG'''
);
SET @ep_ddl = IF(@ep_control_ok = 1, @ep_final_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_final_ok = IF(@ep_control_ok = 1 AND COALESCE(@ep_final_count, 0) = 1
    AND @ep_final_revision >= 0, 1, 0);

-- 默认环境：空库预置 TEST / 测试环境，首次启用 process 时把部署配置
-- iws.endpoint.current-environment 指向 TEST 即可通过启动校验。
SET @ep_default_env_count_sql = CONCAT(
    'SELECT COUNT(*) INTO @ep_env_count FROM ', @upgrade_schema_q,
    '.`iws_endpoint_environment` WHERE `environment_code` = ''TEST'''
);
SET @ep_ddl = IF(@ep_final_ok = 1, @ep_default_env_count_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl = IF(@ep_final_ok = 1 AND COALESCE(@ep_env_count, 0) = 0, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.`iws_endpoint_environment`',
    ' (`environment_code`, `display_name`, `description`, `enabled`, `revision`,',
    ' `create_by`, `create_time`, `update_by`, `update_time`)',
    ' VALUES (''TEST'', ''测试环境'', ''空库初始化默认环境，供首次启动使用；接入正式环境后可将本行停用'',',
    ' 1, 0, NULL, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_default_env_check_sql = CONCAT(
    'SELECT COUNT(*) INTO @ep_env_ok FROM ', @upgrade_schema_q, '.`iws_endpoint_environment`',
    ' WHERE `environment_code` = ''TEST'' AND `display_name` = ''测试环境''',
    ' AND `enabled` = 1 AND `revision` = 0'
);
SET @ep_ddl = IF(@ep_final_ok = 1, @ep_default_env_check_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_default_env_ok = IF(@ep_final_ok = 1 AND COALESCE(@ep_env_ok, 0) = 1, 1, 0);

SET @ep_default_system_count_sql = CONCAT(
    'SELECT COUNT(*) INTO @ep_system_count FROM ', @upgrade_schema_q,
    '.iws_endpoint_system WHERE system_code = ''IWS'''
);
SET @ep_ddl = IF(@ep_final_ok = 1, @ep_default_system_count_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_ddl = IF(@ep_final_ok = 1 AND COALESCE(@ep_system_count, 0) = 0, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.iws_endpoint_system',
    ' (system_code, display_name, description, enabled, revision,',
    ' create_by, create_time, update_by, update_time)',
    ' VALUES (''IWS'', ''IWS+'', ''空库初始化默认系统，供 APK 等计划事件发布使用'',',
    ' 1, 0, NULL, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)'
), 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_default_system_check_sql = CONCAT(
    'SELECT COUNT(*) INTO @ep_system_ok FROM ', @upgrade_schema_q, '.iws_endpoint_system',
    ' WHERE system_code = ''IWS'' AND enabled = 1 AND revision = 0'
);
SET @ep_ddl = IF(@ep_final_ok = 1, @ep_default_system_check_sql, 'DO 0');
PREPARE ep_stmt FROM @ep_ddl;
EXECUTE ep_stmt;
DEALLOCATE PREPARE ep_stmt;

SET @ep_default_system_ok = IF(@ep_final_ok = 1 AND COALESCE(@ep_system_ok, 0) = 1, 1, 0);
SET @ep_seed_ok = IF(@ep_final_ok = 1 AND @ep_default_env_ok = 1
    AND @ep_default_system_ok = 1, 1, 0);

SELECT
    IF(@ep_final_ok = 1, '系统接入配置结构升级完成', '系统接入配置结构升级失败') AS `执行结果`,
    @upgrade_schema AS `目标库`,
    @ep_final_revision AS `目录版本`,
    IF(@ep_default_env_ok = 1, 'TEST', '未写入') AS `默认环境`,
    IF(@ep_default_system_ok = 1, 'IWS', '未写入') AS `默认系统`;

SELECT JSON_EXTRACT(IF(@ep_seed_ok = 1, '{}', 'invalid'), '$') AS `最终校验`;

-- 后置验收查询：供执行人独立复核。
SELECT table_name AS `表名`, engine AS `存储引擎`, table_collation AS `排序规则`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @upgrade_schema
  AND table_name IN (
      'iws_endpoint_system',
      'iws_endpoint_environment',
      'iws_endpoint_address',
      'iws_endpoint_system_address',
      'iws_endpoint_catalog_control',
      'iws_endpoint_audit'
  )
ORDER BY table_name;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_conflicts;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_indexes;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_ep_expected_tables;


-- ----------------------------------------------------------------------------
-- 计划事件 process 运行表
-- ----------------------------------------------------------------------------

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

SET @upgrade_schema = @init_schema;
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
('iws_process_request_detail', 33, 'client_ip', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
('iws_process_request_detail', 34, 'access_summary', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_general_ci'),
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
    "`client_ip` varchar(64) NULL,",
    "`access_summary` varchar(500) NULL,",
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

-- ----------------------------------------------------------------------------
-- endpoint 与 process 菜单及按钮权限
-- ----------------------------------------------------------------------------

/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件与系统接入配置
 * @Version      : V1.0
 * @Description  : endpoint与process菜单及按钮权限升级；目标MySQL 8.0，可重复执行并核验既有菜单；
 * @ModifyRecord : V1.0 新建；
 */

-- 目标：MySQL 8.0；单一目标库参数见下一行。
-- 本脚本只写 sys_menu，不向任何角色授予菜单，不签发真实权限。
-- 菜单不按模块启停自动隐藏；运行时过滤由后端能力判断负责。

SET @upgrade_schema = @init_schema;
SET @upgrade_schema_q = CONCAT('`', REPLACE(@upgrade_schema, '`', '``'), '`');

SET @menu_target_ok = (SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY));
SET @menu_database_matches = (DATABASE() IS NOT NULL AND CAST(DATABASE() AS BINARY) = CAST(@upgrade_schema AS BINARY));
SET @menu_version_ok = (VERSION() LIKE '8.0.%');
SET @menu_schema_collation_ok = (SELECT COUNT(*) = 1 FROM information_schema.SCHEMATA WHERE CAST(SCHEMA_NAME AS BINARY) = CAST(@upgrade_schema AS BINARY) AND DEFAULT_COLLATION_NAME = 'utf8mb4_general_ci');
SET @menu_table_case_setting_ok = (@@lower_case_table_names IN (0, 1, 2));
SET @menu_base_ok = (@menu_target_ok = 1 AND @menu_database_matches = 1 AND @menu_version_ok = 1 AND @menu_schema_collation_ok = 1 AND @menu_table_case_setting_ok = 1);
SET @menu_table_ok = (SELECT COUNT(*) = 1 FROM information_schema.TABLES WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY) AND CAST(TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY) AND TABLE_TYPE = 'BASE TABLE');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_sys_columns;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_conflicts;
CREATE TEMPORARY TABLE tmp_iws_menu_sys_columns (column_name varchar(64) NOT NULL, column_type varchar(255) NOT NULL, is_nullable varchar(3) NOT NULL, PRIMARY KEY (column_name));
CREATE TEMPORARY TABLE tmp_iws_menu_expected (
    menu_id bigint NOT NULL PRIMARY KEY, natural_key varchar(512) NOT NULL, menu_name varchar(50) NOT NULL,
    parent_id bigint NOT NULL, order_num int NOT NULL, path varchar(200) NOT NULL, component varchar(255) DEFAULT NULL,
    query_param varchar(255) DEFAULT NULL, is_frame char(1) NOT NULL, is_cache char(1) NOT NULL, menu_type char(1) NOT NULL,
    visible char(1) NOT NULL, status char(1) NOT NULL, perms varchar(100) NOT NULL, icon varchar(100) NOT NULL,
    active_menu varchar(255) NOT NULL, ext varchar(2000) NOT NULL, create_dept bigint NOT NULL, create_by bigint NOT NULL,
    remark varchar(500) NOT NULL, UNIQUE KEY uk_tmp_iws_menu_natural (natural_key)
);
CREATE TEMPORARY TABLE tmp_iws_menu_actual (
    menu_id bigint NOT NULL, menu_name varchar(50) NOT NULL, parent_id bigint DEFAULT NULL,
    order_num int DEFAULT NULL, path varchar(200) DEFAULT NULL, component varchar(255) DEFAULT NULL,
    query_param varchar(255) DEFAULT NULL, is_frame char(1) DEFAULT NULL, is_cache char(1) DEFAULT NULL,
    menu_type char(1) DEFAULT NULL, visible char(1) DEFAULT NULL, status char(1) DEFAULT NULL,
    perms varchar(100) DEFAULT NULL, icon varchar(100) DEFAULT NULL, active_menu varchar(255) DEFAULT NULL,
    ext varchar(2000) DEFAULT NULL, create_dept bigint DEFAULT NULL, create_by bigint DEFAULT NULL,
    create_time datetime DEFAULT NULL, update_by bigint DEFAULT NULL, update_time datetime DEFAULT NULL,
    remark varchar(500) DEFAULT NULL, KEY idx_tmp_iws_menu_actual_id (menu_id)
);
CREATE TEMPORARY TABLE tmp_iws_menu_post LIKE tmp_iws_menu_actual;
CREATE TEMPORARY TABLE tmp_iws_menu_conflicts (object_key varchar(128) NOT NULL, reason varchar(255) NOT NULL);

INSERT INTO tmp_iws_menu_sys_columns VALUES
('menu_id','bigint','NO'),('menu_name','varchar(50)','NO'),('parent_id','bigint','YES'),('order_num','int','YES'),
('path','varchar(200)','YES'),('component','varchar(255)','YES'),('query_param','varchar(255)','YES'),
('is_frame','char(1)','YES'),('is_cache','char(1)','YES'),('menu_type','char(1)','YES'),('visible','char(1)','YES'),
('status','char(1)','YES'),('perms','varchar(100)','YES'),('icon','varchar(100)','YES'),('active_menu','varchar(255)','YES'),
('ext','varchar(2000)','YES'),('create_dept','bigint','YES'),('create_by','bigint','YES'),('create_time','datetime','YES'),
('update_by','bigint','YES'),('update_time','datetime','YES'),('remark','varchar(500)','YES');

INSERT INTO tmp_iws_menu_expected
(menu_id,natural_key,menu_name,parent_id,order_num,path,component,query_param,is_frame,is_cache,menu_type,visible,status,perms,icon,active_menu,ext,create_dept,create_by,remark) VALUES
(1761400000000030001,'M:0:endpoint','系统接入配置',0,6,'endpoint',NULL,NULL,'N','Y','M','0','0','','connection','','',1761000000000000103,1761100000000000001,'IWS系统定义、环境与共享地址基础配置'),
(1761400000000030002,'C:1761400000000030001:system','系统定义',1761400000000030001,1,'system','endpoint/system/index',NULL,'N','Y','C','0','0','endpoint:system:list','server','','',1761000000000000103,1761100000000000001,'逻辑系统目录'),
(1761400000000030003,'C:1761400000000030001:environment','环境配置',1761400000000030001,2,'environment','endpoint/environment/index',NULL,'N','Y','C','0','0','endpoint:environment:list','tree','','',1761000000000000103,1761100000000000001,'环境目录，当前运行环境只读展示'),
(1761400000000030004,'C:1761400000000030001:address','系统地址',1761400000000030001,3,'address','endpoint/address/index',NULL,'N','Y','C','0','0','endpoint:address:list','link','','',1761000000000000103,1761100000000000001,'环境共享地址与系统关联'),
(1761400000000030011,'F:1761400000000030002:endpoint:system:query','系统查询',1761400000000030002,1,'',NULL,NULL,'N','Y','F','0','0','endpoint:system:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030012,'F:1761400000000030002:endpoint:system:add','系统新增',1761400000000030002,2,'',NULL,NULL,'N','Y','F','0','0','endpoint:system:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030013,'F:1761400000000030002:endpoint:system:edit','系统修改',1761400000000030002,3,'',NULL,NULL,'N','Y','F','0','0','endpoint:system:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030014,'F:1761400000000030002:endpoint:audit:query','配置审计查询',1761400000000030002,4,'',NULL,NULL,'N','Y','F','0','0','endpoint:audit:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030015,'F:1761400000000030003:endpoint:environment:query','环境查询',1761400000000030003,1,'',NULL,NULL,'N','Y','F','0','0','endpoint:environment:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030016,'F:1761400000000030003:endpoint:environment:add','环境新增',1761400000000030003,2,'',NULL,NULL,'N','Y','F','0','0','endpoint:environment:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030017,'F:1761400000000030003:endpoint:environment:edit','环境修改',1761400000000030003,3,'',NULL,NULL,'N','Y','F','0','0','endpoint:environment:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030018,'F:1761400000000030004:endpoint:address:query','地址查询',1761400000000030004,1,'',NULL,NULL,'N','Y','F','0','0','endpoint:address:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030019,'F:1761400000000030004:endpoint:address:add','地址新增',1761400000000030004,2,'',NULL,NULL,'N','Y','F','0','0','endpoint:address:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000030020,'F:1761400000000030004:endpoint:address:edit','地址修改',1761400000000030004,3,'',NULL,NULL,'N','Y','F','0','0','endpoint:address:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031001,'M:0:process','计划事件',0,7,'process',NULL,NULL,'N','Y','M','0','0','','tree-table','','',1761000000000000103,1761100000000000001,'计划定义、事件执行与监督调度'),
(1761400000000031002,'C:1761400000000031001:schedule','业务调度',1761400000000031001,1,'schedule','process/schedule/index',NULL,'N','Y','C','0','0','process:schedule:list','calendar','','',1761000000000000103,1761100000000000001,'业务统一调度与监督控制'),
(1761400000000031003,'C:1761400000000031001:definition','计划定义',1761400000000031001,2,'definition','process/definition/index',NULL,'N','Y','C','0','0','process:definition:list','tree','','',1761000000000000103,1761100000000000001,'计划版本、状态、事件与边'),
(1761400000000031004,'C:1761400000000031001:execution','执行总览',1761400000000031001,3,'execution','process/execution/index',NULL,'N','Y','C','0','0','process:execution:list','dashboard','','',1761000000000000103,1761100000000000001,'执行、队列、异常与接口抛账记录'),
(1761400000000031005,'C:1761400000000031001:instance/detail','计划详情',1761400000000031001,4,'instance/detail','process/instance/detail',NULL,'N','Y','C','1','0','process:instance:query','#','/process/execution','',1761000000000000103,1761100000000000001,'隐藏计划执行链详情'),
(1761400000000031006,'C:1761400000000031001:definition/edit','计划配置编辑',1761400000000031001,5,'definition/edit','process/definition/edit',NULL,'N','Y','C','1','0','process:definition:edit','#','/process/definition','',1761000000000000103,1761100000000000001,'隐藏计划配置编辑页'),
(1761400000000031011,'F:1761400000000031002:process:schedule:query','调度查询',1761400000000031002,1,'',NULL,NULL,'N','Y','F','0','0','process:schedule:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031012,'F:1761400000000031002:process:schedule:add','调度新增',1761400000000031002,2,'',NULL,NULL,'N','Y','F','0','0','process:schedule:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031013,'F:1761400000000031002:process:schedule:edit','调度修改',1761400000000031002,3,'',NULL,NULL,'N','Y','F','0','0','process:schedule:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031014,'F:1761400000000031002:process:schedule:remove','调度删除',1761400000000031002,4,'',NULL,NULL,'N','Y','F','0','0','process:schedule:remove','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031015,'F:1761400000000031002:process:schedule:pause','监督暂停',1761400000000031002,5,'',NULL,NULL,'N','Y','F','0','0','process:schedule:pause','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031016,'F:1761400000000031002:process:schedule:resume','监督恢复',1761400000000031002,6,'',NULL,NULL,'N','Y','F','0','0','process:schedule:resume','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031017,'F:1761400000000031002:process:schedule:restart','监督重启worker',1761400000000031002,7,'',NULL,NULL,'N','Y','F','0','0','process:schedule:restart','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031018,'F:1761400000000031003:process:definition:query','计划定义查询',1761400000000031003,1,'',NULL,NULL,'N','Y','F','0','0','process:definition:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031019,'F:1761400000000031003:process:definition:add','计划草稿新增',1761400000000031003,2,'',NULL,NULL,'N','Y','F','0','0','process:definition:add','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031020,'F:1761400000000031003:process:definition:edit','计划草稿修改',1761400000000031003,3,'',NULL,NULL,'N','Y','F','0','0','process:definition:edit','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031021,'F:1761400000000031003:process:definition:copy','计划版本复制',1761400000000031003,4,'',NULL,NULL,'N','Y','F','0','0','process:definition:copy','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031022,'F:1761400000000031003:process:definition:publish','计划发布',1761400000000031003,5,'',NULL,NULL,'N','Y','F','0','0','process:definition:publish','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031023,'F:1761400000000031003:process:definition:remove','计划草稿删除',1761400000000031003,6,'',NULL,NULL,'N','Y','F','0','0','process:definition:remove','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031024,'F:1761400000000031004:process:instance:chain','执行链查询',1761400000000031004,1,'',NULL,NULL,'N','Y','F','0','0','process:instance:chain','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031025,'F:1761400000000031004:process:instance:attach','任务补挂',1761400000000031004,2,'',NULL,NULL,'N','Y','F','0','0','process:instance:attach','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031026,'F:1761400000000031004:process:instance:confirm','首次确认',1761400000000031004,3,'',NULL,NULL,'N','Y','F','0','0','process:instance:confirm','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031027,'F:1761400000000031004:process:instance:pause','实例暂停',1761400000000031004,4,'',NULL,NULL,'N','Y','F','0','0','process:instance:pause','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031028,'F:1761400000000031004:process:instance:resume','实例恢复',1761400000000031004,5,'',NULL,NULL,'N','Y','F','0','0','process:instance:resume','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031029,'F:1761400000000031004:process:instance:cancel','实例取消',1761400000000031004,6,'',NULL,NULL,'N','Y','F','0','0','process:instance:cancel','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031030,'F:1761400000000031004:process:instance:hold','挂起计划',1761400000000031004,7,'',NULL,NULL,'N','Y','F','0','0','process:instance:hold','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031031,'F:1761400000000031004:process:instance:release','放行计划',1761400000000031004,8,'',NULL,NULL,'N','Y','F','0','0','process:instance:release','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031032,'F:1761400000000031004:process:instance:priority','实例优先级',1761400000000031004,9,'',NULL,NULL,'N','Y','F','0','0','process:instance:priority','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031033,'F:1761400000000031004:process:instance:execute','即时执行',1761400000000031004,10,'',NULL,NULL,'N','Y','F','0','0','process:instance:execute','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031034,'F:1761400000000031004:process:trigger:receive','触发受理',1761400000000031004,11,'',NULL,NULL,'N','Y','F','0','0','process:trigger:receive','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031035,'F:1761400000000031004:process:event:query','事件查询',1761400000000031004,12,'',NULL,NULL,'N','Y','F','0','0','process:event:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031036,'F:1761400000000031004:process:event:retry','事件重试',1761400000000031004,13,'',NULL,NULL,'N','Y','F','0','0','process:event:retry','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031037,'F:1761400000000031004:process:event:revalidate','事件重新校验',1761400000000031004,14,'',NULL,NULL,'N','Y','F','0','0','process:event:revalidate','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031038,'F:1761400000000031004:process:event:finalize','事件最终失败',1761400000000031004,15,'',NULL,NULL,'N','Y','F','0','0','process:event:finalize','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031039,'F:1761400000000031004:process:event:priority','事件插队',1761400000000031004,16,'',NULL,NULL,'N','Y','F','0','0','process:event:priority','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031040,'F:1761400000000031004:process:event:request','请求报文查看',1761400000000031004,17,'',NULL,NULL,'N','Y','F','0','0','process:event:request','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031041,'F:1761400000000031004:process:audit:query','审计时间线',1761400000000031004,18,'',NULL,NULL,'N','Y','F','0','0','process:audit:query','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031042,'F:1761400000000031004:process:request:list','抛账记录列表',1761400000000031004,19,'',NULL,NULL,'N','Y','F','0','0','process:request:list','#','','',1761000000000000103,1761100000000000001,''),
(1761400000000031043,'F:1761400000000031004:process:request:query','抛账记录详情',1761400000000031004,20,'',NULL,NULL,'N','Y','F','0','0','process:request:query','#','','',1761000000000000103,1761100000000000001,'');

-- 先加载目标菜单快照；只读取 sys_menu，不改变目标数据。
SELECT COUNT(*) INTO @menu_schema_conflicts
FROM tmp_iws_menu_sys_columns e
LEFT JOIN information_schema.COLUMNS c
  ON CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
 AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
 AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable);
SET @menu_sys_schema_ok = IF(@menu_table_ok = 1 AND @menu_schema_conflicts = 0, 1, 0);
SET @menu_load_sql = IF(@menu_sys_schema_ok = 1, CONCAT(
    'INSERT INTO tmp_iws_menu_actual (menu_id, menu_name, parent_id, order_num, path, component, query_param,',
    ' is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by,',
    ' create_time, update_by, update_time, remark) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time,',
    ' update_by, update_time, remark FROM ', @upgrade_schema_q, '.`sys_menu`'
), 'DO 0');
PREPARE menu_preflight_load_stmt FROM @menu_load_sql;
EXECUTE menu_preflight_load_stmt;
DEALLOCATE PREPARE menu_preflight_load_stmt;

DELETE FROM tmp_iws_menu_conflicts;
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT CAST(m.menu_id AS char), '既有菜单ID字段与目标不一致'
FROM tmp_iws_menu_expected e
JOIN tmp_iws_menu_actual m ON m.menu_id = e.menu_id
WHERE NOT (m.menu_name <=> e.menu_name AND m.parent_id <=> e.parent_id AND m.order_num <=> e.order_num
  AND m.path <=> e.path AND m.component <=> e.component AND m.query_param <=> e.query_param
  AND m.is_frame <=> e.is_frame AND m.is_cache <=> e.is_cache AND m.menu_type <=> e.menu_type
  AND m.visible <=> e.visible AND m.status <=> e.status AND m.perms <=> e.perms
  AND m.icon <=> e.icon AND m.active_menu <=> e.active_menu AND m.ext <=> e.ext);
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), CONCAT('自然键已被其他菜单占用：', e.natural_key)
FROM tmp_iws_menu_expected e
JOIN tmp_iws_menu_actual m
  ON m.parent_id = e.parent_id AND m.menu_type = e.menu_type
 AND (CASE WHEN m.menu_type = 'F' THEN CONCAT('F:', m.parent_id, ':', COALESCE(m.perms, ''))
           ELSE CONCAT(m.menu_type, ':', m.parent_id, ':', COALESCE(m.path, '')) END) = e.natural_key
WHERE m.menu_id <> e.menu_id;
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT e.column_name, 'sys_menu 必需列缺失或类型不符'
FROM tmp_iws_menu_sys_columns e
LEFT JOIN information_schema.COLUMNS c
  ON CAST(c.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
 AND CAST(c.TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
 AND c.COLUMN_NAME = e.column_name
WHERE c.COLUMN_NAME IS NULL
   OR NOT (c.COLUMN_TYPE <=> e.column_type)
   OR NOT (c.IS_NULLABLE <=> e.is_nullable);
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT 'sys_menu', '缺少 menu_id 主键索引'
WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS s
    WHERE CAST(s.TABLE_SCHEMA AS BINARY) = CAST(@upgrade_schema AS BINARY)
      AND CAST(s.TABLE_NAME AS BINARY) = CAST('sys_menu' AS BINARY)
      AND s.INDEX_NAME = 'PRIMARY' AND s.SEQ_IN_INDEX = 1
      AND s.COLUMN_NAME = 'menu_id'
);

SELECT COUNT(*) INTO @menu_conflicts FROM tmp_iws_menu_conflicts;
SET @menu_preflight_ok = IF(@menu_base_ok = 1 AND @menu_conflicts = 0, 1, 0);
SELECT object_key AS `对象`, reason AS `冲突说明` FROM tmp_iws_menu_conflicts ORDER BY object_key, reason;
SELECT JSON_EXTRACT(IF(@menu_preflight_ok = 1, '{}', 'invalid'), '$') AS `菜单预检`;

SET @menu_insert_sql = IF(@menu_preflight_ok = 1, CONCAT(
    'INSERT INTO ', @upgrade_schema_q, '.`sys_menu` ',
    '(menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, CURRENT_TIMESTAMP, remark '
    'FROM tmp_iws_menu_expected e WHERE NOT EXISTS (SELECT 1 FROM ', @upgrade_schema_q, '.`sys_menu` m WHERE m.menu_id = e.menu_id) ORDER BY menu_id'
), 'DO 0');
PREPARE menu_stmt FROM @menu_insert_sql;
EXECUTE menu_stmt;
DEALLOCATE PREPARE menu_stmt;

-- 在插入后重新只读加载目标快照，独立核验本次菜单。
DELETE FROM tmp_iws_menu_post;
SET @menu_post_load_sql = IF(@menu_sys_schema_ok = 1, CONCAT(
    'INSERT INTO tmp_iws_menu_post (menu_id, menu_name, parent_id, order_num, path, component, query_param,',
    ' is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by,',
    ' create_time, update_by, update_time, remark) ',
    'SELECT menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,',
    ' menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time,',
    ' update_by, update_time, remark FROM ', @upgrade_schema_q, '.`sys_menu`'
), 'DO 0');
PREPARE menu_post_load_stmt FROM @menu_post_load_sql;
EXECUTE menu_post_load_stmt;
DEALLOCATE PREPARE menu_post_load_stmt;

DELETE FROM tmp_iws_menu_conflicts;
INSERT INTO tmp_iws_menu_conflicts (object_key, reason)
SELECT CAST(e.menu_id AS char), '菜单写入后仍缺失'
FROM tmp_iws_menu_expected e
LEFT JOIN tmp_iws_menu_post p ON p.menu_id = e.menu_id
WHERE p.menu_id IS NULL;
INSERT INTO tmp_iws_menu_conflicts
SELECT CAST(m.menu_id AS char), '菜单写入后字段不一致'
FROM tmp_iws_menu_expected e
JOIN tmp_iws_menu_post m ON m.menu_id = e.menu_id
WHERE NOT (m.menu_name <=> e.menu_name AND m.parent_id <=> e.parent_id AND m.order_num <=> e.order_num
  AND m.path <=> e.path AND m.component <=> e.component AND m.query_param <=> e.query_param
  AND m.is_frame <=> e.is_frame AND m.is_cache <=> e.is_cache AND m.menu_type <=> e.menu_type
  AND m.visible <=> e.visible AND m.status <=> e.status AND m.perms <=> e.perms
  AND m.icon <=> e.icon AND m.active_menu <=> e.active_menu AND m.ext <=> e.ext);
INSERT INTO tmp_iws_menu_conflicts
SELECT CAST(e.menu_id AS char), '菜单父级不存在或不是目录'
FROM tmp_iws_menu_expected e
LEFT JOIN tmp_iws_menu_post p ON p.menu_id = e.parent_id
WHERE e.parent_id <> 0
  AND (p.menu_id IS NULL
       OR (e.menu_type = 'C' AND p.menu_type <> 'M')
       OR (e.menu_type = 'F' AND p.menu_type <> 'C'));
SELECT COUNT(*) INTO @menu_conflicts FROM tmp_iws_menu_conflicts;
SET @menu_post_ok = IF(@menu_preflight_ok = 1 AND @menu_conflicts = 0, 1, 0);
SELECT object_key AS `对象`, reason AS `冲突说明` FROM tmp_iws_menu_conflicts ORDER BY object_key, reason;
SELECT JSON_EXTRACT(IF(@menu_post_ok = 1, '{}', 'invalid'), '$') AS `菜单后置校验`;

SELECT
    IF(@menu_post_ok = 1, 'endpoint 与 process 菜单权限升级完成', '菜单权限升级失败') AS `执行结果`,
    @upgrade_schema AS `目标库`,
    (SELECT COUNT(*) FROM tmp_iws_menu_expected) AS `本次菜单数`;

SELECT menu_id AS `菜单ID`, menu_name AS `菜单名称`, parent_id AS `父级ID`, menu_type AS `类型`, perms AS `权限标识`
FROM tmp_iws_menu_expected ORDER BY menu_id;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_post;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_conflicts;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_expected;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_menu_sys_columns;

-- ----------------------------------------------------------------------------
-- APK 应用版本管理：仅保留发布记录表；业务系统不再初始化 APK 菜单
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_apk_release` (
  `release_id` bigint NOT NULL,
  `app_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `package_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `version_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `version_code` bigint NOT NULL,
  `sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `file_size` bigint NOT NULL,
  `oss_id` bigint NOT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'UPLOADED',
  `force_update` tinyint(1) NOT NULL DEFAULT 1,
  `process_instance_id` bigint DEFAULT NULL,
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_dept` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `download_count` bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (`release_id`),
  UNIQUE KEY `uk_apk_package_version` (`package_name`,`version_code`),
  KEY `idx_apk_name` (`app_name`),
  KEY `idx_apk_published` (`package_name`,`status`,`version_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 在库管理公司最小闭环：复用既有在库管理目录作为父级（visible 0 显示、1 隐藏）；只登记权限，不授予角色。
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000050100,'公司管理',1761400000000020004,1,'company','wms/inventory/company/index','','N','Y','C','0','0','inventory:company:query','peoples','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'公司最小闭环', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000051001,'公司新增',1761400000000050100,1,'',NULL,'','N','Y','F','0','0','inventory:company:create','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000051002,'公司修改',1761400000000050100,2,'',NULL,'','N','Y','F','0','0','inventory:company:update','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000051003,'公司启用',1761400000000050100,3,'',NULL,'','N','Y','F','0','0','inventory:company:enable','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000051004,'公司停用',1761400000000050100,4,'',NULL,'','N','Y','F','0','0','inventory:company:disable','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000051005,'跨公司范围',1761400000000050100,5,'',NULL,'','N','Y','F','0','0','inventory:company:scope:all','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000051006,'管理范围授权',1761400000000050100,6,'',NULL,'','N','Y','F','0','0','inventory:company:scope:manage','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');

-- 空间菜单复用既有在库管理根；登记页面、查询与生命周期命令权限，不授予角色。
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000050200,'空间查询',1761400000000020004,2,'space','wms/inventory/space/index','','N','Y','C','0','0','','tree','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000052001,'仓库查询',1761400000000050200,1,'',NULL,'','N','Y','F','0','0','inventory:warehouse:query','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000052002,'库区查询',1761400000000050200,2,'',NULL,'','N','Y','F','0','0','inventory:zone:query','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000052003,'储位查询',1761400000000050200,3,'',NULL,'','N','Y','F','0','0','inventory:storage:query','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000052004,'空间变更',1761400000000050200,4,'',NULL,'','N','Y','F','0','0','inventory:space:change','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');

-- 库存业务目录复用既有在库管理根；只登记页面，不创建子按钮或角色授权。
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000050300,'库存业务目录',1761400000000020004,3,'catalog','wms/inventory/catalog/index','','N','Y','C','0','0','inventory:business:query','list','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');

-- 在库管理 P7 只读页面与权限：业务任务使用独立 inventory:task:query；只登记，不授予角色。
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000050400,'在库物料',1761400000000020004,4,'materials','wms/inventory/material/index','','N','Y','C','0','0','','goods','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000054001,'物料查询',1761400000000050400,1,'',NULL,'','N','Y','F','0','0','inventory:material:query','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000050500,'在库规则',1761400000000020004,5,'rules','wms/inventory/rules/index','','N','Y','C','0','0','','skill','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000055001,'规则查询',1761400000000050500,1,'',NULL,'','N','Y','F','0','0','inventory:rule:query','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000055002,'草稿预览',1761400000000050500,2,'',NULL,'','N','Y','F','0','0','inventory:rule:draft','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000055003,'规则发布',1761400000000050500,3,'',NULL,'','N','Y','F','0','0','inventory:rule:publish','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000055004,'逻辑合并',1761400000000050500,4,'',NULL,'','N','Y','F','0','0','inventory:logical-merge:manage','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000050600,'物料追踪',1761400000000020004,6,'traces','wms/inventory/trace/index','','N','Y','C','0','0','','chart','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000056001,'追踪查询',1761400000000050600,1,'',NULL,'','N','Y','F','0','0','inventory:trace:query','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000056002,'追踪导出',1761400000000050600,2,'',NULL,'','N','Y','F','0','0','inventory:trace:export','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000056003,'UNKNOWN查证',1761400000000050600,3,'',NULL,'','N','Y','F','0','0','inventory:allocation:investigate','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000050700,'在库业务',1761400000000020004,7,'business','wms/inventory/business/index','','N','Y','C','0','0','','log','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query_param`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `active_menu`, `ext`, `create_dept`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`, `application_id`) VALUES (1761400000000057001,'业务任务查询',1761400000000050700,1,'',NULL,'','N','Y','F','0','0','inventory:task:query','#','','',NULL,NULL,CURRENT_TIMESTAMP,NULL,NULL,'', 'iws-a');

-- 认证中心、AI、共享监控/工具及官网归共享范围，其余初始化业务菜单归 IWS-A
WITH RECURSIVE shared_menu (menu_id) AS (
    SELECT menu_id
    FROM sys_menu
    WHERE menu_id IN (
        1761400000000000001,
        1761400000000000002,
        1761400000000000003,
        1761400000000000004,
        1761400000000000008
    )
    UNION ALL
    SELECT child.menu_id
    FROM sys_menu child
    INNER JOIN shared_menu parent ON child.parent_id = parent.menu_id
)
UPDATE sys_menu target
INNER JOIN shared_menu scope ON scope.menu_id = target.menu_id
SET target.application_id = '*';

-- ----------------------------------------------------------------------------
-- 最终验收：结构数量、必要种子和空业务表不变量
-- ----------------------------------------------------------------------------
SET @init_final_table_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@init_schema AS BINARY)
);
SET @init_final_menu_count = (SELECT COUNT(*) FROM sys_menu);
SET @init_final_menu_application_column_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @init_schema
      AND TABLE_NAME = 'sys_menu'
      AND COLUMN_NAME = 'application_id'
      AND LOWER(COLUMN_TYPE) = 'varchar(64)'
      AND IS_NULLABLE = 'NO'
      AND COALESCE(COLUMN_DEFAULT, '') = 'iws-a'
);
SET @init_final_menu_application_index_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @init_schema
      AND TABLE_NAME = 'sys_menu'
      AND INDEX_NAME = 'idx_sys_menu_application'
      AND COLUMN_NAME = 'application_id'
      AND NON_UNIQUE = 1
);
SET @init_final_menu_application_data_ok = (
    SELECT COUNT(*) = 255
       AND SUM(application_id = '*') = 100
       AND SUM(application_id = 'iws-a') = 155
       AND SUM(application_id NOT IN ('*', 'iws-a')) = 0
    FROM sys_menu
);
SET @init_final_role_menu_scope_table_count = (
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @init_schema
      AND TABLE_NAME IN ('sys_role_menu_scope', 'sys_role_menu_application')
);
SET @init_final_event_menu_count = (
    SELECT COUNT(*) FROM sys_menu
    WHERE menu_id BETWEEN 1761400000000030001 AND 1761400000000031043
);
SET @init_final_role_menu_count = (SELECT COUNT(*) FROM sys_role_menu);
SET @init_final_space_conflict_count = (
    SELECT COUNT(*)
    FROM sys_menu m
    WHERE (m.menu_id <> 1761400000000050200
           AND m.parent_id = 1761400000000020004
           AND CONVERT(m.path USING utf8mb4) COLLATE utf8mb4_general_ci = 'space')
       OR (m.menu_id NOT IN (
               1761400000000052001, 1761400000000052002,
               1761400000000052003, 1761400000000052004
           )
           AND CONVERT(m.perms USING utf8mb4) COLLATE utf8mb4_general_ci IN (
               'inventory:warehouse:query',
               'inventory:zone:query',
               'inventory:storage:query',
               'inventory:space:change'
           ))
       OR (m.menu_id <> 1761400000000020004
           AND m.parent_id = 0
           AND CONVERT(m.menu_name USING utf8mb4) COLLATE utf8mb4_general_ci = '在库管理')
);
SET @init_final_space_root_ok = (
    SELECT COUNT(*) = 1
    FROM sys_menu
    WHERE menu_id = 1761400000000020004
      AND (CAST(menu_name AS BINARY) <=> CAST('在库管理' AS BINARY))
      AND parent_id = 0
      AND order_num = 13
      AND (CAST(path AS BINARY) <=> CAST('iws-inventory' AS BINARY))
      AND component IS NULL
      AND (CAST(query_param AS BINARY) <=> CAST('' AS BINARY))
      AND is_frame = 'N'
      AND is_cache = 'Y'
      AND menu_type = 'M'
      AND visible = '0'
      AND status = '0'
      AND (CAST(perms AS BINARY) <=> CAST('' AS BINARY))
      AND (CAST(icon AS BINARY) <=> CAST('list' AS BINARY))
      AND (CAST(active_menu AS BINARY) <=> CAST('' AS BINARY))
      AND (CAST(ext AS BINARY) <=> CAST('' AS BINARY))
);
SET @init_final_space_menu_ok = (
    SELECT COUNT(*) = 5
    FROM sys_menu
    WHERE (
        menu_id = 1761400000000050200
        AND (CAST(menu_name AS BINARY) <=> CAST('空间查询' AS BINARY))
        AND parent_id = 1761400000000020004
        AND order_num = 2
        AND (CAST(path AS BINARY) <=> CAST('space' AS BINARY))
        AND (CAST(component AS BINARY) <=> CAST('wms/inventory/space/index' AS BINARY))
        AND (CAST(query_param AS BINARY) <=> CAST('' AS BINARY))
        AND is_frame = 'N'
        AND is_cache = 'Y'
        AND menu_type = 'C'
        AND visible = '0'
        AND status = '0'
        AND (CAST(perms AS BINARY) <=> CAST('' AS BINARY))
        AND (CAST(icon AS BINARY) <=> CAST('tree' AS BINARY))
        AND (CAST(active_menu AS BINARY) <=> CAST('' AS BINARY))
        AND (CAST(ext AS BINARY) <=> CAST('' AS BINARY))
    ) OR (
        menu_id = 1761400000000052001
        AND (CAST(menu_name AS BINARY) <=> CAST('仓库查询' AS BINARY))
        AND parent_id = 1761400000000050200
        AND order_num = 1
        AND (CAST(path AS BINARY) <=> CAST('' AS BINARY))
        AND component IS NULL
        AND (CAST(query_param AS BINARY) <=> CAST('' AS BINARY))
        AND is_frame = 'N'
        AND is_cache = 'Y'
        AND menu_type = 'F'
        AND visible = '0'
        AND status = '0'
        AND (CAST(perms AS BINARY) <=> CAST('inventory:warehouse:query' AS BINARY))
        AND (CAST(icon AS BINARY) <=> CAST('#' AS BINARY))
        AND (CAST(active_menu AS BINARY) <=> CAST('' AS BINARY))
        AND (CAST(ext AS BINARY) <=> CAST('' AS BINARY))
    ) OR (
        menu_id = 1761400000000052002
        AND (CAST(menu_name AS BINARY) <=> CAST('库区查询' AS BINARY))
        AND parent_id = 1761400000000050200
        AND order_num = 2
        AND (CAST(path AS BINARY) <=> CAST('' AS BINARY))
        AND component IS NULL
        AND (CAST(query_param AS BINARY) <=> CAST('' AS BINARY))
        AND is_frame = 'N'
        AND is_cache = 'Y'
        AND menu_type = 'F'
        AND visible = '0'
        AND status = '0'
        AND (CAST(perms AS BINARY) <=> CAST('inventory:zone:query' AS BINARY))
        AND (CAST(icon AS BINARY) <=> CAST('#' AS BINARY))
        AND (CAST(active_menu AS BINARY) <=> CAST('' AS BINARY))
        AND (CAST(ext AS BINARY) <=> CAST('' AS BINARY))
    ) OR (
        menu_id = 1761400000000052003
        AND (CAST(menu_name AS BINARY) <=> CAST('储位查询' AS BINARY))
        AND parent_id = 1761400000000050200
        AND order_num = 3
        AND (CAST(path AS BINARY) <=> CAST('' AS BINARY))
        AND component IS NULL
        AND (CAST(query_param AS BINARY) <=> CAST('' AS BINARY))
        AND is_frame = 'N'
        AND is_cache = 'Y'
        AND menu_type = 'F'
        AND visible = '0'
        AND status = '0'
        AND (CAST(perms AS BINARY) <=> CAST('inventory:storage:query' AS BINARY))
        AND (CAST(icon AS BINARY) <=> CAST('#' AS BINARY))
        AND (CAST(active_menu AS BINARY) <=> CAST('' AS BINARY))
        AND (CAST(ext AS BINARY) <=> CAST('' AS BINARY))
    ) OR (
        menu_id = 1761400000000052004
        AND (CAST(menu_name AS BINARY) <=> CAST('空间变更' AS BINARY))
        AND parent_id = 1761400000000050200
        AND order_num = 4
        AND (CAST(path AS BINARY) <=> CAST('' AS BINARY))
        AND component IS NULL
        AND (CAST(query_param AS BINARY) <=> CAST('' AS BINARY))
        AND is_frame = 'N'
        AND is_cache = 'Y'
        AND menu_type = 'F'
        AND visible = '0'
        AND status = '0'
        AND (CAST(perms AS BINARY) <=> CAST('inventory:space:change' AS BINARY))
        AND (CAST(icon AS BINARY) <=> CAST('#' AS BINARY))
        AND (CAST(active_menu AS BINARY) <=> CAST('' AS BINARY))
        AND (CAST(ext AS BINARY) <=> CAST('' AS BINARY))
    )
);
SET @init_final_space_menu_ok = (@init_final_space_menu_ok = 1 AND @init_final_space_conflict_count = 0);
SET @init_final_catalog_menu_ok = (
    SELECT COUNT(*) = 1
    FROM sys_menu
    WHERE menu_id = 1761400000000050300
      AND (CAST(menu_name AS BINARY) <=> CAST('库存业务目录' AS BINARY))
      AND parent_id = 1761400000000020004
      AND order_num = 3
      AND (CAST(path AS BINARY) <=> CAST('catalog' AS BINARY))
      AND (CAST(component AS BINARY) <=> CAST('wms/inventory/catalog/index' AS BINARY))
      AND (CAST(query_param AS BINARY) <=> CAST('' AS BINARY))
      AND is_frame = 'N'
      AND is_cache = 'Y'
      AND menu_type = 'C'
      AND visible = '0'
      AND status = '0'
      AND (CAST(perms AS BINARY) <=> CAST('inventory:business:query' AS BINARY))
      AND (CAST(icon AS BINARY) <=> CAST('list' AS BINARY))
      AND (CAST(active_menu AS BINARY) <=> CAST('' AS BINARY))
      AND (CAST(ext AS BINARY) <=> CAST('' AS BINARY))
      AND create_dept IS NULL
      AND create_by IS NULL
      AND create_time IS NOT NULL
      AND update_by IS NULL
      AND update_time IS NULL
      AND (CAST(remark AS BINARY) <=> CAST('' AS BINARY))
);
DROP TEMPORARY TABLE IF EXISTS tmp_iws_init_p7_expected;
CREATE TEMPORARY TABLE tmp_iws_init_p7_expected (
    menu_id bigint NOT NULL PRIMARY KEY,
    menu_name varchar(50) NOT NULL,
    parent_id bigint NOT NULL,
    order_num int NOT NULL,
    path varchar(200) NOT NULL,
    component varchar(255) DEFAULT NULL,
    query_param varchar(255) NOT NULL,
    is_frame char(1) NOT NULL,
    is_cache char(1) NOT NULL,
    menu_type char(1) NOT NULL,
    visible char(1) NOT NULL,
    status char(1) NOT NULL,
    perms varchar(100) NOT NULL,
    icon varchar(100) NOT NULL,
    active_menu varchar(255) NOT NULL,
    ext varchar(2000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO tmp_iws_init_p7_expected
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext)
VALUES
    (1761400000000020004, '在库管理', 0, 13, 'iws-inventory', NULL, '',
     'N', 'Y', 'M', '0', '0', '', 'list', '', ''),
    (1761400000000050400, '在库物料', 1761400000000020004, 4, 'materials',
     'wms/inventory/material/index', '', 'N', 'Y', 'C', '0', '0', '', 'goods', '', ''),
    (1761400000000054001, '物料查询', 1761400000000050400, 1, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:material:query', '#', '', ''),
    (1761400000000050500, '在库规则', 1761400000000020004, 5, 'rules',
     'wms/inventory/rules/index', '', 'N', 'Y', 'C', '0', '0', '', 'skill', '', ''),
    (1761400000000055001, '规则查询', 1761400000000050500, 1, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:rule:query', '#', '', ''),
    (1761400000000055002, '草稿预览', 1761400000000050500, 2, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:rule:draft', '#', '', ''),
    (1761400000000055003, '规则发布', 1761400000000050500, 3, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:rule:publish', '#', '', ''),
    (1761400000000055004, '逻辑合并', 1761400000000050500, 4, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:logical-merge:manage', '#', '', ''),
    (1761400000000050600, '物料追踪', 1761400000000020004, 6, 'traces',
     'wms/inventory/trace/index', '', 'N', 'Y', 'C', '0', '0', '', 'chart', '', ''),
    (1761400000000056001, '追踪查询', 1761400000000050600, 1, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:trace:query', '#', '', ''),
    (1761400000000056002, '追踪导出', 1761400000000050600, 2, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:trace:export', '#', '', ''),
    (1761400000000056003, 'UNKNOWN查证', 1761400000000050600, 3, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:allocation:investigate', '#', '', ''),
    (1761400000000050700, '在库业务', 1761400000000020004, 7, 'business',
     'wms/inventory/business/index', '', 'N', 'Y', 'C', '0', '0', '', 'log', '', ''),
    (1761400000000057001, '业务任务查询', 1761400000000050700, 1, '', NULL, '',
     'N', 'Y', 'F', '0', '0', 'inventory:task:query', '#', '', '');
SET @init_final_p7_conflict_count = (
    SELECT COUNT(*)
    FROM tmp_iws_init_p7_expected e
    JOIN sys_menu m ON m.menu_id <> e.menu_id
    WHERE (e.path <> '' AND m.parent_id = e.parent_id
           AND CONVERT(m.path USING utf8mb4) COLLATE utf8mb4_general_ci = e.path)
       OR (e.perms <> ''
           AND CONVERT(m.perms USING utf8mb4) COLLATE utf8mb4_general_ci = e.perms)
       OR (e.menu_type IN ('M', 'C') AND m.parent_id = e.parent_id
           AND CONVERT(m.menu_name USING utf8mb4) COLLATE utf8mb4_general_ci = e.menu_name)
);
SET @init_final_p7_menu_ok = (
    SELECT COUNT(*) = 14
    FROM tmp_iws_init_p7_expected e JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE (CAST(m.menu_name AS BINARY) <=> CAST(e.menu_name AS BINARY))
      AND (m.parent_id <=> e.parent_id) AND (m.order_num <=> e.order_num)
      AND (CAST(m.path AS BINARY) <=> CAST(e.path AS BINARY))
      AND (CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY))
      AND (CAST(m.query_param AS BINARY) <=> CAST(e.query_param AS BINARY))
      AND (CAST(m.is_frame AS BINARY) <=> CAST(e.is_frame AS BINARY))
      AND (CAST(m.is_cache AS BINARY) <=> CAST(e.is_cache AS BINARY))
      AND (CAST(m.menu_type AS BINARY) <=> CAST(e.menu_type AS BINARY))
      AND (CAST(m.visible AS BINARY) <=> CAST(e.visible AS BINARY))
      AND (CAST(m.status AS BINARY) <=> CAST(e.status AS BINARY))
      AND (CAST(m.perms AS BINARY) <=> CAST(e.perms AS BINARY))
      AND (CAST(m.icon AS BINARY) <=> CAST(e.icon AS BINARY))
      AND (CAST(m.active_menu AS BINARY) <=> CAST(e.active_menu AS BINARY))
      AND (CAST(m.ext AS BINARY) <=> CAST(e.ext AS BINARY))
);
SET @init_final_p7_menu_ok = (@init_final_p7_menu_ok = 1 AND @init_final_p7_conflict_count = 0);
DROP TEMPORARY TABLE IF EXISTS tmp_iws_init_p7_expected;

-- WMS 在库管理计划目录与挂载 seed。计划定义由当前应用在计划事件启用时
-- 通过计划定义服务幂等 provision，空库脚本不直接伪造已发布定义或运行实例。
SET @init_seed_schedule_id = 1761400000000060001;
SET @init_seed_plan_type_id = 1761400000000060101;
SET @init_seed_space_schedule_id = 1761400000000060002;
SET @init_seed_space_change_type_id = 1761400000000060102;
SET @init_seed_space_retire_type_id = 1761400000000060103;
SET @init_seed_logical_schedule_id = 1761400000000060003;
SET @init_seed_logical_batch_type_id = 1761400000000060104;
SET @init_seed_logical_close_type_id = 1761400000000060105;
SET @init_seed_business_type = 'WMS_RULESET_PUBLISH';
SET @init_seed_plan_type = 'RULESET_PUBLISH';
SET @init_seed_schedule_code = 'INVENTORY_CONFIG';
SET @init_seed_mount_id = 1761400000000060201;
SET @init_seed_space_business_type = 'WMS_SPACE_CHANGE';
SET @init_seed_logical_business_type = 'WMS_LOGICAL_MERGE';
SET @init_seed_space_schedule_code = 'SPACE_CONFIG';
SET @init_seed_logical_schedule_code = 'LOGICAL_MERGE';
SET @init_seed_space_mount_id = 1761400000000060202;
SET @init_seed_space_retire_mount_id = 1761400000000060203;
SET @init_seed_logical_batch_mount_id = 1761400000000060204;
SET @init_seed_logical_close_mount_id = 1761400000000060205;

INSERT INTO iws_business_schedule
    (id, business_code, business_name, enabled, fairness_window_size,
     revision, remark, del_flag, create_time, update_time)
SELECT @init_seed_schedule_id, @init_seed_schedule_code, '库存配置', 1, 32,
       0, 'WMS 在库管理配置类计划统一调度', '0',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM iws_business_schedule
    WHERE CAST(business_code AS BINARY) = CAST(@init_seed_schedule_code AS BINARY)
);

INSERT INTO iws_business_schedule
    (id, business_code, business_name, enabled, fairness_window_size,
     revision, remark, del_flag, create_time, update_time)
SELECT @init_seed_space_schedule_id, @init_seed_space_schedule_code, '空间配置',
       1, 32, 0, 'WMS 空间生命周期计划统一调度', '0',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM iws_business_schedule
    WHERE CAST(business_code AS BINARY) = CAST(@init_seed_space_schedule_code AS BINARY)
);

INSERT INTO iws_business_schedule
    (id, business_code, business_name, enabled, fairness_window_size,
     revision, remark, del_flag, create_time, update_time)
SELECT @init_seed_logical_schedule_id, @init_seed_logical_schedule_code,
       '逻辑归属合并', 1, 32, 0, 'WMS 逻辑归属合并计划统一调度', '0',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM iws_business_schedule
    WHERE CAST(business_code AS BINARY) = CAST(@init_seed_logical_schedule_code AS BINARY)
);

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @init_seed_plan_type_id, schedule.id, @init_seed_plan_type,
       '规则包发布', 10, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@init_seed_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST(@init_seed_plan_type AS BINARY)
  );

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @init_seed_space_change_type_id, schedule.id, 'SPACE_CHANGE',
       '空间配置变更', 10, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@init_seed_space_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST('SPACE_CHANGE' AS BINARY)
  );

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @init_seed_logical_batch_type_id, schedule.id, 'LOGICAL_MERGE',
       '逻辑合并批次', 10, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@init_seed_logical_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST('LOGICAL_MERGE' AS BINARY)
  );

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @init_seed_logical_close_type_id, schedule.id, 'LOGICAL_MERGE_CLOSE',
       '逻辑合并收敛', 20, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@init_seed_logical_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST('LOGICAL_MERGE_CLOSE' AS BINARY)
  );

INSERT INTO iws_business_schedule_plan_type
    (id, schedule_id, plan_type, plan_type_name, priority, enabled,
     create_time, update_time)
SELECT @init_seed_space_retire_type_id, schedule.id, 'SPACE_RETIRE',
       '空间退役', 20, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM iws_business_schedule schedule
WHERE CAST(schedule.business_code AS BINARY) = CAST(@init_seed_space_schedule_code AS BINARY)
  AND NOT EXISTS (
      SELECT 1 FROM iws_business_schedule_plan_type type_row
      WHERE type_row.schedule_id = schedule.id
        AND CAST(type_row.plan_type AS BINARY) = CAST('SPACE_RETIRE' AS BINARY)
  );

INSERT INTO wms_business_type
    (business_type_code, business_type_name, status, contract_version,
     object_policy_key, revision, create_time)
SELECT @init_seed_business_type, '规则包发布', 'ACTIVE', '1',
       @init_seed_business_type, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_type
    WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_business_type AS BINARY)
);

INSERT INTO wms_business_type
    (business_type_code, business_type_name, status, contract_version,
     object_policy_key, revision, create_time)
SELECT @init_seed_space_business_type, '空间配置变更', 'ACTIVE', '1',
       @init_seed_space_business_type, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_type
    WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_space_business_type AS BINARY)
);

INSERT INTO wms_business_type
    (business_type_code, business_type_name, status, contract_version,
     object_policy_key, revision, create_time)
SELECT @init_seed_logical_business_type, '逻辑归属合并', 'ACTIVE', '1',
       @init_seed_logical_business_type, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_type
    WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_logical_business_type AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @init_seed_mount_id, @init_seed_business_type, @init_seed_plan_type,
       @init_seed_schedule_code, '1', 1, 1, CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST(@init_seed_plan_type AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@init_seed_schedule_code AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @init_seed_logical_batch_mount_id, @init_seed_logical_business_type,
       'LOGICAL_MERGE', @init_seed_logical_schedule_code, '1', 1, 1,
       CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_logical_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST('LOGICAL_MERGE' AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@init_seed_logical_schedule_code AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @init_seed_logical_close_mount_id, @init_seed_logical_business_type,
       'LOGICAL_MERGE_CLOSE', @init_seed_logical_schedule_code, '1', 1, 1,
       CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_logical_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST('LOGICAL_MERGE_CLOSE' AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@init_seed_logical_schedule_code AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @init_seed_space_mount_id, @init_seed_space_business_type,
       'SPACE_CHANGE', @init_seed_space_schedule_code, '1', 1, 1,
       CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_space_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST('SPACE_CHANGE' AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@init_seed_space_schedule_code AS BINARY)
);

INSERT INTO wms_business_plan_mount
    (mount_id, business_type_code, plan_type_code, schedule_code,
     contract_version, enabled, revision, create_time)
SELECT @init_seed_space_retire_mount_id, @init_seed_space_business_type,
       'SPACE_RETIRE', @init_seed_space_schedule_code, '1', 1, 1,
       CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (
    SELECT 1 FROM wms_business_plan_mount
    WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_space_business_type AS BINARY)
      AND CAST(plan_type_code AS BINARY) = CAST('SPACE_RETIRE' AS BINARY)
      AND CAST(schedule_code AS BINARY) = CAST(@init_seed_space_schedule_code AS BINARY)
);

SET @init_final_user_count = (SELECT COUNT(*) FROM sys_user);
SET @init_final_job_admin_count = (
    SELECT COUNT(*) FROM sj_system_user WHERE username = 'innolux' AND role = 2
);
SET @init_final_snailjob_namespace_count = (
    SELECT COUNT(*) FROM sj_namespace WHERE deleted = 0 AND unique_id IN ('dev', 'prod')
);
SET @init_final_snailjob_group_ok = (
    SELECT COUNT(*) = 2
    FROM sj_group_config
    WHERE group_name = 'iws_group'
      AND group_status = 1
      AND namespace_id IN ('dev', 'prod')
      AND CAST(token AS BINARY) = CAST(@init_snailjob_group_token AS BINARY)
);
SET @init_final_dict_type_count = (SELECT COUNT(*) FROM sys_dict_type);
SET @init_final_dict_data_count = (SELECT COUNT(*) FROM sys_dict_data);
SET @init_final_client_count = (SELECT COUNT(*) FROM sys_client);
SET @init_final_oss_count = (SELECT COUNT(*) FROM sys_oss_config);
SET @init_final_flow_category_count = (SELECT COUNT(*) FROM flow_category);
SET @init_final_flow_spel_count = (SELECT COUNT(*) FROM flow_spel);
SET @init_final_ai_user_count = (SELECT COUNT(*) FROM sai_user);
SET @init_final_ai_provider_count = (SELECT COUNT(*) FROM sai_model_provider);
SET @init_final_ai_agent_count = (SELECT COUNT(*) FROM sai_agent);
SET @init_final_ai_default_agent_ok = (
    SELECT COUNT(*) = 1
    FROM sai_agent
    WHERE id = 1
      AND name = 'IWS+ 助手'
      AND status = 1
      AND app_id = '1'
      AND chat_model_id = 1
);
SET @init_final_ai_openapi_user_count = (SELECT COUNT(*) FROM sai_openapi_user);
SET @init_final_ai_app_count = (SELECT COUNT(*) FROM sai_app);
SET @init_final_ai_default_app_ok = (
    SELECT COUNT(*) = 1
    FROM sai_app
    WHERE app_id = '1'
      AND app_name = 'IWS+'
      AND description = 'IWS+ 主应用，供主服务 SnailAI 客户端接入'
      AND token IS NOT NULL
      AND CHAR_LENGTH(token) > 0
      AND CAST(token AS BINARY) = CAST(@init_snailai_default_app_token AS BINARY)
      AND route_strategy = 'LEAST_LOAD'
      AND status = 1
);
SET @init_final_control_count = (SELECT COUNT(*) FROM iws_endpoint_catalog_control);
SET @init_final_control_revision = (SELECT COALESCE(MAX(revision), -1) FROM iws_endpoint_catalog_control);
SET @init_final_endpoint_environment_count = (SELECT COUNT(*) FROM iws_endpoint_environment);
SET @init_final_endpoint_default_environment_ok = (
    SELECT COUNT(*) = 1
    FROM iws_endpoint_environment
    WHERE environment_code = 'TEST'
      AND display_name = '测试环境'
      AND enabled = 1
      AND revision = 0
);
SET @init_final_endpoint_default_system_ok = (
    SELECT COUNT(*) = 1
    FROM iws_endpoint_system
    WHERE system_code = 'IWS'
      AND enabled = 1
      AND revision = 0
);
SET @init_final_endpoint_business_rows = (
    (SELECT COUNT(*) FROM iws_endpoint_system)
  + (SELECT COUNT(*) FROM iws_endpoint_address)
  + (SELECT COUNT(*) FROM iws_endpoint_system_address)
  + (SELECT COUNT(*) FROM iws_endpoint_audit)
);
SET @init_final_plan_catalog_ok = (
    (SELECT COUNT(*) FROM iws_business_schedule
     WHERE id = @init_seed_schedule_id
       AND CAST(business_code AS BINARY) = CAST(@init_seed_schedule_code AS BINARY)
       AND business_name = '库存配置'
       AND enabled = 1 AND del_flag = '0') = 1
    AND
    (SELECT COUNT(*) FROM iws_business_schedule_plan_type
     WHERE id = @init_seed_plan_type_id
       AND schedule_id = @init_seed_schedule_id
       AND CAST(plan_type AS BINARY) = CAST(@init_seed_plan_type AS BINARY)
       AND plan_type_name = '规则包发布'
       AND enabled = 1) = 1
    AND
    (SELECT COUNT(*) FROM wms_business_type
     WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_business_type AS BINARY)
       AND business_type_name = '规则包发布'
       AND status = 'ACTIVE'
       AND CAST(contract_version AS BINARY) = CAST('1' AS BINARY)) = 1
    AND
    (SELECT COUNT(*) FROM wms_business_plan_mount
     WHERE mount_id = @init_seed_mount_id
       AND CAST(business_type_code AS BINARY) = CAST(@init_seed_business_type AS BINARY)
       AND CAST(plan_type_code AS BINARY) = CAST(@init_seed_plan_type AS BINARY)
       AND CAST(schedule_code AS BINARY) = CAST(@init_seed_schedule_code AS BINARY)
       AND enabled = 1) = 1
    AND
    (SELECT COUNT(*) FROM iws_business_schedule
     WHERE id = @init_seed_space_schedule_id
       AND CAST(business_code AS BINARY) = CAST(@init_seed_space_schedule_code AS BINARY)
       AND business_name = '空间配置'
       AND enabled = 1 AND del_flag = '0') = 1
    AND
    (SELECT COUNT(*) FROM iws_business_schedule_plan_type
     WHERE id IN (@init_seed_space_change_type_id, @init_seed_space_retire_type_id)
       AND schedule_id = @init_seed_space_schedule_id
       AND enabled = 1) = 2
    AND
    (SELECT COUNT(*) FROM wms_business_type
     WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_space_business_type AS BINARY)
       AND business_type_name = '空间配置变更'
       AND status = 'ACTIVE') = 1
    AND
    (SELECT COUNT(*) FROM wms_business_plan_mount
     WHERE mount_id IN (@init_seed_space_mount_id, @init_seed_space_retire_mount_id)
       AND enabled = 1) = 2
    AND
    (SELECT COUNT(*) FROM iws_business_schedule
     WHERE id = @init_seed_logical_schedule_id
       AND CAST(business_code AS BINARY) = CAST(@init_seed_logical_schedule_code AS BINARY)
       AND business_name = '逻辑归属合并'
       AND enabled = 1 AND del_flag = '0') = 1
    AND
    (SELECT COUNT(*) FROM iws_business_schedule_plan_type
     WHERE id IN (@init_seed_logical_batch_type_id, @init_seed_logical_close_type_id)
       AND schedule_id = @init_seed_logical_schedule_id
       AND enabled = 1) = 2
    AND
    (SELECT COUNT(*) FROM wms_business_type
     WHERE CAST(business_type_code AS BINARY) = CAST(@init_seed_logical_business_type AS BINARY)
       AND status = 'ACTIVE') = 1
    AND
    (SELECT COUNT(*) FROM wms_business_plan_mount
     WHERE mount_id IN (@init_seed_logical_batch_mount_id, @init_seed_logical_close_mount_id)
       AND enabled = 1) = 2
);
SET @init_final_process_rows = (
    (SELECT COUNT(*) FROM iws_business_schedule)
  + (SELECT COUNT(*) FROM iws_business_schedule_plan_type)
  + (SELECT COUNT(*) FROM iws_process_definition)
  + (SELECT COUNT(*) FROM iws_process_system_policy)
  + (SELECT COUNT(*) FROM iws_process_state)
  + (SELECT COUNT(*) FROM iws_process_event_definition)
  + (SELECT COUNT(*) FROM iws_process_edge)
  + (SELECT COUNT(*) FROM iws_process_instance)
  + (SELECT COUNT(*) FROM iws_process_system_context)
  + (SELECT COUNT(*) FROM iws_process_token)
  + (SELECT COUNT(*) FROM iws_process_event)
  + (SELECT COUNT(*) FROM iws_process_attempt)
  + (SELECT COUNT(*) FROM iws_process_trigger_receipt)
  + (SELECT COUNT(*) FROM iws_process_request_detail)
  + (SELECT COUNT(*) FROM iws_process_action_audit)
  + (SELECT COUNT(*) FROM iws_process_schedule_control)
);
SET @init_final_wms_table_count = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE CAST(TABLE_SCHEMA AS BINARY) = CAST(@init_schema AS BINARY)
      AND TABLE_NAME LIKE 'wms\\_%'
);
SET @init_final_wms_rows = (
    (SELECT COUNT(*) FROM wms_business_task)
  + (SELECT COUNT(*) FROM wms_business_task_barrier)
  + (SELECT COUNT(*) FROM wms_business_plan_mount)
  + (SELECT COUNT(*) FROM wms_business_type)
  + (SELECT COUNT(*) FROM wms_legacy_id_map)
  + (SELECT COUNT(*) FROM wms_schema_version)
  + (SELECT COUNT(*) FROM wms_upgrade_checkpoint)
  + (SELECT COUNT(*) FROM wms_upgrade_issue)
  + (SELECT COUNT(*) FROM wms_upgrade_run)
  + (SELECT COUNT(*) FROM wms_warehouse)
  + (SELECT COUNT(*) FROM wms_warehouse_zone)
  + (SELECT COUNT(*) FROM wms_storage)
  + (SELECT COUNT(*) FROM wms_container_type)
  + (SELECT COUNT(*) FROM wms_container)
  + (SELECT COUNT(*) FROM wms_compartment)
  + (SELECT COUNT(*) FROM wms_supplier)
  + (SELECT COUNT(*) FROM wms_material)
  + (SELECT COUNT(*) FROM wms_inventory)
  + (SELECT COUNT(*) FROM wms_inventory_flow)
  + (SELECT COUNT(*) FROM wms_inbound_order)
  + (SELECT COUNT(*) FROM wms_inbound_order_line)
  + (SELECT COUNT(*) FROM wms_outbound_order)
  + (SELECT COUNT(*) FROM wms_outbound_order_line)
  + (SELECT COUNT(*) FROM wms_inventory_history)
  + (SELECT COUNT(*) FROM wms_inbound_order_history)
  + (SELECT COUNT(*) FROM wms_inbound_order_line_history)
  + (SELECT COUNT(*) FROM wms_outbound_order_history)
  + (SELECT COUNT(*) FROM wms_outbound_order_line_history)
  + (SELECT COUNT(*) FROM wms_inbound_order_push_log)
  + (SELECT COUNT(*) FROM wms_outbound_order_push_log)
  + (SELECT COUNT(*) FROM wms_company)
  + (SELECT COUNT(*) FROM wms_company_management_scope)
  + (SELECT COUNT(*) FROM wms_company_management_grant)
  + (SELECT COUNT(*) FROM wms_object_audit)
  + (SELECT COUNT(*) FROM wms_command_attempt)
  + (SELECT COUNT(*) FROM wms_attribute_catalog)
  + (SELECT COUNT(*) FROM wms_rule_definition)
  + (SELECT COUNT(*) FROM wms_rule_version)
  + (SELECT COUNT(*) FROM wms_rule_package_version)
  + (SELECT COUNT(*) FROM wms_rule_package_member)
  + (SELECT COUNT(*) FROM wms_rule_effective_pointer)
  + (SELECT COUNT(*) FROM wms_rule_seed_ledger)
  + (SELECT COUNT(*) FROM wms_logical_scope)
  + (SELECT COUNT(*) FROM wms_logical_scope_version)
  + (SELECT COUNT(*) FROM wms_logical_scope_storage)
  + (SELECT COUNT(*) FROM wms_material_logical_assignment)
  + (SELECT COUNT(*) FROM wms_logical_assignment_history)
  + (SELECT COUNT(*) FROM wms_warehouse_type)
  + (SELECT COUNT(*) FROM wms_warehouse_type_config)
  + (SELECT COUNT(*) FROM wms_access_channel)
  + (SELECT COUNT(*) FROM wms_storage_access_member)
  + (SELECT COUNT(*) FROM wms_storage_access_edge)
  + (SELECT COUNT(*) FROM wms_location_version)
  + (SELECT COUNT(*) FROM wms_location_effective)
  + (SELECT COUNT(*) FROM wms_object_visibility)
  + (SELECT COUNT(*) FROM wms_object_visibility_department)
  + (SELECT COUNT(*) FROM wms_storage_capacity)
  + (SELECT COUNT(*) FROM wms_production_info)
  + (SELECT COUNT(*) FROM wms_material_attribute_value)
  + (SELECT COUNT(*) FROM wms_load_node)
  + (SELECT COUNT(*) FROM wms_load_relation)
  + (SELECT COUNT(*) FROM wms_load_relation_history)
  + (SELECT COUNT(*) FROM wms_storage_info)
  + (SELECT COUNT(*) FROM wms_occupancy_effect)
  + (SELECT COUNT(*) FROM wms_storage_occupancy)
  + (SELECT COUNT(*) FROM wms_material_trace_history)
  + (SELECT COUNT(*) FROM wms_material_trace_item)
  + (SELECT COUNT(*) FROM wms_material_trace_edge)
  + (SELECT COUNT(*) FROM wms_access_channel_lock)
  + (SELECT COUNT(*) FROM wms_allocation_candidate)
  + (SELECT COUNT(*) FROM wms_allocation_decision)
  + (SELECT COUNT(*) FROM wms_allocation_lock)
  + (SELECT COUNT(*) FROM wms_allocation_release)
  + (SELECT COUNT(*) FROM wms_inventory_reservation)
  + (SELECT COUNT(*) FROM wms_space_change_order)
  + (SELECT COUNT(*) FROM wms_logical_merge_order)
  + (SELECT COUNT(*) FROM wms_logical_merge_batch)
);
SET @init_final_rules_rows = (
    (SELECT COUNT(*) FROM wms_attribute_catalog)
  + (SELECT COUNT(*) FROM wms_rule_definition)
  + (SELECT COUNT(*) FROM wms_rule_version)
  + (SELECT COUNT(*) FROM wms_rule_package_version)
  + (SELECT COUNT(*) FROM wms_rule_package_member)
  + (SELECT COUNT(*) FROM wms_rule_effective_pointer)
  + (SELECT COUNT(*) FROM wms_rule_seed_ledger)
  + (SELECT COUNT(*) FROM wms_logical_scope)
  + (SELECT COUNT(*) FROM wms_logical_scope_version)
  + (SELECT COUNT(*) FROM wms_logical_scope_storage)
  + (SELECT COUNT(*) FROM wms_material_logical_assignment)
  + (SELECT COUNT(*) FROM wms_logical_assignment_history)
);
SET @init_final_demo_rows = (
    (SELECT COUNT(*) FROM test_demo)
  + (SELECT COUNT(*) FROM test_tree)
  + (SELECT COUNT(*) FROM sys_notice)
);
SET @init_final_ok = (
    @init_preflight_ok = 1
    AND @init_final_table_count = 183
    AND @init_final_wms_table_count = 77
    AND @init_final_menu_count = 255
    AND @init_final_menu_application_column_ok = 1
    AND @init_final_menu_application_index_ok = 1
    AND @init_final_menu_application_data_ok = 1
    AND @init_final_role_menu_scope_table_count = 2
    AND @init_final_event_menu_count = 53
    AND @init_final_role_menu_count = 133
    AND @init_final_space_root_ok = 1
    AND @init_final_space_menu_ok = 1
    AND @init_final_catalog_menu_ok = 1
    AND @init_final_p7_menu_ok = 1
    AND @init_final_user_count = 1
    AND @init_final_job_admin_count = 1
    AND @init_final_snailjob_namespace_count = 2
    AND @init_final_snailjob_group_ok = 1
    AND @init_final_dict_type_count = 13
    AND @init_final_dict_data_count = 55
    AND @init_final_client_count = 2
    AND @init_final_oss_count = 5
    AND @init_final_flow_category_count = 10
    AND @init_final_flow_spel_count = 2
    AND @init_final_ai_user_count = 1
    AND @init_final_ai_provider_count = 7
    AND @init_final_ai_app_count = 1
    AND @init_final_ai_default_app_ok = 1
    AND @init_final_ai_agent_count = 1
    AND @init_final_ai_default_agent_ok = 1
    AND @init_final_ai_openapi_user_count = 0
    AND @init_final_control_count = 1
    AND @init_final_control_revision = 0
    AND @init_final_endpoint_environment_count = 1
    AND @init_final_endpoint_default_environment_ok = 1
    AND @init_final_endpoint_default_system_ok = 1
    AND @init_final_endpoint_business_rows = 1
    AND @init_final_plan_catalog_ok = 1
    AND @init_final_process_rows = 8
    AND @init_final_wms_rows = 14
    AND @init_final_rules_rows = 6
    AND @init_final_demo_rows = 0
);

SELECT
    @init_schema AS `目标库`,
    @init_final_table_count AS `表数量`,
    @init_final_wms_table_count AS `WMS表数量`,
    @init_final_menu_count AS `系统菜单总数`,
    @init_final_menu_application_column_ok AS `菜单应用列符合`,
    @init_final_menu_application_index_ok AS `菜单应用索引符合`,
    @init_final_menu_application_data_ok AS `菜单应用范围符合`,
    @init_final_role_menu_scope_table_count AS `角色应用菜单表数`,
    @init_final_event_menu_count AS `计划事件菜单数`,
    @init_final_space_menu_ok AS `空间菜单符合`,
    @init_final_catalog_menu_ok AS `业务目录菜单符合`,
    @init_final_p7_menu_ok AS `P7菜单符合`,
    @init_final_user_count AS `默认管理员数`,
    @init_final_job_admin_count AS `调度初始管理员数`,
    @init_final_snailjob_group_ok AS `默认组符合`,
    @init_final_ai_app_count AS `默认AI应用数`,
    @init_final_ai_default_app_ok AS `默认AI应用符合`,
    @init_final_ai_default_agent_ok AS `默认AI助手符合`,
    @init_final_control_revision AS `目录控制版本`,
    IF(@init_final_endpoint_default_environment_ok = 1, 'TEST', '缺失') AS `默认环境`,
    IF(@init_final_endpoint_default_system_ok = 1, 'IWS', '缺失') AS `默认系统`,
    @init_final_plan_catalog_ok AS `计划目录种子符合`,
    @init_final_wms_rows AS `WMS业务行`,
    @init_final_rules_rows AS `规则表行`,
    @init_final_process_rows AS `计划事件运行行`,
    IF(@init_final_ok = 1, '空库全量初始化完成', '最终验收失败，请检查上述不变量') AS `执行结果`;

SELECT JSON_EXTRACT(IF(@init_final_ok = 1, '{}', 'invalid'), '$') AS `最终验收`;
