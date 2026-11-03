-- ============================================================================
-- IWS+ 在库管理公司最小闭环旧库幂等升级（INV-MVP-01）
-- ============================================================================
-- 目标数据库：MySQL 8.0+
-- 目标对象：
--   wms_company
--   wms_company_management_scope
--   wms_company_management_grant
--   wms_object_audit
--   wms_command_attempt
--   复用既有在库管理目录，新增公司管理菜单及 7 个功能权限
-- 执行性质：
--   只新增缺失的表、菜单和权限；不修改、删除旧表、旧列、旧索引或任何业务数据。
--   不创建默认公司，不授予角色，不写公司、授权、审计或尝试业务行。
-- 幂等：
--   重复执行只跳过已满足对象；已有目标表先按元数据核对，再决定是否创建缺失表。
--   表、列、索引、菜单 ID、路径、权限或父级冲突都在写入前硬中止，不以“已存在”掩盖冲突。
--   既有在库管理目录必须匹配 iws_init.sql 基线；缺失、内容冲突或出现重复根时，
--   在 DDL 和菜单写入前中止，不更新或删除用户菜单。
-- 前置：
--   已执行 iws_init.sql 与旧版 iws_wms_smt_core.sql 的旧库，20 张 WMS 基线表结构符合
--   @baseline_release_id；也接受当前核心或空库初始化后的目标态，以及中断后部分目标表
--   已按目标结构落地的合法中间态。
-- 源/目标指纹：
--   本脚本只附加公司最小闭环 5 张空表和菜单，不读取或回填 WMS 业务数据。
--   20 张 WMS 基线表分别列出 LEGACY 与 EMPTY 双 manifest；表/列字符集、排序规则、
--   表/列注释、列顺序/类型/空值/默认值/额外属性及完整索引顺序均纳入签名。
--   每个元数据行先由 JSON_ARRAY 编码，保留 SQL NULL、引号、反斜杠和分隔符字符，
--   再组成 JSON 数组并散列；不使用 IFNULL sentinel、CONCAT_WS 或 GROUP_CONCAT SEPARATOR 拼接。
--   来源必须完整匹配其中一个 manifest，不按表混用，不以“排序规则/注释可忽略”放宽。
--   目标表按目标列、索引、默认值、字符集和注释逐项校验；已有目标表冲突在 CREATE 前中止。
--   DDL 仅对元数据判定为缺失且前置门禁通过的表执行；会话 checkpoint 记录 EXISTS/CREATED，
--   每张表确认成功后才推进下一张；中断重入时重新读取元数据，不假设多语句脚本可整体回滚。
-- 批大小、写冻结 token、站点模板：
--   不适用。本脚本无数据回填、无旧表改列、无公司/仓库/楼层/站点映射创建；
--   真实上线仍须按部署流程冻结旧写入口并另行授权。
-- 执行：
--   mysql --protocol=TCP --host=<主机> --port=<端口> --user=<账号> \
--     --default-character-set=utf8mb4 --database=<目标库> --batch --raw \
--     --execute="source script/sql/iws_inventory_management_upgrade.sql"
--   不得添加 --force；首个错误必须停止并保留现场。
--   必须串行执行并保持同一连接/会话临时表；首个 SQL 错误必须停止并关闭连接，禁止 --force。
--   本脚本不承诺并发执行，也不承诺数据库错误后的续跑或自动补偿。
--   菜单后置失败时脚本先显式 ROLLBACK 再触发错误，不依赖客户端停在事务中。
-- 权限：
--   部署账号需要 SELECT（information_schema）、CREATE、CREATE TEMPORARY TABLES、INSERT 权限。
--   本脚本不创建存储过程或触发器。
-- 参数：
--   目标库、发布标识、WMS 基线版本、基线/目标表数和菜单 ID 全部集中在下方，执行前只改一处。
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 集中参数
-- ----------------------------------------------------------------------------
SET @target_schema = 'innolux-iws';
SET @release_id = 'INV-MVP-01-20261027';
SET @baseline_release_id = 'WMS-CORE-20-20261027';
SET @expected_baseline_tables = 20;
SET @expected_target_tables = 5;

SET @inventory_parent_menu_id = 1761400000000020004;
SET @inventory_company_menu_id = 1761400000000050100;
SET @inventory_company_create_id = 1761400000000051001;
SET @inventory_company_update_id = 1761400000000051002;
SET @inventory_company_enable_id = 1761400000000051003;
SET @inventory_company_disable_id = 1761400000000051004;
SET @inventory_company_scope_all_id = 1761400000000051005;
SET @inventory_company_scope_manage_id = 1761400000000051006;

-- ----------------------------------------------------------------------------
-- 一、会话参数
-- ----------------------------------------------------------------------------
SET @saved_group_concat_max_len = @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 1048576;

-- ----------------------------------------------------------------------------
-- 二、目标结构期望（仅会话临时表，不落库）
-- ----------------------------------------------------------------------------
-- 20 张 WMS 基线表的双 manifest（LEGACY / EMPTY）。
-- LEGACY = iws_init.sql + S1 前 iws_wms_smt_core.sql 的 20 表来源；
-- EMPTY = 当前 iws_wms_smt_core.sql / iws_init_from_empty.sql 的 20 表来源，
-- 其中库区与储位已包含空间 S1 的可空父链和五个可空文本地址列。两条来源分别列 manifest，
-- 不按表混用，也不以“排序规则/注释可忽略”放宽。
-- 列签名覆盖 ordinal_position、列名、类型、空值、默认值、字符集、排序规则、
-- 额外属性、生成表达式和列注释；索引签名覆盖名称、唯一性、顺序、列、前缀、
-- 类型、排序方向、表达式和可见性。表签名覆盖 InnoDB、表排序规则和表注释。
-- 常量在 MySQL 8.0.45 隔离库由两条来源分别生成；S1 使库区与储位的列签名在两个
-- variant 间不同，其余 18 张表相同。来源定义或目标 MySQL 元数据表示变化时
-- 必须分别重生成并评审，不能因同名表而互换。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_baseline;
CREATE TEMPORARY TABLE tmp_iws_mvp_baseline (
    source_variant  varchar(16)  NOT NULL,
    table_name      varchar(64)  NOT NULL,
    engine          varchar(32)  NOT NULL,
    table_collation varchar(64)  NOT NULL,
    table_comment   varchar(255) NOT NULL,
    column_count    int          NOT NULL,
    column_hash     char(64)     NOT NULL,
    index_count     int          NOT NULL,
    index_hash      char(64)     NOT NULL,
    PRIMARY KEY (source_variant, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_mvp_baseline
    (source_variant, table_name, engine, table_collation, table_comment,
     column_count, column_hash, index_count, index_hash)
VALUES
    ('LEGACY', 'wms_compartment', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS容器格口表', 10, '474a3634c9d45a387b18664e9d62e10c19f44520294de2ebbbf9d95e3d821ab8', 4, '37853190ce11ed74162dea9a1d9cbd01855b69d5ff3c862cc5f872b61ab0575e'),
    ('EMPTY', 'wms_compartment', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS容器格口表', 10, '474a3634c9d45a387b18664e9d62e10c19f44520294de2ebbbf9d95e3d821ab8', 4, '37853190ce11ed74162dea9a1d9cbd01855b69d5ff3c862cc5f872b61ab0575e'),
    ('LEGACY', 'wms_container', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS容器表', 21, '3dec705c6d5baec32e0083a1e6819e351ed45892716db49e1b0c75acf5f96929', 8, '769d6d8ebe6f1fc048a053637db5d852f4ab598767aef269d12d5cf1c5f7e60b'),
    ('EMPTY', 'wms_container', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS容器表', 21, '3dec705c6d5baec32e0083a1e6819e351ed45892716db49e1b0c75acf5f96929', 8, '769d6d8ebe6f1fc048a053637db5d852f4ab598767aef269d12d5cf1c5f7e60b'),
    ('LEGACY', 'wms_container_type', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS容器类型表', 20, '3d887d53c62b35aa753b9949766bd275ceb42cd2f937f40c9d28b9e0e8516653', 4, 'eb504d7783ac8bc80d7ab3aee539af993a0c15804c648da8c1e7d306b606458f'),
    ('EMPTY', 'wms_container_type', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS容器类型表', 20, '3d887d53c62b35aa753b9949766bd275ceb42cd2f937f40c9d28b9e0e8516653', 4, 'eb504d7783ac8bc80d7ab3aee539af993a0c15804c648da8c1e7d306b606458f'),
    ('LEGACY', 'wms_inbound_order', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单头表', 16, '0d2e45d0181e1249d3706fa6ca5cb304d4979d9cb53dbb52207409016b3b1ece', 5, 'dd5b9916400ffccac4f05fd54a1ecbe4dca802ee0ce042a5afa3a7c84bcc613e'),
    ('EMPTY', 'wms_inbound_order', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单头表', 16, '0d2e45d0181e1249d3706fa6ca5cb304d4979d9cb53dbb52207409016b3b1ece', 5, 'dd5b9916400ffccac4f05fd54a1ecbe4dca802ee0ce042a5afa3a7c84bcc613e'),
    ('LEGACY', 'wms_inbound_order_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单头历史表', 16, 'd5369f3408c39a5cd797611f1602659310ec94fc1bdb33b828561dfe693b4e47', 6, '9f64523721c061c31c98d650859b2efa9817c8e8ef78751354efae1099729ac6'),
    ('EMPTY', 'wms_inbound_order_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单头历史表', 16, 'd5369f3408c39a5cd797611f1602659310ec94fc1bdb33b828561dfe693b4e47', 6, '9f64523721c061c31c98d650859b2efa9817c8e8ef78751354efae1099729ac6'),
    ('LEGACY', 'wms_inbound_order_line', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单明细表', 23, 'dfac3ff34ff1b99f6b1817275ffb1133efea8d2a778c26de4147b84e37bf8f55', 6, '33ecea4e25d99ef02188742fbc716649c16d6cf066a417f0336f5106df743d9b'),
    ('EMPTY', 'wms_inbound_order_line', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单明细表', 23, 'dfac3ff34ff1b99f6b1817275ffb1133efea8d2a778c26de4147b84e37bf8f55', 6, '33ecea4e25d99ef02188742fbc716649c16d6cf066a417f0336f5106df743d9b'),
    ('LEGACY', 'wms_inbound_order_line_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单明细历史表', 26, 'bc60c21aff1d457657cc6d36902fa6efc34f71c06df4dd82d12892a00c3705f4', 7, 'c1274e9c9d13b10f66ffa7622d7830888730c4c24df8867a13403be1f6486820'),
    ('EMPTY', 'wms_inbound_order_line_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单明细历史表', 26, 'bc60c21aff1d457657cc6d36902fa6efc34f71c06df4dd82d12892a00c3705f4', 7, 'c1274e9c9d13b10f66ffa7622d7830888730c4c24df8867a13403be1f6486820'),
    ('LEGACY', 'wms_inbound_order_push_log', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单推送记录表', 30, '3264a7da35a2f1e5c9739425b30759b5d02903ffd7aeca4369a291de83af2b96', 8, 'e9b252b08488538878a60c88ebc89fd9c159ac9f20744929716be4936c3deec7'),
    ('EMPTY', 'wms_inbound_order_push_log', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS入库单推送记录表', 30, '3264a7da35a2f1e5c9739425b30759b5d02903ffd7aeca4369a291de83af2b96', 8, 'e9b252b08488538878a60c88ebc89fd9c159ac9f20744929716be4936c3deec7'),
    ('LEGACY', 'wms_inventory', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS库存表', 43, '5560ccf22ea677ef66bf7b8c1b9bbd4eeda45d5b006fd569ae7893974cf0eed1', 10, '2aa5af70f3c33263eaade3e8ec145fc3b13cc7028afe318e0a83b548080eb9b3'),
    ('EMPTY', 'wms_inventory', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS库存表', 43, '5560ccf22ea677ef66bf7b8c1b9bbd4eeda45d5b006fd569ae7893974cf0eed1', 10, '2aa5af70f3c33263eaade3e8ec145fc3b13cc7028afe318e0a83b548080eb9b3'),
    ('LEGACY', 'wms_inventory_flow', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS库存流水表', 22, '2f010e9b3de94f33f092e9edd3735925968db2ac86ce43ca8b0b390f6f952592', 7, '97f24d90a5728c6027f2f62b129da545b5da500b923f5ce8afe40edd9535b33e'),
    ('EMPTY', 'wms_inventory_flow', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS库存流水表', 22, '2f010e9b3de94f33f092e9edd3735925968db2ac86ce43ca8b0b390f6f952592', 7, '97f24d90a5728c6027f2f62b129da545b5da500b923f5ce8afe40edd9535b33e'),
    ('LEGACY', 'wms_inventory_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS库存历史表', 42, 'ed6af72cbd37132965d9f425bb3f1a40385ac30d0e63e41e0b7444f6ad9a2301', 10, '05f2d192e3cc314e1782c232b96df622c1acb088475262c22decc9d11511410b'),
    ('EMPTY', 'wms_inventory_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS库存历史表', 42, 'ed6af72cbd37132965d9f425bb3f1a40385ac30d0e63e41e0b7444f6ad9a2301', 10, '05f2d192e3cc314e1782c232b96df622c1acb088475262c22decc9d11511410b'),
    ('LEGACY', 'wms_material', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS物料表', 29, '2146ce844764c3ca0c4a2cb613bcbc953da19ddb64080593575dbd3720d9bc51', 6, '408a45b830369beb21bbd03add97894bde042e2ee3311f69dfdacdd016969cc0'),
    ('EMPTY', 'wms_material', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS物料表', 29, '2146ce844764c3ca0c4a2cb613bcbc953da19ddb64080593575dbd3720d9bc51', 6, '408a45b830369beb21bbd03add97894bde042e2ee3311f69dfdacdd016969cc0'),
    ('LEGACY', 'wms_outbound_order', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单头表', 20, 'e390467e383ce80da7a859e30ecc1f739cec23f806923e7ead026ad8e5067dae', 6, '647850d275b4930e9b55f7b9f9431f383e23316da536030a6e2260127566bb57'),
    ('EMPTY', 'wms_outbound_order', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单头表', 20, 'e390467e383ce80da7a859e30ecc1f739cec23f806923e7ead026ad8e5067dae', 6, '647850d275b4930e9b55f7b9f9431f383e23316da536030a6e2260127566bb57'),
    ('LEGACY', 'wms_outbound_order_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单头历史表', 23, 'a74d88b8ec84d9fffcb21584bced2d371b96228df19558ce5206a606ed78e4f7', 7, 'cdfe84da861845bf07a21f6388e869afd5daaa2398e0115e8eaf63bfc3675255'),
    ('EMPTY', 'wms_outbound_order_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单头历史表', 23, 'a74d88b8ec84d9fffcb21584bced2d371b96228df19558ce5206a606ed78e4f7', 7, 'cdfe84da861845bf07a21f6388e869afd5daaa2398e0115e8eaf63bfc3675255'),
    ('LEGACY', 'wms_outbound_order_line', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单明细表', 25, 'a9dc5dd9cc6fc371af11501c7c44397af5e2160fae6b63d6605d27f9526396b2', 7, '63cfc5377e3d7c33ff0f85edb0e01996bb1e9a3d66f8cf26f7ba9d948ef155ce'),
    ('EMPTY', 'wms_outbound_order_line', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单明细表', 25, 'a9dc5dd9cc6fc371af11501c7c44397af5e2160fae6b63d6605d27f9526396b2', 7, '63cfc5377e3d7c33ff0f85edb0e01996bb1e9a3d66f8cf26f7ba9d948ef155ce'),
    ('LEGACY', 'wms_outbound_order_line_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单明细历史表', 31, '5981e2f4322dea349e10428d8671952b84be9c89294c4e8205f358046c951c97', 9, 'c13dc6fc34c831c48df37f4c4ce2981a20196d2467f330deddad138a6a3674fd'),
    ('EMPTY', 'wms_outbound_order_line_history', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单明细历史表', 31, '5981e2f4322dea349e10428d8671952b84be9c89294c4e8205f358046c951c97', 9, 'c13dc6fc34c831c48df37f4c4ce2981a20196d2467f330deddad138a6a3674fd'),
    ('LEGACY', 'wms_outbound_order_push_log', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单推送记录表', 32, 'dfae44d5b886b647435dc5d321ff81e333606dae0cbc96c6078fa1d2227d6abe', 9, 'e5c3331e62286359baf829dbf6bb1064e7eb5d1adbcfa2d3e9da02c4cc45b168'),
    ('EMPTY', 'wms_outbound_order_push_log', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS出库单推送记录表', 32, 'dfae44d5b886b647435dc5d321ff81e333606dae0cbc96c6078fa1d2227d6abe', 9, 'e5c3331e62286359baf829dbf6bb1064e7eb5d1adbcfa2d3e9da02c4cc45b168'),
    ('LEGACY', 'wms_storage', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS储位表', 24, '4a86df18718fd0c4abc5cc76d6e92d7a290b652ca15e354bc9d97cfa9079e107', 6, '6b934a1c79b03dab2a5dc7210737dc68ec874bb07843ebb8e96bcc38d16fd9c3'),
    ('EMPTY', 'wms_storage', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS储位表', 30, 'f56277396b69d2ae064ca00ecd25bf5c474cac6a93792c757a9711e315ed4203', 7, '4dd37da0259bdf102cb9c3d3162db8ea06946c4934059b19eccfd0c772eeffda'),
    ('LEGACY', 'wms_supplier', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS供应商表', 16, '1954faedfdfeae97ad644f8751c46e0f638b7ab3067958772a709741a2638ff0', 5, 'faa06ea5ca2b670eca45f0b1c9ce0a393999f195adaf83eb08c7fa0dfe25871c'),
    ('EMPTY', 'wms_supplier', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS供应商表', 16, '1954faedfdfeae97ad644f8751c46e0f638b7ab3067958772a709741a2638ff0', 5, 'faa06ea5ca2b670eca45f0b1c9ce0a393999f195adaf83eb08c7fa0dfe25871c'),
    ('LEGACY', 'wms_warehouse_zone', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS库区表', 18, '1c5e59e1e8995c94a860b43ab0f357739df434aa56f7884fdec9f4b5a580edcd', 5, '6fed6a8f4fe084ce1e2ad5ed26e3b7e3fe727cce9ccfcd73d3d54def506f4eac'),
    ('EMPTY', 'wms_warehouse_zone', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS库区表', 19, '4b0519abe2deff7a6ec448a4060d96b29c2aa1da9712375a54963df9d931041a', 6, '4d1fdb85bb7769c535e8405d23296e3a9694ae293bfd2092d81f2a0c3a6e5534');

-- 当前库实际元数据按同一 canonical signature 物化；后续每个 manifest 比较
-- 只引用一次临时表，避免 MySQL 临时表同一查询重复打开。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_actual_tbl;
CREATE TEMPORARY TABLE tmp_iws_mvp_actual_tbl AS
SELECT TABLE_NAME, ENGINE, TABLE_COLLATION, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_TYPE = 'BASE TABLE'
  AND TABLE_NAME IN (
      'wms_warehouse_zone', 'wms_storage', 'wms_container_type',
      'wms_container', 'wms_compartment', 'wms_supplier', 'wms_material',
      'wms_inventory', 'wms_inventory_flow', 'wms_inbound_order',
      'wms_inbound_order_line', 'wms_outbound_order', 'wms_outbound_order_line',
      'wms_inventory_history', 'wms_inbound_order_history',
      'wms_inbound_order_line_history', 'wms_outbound_order_history',
      'wms_outbound_order_line_history', 'wms_inbound_order_push_log',
      'wms_outbound_order_push_log'
  );

DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_actual_col;
CREATE TEMPORARY TABLE tmp_iws_mvp_actual_col AS
SELECT TABLE_NAME, COUNT(*) AS column_count,
       SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   ORDINAL_POSITION, COLUMN_NAME, LOWER(COLUMN_TYPE),
                   IS_NULLABLE, COLUMN_DEFAULT, CHARACTER_SET_NAME,
                   COLLATION_NAME, EXTRA, GENERATION_EXPRESSION, COLUMN_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY ORDINAL_POSITION SEPARATOR ','
           ),
       ']'), 256) AS column_hash
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
      'wms_warehouse_zone', 'wms_storage', 'wms_container_type',
      'wms_container', 'wms_compartment', 'wms_supplier', 'wms_material',
      'wms_inventory', 'wms_inventory_flow', 'wms_inbound_order',
      'wms_inbound_order_line', 'wms_outbound_order', 'wms_outbound_order_line',
      'wms_inventory_history', 'wms_inbound_order_history',
      'wms_inbound_order_line_history', 'wms_outbound_order_history',
      'wms_outbound_order_line_history', 'wms_inbound_order_push_log',
      'wms_outbound_order_push_log'
  )
GROUP BY TABLE_NAME;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_actual_idx;
CREATE TEMPORARY TABLE tmp_iws_mvp_actual_idx AS
SELECT TABLE_NAME, COUNT(DISTINCT INDEX_NAME) AS index_count,
       SHA2(CONCAT('[',
           GROUP_CONCAT(
               CAST(JSON_ARRAY(
                   INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                   SUB_PART, INDEX_TYPE, COLLATION, EXPRESSION,
                   IS_VISIBLE, INDEX_COMMENT
               ) AS CHAR CHARACTER SET utf8mb4)
               ORDER BY INDEX_NAME, SEQ_IN_INDEX SEPARATOR ','
           ),
       ']'), 256) AS index_hash
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
      'wms_warehouse_zone', 'wms_storage', 'wms_container_type',
      'wms_container', 'wms_compartment', 'wms_supplier', 'wms_material',
      'wms_inventory', 'wms_inventory_flow', 'wms_inbound_order',
      'wms_inbound_order_line', 'wms_outbound_order', 'wms_outbound_order_line',
      'wms_inventory_history', 'wms_inbound_order_history',
      'wms_inbound_order_line_history', 'wms_outbound_order_history',
      'wms_outbound_order_line_history', 'wms_inbound_order_push_log',
      'wms_outbound_order_push_log'
  )
GROUP BY TABLE_NAME;

DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_col;
CREATE TEMPORARY TABLE tmp_iws_mvp_col (
    table_name       varchar(64)  NOT NULL,
    ordinal_position int          NOT NULL,
    column_name      varchar(64)  NOT NULL,
    column_type      varchar(64)  NOT NULL,
    is_nullable      char(3)      NOT NULL,
    column_default   varchar(128) DEFAULT NULL,
    collation_name   varchar(64)  DEFAULT NULL,
    column_comment   varchar(255) NOT NULL,
    PRIMARY KEY (table_name, ordinal_position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_mvp_col
    (table_name, ordinal_position, column_name, column_type, is_nullable, column_default, collation_name, column_comment)
VALUES
    ('wms_company', 1, 'company_id', 'bigint', 'NO', NULL, NULL, '公司主键，系统生成'),
    ('wms_company', 2, 'company_code', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin', '公司编码，业务唯一，创建后不可修改'),
    ('wms_company', 3, 'company_name', 'varchar(100)', 'NO', NULL, 'utf8mb4_0900_ai_ci', '公司名称'),
    ('wms_company', 4, 'enabled', 'tinyint', 'NO', '0', NULL, '是否可被后续业务选择：0否，1是；与生命周期分离'),
    ('wms_company', 5, 'lifecycle_status', 'varchar(16)', 'NO', 'ACTIVE', 'utf8mb4_bin', '生命周期：ACTIVE/RETIRING/RETIRED'),
    ('wms_company', 6, 'revision', 'int', 'NO', '1', NULL, '公司乐观锁版本，创建为1'),
    ('wms_company', 7, 'remark', 'varchar(500)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '备注'),
    ('wms_company', 8, 'create_dept', 'bigint', 'YES', NULL, NULL, '创建部门'),
    ('wms_company', 9, 'create_by', 'bigint', 'YES', NULL, NULL, '创建者'),
    ('wms_company', 10, 'create_time', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)', NULL, '创建时间'),
    ('wms_company', 11, 'update_by', 'bigint', 'YES', NULL, NULL, '更新者'),
    ('wms_company', 12, 'update_time', 'datetime(3)', 'YES', NULL, NULL, '更新时间'),

    ('wms_company_management_scope', 1, 'company_id', 'bigint', 'NO', NULL, NULL, '公司主键，逻辑关联 wms_company.company_id；每公司恰一行'),
    ('wms_company_management_scope', 2, 'scope_revision', 'int', 'NO', '0', NULL, '管理范围独立乐观锁版本，创建为0'),
    ('wms_company_management_scope', 3, 'updated_by', 'bigint', 'YES', NULL, NULL, '最后更新人'),
    ('wms_company_management_scope', 4, 'updated_at', 'datetime(3)', 'YES', NULL, NULL, '最后更新时间'),

    ('wms_company_management_grant', 1, 'grant_id', 'bigint', 'NO', NULL, NULL, '授权明细主键，系统生成'),
    ('wms_company_management_grant', 2, 'company_id', 'bigint', 'NO', NULL, NULL, '公司主键，逻辑关联 wms_company.company_id'),
    ('wms_company_management_grant', 3, 'role_id', 'bigint', 'NO', NULL, NULL, '角色主键，逻辑关联 sys_role.role_id'),
    ('wms_company_management_grant', 4, 'created_by', 'bigint', 'YES', NULL, NULL, '创建者'),
    ('wms_company_management_grant', 5, 'created_at', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)', NULL, '创建时间'),

    ('wms_object_audit', 1, 'audit_id', 'bigint', 'NO', NULL, NULL, '审计主键，系统生成'),
    ('wms_object_audit', 2, 'object_type', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin', '对象类型，如 WMS_COMPANY'),
    ('wms_object_audit', 3, 'object_id', 'bigint', 'NO', NULL, NULL, '对象内部ID'),
    ('wms_object_audit', 4, 'action', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin', '动作：CREATE/UPDATE/ENABLE/DISABLE/MANAGEMENT_SCOPE_REPLACE'),
    ('wms_object_audit', 5, 'command_key', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin', '命令键，用于幂等与追责'),
    ('wms_object_audit', 6, 'before_summary', 'varchar(2000)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '变更前摘要（脱敏快照，超长截断）'),
    ('wms_object_audit', 7, 'after_summary', 'varchar(2000)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '变更后摘要（脱敏快照，超长截断）'),
    ('wms_object_audit', 8, 'actor_user_id', 'bigint', 'YES', NULL, NULL, '当前操作者'),
    ('wms_object_audit', 9, 'actor_name', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '操作者名称快照'),
    ('wms_object_audit', 10, 'initiator_type', 'varchar(32)', 'YES', NULL, 'utf8mb4_bin', '发起人类型：USER/PLAN/SYSTEM'),
    ('wms_object_audit', 11, 'initiator_id', 'varchar(128)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '发起人标识'),
    ('wms_object_audit', 12, 'initiator_name', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '发起人名称快照'),
    ('wms_object_audit', 13, 'dept_id', 'bigint', 'YES', NULL, NULL, '操作者部门快照'),
    ('wms_object_audit', 14, 'dept_name', 'varchar(128)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '操作者部门名称快照'),
    ('wms_object_audit', 15, 'execution_subject', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '执行主体名称快照'),
    ('wms_object_audit', 16, 'execution_subject_type', 'varchar(32)', 'YES', NULL, 'utf8mb4_bin', '执行主体类型：USER/PLAN/SYSTEM'),
    ('wms_object_audit', 17, 'execution_account', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '执行账号快照'),
    ('wms_object_audit', 18, 'result', 'varchar(16)', 'NO', NULL, 'utf8mb4_bin', '结果：SUCCESS/REJECTED/FAILED/UNKNOWN'),
    ('wms_object_audit', 19, 'evidence_json', 'varchar(2000)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '证据引用或幂等摘要'),
    ('wms_object_audit', 20, 'occurred_at', 'datetime(3)', 'NO', NULL, NULL, '业务发生时间'),
    ('wms_object_audit', 21, 'recorded_at', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)', NULL, '审计记录时间'),

    ('wms_command_attempt', 1, 'attempt_id', 'bigint', 'NO', NULL, NULL, '尝试主键，系统生成'),
    ('wms_command_attempt', 2, 'command_key', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin', '命令键'),
    ('wms_command_attempt', 3, 'action', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin', '命令动作，如 COMPANY_CREATE/COMPANY_UPDATE/COMPANY_ENABLE/COMPANY_DISABLE/COMPANY_SCOPE_REPLACE'),
    ('wms_command_attempt', 4, 'object_type', 'varchar(64)', 'NO', NULL, 'utf8mb4_bin', '对象类型，如 WMS_COMPANY'),
    ('wms_command_attempt', 5, 'object_id', 'bigint', 'YES', NULL, NULL, '对象内部ID，创建前可空'),
    ('wms_command_attempt', 6, 'input_digest', 'char(64)', 'NO', NULL, 'utf8mb4_0900_ai_ci', '规范化请求摘要，SHA-256小写'),
    ('wms_command_attempt', 7, 'input_summary', 'varchar(2000)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '脱敏请求摘要'),
    ('wms_command_attempt', 8, 'status', 'varchar(16)', 'NO', NULL, 'utf8mb4_bin', '状态：ACCEPTED/PROCESSING/APPLIED/REJECTED/FAILED/UNKNOWN'),
    ('wms_command_attempt', 9, 'error_code', 'varchar(64)', 'YES', NULL, 'utf8mb4_bin', '错误码，如 VERSION_CONFLICT/SCOPE_VERSION_CONFLICT/FEATURE_NOT_OPEN'),
    ('wms_command_attempt', 10, 'error_message', 'varchar(500)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '脱敏错误消息'),
    ('wms_command_attempt', 11, 'fact_type', 'varchar(64)', 'YES', NULL, 'utf8mb4_bin', '成功事实类型，如 WMS_OBJECT_AUDIT'),
    ('wms_command_attempt', 12, 'fact_id', 'varchar(128)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '成功事实标识'),
    ('wms_command_attempt', 13, 'actor_user_id', 'bigint', 'YES', NULL, NULL, '操作者'),
    ('wms_command_attempt', 14, 'actor_name', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '操作者名称快照'),
    ('wms_command_attempt', 15, 'dept_id', 'bigint', 'YES', NULL, NULL, '操作者部门快照'),
    ('wms_command_attempt', 16, 'dept_name', 'varchar(128)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '操作者部门名称快照'),
    ('wms_command_attempt', 17, 'initiator_name', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '发起人名称快照'),
    ('wms_command_attempt', 18, 'execution_subject', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '执行主体名称快照'),
    ('wms_command_attempt', 19, 'execution_subject_type', 'varchar(32)', 'YES', NULL, 'utf8mb4_bin', '执行主体类型：USER/PLAN/SYSTEM'),
    ('wms_command_attempt', 20, 'execution_account', 'varchar(64)', 'YES', NULL, 'utf8mb4_0900_ai_ci', '执行账号快照'),
    ('wms_command_attempt', 21, 'occurred_at', 'datetime(3)', 'NO', NULL, NULL, '命令发生时间'),
    ('wms_command_attempt', 22, 'recorded_at', 'datetime(3)', 'NO', 'CURRENT_TIMESTAMP(3)', NULL, '记录时间');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_idx;
CREATE TEMPORARY TABLE tmp_iws_mvp_idx (
    table_name  varchar(64) NOT NULL,
    index_name  varchar(64) NOT NULL,
    non_unique  tinyint     NOT NULL,
    seq_in_index int        NOT NULL,
    column_name varchar(64) NOT NULL,
    PRIMARY KEY (table_name, index_name, seq_in_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_mvp_idx (table_name, index_name, non_unique, seq_in_index, column_name)
VALUES
    ('wms_company', 'PRIMARY', 0, 1, 'company_id'),
    ('wms_company', 'uk_wms_company_code', 0, 1, 'company_code'),
    ('wms_company', 'idx_wms_company_lifecycle', 1, 1, 'lifecycle_status'),
    ('wms_company', 'idx_wms_company_lifecycle', 1, 2, 'enabled'),

    ('wms_company_management_scope', 'PRIMARY', 0, 1, 'company_id'),

    ('wms_company_management_grant', 'PRIMARY', 0, 1, 'grant_id'),
    ('wms_company_management_grant', 'uk_wms_company_grant', 0, 1, 'company_id'),
    ('wms_company_management_grant', 'uk_wms_company_grant', 0, 2, 'role_id'),
    ('wms_company_management_grant', 'idx_wms_company_grant_role', 1, 1, 'role_id'),
    ('wms_company_management_grant', 'idx_wms_company_grant_role', 1, 2, 'company_id'),

    ('wms_object_audit', 'PRIMARY', 0, 1, 'audit_id'),
    ('wms_object_audit', 'idx_wms_object_audit_object', 1, 1, 'object_type'),
    ('wms_object_audit', 'idx_wms_object_audit_object', 1, 2, 'object_id'),
    ('wms_object_audit', 'idx_wms_object_audit_object', 1, 3, 'occurred_at'),
    ('wms_object_audit', 'idx_wms_object_audit_command_key', 1, 1, 'command_key'),

    ('wms_command_attempt', 'PRIMARY', 0, 1, 'attempt_id'),
    ('wms_command_attempt', 'uk_wms_command_attempt_command', 0, 1, 'command_key'),
    ('wms_command_attempt', 'idx_wms_command_attempt_object', 1, 1, 'object_type'),
    ('wms_command_attempt', 'idx_wms_command_attempt_object', 1, 2, 'object_id'),
    ('wms_command_attempt', 'idx_wms_command_attempt_object', 1, 3, 'occurred_at'),
    ('wms_command_attempt', 'idx_wms_command_attempt_status', 1, 1, 'status'),
    ('wms_command_attempt', 'idx_wms_command_attempt_status', 1, 2, 'occurred_at');

DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_tbl;
CREATE TEMPORARY TABLE tmp_iws_mvp_tbl (
    table_name      varchar(64)  NOT NULL,
    engine          varchar(32)  NOT NULL,
    table_collation varchar(64)  NOT NULL,
    table_comment   varchar(255) NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_mvp_tbl (table_name, engine, table_collation, table_comment)
VALUES
    ('wms_company', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS公司主档'),
    ('wms_company_management_scope', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS公司管理范围头'),
    ('wms_company_management_grant', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS公司管理角色授权明细'),
    ('wms_object_audit', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS对象变更审计，只追加'),
    ('wms_command_attempt', 'InnoDB', 'utf8mb4_0900_ai_ci', 'WMS非计划受控命令尝试');

-- 会话级 DDL checkpoint：仅记录本次连接对每张目标表的元数据判定与执行结果。
-- CREATE 逐表由前置门禁控制；中断后重入重新读取元数据，已按目标结构存在的表标 EXISTS。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_ddl;
CREATE TEMPORARY TABLE tmp_iws_mvp_ddl (
    table_name varchar(64) NOT NULL,
    action     varchar(16) NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_mvp_ddl (table_name, action)
SELECT e.table_name,
       CASE
           WHEN t.TABLE_NAME IS NULL THEN 'CREATE'
           WHEN t.TABLE_TYPE = 'BASE TABLE' THEN 'EXISTS'
           ELSE 'CONFLICT'
       END
FROM tmp_iws_mvp_tbl e
LEFT JOIN information_schema.TABLES t
    ON t.TABLE_SCHEMA = DATABASE()
   AND t.TABLE_NAME = e.table_name;

-- ----------------------------------------------------------------------------
-- 三、升级前置校验（任何 DDL 或菜单写入之前）
-- ----------------------------------------------------------------------------
SET @schema_ok = (
    DATABASE() IS NOT NULL
    AND CAST(DATABASE() AS BINARY) = CAST(@target_schema AS BINARY)
);
SET @version_ok = (VERSION() LIKE '8.%');

SET @baseline_table_count = (
    SELECT COUNT(*) FROM tmp_iws_mvp_actual_tbl
);
SET @baseline_legacy_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_baseline b
    LEFT JOIN tmp_iws_mvp_actual_tbl t ON t.TABLE_NAME = b.table_name
    LEFT JOIN tmp_iws_mvp_actual_col c ON c.TABLE_NAME = b.table_name
    LEFT JOIN tmp_iws_mvp_actual_idx i ON i.TABLE_NAME = b.table_name
    WHERE b.source_variant = 'LEGACY'
      AND (t.TABLE_NAME IS NULL
           OR t.ENGINE <> b.engine
           OR t.TABLE_COLLATION <> b.table_collation
           OR CAST(t.TABLE_COMMENT AS BINARY) <> CAST(b.table_comment AS BINARY)
           OR c.TABLE_NAME IS NULL
           OR c.column_count <> b.column_count
           OR c.column_hash <> b.column_hash
           OR i.TABLE_NAME IS NULL
           OR i.index_count <> b.index_count
           OR i.index_hash <> b.index_hash)
);
SET @baseline_empty_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_baseline b
    LEFT JOIN tmp_iws_mvp_actual_tbl t ON t.TABLE_NAME = b.table_name
    LEFT JOIN tmp_iws_mvp_actual_col c ON c.TABLE_NAME = b.table_name
    LEFT JOIN tmp_iws_mvp_actual_idx i ON i.TABLE_NAME = b.table_name
    WHERE b.source_variant = 'EMPTY'
      AND (t.TABLE_NAME IS NULL
           OR t.ENGINE <> b.engine
           OR t.TABLE_COLLATION <> b.table_collation
           OR CAST(t.TABLE_COMMENT AS BINARY) <> CAST(b.table_comment AS BINARY)
           OR c.TABLE_NAME IS NULL
           OR c.column_count <> b.column_count
           OR c.column_hash <> b.column_hash
           OR i.TABLE_NAME IS NULL
           OR i.index_count <> b.index_count
           OR i.index_hash <> b.index_hash)
);
SET @baseline_ok = (
    @schema_ok = 1
    AND @version_ok = 1
    AND @baseline_table_count = @expected_baseline_tables
    AND (@baseline_legacy_mismatch = 0 OR @baseline_empty_mismatch = 0)
);
SET @baseline_source = CASE
    WHEN @baseline_legacy_mismatch = 0 AND @baseline_empty_mismatch = 0 THEN 'LEGACY+EMPTY'
    WHEN @baseline_legacy_mismatch = 0 THEN 'LEGACY'
    WHEN @baseline_empty_mismatch = 0 THEN 'EMPTY'
    ELSE 'NONE'
END;


SET @sys_menu_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_menu'
      AND TABLE_TYPE = 'BASE TABLE'
);
SET @sys_menu_columns_ok = (
    SELECT COUNT(*) = 22
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_menu'
      AND COLUMN_NAME IN (
          'menu_id', 'menu_name', 'parent_id', 'order_num', 'path', 'component',
          'query_param', 'is_frame', 'is_cache', 'menu_type', 'visible', 'status',
          'perms', 'icon', 'active_menu', 'ext', 'create_dept', 'create_by',
          'create_time', 'update_by', 'update_time', 'remark'
      )
);
SET @sys_menu_engine_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_menu'
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE = 'InnoDB'
);
-- 完整 PRIMARY 必须恰为单列 menu_id；复合主键即使首列是 menu_id 也必须拒绝。
SET @sys_menu_pk_ok = (
    SELECT COUNT(*) = 1
       AND MIN(SEQ_IN_INDEX) = 1
       AND MAX(SEQ_IN_INDEX) = 1
       AND MAX(COLUMN_NAME) = 'menu_id'
       AND MAX(NON_UNIQUE) = 0
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_menu'
      AND INDEX_NAME = 'PRIMARY'
);
SET @sys_menu_types_ok = (
    SELECT COUNT(*) = 22
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_menu'
      AND (
          (COLUMN_NAME = 'menu_id' AND LOWER(COLUMN_TYPE) = 'bigint' AND IS_NULLABLE = 'NO')
          OR (COLUMN_NAME = 'menu_name' AND LOWER(COLUMN_TYPE) = 'varchar(50)' AND IS_NULLABLE = 'NO')
          OR (COLUMN_NAME = 'parent_id' AND LOWER(COLUMN_TYPE) = 'bigint')
          OR (COLUMN_NAME = 'order_num' AND LOWER(COLUMN_TYPE) = 'int')
          OR (COLUMN_NAME = 'path' AND LOWER(COLUMN_TYPE) = 'varchar(200)')
          OR (COLUMN_NAME = 'component' AND LOWER(COLUMN_TYPE) = 'varchar(255)')
          OR (COLUMN_NAME = 'query_param' AND LOWER(COLUMN_TYPE) = 'varchar(255)')
          OR (COLUMN_NAME = 'is_frame' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'is_cache' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'menu_type' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'visible' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'status' AND LOWER(COLUMN_TYPE) = 'char(1)')
          OR (COLUMN_NAME = 'perms' AND LOWER(COLUMN_TYPE) = 'varchar(100)')
          OR (COLUMN_NAME = 'icon' AND LOWER(COLUMN_TYPE) = 'varchar(100)')
          OR (COLUMN_NAME = 'active_menu' AND LOWER(COLUMN_TYPE) = 'varchar(255)')
          OR (COLUMN_NAME = 'ext' AND LOWER(COLUMN_TYPE) = 'varchar(2000)')
          OR (COLUMN_NAME = 'create_dept' AND LOWER(COLUMN_TYPE) = 'bigint')
          OR (COLUMN_NAME = 'create_by' AND LOWER(COLUMN_TYPE) = 'bigint')
          OR (COLUMN_NAME = 'create_time' AND LOWER(COLUMN_TYPE) = 'datetime')
          OR (COLUMN_NAME = 'update_by' AND LOWER(COLUMN_TYPE) = 'bigint')
          OR (COLUMN_NAME = 'update_time' AND LOWER(COLUMN_TYPE) = 'datetime')
          OR (COLUMN_NAME = 'remark' AND LOWER(COLUMN_TYPE) = 'varchar(500)')
      )
);
SET @sys_role_ok = (
    SELECT COUNT(*) = 1
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_role'
      AND TABLE_TYPE = 'BASE TABLE'
);

-- 既有在库管理目录基线：只校验，不更新。该目录由 iws_init.sql 提供。
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_existing_parent;
CREATE TEMPORARY TABLE tmp_iws_mvp_existing_parent (
    menu_id     bigint       NOT NULL,
    menu_name   varchar(50)  NOT NULL,
    parent_id   bigint       NOT NULL,
    order_num   int          NOT NULL,
    path        varchar(200) NOT NULL,
    component   varchar(255) DEFAULT NULL,
    query_param varchar(255) NOT NULL,
    is_frame    char(1)      NOT NULL,
    is_cache    char(1)      NOT NULL,
    menu_type   char(1)      NOT NULL,
    visible     char(1)      NOT NULL,
    status      char(1)      NOT NULL,
    perms       varchar(100) NOT NULL,
    icon        varchar(100) NOT NULL,
    active_menu varchar(255) NOT NULL,
    ext         varchar(2000) NOT NULL,
    PRIMARY KEY (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tmp_iws_mvp_existing_parent
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext)
VALUES
    (@inventory_parent_menu_id, '在库管理', 0, 13, 'iws-inventory', NULL, '',
     'N', 'Y', 'M', '0', '0', '', 'list', '', '');
SET @inventory_parent_missing = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_existing_parent e
    LEFT JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE m.menu_id IS NULL
);
SET @inventory_parent_conflict = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_existing_parent e
    JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE CAST(IFNULL(m.menu_name, '') AS BINARY) <> CAST(e.menu_name AS BINARY)
       OR IFNULL(m.parent_id, -1) <> e.parent_id
       OR IFNULL(m.order_num, -1) <> e.order_num
       OR CAST(IFNULL(m.path, '') AS BINARY) <> CAST(e.path AS BINARY)
       OR NOT (CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY))
       OR CAST(IFNULL(m.query_param, '') AS BINARY) <> CAST(e.query_param AS BINARY)
       OR CAST(IFNULL(m.is_frame, '') AS BINARY) <> CAST(e.is_frame AS BINARY)
       OR CAST(IFNULL(m.is_cache, '') AS BINARY) <> CAST(e.is_cache AS BINARY)
       OR CAST(IFNULL(m.menu_type, '') AS BINARY) <> CAST(e.menu_type AS BINARY)
       OR CAST(IFNULL(m.visible, '') AS BINARY) <> CAST(e.visible AS BINARY)
       OR CAST(IFNULL(m.status, '') AS BINARY) <> CAST(e.status AS BINARY)
       OR CAST(IFNULL(m.perms, '') AS BINARY) <> CAST(e.perms AS BINARY)
       OR CAST(IFNULL(m.icon, '') AS BINARY) <> CAST(e.icon AS BINARY)
       OR CAST(IFNULL(m.active_menu, '') AS BINARY) <> CAST(e.active_menu AS BINARY)
       OR CAST(IFNULL(m.ext, '') AS BINARY) <> CAST(e.ext AS BINARY)
);
SET @inventory_root_dup_count = (
    SELECT COUNT(*)
    FROM sys_menu
    WHERE IFNULL(parent_id, -1) = 0
      AND (CAST(IFNULL(menu_name, '') AS BINARY) = CAST('在库管理' AS BINARY)
           OR CAST(IFNULL(path, '') AS BINARY) = CAST('iws-inventory' AS BINARY))
      AND menu_id <> @inventory_parent_menu_id
);
SET @inventory_parent_ok = (
    @inventory_parent_missing = 0
    AND @inventory_parent_conflict = 0
    AND @inventory_root_dup_count = 0
);

SET @target_view_conflict = (
    SELECT COUNT(*) FROM tmp_iws_mvp_ddl WHERE action = 'CONFLICT'
);
SET @target_existing_count = (
    SELECT COUNT(*) FROM tmp_iws_mvp_ddl WHERE action = 'EXISTS'
);
SET @target_missing_count = (
    SELECT COUNT(*) FROM tmp_iws_mvp_ddl WHERE action = 'CREATE'
);
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_conflict;
CREATE TEMPORARY TABLE tmp_iws_mvp_conflict (
    table_name varchar(64) NOT NULL,
    PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO tmp_iws_mvp_conflict (table_name)
SELECT d.table_name
FROM tmp_iws_mvp_ddl d
JOIN tmp_iws_mvp_tbl e ON e.table_name = d.table_name
JOIN information_schema.TABLES t
    ON t.TABLE_SCHEMA = DATABASE()
   AND t.TABLE_NAME = d.table_name
WHERE d.action = 'EXISTS'
  AND (t.TABLE_TYPE <> 'BASE TABLE'
       OR t.ENGINE <> e.engine
       OR t.TABLE_COLLATION <> e.table_collation
       OR CAST(t.TABLE_COMMENT AS BINARY) <> CAST(e.table_comment AS BINARY));

INSERT IGNORE INTO tmp_iws_mvp_conflict (table_name)
SELECT d.table_name
FROM tmp_iws_mvp_ddl d
JOIN tmp_iws_mvp_col e ON e.table_name = d.table_name
LEFT JOIN information_schema.COLUMNS c
    ON c.TABLE_SCHEMA = DATABASE()
   AND c.TABLE_NAME = e.table_name
   AND c.COLUMN_NAME = e.column_name
WHERE d.action = 'EXISTS'
  AND (c.COLUMN_NAME IS NULL
       OR c.ORDINAL_POSITION <> e.ordinal_position
       OR LOWER(c.COLUMN_TYPE) <> LOWER(e.column_type)
       OR c.IS_NULLABLE <> e.is_nullable
       OR NOT (CAST(c.COLUMN_DEFAULT AS BINARY) <=> CAST(e.column_default AS BINARY))
       OR NOT (CAST(c.COLLATION_NAME AS BINARY) <=> CAST(e.collation_name AS BINARY))
       OR LOWER(c.EXTRA) LIKE '%auto_increment%'
       OR LOWER(c.EXTRA) LIKE '%virtual generated%'
       OR LOWER(c.EXTRA) LIKE '%stored generated%');

INSERT IGNORE INTO tmp_iws_mvp_conflict (table_name)
SELECT 'wms_company'
FROM tmp_iws_mvp_ddl d
JOIN information_schema.COLUMNS c
    ON c.TABLE_SCHEMA = DATABASE()
   AND c.TABLE_NAME = 'wms_company'
   AND c.COLUMN_NAME = 'update_time'
WHERE d.table_name = 'wms_company'
  AND d.action = 'EXISTS'
  AND LOWER(c.EXTRA) NOT LIKE '%on update current_timestamp%';

INSERT IGNORE INTO tmp_iws_mvp_conflict (table_name)
SELECT d.table_name
FROM tmp_iws_mvp_ddl d
JOIN information_schema.COLUMNS c
    ON c.TABLE_SCHEMA = DATABASE()
   AND c.TABLE_NAME = d.table_name
LEFT JOIN tmp_iws_mvp_col e
    ON e.table_name = c.TABLE_NAME
   AND e.column_name = c.COLUMN_NAME
WHERE d.action = 'EXISTS'
  AND e.table_name IS NULL;

INSERT IGNORE INTO tmp_iws_mvp_conflict (table_name)
SELECT d.table_name
FROM tmp_iws_mvp_ddl d
JOIN tmp_iws_mvp_idx e ON e.table_name = d.table_name
LEFT JOIN information_schema.STATISTICS s
    ON s.TABLE_SCHEMA = DATABASE()
   AND s.TABLE_NAME = e.table_name
   AND s.INDEX_NAME = e.index_name
   AND s.SEQ_IN_INDEX = e.seq_in_index
WHERE d.action = 'EXISTS'
  AND (s.INDEX_NAME IS NULL
       OR s.NON_UNIQUE <> e.non_unique
       OR NOT (CAST(s.COLUMN_NAME AS BINARY) <=> CAST(e.column_name AS BINARY))
       OR s.INDEX_TYPE <> 'BTREE'
       OR s.SUB_PART IS NOT NULL
       OR s.EXPRESSION IS NOT NULL
       OR s.IS_VISIBLE <> 'YES'
       OR NOT (CAST(s.COLLATION AS BINARY) <=> CAST('A' AS BINARY)));

INSERT IGNORE INTO tmp_iws_mvp_conflict (table_name)
SELECT d.table_name
FROM tmp_iws_mvp_ddl d
JOIN information_schema.STATISTICS s
    ON s.TABLE_SCHEMA = DATABASE()
   AND s.TABLE_NAME = d.table_name
LEFT JOIN tmp_iws_mvp_idx e
    ON e.table_name = s.TABLE_NAME
   AND e.index_name = s.INDEX_NAME
   AND e.seq_in_index = s.SEQ_IN_INDEX
WHERE d.action = 'EXISTS'
  AND e.table_name IS NULL;

INSERT IGNORE INTO tmp_iws_mvp_conflict (table_name)
SELECT d.table_name
FROM tmp_iws_mvp_ddl d
JOIN (
    SELECT table_name, index_name, COUNT(*) AS expected_parts,
           MAX(seq_in_index) AS expected_last
    FROM tmp_iws_mvp_idx
    GROUP BY table_name, index_name
) e ON e.table_name = d.table_name
LEFT JOIN (
    SELECT TABLE_NAME, INDEX_NAME, COUNT(*) AS actual_parts,
           MAX(SEQ_IN_INDEX) AS actual_last
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
    GROUP BY TABLE_NAME, INDEX_NAME
) a ON a.TABLE_NAME = e.table_name
   AND a.INDEX_NAME = e.index_name
WHERE d.action = 'EXISTS'
  AND (a.INDEX_NAME IS NULL
       OR a.actual_parts <> e.expected_parts
       OR a.actual_last <> e.expected_last);

SET @target_present_conflict = (SELECT COUNT(*) FROM tmp_iws_mvp_conflict);
SET @target_preflight_ok = (
    @target_view_conflict = 0
    AND @target_present_conflict = 0
    AND @target_existing_count + @target_missing_count = @expected_target_tables
);
SET @preflight_ok = (
    @baseline_ok = 1
    AND @sys_menu_ok = 1
    AND @sys_menu_columns_ok = 1
    AND @sys_menu_engine_ok = 1
    AND @sys_menu_pk_ok = 1
    AND @sys_menu_types_ok = 1
    AND @sys_role_ok = 1
    AND @inventory_parent_ok = 1
    AND @target_preflight_ok = 1
);
SET @source_state = CASE
    WHEN @target_existing_count = 0 THEN '旧态（20表）'
    WHEN @target_existing_count < @expected_target_tables THEN '中间态（部分目标表）'
    ELSE '目标态（25表）'
END;

SELECT
    @target_schema AS `目标库参数`,
    DATABASE() AS `当前库`,
    VERSION() AS `数据库版本`,
    @baseline_release_id AS `基线版本`,
    @source_state AS `源态判定`,
    @baseline_table_count AS `WMS基线表数`,
    @baseline_source AS `基线manifest匹配`,
    @baseline_legacy_mismatch AS `LEGACY manifest不符表数`,
    @baseline_empty_mismatch AS `EMPTY manifest不符表数`,
    @target_existing_count AS `已存在目标表数`,
    @target_missing_count AS `待新增目标表数`,
    @target_view_conflict AS `同名非表对象冲突数`,
    @target_present_conflict AS `已有目标表结构冲突数`,
    IF(@preflight_ok = 1, '通过', '失败：基线、系统表或目标表预检不满足') AS `前置结果`;
SELECT 'sys_menu结构' AS `校验项`,
       IF(@sys_menu_engine_ok = 1 AND @sys_menu_pk_ok = 1 AND @sys_menu_types_ok = 1, '通过', '失败') AS `结果`,
       @sys_menu_engine_ok AS `InnoDB引擎`,
       @sys_menu_pk_ok AS `主键恰为单列menu_id`,
       @sys_menu_types_ok AS `关键列类型`;
SELECT '在库管理父节点' AS `校验项`,
       IF(@inventory_parent_ok = 1, '通过', '失败') AS `结果`,
       @inventory_parent_missing AS `缺失数`,
       @inventory_parent_conflict AS `冲突数`,
       @inventory_root_dup_count AS `重复在库管理根数`;
SELECT JSON_EXTRACT(IF(@inventory_parent_ok = 1, '{}', 'invalid'), '$') AS `在库管理父节点门禁`;
SELECT JSON_EXTRACT(IF(@preflight_ok = 1, '{}', 'invalid'), '$') AS `升级前置门禁`;

-- ----------------------------------------------------------------------------
-- 四、附加 DDL（逐表由 checkpoint 门控）
-- ----------------------------------------------------------------------------
-- 只有前置门禁通过且 checkpoint 判定为 CREATE 的表才执行；否则执行 DO 0。
-- 使用不带 IF NOT EXISTS 的 CREATE：元数据与并发竞态造成的同名对象由后置结构核验兜底。
-- 每张表执行后重新读取 checkpoint；前一张表未确认 EXISTS/CREATED 时，后续 DDL 一律 DO 0。
SET @ddl_chain_ok = @preflight_ok;
SET @ddl_company = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_company') = 'CREATE',
    'CREATE TABLE wms_company (
        company_id       bigint       NOT NULL COMMENT ''公司主键，系统生成'',
        company_code     varchar(64)  COLLATE utf8mb4_bin NOT NULL COMMENT ''公司编码，业务唯一，创建后不可修改'',
        company_name     varchar(100) NOT NULL COMMENT ''公司名称'',
        enabled          tinyint      NOT NULL DEFAULT 0 COMMENT ''是否可被后续业务选择：0否，1是；与生命周期分离'',
        lifecycle_status varchar(16)  COLLATE utf8mb4_bin NOT NULL DEFAULT ''ACTIVE'' COMMENT ''生命周期：ACTIVE/RETIRING/RETIRED'',
        revision         int          NOT NULL DEFAULT 1 COMMENT ''公司乐观锁版本，创建为1'',
        remark           varchar(500) DEFAULT NULL COMMENT ''备注'',
        create_dept      bigint       DEFAULT NULL COMMENT ''创建部门'',
        create_by        bigint       DEFAULT NULL COMMENT ''创建者'',
        create_time      datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
        update_by        bigint       DEFAULT NULL COMMENT ''更新者'',
        update_time      datetime(3)  DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间'',
        PRIMARY KEY (company_id),
        UNIQUE KEY uk_wms_company_code (company_code),
        KEY idx_wms_company_lifecycle (lifecycle_status, enabled)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS公司主档''',
    'DO 0'
);
PREPARE stmt_iws_mvp_company FROM @ddl_company;
EXECUTE stmt_iws_mvp_company;
DEALLOCATE PREPARE stmt_iws_mvp_company;
UPDATE tmp_iws_mvp_ddl SET action = 'CREATED'
WHERE table_name = 'wms_company' AND action = 'CREATE' AND @preflight_ok = 1
  AND EXISTS (SELECT 1 FROM information_schema.TABLES t
              WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'wms_company'
                AND t.TABLE_TYPE = 'BASE TABLE');
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_company') IN ('EXISTS', 'CREATED'),
    1, 0
);

SET @ddl_scope = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_company_management_scope') = 'CREATE',
    'CREATE TABLE wms_company_management_scope (
        company_id     bigint      NOT NULL COMMENT ''公司主键，逻辑关联 wms_company.company_id；每公司恰一行'',
        scope_revision int         NOT NULL DEFAULT 0 COMMENT ''管理范围独立乐观锁版本，创建为0'',
        updated_by     bigint      DEFAULT NULL COMMENT ''最后更新人'',
        updated_at     datetime(3) DEFAULT NULL COMMENT ''最后更新时间'',
        PRIMARY KEY (company_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS公司管理范围头''',
    'DO 0'
);
PREPARE stmt_iws_mvp_scope FROM @ddl_scope;
EXECUTE stmt_iws_mvp_scope;
DEALLOCATE PREPARE stmt_iws_mvp_scope;
UPDATE tmp_iws_mvp_ddl SET action = 'CREATED'
WHERE table_name = 'wms_company_management_scope' AND action = 'CREATE' AND @preflight_ok = 1
  AND EXISTS (SELECT 1 FROM information_schema.TABLES t
              WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'wms_company_management_scope'
                AND t.TABLE_TYPE = 'BASE TABLE');
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_company_management_scope') IN ('EXISTS', 'CREATED'),
    1, 0
);

SET @ddl_grant = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_company_management_grant') = 'CREATE',
    'CREATE TABLE wms_company_management_grant (
        grant_id   bigint      NOT NULL COMMENT ''授权明细主键，系统生成'',
        company_id bigint      NOT NULL COMMENT ''公司主键，逻辑关联 wms_company.company_id'',
        role_id    bigint      NOT NULL COMMENT ''角色主键，逻辑关联 sys_role.role_id'',
        created_by bigint      DEFAULT NULL COMMENT ''创建者'',
        created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间'',
        PRIMARY KEY (grant_id),
        UNIQUE KEY uk_wms_company_grant (company_id, role_id),
        KEY idx_wms_company_grant_role (role_id, company_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS公司管理角色授权明细''',
    'DO 0'
);
PREPARE stmt_iws_mvp_grant FROM @ddl_grant;
EXECUTE stmt_iws_mvp_grant;
DEALLOCATE PREPARE stmt_iws_mvp_grant;
UPDATE tmp_iws_mvp_ddl SET action = 'CREATED'
WHERE table_name = 'wms_company_management_grant' AND action = 'CREATE' AND @preflight_ok = 1
  AND EXISTS (SELECT 1 FROM information_schema.TABLES t
              WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'wms_company_management_grant'
                AND t.TABLE_TYPE = 'BASE TABLE');
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_company_management_grant') IN ('EXISTS', 'CREATED'),
    1, 0
);

SET @ddl_audit = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_object_audit') = 'CREATE',
    'CREATE TABLE wms_object_audit (
        audit_id          bigint        NOT NULL COMMENT ''审计主键，系统生成'',
        object_type       varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT ''对象类型，如 WMS_COMPANY'',
        object_id         bigint        NOT NULL COMMENT ''对象内部ID'',
        action            varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT ''动作：CREATE/UPDATE/ENABLE/DISABLE/MANAGEMENT_SCOPE_REPLACE'',
        command_key       varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT ''命令键，用于幂等与追责'',
        before_summary    varchar(2000) DEFAULT NULL COMMENT ''变更前摘要（脱敏快照，超长截断）'',
        after_summary     varchar(2000) DEFAULT NULL COMMENT ''变更后摘要（脱敏快照，超长截断）'',
        actor_user_id     bigint        DEFAULT NULL COMMENT ''当前操作者'',
        actor_name        varchar(64)   DEFAULT NULL COMMENT ''操作者名称快照'',
        initiator_type    varchar(32)   COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''发起人类型：USER/PLAN/SYSTEM'',
        initiator_id      varchar(128)  DEFAULT NULL COMMENT ''发起人标识'',
        initiator_name    varchar(64)   DEFAULT NULL COMMENT ''发起人名称快照'',
        dept_id           bigint        DEFAULT NULL COMMENT ''操作者部门快照'',
        dept_name         varchar(128)  DEFAULT NULL COMMENT ''操作者部门名称快照'',
        execution_subject varchar(64)   DEFAULT NULL COMMENT ''执行主体名称快照'',
        execution_subject_type varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''执行主体类型：USER/PLAN/SYSTEM'',
        execution_account varchar(64)   DEFAULT NULL COMMENT ''执行账号快照'',
        result            varchar(16)   COLLATE utf8mb4_bin NOT NULL COMMENT ''结果：SUCCESS/REJECTED/FAILED/UNKNOWN'',
        evidence_json     varchar(2000) DEFAULT NULL COMMENT ''证据引用或幂等摘要'',
        occurred_at       datetime(3)   NOT NULL COMMENT ''业务发生时间'',
        recorded_at       datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''审计记录时间'',
        PRIMARY KEY (audit_id),
        KEY idx_wms_object_audit_object (object_type, object_id, occurred_at),
        KEY idx_wms_object_audit_command_key (command_key)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS对象变更审计，只追加''',
    'DO 0'
);
PREPARE stmt_iws_mvp_audit FROM @ddl_audit;
EXECUTE stmt_iws_mvp_audit;
DEALLOCATE PREPARE stmt_iws_mvp_audit;
UPDATE tmp_iws_mvp_ddl SET action = 'CREATED'
WHERE table_name = 'wms_object_audit' AND action = 'CREATE' AND @preflight_ok = 1
  AND EXISTS (SELECT 1 FROM information_schema.TABLES t
              WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'wms_object_audit'
                AND t.TABLE_TYPE = 'BASE TABLE');
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_object_audit') IN ('EXISTS', 'CREATED'),
    1, 0
);

SET @ddl_attempt = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_command_attempt') = 'CREATE',
    'CREATE TABLE wms_command_attempt (
        attempt_id        bigint        NOT NULL COMMENT ''尝试主键，系统生成'',
        command_key       varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT ''命令键'',
        action            varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT ''命令动作，如 COMPANY_CREATE/COMPANY_UPDATE/COMPANY_ENABLE/COMPANY_DISABLE/COMPANY_SCOPE_REPLACE'',
        object_type       varchar(64)   COLLATE utf8mb4_bin NOT NULL COMMENT ''对象类型，如 WMS_COMPANY'',
        object_id         bigint        DEFAULT NULL COMMENT ''对象内部ID，创建前可空'',
        input_digest      char(64)      NOT NULL COMMENT ''规范化请求摘要，SHA-256小写'',
        input_summary     varchar(2000) DEFAULT NULL COMMENT ''脱敏请求摘要'',
        status            varchar(16)   COLLATE utf8mb4_bin NOT NULL COMMENT ''状态：ACCEPTED/PROCESSING/APPLIED/REJECTED/FAILED/UNKNOWN'',
        error_code        varchar(64)   COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''错误码，如 VERSION_CONFLICT/SCOPE_VERSION_CONFLICT/FEATURE_NOT_OPEN'',
        error_message     varchar(500)  DEFAULT NULL COMMENT ''脱敏错误消息'',
        fact_type         varchar(64)   COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''成功事实类型，如 WMS_OBJECT_AUDIT'',
        fact_id           varchar(128)  DEFAULT NULL COMMENT ''成功事实标识'',
        actor_user_id     bigint        DEFAULT NULL COMMENT ''操作者'',
        actor_name        varchar(64)   DEFAULT NULL COMMENT ''操作者名称快照'',
        dept_id           bigint        DEFAULT NULL COMMENT ''操作者部门快照'',
        dept_name         varchar(128)  DEFAULT NULL COMMENT ''操作者部门名称快照'',
        initiator_name    varchar(64)   DEFAULT NULL COMMENT ''发起人名称快照'',
        execution_subject varchar(64)   DEFAULT NULL COMMENT ''执行主体名称快照'',
        execution_subject_type varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT ''执行主体类型：USER/PLAN/SYSTEM'',
        execution_account varchar(64)   DEFAULT NULL COMMENT ''执行账号快照'',
        occurred_at       datetime(3)   NOT NULL COMMENT ''命令发生时间'',
        recorded_at       datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''记录时间'',
        PRIMARY KEY (attempt_id),
        UNIQUE KEY uk_wms_command_attempt_command (command_key),
        KEY idx_wms_command_attempt_object (object_type, object_id, occurred_at),
        KEY idx_wms_command_attempt_status (status, occurred_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT=''WMS非计划受控命令尝试''',
    'DO 0'
);
PREPARE stmt_iws_mvp_attempt FROM @ddl_attempt;
EXECUTE stmt_iws_mvp_attempt;
DEALLOCATE PREPARE stmt_iws_mvp_attempt;
UPDATE tmp_iws_mvp_ddl SET action = 'CREATED'
WHERE table_name = 'wms_command_attempt' AND action = 'CREATE' AND @preflight_ok = 1
  AND EXISTS (SELECT 1 FROM information_schema.TABLES t
              WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'wms_command_attempt'
                AND t.TABLE_TYPE = 'BASE TABLE');
SET @ddl_chain_ok = IF(
    @ddl_chain_ok = 1
    AND (SELECT action FROM tmp_iws_mvp_ddl WHERE table_name = 'wms_command_attempt') IN ('EXISTS', 'CREATED'),
    1, 0
);

SET @ddl_checkpoint_ok = (
    SELECT COUNT(*) = @expected_target_tables
    FROM tmp_iws_mvp_ddl
    WHERE action IN ('EXISTS', 'CREATED')
) AND @ddl_chain_ok = 1;
SET @ddl_created_count = (
    SELECT COUNT(*) FROM tmp_iws_mvp_ddl WHERE action = 'CREATED'
);
SET @ddl_existing_count = (
    SELECT COUNT(*) FROM tmp_iws_mvp_ddl WHERE action = 'EXISTS'
);
SELECT 'DDL checkpoint' AS `校验项`,
       IF(@ddl_checkpoint_ok = 1, '通过', '失败') AS `结果`,
       @ddl_created_count AS `本次新增表数`,
       @ddl_existing_count AS `原有目标表数`,
       @ddl_chain_ok AS `逐表成功链`;
SELECT JSON_EXTRACT(IF(@ddl_checkpoint_ok = 1, '{}', 'invalid'), '$') AS `DDL checkpoint 门禁`;

-- ----------------------------------------------------------------------------
-- 五、表选项精确校验
-- ----------------------------------------------------------------------------
SET @tbl_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_tbl e
    LEFT JOIN information_schema.TABLES t
        ON t.TABLE_SCHEMA = DATABASE()
       AND t.TABLE_NAME = e.table_name
    WHERE t.TABLE_NAME IS NULL
       OR t.TABLE_TYPE <> 'BASE TABLE'
       OR t.ENGINE <> e.engine
       OR t.TABLE_COLLATION <> e.table_collation
       OR t.TABLE_COMMENT <> e.table_comment
);
SELECT '目标表选项' AS `校验项`, IF(@tbl_mismatch = 0, '通过', '失败') AS `结果`,
       @tbl_mismatch AS `不一致表数`;
SELECT JSON_EXTRACT(IF(@tbl_mismatch = 0, '{}', 'invalid'), '$') AS `表选项门禁`;

-- ----------------------------------------------------------------------------
-- 六、列定义精确校验（名称、顺序、类型、空值、默认值、字符集、注释）
-- ----------------------------------------------------------------------------
SET @col_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_col e
    LEFT JOIN information_schema.COLUMNS c
        ON c.TABLE_SCHEMA = DATABASE()
       AND c.TABLE_NAME = e.table_name
       AND c.COLUMN_NAME = e.column_name
    WHERE c.COLUMN_NAME IS NULL
       OR c.ORDINAL_POSITION <> e.ordinal_position
       OR LOWER(c.COLUMN_TYPE) <> LOWER(e.column_type)
       OR c.IS_NULLABLE <> e.is_nullable
       OR NOT (CAST(c.COLUMN_DEFAULT AS BINARY) <=> CAST(e.column_default AS BINARY))
       OR NOT (CAST(c.COLLATION_NAME AS BINARY) <=> CAST(e.collation_name AS BINARY))
       OR BINARY c.COLUMN_COMMENT <> BINARY e.column_comment
);
SET @col_count_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT table_name, COUNT(*) AS expected_count
        FROM tmp_iws_mvp_col
        GROUP BY table_name
    ) e
    LEFT JOIN (
        SELECT TABLE_NAME, COUNT(*) AS actual_count
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME IN (
              'wms_company',
              'wms_company_management_scope',
              'wms_company_management_grant',
              'wms_object_audit',
              'wms_command_attempt'
          )
        GROUP BY TABLE_NAME
    ) a ON a.TABLE_NAME = e.table_name
    WHERE a.actual_count IS NULL
       OR a.actual_count <> e.expected_count
);
SET @on_update_mismatch = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wms_company'
      AND COLUMN_NAME = 'update_time'
      AND LOWER(EXTRA) NOT LIKE '%on update current_timestamp%'
);
SET @col_extra_mismatch = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN (
          'wms_company',
          'wms_company_management_scope',
          'wms_company_management_grant',
          'wms_object_audit',
          'wms_command_attempt'
      )
      AND (
          LOWER(EXTRA) LIKE '%auto_increment%'
          OR LOWER(EXTRA) LIKE '%virtual generated%'
          OR LOWER(EXTRA) LIKE '%stored generated%'
      )
);
SELECT '目标表列定义' AS `校验项`,
       IF(@col_mismatch = 0 AND @col_count_mismatch = 0 AND @on_update_mismatch = 0 AND @col_extra_mismatch = 0, '通过', '失败') AS `结果`,
       @col_mismatch AS `不一致列数`,
       @col_count_mismatch AS `列数不一致表数`,
       @on_update_mismatch AS `更新时间自动维护不符数`,
       @col_extra_mismatch AS `自增或生成列不符数`;
SELECT JSON_EXTRACT(IF(@col_mismatch = 0 AND @col_count_mismatch = 0 AND @on_update_mismatch = 0 AND @col_extra_mismatch = 0, '{}', 'invalid'), '$') AS `列定义门禁`;

-- ----------------------------------------------------------------------------
-- 七、索引精确校验（名称、唯一性、顺序、列、索引类型）
-- ----------------------------------------------------------------------------
SET @idx_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_idx e
    LEFT JOIN information_schema.STATISTICS s
        ON s.TABLE_SCHEMA = DATABASE()
       AND s.TABLE_NAME = e.table_name
       AND s.INDEX_NAME = e.index_name
       AND s.SEQ_IN_INDEX = e.seq_in_index
    WHERE s.INDEX_NAME IS NULL
       OR s.NON_UNIQUE <> e.non_unique
       OR NOT (CAST(s.COLUMN_NAME AS BINARY) <=> CAST(e.column_name AS BINARY))
       OR s.INDEX_TYPE <> 'BTREE'
       OR s.SUB_PART IS NOT NULL
       OR s.EXPRESSION IS NOT NULL
       OR s.IS_VISIBLE <> 'YES'
       OR NOT (CAST(s.COLLATION AS BINARY) <=> CAST('A' AS BINARY))
);
SET @idx_count_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT table_name, COUNT(DISTINCT index_name) AS expected_count
        FROM tmp_iws_mvp_idx
        GROUP BY table_name
    ) e
    LEFT JOIN (
        SELECT TABLE_NAME, COUNT(DISTINCT INDEX_NAME) AS actual_count
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME IN (
              'wms_company',
              'wms_company_management_scope',
              'wms_company_management_grant',
              'wms_object_audit',
              'wms_command_attempt'
          )
        GROUP BY TABLE_NAME
    ) a ON a.TABLE_NAME = e.table_name
    WHERE a.actual_count IS NULL
       OR a.actual_count <> e.expected_count
);
SET @idx_part_mismatch = (
    SELECT COUNT(*)
    FROM (
        SELECT table_name, index_name, COUNT(*) AS expected_parts,
               MAX(seq_in_index) AS expected_last
        FROM tmp_iws_mvp_idx
        GROUP BY table_name, index_name
    ) e
    LEFT JOIN (
        SELECT TABLE_NAME, INDEX_NAME, COUNT(*) AS actual_parts,
               MAX(SEQ_IN_INDEX) AS actual_last
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME IN (
              'wms_company',
              'wms_company_management_scope',
              'wms_company_management_grant',
              'wms_object_audit',
              'wms_command_attempt'
          )
        GROUP BY TABLE_NAME, INDEX_NAME
    ) a ON a.TABLE_NAME = e.table_name
       AND a.INDEX_NAME = e.index_name
    WHERE a.INDEX_NAME IS NULL
       OR a.actual_parts <> e.expected_parts
       OR a.actual_last <> e.expected_last
);
SET @idx_extra_count = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS s
    LEFT JOIN tmp_iws_mvp_idx e
        ON e.table_name = s.TABLE_NAME
       AND e.index_name = s.INDEX_NAME
       AND e.seq_in_index = s.SEQ_IN_INDEX
    WHERE s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME IN (
          'wms_company',
          'wms_company_management_scope',
          'wms_company_management_grant',
          'wms_object_audit',
          'wms_command_attempt'
      )
      AND e.table_name IS NULL
);
SELECT '目标表索引' AS `校验项`,
       IF(@idx_mismatch = 0 AND @idx_count_mismatch = 0
          AND @idx_part_mismatch = 0 AND @idx_extra_count = 0, '通过', '失败') AS `结果`,
       @idx_mismatch AS `不一致索引列数`,
       @idx_count_mismatch AS `索引数不一致表数`,
       @idx_part_mismatch AS `索引列数不一致数`,
       @idx_extra_count AS `多余索引列数`;
SELECT JSON_EXTRACT(IF(@idx_mismatch = 0 AND @idx_count_mismatch = 0
                       AND @idx_part_mismatch = 0 AND @idx_extra_count = 0,
                       '{}', 'invalid'), '$') AS `索引门禁`;

SET @post_ddl_ok = (
    @preflight_ok = 1
    AND @ddl_checkpoint_ok = 1
    AND @tbl_mismatch = 0
    AND @col_mismatch = 0
    AND @col_count_mismatch = 0
    AND @on_update_mismatch = 0
    AND @col_extra_mismatch = 0
    AND @idx_mismatch = 0
    AND @idx_count_mismatch = 0
    AND @idx_part_mismatch = 0
    AND @idx_extra_count = 0
);
SELECT '目标结构后置' AS `校验项`, IF(@post_ddl_ok = 1, '通过', '失败') AS `结果`;
SELECT JSON_EXTRACT(IF(@post_ddl_ok = 1, '{}', 'invalid'), '$') AS `目标结构后置门禁`;

-- ----------------------------------------------------------------------------
-- 八、数据守恒与逻辑关联校验（新表首次升级为空；重跑时保护已承接数据）
-- ----------------------------------------------------------------------------
SET @company_invalid_count = (
    SELECT COUNT(*)
    FROM wms_company
    WHERE lifecycle_status NOT IN ('ACTIVE', 'RETIRING', 'RETIRED')
       OR enabled NOT IN (0, 1)
       OR revision < 1
       OR company_code = ''
);
SELECT '公司主档取值' AS `校验项`, IF(@company_invalid_count = 0, '通过', '失败') AS `结果`,
       @company_invalid_count AS `非法行数`;
SELECT JSON_EXTRACT(IF(@company_invalid_count = 0, '{}', 'invalid'), '$') AS `公司取值门禁`;

SET @scope_invalid_count = (
    SELECT
        (SELECT COUNT(*)
         FROM wms_company c
         LEFT JOIN wms_company_management_scope s ON s.company_id = c.company_id
         WHERE s.company_id IS NULL)
      + (SELECT COUNT(*)
         FROM wms_company_management_scope s
         LEFT JOIN wms_company c ON c.company_id = s.company_id
         WHERE c.company_id IS NULL)
      + (SELECT COUNT(*) FROM wms_company_management_scope WHERE scope_revision < 0)
);
SELECT '公司管理范围头' AS `校验项`, IF(@scope_invalid_count = 0, '通过', '失败') AS `结果`,
       @scope_invalid_count AS `缺失或非法行数`;
SELECT JSON_EXTRACT(IF(@scope_invalid_count = 0, '{}', 'invalid'), '$') AS `管理范围门禁`;

SET @grant_invalid_count = (
    SELECT
        (SELECT COUNT(*)
         FROM wms_company_management_grant g
         LEFT JOIN wms_company c ON c.company_id = g.company_id
         WHERE c.company_id IS NULL)
      + (SELECT COUNT(*)
         FROM wms_company_management_grant
         WHERE role_id IS NULL OR role_id <= 0)
);
SELECT '公司管理授权明细' AS `校验项`, IF(@grant_invalid_count = 0, '通过', '失败') AS `结果`,
       @grant_invalid_count AS `孤儿或非法行数`;
SELECT JSON_EXTRACT(IF(@grant_invalid_count = 0, '{}', 'invalid'), '$') AS `管理授权门禁`;

SET @audit_invalid_count = (
    SELECT COUNT(*)
    FROM wms_object_audit
    WHERE result NOT IN ('SUCCESS', 'REJECTED', 'FAILED', 'UNKNOWN')
       OR command_key = ''
       OR object_type = ''
       OR execution_subject_type = ''
       OR execution_account = ''
);
SELECT '对象审计取值' AS `校验项`, IF(@audit_invalid_count = 0, '通过', '失败') AS `结果`,
       @audit_invalid_count AS `非法行数`;
SELECT JSON_EXTRACT(IF(@audit_invalid_count = 0, '{}', 'invalid'), '$') AS `对象审计门禁`;

SET @attempt_invalid_count = (
    SELECT COUNT(*)
    FROM wms_command_attempt
    WHERE status NOT IN ('ACCEPTED', 'PROCESSING', 'APPLIED', 'REJECTED', 'FAILED', 'UNKNOWN')
       OR command_key = ''
       OR action = ''
       OR CHAR_LENGTH(input_digest) <> 64
       OR execution_subject_type = ''
       OR execution_account = ''
);
SET @attempt_orphan_role_count = (
    SELECT COUNT(*)
    FROM wms_company_management_grant g
    LEFT JOIN sys_role r ON r.role_id = g.role_id
    WHERE r.role_id IS NULL
);
SET @data_ok = (
    @company_invalid_count = 0
    AND @scope_invalid_count = 0
    AND @grant_invalid_count = 0
    AND @audit_invalid_count = 0
    AND @attempt_invalid_count = 0
);
SELECT '受控命令尝试取值' AS `校验项`, IF(@attempt_invalid_count = 0, '通过', '失败') AS `结果`,
       @attempt_invalid_count AS `非法行数`,
       @attempt_orphan_role_count AS `引用已删除角色的授权数`;
SELECT JSON_EXTRACT(IF(@attempt_invalid_count = 0, '{}', 'invalid'), '$') AS `命令尝试门禁`;

-- ----------------------------------------------------------------------------
-- 九、在库管理菜单与功能权限（visible 0 显示、1 隐藏；先全量预检，后补缺，不覆盖，不授予角色）
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_menu;
CREATE TEMPORARY TABLE tmp_iws_mvp_menu (
    menu_id     bigint       NOT NULL,
    menu_name   varchar(50)  NOT NULL,
    parent_id   bigint       NOT NULL,
    order_num   int          NOT NULL,
    path        varchar(200) NOT NULL,
    component   varchar(255) DEFAULT NULL,
    query_param varchar(255) NOT NULL,
    is_frame    char(1)      NOT NULL,
    is_cache    char(1)      NOT NULL,
    menu_type   char(1)      NOT NULL,
    visible     char(1)      NOT NULL,
    status      char(1)      NOT NULL,
    perms       varchar(100) NOT NULL,
    icon        varchar(100) NOT NULL,
    active_menu varchar(255) NOT NULL,
    ext         varchar(2000) NOT NULL,
    PRIMARY KEY (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tmp_iws_mvp_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext)
VALUES
    (@inventory_company_menu_id, '公司管理', @inventory_parent_menu_id, 1, 'company', 'wms/inventory/company/index', '', 'N', 'Y', 'C', '0', '0', 'inventory:company:query', 'peoples', '', ''),
    (@inventory_company_create_id, '公司新增', @inventory_company_menu_id, 1, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:company:create', '#', '', ''),
    (@inventory_company_update_id, '公司修改', @inventory_company_menu_id, 2, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:company:update', '#', '', ''),
    (@inventory_company_enable_id, '公司启用', @inventory_company_menu_id, 3, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:company:enable', '#', '', ''),
    (@inventory_company_disable_id, '公司停用', @inventory_company_menu_id, 4, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:company:disable', '#', '', ''),
    (@inventory_company_scope_all_id, '跨公司范围', @inventory_company_menu_id, 5, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:company:scope:all', '#', '', ''),
    (@inventory_company_scope_manage_id, '管理范围授权', @inventory_company_menu_id, 6, '', NULL, '', 'N', 'Y', 'F', '0', '0', 'inventory:company:scope:manage', '#', '', '');

SET @menu_id_conflict = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_menu e
    JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE CAST(IFNULL(m.menu_name, '') AS BINARY) <> CAST(e.menu_name AS BINARY)
       OR IFNULL(m.parent_id, -1) <> e.parent_id
       OR IFNULL(m.order_num, -1) <> e.order_num
       OR CAST(IFNULL(m.path, '') AS BINARY) <> CAST(e.path AS BINARY)
       OR NOT (CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY))
       OR CAST(IFNULL(m.query_param, '') AS BINARY) <> CAST(e.query_param AS BINARY)
       OR CAST(IFNULL(m.is_frame, '') AS BINARY) <> CAST(e.is_frame AS BINARY)
       OR CAST(IFNULL(m.is_cache, '') AS BINARY) <> CAST(e.is_cache AS BINARY)
       OR CAST(IFNULL(m.menu_type, '') AS BINARY) <> CAST(e.menu_type AS BINARY)
       OR CAST(IFNULL(m.visible, '') AS BINARY) <> CAST(e.visible AS BINARY)
       OR CAST(IFNULL(m.status, '') AS BINARY) <> CAST(e.status AS BINARY)
       OR CAST(IFNULL(m.perms, '') AS BINARY) <> CAST(e.perms AS BINARY)
       OR CAST(IFNULL(m.icon, '') AS BINARY) <> CAST(e.icon AS BINARY)
       OR CAST(IFNULL(m.active_menu, '') AS BINARY) <> CAST(e.active_menu AS BINARY)
       OR CAST(IFNULL(m.ext, '') AS BINARY) <> CAST(e.ext AS BINARY)
);
SET @menu_missing_count = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_menu e
    LEFT JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE m.menu_id IS NULL
);
SET @menu_path_conflict = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_menu e
    JOIN sys_menu m
        ON IFNULL(m.parent_id, -1) = e.parent_id
       AND CAST(IFNULL(m.path, '') AS BINARY) = CAST(e.path AS BINARY)
       AND m.menu_id <> e.menu_id
    WHERE e.path <> ''
      AND e.menu_type IN ('M', 'C')
);
SET @menu_perm_conflict = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_menu e
    JOIN sys_menu m
        ON CAST(IFNULL(m.perms, '') AS BINARY) = CAST(e.perms AS BINARY)
       AND m.menu_id <> e.menu_id
    WHERE e.perms <> ''
);
SET @menu_preflight_ok = (
    @post_ddl_ok = 1
    AND @data_ok = 1
    AND @menu_id_conflict = 0
    AND @menu_missing_count BETWEEN 0 AND 7
    AND @menu_path_conflict = 0
    AND @inventory_parent_ok = 1
    AND @inventory_root_dup_count = 0
    AND @menu_perm_conflict = 0
);
SELECT '菜单前置冲突' AS `校验项`,
       IF(@menu_preflight_ok = 1, '通过', '失败') AS `结果`,
       @menu_id_conflict AS `ID已有内容冲突数`,
       @menu_missing_count AS `待新增菜单数`,
       @menu_path_conflict AS `路径冲突数`,
       @inventory_parent_missing AS `父节点缺失数`,
       @inventory_parent_conflict AS `父节点冲突数`,
       @inventory_root_dup_count AS `重复在库管理根数`,
       @menu_perm_conflict AS `权限归属冲突数`;
SELECT JSON_EXTRACT(IF(@menu_preflight_ok = 1, '{}', 'invalid'), '$') AS `菜单前置门禁`;

START TRANSACTION;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
     create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @inventory_company_menu_id, '公司管理', @inventory_parent_menu_id, 1, 'company',
       'wms/inventory/company/index', '', 'N', 'Y', 'C', '0', '0',
       'inventory:company:query', 'peoples', '', '',
       NULL, NULL, CURRENT_TIMESTAMP, NULL, NULL, '公司最小闭环'
FROM DUAL
WHERE @menu_preflight_ok = 1
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = @inventory_company_menu_id);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
     create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @inventory_company_create_id, '公司新增', @inventory_company_menu_id, 1, '', NULL, '',
       'N', 'Y', 'F', '0', '0', 'inventory:company:create', '#', '', '',
       NULL, NULL, CURRENT_TIMESTAMP, NULL, NULL, ''
FROM DUAL
WHERE @menu_preflight_ok = 1
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = @inventory_company_create_id);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
     create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @inventory_company_update_id, '公司修改', @inventory_company_menu_id, 2, '', NULL, '',
       'N', 'Y', 'F', '0', '0', 'inventory:company:update', '#', '', '',
       NULL, NULL, CURRENT_TIMESTAMP, NULL, NULL, ''
FROM DUAL
WHERE @menu_preflight_ok = 1
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = @inventory_company_update_id);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
     create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @inventory_company_enable_id, '公司启用', @inventory_company_menu_id, 3, '', NULL, '',
       'N', 'Y', 'F', '0', '0', 'inventory:company:enable', '#', '', '',
       NULL, NULL, CURRENT_TIMESTAMP, NULL, NULL, ''
FROM DUAL
WHERE @menu_preflight_ok = 1
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = @inventory_company_enable_id);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
     create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @inventory_company_disable_id, '公司停用', @inventory_company_menu_id, 4, '', NULL, '',
       'N', 'Y', 'F', '0', '0', 'inventory:company:disable', '#', '', '',
       NULL, NULL, CURRENT_TIMESTAMP, NULL, NULL, ''
FROM DUAL
WHERE @menu_preflight_ok = 1
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = @inventory_company_disable_id);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
     create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @inventory_company_scope_all_id, '跨公司范围', @inventory_company_menu_id, 5, '', NULL, '',
       'N', 'Y', 'F', '0', '0', 'inventory:company:scope:all', '#', '', '',
       NULL, NULL, CURRENT_TIMESTAMP, NULL, NULL, ''
FROM DUAL
WHERE @menu_preflight_ok = 1
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = @inventory_company_scope_all_id);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
     create_dept, create_by, create_time, update_by, update_time, remark)
SELECT @inventory_company_scope_manage_id, '管理范围授权', @inventory_company_menu_id, 6, '', NULL, '',
       'N', 'Y', 'F', '0', '0', 'inventory:company:scope:manage', '#', '', '',
       NULL, NULL, CURRENT_TIMESTAMP, NULL, NULL, ''
FROM DUAL
WHERE @menu_preflight_ok = 1
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = @inventory_company_scope_manage_id);

SET @menu_mismatch = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_menu e
    LEFT JOIN sys_menu m ON m.menu_id = e.menu_id
    WHERE m.menu_id IS NULL
       OR CAST(IFNULL(m.menu_name, '') AS BINARY) <> CAST(e.menu_name AS BINARY)
       OR IFNULL(m.parent_id, -1) <> e.parent_id
       OR IFNULL(m.order_num, -1) <> e.order_num
       OR CAST(IFNULL(m.path, '') AS BINARY) <> CAST(e.path AS BINARY)
       OR NOT (CAST(m.component AS BINARY) <=> CAST(e.component AS BINARY))
       OR CAST(IFNULL(m.query_param, '') AS BINARY) <> CAST(e.query_param AS BINARY)
       OR CAST(IFNULL(m.is_frame, '') AS BINARY) <> CAST(e.is_frame AS BINARY)
       OR CAST(IFNULL(m.is_cache, '') AS BINARY) <> CAST(e.is_cache AS BINARY)
       OR CAST(IFNULL(m.perms, '') AS BINARY) <> CAST(e.perms AS BINARY)
       OR CAST(IFNULL(m.menu_type, '') AS BINARY) <> CAST(e.menu_type AS BINARY)
       OR CAST(IFNULL(m.visible, '') AS BINARY) <> CAST(e.visible AS BINARY)
       OR CAST(IFNULL(m.status, '') AS BINARY) <> CAST(e.status AS BINARY)
       OR CAST(IFNULL(m.icon, '') AS BINARY) <> CAST(e.icon AS BINARY)
       OR CAST(IFNULL(m.active_menu, '') AS BINARY) <> CAST(e.active_menu AS BINARY)
       OR CAST(IFNULL(m.ext, '') AS BINARY) <> CAST(e.ext AS BINARY)
);
SET @perm_dup_count = (
    SELECT COUNT(*)
    FROM (
        SELECT perms
        FROM sys_menu
        WHERE perms IN (
            'inventory:company:query',
            'inventory:company:create',
            'inventory:company:update',
            'inventory:company:enable',
            'inventory:company:disable',
            'inventory:company:scope:all',
            'inventory:company:scope:manage'
        )
        GROUP BY perms
        HAVING COUNT(*) <> 1
    ) d
);
SET @menu_path_conflict = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_menu e
    JOIN sys_menu m
        ON IFNULL(m.parent_id, -1) = e.parent_id
       AND CAST(IFNULL(m.path, '') AS BINARY) = CAST(e.path AS BINARY)
       AND m.menu_id <> e.menu_id
    WHERE e.path <> ''
      AND e.menu_type IN ('M', 'C')
);
SET @menu_perm_conflict = (
    SELECT COUNT(*)
    FROM tmp_iws_mvp_menu e
    JOIN sys_menu m
        ON CAST(IFNULL(m.perms, '') AS BINARY) = CAST(e.perms AS BINARY)
       AND m.menu_id <> e.menu_id
    WHERE e.perms <> ''
);
SET @menu_ok = (
    @menu_mismatch = 0
    AND @perm_dup_count = 0
    AND @inventory_parent_ok = 1
    AND @inventory_root_dup_count = 0
    AND @menu_path_conflict = 0
    AND @menu_perm_conflict = 0
);
SELECT '公司菜单与权限' AS `校验项`,
       IF(@menu_ok = 1, '通过', '失败') AS `结果`,
       @menu_mismatch AS `菜单不一致数`,
       @perm_dup_count AS `权限重复或缺失数`,
       @menu_path_conflict AS `路径冲突数`,
       @inventory_parent_missing AS `父节点缺失数`,
       @inventory_parent_conflict AS `父节点冲突数`,
       @inventory_root_dup_count AS `重复在库管理根数`,
       @menu_perm_conflict AS `权限归属冲突数`;

-- 必须先明确结束事务，再触发 JSON 错误：已知后置失败时显式 ROLLBACK，
-- 不依赖客户端停在未结束事务或连接关闭来隐式回滚。
-- 执行器要求：同一连接执行；禁止 --force；任何 SQL 语句错误必须终止并关闭连接。
SET @menu_tx_end = IF(@menu_ok = 1, 'COMMIT', 'ROLLBACK');
PREPARE stmt_iws_mvp_menu_tx FROM @menu_tx_end;
EXECUTE stmt_iws_mvp_menu_tx;
DEALLOCATE PREPARE stmt_iws_mvp_menu_tx;
SELECT JSON_EXTRACT(IF(@menu_ok = 1, '{}', 'invalid'), '$') AS `菜单权限门禁`;

-- ----------------------------------------------------------------------------
-- 十、清理临时对象并输出验收摘要
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_baseline;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_actual_tbl;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_actual_col;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_actual_idx;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_col;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_idx;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_tbl;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_menu;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_existing_parent;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_ddl;
DROP TEMPORARY TABLE IF EXISTS tmp_iws_mvp_conflict;

SET @company_count = (SELECT COUNT(*) FROM wms_company);
SET @scope_count = (SELECT COUNT(*) FROM wms_company_management_scope);
SET @grant_count = (SELECT COUNT(*) FROM wms_company_management_grant);
SET @audit_count = (SELECT COUNT(*) FROM wms_object_audit);
SET @attempt_count = (SELECT COUNT(*) FROM wms_command_attempt);
SET @inventory_menu_count = (
    SELECT COUNT(*)
    FROM sys_menu
    WHERE menu_id IN (
        @inventory_company_menu_id,
        @inventory_company_create_id,
        @inventory_company_update_id,
        @inventory_company_enable_id,
        @inventory_company_disable_id,
        @inventory_company_scope_all_id,
        @inventory_company_scope_manage_id
    )
);
SET @final_ok = (
    @preflight_ok = 1
    AND @post_ddl_ok = 1
    AND @menu_preflight_ok = 1
    AND @data_ok = 1
    AND @menu_ok = 1
    AND @inventory_parent_ok = 1
    AND @inventory_root_dup_count = 0
    AND @inventory_menu_count = 7
);

SELECT
    @target_schema AS `目标库`,
    @release_id AS `发布标识`,
    @baseline_release_id AS `基线版本`,
    @baseline_source AS `基线manifest匹配`,
    @source_state AS `升级前源态`,
    @target_missing_count AS `本次升级前缺失表数`,
    @target_present_conflict AS `升级前已有目标表冲突数`,
    @company_count AS `公司行数`,
    @scope_count AS `管理范围头行数`,
    @grant_count AS `管理授权行数`,
    @audit_count AS `对象审计行数`,
    @attempt_count AS `命令尝试行数`,
    @inventory_menu_count AS `公司菜单行数`,
    @attempt_orphan_role_count AS `引用已删除角色的授权数（仅提示）`,
    IF(@final_ok = 1, '公司最小闭环结构升级完成', '升级验收失败，请检查上述校验项') AS `执行结果`;

SELECT JSON_EXTRACT(IF(@final_ok = 1, '{}', 'invalid'), '$') AS `升级完成门禁`;

SET SESSION group_concat_max_len = @saved_group_concat_max_len;
