# 计划事件公共平台操作指南

日期：2026-10-23

适用范围：IWS `iws-endpoint`、`iws-process` 公共平台及对应 Web 页面，供配置、运行和排障使用。

代理接入任务按需使用 [项目版 iws-plan-event-project](C:/code/company/innolux-iws/.agents/skills/iws-plan-event-project/SKILL.md)；项目版安装在当前 IWS 后端仓库的 `.agents/skills`，全局 `iws-plan-event` 保留未修改。平台验证边界见 [验收记录](plan-event-verification.md)。

当前真实业务链路尚未接入；`iws.endpoint.enabled`、`iws.process.enabled`、`iws.process.scheduler.enabled` 默认均关闭。本文按当前 Controller、service、Web 页面和升级脚本整理，不代表已经上线验收。

## 1 配置前置

### 数据库脚本

| 顺序 | 文件 | 内容 |
| --- | --- | --- |
| 1 | `script/sql/iws_plan_event_endpoint_schema.sql` | 6 张 endpoint 目录表、唯一 `CATALOG` 控制行和配置审计表 |
| 2 | `script/sql/iws_plan_event_process_schema.sql` | 16 张 process 运行、配置和调度控制表；要求 endpoint 表已存在 |
| 3 | `script/sql/iws_plan_event_menu.sql` | 53 行菜单：2 个目录、8 个页面、43 个按钮；不授予角色 |

三个脚本在同一 MySQL 8.0 目标库和同一执行器会话中按顺序执行。当前目标库参数为 `@upgrade_schema = 'innolux_iws'`，连接库须与目标库一致；schema 默认排序规则须为 `utf8mb4_general_ci`，表名大小写、列顺序、索引和唯一键均会校验。

脚本可重复执行；结构冲突时停止并输出中文原因，不自动 `ALTER`、`DROP`、重建或覆盖，也不要添加 `--force`。DDL 会隐式提交，中断后先核对已建对象，再从同一脚本继续。菜单脚本只写 `sys_menu`，不写 `sys_role_menu`。

### 启用顺序

1. 备份并确认变更窗口。保持三个开关关闭，执行 endpoint、process、menu 脚本。
2. 部署包含 endpoint/process 代码的应用构建，继续保持 `iws.endpoint.enabled=false`、`iws.process.enabled=false`、`iws.process.scheduler.enabled=false`，确认原有功能不受影响。
3. 启用 `iws.endpoint.enabled=true`，保持 process 和调度关闭，检查系统定义、环境配置和系统地址页面。
4. 创建并启用目标环境，把部署配置 `iws.endpoint.current-environment` 设置为该环境码，然后重启应用。页面选择或编辑环境不会切换运行时环境。
   空库初始化已登记 `TEST`（测试环境），也可直接把当前环境设为 `TEST` 先完成联调；已有库缺少默认环境时，在环境配置补一行 `TEST` 并启用。
5. 启用 `iws.process.enabled=true`，继续关闭 `iws.process.scheduler.enabled`。启动时会校验 endpoint Reader 和当前环境；环境缺失、停用或 endpoint 实现缺失时直接拒绝启动。
6. 为岗位授权，并接通对象范围、入站身份和业务能力。对象范围策略缺失时，普通用户即使有页面权限也会被拒绝；`process:object:all` 仅用于独立的管理员全范围授权。
7. 配置业务调度和计划定义，完成发布检查与空数据检查后，再按业务窗口启动作业。
8. 只有 `iws.process.enabled=true`、`iws.process.scheduler.enabled=true`、`snail-job.enabled=true` 同时成立，job 侧才注册监督执行器和 `QUEUE` 事件 worker。`iws.process.scheduler.control-batch-limit` 与 `control-poll-interval-ms` 已在 `application.yml` 提供正数默认值，按部署规模调整即可。空库初始化已建好命名空间 `dev`/`prod` 与组 `iws_group`，组令牌与 `snail-job.token` 一致；已有库需在 SnailJob 后台组管理补建，否则执行器注册失败、任务无法分派。

`IMMEDIATE` 另需正数 `iws.process.immediate.max-concurrency` 和 `iws.process.immediate.response-reserve-ms`；缺少任一项时即时执行返回 `NOT_READY`，不会自动转为排队执行。

菜单脚本生成 50 个 endpoint/process 权限码。为岗位逐项授予实际需要的完整权限码，覆盖目录配置、计划编辑与发布、实例操作、监督控制、事件恢复、请求记录和审计；斜线只表示同前缀下的多个完整权限码。

## 2 目录配置

入口：`系统接入配置` > `环境配置`、`系统定义`、`系统地址`。

1. 新增环境，填写唯一环境码、名称、说明和启用状态。环境码创建后不可修改；停用只阻止新解析，不切换当前环境，也不取消已发生的调用。
2. 新增系统，填写稳定系统码、名称、说明和启用状态。系统码创建后不可修改；停用阻止新解析和新准入，历史引用与已发生调用保留。页面不提供系统删除。
3. 新增共享地址。环境与端点引用组合唯一；协议仅限 HTTP/HTTPS；主机只填域名、IPv4 或 IPv6，不带协议、端口、路径、查询串或凭据；基础路径以单个斜杠开头；`凭据引用` 只填写现有安全配置的引用标识。
4. 在系统定义的 `关联地址` 中保存完整地址集合。地址可被多个系统共享；解除关联只影响后续解析，历史引用和已发送事实保留。
5. 修改系统、环境、地址或关联只影响后续新解析。已产生发送意图和 `UNKNOWN` 对账仍使用原环境、端点和 revision。发布和实际调用前，在 `外部系统与抛账模式` 核对地址摘要和启用状态；目录地址不代表外部系统可达。

## 3 计划、系统模式与发布

入口：`计划事件` > `业务调度`、`计划定义`。

### 业务调度

1. 新增业务调度，填写业务编码、名称、说明、公平窗口 K、启用状态和保存原因。业务编码在调度内唯一且创建后不可修改。
2. 在 `计划类型与优先级` 维护计划类型，用 `上移`、`下移` 调整顺序；越靠前优先级越高。保存时集合整体替换并产生新的调度 revision。
3. `公平窗口 K` 只对 `QUEUE` 有意义，必须与监督定义冻结值一致，否则报配置冲突。
4. 停用阻止新补挂和即时执行。删除会删除调度及计划类型；定义、实例和审计需单独处置，删除前确认没有在用版本和任务。

### 版本与发布

| 计划角色 | 必填与限制 |
| --- | --- |
| `BUSINESS` | 维护状态、事件、边和业务动作；不能配置监督模式、活动计划上限、公平窗口或即时期限 |
| `SUPERVISOR` | 必须为 `QUEUE` 或 `IMMEDIATE`；`activePlanLimit` 为正数，`IMMEDIATE` 时固定为 1，并配置正数 `maxExecutionMs` |

1. 新建草稿时选择计划角色。同一计划编码版本递增，只有最新 `DRAFT` 可编辑。
2. `ACTIVE` 是运行版本，`RETIRED` 已退役；发布版本只读。修改时复制为更高版本草稿。
3. 编辑页按基本信息、系统模式、状态定义、事件与边、分支与汇合、发布检查组织。保存携带当前 revision，冲突时拒绝覆盖。
4. 先执行发布检查，再发布。服务端会重新读取完整草稿校验；通过后草稿变为 `ACTIVE`，旧运行版本变为 `RETIRED`。
5. 发布校验覆盖完整图、状态和事件引用、能力注册及 endpoint 目录。启用的外部出站必须能解析到已登记、启用且已关联的地址；被 `BYPASS` 屏蔽的事件除外。

事件保持单动作：`INBOUND`/`OUTBOUND` 使用恰好一个调用合同，`INTERNAL` 使用恰好一个本地动作。能力键、版本、校验器和分支条件必须对应容器中真实注册的 Bean。已物化实例继续使用创建时冻结的版本、revision、状态和系统模式。

### 系统模式与 BYPASS

只有最新 `BUSINESS` 草稿可修改系统模式。入口：计划编辑页 > `系统模式` > `打开系统模式配置`。

1. 有策略归属的系统组成完整模式向量，一次预览和保存覆盖全部；仅入站来源或无策略归属的系统只展示，不随本次保存提交。
2. 为每个有策略归属的系统选择 `自动抛转`、`人工确认后抛转` 或 `屏蔽`，可补充首次确认策略、失败策略和说明。
3. 任一修改都会基于当前定义 revision、目录 revision、当前环境和完整向量重新预览；输入指纹未变化且预览通过后才能保存全量模式向量。
4. 预览只校验定义、目录和模式向量，不探活外部系统，也不发送请求。阻断问题或过期预览不可保存。
5. 保存需填写变更原因并携带 `expectedVersion`、`catalogRevision`、`environmentCode`。任一版本或当前环境不一致时，重新读取后再操作。

| `BYPASS` 限制 | 规则 |
| --- | --- |
| 作用范围 | 仅 `OUTBOUND` + `EXTERNAL_POSTING`，且目标系统是该事件系统、不是当前定义的本系统；入站、查询、控制和本系统出站不可屏蔽成功 |
| 可用前提 | 该系统至少有一个可屏蔽抛账事件；仅入站、查询或控制的系统不能配置 |
| 异步完成 | `ACCEPTANCE` 出站必须有下游入站结果事件，结果事件显式允许跳过并配置跳过合同 |
| 执行结果 | 跳过事件不产生 HTTP 调用，也不生成“外部完成”证据 |
| 恢复 | 必须明确改为 `自动抛转` 或 `人工确认后抛转`；“取消屏蔽”不等于自动成功 |

保存只更新当前草稿。新实例使用新向量仍需发布；已有实例的系统上下文冻结。

## 4 运行操作

### 补挂任务

入口：`计划事件` > `执行总览` > `补挂任务`。

1. 选择已启用调度下已登记且启用的计划类型，填写业务类型、任务唯一键和业务 ID；输入摘要和根集合可选，页面不自行计算摘要。
2. `挂起未开始` 保留已触发任务和等待事实，但不占计划名额，也不能绕过前驱、首次确认或资源门禁。
3. scope 由服务端按业务事实解析。补挂只受理 `QUEUE`；冻结监督为 `IMMEDIATE` 时返回 `MODE_MISMATCH`，应改用即时执行入口。
4. `businessType:taskKey` 是幂等键。相同摘要返回已有实例，不同摘要拒绝覆盖；监督暂停时，新补挂强制保持未开始。

### 首次确认

入口：`执行总览` 的运行中计划，或 `计划详情` > `确认系统`。

1. 仅列出冻结模式为 `MANUAL_CONFIRM`、当前可确认且尚未首次确认的系统；`AUTOMATIC`、`BYPASS` 和已确认系统不会出现。
2. 选择系统并填写原因，提交携带当前实例版本。服务端复核模式、系统上下文、实例版本和权限；冲突时刷新后重做。
3. 首次确认按“实例 + 系统”记录。确认后，同实例内该系统后续事件按冻结模式继续，不再重复确认。
4. 确认只解除首次确认门禁，不代表外部调用完成；事件仍需经过依赖、资源、结果和审计。无可用确认项时先核对冻结模式和 `first_confirmed`。

### 暂停与恢复

计划操作在 `执行总览` 或 `计划详情`；监督操作在 `业务调度` > `监督计划` > `监督详情`。

| 操作 | 范围与结果 |
| --- | --- |
| 暂停计划 | 停止新认领，保留正在执行的尝试和未知副作用。恢复要求计划为 `PAUSED`、仍持有 `PLAN_SLOT`，且所属监督未人工暂停 |
| 恢复计划 | 在暂停条件仍满足时恢复；不自动重放或补造已丢失的外部事实 |
| 取消计划 | 停止新认领并等待安全收敛；存在处理中、已发送未收敛或 `UNKNOWN` 事件时进入异常停留 |
| 暂停监督 | 将分派控制置为人工暂停；开放轮次中的未执行事件拒绝后安全关轮，不取消子计划 |
| 恢复监督 | 恢复期望运行状态，下一轮重新计算候选；不自动重放此前被拒绝的旧事件 |
| 重启工作进程 | 只补建或触发期望 Job；不重建子计划、不重置轮次、不重发未知出站；监督暂停时拒绝 |
| 切换监督版本 | 仅在监督暂停、无开放轮次、无 `PLAN_SLOT`、无未收敛平台控制、无在途或未知子计划时允许；目标须为同一调度和计划类型的已发布监督版本 |

监督详情的 `期望/观测` 是两列事实。控制意图已提交不等于 SnailJob 已应用；平台未返回修订号时，观测值不能标为已知修订。

### 请求记录与审计

入口：`执行总览` > `接口请求记录`。

- 可按请求、合同、业务结果、效果、实例、调用链、调度、业务 ID、任务键、计划关联和时间筛选。
- HTTP 200 不等于业务成功。结合 `effectResult`、完成合同、检查点和证据判断；`INTERNAL` 事件没有 HTTP 记录，应查看本地动作、检查点和审计。
- `请求安全详情` 不返回请求或响应摘要；单事件脱敏摘要从事件详情的 `接口请求` 页签查看，需要 `process:event:request`。
- 审计追加记录动作、操作者、结果、原因和关联对象；命令回执以 `receipt_key` 为锚点，重放同一命令不新增业务事实。

## 5 UNKNOWN 与检查点恢复

### 判断事实

在事件详情的 `执行概览`、`尝试/检查点`、`接口请求` 和 `审计` 中核对：

- `effectResult=UNKNOWN`，或已标记发送但最新检查点为 `SEND_INTENT`/`UNKNOWN`，按结果未知处理。
- 有成功效果且存在 `evidenceRef` 或 `checkpointJson`，才可能复用检查点收口。
- 只有成功效果、没有可复用证据时仍按未知处理，不能直接终结。
- `PROCESSING`、未到期租约或仍在途调用存在时，不能通过“租约过期”推断可以重发。

### UNKNOWN 恢复

1. 打开 `重试/恢复`，选择“仅恢复已有证据或原对账查询，不重发原出站、不重新执行业务本地动作”。
2. 原事件必须无活动租约；图中必须存在唯一已发布、方向为 `OUTBOUND`、用途为 `QUERY`、`recoveryForEventCode` 指向原事件的恢复事件。
3. 原实例须仍持有 `PLAN_SLOT`，原 `CALL_PERMIT` 须存在且未释放，定义须提供正数 `maxExecutionMs`。
4. 满足后，服务端激活该 `QUERY` 并标记 `RECOVERY_ONLY`，记录恢复期限和原事件关联。查询产生可信证据后，只让原事件排队补齐编排，不重发原出站。
5. 条件不满足时保留事件、实例和名额，补充对账证据或交由业务方处理。不要改库、手工释放名额或改用普通重试。

### 已有成功检查点

选择恢复语义后，服务端检查检查点证据和 `maxExecutionMs`，将事件放入只补编排的恢复路径，不重新执行原外部动作；缺少有限恢复预算时拒绝收口。

### 常见拒绝

| 提示或现象 | 处理 |
| --- | --- |
| `UNKNOWN 事件必须先对账或走已发布 QUERY 恢复分支，禁止盲目重试` | 重新勾选恢复语义；确认唯一 `QUERY` 恢复事件已发布 |
| `缺少唯一已发布对账 QUERY 事件，拒绝恢复` | 发布包含唯一 `QUERY` 恢复事件的版本 |
| `原实例未持有 PLAN_SLOT，拒绝恢复` | 核对监督准入和计划名额，不手工补名额 |
| `原 CALL 不存在或已释放，拒绝 QUERY 恢复` | 先补齐原调用资源事实，再判断恢复 |
| `检查点收口缺少有限 maxExecutionMs 恢复预算` | 修正定义并按规则重新发布，不无限等待 |
| `存在活动租约或未知副作用，不能最终失败` | 保留异常停留，完成对账 |

`最终失败` 只用于后端允许且没有在途或未知副作用的待人工/待重试事件；已有成功检查点的事件不能直接最终失败。

即时执行的 `RESULT_UNKNOWN` 或 `RESPONSE_TIMEOUT` 必须用原 `commandKey` 回查，不能生成新键重做。页面关闭后原键仍保留；只有 `SUCCEEDED` 才算成功。

## 6 QUEUE 与 IMMEDIATE

| 项目 | `QUEUE` | `IMMEDIATE` |
| --- | --- | --- |
| 任务入口 | `补挂任务`，由监督和事件 worker 推进 | 业务调度页 `即时执行`，同步驱动 |
| 活动计划上限 | 正数，按监督名额准入 | 固定为 1 |
| 排队与优先级 | 支持补挂、待执行、挂起/放行和优先级 | 无排队、挂起或插队入口 |
| 人工首次确认 | 可用 `MANUAL_CONFIRM` | 禁止 `MANUAL_CONFIRM` |
| 异步完成 | 可用 `ACCEPTANCE` 和结果回调 | 禁止 `ACCEPTANCE` 和结果回调 |
| 自动重试 | 按定义配置 | `retryMax` 必须为 0 |
| 入站证据 | 由触发入口受理并绑定 | 每个入站事件都须有本库持久入站证据 |
| 运行资源 | worker 批量和时长上限 | `max-concurrency`、`response-reserve-ms` 必须为正数 |
| 成功判定 | 事件状态、检查点、回调和证据 | 仅 `resultCode=SUCCEEDED`；其余状态按原命令回查 |

`IMMEDIATE` 还可能返回 `NOT_READY`、`SUPERVISOR_PAUSED`、`SUPERVISOR_BUSY`、`BUSY`、`MODE_MISMATCH`；除 `SUCCEEDED` 外都不表示成功，`R.code=200` 不能替代结果判定。`QUEUE` 出站合同未配置正数 `resourceKey` 容量时按 fail-closed 处理。

## 7 主要入口

- 目录：`系统接入配置` > `系统定义`、`环境配置`、`系统地址`；对应 `/endpoint/systems`、`/endpoint/environments`、`/endpoint/addresses`，审计为 `/endpoint/audit/page`。
- 调度：`计划事件` > `业务调度`；`/process/schedule`，计划类型为 `/{id}/plan-types`。
- 计划：`计划事件` > `计划定义`；`/process/definition`，含草稿、校验、发布和复制。
- 系统模式：`/external-systems`、`/system-policies`、`/system-policies/preview`。
- 执行：`执行总览`、`计划详情`；补挂、确认、暂停、恢复、取消和优先级的入口在 `/process/instance`。
- 监督：`业务调度` > `监督计划` > `监督详情`；暂停、恢复、重启 worker 和切换版本在 `/process/supervisor/{id}`。
- 查询与恢复：`/process/requests/page`、`/process/event/{id}`；事件重试、最终失败和重新校验在 `/process/event`。
- 即时执行：`/process/schedule/{scheduleId}/execute-immediate`；原命令回查为 `/commands/{commandKey}`。

## 8 核对边界

| 阶段 | 核对 |
| --- | --- |
| 数据库 | endpoint 6 张表、process 16 张表及 53 行菜单存在；`iws_endpoint_catalog_control` 恰有一行 `CATALOG` |
| endpoint-only | 系统、环境、地址页面可读；修改产生 revision 和原因；当前环境只读且与部署配置一致 |
| process 开启前 | endpoint Reader、当前环境和 `processDependencyGuard` 正常；调度关闭时不创建或启停真实 SnailJob 任务 |
| 调度开启前 | `snail-job.enabled` 与两个正数控制参数已配置；监督执行器、`QUEUE` worker 和平台适配器已装配 |
| 真实链路前 | 对象范围、入站身份、事件、调用、本地动作、校验器、分支条件、即时失败合同和真实 `SingleActionOperation` 均已接入 |
| 实际外呼前 | 目录地址、系统启用状态、合同 `resourceKey` 容量和业务调用实现均核对 |

隔离数据库、HTTP、浏览器及构建结果统一见 [验收记录](plan-event-verification.md)，不将测试环境结果等同于生产验收。

`application-dev.yml` 当前指向 `localhost:3307/innolux_iws`，但本轮 MCP 无可用连接，尚未读取或写入 3307 的结构和数据。真实业务链路、真实库核验、生产部署和外部调用仍需后续接入与验收，现有证据不能替代生产验收。

## 9 主要来源

- 配置与升级：`iws-admin/src/main/resources/application.yml`、`application-dev.yml`、`docs/plan-event-upgrade.md`。
- 数据库：`script/sql/iws_plan_event_endpoint_schema.sql`、`iws_plan_event_process_schema.sql`、`iws_plan_event_menu.sql`。
- endpoint：`iws-modules/iws-endpoint/src/main/java/com/innolux/iws/endpoint/controller`、`catalog`、`service`。
- process：`iws-modules/iws-process/src/main/java/com/innolux/iws/process/controller`、`runtime`、`config`。
- job：`iws-modules/iws-job/src/main/java/com/innolux/iws/job/process`。
- Web：`../ui/innolux-iws-web/src/views/endpoint`、`../ui/innolux-iws-web/src/views/process`。
- 设计约束：`iws-modules/iws-process/docs/process-access.md`、`process-contract.md`。
