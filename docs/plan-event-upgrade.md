# IWS 计划事件与系统接入配置数据库升级说明

日期：2026-10-22

目标数据库：MySQL 8.0，单库参数位于各 SQL 文件头部

仓库：`C:/code/company/innolux-iws`

从零部署整套系统使用[全量初始化说明](iws-initialization.md)及 `iws_init_from_empty.sql` 配套建库入口；本文三份脚本用于已完成框架初始化的目标库，不等同于整套系统初始化。

## 1. 交付范围

本次新增三个升级脚本：

| 文件 | 用途 |
| --- | --- |
| `script/sql/iws_plan_event_endpoint_schema.sql` | 创建 `iws-endpoint` 的系统、环境、共享地址、系统地址关联、目录控制行和配置审计表 |
| `script/sql/iws_plan_event_process_schema.sql` | 创建 `iws-process` 与 `iws-business_schedule` 相关的 16 张运行/配置表 |
| `script/sql/iws_plan_event_menu.sql` | 创建“系统接入配置”和“计划事件”菜单及按钮权限 |

脚本只负责表结构、endpoint 唯一目录控制行和菜单定义。它不创建真实系统、环境、地址、凭据、业务调度、计划、实例、事件、任务或调度平台配置，也不向任何角色授予菜单。

### endpoint 表

`iws_endpoint_system`、`iws_endpoint_environment`、`iws_endpoint_address`、`iws_endpoint_system_address`、`iws_endpoint_catalog_control`、`iws_endpoint_audit`。

关键约束：

- `system_code`、`environment_code` 唯一。
- 地址的 `(environment_code, endpoint_ref)` 唯一。
- 系统地址关联的 `(system_code, address_id)` 唯一。
- 配置审计的 `command_key` 唯一，用于命令幂等回执。
- `iws_endpoint_catalog_control` 只初始化一行 `CATALOG`，初始 `revision=0`；该行用于 endpoint 写事务独占锁、process 读事务共享锁的固定顺序。

### process 表

| 表 | 作用 |
| --- | --- |
| `iws_business_schedule` | 业务统一调度根配置 |
| `iws_business_schedule_plan_type` | 业务调度下的计划类型、优先级和启停 |
| `iws_process_definition` | 计划版本、角色、发布状态与监督参数 |
| `iws_process_system_policy` | 计划版本内逐系统过账模式 |
| `iws_process_state` | 版本内状态定义 |
| `iws_process_event_definition` | 单动作事件定义 |
| `iws_process_edge` | 事件依赖、分支和汇合边 |
| `iws_process_instance` | 业务计划或长期监督实例 |
| `iws_process_system_context` | 实例冻结系统模式与首次确认事实 |
| `iws_process_token` | 分支 token 与 scope 协调记录 |
| `iws_process_event` | 事件运行事实、租约、恢复与结果 |
| `iws_process_attempt` | 单动作尝试和检查点 |
| `iws_process_trigger_receipt` | 入站触发、结果回执与来源幂等 |
| `iws_process_request_detail` | 入站/出站请求观测明细 |
| `iws_process_action_audit` | 追加式领域审计和命令回执锚点 |
| `iws_process_schedule_control` | 业务、监督、PLAN_SLOT 和 CALL 控制 |

关键约束包括：

- 计划版本 `(plan_code, plan_version)`、`active_key`、`latest_key` 唯一。
- 系统策略 `(definition_version_id, system_code)` 唯一。
- 状态 `(definition_version_id, state_code)` 唯一。
- 事件定义 `(definition_version_id, event_code)` 唯一。
- 分支边 `(definition_version_id, predecessor_event_code, successor_event_code, branch_key)` 唯一。
- 业务实例使用 `business_task_key` 唯一，取值由服务按 `businessType:taskKey` 写入；监督实例使用 `supervisor_key` 唯一，取值由服务按 `SUPERVISOR:scheduleId:planType:scope` 写入。两类键在另一角色下为 NULL，因此同一 scope 可挂多个 BUSINESS，同时每个监督 scope 只有一个监督实例。
- 实例系统上下文 `(instance_id, system_code)` 唯一。
- token `(instance_id, scope_key, branch_key)` 唯一。
- 事件 `(instance_id, event_key)` 唯一。
- attempt `(event_id, attempt_no)` 唯一。
- trigger receipt 的来源四元组唯一。
- 请求明细 `request_id` 唯一。
- action audit 的 `receipt_key` 唯一。
- schedule control 的 `(scope_type, scope_key, duty)` 唯一；PLAN_SLOT 另有 `(supervisor_instance_id, child_instance_id, duty)` 唯一。

process 的实体持久化 Mapper 使用 `BaseMapperPlus`；执行查询另由只读的 `ProcessExecutionQueryMapper.xml` 提供分页与详情 SQL。本次 DDL 已按 16 个实体字段、`BaseEntity` 公共字段、查询 Mapper 引用的实例/事件/请求字段及冻结契约 `iws-process/docs/process-contract.md` 对齐。`originOutboundEventId` 对应 `origin_outbound_event_id` 并保留普通索引；恢复 Worker 的 `execution_purpose + event_status + recovery_deadline` 扫描另使用复合索引 `idx_iws_process_event_recovery`。后续若加入自定义 Mapper SQL 或实体字段，必须先同步扩展本脚本的结构核验定义。

## 2. 不变边界

- 2026-10-23 重新解析当前 `application.yml` 的 dev Profile，生效 master 为 `localhost:3307/innolux_iws`；MCP 的首条 `SHOW GRANTS` 因无匹配连接被拒绝，尚未取得该目标的结构证据。早期 `localhost:3306/innolux_iws` 的只读核验是历史快照，不能替代当前目标。3306/3307 均未执行 DDL/DML。
- 不修改应用数据源账号，不复制应用凭据，不写入真实 IP、系统映射、环境、端口、任务编号或调度参数。
- 不创建 SnailJob 任务，不启停调度，不启动或停止业务服务。
- 不授予任何角色菜单；菜单写入后由实际角色配置流程决定授权范围。
- 不生成 DROP、TRUNCATE、数据清理或自动回滚脚本。

## 3. 单目标库入口与执行器

每个 SQL 文件只有一处目标库配置：

```sql
SET @upgrade_schema = 'innolux_iws';
```

执行时必须让当前连接数据库与该项一致。推荐的 mysql 客户端调用如下，账号和密码由部署人通过现有安全流程提供：

```powershell
& mysql.exe --protocol=TCP -h localhost -P 3306 -u <部署账号> -p `
  --database=innolux_iws --batch --raw `
  --execute="source C:/code/company/innolux-iws/script/sql/iws_plan_event_endpoint_schema.sql"
```

三个文件应使用同一执行器、同一会话顺序执行。不要添加 `--force`。执行器必须在首个错误处停止。

脚本同时采用两层失败控制：

1. 前置结构冲突会输出中文冲突明细并触发无效 JSON 错误（MySQL 3141），阻止依赖写入。
2. 即使客户端被配置为继续执行，所有持久化 DDL/DML 仍由 `target_ok`、`precheck_ok`、`preflight_ok`、`ddl_chain_ok`、`post_ok` 等变量门控。每张表创建后必须确认目标表实际存在，下一张表的创建门控才继续为真；首张 DDL 失败后，后续表不会继续创建。

## 4. 前置条件与执行顺序

执行前确认：

- MySQL 为 8.0。
- 目标 schema 存在，默认排序规则为 `utf8mb4_general_ci`。脚本核验的是目标 schema 的 `DEFAULT_COLLATION_NAME`，不要求整个 MySQL 实例统一使用该排序规则。
- `lower_case_table_names` 为 MySQL 合法值 `0/1/2` 均可；脚本另外按字节比较对象名，只接受精确的小写表名，避免在 Linux `=0` 环境创建同名大小写变体。
- endpoint 升级完成后再执行 process 升级；process 脚本要求 endpoint 6 张表已存在。
- 菜单脚本要求 `sys_menu` 已存在且必需列、类型和 `menu_id` 主键符合当前 IWS 结构。
- 执行账号具备目标 schema 的 CREATE/ALTER/INDEX 和 `sys_menu` INSERT 权限。权限由部署侧提供，脚本不切换账号。

执行顺序：

1. `iws_plan_event_endpoint_schema.sql`
2. `iws_plan_event_process_schema.sql`
3. `iws_plan_event_menu.sql`

每个文件独立完成目标核验、结构预检、缺失对象创建和后置核验。进程中断后按第 6 节恢复，不要跳过前序文件。

## 5. 结构定义核验

脚本不是只执行 `CREATE TABLE IF NOT EXISTS`。对每个已存在的目标表，脚本比较：

- 表类型、`ENGINE=InnoDB`、表排序规则。
- 列的精确顺序、`COLUMN_TYPE`、`IS_NULLABLE`、默认值、`EXTRA`、字符集和排序规则。
- 必需索引的索引名、唯一性、列顺序、列名、前缀长度 `SUB_PART` 和索引类型。
- 未登记列和未登记索引。
- 大小写近似的对象名。
- endpoint 目录控制行是否只有合法的 `CATALOG` 行，且 revision 非负。
- `sys_menu` 必需列、类型、可空性和 `menu_id` 主键。

已存在但定义不一致时，脚本在创建任何缺失对象前失败，并输出具体表、列或索引冲突。脚本不会自动 ALTER、DROP、重建或覆盖既有表。需要修正真实结构时，应先由数据库负责人确认对象归属、历史数据和回退方式。

## 6. 重复执行、冲突与失败恢复

### 重复执行

- 首次执行：创建缺失表、endpoint 唯一控制行和缺失菜单。
- 再次执行：已有且定义一致的表、控制行和菜单保持原值；缺失对象才创建。
- endpoint 控制行的 revision 不会因重跑重置。
- process 表不写入任何初始业务行。
- 菜单脚本只在菜单 ID 不存在且自然键未占用时插入；不会重置既有菜单的显示状态、顺序或权限。

### 预期冲突

下列情况会失败且不自动修复：

- 同名表为视图、引擎/排序规则不同、少列、多列或列定义不同。
- 唯一索引或普通索引缺失、列顺序不同、唯一性不同或存在额外索引。
- 对象名仅大小写不同。
- endpoint 控制行缺失后存在非法控制行，或 `CATALOG` revision 为负。
- 菜单 ID 已被占用但菜单字段不同。
- 菜单自然键（父级、类型、路径或权限）已被其他菜单 ID 占用。
- `sys_menu` 必需列缺失或类型不符。

错误输出中的 `ERROR 3141 ... Invalid JSON text` 是脚本主动制造的失败信号，不是 JSON 数据损坏。根据前面的中文冲突结果处理，不要删除该失败信号。

### 中断恢复

1. 检查目标 schema 中已经创建的表和索引。
2. 确认失败点对应的定义冲突或权限问题。
3. 已存在且定义一致的对象无需删除。
4. 修复外部权限或经批准修正冲突结构后，重跑同一脚本。
5. 重新执行后置核验，不把“脚本退出码为 0”单独作为成功依据。

DDL 会隐式提交，脚本不承诺多表整体回滚。不要用 DROP 已建表的方式制造“干净状态”。菜单插入是单条 INSERT SELECT，在 InnoDB 下原子提交；失败后重跑即可。

## 7. 菜单与权限目录

菜单脚本共插入 53 行：2 个目录、8 个页面（其中 2 个隐藏路由）、43 个按钮权限。ID 范围为 `1761400000000030001` 至 `1761400000000031043`。不写 `sys_role_menu`。

### endpoint

| 页面/权限 | 权限码 |
| --- | --- |
| 系统定义列表 | `endpoint:system:list` |
| 系统查询/新增/修改 | `endpoint:system:query`、`endpoint:system:add`、`endpoint:system:edit` |
| 环境配置列表 | `endpoint:environment:list` |
| 环境查询/新增/修改 | `endpoint:environment:query`、`endpoint:environment:add`、`endpoint:environment:edit` |
| 共享地址列表 | `endpoint:address:list` |
| 地址查询/新增/修改 | `endpoint:address:query`、`endpoint:address:add`、`endpoint:address:edit` |
| 配置审计查询 | `endpoint:audit:query` |

### process

| 页面/权限 | 权限码 |
| --- | --- |
| 业务调度列表 | `process:schedule:list` |
| 调度查询/新增/修改/删除 | `process:schedule:query`、`process:schedule:add`、`process:schedule:edit`、`process:schedule:remove` |
| 监督暂停/恢复/重启 worker | `process:schedule:pause`、`process:schedule:resume`、`process:schedule:restart` |
| 计划定义列表 | `process:definition:list` |
| 定义查询/新增/修改/复制/发布/删除 | `process:definition:query`、`process:definition:add`、`process:definition:edit`、`process:definition:copy`、`process:definition:publish`、`process:definition:remove` |
| 执行总览 | `process:execution:list` |
| 实例查询/执行链 | `process:instance:query`、`process:instance:chain` |
| 补挂/首次确认 | `process:instance:attach`、`process:instance:confirm` |
| 实例暂停/恢复/取消 | `process:instance:pause`、`process:instance:resume`、`process:instance:cancel` |
| 挂起/放行/优先级/即时执行 | `process:instance:hold`、`process:instance:release`、`process:instance:priority`、`process:instance:execute` |
| 触发受理 | `process:trigger:receive` |
| 事件查询/重试/重新校验/最终失败/插队 | `process:event:query`、`process:event:retry`、`process:event:revalidate`、`process:event:finalize`、`process:event:priority` |
| 请求报文 | `process:event:request` |
| 审计时间线 | `process:audit:query` |
| 抛账记录列表/详情 | `process:request:list`、`process:request:query` |

`process:request:list` 与 `process:request:query` 分别对应 `ProcessExecutionQueryController` 的 `GET /process/requests/page` 和 `GET /process/requests/{requestId}`。静态核对当前 Controller 的 50 个唯一 endpoint/process 权限与菜单权限集合完全一致。

菜单定义不替代 API 鉴权和对象范围校验。角色授权应由部署侧按“计划管理员、运行人员、运行主管、审计只读”等实际职责单独配置。

## 8. 默认关闭与分批部署

本次 SQL 不修改任何应用开关或调度开关。所有新增能力默认关闭：

```text
iws.endpoint.enabled=false
iws.process.enabled=false
iws.process.scheduler.enabled=false
```

启用 process 前必须同时满足：endpoint 已启用、endpoint 依赖可用、`iws.endpoint.current-environment` 指向已存在且启用的环境。该环境值由部署配置提供，本 SQL 不写入任何真实值。

建议分批上线：

1. 完成备份、变更窗口和数据库账号授权确认。
2. 先把两个模块保持关闭，依次执行 endpoint、process、menu 脚本。
3. 部署包含 endpoint/process 的应用构建；保持 endpoint 和 process 均关闭，确认旧功能不受影响。
4. 仅在 endpoint-only 场景启用 `iws.endpoint.enabled=true`，验证系统、环境、地址页面和目录审计；process 仍关闭。
5. 验证菜单能力过滤后，按角色授予菜单；不要直接复制生产角色权限。
6. 配置有效当前环境后，再启用 process，保持 `iws.process.scheduler.enabled=false`。验证配置查询、空目录/无业务计划和启动门禁。
7. 调度启停、真实系统地址、业务映射、计划发布和任务执行另立变更窗口，必须获得单独授权。

## 9. 后置验收

### endpoint

```sql
SELECT table_name, engine, table_collation
FROM information_schema.TABLES
WHERE table_schema = 'innolux_iws'
  AND table_name LIKE 'iws_endpoint_%'
ORDER BY table_name;

SELECT singleton_key, revision
FROM innolux_iws.iws_endpoint_catalog_control;
```

首次部署应看到 6 张表、恰好一行 `CATALOG`，初始 revision 为 0。业务系统、环境、地址和审计表应保持空表；后续配置由 endpoint 页面按授权录入。

### process

```sql
SELECT table_name, engine, table_collation
FROM information_schema.TABLES
WHERE table_schema = 'innolux_iws'
  AND (table_name LIKE 'iws_process_%'
       OR table_name LIKE 'iws_business_schedule%')
ORDER BY table_name;

SELECT COUNT(*)
FROM information_schema.TABLES
WHERE table_schema = 'innolux_iws'
  AND (table_name LIKE 'iws_process_%'
       OR table_name LIKE 'iws_business_schedule%');
```

首次部署应看到 16 张表；所有业务行保持为空。核对唯一键和索引时以脚本后置核验结果为准，不只看表数量。

`iws_process_event` 应同时存在 `idx_iws_process_event_origin(origin_outbound_event_id)` 和 `idx_iws_process_event_recovery(execution_purpose,event_status,recovery_deadline,id)`。

### 菜单

```sql
SELECT menu_id, menu_name, parent_id, menu_type, perms
FROM innolux_iws.sys_menu
WHERE menu_id BETWEEN 1761400000000030001 AND 1761400000000031043
ORDER BY menu_id;

SELECT COUNT(*)
FROM innolux_iws.sys_menu
WHERE menu_id BETWEEN 1761400000000030001 AND 1761400000000031043;
```

本次菜单应为 53 行；脚本不新增角色菜单关系。重复执行时不应出现重复 ID 或重复自然键。

### 应用验收

- 两个模块均关闭时应用可正常启动，旧菜单/旧业务不受影响。
- endpoint-only 时三页 API、菜单、权限和审计可用。
- endpoint 关闭而 process 开启时应用明确拒绝启动，不退化为空目录或自动 BYPASS。
- process 开启但当前环境缺失/停用/不存在时明确拒绝启动。
- 调度关闭时配置与查询可用，不创建或启停真实任务。
- 不把 SQL 成功、编译成功或接口桩成功说成真实过账、调度并发或浏览器验收通过。

## 10. 隔离验证记录

验证实例：

```text
127.0.0.1:13307
MySQL 8.0.45
datadir=C:/Users/yohmi/.codex/visualizations/2026/10/22/01a16176-b64c-78b2-9aa8-33beed46c04d/mysql-isolated/data
lower_case_table_names=1
character_set_server=utf8mb4
collation_server=utf8mb4_general_ci
```

只在上述隔离实例创建 fixture，未连接 3306/3307 写入。

已执行并通过：

| 项目 | 结果 |
| --- | --- |
| endpoint 首跑 | 6 表、唯一键、索引和 `CATALOG/revision=0` 控制行创建成功 |
| endpoint 重跑 | 结构一致，无重复控制行，无新增写入 |
| endpoint 冲突 | 错误列和错误索引在预检阶段阻断，未创建其他 endpoint 表 |
| endpoint 部分恢复 | 已存在 2 张正确表、其余缺失时可补齐至 6 张 |
| process 首跑 | 16 张表创建成功 |
| process 重跑 | 16 张表结构复核无冲突 |
| process 实体对齐 | 16 个实体字段（含 `BaseEntity`）与数据库列差异为 0 |
| 请求查询 Mapper | 请求分页/详情投影所列字段均可执行，包含实例关联字段；空关联请求记录返回正常 |
| 恢复索引选择 | `iws_process_event` 写入 2000 行并执行 `ANALYZE TABLE` 后，恢复队列查询使用覆盖索引 `idx_iws_process_event_recovery` |
| CAST 排序规则 | 计划候选 `EXISTS` 查询使用 `CAST(i.id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_general_ci` 可执行，未再出现 `utf8mb4_0900_ai_ci` 冲突 |
| 菜单权限对齐 | Controller 的 50 个 endpoint/process 权限与菜单 50 个权限完全一致，缺失和多余均为 0 |
| 新增权限补齐 | 既有 51 行菜单重跑后补齐 `process:request:list`、`process:request:query`，最终为 53 行 |
| process 冲突 | 既有字段/索引错误时在创建前阻断，新增 process 表为 0 |
| process 部分恢复 | 已存在 5 张正确表、其余缺失时可补齐至 16 张 |
| menu 首跑 | 53 行菜单创建成功，ID 无精度丢失 |
| menu 重跑 | 不重复插入，预检/后置一致 |
| menu 冲突 | 既有菜单 ID 字段不一致时预检阻断，未插入其余菜单 |
| 索引前缀冲突 | endpoint 地址唯一键改为 `endpoint_ref(8)`、process token 唯一键改为字符串前缀后，两脚本均识别 `SUB_PART` 差异并阻断 |
| DDL 失败链控制 | 强制令首张 CREATE 失败并让 mysql 客户端 `--force` 继续执行；endpoint/process 均只出现 1 个 DDL 错误，后续表创建数为 0 |
| 角色唯一键 | 同一 scope 可挂 2 个 BUSINESS；重复 `business_task_key` 被拒；首个 SUPERVISOR 成功，重复 `supervisor_key` 被拒 |
| 最终干净库复跑 | 从空 schema 连续执行三份脚本并整体重跑一轮，6 次退出码均为 0；22 张表、唯一控制行 `CATALOG:0`、53 行菜单，endpoint 系统与 process 计划业务行均为 0 |

隔离验证覆盖 MySQL 8.0 方言、唯一键、索引、重复执行、结构冲突和部分状态恢复。它不替代真实目标库变更审批、应用启动、Sa-Token/菜单能力过滤、浏览器或调度平台联调。

## 11. 未完成与限制

- 未在真实目标 `localhost:3307/innolux_iws` 或早期核验的 3306 执行任何写入；当前真实目标只读结构核验受连接匹配限制。
- 已在隔离环境验证 endpoint HTTP 与浏览器环境新增回读；process 启停组合及菜单过滤有专用测试。它们不等于真实目标应用上线验收，最终证据见实施验收记录。
- 未创建或启停 SnailJob 任务，未验证真实调度重试、租约或控制平台。
- 未配置真实系统、环境、地址、凭据或业务计划。
- 未授予角色菜单，未验证实际岗位权限。
- 未提供自动回退脚本；回退应用版本不等于回退数据库，任何删表、删索引或数据恢复都必须结合备份和单独授权。
