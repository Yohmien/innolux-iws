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
| endpoint | 6 张表，唯一 `CATALOG` 控制行，revision 为 0 |
| process | 16 张配置及运行表 |
| APK 应用版本管理 | 1 张版本表，独立应用包管理页面 |
| SnailAI 默认应用 | 1 行 `sai_app`，`app_id=1`，供主服务默认配置接入 |
| 默认对话模型 | 1 行 `sai_model_config`（`glm-5.1`，DashScope OpenAI 兼容端点），API Key 为空，需在 AI 控制台补充 |
| SnailAI 默认 AI 助手 | 1 行 `sai_agent`（`id=1`，`IWS+ 助手`），`chat_model_id=1` 显式绑定默认对话模型 |
| 菜单 | 共 230 行，其中 endpoint/process 53 行、APK 5 行 |

合计 124 张表。APK 表和菜单已内联在完整初始化 SQL 中，新空库无需额外执行 `iws_apk_management.sql`；已有库只执行该升级脚本，不重跑全量初始化。保留框架必要的模板管理员、SnailJob 初始管理员、角色、菜单、字典、参数、默认客户端、OSS 模板及流程/AI 基础配置。默认账号和客户端配置沿用基础初始化模板，部署后通过受控流程更换；SnailJob 模板管理员为 `admin`，初始密码沿用基础脚本的 `admin`。

SnailAI 默认应用、默认对话模型和默认 AI 助手均已内联到空库初始化。默认模型为 `glm-5.1`，通过阿里云百炼 DashScope 的 OpenAI 兼容端点调用；种子中的 API Key 为空，首次使用只需在 AI 控制台为默认模型补充 API Key。默认助手 `IWS+ 助手`（`id=1`）显式绑定模型 `id=1`；新用户首次打开 AI 会话会自动订阅并默认选中。已有库缺少默认应用或助手时，先执行 `iws_snailai_default_app.sql`，再执行 `iws_snailai_default_agent.sql`；助手脚本要求 `sai_app` 已有启用的 `app_id=1`。

不写测试账号、演示供应商、库存或单据、计划定义、实例、事件运行记录、真实系统地址与环境凭据。默认 AI 助手是首次对话的运行种子，不属于业务演示数据。基础模板菜单仍保留，其中部分入口属于示例功能，不代表对应业务已经接入。平台模块默认关闭，不创建 SnailJob 任务，不为新增平台菜单写角色授权关系。

全量 SQL 是当前仓库标准结构的快照；后续修改实体、基础或模块 SQL 时须同步维护并重新验证。框架原始脚本、WMS 核心脚本和三份平台升级脚本仍保留，现有库升级使用对应升级流程，不执行此全量初始化。

## 重复执行与失败

这是一次性空库初始化，重复执行非空库会拒绝，不宣称可对存量库幂等升级。DDL 会隐式提交，不能保证整个文件回滚。

执行中断后先查明已创建对象和失败点，保留日志。入口会拒绝部分初始化的非空库，并在 `ALTER DATABASE` 之前停止；应由部署负责人根据实际状态决定修复或另选全新目标。脚本不自动删除、清空或重建已有库。

## 验证

下表前期结果记录 2026-10-24 在本任务专用 `127.0.0.1:13308`、MySQL 8.0.45、独立 datadir 的历史验证，没有在真实 3306/3307 写入。2026-10-26 另在专用隔离实例 `13309` 验证当前自动调整逻辑，数据目录与业务实例分离：默认 `utf8mb4_0900_ai_ci` 的空库成功调整并完成 124 表、230 菜单初始化；将该非空库默认规则设为 `0900` 后重跑，退出 1，默认规则仍为 `0900`、124 表均保留。测试库名包含连字符，目标仍由连接选库取得。

| 检查 | 结果 |
| --- | --- |
| 最终版入口从不存在的库开始执行 | 成功：124 表、230 菜单、53 平台菜单、IWS 与 SnailJob 初始管理员各 1、SnailAI 默认应用 1 行、默认 AI 助手 1 行、默认对话模型 1 行、OpenAPI 用户 0 行、用户订阅 0 行、控制版本 0，WMS 和 process 业务行均为 0 |
| 默认 AI 助手种子与最终验收 | 初始化输出 `默认AI助手符合=1`；`sai_agent` 1 行（`id=1`、`IWS+ 助手`、`chat_model_id=1`）、`sai_model_config` 1 行、`sai_openapi_user` 0 行、`sai_user_agent` 0 行 |
| 入口重跑非空库 | 退出 1；所有表行数快照保持一致 |
| 直接 SQL 重跑非空库 | 前置校验退出 1；所有表行数快照保持一致 |
| 2026-10-24 旧行为：已有空库排序为 `utf8mb4_0900_ai_ci` | 拒绝，新增表数为 0；这是历史结果，不代表当前新行为 |
| 与基础 SQL、WMS 核心及平台升级生成的参考结构比较 | 已有 reference/full 列及索引元数据文件字节一致；本轮新增管理员只改变必要种子 |
| PowerShell 解析 | 通过 |

证据目录：`C:/Users/yohmi/.codex/visualizations/2026/10/23/01a168da-b04a-7d72-965e-62d12c45cc0d/mysql-init`，最终首跑见 `delivery-first-run.log`，拒绝路径及快照见 `delivery-verification.json` 和对应日志。此前带反引号库名的成功记录为 `tick-entry.log`，与最终版普通库名首跑分开记录。

真实目标库仍须部署前核验，原 3307 MCP 连接匹配限制未解决。未验证生产部署、真实角色授权或 SnailJob 控制台登录；静态复核发现遗漏的初始管理员已补入种子及最终验收。
