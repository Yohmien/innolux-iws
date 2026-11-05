# IWS 分服务外部配置模板

本目录提供各服务需要现场填写的外部配置模板，不是可直接运行的配置目录。复制时保留服务子目录，不要让多个服务共用同一份 `application.yml`。

```text
docs/config/
  auth-center/application.yml
  iws-a/application.yml
  iws-b/application.yml
  job/application.yml
  ai/application.yml
  monitor/application.yml
```

模板只覆盖部署环境需要显式提供、或必须与统一身份中心保持一致的配置。未列出的属性继续使用 JAR 内的 `application.yml` 和对应 profile。完整的部署示例见 [script/config/iam/README.md](../../script/config/iam/README.md)。

## 加载路径

以可执行 JAR 启动时，默认配置根目录是 **JAR 所在目录**下的 `config`，不是命令当前目录：

```text
C:\release\
  iws-admin.jar
  config\
    iws-a\application.yml
    iws-b\application.yml
```

因此，配置与 JAR 放在一起时不需要指定根目录：

```powershell
java -jar C:\release\iws-admin.jar --iws.config.key=iws-a --spring.profiles.active=prod
```

配置位于其他盘符或共享目录时，才需要显式指定绝对路径：

```powershell
java -jar C:\release\iws-admin.jar `
  --iws.config.root=C:\release\config `
  --iws.config.key=iws-a `
  --spring.profiles.active=prod
```

上面的命令读取 `C:\release\config\iws-a\application.yml`。`iws.config.root` 不能是相对路径；路径有空格时必须使用引号。各制品的默认配置键如下：

| 服务 | 默认键 | A/B 部署时 |
|---|---|---|
| `iws-admin` | `iws` | 分别使用 `iws-a`、`iws-b` |
| 认证中心 | `auth-center` | 固定 |
| SnailJob | `job` | 固定 |
| SnailAI | `ai` | 固定 |
| 管理监控 | `monitor` | 固定 |

## 优先级

从高到低依次为：

1. 命令行参数
2. JVM 系统属性
3. 操作系统环境变量
4. 所选服务目录的 `application-{profile}.yml`
5. 所选服务目录的 `application.yml`
6. JAR 内的 `application-{profile}.yml`
7. JAR 内的 `application.yml`

外部目录不存在时回退 JAR 内配置；目录或文件存在但格式非法、不可读、绑定失败时，应按启动失败处理，不要继续使用不完整配置。

## IDEA 启动

直接运行各模块的 `main` 类时，源码位于开发类目录而不是可执行 JAR，项目启动器不会自动装配 `<configRoot>/<configKey>/`。此时 `--iws.config.root` 和 `--iws.config.key` 只记录选择结果，不会把该目录加入 Spring 配置。

| 服务 | IDEA Main class |
|---|---|
| IWS | `com.innolux.iws.IwsApplication` |
| 认证中心 | `com.innolux.iws.auth.AuthServerApplication` |
| SnailJob | `com.innolux.iws.snailjob.SnailJobServerApplication` |
| SnailAI | `com.innolux.iws.snailai.SnailAiServerApplication` |
| 管理监控 | `com.innolux.iws.monitor.admin.MonitorAdminApplication` |

在 IDEA 的 Run Configuration 中使用 Program arguments 显式追加外部目录：

```text
--spring.config.additional-location=file:C:/release/config/iws-a/ --spring.profiles.active=prod
```

认证中心、Job、AI、监控分别把路径中的 `iws-a` 改为 `auth-center`、`job`、`ai`、`monitor`。数据库密码、JWT 密钥和客户端密钥优先放在 IDEA Environment variables 中，不写入仓库或提交到版本库。

真实 JAR 与 IDEA 直接运行必须分别验证；IDEA 能启动不代表 JAR 外部配置目录已正确装配。

## 模板约定

- `${NAME:}` 是没有默认值的占位符，部署时必须通过外部文件、环境变量、系统属性或命令行提供。
- 带“按需启用”说明的配置仅在对应功能打开时填写；关闭状态下可保持为空。
- `applicationId`、`instanceId`、会话命名空间及客户端密钥必须与认证中心注册项一致。
- 以下环境变量在认证中心和目标服务中必须使用同一个值：

| 目标服务 | 认证中心变量 | 目标服务变量 |
|---|---|---|
| IWS A | `IAM_IWS_A_CLIENT_SECRET` | `IAM_IWS_A_CLIENT_SECRET` |
| IWS B | `IAM_IWS_B_CLIENT_SECRET` | `IAM_IWS_B_CLIENT_SECRET` |
| SnailJob | `IAM_SNAILJOB_CLIENT_SECRET` | `IAM_SNAILJOB_CLIENT_SECRET` |
| SnailAI | `IAM_SNAILAI_CLIENT_SECRET` | `IAM_SNAILAI_CLIENT_SECRET` |
| 管理监控 | `IAM_MONITOR_CLIENT_SECRET` | `IAM_MONITOR_CLIENT_SECRET` |

不要提交真实密码、私钥、JWT 密钥或第三方凭据。发布目录中的配置文件应在仓库外单独维护，升级时只替换 JAR 和前端制品。
