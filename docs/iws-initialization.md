# IWS 从零初始化

2026-10-24。适用 MySQL 8.0。完整 SQL 为 [iws_init_from_empty.sql](../script/sql/iws_init_from_empty.sql)，建库执行入口为 [iws_init_from_empty.ps1](../script/sql/iws_init_from_empty.ps1)。

## 执行

在后端仓库根目录执行，目标库名只通过 `-Database` 配置一次，主机、端口、账号和 mysql 路径按实际环境传入：

```powershell
$initCredential = Get-Credential -UserName '<部署账号>'
& ./script/sql/iws_init_from_empty.ps1 -Database '<目标库>' `
    -Server '<主机>' -Port 3306 -User $initCredential.UserName `
    -Credential $initCredential -Mysql '<mysql.exe绝对路径>'
```

入口使用专用 mysql 连接，目标不存在时创建 `utf8mb4 / utf8mb4_general_ci` 数据库；已存在时必须完全没有表、视图、存储程序、触发器和事件。账号须有建库、建表、索引、种子写入及元数据读取权限，由部署流程提供。可用 `-SslMode` 选择已有环境要求的 TLS 模式。密码通过凭据对象传入，调用期间临时设置子进程使用的环境变量，结束后恢复，不写入 SQL 或命令行参数。

SQL 使用当前连接库作为唯一目标。已有合规空库时，也可按 SQL 头部说明用 `mysql --database=<目标库>` 执行。MySQL 8.0.45 不支持 `PREPARE` 执行 `USE`，因此建库与选择连接库由薄入口承担；SQL 本身包含全部初始化内容，不依赖客户端 `source` 其他文件。

只支持遇错停止的批处理执行器，禁止 `--force` 或 GUI 续错。入口明确传 `--no-defaults`，不继承默认配置中的续错选项。

## 初始化内容

| 内容 | 目标 |
| --- | --- |
| 框架、系统、生成器、调度、工作流、AI 平台 | 81 张表 |
| WMS SMT 核心 | 20 张表 |
| endpoint | 6 张表，唯一 `CATALOG` 控制行，revision 为 0 |
| process | 16 张配置及运行表 |
| 菜单 | 共 225 行，其中 endpoint/process 53 行 |

合计 123 张表。保留框架必要的模板管理员、SnailJob 初始管理员、角色、菜单、字典、参数、默认客户端、OSS 模板及流程/AI 基础配置。默认账号和客户端配置沿用基础初始化模板，部署后通过受控流程更换；SnailJob 模板管理员为 `admin`，初始密码沿用基础脚本的 `admin`。

不写测试账号、演示供应商、库存或单据、计划定义、实例、事件运行记录、真实系统地址与环境凭据。基础模板菜单仍保留，其中部分入口属于示例功能，不代表对应业务已经接入。平台模块默认关闭，不创建 SnailJob 任务，不为新增平台菜单写角色授权关系。

全量 SQL 是当前仓库标准结构的快照；后续修改实体、基础或模块 SQL 时须同步维护并重新验证。框架原始脚本、WMS 核心脚本和三份平台升级脚本仍保留，现有库升级使用对应升级流程，不执行此全量初始化。

## 重复执行与失败

这是一次性空库初始化，重复执行非空库会拒绝，不宣称可对存量库幂等升级。DDL 会隐式提交，不能保证整个文件回滚。

执行中断后先查明已创建对象和失败点，保留日志。入口会拒绝部分初始化的非空库；应由部署负责人根据实际状态决定修复或另选全新目标。脚本不自动删除、清空或重建已有库。

## 验证

仅在本任务专用 `127.0.0.1:13308`、MySQL 8.0.45、独立 datadir 验证，没有在真实 3306/3307 写入。

| 检查 | 结果 |
| --- | --- |
| 最终版入口从不存在的库开始执行 | 成功：123 表、225 菜单、53 平台菜单、IWS 与 SnailJob 初始管理员各 1、控制版本 0，WMS 和 process 业务行均为 0 |
| 入口重跑非空库 | 退出 1；所有表行数快照保持一致 |
| 直接 SQL 重跑非空库 | 前置校验退出 1；所有表行数快照保持一致 |
| 已有空库排序为 `utf8mb4_0900_ai_ci` | 拒绝，新增表数为 0 |
| 与基础 SQL、WMS 核心及平台升级生成的参考结构比较 | 已有 reference/full 列及索引元数据文件字节一致；本轮新增管理员只改变必要种子 |
| PowerShell 解析 | 通过 |

证据目录：`C:/Users/yohmi/.codex/visualizations/2026/10/23/01a168da-b04a-7d72-965e-62d12c45cc0d/mysql-init`，最终首跑见 `delivery-first-run.log`，拒绝路径及快照见 `delivery-verification.json` 和对应日志。此前带反引号库名的成功记录为 `tick-entry.log`，与最终版普通库名首跑分开记录。

真实目标库仍须部署前核验，原 3307 MCP 连接匹配限制未解决。未验证生产部署、真实角色授权或 SnailJob 控制台登录；静态复核发现遗漏的初始管理员已补入种子及最终验收。
