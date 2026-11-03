# 在库管理重建后数据库基线（2026-10-27）

## 当前基线及适用范围

基线ID：`inventory-db-20261027`。2026-10-27 08:24前后（Asia/Shanghai）通过只读MCP采集。用户已说明数据库重建，本次以重新查询的真实结构和聚合状态为当前依据；[2026-10-26证据](inventory-management-database-evidence-20261026.md)仅保留历史，不再代表当前库。

数据源从产品工作区 `C:/code/company/innolux-iws` 当前配置解析：`application.yml` 的 `spring.profiles.active=dev`，`application-dev.yml` 的 `spring.datasource.dynamic.datasource.master` 指向 `localhost:3306/innolux-iws`。查询连接为 `mysql-local`，首条查询严格为 `SHOW GRANTS`，`codex@localhost` 仅有SELECT、SHOW DATABASES、SHOW VIEW；未执行DDL、DML、迁移、业务接口、调度命令或设备调用。

完整可机读证据见[数据库基线JSON](baselines/inventory-db-baseline-20261027.json)。它仅含结构、表名、数量与脱敏业务状态，不含应用凭据、原始报文、APK文件路径或人员明细，不是数据库数据备份。

## 数据库与采集完整性

| 项目 | 当前证据 |
| --- | --- |
| MySQL | 8.0.45，MySQL Community Server - GPL |
| schema默认 | utf8mb4 / utf8mb4_bin |
| server默认 | utf8mb4 / utf8mb4_0900_ai_ci |
| 会话隔离级别 | REPEATABLE-READ；每次独立查询，不是跨查询一致性事务快照 |
| 时区 | session.time_zone=SYSTEM；system_time_zone返回乱码，未据此推断业务时区。业务时区仍按初始化值表要求明确配置 |
| 表名大小写 | lower_case_table_names=1 |
| 全库对象清单 | 124张BASE TABLE、0视图；对象查询124行未截断，均为InnoDB |
| WMS | 20表、487列、135个索引（265个索引列部分），全部逐表COUNT为0；WMS表排序规则均为utf8mb4_0900_ai_ci |
| endpoint/process | 22表、482列、96个索引（178个索引列部分） |
| APK | sys_apk_release为17列、4个索引（7个索引列部分） |
| 本次结构全量范围 | 上述43表共986列，按稳定table_name/ordinal_position分页250+250+250+236行；450个索引列部分一次返回未截断 |
| 约束 | 43表共110条主键/唯一键列记录；按约束名计43主键、67唯一约束，无外键、无CHECK；全库触发器查询0行 |
| 准确行数范围 | WMS20表、endpoint/process22表、APK及5张框架表，共48表准确COUNT；未采集其他表的数据内容 |

列元数据包含类型、空值、默认值、字符集/排序规则、主键标记、extra和注释；索引包含顺序、唯一性、前缀长度和类型。分页完整性以全部页及最后236行确认，不能将中间页的truncated=false当作总量完整。

结构指纹算法：对JSON中的43表元数据、完整列、索引、约束、CHECK、触发器，固定键 `tables/columns/indexes/constraints/checks/triggers`，按当前已排序数组，用 `ensure_ascii=False, sort_keys=True, separators=(',', ':')` 序列化为UTF-8后取SHA256：`00027f7532006c679e7ae6164937547645378c498614c1a5fe206299a74687a0`。该指纹覆盖明确范围，不冒充全库DDL指纹；后续扩展采集字段或算法需新指纹版本。

## 必须保留的当前业务事实

| 对象 | 当前状态 |
| --- | --- |
| WMS主数据/库存/单据/历史/流水 | 20表均为空；不代表其他业务为空 |
| 业务调度 | APK_RELEASE启用；允许APK_PUBLISH，类型启用，priority=0 |
| 计划定义 | APK_RELEASE_PUBLISH v1，BUSINESS，ACTIVE，revision=2；APK_RELEASE_PUBLISH_SUPERVISOR v1，SUPERVISOR，ACTIVE，revision=2，IMMEDIATE |
| 计划实例 | BUSINESS/COMPLETED=3；SUPERVISOR/RUNNING=1，共4实例 |
| 执行事实 | event=3，attempt=3，token=3，schedule_control=5，action_audit=24；request_detail与trigger_receipt均为0 |
| APK发布记录 | PUBLISHED=3，三条force_update均为1；这里只统计状态，不读取安装包或下载日志 |
| endpoint目录 | 环境TEST启用/revision=0，系统IWS启用/revision=0，CATALOG revision=2；地址/系统地址均0，目录审计0 |
| 框架数量 | sys_menu=231，sys_dict_type=13，sys_dict_data=55，sys_dept=10，sys_role=3 |

因此不能重跑空库初始化，也不能将process表清零、重建APK计划或把CATALOG改回revision=0。运行中的监督实例需要后续升级按真实状态核对和授权处理，本次没有暂停它。

实际菜单231与源初始化脚本终验230不同，说明当前库已含初始化之外的变化；本次没有采集231条菜单明细或认定多出项的来源，不擅自删除或补齐。运行表与审计表可能继续变化，执行升级前必须重读数量、版本及任务状态。

## 现有WMS约束与目标差距

原五张重点表仍为128列、36个索引，完整明细已纳入新JSON。库区仍使用可空warehouse_code/parent_zone_id；储位zone_id必填且无depth_no；容器storage_id保留唯一索引uk_wms_container_storage且没有嵌套父容器字段；库存数量与预留为decimal(20,6)，生产/位置仍内联；库存流水idempotency_key唯一。

公司、独立仓库、规则版本、统一装载关系、独立分配锁与占用、物料trace头/item/家谱、扩展类型目录等方案目标表未出现在完整对象清单。尚不能认为方案或默认配置已经部署；冻结/解冻业务仍不在本期范围，既有字段只读保留。

## 当前初始化SQL源基线

源码基线为产品main `e9361ae58d66440155b39b72affee13b79025600`，采集时git status为空。方案工作区仍为独立分支 `codex/inventory-design-20261026`，并未自动合并该产品提交；后续实施必须将计划与当前产品源码重新核对。

| 文件 | 当前SHA256 | 字节 | HEAD blob |
| --- | --- | ---: | --- |
| script/sql/iws_init_from_empty.sql | b59424862c3d231bbc753d3127a5df2ea85963f02d023eedf52e3acbfcc150e2 | 533577 | ad0c46b3ed477553f8b4877333fed229d897fb11 |
| script/sql/iws_wms_smt_core.sql | 98bf277b32ae16fecb3fff4bfcfc56307118a622053499cc99ca5364ce9ae709 | 76368 | 0df70bc553dac84939263c3929e10f796e25c605 |
| script/sql/iws_init.sql | 87671be8133358527e7c6fac61b17e66b53fe8c8dcfd08eeda32a9f353397daf | 204217 | e325aab32205b33291a2a52d9f097f841bc0801f |

三文件均与该HEAD一致，无dirty差异。与2026-10-26保存的工作树字节相比，仅完整空库脚本hash变化，WMS核心SQL及框架SQL的hash相同；这只是源文件比较，不证明用户具体使用了哪一份脚本重建。

当前完整脚本自带终验仍是124表、230菜单（`:5714-5715`），真实库231菜单及非空APK/process状态须单独承接，不能套用“纯初始化种子”断言。当前SQL字节快照和source-manifest.json保存在仓库外任务产物目录 `database-rebaseline-20261027/sql-source/`，历史字节快照未覆盖；该快照可能含原初始化模板凭据，不纳入审阅ZIP或提交。

## 后续升级使用规则

1. 当前开发库以本基线为起点；2026-10-26基线保留用于历史来源回归，不再用于判定当前数据库状态。
2. 结构预检对照本JSON覆盖的43表；其余框架表只有对象清单，不得据此宣称全列/全索引一致。实际影响其他表时再最小化补采。
3. 迁移前再次检查数据源、权限、源码hash、结构指纹及活动实例；本次串行查询不能替代写冻结后的稳定备份。
4. WMS空表可简化本开发库的回填演练，但升级脚本仍须支持已明确的旧库数据承接合同；APK及process运行事实不能删除、覆盖或重放。
5. 分别验证新空库初始化、当前重建库结构加脱敏运行fixture后的幂等升级，以及旧源基线回归；重复执行不能重复种子或业务副作用。
6. 本次只读结果不能证明事务写入、并发锁、性能或升级脚本成功；这些仍属隔离实施验收。未执行任何数据库变更。

部分元数据语句被MCP拒绝：SHOW VARIABLES带WHERE未受支持，随后使用单条SELECT读取明确系统变量成功。system_time_zone乱码原样保留在JSON，未修饰为可靠时区信息。
