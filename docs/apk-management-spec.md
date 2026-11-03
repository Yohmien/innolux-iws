# APK 应用版本管理与 PDA 强制更新

## 目标与范围

新增独立 APK 管理页面，保留现有文件管理页面。复用现有 OSS 存储、统一请求、权限、业务调度与计划事件能力。解析 APK 内部元数据得到应用名称、Android 包名、versionName、versionCode，不以文件名或人工输入替代。

按包名管理多版本，以数值 versionCode 选择最新已发布版本。同一包名和 versionCode 的相同摘要重复上传返回已有版本，不同摘要拒绝覆盖。应用名称查询若匹配多个包名，明确要求使用包名；版本名称重复时要求使用 versionCode。保存 SHA256、文件大小、OSS ID、发布状态和计划实例。

本次授权包含代码、文档、升级脚本与隔离验证；真实数据库仅只读核验，不自动执行初始化、迁移或发布命令，不提交或推送。用户已有未提交内容保留。

## 文件与发布

文件使用默认 OSS 配置，业务目录为 `apk/<packageName>/<versionCode>`，实际对象名沿用 OSS 防覆盖命名。上传成功保存 `UPLOADED` 版本，元数据事务提交后调用发布计划；只有计划本地动作完成才置为 `PUBLISHED`。上传和发布失败均不能被当作最新可下载版本。保留上传结果，支持同一版本重试发布。

| 业务 | 计划 | 事件 | 方向 | 唯一动作与完成证据 |
| --- | --- | --- | --- | --- |
| APK_RELEASE | APK_PUBLISH | APK_RELEASE_PUBLISH | INTERNAL | SAME_TX 修改本库发布状态，返回版本与实例证据；不调用 S3/HTTP |

平台关闭或定义不可用时拒绝发布，不直接改状态绕过。计划初始化是管理员显式操作，使用平台创建、校验和发布服务创建业务定义和 IMMEDIATE 监督定义，不在应用启动时写库。结果未知按平台检查点查询，不换命令键盲重放。只有持久化 `NOT_READY` 回执证明尚未准入、无实例且无需对账时，才沿确定的命令键链开始新尝试；响应丢失与并发重试仍定位同一尝试。无需启动后台队列调度，使用有界 IMMEDIATE 执行。

## 接口合同

所有 JSON 接口使用现有 `R<T>`，分页为 `data.rows/data.total`。长 ID 和 versionCode 保持字符串精度。

| 接口 | 用途 | 访问 |
| --- | --- | --- |
| GET /system/apk/list | 按包分组分页，支持包名/应用名称筛选 | system:apk:list |
| GET /system/apk/history | 指定包名的全部发布/未发布版本分页 | system:apk:list |
| POST /system/apk/upload | multipart file，可选 remark，自动解析和归类并触发发布 | system:apk:upload |
| POST /system/apk/{releaseId}/publish | 重试已有上传版本的发布计划 | system:apk:publish |
| POST /system/apk/initialize | 显式初始化/核验 APK 发布计划 | system:apk:publish |
| GET /app/apk/latest | 包名或应用名称查询最新已发布版本 | 登录前可访问 |
| GET /app/apk/history | 包名或应用名称查询已发布历史，分页 | 登录前可访问 |
| GET /app/apk/download | 包名或应用名称，加 versionCode 或 versionName；未给版本取最新 | 登录前可访问 |

下载只返回已发布 APK，通过后端流式读取私有 OSS，携带 APK MIME、长度及下载文件名，不将设备引向本机回环地址，不将整个安装包装入 Java 堆。上传全局 multipart 限额调至适合 APK 的容量，并在业务层再次限制 512MB。

ReleaseVO 包含 releaseId、appName、packageName、versionName、versionCode、fileSize、sha256、ossId、status、processInstanceId、downloadUrl、createTime、remark、forceUpdate。ApplicationVO 包含 packageName、appName、versionCount、latestVersion。版本级强制更新开关、成功空版本及门禁服务设置的增量合同和验收见 [更新策略调整](apk-update-policy-20261026.md)。

## PDA 登录门禁

Android APP-PLUS 进入登录页或应用恢复时读取已安装应用真实 packageName/versionCode，查询该包最新已发布版本。同版本、成功空版本或最新版本关闭强制更新时允许原登录/自动跳转流程；检查失败、强制更新版本不匹配时保持门禁并可重试，不能取消或通过返回键进入业务页。强制更新且服务器版本低于本机版本时提示不可降级并保持阻止，避免触发无意义安装。门禁内允许设置服务器地址和端口，保存后切换基址并重新检查，旧异步结果失效。

下载展示百分比、已下载和总字节；未知总量不能伪造进度。下载失败可重试，成功后自动调起 Android 安装程序。系统安装确认与未知来源权限由 Android 管理，应用不能代用户静默确认；用户取消或安装失败仍保持门禁，恢复时重新读取真实安装版本。H5 开发不调用 Android 安装能力，保留原登录。

## 验收与部署

覆盖合法二进制 APK 解析、无效包、同包多版本、并发重复冲突、最新按 versionCode、同名歧义、公开端点不泄漏未发布包、发布失败安全、计划重复发布和关闭拒绝。PDA 验证同版本放行、异版本阻止、网络失败、下载进度、失败重试及安装恢复；Android 安装必须真机验证，H5/模拟通过不替代真机。

升级新增 `sys_apk_release` 与独立菜单，脚本需可重复执行并检查结构冲突，显式目标库，避免用户变量与字段排序规则冲突。新空库入口同步包含本次结构与菜单。先执行数据库升级，再按环境启用 endpoint/process 与 IMMEDIATE 资源配置，管理员初始化计划，上传首个对应包名版本，最后部署 PDA 门禁版本。启用强制更新前须先有可用发布版本；无对应发布版本不阻断初始化登录。

用户确认初始化目标为 `localhost:3306/innolux-iws`，已只读核验 endpoint、process、业务调度与 OSS 表存在，APK 表尚未创建。相关字符串字段采用 `utf8mb4_general_ci`，新 APK 包名与摘要使用精确排序规则。三个服务 dev JDBC 对齐该目标；真实升级与种子写入仍未执行。

## 任务与状态

| 标识 | 交付 | 依赖 | 状态 | 验收 |
| --- | --- | --- | --- | --- |
| A | APK 元数据解析及必要依赖 | 无 | done | 17 项二进制 APK 与拒绝场景测试通过 |
| B | 版本表、幂等迁移与菜单 | 无 | done | MySQL 8.0.45 空库 124 表/230 菜单，升级首跑/重复/冲突拒绝/非空初始化拒绝通过 |
| C | 发布计划与能力注册 | 既有 process 合同 | done | 真实隔离平台两次初始化、发布及检查点、相同命令幂等、资源满后恢复重试通过 |
| D | OSS 前缀复用、版本业务与公开接口 | A、C；上线需 B | done | 后端单测通过；下载流及未发布过滤已测，真实 OSS 上传接口联调未执行 |
| E | 独立 Web 应用/历史/上传界面 | 本文接口合同；联调依赖 D | done | lint、类型、构建和 4 项逻辑测试通过；浏览器隔离 API 交互通过 |
| F | PDA 登录强制更新 | 本文接口合同；联调依赖 D | review | 10 项逻辑测试、实际组件浏览器渲染及门禁静态复核通过；Android 安装和生命周期需真机验收 |
| G | 整合验收、独立审查与部署说明 | A 至 F | review | 三仓静态差异通过，独立审查 P1 已修复；真实数据库升级和真机部署未执行 |

验收日期：2026-10-26。隔离数据库仅使用 13309 与专用 datadir，真实 3306 仅只读。部署说明见 [apk-management.md](apk-management.md)，截图与测试日志在本次 Codex 可视化产物目录。代码已交付但不将未执行的 Android 真机验收标为完成。
