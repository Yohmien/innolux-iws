# 页面异常与日志调整

## 日志证据

用户提供的 `logs.zip` 来自另一台机器的 `D:\lin\business\innolux-iws`，运行数据库为 `innolux-iws`，目前只执行过初始化 SQL。本次本机验证不能证明该机器已经恢复。

| 问题 | 证据 | 处理 |
| --- | --- | --- |
| 公共字典请求反复出现未知异常 | `sys-error.log` 中 14 次字典请求失败；Redis 中的对象类型为 `org.dromara.system.domain.vo.SysDictDataVo`，当前类已改为 IWS 包名 | 为主服务配置独立 Redis 前缀，停止读取无前缀旧缓存 |
| AI 页面无法注册当前用户 | 两次 `/snail-ai/user/register` 失败；AI 服务记录“应用不存在: 1” | 初始化 SQL 只创建 `sai_app` 表；还需在 SnailAI 控制台创建应用并配对主服务配置 |
| SnailJob 持续报错 | 15 处 MySQL `Access denied for user` 堆栈 | 核对运行机 SnailJob 的数据库账号、密码及连接地址；库名为 `innolux-iws` |
| 监控页面报错 | 提供的监控服务日志显示成功启动和服务状态变化，没有对应请求异常 | 先复验公共字典请求；若仍异常，按页面网络请求和发生时间继续定位 |

## Redis 配置生效

主服务开发和生产配置使用 `IWS_REDIS_KEY_PREFIX`，默认 `innolux-iws`。例如字典缓存 `sys_dict` 实际访问 `innolux-iws:sys_dict`。已有旧键保留，不执行清空或删除。

切换前缀会影响该 Redisson 客户端的缓存、会话和分布式锁。部署时先停止同一业务集群的全部主服务实例，统一前缀后再启动；不能让新旧前缀实例同时处理业务。用户需要重新登录。共享 Redis 的不同环境应分别配置前缀，同一集群内保持一致。

## AI 应用配对

运行机日志显示请求头 `Snail-Ai-App-Id` 的值 `1` 在 SnailAI 服务端查不到对应应用，返回“应用不存在: 1”，主服务再把它包装成 `SnailAiException`。异机 `sai_app` 的实际行未在本机查询；这是运行环境缺少应用记录，不是只改异常文案就能修复的问题。

`iws_init_from_empty.sql` 当前只创建 `sai_app` 表，没有写入应用；因此按当前空库流程执行后，还需要在 SnailAI 控制台创建应用并与主服务配对。应用 token 由 SnailAI 控制台生成，属于运行环境凭据，不应写入空库初始化 SQL。仓库旧初始化脚本中的固定应用和 token 是历史记录，不能作为当前空库流程已创建应用的证据，也不应复制到运行环境。

用户确认当前只执行过初始化 SQL、尚未创建 AI 应用；当前环境按控制台创建应用，不需要新增 SQL：

1. 打开 SnailAI 控制台 `http://<SnailAI主机>:18089/snail-ai/`（经 IWS 反向代理时为 `http://<IWS主机>/snail-ai/`），使用部署配置的 SnailAI 管理员登录。
2. 进入“应用管理”，点击“新增”，填写：
   - `appId`：`1`，与主服务当前 `snail-ai.app-id` 对齐。这里填的是 `sai_app.app_id`（字符串），不是自增主键 `id`；如果该标识已被占用，改用其他值并同步修改主服务配置。
   - `appName`：`IWS+`。
   - `description`：`IWS+ 主应用`，可留空。
   - 路由策略：保持默认 `LEAST_LOAD`。
   `appId` 创建后不可修改，创建前确认。
3. 保存后确认应用状态为启用（`status=1`）。创建成功提示会显示以 `SAI_` 开头的 token，也可在应用列表的 `token` 列使用复制按钮获取。不要把 token 写入仓库、文档、日志或工单；不要沿用仓库或旧脚本中的 token。
4. 在运行机主服务的外部配置中设置并重启主服务：

   ```yaml
   snail-ai:
     app-id: "1"
     token: "<从 SnailAI 控制台复制的 token>"
     open-api:
       enabled: true
       web-port: 18089
       prefix: snail-ai
   ```

   `snail-ai.enabled` 控制的是 Agent 客户端接入，当前可保持 `false`；AI 页面注册用户使用 OpenAPI 客户端。
5. 重新登录 IWS Web，打开 AI 页面。现有前端会把 `/snail-ai/user/register` 的固定安全提示通过全局提示显示；页面空态仍使用通用文案。

SnailAI 会缓存已认证应用 5 分钟。若创建后又修改了该应用的 token，等待缓存过期或重启 SnailAI 服务后再复验。

运行机只读核验：

```sql
SELECT id, app_id, app_name, status
FROM `innolux-iws`.sai_app
WHERE app_id = '1';
```

应有且只有一行，且 `status=1`。再调用已登录状态下的 `POST /snail-ai/user/register`，应返回 `code=200` 且 `data.openId` 非空；SnailAI 日志中不应再出现“应用不存在”或“应用认证失败”。本机 `innolux-iws.sai_app` 为空只能作为结构参考，不能作为异机运行证据。

代码侧新增 `SnailAiExceptionHandler`，在通用 `RuntimeException` 处理器之前返回固定安全提示：应用认证失败时给出应用管理、`app-id` 和 `token` 的核对指引，其他失败统一提示检查 SnailAI 服务端状态和应用配置。SDK 的异常原文和 HTTP 响应体不回传页面；日志只记录请求地址、认证分类、异常类型和安全堆栈帧，不记录异常 message 或 cause。它让配置错误可识别，不改变缺失应用这一环境事实。

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
- 独立源码审查发现的 AI 原始错误内容透传问题已修复并复核；`git diff --check` 通过。

这些是本机隔离验证，不等同于运行机页面和服务恢复验收。

## 运行机复验

1. 核对 SnailJob 的生效数据库配置，修正账号认证失败后确认服务不再循环报错；不要仅修改库名。
2. 配置 SnailAI 应用及主服务接入参数，确认两端应用标识和令牌一致。
3. 统一主服务 Redis 前缀并重启，重新登录后打开字典相关页面、监控和 AI 页面。
4. 检查字典接口与 `/snail-ai/user/register` 的实际响应，确认新日志没有对应错误。

未在用户运行机修改数据库、Redis 或服务进程；运行机恢复验收仍需以上步骤完成。
