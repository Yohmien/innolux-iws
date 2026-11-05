# IWS 统一身份认证中心配置说明

认证中心沿用项目已有用户、角色、公司、部门树、岗位、菜单权限和统一管理账号。Web、PDA、多个 IWS 后端及 Job、AI、Admin 监控接入同一身份源，不为新增前端复制人员账号或新建身份数据库。

状态（2026-11-05）：中心、IWS Web/PDA 源码、Admin 监控及多前端 SSO 链路已在最终隔离版本完成真实联调。JDK 21 全量构建覆盖 47 个 reactor 模块；WMS 493/493、Job 12/12、AI 21/21、Monitor 7/7、中心 Redis 1/1 测试通过；Web `vue-tsc` 及 277/277 Vitest 通过，普通构建和认证中心构建均成功。真实 HTTP 已验证同一 Web 构建从 IWS-A 同源切到 IWS-B 不再输入密码、旧 A Token 401、退出准确落到 B、SSE 使用 Authorization 头且不把 Token 放入 URL；还覆盖独立业务库、G21 数据范围更新、G24 部门移动及后代祖先更新、中心停机写请求 503、SSE/WS 失效传播、WebSocket 一次性票据跨副本并发核销和监控改密传播。本机自签 HTTPS 已验证跨站顶层 Web→Center→IWS-A→IWS-B 回调回原入口、iframe 登录页、禁第三方 Cookie 时顶层登录，以及真实非安全 HTTP 上下文的 IndexedDB 读写；隔离 MySQL 停机时登录和旧会话均失败关闭且不返回用户数据，恢复后自动重连。Job 2.0.2 与 AI 1.1.1 的后端源码接缝已核对，并在包装层实现显式账号映射、中心票据核销、原生兼容 Token、请求复核、登录守卫和统一退出；Job 隔离执行器已实际注册、执行、掉线恢复并验证错误凭据拒绝。AI 人员 HTTP 流终止、服务端 gRPC 取消、原生资源权限、会话隔离及 app token 分离已验证，但上游模型 HTTP 取消仍为 `false`，不能用人员门禁通过替代模型 SDK 限制。PDA 已用官方 Vue3/Vite uni-app 构建 H5，并在 HTTP Chromium 中复验中心登录、A/B 切换、hash 回调、页面映射、请求前缀、存储隔离和旧 A Token 替换；尚未完成 HBuilderX APK、原生 `iws-pda://` 回调、真机存储隔离和安装图标验收。上游两份管理控制台仍以压缩制品交付，未手改压缩 JS；可维护的 SSO 启动/回调/守卫/退出代码在 `iws-snailjob-server`、`iws-snailai-server` 包装层。生产代理拓扑、外部证书、全部节点重启和通知重投极端矩阵仍未闭环；真实数据库未写入，未提交、推送或部署共享/生产环境。

补充（2026-11-05）：Monitor 在最终 CSRF 修复后定向测试 10/10 通过；中心停机时人员本地注销可进入持久待撤销队列，Monitor 重启后队列保留，中心恢复后撤销旧根会话。无 CSRF 的注销仍返回 403，只有带合法 `X-XSRF-TOKEN` 的浏览器注销才进入 LogoutHandler。该结果不改变 Monitor 7/7 全量历史结果，也不表示生产代理和完整通知重投矩阵已验收。

## 配置文件放在哪里

统一配置根目录按服务分目录管理：

```text
release/
  iws-auth-server.jar
  iws-admin.jar
  config/
    auth-center/
      application.yml
      application-prod.yml
    iws-a/
      application.yml
    iws-b/
      application.yml
    job/
      application.yml
    ai/
      application.yml
    monitor/
      application.yml
```

认证中心的系统登记配置放在 `config/auth-center/application.yml`，环境差异放在同目录的 `application-{profile}.yml`。不要把所有应用的 application.yml 合并，也不要扫描并加载兄弟服务目录。

配置根默认相对实际运行 JAR 目录，服务目录由启动配置键选择；`iws.config.root` / `IWS_CONFIG_ROOT` 可指定绝对配置根，`iws.config.key` / `IWS_CONFIG_KEY` 可选择服务目录。认证中心使用 `auth-center`，同一 IWS 制品部署 A/B 时分别选择 `iws-a` / `iws-b`。该约定由公共启动适配提供，发布时仍需检查实际制品已接入并完成从其他工作目录启动的验证。

外部配置覆盖包内同名属性，缺项由包内默认补齐；所选服务外部文件不存在时回退该制品包内默认配置。非法或不可读的外部配置应报错，不能静默回退。显式启动参数、系统属性和环境变量沿用框架优先级。发布不覆盖运维已有配置，真实凭据不写入仓库或示例。

## APK 发布管理

APK 发布事实由认证中心独占，代码位于 `iws-modules/iws-app-release`，只由 `iws-auth-server` 依赖。IWS-A/B 不再包含 APK Controller、发布动作、Mapper 或 `/app/apk/*` 安全放行项；同一 Android 包名在所有业务后端共用一个发布序列和更新策略。

### 启用前提

- 中心目标库需包含 `sys_apk_release`、`iws_endpoint_*` 和 `iws_process_*` 表。新库先执行 `script/sql/iws_init_from_empty.sql`，再执行 `iws_apk_management.sql` 创建中心 APK 菜单；已有中心表缺少策略/下载字段时，先执行 `iws_apk_force_update.sql`、`iws_apk_download_access.sql`，最后再以 `iws_apk_management.sql` 校验完整结构并补齐菜单。
- 对象存储需已配置并可由中心访问。`SystemApplicationRunner` 会在启动时初始化 OSS 配置缓存；缺少该初始化时上传/下载不可用。
- 使用 `SPRING_PROFILES_ACTIVE=prod,apk` 显式启用 `application-apk.yml`。该 profile 只开启 APK 所需的 endpoint/process，计划调度器保持关闭；普通登录不要求该 profile。
- `iam.public-base-url` 是浏览器登录页基址，PDA 公开 APK API 使用中心服务根地址，不追加页面 `/iam` 或业务 `/innolux` 前缀。
- `iam.applications.<id>.allowed-instance-ids` 必须包含实际后端 `iam.client.instance-id`。遗漏时 SSO 票据会返回“目标应用节点未登记”，与 APK 权限无关。

中心启动示例：

```powershell
java -jar iws-auth-server.jar `
  --iws.config.root=C:\iws\config `
  --iws.config.key=auth-center `
  --spring.profiles.active=prod,apk
```

外部 `config/auth-center/application.yml` 至少明确中心公开地址、受信前端、后端应用、实例白名单、client secret 和数据库/Redis/OSS 配置。真实 secret 只放部署配置。

### 接口与权限

| 范围 | 路径 | 权限/约束 |
| --- | --- | --- |
| 管理列表 | `GET /system/apk/list` | `system:apk:list` |
| 版本历史 | `GET /system/apk/history` | `system:apk:list` |
| 上传/解析 | `POST /system/apk/upload` | `system:apk:upload` |
| 策略调整 | `POST /system/apk/{releaseId}/force-update` | `system:apk:publish` |
| 发布/重试 | `POST /system/apk/{releaseId}/publish` | `system:apk:publish` |
| 初始化发布计划 | `POST /system/apk/initialize` | `system:apk:publish` |
| 公开最新版本 | `GET /app/apk/latest` | 登录前可访问，仅返回已发布版本 |
| 公开历史/下载 | `GET /app/apk/history`、`GET /app/apk/download` | 登录前可访问；未发布文件不可下载 |

发布必须通过计划事件 IMMEDIATE 产生事实，Controller 不能直接把记录写成 `PUBLISHED`。同包名同 `versionCode` 同摘要上传幂等，同版本不同摘要拒绝；多个历史已发布版本可共存，最新版本按数值 `versionCode` 确定。

### 自测

```powershell
mvnw.cmd -pl iws-modules/iws-app-release -am test -Dsurefire.failIfNoSpecifiedTests=false
mvnw.cmd -pl iws-extend/iws-auth-server -am clean package -DskipTests
```

启动后使用具备 `system:apk:*` 的管理员进入 `/iam/system/apk`，上传真实可解析 APK，确认过程实例成功、状态为 `PUBLISHED`、历史/策略/下载可用；再用只读角色和无权限角色检查按钮与后端 403。PDA H5 只验证统一登录，不应请求 APK 更新接口；Android 原生更新和安装需另做真机验收。

本轮隔离环境的制品、测试、浏览器证据和未完成项见 `C:\code\company\innolux-iws\work\apk-central-release-20261105\verification.md` 与 `defects.md`。真实中心库尚未完成只读 MCP 核验，不能把隔离库结果当作生产数据证明。

## 后端系统如何登记

直接复用已有 `iam.applications`，配置绑定类是 `IamProperties.Application`，不另建登录页专用系统清单。受信前端入口单独登记在 `iam.frontends`，二者通过 `allowed-frontend-ids` 绑定；`frontendId`、后端 `applicationId`、节点 `instanceId` 和原登录渠道 `sys_client.client_id` 不可混用。

| 前端配置 | 用途 |
|---|---|
| `iam.frontends` 的 Map 键 | 受信 `frontendId`，例如 web-main、web-branch、pda-main |
| `enabled` / `terminal` | 入口启停及 WEB/PDA 终端限制 |
| `public-base-url` | 该前端部署的公开基址 |
| `launch-uri` | 精确前端进入地址，不是后端 API |
| `redirect-uri` | 精确票据回调地址 |
| `logout-uri` | 该前端的退出返回地址 |

| 配置 | 用途 |
|---|---|
| `iam.public-base-url` | 认证中心的浏览器公开基址 |
| `iam.applications` 的 Map 键 | 后端应用 ID，例如 iws-a、iws-b；不同前端访问同一后端时复用该 ID |
| `name` | 登录选择器和应用导航展示名称，前端不另维护名称数组 |
| `type` | 应用类型；IWS-A/B 同属 IWS，PDA 访问 IWS 时也属于 IWS |
| `terminals` | 允许的终端 WEB/PDA；不代替人员接入权限 |
| `enabled` | 是否开放该系统展示和接入 |
| `api-base-url` | 浏览器/终端可访问的后端公共 API 地址或同源代理前缀 |
| `allowed-frontend-ids` | 允许连接此后端的受信前端入口；不把前端实例当作后端节点 |
| `access-permission` | 现有角色权限体系中的接入权限码，不能因为可选而直接放行 |
| `client-secret` | 目标后端与中心服务端通信使用的协议凭据，只在服务端配置 |
| `allowed-instance-ids` | 允许接入的后端实例 ID，不是浏览器/Web 部署 ID |
| `session-namespace` | 该后端应用的会话命名空间，同组副本保持一致 |

以下是中心应用登记片段，所有地址、权限码和秘密由部署环境提供；数据库、Redis、中心会话密钥等其余必需配置仍沿用原配置。示例中的变量是部署参数，不是默认真实环境。

```yaml
iam:
  public-base-url: ${IAM_PUBLIC_BASE_URL}
  frontends:
    web-main:
      enabled: true
      terminal: WEB
      public-base-url: ${WEB_MAIN_PUBLIC_BASE_URL}
      launch-uri: ${WEB_MAIN_LAUNCH_URI}
      redirect-uri: ${WEB_MAIN_REDIRECT_URI}
      logout-uri: ${WEB_MAIN_LOGOUT_URI}
    web-branch:
      enabled: true
      terminal: WEB
      public-base-url: ${WEB_BRANCH_PUBLIC_BASE_URL}
      launch-uri: ${WEB_BRANCH_LAUNCH_URI}
      redirect-uri: ${WEB_BRANCH_REDIRECT_URI}
      logout-uri: ${WEB_BRANCH_LOGOUT_URI}
    pda-main:
      enabled: true
      terminal: PDA
      client-mode: APP
      public-base-url:
      launch-uri: iws-pda://pages/work/index
      redirect-uri: iws-pda://sso/callback
      logout-uri: iws-pda://pages/login
  applications:
    iws-a:
      name: IWS-A
      type: IWS
      terminals: [WEB, PDA]
      enabled: true
      api-base-url: ${IWS_A_PUBLIC_API_BASE_URL}
      allowed-frontend-ids: [web-main, web-branch, pda-main]
      access-permission: ${IWS_A_ACCESS_PERMISSION}
      client-secret: ${IWS_A_CLIENT_SECRET}
      allowed-instance-ids: [iws-a-01]
      session-namespace: iws-a
    iws-b:
      name: IWS-B
      type: IWS
      terminals: [WEB, PDA]
      enabled: true
      api-base-url: ${IWS_B_PUBLIC_API_BASE_URL}
      allowed-frontend-ids: [web-main, web-branch, pda-main]
      access-permission: ${IWS_B_ACCESS_PERMISSION}
      client-secret: ${IWS_B_CLIENT_SECRET}
      allowed-instance-ids: [iws-b-01]
      session-namespace: iws-b
```

多个后端可以使用同一 Web/PDA 入口与对应回调，但后端 ID、协议凭据、会话及目标绑定仍区分。入口相同不意味着 API 相同；例如共用界面连接 A/B 时，各 `api-base-url` 指向各自公共 API 或受控代理前缀。若多个前端使用相对代理前缀，各前端部署都必须实际提供对应代理；开发代理不能作为生产代理已经可用的证据。

## 新增 Web 接入旧认证中心

默认一套 Web 和一套 PDA 可选择多个 IWS 后端；允许再增加一个 Web 部署，继续复用原认证中心。新增 Web 是前端接入端，不自动代表新增一个后端应用、账号来源或同类型会话名额。

### 新 Web 连接已有 IWS-A/B

1. 新 Web 使用原认证中心公开地址和原接口/适配契约，不搭第二个认证中心。部署地址和请求通道通过现有配置机制设置，不把服务端 client-secret 打包到浏览器。
2. 登录页从中心已有应用列表获取可选后端，复用原 applicationId、name、接入权限及菜单映射。当前控制器提供 `/iam/login/apps` 和 `/iam/portal/apps`；使用其实际终端、认证及返回值规则，不维护第二份后端清单。
3. 新 Web 是同一公开入口背后的扩容实例时，继续使用原入口/回调，不因增加前端进程重复登记 IWS-A/B，也不把前端实例填入后端 allowed-instance-ids。
4. 新 Web 使用不同域名、端口或独立入口时，需登记并精确校验该前端的入口、回调、退出及允许来源，与其可连接的原后端绑定。不能仅修改 DNS 或允许来源就认为认证协议支持了新回调；也不能把原 redirect-uri 全局替换为新地址，使旧 Web 无法登录。
5. 当前 `IamProperties` 已提供 `iam.frontends`，`IamProperties.Application` 通过 `allowed-frontend-ids` 绑定一个或多个受信前端；原应用单值入口已迁入前端登记，不再保留第二套入口推断。中心在应用列表、登录授权、票据、状态和退出协议中贯穿校验 frontendId、applicationId、terminal、state 与事务。
6. 多入口能力仍在原 IAM 配置体系内，不新建人员或平行系统登记模型。不同公开地址的 Web 分别登记 frontendId，同一 IWS 应用可同时允许这些入口；错前端、未绑定、错终端和错事务票据均拒绝。
7. 复用现有 Web 页面和统一适配器。建立已验证的目标上下文后，用户信息、业务 API、上传/下载、字典、WS/SSE 和退出全部使用同一绑定后端；不同前端域名的 Cookie、存储、允许来源及协议回调按各部署验证。

### 新 Web 同时连接新增 IWS-C

先按原 `iam.applications` 模型登记 IWS-C 的名称、IWS 类型、终端、公共 API、入口/回调、接入权限和协议参数，再配置新 Web 与它的受信入口绑定。使用原角色配置给必要人员分配原体系中的权限，不自动给所有用户补权；用户、角色、公司、部门和已有管理员继续沿用原数据。

新 Web 不是新增后端的必需条件：共用的旧 Web/PDA 也应能在登记生效后选择被允许的 IWS-C，不要求重新构建前端来写入后端地址或名称。

## 页面、菜单及权限如何配合

在中心复用原菜单配置页及角色分配页面，配置原 menuId 对应的 applicationId、WEB/PDA 和已注册 viewKey。优先使用原有元数据，必要映射使用原配置能力，不重写页面或新增平行菜单授权表。

最终可见菜单取目标系统配置、账号原角色权限、目标后端实际能力与终端页面注册的交集。显示名称仍取同一 `iam.applications` 的 name；界面映射不自动授予系统接入、功能或公司范围权限。中心自身 classpath 不决定 IWS 业务模块是否可用，业务接口仍自行执行授权。

## Job 与 AI 如何接入中心

Job 和 AI 不再接受新的人员密码登录，也不复制中心 Token 冒充原生身份。包装层按中心票据建立本地兼容 Token，并继续执行各自原用户、角色、namespace 或资源权限检查。

| 应用 | 中心登记 ID | 原生认证头 | 本地适配入口 |
|---|---|---|---|
| Job | `snail-job` | `SNAIL-JOB-AUTH` + `SNAIL-JOB-NAMESPACE-ID` | `iws-snailjob-server/.../iam/IamJobSsoController`、`IamJobSessionFilter`、`IamJobLocalSessionService` |
| AI | `snail-ai` | `Snail-Ai-Auth` | `iws-snailai-server/.../iam/IamAiSsoController`、`IamAiSessionFilter`、`IamAiLocalSessionService`、`IamAiStreamingRevocationAdvice` |

中心登记沿用 `iam.applications` 中的 `access-permission`：Job 为 `monitor:snailjob:list`，AI 为 `monitor:snailai:list`。这两项权限只决定能否进入目标系统；进入后仍由 Job 检查 namespace/group，由 AI 检查本地角色及资源关系。

本地身份必须显式映射，不能按同名自动合并。两份服务的外部配置使用同一结构：

```yaml
iam:
  client:
    public-base-url: http://127.0.0.1:18081/iam
    center-base-url: http://127.0.0.1:18081
    application-id: snail-job       # AI 使用 snail-ai
    application-type: SNAIL_JOB     # AI 使用 SNAIL_AI
    frontend-id: job-main           # AI 使用 ai-main
    instance-id: snail-job-1        # AI 使用 snail-ai-1
    client-secret: ${IAM_APPLICATION_CLIENT_SECRET}
    accounts:
      center-account-name: existing-job-or-ai-local-account
```

`accounts` 为空或中心账号没有显式映射时，回调明确拒绝，不创建本地用户、不映射成管理员。Job/AI 的机器协议不经过人员认证头；关闭人员密码登录不删除机器 API、调度客户端令牌、AI OpenAPI app token 或模型密钥。

外部配置生成或轮换 `client-secret` 后，中心与对应应用必须使用同一版配置一起重启；只重启一侧会按设计拒绝签名请求。

AI 流式撤销需按三层区分：`snail-ai 1.1.1` 使用 Spring AI `2.0.0` / OpenAI Java SDK，包装层 `IamAiGrpcCancellationAspect` 与 `IamAiStreamCancellationRegistry` 能取消中心到 Agent 的 gRPC `ClientCall`，人员 HTTP 流也会在撤权复核后终止；但上游 `ClientChatExecutor.executeStream` 当前丢弃 Reactor `Disposable`，`ChatDispatchStreamingHandler` 也未把 gRPC 取消桥接到该订阅，因此不能证明外部模型 HTTP 请求被取消。验收报告保留 `serverGrpcCancelled=true` 与 `upstreamModelCancellation=false` 两个独立字段；不得把身份门禁、服务端 gRPC 取消或 HTTP 流结束写成上游模型 SDK 已完成取消传播。

## 新旧 Web 会话如何处理

同一权威账号和同一应用类型只允许一个有效应用会话，覆盖全部 Web/PDA 及不同设备。新 Web 成功登录 IWS-B 后，旧 Web/PDA 的 IWS-A 会话失效；同一后端上的新独立登录也遵守此限制。前端扩容、多标签复用同一有效会话和普通刷新不算新登录。

Job、AI、监控分别属于其他类型，可以按权限继续使用。应用互踢不清除统一中心身份，也不撤销机器凭据。被替换端停止操作和自动重连，不能静默再次登录反向踢掉新会话；失败或无权限的登录不能踢掉旧会话。

## 前端如何标记自身与绑定多个入口

使用 `frontendId` 表示一个受信前端入口，例如 `web-main`、`web-branch`、`pda-main`。它与后端 `applicationId`、后端节点 `instanceId`、登录渠道 `sys_client.client_id` 是不同概念。一个 frontendId 可以连接多个获准后端，一个后端也可以允许多个 frontendId；增加前端不能复制原后端登记或增加同类型会话名额。

当前代码已支持该绑定结构。新增内容仍属于原 IAM 配置，不是第二份后端系统清单，也不新增账号数据模型。

| 位置 | 目标配置及用途 |
|---|---|
| 中心 YAML 的 `iam.frontends.<frontendId>` | 只登记前端入口：enabled、terminal、公开基址及精确 launch/redirect/logout 地址；不复制系统名称、后端 API、角色或人员名单 |
| 原 `iam.applications.<applicationId>.allowed-frontend-ids` | 允许连接此后端的 frontendId，继续复用本应用 name/type/API/权限/凭据 |
| Web 部署运行时公开配置的 `frontendId` | 指明本 Web 是 web-main 还是 web-branch，登录开始前读取；不是通过当前所选后端决定 |
| PDA 既有启动配置的 `frontendId` | 指明受信 PDA 入口；沿用平台配置能力，不要求各后端重打安装包 |

**frontendId 配置位置与读取方式**

| 配置对象 | 明确位置 | 配置内容 |
|---|---|---|
| Web 开发/源码模板 | Web 仓库 `public/frontend-config.json` | 本部署 frontendId、认证中心公开基址 iamBaseUrl |
| Web 发布运行时 | 实际 Web 静态发布根下的 `frontend-config.json`，普通 Web 构建通常为 `dist/frontend-config.json` | 运维在部署时覆盖此公开 JSON，不改 JS 构建物；实际构建输出目录不叫 dist 时放在对应输出根 |
| PDA | PDA 仓库现有 `config.js` | 导出 `iamFrontendId`（如 pda-main）与 `iamPublicUrl`；构建键为 `VITE_IAM_FRONTEND_ID`、`VITE_IAM_PUBLIC_URL`，并保留原 `process.env` 回退，不另建后端列表 |
| 认证中心 | 认证中心 JAR 旁 `config/auth-center/application.yml` | `iam.frontends.<frontendId>` 的键必须与终端所填标识一致，原应用 allowed-frontend-ids 也引用同一键 |

Web JSON 完整最小内容如下，示例中心地址须替换为终端实际可访问的部署地址：

```json
{
  "frontendId": "web-main",
  "iamBaseUrl": "http://iam.example.test"
}
```

新 Web 部署使用同一构建时，仅将该部署 JSON 中 frontendId 改为 `web-branch`，并在原中心 YAML 登记 web-branch 的精确入口/回调及后端绑定；不需要为选 A/B 修改 JSON 或重打包。系统 ID/名称/后端 API 仍只从中心原应用登记取得，此 JSON 不存系统列表或秘密。

读取 URL 相对 Web 的已配置公开基址：Web 位于站点根时读取 `/frontend-config.json`；部署在 `/iws/` 子路径时读取 `/iws/frontend-config.json`。实现使用当前运行入口的受控 base path 拼接，不能硬编码站点根或按当前业务路由目录拼接；配置请求不得被 SPA 回退成 index.html。构建/发布流程须保留模板并确保实际公开 URL 可访问，不能只在源码放文件就宣称生效。

读取在应用列表、登录跳转及路由鉴权启动之前完成，整个运行上下文固定使用已核验的本部署标识；缺文件、非 JSON、空/未知 frontendId 或无效中心基址明确提示配置错误，不猜默认前端或读另一个部署的存储。此文件为同源公开资源，不携带认证 Token，也不通过读取它创建或抢占会话。

该稳定文件必须重新验证或禁用缓存；Service Worker/离线包不得长期缓存旧 frontendId。部署修改后通过重新加载页面读取，验证浏览器实际请求 URL、响应与随后登录参数一致；不宣称已经实现运行中自动热替换。PDA 如只有打包配置，按其现有发布流程更新前端入口标识，但同一个 PDA 包切换不同 IWS 后端仍不修改 frontendId。

上述文件已存在，Web 读取入口为 `src/utils/frontendConfig.ts`，应用启动在路由鉴权前调用 `ensureFrontendConfig()`；发布构建由 `scripts/finalize-auth-build.mjs` 保留运行时 JSON。服务器环境变量、Vite 构建时 env 和浏览器运行时 JSON 仍是不同层次，不能只在服务器设置变量就宣称浏览器可读。

中心登记示例如下：

```yaml
iam:
  frontends:
    web-main:
      enabled: true
      terminal: WEB
      public-base-url: ${WEB_MAIN_PUBLIC_BASE_URL}
      launch-uri: ${WEB_MAIN_LAUNCH_URI}
      redirect-uri: ${WEB_MAIN_REDIRECT_URI}
      logout-uri: ${WEB_MAIN_LOGOUT_URI}
    web-branch:
      enabled: true
      terminal: WEB
      public-base-url: ${WEB_BRANCH_PUBLIC_BASE_URL}
      launch-uri: ${WEB_BRANCH_LAUNCH_URI}
      redirect-uri: ${WEB_BRANCH_REDIRECT_URI}
      logout-uri: ${WEB_BRANCH_LOGOUT_URI}
  applications:
    iws-a:
      allowed-frontend-ids: [web-main, web-branch]
    iws-b:
      allowed-frontend-ids: [web-main, web-branch]
```

同一公开入口背后的扩容副本共用 frontendId；不同受信公开入口使用不同 frontendId。PDA 按其已注册的平台回调登记同一逻辑关系，不能照抄 Web 回调或通过把 IWS 类型改成 PDA 逃逸互踢。前端公开基址是部署位置，不能从用户任意输入的 URL 动态注册。

登录/切换步骤：
1. 前端读自己的 frontendId，向原应用列表及登录授权能力传 frontendId、terminal、所选 applicationId。展示仍使用原应用 name，不能用 frontendId 代替目标后端。
2. 中心核验前端/后端启用、终端相符、allowed-frontend-ids 关系及原账号准入，回调从登记中选取，不信任浏览器任意传入地址。origin/代理来源按受控部署配置校验，但不把可伪造的公开 frontendId 当成身份凭据。
3. 将 issuer/userId、frontendId、applicationId、terminal、state、登录事务、选定回调及会话代次贯穿授权、票据核销、应用会话、界面上下文与退出；旧入口/错绑定的票据不可用于另一入口。服务端协议调用仍由原后端凭据认证，不向浏览器发 client-secret。
4. 登录后回到发起的受信前端，装配所选后端上下文；相对 API 代理前缀必须在每个前端部署实际提供。不同入口有网络差异时复用现有部署通道，不能直接拿另一入口的相对地址当成当前已可用地址。
5. 迁移当前单值 launch/redirect/logout 的入口职责到前端登记，并同步原控制器、校验和配置模板；不长期双读猜测回调，不仅把旧字符串改成列表。中心自己的人员根会话仍在中心，前端 ID 不参与拆分账号+applicationType 的互斥键。

标识可以公开，不能作为客户端密钥、授权凭证或安全证明。未知/停用 frontendId、未绑定的后端、终端/回调错配、缺失启动标识均明确拒绝，不猜一个默认入口。验证至少覆盖两个不同地址的 Web 同时被中心登记、各自连接同一后端及各自选择 A/B，正确回到原入口；不同账号可并存，同账号同类型后登录仍替换前会话。

## 复评修复与标签图标

- 写请求不能使用读租约快路径。IWS 与监控只对 GET/HEAD/OPTIONS 使用默认 30 秒读租约，中心不可达时新写请求直接返回 `503 统一身份中心不可达`。
- 监控 `/admin/instances` 只允许真实机器认证绕过人员 SSO 过滤；已登录人员的 Cookie/CSRF 请求仍校验中心状态，撤权后返回 401，机器 Basic 仍可用。
- 中心根会话绑定共享授权指纹，覆盖菜单权限、角色、`dataScope`、自定义部门范围和部门树，但排除各应用接入权限；IWS/Monitor 在应用状态查询中单独校验本应用 `access-permission`。角色范围收紧后旧 Token 的查询和修改均被拒绝，单独撤销 IWS-A 不会注销 IWS-B 或 Monitor。
- IWS 使用 Redis 持久待撤销队列，监控使用本地持久待撤销队列并定时重试。中心停机时，`/admin/logout` 在过滤器放行后仍由 Spring Security 执行 CSRF 校验和本地 LogoutHandler，合法注销会把待撤销 rootSid 原子写入队列；Monitor 重启后队列保留，中心恢复后周期任务只删除确认成功的项。浏览器链和机器链均使用 Cookie/Header 标准 `CsrfTokenRequestAttributeHandler`；无令牌的浏览器注销返回 403，带合法 `X-XSRF-TOKEN` 的注销返回 302。读取、写入或原子替换失败仍返回 `persistenceFailed=true`，不再报告已登记自动重试。
- 授权请求在激活任何应用占位前完成 state、returnPath 和应用配置校验；激活后签票失败会回滚本次占位，非法请求不会踢掉原有效会话。
- 中心独立构建的登录页从 `/iam/iws-auth-icon-transparent-20261104.png` 与 `/iam/iws-auth-icon-transparent-20261104.ico` 加载透明底 IWS 标签图标；IWS Web、PDA H5 shell、Job、AI 和 Admin 监控也已完成各自静态制品接入。最终 JAR/页面实际请求、MIME、透明 alpha 和 ICO 头以隔离证据为准，不能只检查源码文件存在。

复评证据位于 `C:\code\company\innolux-iws\work\iam-web-runtime-20261103-v18`：`review-repair-acceptance.json`、`review-logout-pending-acceptance.json`、`pending-iwa-acceptance.json`、`n3-revoke-scope-acceptance.json`、`icon-matrix-acceptance.json`、`monitor-icon-acceptance.json` 及对应截图。第二轮报告与修复说明位于 `C:\code\company\innolux-iws\work\iam-acceptance-review-20261104-round2`。验证码关闭是用户指定基线，本轮修复和图标接入未改动该配置。

## 接入验证与配置生效

- 核对最终制品配置来源、profile、公开地址及实际代理；默认按现有加载机制重新启动生效，不承诺 YAML 自动热更新。
- 使用原统一管理账号与普通角色分别验证，检查新旧 Web 返回同一权威 userId，不把普通用户映射成管理员。
- 已用同一 Web 构建和两个运行时 frontendId 验证 web-main 登录 IWS-A、web-branch 登录 IWS-B，并准确返回各自入口；增加前端未复制后端登记、账号、权限或业务页面。
- 验证同账号后登录替换旧会话、不同账号互不影响；列表加载、目标预选、刷新不会抢占。
- 已覆盖精确回调、票据重放、并发跨副本核销、应用撤权、账号停用、密码变化、统一退出和中心短时不可达；错前端、错终端及更多迟到回调场景仍按第 13 节继续保留。
- 从同一 Web 构建切换 A/B，核对实际 API、上传/下载、长连接、菜单和公司范围；改变原 YAML 名称/入口并生效后展示一致。
- 新域名下按真实 HTTP/HTTPS、跨域或同源代理方式验证；未运行、未实现或缺少终端实测的项目如实保留，不以配置说明代替验收。

最终隔离证据位于 `C:\code\company\innolux-iws\work\iam-config-validation-20261103-v17` 和 `C:\code\company\innolux-iws\work\iam-web-runtime-20261103-v18`；当前 JAR、PID、端口、启动命令、Web 目录和源码 HEAD 记录在 `final-runtime-20261105.json`，不使用旧 `final-runtime.json` 代表最终版本。`same-build-switch-acceptance.json` 记录同构建 A→B、旧 A 401、B 退出和 SSE 头；`iam-identity-routing-acceptance.json`、`iam-g20-split-acceptance.json`、`iam-g21-scope-matrix-acceptance.json`、`iam-g21-business-scope-acceptance.json`、`iam-g24-dept-move-scope-acceptance.json` 记录权威身份路由、独立业务库和权限事实更新；`iam-write-center-outage-valid-business-acceptance.json`、`iam-push-center-outage-v2-acceptance.json`、`iam-ws-ticket-browser-v3-acceptance.json`、`iam-ws-cross-replica-acceptance.json` 记录写门禁、长连接失效和一次性票据；`iam-g22-twotab-samebuild-acceptance.json`、`iam-tls-cross-site-acceptance.json`、`iam-tls-third-party-cookie-acceptance.json`、`iam-mysql-failure-acceptance.json` 分别记录双标签、本机 HTTPS/跨站/iframe、禁第三方 Cookie、HTTP IndexedDB 和数据库故障恢复；`iam-job-executor-acceptance.json` 记录执行器注册、真实执行、人员退出不影响机器身份、掉线恢复和错误凭据拒绝；`ai-full-acceptance.json` 记录 AI 人员流、服务端 gRPC 取消、原生权限、会话隔离和 app token 分离，同时明确保留 `upstreamModelCancellation=false`；`monitor-acceptance.json` 复验人员 SSO、机器 Basic/Actuator 和浏览器路径隔离；`pda-h5-acceptance.json` 复验最终 H5 A/B、映射、存储和旧 Token 替换。PDA APK/真机、AI 上游模型 HTTP 取消、生产代理/外部证书及通知重投极端矩阵仍保留为未完成项。

Monitor 注销恢复的当前证据为 `iam-monitor-pending-restart-acceptance-v2.json`，记录无 CSRF 403、合法 CSRF 302、待撤销文件写入、Monitor 重启保留、中心恢复后队列清空及旧根 Token 401；`iam-monitor-pending-restart-failure-csrf-20261105.json` 和 `iam-monitor-pending-restart-failure-browser-csrf-20261105.json` 保留修复前失败历史，不作为关闭证据。
