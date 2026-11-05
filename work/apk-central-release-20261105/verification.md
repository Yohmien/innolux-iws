# APK 中心化迁移验证记录

日期：2026-11-05。范围：统一身份认证中心 APK 发布管理、Web 中心入口、PDA 更新来源与 SSO 回调。本文只记录实际执行结果；“源码通过”“H5 通过”和“Android 真机通过”分开表述。

## 结论

- 中心已独立承载 APK 管理、公开查询、下载、OSS 引用保护及计划事件发布；业务 `iws-admin.jar` 不再包含 APK 模块、APK Controller 或 `/app/apk/*` 安全放行项。
- 隔离 MySQL/MinIO 环境完成真实上传、解析、IMMEDIATE 发布、历史、策略切换、下载和同包同版本同摘要幂等复用。
- 中心管理页面、授权角色和无权限拒绝已用真实 HTTP/浏览器验证；业务 Web 的 `/system/apk` 已跳转到中心。
- PDA H5 已完成中心登录、选择 IWS-A、票据核销、A 后端换票并进入工作台；整个流程未请求 `/app/apk/latest|history|download`，符合 H5 不安装 APK 的边界。
- 真实中心库 `127.0.0.1:13306/iws_iam_20261103` 仍未完成 MCP 只读核验；Android APP-PLUS 更新门禁、系统安装器和真机生命周期未验收。

## 制品

| 制品 | 实际值 |
| --- | --- |
| `iws-admin/target/iws-admin.jar` | 263742906 字节；SHA-256 `190EDBF7224CFFFD0EA5A746E912734C3E447A8554149BF1C4B26056F3B764A0` |
| `iws-extend/iws-auth-server/target/iws-auth-server.jar`（APK 路由权限修复后） | SHA-256 `5D735067F81A7531642A7D209878748675E8A2F875AE202ECEB6CE6A2ACCFDC3` |
| 中心静态制品 `runtime/center-static-auth-route-20261105152223` | `index.html` SHA-256 `E72931DDB35360D4A0E4364575D0E82E5C723868965C8D34C3BB539860EC6CCB` |
| 中心 APK JS | `apk-CvTF67x7.js` SHA-256 `6DFA44C17443DC66604305C2609E089DF06F21ABFA6DA8B3414381D729F14A32` |
| 中心 APK CSS | `apk-CHVCE0SV.css` SHA-256 `017B95A9D1981804888A7B33FEA89E638E5FE5D098F66FE5904732229DE72274` |
| PDA H5 制品 `runtime/pda-h5-center-19202` | `index.html` SHA-256 `D7016E923ED65C8348685A0DA44E683ED0EA7B7B0143184BB17828AD2627DEAB` |

构建与运行证据：

- 后端 clean package：41/41 reactor 模块成功；`iws-admin.jar` 中 `iws-app-release`、`application-apk.yml`、`system/apk` 类均为 0；中心 JAR 包含 `iws-app-release` 及 APK 静态资源。
- Web `pnpm build:auth` 成功，生成 `dist-auth/assets/apk-E9zjuc-d.js` 与 `apk-CHVCE0SV.css`；原 `dist-auth/` 备份后恢复，最终制品另存，未覆盖原目录。
- PDA H5 `DONE Build complete.`；构建日志为 `runtime/pda-h5-build-19202.log`。

## 自动化验证

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| APK 后端模块 | `mvnw.cmd -pl iws-modules/iws-app-release -am test` | Surefire 9 个报告合计 61/61，0 failure/error/skip |
| Web APK 定向测试 | `pnpm exec vitest run src/auth/__tests__/apk-center-api.spec.ts src/api/system/apk/__tests__/presentation.spec.ts src/api/system/apk/__tests__/force-update.spec.ts` | 10/10 通过 |
| Web APK 路由权限 | `pnpm exec vitest run src/auth/__tests__/route-access.spec.ts src/auth/__tests__/apk-center-api.spec.ts` | 6/6 通过；`vue-tsc --noEmit` 和中心构建通过 |
| PDA Node 测试 | `node --test tests/apk-center-source.test.mjs tests/apk-update.test.mjs tests/iam-callback-interleaving.test.mjs tests/iam-request-interceptor.test.mjs` | 37/37 通过 |
| 空库初始化 | `iws_init_from_empty.sql`（隔离 MySQL） | 181 表，254 菜单；脚本执行成功 |
| APK 平台集成 | 隔离 MySQL/MinIO | 2/2 通过 |

自动化测试覆盖同版本幂等、内容冲突、并发上传、失败恢复、OSS 引用保护、中心来源 URL、无 Token 公开检查、强制更新失败关闭、回调事务交错和迟到结果隔离。

## 隔离发布事实

浏览器真实发布结果：

| 字段 | 值 |
| --- | --- |
| releaseId | `2118223507461808130` |
| packageName | `com.example.probe` |
| versionCode | `8589934634` |
| status | `PUBLISHED` |
| processInstanceId | `2118223508489412610` |
| planStatus | `COMPLETED` |
| attempt | `1`，`SUCCEEDED`，`APK_START -> APK_PUBLISHED` |

同包名、同版本、同摘要再次上传复用上述 release。匿名 `/app/apk/latest` 返回 200 和上述发布事实；匿名 `/app/apk/download` 与页面下载均为 1271 字节，SHA-256 `7902FA3C7EB064C36A38EC205AB1B17659E2F815B278C3E5E819925762B73C42`。复验文件为 `runtime/center-public-download-verification.apk`。

## 浏览器验证

| 场景 | 实际结果 |
| --- | --- |
| 中心管理员 | `/iam/system/apk` 列表、上传解析、历史、策略切换、下载均使用中心；真实发布走中心计划事件 |
| 授权角色 `apk_viewer_20261105` | 仅列表、历史和下载可用；页面无上传、发布和策略操作 |
| 无权限角色 `apk_deny_c1324e27` | 左侧无“应用包管理”；2026-11-05 用 Chromium 注入无权限用户直接访问 `/iam/system/apk`，实际跳到 `/iam/login?redirect=%252Fsystem%252Fapk&accessDenied=1`，页面无“上传 APK”，且未请求 APK 列表接口 |
| 业务 Web `/system/apk` | Vite `18000` 实际跳转 `/iam/login?redirect=%252Fsystem%252Fapk` |
| PDA H5 | 登录中心并选择 IWS-A；`/iam/authorize`、A 后端 `/auth/sso/exchange`、`/system/user/getInfo` 和菜单上下文均成功，进入 `/#/pages/work/index` |
| H5 版本门禁 | 19202 代理请求日志无 `/app/apk/latest|history|download`；H5 页面和回调均跳过 APK 安装 |

截图：`runtime/center-apk-page.png`、`runtime/center-apk-history.png`、`runtime/center-apk-denied-route.png`、`runtime/pda-h5-workbench-19202.png`。

## 配置复验

隔离中心的 `iam.applications.iws-a.allowed-instance-ids` 必须包含实际后端 `instance-id`。未配置时，中心 `/iam/tickets/inspect` 返回“目标应用节点未登记”，H5 回调无法完成。使用测试配置 `runtime/config/apk-confirmed-center/application.yml`，设置 `iws-a-1` 后，/iam/tickets/inspect 和 /iam/tickets/exchange 均返回 200。

中心启动配置：

```text
iws.config.root=<runtime>/config
iws.config.key=apk-confirmed-center
spring.profiles.active=prod,apk
server.port=19181
```

`application-apk.yml` 只启用 `iws.endpoint` 和 `iws.process`，保持 scheduler 关闭；未启用该 profile 时统一登录和管理查询仍可启动。

## 未完成与限制

- 真实中心库 MCP 查询仍被 `Unresolved datasource placeholder` 拒绝，未执行 `SHOW GRANTS`，也未核验真实表结构、索引、记录和 OSS 引用；C01 保持 `in_progress`。
- 未使用 HBuilderX 生成并安装 Android APK；APP-PLUS 登录前、前台恢复、SSO 回调、下载校验、系统安装器和安装返回生命周期仍无真机证据。PDA 原生图标未修改。
- 未用真实 IWS-B 完成同包名跨后端版本、策略和下载摘要对比；当前同源规则由代码和 Node 测试覆盖。
- 无权限角色直接访问 APK 路由已修复：路由守卫在加载页面前检查 `system:apk:list`，返回登录页时使用 `accessDenied=1` 防止保留的中心 Token 再次弹回原路由；后端逐接口鉴权保持不变。
- 本轮未写真实中心库、未提交、未推送、未部署生产。

## 现场清理

验证结束后已停止本轮 `19181` 中心、`19280` 临时 IWS-A、`19202/19182` H5 代理和 `18000` Vite；删除隔离 MySQL/MinIO 容器及三份本轮卷。原 `19080/19084` 进程未停止，`dump.rdb` 和 Web 原 `dist-auth/` 均保留。
