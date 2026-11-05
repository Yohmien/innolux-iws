# APK 中心化迁移缺陷台账

日期：2026-11-05。状态只按实际复验结果填写；环境缺口与产品缺陷分开记录。

| ID | 优先级 | 状态 | 触发条件 | 影响与依据 | 修复/处置 | 复验 |
| --- | --- | --- | --- | --- | --- | --- |
| APK-D01 | P1 | 已修复 | 中心启动扫描排除 `SystemApplicationRunner` | 中心 OSS 配置缓存未初始化，APK 上传/下载不能按中心配置工作 | 恢复中心组件扫描，保留 iws-system 通用 OSS 控制器 | 中心启动日志输出“初始化OSS配置成功”；隔离上传和下载成功 |
| APK-D02 | P1 | 已修复 | PDA 从 `/iam` 页面地址推导公开 API 根 | 推导结果为空，版本检查和下载地址不可用 | `deriveCenterApiBase` 规范化 `/iam`，新增 `VITE_IAM_API_URL` 显式覆盖；请求不再走业务客户端 | Node 测试覆盖绝对地址、子路径、相对 `/iam`、无配置失败；37/37 通过 |
| APK-D03 | P2 | 已修复 | `iws-admin` 增量构建残留旧 `application-apk.yml` | 可能把中心 APK profile 打进业务后端，破坏进程边界 | 删除业务侧文件并执行 clean package | `iws-admin.jar` 不含 `application-apk.yml`、`iws-app-release` 或 APK 类 |
| APK-D04 | P1 | 已修复 | 隔离中心未登记 `iws-a-1` | `/iam/tickets/inspect` 返回“目标应用节点未登记”，H5 回调失败 | 在确认隔离配置中登记 `allowed-instance-ids: [iws-a-1, iws-a-2]` 并重启中心 | 浏览器回调进入工作台；中心 inspect/exchange 返回 200 |
| APK-D05 | P2 | 已修复 | 无 APK 权限账号直接输入 `/iam/system/apk` | 旧行为先渲染页面壳，后端 403 后列表为空 | 中心路由增加 `system:apk:list` 权限元数据，在加载页面前校验；无权限返回 `/iam/login?accessDenied=1&redirect=...`，登录路由允许该标记避免 Token 回跳死循环 | `route-access` 单元测试通过；Chromium 实际重定向到登录页且 APK 页面未渲染、未请求列表接口；`vue-tsc`、中心构建成功 |
| APK-D06 | P1 | 未关闭 | 使用 MCP 连接真实中心库 `127.0.0.1:13306/iws_iam_20261103` | `Unresolved datasource placeholder`，无法执行首笔 `SHOW GRANTS`，真实结构、唯一键、数据分布和 OSS 引用未取证 | 真实库只读核验未完成；不得用隔离库结果替代 | MCP 返回明确错误；C01 保持 `in_progress` |
| APK-D07 | P1 | 未关闭 | Android APP-PLUS 登录、回调、下载和安装 | 尚无 HBuilderX APK、系统安装器及真机生命周期证据，不能证明设备端更新门禁 | 保留现有 H5 通过和源码/Node 证据，不做“真机已通过”结论 | 未执行 |

缺陷关闭口径：产品行为有当前源码、实际构建和对应复验；仅单测、Mock、历史结果或页面截图不能替代真实数据库、Android 设备或生产代理证据。
