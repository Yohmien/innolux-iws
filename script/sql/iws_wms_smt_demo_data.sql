-- -----------------------------------------------------------------------------
-- IWS+ SMT 电子料仓库标准化样例数据
-- -----------------------------------------------------------------------------
-- 用途：
-- 1. 仅用于本地开发、标准 CRUD 页面联调和演示。
-- 2. 不属于框架初始化脚本，不应合并到 script/sql/iws_init.sql。
-- 3. 执行前请先执行 script/sql/iws_wms_smt_core.sql。
-- -----------------------------------------------------------------------------

insert ignore into wms_supplier
    (supplier_id, supplier_code, supplier_name, contact_person, contact_phone, contact_email, address, status, remark)
values
    (1762000000000001001, 'SUP-MURATA', 'Murata Manufacturing', 'Kevin Chen', '021-6000-1001', 'murata@example.com',
     'Japan / China SMT material supplier', 1, 'SMT电子料标准样例供应商'),
    (1762000000000001002, 'SUP-YAGEO', 'YAGEO Corporation', 'Amy Lin', '021-6000-1002', 'yageo@example.com',
     'Passive components supplier', 1, '电阻电容类供应商'),
    (1762000000000001003, 'SUP-TDK', 'TDK Electronics', 'Jason Wu', '021-6000-1003', 'tdk@example.com',
     'Electronic components supplier', 1, '磁性元件与电子料供应商'),
    (1762000000000001004, 'SUP-DEMO-OFF', '停用样例供应商', 'Demo User', '021-6000-1004', 'demo-off@example.com',
     'Inactive demo supplier', 0, '用于验证状态字典与筛选');
