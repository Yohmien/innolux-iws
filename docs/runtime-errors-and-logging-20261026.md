# 页面异常与日志调整

## 日志证据

用户提供的 `logs.zip` 来自另一台机器的 `D:\lin\business\innolux-iws`，运行数据库为 `innolux-iws`，目前只执行过初始化 SQL。本次本机验证不能证明该机器已经恢复。

| 问题 | 证据 | 处理 |
| --- | --- | --- |
| 公共字典请求反复出现未知异常 | `sys-error.log` 中 14 次字典请求失败；Redis 中的对象类型为 `org.dromara.system.domain.vo.SysDictDataVo`，当前类已改为 IWS 包名 | 为主服务配置独立 Redis 前缀，停止读取无前缀旧缓存 |
| AI 页面无法注册当前用户 | 两次 `/snail-ai/user/register` 失败；AI 服务记录“应用不存在: 1” | 新初始化已内置默认应用；旧库执行 `iws_snailai_default_app.sql` 补齐 |
| AI 会话没有智能体 | 聊天链路 `SubscriptionCheckHandler` 校验 `sai_user_agent`，未订阅直接返回“请先订阅该智能体”；新库没有默认助手，旧库也没有订阅 | 新初始化内置 `IWS+ 助手`；旧库执行 `iws_snailai_default_agent.sql` 补齐；主服务需 `snail-ai.enabled=true` |
| SnailJob 持续报错 | 15 处 MySQL `Access denied for user` 堆栈 | 核对运行机 SnailJob 的数据库账号、密码及连接地址；库名为 `innolux-iws` |
| 监控页面报错 | 提供的监控服务日志显示成功启动和服务状态变化，没有对应请求异常 | 先复验公共字典请求；若仍异常，按页面网络请求和发生时间继续定位 |

## Redis 配置生效

主服务开发和生产配置使用 `IWS_REDIS_KEY_PREFIX`，默认 `innolux-iws`。例如字典缓存 `sys_dict` 实际访问 `innolux-iws:sys_dict`。已有旧键保留，不执行清空或删除。

切换前缀会影响该 Redisson 客户端的缓存、会话和分布式锁。部署时先停止同一业务集群的全部主服务实例，统一前缀后再启动；不能让新旧前缀实例同时处理业务。用户需要重新登录。共享 Redis 的不同环境应分别配置前缀，同一集群内保持一致。

## AI 应用配对

运行机日志显示请求头 `Snail-Ai-App-Id` 的值 `1` 在 SnailAI 服务端查不到对应应用，返回“应用不存在: 1”，主服务再把它包装成 `SnailAiException`。这是运行环境缺少应用记录，不是只改异常文案就能修复的问题。

当前 `iws_init_from_empty.sql` 已内置 `app_id=1` 的默认应用，并与主服务 dev/prod 默认 `snail-ai.app-id`、`snail-ai.token` 对齐；新建空库无需再进控制台手工创建。

已执行过旧版初始化、尚未创建 AI 应用的库，在运行机数据库执行：

```powershell
mysql --host=127.0.0.1 --port=3306 --user=<部署账号> --default-character-set=utf8mb4 `
  --database=innolux-iws --batch --raw < script/sql/iws_snailai_default_app.sql
```

脚本头部 `@snailai_app_schema` 已设为 `innolux-iws`。脚本连续执行两次应成功且只保留一行；若 `app_id=1` 已存在但名称、描述、令牌、路由策略或状态不同，会明确失败并保留原记录。此时按现有应用令牌同步主服务配置，或经确认后处理该自定义记录；不要在未知情况下覆盖。

默认令牌属于接入模板。共享或生产环境应同时更换 `sai_app.app_id=1` 的令牌和主服务 `snail-ai.token`，不能只修改其中一处。SnailAI 会缓存已认证应用 5 分钟；修改令牌后等待缓存过期或重启 SnailAI 服务。

运行机只读核验：

```sql
SELECT id, app_id, app_name, status
FROM `innolux-iws`.sai_app
WHERE app_id = '1';
```

应有且只有一行，且 `status=1`。再调用已登录状态下的 `POST /snail-ai/user/register`，应返回 `code=200` 且 `data.openId` 非空；SnailAI 日志中不应再出现“应用不存在”或“应用认证失败”。本机 `innolux-iws.sai_app` 的当前结构和空数据只作为实施前状态参考，不能作为异机运行证据。

代码侧新增 `SnailAiExceptionHandler`，在通用 `RuntimeException` 处理器之前返回固定安全提示：应用认证失败时给出应用管理、`app-id` 和 `token` 的核对指引，其他失败统一提示检查 SnailAI 服务端状态和应用配置。SDK 的异常原文和 HTTP 响应体不回传页面；日志只记录请求地址、认证分类、异常类型和安全堆栈帧，不记录异常 message 或 cause。它让配置错误可识别，不改变缺失应用这一环境事实。

## AI 默认助手

新增用户首次打开 AI 会话时没有智能体，是因为聊天链路按 `sai_user_agent` 校验订阅，未订阅会直接返回“请先订阅该智能体”。空库初始化现在会创建默认 AI 助手 `IWS+ 助手`（`sai_agent.id=1`），并显式绑定默认对话模型 `id=1`；种子模型使用 `glm-5.1` 和阿里云百炼 DashScope 的 OpenAI 兼容端点。

默认模型的 API Key 为空，未补 Key 时对话不可用。在 SnailAI 控制台（AI 控制台）的模型配置中找到默认 CHAT 模型，补充 API Key 后即可对话；端点保持 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型为 `glm-5.1`。Key 必须与该端点匹配；改用其他提供商时同时调整端点和模型，不能只替换 Key。

### ChatClient 构建失败

本次运行反馈确认默认模型 API Key 尚未填写。SnailAI 1.1.1 的 `ChatModelRuntime.validate` 要求 `apiKey` 非空，因此空 API Key 是当前 `ChatClient` 构建失败的直接原因。按以下顺序处理：

1. 打开 AI 控制台 → 模型管理，为默认模型 `glm-5.1` 填写并保存 API Key，然后点击“验证”。
2. 确认默认助手 `IWS+ 助手` 的 `chat_model_id=1`，且 `sai_model_config.id=1` 为启用的 GLOBAL CHAT 模型；默认助手已显式绑定它，不要求它同时带系统默认标志。
3. 填写并验证后再发起对话；若仍失败，查看 `iws-snailai-server` 日志中 `Failed to build ChatClient for config` 之后的异常信息，继续定位缺失字段或不可用的适配器。

不要写入假 API Key，也不要修改 SnailAI 依赖代码绕过 `apiKey` 校验。

已执行过旧版初始化、没有默认助手的库，先确认 `sai_app` 已有启用的 `app_id=1`；如果为空，先执行 `iws_snailai_default_app.sql`，再执行下面的默认助手脚本。

```powershell
Get-Content -Raw -Encoding UTF8 script/sql/iws_snailai_default_app.sql |
  mysql --host=127.0.0.1 --port=3306 --user=<部署账号> --default-character-set=utf8mb4 --database=innolux-iws --batch --raw

Get-Content -Raw -Encoding UTF8 script/sql/iws_snailai_default_agent.sql |
  mysql --host=127.0.0.1 --port=3306 --user=<部署账号> --default-character-set=utf8mb4 --database=innolux-iws --batch --raw
```

脚本头部 `@snailai_agent_schema` 已设为 `innolux-iws`。首次执行为缺失的 `id=1` 新增默认助手并绑定模型 `id=1`，同时给 `app_id=1` 且没有任何订阅的 OpenAPI 用户回填默认助手订阅；已有其他订阅的用户不动。`id=1` 已被其他名称占用、缺少启用的 GLOBAL CHAT 模型 `id=1` 或必要表时前置失败，不覆盖原记录；重复执行无变化。

运行机只读核验：

```sql
SELECT id, name, status, app_id, chat_model_id
FROM `innolux-iws`.sai_agent
WHERE id = 1;

SELECT id, model_name, model_type, scope, is_default, is_enabled, api_endpoint, api_key = '' AS api_key_empty
FROM `innolux-iws`.sai_model_config
WHERE id = 1 AND model_type = 'CHAT' AND scope = 'GLOBAL' AND is_enabled = 1;

SELECT u.app_id, u.open_id, ua.agent_id
FROM `innolux-iws`.sai_openapi_user u
LEFT JOIN `innolux-iws`.sai_user_agent ua ON ua.user_id = u.platform_user_id
WHERE u.app_id = '1';
```

应有且只有一行默认助手，命名为 `IWS+ 助手`、`status=1`、`app_id=1`、`chat_model_id=1`；模型 `id=1` 应为启用的 GLOBAL CHAT 模型且 `api_key` 非空；每个 `app_id=1` 的 OpenAPI 用户至少有一条订阅。

聊天还要求主服务以 `snail-ai.enabled=true` 注册为 SnailAI 客户端，并保持 SnailAI Server 运行。主服务未连接时，`ContextCollectorHandler` 会报无可用客户端；此时检查主服务与 SnailAI Server 的 gRPC 地址、端口和启动顺序，而不是只改数据库。

## 日志位置与滚动

四个服务各自汇总，主服务额外按业务模块拆分：

```text
logs/
  iws-admin/
    all.log
    modules/wms.log
    modules/system.log
    modules/ai.log
    modules/common.log
    modules/...
  iws-monitor-admin/all.log
  iws-snailjob-server/all.log
  iws-snailai-server/all.log
```

模块文件随该模块第一次产生日志创建；第三方框架日志保留在服务汇总中。汇总包含所有已启用级别及异常堆栈，默认仍为 INFO；未启用的 DEBUG/TRACE 不会凭空产生。

每份日志按日期和大小滚动，默认 50MB，归档格式为 `all.2026-10-26.0.log.gz`，模块归档采用同样的日期和序号格式。50MB 是原生日志滚动阈值，按完整事件写入，单条事件可能使文件略超阈值。跨日时由下一条日志触发滚动。历史默认保留 60 天。

可通过 `LOG_ROOT` 修改所有服务的日志根目录，服务子目录固定隔离；`LOG_MAX_FILE_SIZE` 和 `LOG_MAX_HISTORY` 分别调整大小与历史天数。同一服务运行多个实例时，应为每个实例指定独立根目录。监控的 Actuator logfile 端点同步读取各服务的 `all.log`。

主服务 SQL 日志改为 `mybatis-plus.sql-log.output: log`，通过日志系统写入汇总和 `modules/common.log`，同时仍可在控制台查看。无需再把标准输出重定向到没有滚动限制的旧 `console.log`。这套文件配置收集日志框架事件；JVM 原生崩溃输出、启动横幅等直接标准输出不属于 Logback 事件。

## 本机验证结果

使用 JDK 21 和 Maven Wrapper，Redis 与日志组合测试共 18 项、AI 异常处理测试 4 项全部通过，无失败、无跳过。

- Redis：验证 dev/prod 默认前缀及外部覆盖、字典键、真实 Sa-Token 键和 Lock4j 键的映射；未连接真实 Redis。
- 日志：实际加载四个服务的配置并写入 WARN，核对各自汇总文件；验证主服务模块路由和异常堆栈，实际写入超过 50MB 产生日期分卷，注入日期时钟验证跨日滚动。SnailJob 远端日志接收 appender 使用测试替身，不代表任务日志远端上报已联调。
- AI：验证认证提示、其他错误提示、响应及日志不包含 SDK 原文中的测试秘密，以及专用处理器优先于通用异常处理器。
- SnailAI 默认应用 SQL：在隔离 MySQL 8.0.45 验证当前空库初始化包含一行默认应用，升级脚本首次新增、重复执行无变化、自定义冲突拒绝覆盖、缺表前置失败。
- 默认 AI 助手 SQL：在隔离 MySQL 8.0.45 验证空库初始化包含一行 `IWS+ 助手`（`chat_model_id=1`）和一行默认 CHAT 模型；旧结构库执行升级脚本首跑新增助手并回填 1 行订阅，二跑无变化，名称冲突失败且不覆盖，缺少启用的 GLOBAL CHAT 模型 `id=1` 或缺 `sai_agent` 表时前置失败且不写入；模型 `id=1` 非系统默认时仍可完成升级。
- 独立源码审查发现的 AI 原始错误内容透传问题已修复并复核；`git diff --check` 通过。

这些是本机隔离验证，不等同于运行机页面和服务恢复验收。

## 运行机复验

1. 核对 SnailJob 的生效数据库配置，修正账号认证失败后确认服务不再循环报错；不要仅修改库名。
2. 旧库执行 `script/sql/iws_snailai_default_app.sql` 和 `script/sql/iws_snailai_default_agent.sql`，新库确认初始化已创建 `sai_app.app_id=1` 与 `sai_agent.id=1`；核对主服务 `snail-ai` 配置。
3. 确认主服务 `snail-ai.enabled=true`、SnailAI Server 正在运行且客户端已连接；在 AI 控制台为默认 CHAT 模型补充 API Key。
4. 统一主服务 Redis 前缀并重启，重新登录后打开字典相关页面、监控和 AI 页面。
5. 检查字典接口与 `/snail-ai/user/register` 的实际响应；新用户首次打开 AI 会话应自动订阅并选中默认助手，确认新日志没有对应错误。

未在用户运行机修改数据库、Redis 或服务进程；运行机恢复验收仍需以上步骤完成。
