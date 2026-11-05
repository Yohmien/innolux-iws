# INNOLUX IWS PDA

## 统一身份与界面切换适配契约

状态（2026-11-05）：PDA 源码已接入统一登录、受信 `iamFrontendId`、应用列表、票据回调、会话上下文和页面适配；认证中心/IWS 侧的最终 HTTP、权限撤销、会话替换和故障传播矩阵已通过隔离验证。已使用官方 Vue3/Vite uni-app 构建链生成 H5，并在 2026-11-05 最终复验 IWS-A 登录、A/B 切换、hash 回调、页面映射、原有工作台菜单、请求前缀、Web 存储和旧 A Token 替换。PDA H5 页面标签使用透明 IWS PNG/ICO；`manifest.json` 既有 schemes 差异、launcher、桌面、启动和 splash 资源保持原样。APP-PLUS 登录已改为应用内 WebView 拦截 `iws-pda://` 回调，不再主动打开外部浏览器；尚未完成 HBuilderX APK、真机 WebView 与设备存储隔离验收，不能把 H5 或单测通过解释为原生终端已通过。

### PDA 应用内统一登录方案

目标是不改变认证中心的一次性 `ticket + state` 协议，只改变 PDA 的承载方式：登录页仍在 `pages/login`，点击统一登录后由应用内 WebView 打开认证中心登录页；登录完成、认证中心跳转 `iws-pda://sso/callback?...` 时由 WebView 拦截，不交给系统浏览器或外部应用。原有登录页的 `sso-start-card` 和按钮样式直接复用，不新增中间页面。

实现入口：

| 文件 | 职责 |
|---|---|
| PDA `utils/iam-sso.js` | H5 保持顶层跳转；APP-PLUS 创建登录事务后打开应用内 WebView；取消时只清理与当前 `transactionId` 匹配的事务 |
| PDA `utils/iam-login-webview.js` | 创建单例 `plus.webview`，精确拦截 `^iws-pda://sso/callback(?:\\?|$)`，关闭窗口并跳转现有回调页 |
| PDA `pages/sso/callback.vue` | 原有票据核销、会话建立、更新门禁和页面导航不变 |
| PDA `App.vue` | 保留 `plus.runtime.arguments` 深链处理，作为系统回调兜底；主链路不依赖外部浏览器 |

运行流程：

1. `pages/login` 调用 `startIamLogin`，生成并保存一次性登录事务。
2. APP-PLUS 使用 `plus.webview.create` 打开 `/iam/login`，H5 仍使用当前页面的顶层跳转。
3. 认证中心完成登录和授权后返回 `iws-pda://sso/callback?ticket=...&state=...`。
4. WebView 的 `overrideUrlLoading` 只拦截精确回调地址，关闭 WebView 后通过 `uni.reLaunch` 进入 `pages/sso/callback`。
5. 回调页继续执行原有 `consumeIamState`、票据核销和导航；取消、返回或关闭窗口时清理当前事务并返回登录页。

认证中心配置：

```yaml
pda-main:
  enabled: true
  terminal: PDA
  client-mode: APP
  public-base-url:
  launch-uri: iws-pda://pages/work/index
  redirect-uri: iws-pda://sso/callback
  logout-uri: iws-pda://pages/login
```

`client-mode: APP` 时不使用 `public-base-url`；`BROWSER` 模式仍要求 HTTP(S) 或同源路径。回调 URI 必须由认证中心精确登记，不能由客户端动态传入；否则会把一次性票据交给未受信地址。应用内 WebView 使用自己的第一方 Cookie 空间，不复用系统浏览器登录态，但同一 APK 后续登录可复用该 WebView 的认证中心会话。不同 PDA 共用认证中心地址，不需要为每台设备登记 IP。

验收边界：

- 已通过 Node 单测：应用内回调拦截、用户关闭取消、重复打开替换、创建失败清理 4/4；既有回调交错和请求隔离回归 13/13。
- 已通过认证中心配置校验测试：APP 无公开基址、APP 拒绝公开基址、BROWSER 要求 HTTP(S)、APP 空公开基址通过 Bean Validation 4/4。
- 仍需用 HBuilderX 生成 APK 并在 Android 真机验证：系统浏览器未被打开、WebView Cookie、返回键、断网、应用后台恢复、重复回调和生产 HTTPS。

### 统一适配入口与上下文

Web 与 PDA 采用同一逻辑契约，分别适配浏览器与 uni-app 的路由、请求、存储和连接生命周期；复用现有身份绑定及请求能力，不另建两套权限引擎。PDA 实际入口为 `utils/iam-sso.js`、`utils/iam-session.js`、`utils/iam-view-adapter.js` 和 `pages/sso/callback.vue`；对应 HTTP 能力如下：

| 能力 | 输入 | 结果及约束 |
|---|---|---|
| `listApplications` | 当前 frontendId、终端类型、可选的有效统一身份 | 登录前取得已绑定入口的最小可公开选项，登录后按权限取得目标应用；读取列表不创建应用会话、不互踢 |
| `activateApplication` | 已登记 frontendId、applicationId、终端类型、受控返回位置、幂等登录事务标识 | 检查入口绑定、身份及准入，建立目标应用会话并提交同类型占位；只由明确登录/切换动作触发，拒绝任意后端 URL |
| `loadViewContext` | 已核验的应用会话 | 返回当前权威主体、应用、会话代次、终端、菜单/页面映射及可用能力；查询不重新激活应用 |
| `applyViewContext` | 仍匹配当前身份/应用/代次的上下文 | 清理前一上下文，按平台装配菜单、已有页面及请求目标；旧响应不得覆盖新上下文 |
| `handleSessionReplaced` | 后端明确的会话替换结果 | 冻结旧业务操作、取消待处理请求、关闭连接并提示重新选择；不静默 SSO 抢回会话 |

权威上下文最少包含：`issuer`、原 `userId`、`applicationId`、`applicationType`、`terminal`（WEB/PDA）、会话标识、`sessionGeneration`、菜单映射版本、菜单项及能力集合。有效会话凭据按现有受控通道处理，不能因加载上下文把中心 Token 或机器凭据暴露给页面配置。会话代次是运行状态，放在原框架/Redis，不给账号表新增字段。

applicationType 由中心受控登记，IWS-A/B 都属于 IWS；Job、AI、监控分别为自己的类型。终端类型、部署配置键、节点 instanceId 不参与拆分人员互斥范围，客户端不能通过自报另一个类型绕过限制。列表中的后端地址来自受控应用登记，不允许用户随意输入 URL 并携带现有凭据访问。

### 前端如何标记自身与绑定多个入口

使用 `frontendId` 表示一个受信前端入口，例如 `web-main`、`web-branch`、`pda-main`。它与后端 `applicationId`、后端节点 `instanceId`、登录渠道 `sys_client.client_id` 是不同概念。一个 frontendId 可以连接多个获准后端，一个后端也可以允许多个 frontendId；增加前端不能复制原后端登记或增加同类型会话名额。

当前后端 `IamProperties` 已提供 `iam.frontends` 与应用的 `allowed-frontend-ids`；PDA 在 `config.js` 导出 `iamFrontendId`，并在登录、回调、上下文与退出链路中校验。新增内容仍属于原 IAM 配置，不是第二份后端系统清单，也不新增账号数据模型。

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
| PDA | PDA 仓库现有 `config.js` | 导出 `iamFrontendId`（如 `pda-main`）与 `iamPublicUrl`；构建键为 `VITE_IAM_FRONTEND_ID`、`VITE_IAM_PUBLIC_URL`，并保留原有 `process.env` 回退。不另建后端列表 |
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

Web 的 `frontend-config.json` 和读取代码已在 Web 仓库落地；PDA 通过 `config.js` 优先读取 `import.meta.env.VITE_IAM_FRONTEND_ID` / `VITE_IAM_PUBLIC_URL`，并保留原 `process.env` 兼容回退。构建时 env、服务器环境变量和浏览器运行时配置是不同层次；本轮 H5 已用构建时 env 验证产物，原生包仍需按实际 uni-app 发布流程生成并验证。

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

### 一套 Web/PDA 对接多个 IWS 后端

默认部署为一套共用 Web 和一套共用 PDA，分别使用同一份前端代码/构建或安装包，按所选 applicationId 对接 IWS-A、IWS-B 等不同后端；不是每个后端各部署、编译或安装一套 Web/PDA。中心认证 UI 可以独立发布，Job/AI/监控原生控制台仍按自身接入，不将它们误认为 IWS 前端副本。

登录/切换处选择的是“可对接的后端”，不是选择另一套前端。选项来自原 YAML 应用登记，按启用状态和终端可对接能力展示，登录后再按账号接入权限校验；可用状态只作反馈，不能替代服务端鉴权。选定 IWS-A/B 后仍留在共用终端界面，通过已绑定的后端上下文加载其菜单、能力和业务数据。

- applicationId 标识目标后端应用，terminal 标识共用 Web/PDA 入口。多个 IWS 可共用同一前端入口及对应终端回调地址；它们的服务端协议身份、凭据、会话命名空间和准入检查仍分别登记。同一回调地址必须通过已绑定并核验的 applicationId/state/登录事务确定目标，不能按回调 URL 猜后端，也不能接受临时拼接的任意后端。
- 区分前端入口和后端 API：`launch-uri` 是界面进入位置，不能当作业务请求 baseURL；API 地址或同源代理前缀来自该应用已有的受控登记。若当前登记只有 launch/redirect/logout 而没有 API 目标，只在现有 `iam.applications` 项内最小补充 `api-base-url` 等实际缺失字段，不新建前端系统清单或配置平台。前端只取得浏览器/终端可用的公共地址/代理前缀，不取得内部通信或协议凭据。
- 配置加载后，适配器将目标后端、会话、菜单/能力及其代次作为同一上下文提交。票据核销、当前用户、业务 API、上传/下载、字典、WS/SSE 及退出均路由至已绑定后端；不能只切 Axios 默认地址而让部分调用继续连接 A，也不能让认证中心代替 B 执行领域授权。
- 切换不重编译前端，不按后端复制 `.env`/安装包。开发时复用当前 Vite 代理；发布环境复用实际可用的请求/代理通道并验证，Vite dev proxy 不能作为生产已有代理的证据。跨域直连按部署明确 CORS、凭据和允许来源；PDA 原生另核验实际网络及回调约束，不把同源代理当前必然存在当成事实。
- 在共用界面内切换 A/B，通过现有登录选择器及同一认证中心复用身份完成；不同后端的页面差异由中心菜单映射、原角色权限、目标实际能力及终端页面注册决定，不需要给 B 新写一套前端页面。
- 继续执行账号+应用类型的单会话：切到 B 成功激活后 A 失效，跨 Web/PDA/设备一致；共用前端不允许两套后端 Token 同时成为活跃上下文。过期响应、旧退出或后台重连不得把已切换的请求重新指向 A。HTTP/HTTPS 两种部署均验证。
- 验收必须只使用一份 Web 构建和同一 PDA 构建/安装包，在不替换环境文件、不重新部署前端的情况下选择 A、切换 B，验证真实请求目标、身份、原页面差异、公司权限及互踢；同时覆盖两个后端复用回调时的串票拒绝、上传/下载和长连接路由。不能通过两套前端分别连 A/B 代替验收。

### 登录页选择目标系统

复用现有登录页增加目标系统选择，不新增独立登录页、系统目录、名称配置文件或系统登记表。直接复用认证中心现有 YAML 的 `iam.applications` 及绑定的 `IamProperties.Application`；显示名称、入口和 SSO 登记使用同一份配置，不在前端另维护 IWS/Job/AI/监控名称或地址数组。

| 界面所需信息 | 已有配置来源 |
|---|---|
| applicationId | `iam.applications` 的 Map 键 |
| 系统显示名称 | 对应项 `name` |
| 业务类型及终端 | 对应项 `type`、`terminals` |
| 启停 | 对应项 `enabled` |
| 前端入口、回调及退出 | `iam.frontends.<frontendId>` 的 `launch-uri`、`redirect-uri`、`logout-uri`；后端项只用 `allowed-frontend-ids` 声明绑定 |
| 后端 API 或公共代理前缀 | 同一应用登记已有字段，缺失时仅在该项最小补充 `api-base-url`；不得把前端 launch-uri 当作 API 地址 |
| 接入资格 | 对应项 `access-permission`，仍由原角色权限服务校验 |

上述是已核对的现有配置字段，不要求新建 `login.systems`、第二份 `displayName` 或门户专用应用清单。继续遵守按服务分目录外部 YAML 优先和包内回退；系统登记在中心自身配置中管理，不逐个扫描其他服务的配置文件。配置生效遵循当前启动/重载机制，不因名称展示需求新增热更新平台。

1. 未登录时可通过既有应用列表能力的最小扩展取得已启用且支持当前终端的系统 ID、名称及必要展示信息，供登录前选择；不返回接入密钥、机器凭据、内部通信地址或权限明细，不把“可显示”当成已授权。登录后再按原角色权限过滤与校验；复用现有 Controller/Service 和配置投影，不为此建立独立系统列表模块。
2. 用户选择已登记的 applicationId 后登录。该选择绑定同一登录事务、state、terminal 和受控返回位置；认证成功后重新校验登记、启用、终端及接入权限，再建立所选系统会话、执行同类型占位，进入其受控入口并加载对应菜单/页面。
3. 从应用直接访问时可携带受控目标 ID 预选；已登录中心后明确选择另一目标可复用身份进入，不要求再次输入密码。点击选择或加载列表本身不激活、不互踢；必须明确确认登录/进入才提交激活。
4. 可记忆上次选择的 ID，但名称和入口每次从服务端配置取得。非法、删除、停用、终端不匹配或无权限目标明确拒绝/重新选择，不悄悄进入默认系统；前端参数、本地缓存及任意 returnUrl 不能替换配置中的权威入口。
5. 修改同一 YAML 登记项的 name/入口后，配置生效时登录选择器、已复用的应用导航和系统切换展示一致；同一 ID 继续关联原菜单映射和权限。中心菜单页面引用这些已有应用 ID/名称，不能再维护另一份系统名称来源。
6. Web/PDA 同样执行本契约，使用当前终端已支持的入口与页面；PDA 是终端维度，访问 IWS 的 PDA 会话仍属于 IWS 类型，不能通过单列 PDA 类型规避同类型互踢。多终端入口适配在原应用登记及既有终端逻辑内完成，不复制应用身份或另建名称表。

Web 侧已验证同一构建的不同运行时 frontendId、回调、普通角色拒绝、Token 隔离、同类型互踢、统一退出、停用/改密/撤权和多服务组合。PDA H5 已验证顶层跳转、A/B 切换、hash 回调和页面映射、落地请求前缀、存储及旧 A Token 替换；APP-PLUS 已改为应用内 WebView，回调拦截和取消清理仅有单测，APK 真机、同账号 Web/PDA 跨设备互踢、断网/重启恢复和真机存储隔离仍待设备条件。

### 认证中心配置系统与界面

在认证中心复用现有菜单配置页及角色分配能力，增加必要的目标系统/终端/已有页面选择适配，不能重写菜单管理或角色授权页面。配置对应关系为：

`applicationId + terminal + menuId -> viewKey + 所需能力`

- menuId、菜单树、权限码及角色菜单关联仍沿用原体系；映射优先使用已有可用配置元数据，确实缺少承载位置时用原 sys_config 配置服务存映射，不新增账号/角色/组织或第二套菜单授权表，不为此修改原账号模型。
- viewKey 是受控组件注册键。Web 注册键映射原 Vue 页面，PDA 注册键映射原 uni-app 已登记页面；终端各有注册表，但统一校验映射与能力，不要求两端具有相同组件路径。
- 页面只能从构建时白名单中选择，保存和读取时均校验系统、终端、菜单及 viewKey 的有效性。禁止远程脚本、任意动态组件路径、未经登记的跨系统跳转；配置不能使 PDA 跳到一个仅 Web 存在的页面。
- 最终可见界面为“目标应用菜单配置 ∩ 当前账号菜单权限 ∩ 目标后端实际部署能力 ∩ 当前终端已注册页面”。以当前目标后端实际能力过滤，不能以认证中心自身 classpath 判断 WMS 菜单是否可用。
- 后端接口仍执行原接入、功能、公司及对象范围鉴权，隐藏菜单不代替鉴权；页面映射不能自动授予角色或扩大数据范围。已有角色配置决定可访问菜单，不另建人员页面白名单。
- 修改映射后刷新/失效对应应用和终端的菜单上下文，记录版本及原审计；不能复用另一应用的过期菜单。菜单编辑可校验已登记的组件元数据，不要求所有业务后端在线才能启动中心。

### 同类型仅一个有效应用会话

互斥范围按同一中心权威主体（issuer、userId）和 applicationType，全终端、全设备、全部根会话统一计算。IWS-A/B 后登录成功替换前会话；Web 与 PDA 也遵守，不能按浏览器、rootSid 或 terminal 分别保留一份。不同应用类型可以同时使用：进入 IWS-B 不踢 Job/AI/监控。统一中心根登录仍有效，应用互踢不等于全系统退出。

“只登录一次”指同一有效统一认证上下文可以复用身份；“同类型单会话”限制同类应用的有效会话数量。多副本是同一应用会话的不同承载节点，请求切换副本不算新登录。多个标签页可以使用同一有效会话，不因此产生多个占位。

1. 新应用必须先通过身份、准入及必要配置检查，再以中心原子激活操作递增会话代次并替换原占位；线性化点为中心成功激活，非点击时间或响应到达顺序。失败登录或无权限目标不能踢掉旧会话。
2. 重试同一事务返回同一激活结果，不再次递增代次；并发激活只有原子顺序中的最后一次有效。旧票据、迟到回调及后台重定向不得重新激活、撤销新会话或反向踢人。激活已成功但回调丢失时按同一事务恢复，不能声称跨服务建立会话是原子事务。
3. 各后端校验当前会话与中心该主体/类型的有效占位及代次；被替换会话的后续受保护请求拒绝，不能仅依赖前端推送或仍有效的签名。中心状态不可确认时依原故障策略拒绝，不回退本地超管。撤销通知加速关闭 WS/SSE/AI 流，失联连接的复核与时限按方案执行。
4. 撤销/退出用主体、应用、会话及代次匹配执行，旧应用退出或延迟通知不得清除新占位。全系统退出仍按原根会话/设备粒度处理，不能遗漏其他类型；不能因为应用互踢清除无关根身份或机器凭据。
5. 被替换端显示“该账号已在另一同类型系统登录”，冻结业务操作，停止自动重连和静默换票。只有人员明确再次登录/切换才可争取新会话，防止两个页面自动抢占循环。
6. 已通过检查的在途业务写入仍遵守方案原有受理检查点及事务边界，不承诺撤权能回滚已经提交或已受理的操作。UNKNOWN、离线待确认命令和资源占用不能因互踢被删除或自动重放。

### 切换时的页面与数据隔离

切换前校验目标是否为当前已激活应用；普通刷新和重复选择只恢复上下文，不新建登录事务。明确的新登录继续执行单会话替换策略。切换中暂停旧业务提交；失败时保留仍有效的原上下文或明确失效状态，不能悄悄连接另一后端。

切换成功后，按身份、applicationId、sessionGeneration 和终端隔离路由、菜单、用户、权限、公司范围、字典/业务缓存、页签和连接。旧后端响应、401、异步回调和推送不得回填新页面；保存原页面/组件，但不能带着 A 的对象 ID、公司选择或表单草稿在 B 上提交。确需保留的离线/未确认数据按来源隔离，提示处理，不直接删除。

原登录、账号管理、角色分配、部门/岗位/菜单及已有 WMS 页面继续复用；适配器只负责选择与装配，不重写页面，不在界面配置中加入业务执行代码。

### 必须验证

- 同一账号从 Web 登录 IWS-A，再从 Web/PDA 或其他设备登录 IWS-B：A 的所有旧同类型会话失效，B 正常；Job/AI/监控仍按各自会话和权限使用。
- 同一 IWS 应用跨副本、页面刷新及多标签共用原会话不互踢；不同账号不互踢；同类型的新独立登录按策略替换原会话。
- 登录失败、无准入权限、并发激活、重复事务、回调丢失与乱序、旧退出及通知失败，均不会放行两个有效占位或误删新会话。
- 被替换端不会静默抢回会话、自动重连或重放命令；Web/PDA 的旧响应与缓存不串系统。
- 认证中心配置同一菜单在 IWS-A/B、WEB/PDA 的不同已有 viewKey，验证普通角色可见性、实际后端能力、页面注册校验及接口越权拒绝；非法或跨终端页面配置不能生效。
- 实施回填实际接口/模块路径、构建及真实多服务/浏览器/PDA 验证证据；未执行项显式保留，不能用文档内容或 mock 代替实现。

PDA H5 shell 的标签图标证据为 `icon-matrix-acceptance.json` 中的 `pda-h5-shell` 项：PNG/ICO 请求均为 200，PNG 解码为 1254×1254、alpha 最小值为 0，ICO 头有效；该证据不代表任何 APK/IPA 原生图标已替换。

最终 PDA H5 证据位于 `C:\code\company\innolux-iws\work\iam-web-runtime-20261103-v18\pda-h5-acceptance.json`：同一 H5 构建登录 IWS-A 后切换 IWS-B，未重复输入密码，上下文和请求前缀分别为 `/iws-a-api`、`/iws-b-api`，页面映射版本与“在库管理”入口实际生效，刷新保持 B，旧 A Token 复核为 401，回调和导航未携带访问 Token；临时页面映射已清理。PDA 回调交错与请求隔离 Node 测试 11/11 通过。构建快照位于 `C:\code\company\ui\innolux-iws-pda-plus\unpackage\iam-h5-build-20261104-v2\dist\build\h5`，当前隔离入口为 `http://127.0.0.1:9022/`；最终 JAR、PID、端口和 Web 目录记录在后端 `work\iam-config-validation-20261103-v17\final-runtime-20261105.json`。APP-PLUS/APK、`iws-pda://`、真机文件/存储隔离和原生图标仍未验证，继续作为 IAM-02 和 SSO-14 的未完成项保留。
