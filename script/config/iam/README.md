# IWS 分服务外部配置

本目录是部署模板，不是运行目录。发布时将各服务目录复制到统一 `config` 根目录，再按实际环境维护；升级只替换 JAR/UI，不覆盖已有配置。

```text
release/
  iws-auth-server.jar
  iws-admin.jar
  iws-snailjob-server.jar
  iws-snailai-server.jar
  iws-monitor-admin.jar
  config/
    auth-center/application.yml
    iws-a/application.yml
    iws-b/application.yml
    job/application.yml
    ai/application.yml
    monitor/application.yml
```

## 启动选择

- 默认配置根目录是实际运行 JAR 所在目录下的 `config`，与当前工作目录无关。
- `iws.config.key` 或环境变量 `IWS_CONFIG_KEY` 只选择一个单级服务目录。
- `iws.config.root` 或环境变量 `IWS_CONFIG_ROOT` 可指定共享配置根目录，显式值必须是绝对路径。
- 元参数优先级为命令行、系统属性、环境变量、制品默认值。
- 中心、Job、AI、监控的默认键分别为 `auth-center`、`job`、`ai`、`monitor`；IWS 默认键为 `iws`。
- 同一个 IWS JAR 作为 A/B 部署时，分别设置 `iws-a`、`iws-b`，不复制或重打 JAR。
- 每个服务只读取包内默认配置和选定服务目录，不读取配置根目录或其他服务目录。

直接启动示例：

```powershell
java -jar C:\release\iws-auth-server.jar
java -jar C:\release\iws-admin.jar --iws.config.key=iws-a --server.port=18080
java -jar C:\release\iws-admin.jar --iws.config.key=iws-b --server.port=18084
java -jar C:\release\iws-snailjob-server.jar
java -jar C:\release\iws-snailai-server.jar
java -jar C:\release\iws-monitor-admin.jar
```

共享根目录示例：

```powershell
java -Duser.timezone=Asia/Shanghai -jar C:\release\iws-admin.jar --iws.config.root="D:\IWS Config" --iws.config.key=iws-a
```

多副本共享同一应用目录，节点差异由实例参数提供，例如 `--server.port=18083 --iam.client.instance-id=iws-a-2`。独立 IWS 应用必须使用不同配置目录、`applicationId`、协议凭据和会话绑定；同一 Web/PDA 构建可以复用精确受信的 `frontendId` 与统一回调入口，不要求为每个后端复制一套前端。

## 模板约束

- `application.yml` 只覆盖该服务需要的属性；未定义属性继续使用包内默认配置。
- 外部目录不存在时回退包内配置；外部文件存在但非法、不可读或绑定失败时启动失败。
- 外部普通值优先于包内 profile 值；所选目录内的 `application-{profile}.yml` 继续按框架规则覆盖普通值。
- 命令行、系统属性和环境变量仍优先于文件配置。
- 模板中的空占位符必须在部署配置中提供，缺失必需凭据时应用应明确失败。
- 禁止在配置根目录放置聚合 `application.yml`，也不要把多个服务配置合并后广播。
