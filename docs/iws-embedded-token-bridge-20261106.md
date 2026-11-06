# IWS 嵌入应用一次性令牌桥接

## 目标

外层 IWS Web 已登录后，打开 AI 控制台、任务调度中心和 Admin 监控时，不再让 iframe 跳转认证中心登录页。外层 IWS 通过服务端向认证中心申请目标应用的一次性票据，嵌入应用消费票据后建立自己的本地会话。

## 范围

- 来源应用：`iws-a`，只接受其当前有效的应用会话。
- 目标应用：`snail-ai`、`snail-job`、`admin-monitor`。
- 目标终端：`WEB`。
- 不新增共享密钥，不复用 IWS 本地 Token 作为目标应用 Token。
- PDA、非嵌入应用和认证中心普通浏览器登录流程不变。

## 流程

1. Web 使用当前 IWS Token 调用 `POST /auth/bridge/issue`，请求目标 `applicationId` 和返回路径。
2. IWS 从当前 Token 读取 `rootSid`、来源 `frontendId`、应用会话标识、代次和终端，拒绝未在配置白名单中的目标应用。
3. IWS 使用既有应用 client secret 发起签名请求，调用认证中心 `POST /iam/bridge/issue`。
4. 认证中心校验来源应用签名和来源应用会话，确认根会话、用户、目标应用接入权限及唯一 WEB 目标前端，然后为来源用户准备目标应用会话并签发普通一次性票据。
5. 中心返回 `ticket`、`state`、目标 `applicationId/frontendId/terminal` 和过期时间。
6. Web 以 no-referrer iframe 加载目标应用桥接地址。
7. 目标应用使用既有 `inspect`、`exchange` 和本地会话服务完成票据核销。AI、Job 在桥接响应中直接写入本地 Token 后跳转首页，不再依赖第二次 `bootstrap` 请求或 `JSESSIONID`；Admin 继续建立 Spring Security 会话。

一次性票据仍由认证中心签发、绑定目标应用和 `state`，且保持单次使用和短有效期。浏览器不会接触任何 client secret。

## 接口

### IWS 来源接口

`POST /auth/bridge/issue`

```json
{
  "targetApplicationId": "snail-ai",
  "returnPath": "/"
}
```

需要当前 IWS 登录 Token。响应数据：

```json
{
  "ticket": "...",
  "state": "...",
  "applicationId": "snail-ai",
  "frontendId": "ai-main",
  "terminal": "WEB",
  "returnPath": "/",
  "expiresAt": 1793900000000
}
```

### 认证中心服务接口

`POST /iam/bridge/issue`

使用既有 `X-IAM-Application`、`X-IAM-Instance`、时间戳、nonce 和签名头。请求体包含来源根会话、来源应用会话、来源前端和终端、目标应用及返回路径。

### 目标应用桥接入口

- AI：`GET /snail-ai/iam/sso/bridge`
- Job：`GET /snail-job/iam/sso/bridge`
- Admin：`GET /admin/sso/bridge`

查询参数沿用普通回调：`ticket`、`state`、`applicationId`、`frontendId`、`terminal`、`returnPath`。桥接入口不要求浏览器事先保存回调 state，`state` 由中心票据绑定并在目标应用核销时校验。

## 安全约束

- 来源必须是已启用 `IWS` 应用，且当前应用会话仍与根会话匹配。
- 目标必须是已启用、非 `IWS` 的允许嵌入应用，且当前用户拥有目标应用接入权限。
- 目标前端必须唯一且为启用 `WEB` 入口；多入口时拒绝自动选择。
- 票据有效期沿用中心 `iam.ticket-ttl`，保持一次性消费和提交幂等。
- iframe 使用 `referrerpolicy="no-referrer"`；桥接页和票据接口禁止缓存。
- AI、Job 的桥接结果只存在于当前无缓存 HTML 响应和浏览器本地存储，不进入 URL，也不依赖代理环境下的 Servlet 会话 Cookie。
- 目标应用桥接入口只接受中心票据，不接收浏览器直接构造的用户身份。

## 验收

1. 外层已登录后首次打开三个嵌入页，不再出现认证中心登录表单。
2. 三个目标应用各自建立有效本地会话，页面进入正常首页。
3. 来源 IWS 会话失效、目标未授权、目标无唯一 WEB 前端、票据过期或重复消费时均明确拒绝。
4. 票据不会写入目标 URL history 之外的持久存储；目标本地 Token 不进入 URL。
5. Web、IWS、认证中心、AI、Job、Admin 的模块测试和构建通过。
6. 至少对 AI 完整链路执行隔离联调；Job、Admin 分别验证入口和会话建立。

## 部署

### 同源入口

浏览器只访问 Web 来源，例如 `http://10.189.88.204:18000`。Nginx 必须把三个目标应用的反向代理放在同一来源下，不能把 iframe 改回 `http://10.189.88.118:18089` 等独立来源：

```nginx
location /snail-ai/ {
    proxy_pass http://10.189.88.118:18089/snail-ai/;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /snail-job/ {
    proxy_pass http://10.189.88.118:18088/snail-job/;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /admin/ {
    proxy_pass http://10.189.88.118:18090/admin/;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

Web 的 `EmbeddedIamApp` 会拒绝跨源桥接地址，因此缺少上述代理时不会静默退回独立应用登录，而会显示“嵌入应用桥接地址必须位于当前站点”。

### 密钥对齐

桥接仍使用既有服务间签名。IWS 主服务、AI、Job、Admin 各自配置的 client secret 必须与认证中心对应应用完全一致：

| 来源或目标 | 本服务环境变量 | 认证中心环境变量 |
| --- | --- | --- |
| IWS 来源 | `IAM_IWS_A_CLIENT_SECRET` | `IAM_IWS_A_CLIENT_SECRET` |
| AI 目标 | `IAM_SNAILAI_CLIENT_SECRET` | `IAM_SNAILAI_CLIENT_SECRET` |
| Job 目标 | `IAM_SNAILJOB_CLIENT_SECRET` | `IAM_SNAILJOB_CLIENT_SECRET` |
| Admin 目标 | `IAM_APPLICATION_SECRET` | `IAM_MONITOR_CLIENT_SECRET` |

只要任一侧不一致，中心会拒绝签名请求，桥接入口最终仍可能表现为登录失败。修改密钥后必须同时重启认证中心和对应目标服务。

### 发布顺序

1. 认证中心发布桥接签票接口。
2. IWS 主服务发布 `/auth/bridge/issue`。
3. AI、Job、Admin 发布各自桥接消费入口。
4. Web 更新三个 iframe 包装页并发布。
5. 核对 Nginx 同源代理、client secret、应用接入权限和唯一 WEB 受信前端后再做浏览器验收。

Web、IWS 主服务、认证中心、AI、Job、Admin 应按同一批次上线；只发布 Web 或只发布认证中心会造成接口缺失或票据无法消费。

## 回滚

回滚 Web 三个监控包装页即可恢复原入口；桥接接口不修改数据库结构，也不改变普通 SSO 票据格式。回滚期间应同时停止使用新的嵌入入口，避免旧目标应用收到桥接参数。
