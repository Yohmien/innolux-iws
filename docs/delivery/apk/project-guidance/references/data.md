# IWS 数据模型差异

只在涉及模型、SQL、结构核验或持久化时读取。路径相对于后端仓库；真实库访问遵循已安装的 database-readonly-mcp 与上级只读约束。

## 当前结构源

`script/sql/iws_init.sql` 负责框架初始化，`iws_wms_smt_core.sql` 负责 WMS 核心表，`iws_wms_smt_demo_data.sql` 只提供演示数据。准备脚本不授权执行；存量升级不能用重跑初始化替代。

从零系统初始化使用 `script/sql/iws_init_from_empty.ps1` 创建并选择目标库，再执行同目录完整 `iws_init_from_empty.sql`；包含框架、WMS 核心、endpoint/process 结构及必要种子，不含业务演示数据。该入口只接受不存在或完全空的目标库，属于一次性初始化，重跑非空库会拒绝。平台存量升级仍使用三份 `iws_plan_event_*` 脚本。执行与验证见后端 `docs/iws-initialization.md`，真实库写入仍需任务授权。

项目按标准化建设，不承担历史版本数据兼容（见 [agent.md 兼容边界](../agent.md)）。结构确需变更时仍按 sql-idempotent-upgrade 交付可重复执行的脚本，并保持同一版本应用与脚本的分批上线可行；不因不兼容旧数据而改成一次性删改。

| 概念 | 当前模型 |
| --- | --- |
| 库区、储位 | `wms_warehouse_zone`、`wms_storage` |
| 容器、类型、格口 | `wms_container`、`wms_container_type`、`wms_compartment` |
| 位置权威 | `wms_container.storage_id`，唯一索引 `uk_wms_container_storage` |
| 物料、供应商 | `wms_material`、`wms_supplier` |
| 当前库存 | `wms_inventory`，唯一库存单元 `stock_unit_code` |
| 预留 | 库存字段 `reserved_qty/reserved_order_no/reserved_order_line_id` |
| 事实流水 | `wms_inventory_flow`，幂等键 `idempotency_key` |
| 计划和执行单据 | `wms_inbound_order/_line`、`wms_outbound_order/_line` |
| 归档与推送 | 库存和单据 history 表，入出库 order_push_log 表 |

库存保留位置、物料与供应商快照，不能让快照变成第二套位置权威。当前采用逻辑关联和明确索引，未默认建立物理外键；独立位置、批次、预留和复杂扣留表不属于当前核心模型。

状态值以目标表字段、枚举、字典和必要的真实数据互证，不套用旧设计的统一枚举。库存数量使用 decimal；历史库存不参与当前可用库存计算。

SQL 文件只能证明仓库结构设计，不能证明部署状态。实际核验需唯一确定生效 profile 和目标库；无只读连接时说明静态验证范围。升级脚本在确需变更时按 sql-idempotent-upgrade 指引准备，不扩展为数据库写入。

APK 多版本记录使用 `sys_apk_release`，唯一键为包名与 versionCode，已发布版本通过计划事件本地事务写入状态和实例 ID。升级入口为后端 `script/sql/iws_apk_management.sql`；完整空库 SQL 已内联该表及 5 项菜单，当前终验为 124 表、230 菜单，新库不再单独执行 APK 升级。2026-10-26 用户确认本机 dev 目标为 `localhost:3306/innolux-iws`，与框架标准名区分；三个服务配置已对齐，其他环境按实际连接核对。

模型和约束发生实质变化时，同步更新本文件及底层变更历史，保留新旧结构和验证证据，不将 IWS 特有表名推广到全局原版。
