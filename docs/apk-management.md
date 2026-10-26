# APK 应用包管理

## 初始化和启动

新空库继续使用 `script/sql/iws_init_from_empty.ps1` 执行完整初始化 SQL；其中已包含 `sys_apk_release` 和 APK 页面 5 项菜单，不再单独执行 APK 升级脚本。当前完整结构为 124 张表、230 项菜单。

已有库执行 `script/sql/iws_apk_management.sql`，先在头部确认唯一参数 `@apk_schema`；本机目标为 `innolux-iws`。通过 MySQL 8.0 批处理连接选择相同目标库，遇错停止，禁止 `--force`。脚本核验现有列、索引和菜单，定义冲突时拒绝，不覆盖已有版本文件。

已有 `sys_apk_release` 但尚无 `force_update` 的库，先执行 `script/sql/iws_apk_force_update.sql`，只修改头部目标参数并选择相同连接库。该脚本要求原表存在，幂等新增 `tinyint(1) NOT NULL DEFAULT 1`，已有字段定义冲突时拒绝。新建 APK 表直接使用新版 APK 初始化脚本；完整空库入口也已包含该字段。真实库升级不由本次开发自动执行。

计划事件运行需要已启用的环境和本系统目录，首次部署顺序如下：

1. 启动主服务时只启用 endpoint：在原 `dev` 参数后追加 `--iws.endpoint.enabled=true`，process 仍关闭。
2. 用系统管理员进入“系统接入配置”，确认默认环境 `TEST` 已启用，并登记启用本系统 `IWS`。空库只预置环境，不预置系统行。
3. 重启主服务并设置 `--spring.profiles.active=dev,apk`。`application-apk.yml` 启用 endpoint/process 和有界 IMMEDIATE 资源；当前环境继承主配置 `iws.endpoint.current-environment`，不单独定义环境变量。后台队列调度仍关闭。
4. 进入“系统管理 → 应用包管理”，点击“初始化发布计划”，由平台服务创建并发布 APK 业务计划和即时监督计划。重复点击只核验既有定义，冲突会明确报错。
5. 启动 MinIO，上传目标 PDA 的 APK，并按版本设置是否强制更新。启用强制更新前确认版本为“已发布”；服务端尚无该包的已发布版本时，PDA 跳过更新检查。

三个后端服务本机 dev 数据库均使用 `localhost:3306/innolux-iws`。新角色需授予 `system:apk:list`、`system:apk:upload`、`system:apk:publish`、`system:apk:download`；初始化使用 publish 权限。迁移脚本不自动扩大角色权限。

## 上传与版本

上传接口复用原 OSS 服务，APK 从二进制 Manifest 和资源表自动识别应用名、包名、versionName、versionCode。最大 APK 为 512MB，multipart 请求上限为 520MB，反向代理也需允许对应大小。原文件管理页面未改。

对象存储上传失败会返回中文处理提示。出现“访问站点指向了 MinIO 管理端口”时，将当前默认对象存储配置的访问站点改为实际 S3 API 端口；如果使用反向代理，也需核对该代理是否转发到 API 端口。服务端日志保留原始存储异常。

对象目录为 `apk/<packageName>/<versionCode>`，对象名继续由 OSS 生成。版本按数值 versionCode 排序，支持 Android versionCodeMajor；版本代码通过 JSON 字符串返回以保持精度。同包同版本同摘要返回已有记录，不同内容拒绝覆盖。

上传元数据先提交，再同步调用计划发布，避免线程丢失登录上下文和事务自锁。发布本地动作在 SAME_TX 中写入 `PUBLISHED`、计划实例 ID 和证据；不访问 S3。发布失败保留待发布版本，可在版本历史中重试。只有已发布版本能被公开查询和下载。

## 查询和下载

接口合同见 [方案](apk-management-spec.md#接口合同)。登录前接口以 `/app/apk` 为前缀：

```text
GET /app/apk/latest?packageName=com.example.pda
GET /app/apk/history?appName=应用名称&pageNum=1&pageSize=20
GET /app/apk/download?packageName=com.example.pda&versionCode=12
GET /app/apk/download?appName=应用名称&versionName=1.2.0
```

包名和应用名称至少一个；同名应用对应多个包时必须提供包名。下载未提供版本时选择最新已发布版本，版本名称不唯一时使用 versionCode。下载经后端流式读取私有 OSS，返回 APK MIME、长度及文件名，PDA 不需要访问服务器的 `127.0.0.1:9000`。

## PDA 更新

Android 登录页读取 PackageManager 的真实包名与安装版本。最新已发布版本开启强制更新且版本不同时阻止登录；关闭强制更新或服务端没有该包的已发布版本时跳过门禁。无发布版本以最新版本接口成功且 `data:null` 表示，请求失败仍保留门禁并可重试。上传时可选择强制更新，版本历史可修改已有版本的开关。

门禁内可设置服务器地址和端口，保存后立即切换请求地址并重新检查，同时使旧检查和下载失效。下载展示进度与字节数，完成后检查文件大小、包名和版本，再调起系统安装。安装取消、失败、未知来源权限尚未授予时仍保留门禁；页面恢复后重新检查已安装版本。设备系统的安装确认无法由应用静默代替。

服务器版本低于当前安装版本时阻止降级，提示管理员上传匹配版本。H5 只保留开发登录，不执行 APK 安装。应用包必须使用相同签名才能覆盖安装；本轮元信息解析不替代 Android 签名验证。

## 验证范围

已在隔离 MySQL 8.0.45、端口 13309、独立 datadir 执行全量空库初始化（124 表、230 菜单），APK 升级脚本连续执行两次成功。真实 3306 库只读核验，未自动升级。

浏览器实际加载独立 Vue 页面，以隔离 API 响应验证列表、历史、发布按钮、上传和窄屏显示；这不代表真实上传联调。Android 安装、未知来源授权和设备恢复需用签名 APK 与真机验收。

真实隔离平台集成测试已验证：管理员通过平台创建并发布监督/业务两个定义，重复初始化复用定义；发布后版本为 `PUBLISHED`、业务计划为 `COMPLETED`、事件带检查点；重复命令复用实例；并发资源满导致未准入的 `NOT_READY` 后可恢复重试。有实例、UNKNOWN 或需对账状态不更换命令键重放。

PDA 的 10 项纯逻辑测试通过；更新组件的下载进度、失败重试状态完成浏览器渲染检查，原生标签转换不代表 HBuilderX 全量构建或 Android 真机验收。独立审查发现的登录异步回调和业务页恢复绕过门禁问题均已修复并静态复核。
