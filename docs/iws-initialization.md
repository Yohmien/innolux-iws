# IWS 从零初始化

2026-10-26。适用 MySQL 8.0。完整 SQL 为 [iws_init_from_empty.sql](../script/sql/iws_init_from_empty.sql)，建库执行入口为 [iws_init_from_empty.ps1](../script/sql/iws_init_from_empty.ps1)。

## 执行

在后端仓库根目录执行。入口以 `-Database` 创建或连接目标库，主机、端口、账号和 mysql 路径按实际环境传入；SQL 内不写死库名，目标统一由 `DATABASE()` 取得，无需按环境手改库名：

```powershell
$initCredential = Get-Credential -UserName '<部署账号>'
& ./script/sql/iws_init_from_empty.ps1 -Database '<目标库>' `
    -Server '<主机>' -Port 3306 -User $initCredential.UserName `
    -Credential $initCredential -Mysql '<mysql.exe绝对路径>'
```

入口使用专用 mysql 连接，目标不存在时创建 `utf8mb4 / utf8mb4_general_ci` 数据库。SQL 以 `DATABASE()` 取得当前连接库作为唯一目标，不写死库名；目标 schema 必须按实际大小写存在于 `information_schema.SCHEMATA`，服务器为 MySQL 8.0，`@@lower_case_table_names` 为 `0`、`1` 或 `2`，且库内完全没有表、视图、存储程序、触发器和事件。非空库直接拒绝，不执行 `ALTER DATABASE`。账号须有建库、建表、索引、种子写入及元数据读取权限，以及已有空库调整默认字符集/排序规则所需的 `ALTER` 权限（即执行 `ALTER DATABASE` 的数据库权限），由部署流程提供。可用 `-SslMode` 选择已有环境要求的 TLS 模式。密码通过凭据对象传入，调用期间临时设置子进程使用的环境变量，结束后恢复，不写入 SQL 或命令行参数。

前置条件全部满足后，SQL 执行 `ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci`，省略库名表示当前连接库，与 `DATABASE()` 的唯一目标一致，因此支持含连字符的库名且不需要拼接标识符。随后从 `information_schema.SCHEMATA` 回读默认规则，仍不符合时停止。MySQL 8.0.45 不支持 `PREPARE` 执行 `USE` 或 `ALTER DATABASE`，不能用动态预处理代替；建库与选库由薄入口承担。已有空库也可按头部说明用 `mysql --database=<目标库>` 执行完整 SQL，必须遇错停止，禁止 `--force` 或 GUI 续错。

只支持遇错停止的批处理执行器，禁止 `--force` 或 GUI 续错。入口明确传 `--no-defaults`，不继承默认配置中的续错选项。

## 初始化内容

| 内容 | 目标 |
| --- | --- |
| 框架、系统、生成器、调度、工作流、AI 平台 | 81 张表 |
| WMS SMT 核心 | 20 张表 |
| endpoint | 6 张表，唯一 `CATALOG` 控制行（revision 为 0），默认环境 `TEST`（测试环境）与系统 `IWS` 各 1 行 |
| process | 16 张配置及运行表 |
| APK 应用版本管理 | 1 张版本表，独立应用包管理页面 |
| SnailAI 默认应用 | 1 行 `sai_app`，`app_id=1`，供主服务默认配置接入 |
| 默认对话模型 | 1 行 `sai_model_config`（`glm-5.1`，DashScope OpenAI 兼容端点），API Key 为空，需在 AI 控制台补充 |
| SnailAI 默认 AI 助手 | 1 行 `sai_agent`（`id=1`，`IWS+ 助手`），`chat_model_id=1` 显式绑定默认对话模型 |
| SnailJob 命名空间与组 | 2 个命名空间（`dev`、`prod`）与各 1 行 `iws_group` 组，组令牌与 dev/prod 客户端配置一致 |
| 菜单 | 共 230 行，其中 endpoint/process 53 行、APK 5 行 |

合计 124 张表。APK 表和菜单已内联在完整初始化 SQL 中，新空库无需额外执行 `iws_apk_management.sql`；已有库只执行该升级脚本，不重跑全量初始化。SnailAI 默认应用也已内联，新空库无需另跑脚本；已执行过旧版初始化、`sai_app` 中缺少 `app_id=1` 的库执行 `script/sql/iws_snailai_default_app.sql`。保留框架必要的模板管理员、SnailJob 初始管理员、角色、菜单、字典、参数、默认客户端、OSS 模板及流程/AI 基础配置。默认账号和客户端配置沿用基础初始化模板，部署后通过受控流程更换；SnailJob 模板管理员为 `admin`，初始密码沿用基础脚本的 `admin`。

SnailAI 默认应用使用与主服务 dev/prod 配置相同的默认令牌，方便空库首次启动。该值属于接入模板，共享或生产环境必须同时更换 `sai_app` 对应令牌和主服务 `snail-ai.token`；只修改其中一处会导致应用认证失败。

### AI 默认对话

空库初始化会创建默认对话模型和默认 AI 助手 `IWS+ 助手`。默认模型为 `glm-5.1`，通过阿里云百炼 DashScope 的 OpenAI 兼容端点调用；种子中的 API Key 为空，未补 Key 时对话不可用。默认助手显式绑定模型 `id=1`，不会因存在多个默认模型而选错。首次使用无需再建智能体，只需在 AI 控制台的模型配置中为默认模型补充 API Key。该 Key 需与 DashScope 端点匹配；改用其他提供商时同时调整 `api_endpoint` 和 `model_key`，不能只替换 Key。

主服务需以 `snail-ai.enabled=true` 注册为 SnailAI 客户端（dev/prod 配置已开启），并运行 SnailAI Server。聊天请求由已连接的客户端转发，缺少客户端时会提示没有可用客户端。新用户首次打开 AI 会话会自动订阅默认助手并默认选中；已有用户再次进入不会重复订阅，也不会覆盖其后续取消订阅的选择。

已执行过旧版初始化的库没有默认助手，执行 `script/sql/iws_snailai_default_agent.sql` 补齐：脚本只新增缺失的 `id=1` 助手，并为 `app_id=1` 且没有任何订阅的 OpenAPI 用户回填默认助手订阅。`id=1` 已被其他名称占用时明确失败，不覆盖；重复执行无变化。

SnailJob 组使用与 dev/prod 客户端配置相同的令牌，命名空间 `unique_id` 为 `dev`、`prod`，与 `snail-job.namespace`（默认取 profile 名）对应。缺少组或令牌不一致时，客户端注册失败，`QUEUE` 事件与监督任务无处可派；共享或生产环境必须同时更换 `sj_group_config.token` 和 `snail-job.token`。APK 发布走 IMMEDIATE，不依赖该组。

不写测试账号、演示供应商、库存或单据、计划定义、实例、事件运行记录、真实系统地址与环境凭据。默认 AI 助手是运行种子，用于首次打开 AI 会话即可对话，不属于业务演示数据。环境目录只预置一行 `TEST`（测试环境），用于首次启用计划事件时通过启动校验，不含系统、地址或审计行。基础模板菜单仍保留，其中部分入口属于示例功能，不代表对应业务已经接入。平台模块默认关闭，不创建 SnailJob 任务，不为新增平台菜单写角色授权关系。

全量 SQL 是当前仓库标准结构的快照；后续修改实体、基础或模块 SQL 时须同步维护并重新验证。框架原始脚本、WMS 核心脚本和三份平台升级脚本仍保留，现有库升级使用对应升级流程，不执行此全量初始化。

已有库的 SnailAI 默认应用升级入口为 `script/sql/iws_snailai_default_app.sql`。先在脚本头部核对唯一的 `@snailai_app_schema`，使用 MySQL 8.0、同一连接和遇错停止模式执行。已存在完全一致的默认应用时重复执行无变化；缺少时新增；已有 `app_id=1` 但名称、描述、令牌、路由策略或状态不同则失败，不覆盖自定义配置。

## 重复执行与失败

这是一次性空库初始化，重复执行非空库会拒绝，不宣称可对存量库幂等升级。DDL 会隐式提交，不能保证整个文件回滚。

执行中断后先查明已创建对象和失败点，保留日志。入口会拒绝部分初始化的非空库，并在 `ALTER DATABASE` 之前停止；应由部署负责人根据实际状态决定修复或另选全新目标。脚本不自动删除、清空或重建已有库。

APK 功能还需要完成“系统接入配置”的当前环境与 `IWS` 系统启用，以及“应用包管理”的发布计划初始化。已有默认环境但缺少或停用 `IWS` 时，可执行幂等脚本 `script/sql/iws_endpoint_default_iws_system.sql`；具体页面字段、配置覆盖和失败重试顺序见 [APK 应用包管理](apk-management.md#发布前置检查)。

## 已有库升级

只补齐 SnailAI 默认应用和默认 AI 助手，不重跑全量初始化。先核对脚本头部的 `@snailai_app_schema`、`@snailai_agent_schema` 与目标库一致，再使用 MySQL 8.0 客户端、同一连接和 `--batch --raw` 执行；执行器必须遇错停止：

```powershell
Get-Content -Raw -Encoding UTF8 script/sql/iws_snailai_default_app.sql |
  mysql --host=127.0.0.1 --port=3306 --user=<部署账号> --default-character-set=utf8mb4 --database=innolux-iws --batch --raw

Get-Content -Raw -Encoding UTF8 script/sql/iws_snailai_default_agent.sql |
  mysql --host=127.0.0.1 --port=3306 --user=<部署账号> --default-character-set=utf8mb4 --database=innolux-iws --batch --raw
```

首次执行缺少默认应用时新增一行；重复执行无变化。已有 `app_id=1` 但任一受管字段不同会失败并保留原记录，需先核对自定义应用与主服务配置。

默认助手脚本首次执行为缺失的 `id=1` 新增一行，并给 `app_id=1` 且没有任何订阅的 OpenAPI 用户回填默认助手订阅；已有其他订阅的用户不动。`id=1` 已被其他名称占用、缺少启用的 GLOBAL CHAT 模型 `id=1` 或必要表时前置失败，不写入依赖数据；重复执行无变化。

## 验证

下表前期结果记录 2026-10-24 在本任务专用 `127.0.0.1:13308`、MySQL 8.0.45、独立 datadir 的历史验证，没有在真实 3306/3307 写入。2026-10-26 另在专用隔离实例 `13309` 验证当前自动调整逻辑，数据目录与业务实例分离：默认 `utf8mb4_0900_ai_ci` 的空库成功调整并完成 124 表、230 菜单初始化；将该非空库默认规则设为 `0900` 后重跑，退出 1，默认规则仍为 `0900`、124 表均保留。2026-10-26 又在全新隔离 MySQL 8.0.45 实例 `127.0.0.1:13311` 执行当前完整初始化，确认 `sai_app` 默认应用为一行且配置符合；同一实例验证升级脚本首次新增、重复执行无变化、自定义冲突拒绝覆盖和缺表前置失败。测试库名包含连字符，目标仍由连接选库取得。

2026-10-26 在 `127.0.0.1:13313` 隔离实例验证默认 AI 助手：全新库 `iws_verify_default_agent_20261026` 完成初始化并通过最终验收，`sai_agent` 1 行（`chat_model_id=1`）、`sai_model_config` 1 行、`sai_openapi_user` 0 行、`sai_user_agent` 0 行。手工插入零订阅和已有其他订阅的 OpenAPI 用户后，初始化文件中的回填段只为零订阅用户新增 1 行，已有其他订阅保持原样，重复执行 0 行。另用 `git show HEAD:script/sql/iws_init_from_empty.sql` 建旧结构库，执行新升级脚本首跑新增默认助手并回填 1 行，二跑无变化；修改 `id=1` 名称后失败且不覆盖、不写入订阅；缺少启用的 GLOBAL CHAT 模型 `id=1` 或缺少 `sai_agent` 表时均前置失败且不写入。

| 检查 | 结果 |
| --- | --- |
| 最终版入口从不存在的库开始执行 | 成功：124 表、230 菜单、53 平台菜单、IWS 与 SnailJob 初始管理员各 1、SnailAI 默认应用 1 行、默认 AI 助手 1 行、默认对话模型 1 行、OpenAPI 用户 0 行、用户订阅 0 行、默认环境 `TEST` 1 行、系统 `IWS` 1 行、SnailJob 命名空间 2 行与 `iws_group` 组 2 行、控制版本 0，WMS 和 process 业务行均为 0 |
| SnailAI 默认应用升级脚本 | 首次新增 1 行；重复执行保持 1 行且无变更；修改应用名后再次执行拒绝覆盖；缺少 `sai_app` 时前置失败 |
| 默认 AI 助手种子与最终验收 | 初始化输出 `默认AI助手符合=1`；`sai_agent` 1 行（`id=1`、`IWS+ 助手`、`chat_model_id=1`）、`sai_model_config` 1 行、`sai_openapi_user` 0 行、`sai_user_agent` 0 行 |
| 初始化回填段（隔离实例 13313 模拟用户） | 零订阅用户新增默认助手订阅 1 行；已有其他订阅的用户保持原订阅；重复执行 0 行 |
| 默认 AI 助手升级脚本（隔离实例 13313 旧结构库） | 首跑新增默认助手（`chat_model_id=1`）并回填 1 行；二跑无变化；名称冲突失败且保留原记录、不写入订阅；缺少启用的 GLOBAL CHAT 模型 `id=1` 或缺 `sai_agent` 表时前置失败且不写入 |
| 默认 AI 助手升级脚本（模型 `id=1` 非系统默认） | 隔离库将模型 `id=1` 的 `is_default` 置 0 后仍前置通过并完成助手新增与零订阅回填；助手显式绑定不依赖系统默认标志 |
| 旧版 `iws_init.sql`（隔离实例 13313） | 按脚本内固定库名 `innolux_iws` 建库并初始化 81 表；默认助手 1 行（`chat_model_id=1`），admin（`platform_user_id=1`）默认助手订阅 1 行；验证后删除隔离测试库 |
| 入口重跑非空库 | 退出 1；所有表行数快照保持一致 |
| 直接 SQL 重跑非空库 | 前置校验退出 1；所有表行数快照保持一致 |
| 2026-10-24 旧行为：已有空库排序为 `utf8mb4_0900_ai_ci` | 拒绝，新增表数为 0；这是历史结果，不代表当前新行为 |
| 与基础 SQL、WMS 核心及平台升级生成的参考结构比较 | 已有 reference/full 列及索引元数据文件字节一致；本轮新增管理员只改变必要种子 |
| PowerShell 解析 | 通过 |
| 2026-10-26 默认目录种子（隔离实例） | 初始化输出 `默认环境 TEST` 与 `默认系统 IWS`、`空库全量初始化完成`；升级脚本首次新增环境/系统并把目录版本推进一次，重复执行不推进版本，自定义同名配置保留原值，已存在但停用时明确失败，缺少 endpoint 表时前置失败且不写入 |

证据目录：`C:/Users/yohmi/.codex/visualizations/2026/10/23/01a168da-b04a-7d72-965e-62d12c45cc0d/mysql-init`，最终首跑见 `delivery-first-run.log`，拒绝路径及快照见 `delivery-verification.json` 和对应日志。此前带反引号库名的成功记录为 `tick-entry.log`，与最终版普通库名首跑分开记录。SnailAI 默认应用隔离验证脚本为 `C:/Users/yohmi/.codex/visualizations/2026/10/26/01a176ae-6f59-7b21-aea2-4f72e9a5a858/verify_snailai_mysql.py`。

真实目标库仍须部署前核验，原 3307 MCP 连接匹配限制未解决。未验证生产部署、真实角色授权、SnailJob 控制台登录或实际 AI 对话（种子 API Key 为空，未连接 SnailAI Server）；静态复核发现遗漏的初始管理员已补入种子及最终验收。
