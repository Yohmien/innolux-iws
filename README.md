# INNOLUX IWS 标准化开发说明

## 项目定位

INNOLUX IWS 是面向智能仓储场景的前后端一体化系统，当前系统展示名为 `IWS+`，后端工程标准包名为 `com.innolux.iws`，数据库标准名称为 `innolux_iws`。

当前标准化版本：`v1.0.0`。

本项目以仓储业务为核心，逐步承载入库、储位、出库、在库、合箱、盘点、看板、WCS 对接、异常处理、系统管理、系统监控与系统工具等模块。后续新增业务模块时，应保持统一命名、统一注释、统一数据库脚本和统一接口返回规范。

## 开发环境

| 类型 | 要求 |
| --- | --- |
| JDK | 21 |
| Maven | 使用后端根目录 `mvnw.cmd` / `mvnw` |
| Node.js | 20.19+ |
| 包管理 | pnpm |
| 数据库 | MySQL 8.x，`dev` 环境默认 `localhost:3307/innolux_iws` |
| 缓存 | Redis 6+ |

## 标准端口

本地开发端口统一使用 `18xxx` 号段，避免与常见默认端口冲突。

| 服务 | IDEA 启动项 | 端口 | 访问地址 | 说明 |
| --- | --- | --- | --- | --- |
| 前端 Web | pnpm dev | `18000` | `http://localhost:18000` | Vue/Vite 开发服务 |
| 主后端 | IWS+ Main Service | `18080` | `http://localhost:18080` | 登录、权限、菜单、WMS 后端接口 |
| 监控中心 | IWS Monitor Admin | `18090` | `http://localhost:18090/admin` | Spring Boot Admin 监控台 |
| SnailJob 控制台 | IWS SnailJob Server | `18088` | `http://localhost:18088/snail-job` | 调度中心 Web 与接口 |
| SnailJob 通信 | IWS SnailJob Server | `18188` | 内部通信端口 | 主服务接入调度中心 |
| SnailAI 控制台 | IWS SnailAI Server | `18089` | `http://localhost:18089/snail-ai` | AI 服务 Web 与接口 |
| SnailAI gRPC | IWS SnailAI Server | `18189` | 内部通信端口 | 主服务接入 AI 服务 |
| 主服务 SnailJob 客户端 | IWS+ Main Service | `18280` | 内部通信端口 | 调度中心回调主服务 |
| 主服务 SnailAI 客户端 | IWS+ Main Service | `18380` | 内部通信端口 | AI 服务回调主服务 |
| MySQL | 外部服务 | `3307` | `localhost:3307/innolux_iws` | dev 数据库 |
| Redis | 外部服务 | `6379` | `localhost:6379` | 缓存与分布式锁 |

日常开发只启动 `IWS+ Main Service` 和前端即可。需要监控、任务调度或 AI 能力时，再分别启动 `IWS Monitor Admin`、`IWS SnailJob Server`、`IWS SnailAI Server`。

## 后端结构

| 模块 | 说明 |
| --- | --- |
| `iws-admin` | 后端启动入口、全局配置、登录鉴权、接口入口 |
| `iws-api` | 跨模块 API、领域模型与事件契约 |
| `iws-common` | 通用能力，包括 core、web、security、mybatis、redis、oss、excel、doc、log、sms、mqtt、ai 等 |
| `iws-modules/iws-system` | 用户、角色、菜单、部门、岗位、字典、参数、通知、日志等系统管理 |
| `iws-modules/iws-job` | 定时任务与任务调度 |
| `iws-modules/iws-gen` | 代码生成能力 |
| `iws-modules/iws-workflow` | 流程引擎与审批相关能力 |
| `iws-modules/iws-ai` | AI 会话与扩展能力 |
| `iws-modules/iws-demo` | 技术示例模块，正式业务模块稳定后可按需移除 |
| `iws-extend` | 监控、任务中心、AI 服务等扩展应用 |

## 前端结构

| 模块 | 说明 |
| --- | --- |
| `src/views` | 页面视图与业务入口 |
| `src/api` | 后端接口封装 |
| `src/layout` | 主布局、侧边栏、顶部导航、标签页 |
| `src/components` | 通用组件 |
| `src/store` | Pinia 状态管理 |
| `src/router` | 路由与动态菜单 |
| `src/utils/iws.ts` | IWS 通用前端工具函数 |
| `src/assets` | LOGO、首页、登录页与样式资产 |

## 数据库规范

1. 只维护一个初始化脚本：`script/sql/iws_init.sql`。
2. 脚本必须先创建并切换到 `innolux_iws` 数据库。
3. 新增表优先使用业务前缀，例如 `wms_`、`wcs_`、`sys_`、`sj_`。
4. 表名、字段名使用小写下划线，主键字段保持语义清晰。
5. 表注释和字段注释必须描述当前业务含义，不保留模板项目介绍信息。
6. DDL 必须可重复审查；新增大模块时按模块分段注释写入同一个初始化脚本。

WMS SMT 电子仓标准数据模型详见 `docs/wms-smt-standard-data-model.md`，其中说明了库区、储位、容器、容器类型、物料、批次、库存、预留、冻结与库存流水的表关系和索引策略。

标准表数据页设计详见 `docs/iws-standard-crud-page-design.md`，其中定义了前端标准 CRUD 页面、字段元数据、搜索、列控制、导入导出、权限、后端白名单查询、注释风格与性能边界。

## 代码文件头规范

所有可注释的前后端代码与配置文件顶部必须保留统一开发基础信息。新增文件时按当前文件注释语法套用以下字段：

```text
@Date         : 2026-09-19
@Author       : YONGJIE.PENG
@Email        : YONGJIE.PENG@INNOLUX.COM
@Job          : INNOLUX IWS 标准化重构
@Module       : IWS 智能仓储管理系统
@Version      : V1.0
@Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
@ModifyRecord : V1.0 新建；
```

JSON、lockfile、二进制资源等不支持注释或不适合写头的文件不添加文件头。

## 命名规范

1. 后端基础包名统一为 `com.innolux.iws`。
2. Maven 模块统一使用 `iws-*` 命名。
3. 前端通用工具、组件、页面命名统一使用 `Iws` / `iws`。
4. 产品展示名统一使用 `IWS+`。
5. 数据库名统一使用 `innolux_iws`。
6. 业务代码、注释、文档和初始化脚本中不保留旧模板项目介绍信息。

## IDEA 启动

1. 使用 IDEA 打开后端目录 `E:\innolux-iws`。
2. Project SDK、Maven Runner、Maven Importer 均使用 `JDK 21`。
3. 右侧 Maven 面板执行 Reload All Maven Projects。
4. 本地先启动 MySQL `3307` 与 Redis `6379`，并确认数据库已执行 `script/sql/iws_init.sql`。
5. Run/Debug 下拉框使用以下四个共享启动项：

| 启动项 | 是否必启 | 说明 |
| --- | --- | --- |
| `IWS+ Main Service` | 是 | 主后端服务，默认 `dev` 环境，端口 `18080` |
| `IWS Monitor Admin` | 否 | 监控中心，端口 `18090`，访问 `/admin` |
| `IWS SnailJob Server` | 否 | 任务调度中心，端口 `18088` / `18188` |
| `IWS SnailAI Server` | 否 | AI 服务中心，端口 `18089` / `18189` |

基础联调启动顺序：MySQL、Redis -> `IWS+ Main Service` -> 前端。

完整能力启动顺序：MySQL、Redis -> `IWS Monitor Admin` -> `IWS SnailJob Server` -> `IWS SnailAI Server` -> `IWS+ Main Service` -> 前端。

## VS Code 启动

后端主服务：

```powershell
cd E:\innolux-iws
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -pl iws-admin -am package -DskipTests
java -jar .\iws-admin\target\iws-admin.jar --spring.profiles.active=dev
```

扩展服务按需启动：

```powershell
java -jar .\iws-extend\iws-monitor-admin\target\iws-monitor-admin.jar --spring.profiles.active=dev
java -jar .\iws-extend\iws-snailjob-server\target\iws-snailjob-server.jar --spring.profiles.active=dev
java -jar .\iws-extend\iws-snailai-server\target\iws-snailai-server.jar --spring.profiles.active=dev
```

前端：

```powershell
cd E:\innolux-iws-web
pnpm install
pnpm dev
```

## 常用命令

后端完整构建：

```powershell
cd E:\innolux-iws
.\mvnw.cmd clean package -DskipTests
```

后端主服务本地运行：

```powershell
cd E:\innolux-iws
.\mvnw.cmd -pl iws-admin -am spring-boot:run -Dspring-boot.run.profiles=dev
```

前端构建：

```powershell
cd E:\innolux-iws-web
pnpm build
```
