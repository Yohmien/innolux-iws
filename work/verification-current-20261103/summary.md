# IWS 在库管理 P1—P7 复评整改与验收

日期：2026-11-03（Asia/Shanghai）。范围：`inventory-completion-review-20261103` REV-01—REV-08，以及复评后继续实施发现的规则只读 readiness 缺口。未使用子代理，未写入真实业务库，未部署、commit 或 push。

## 结论

- REV-01—REV-08 均已处置，其中 7 项有正式执行复现或正式 API/worker 证据；REV-05 由真实 MySQL 分事务集成用例覆盖。
- 正式空库入口仍为 `181` 表、`77` 张 WMS 表、`256` 条菜单；本轮应用和测试直接从该隔离库工作，没有为集成测试额外补 DDL。
- 最终源码在 JDK 21 下完成根 reactor `43/43` clean test-compile、全应用 `43/43` package 和 WMS 模块 `481/481` 测试。
- Web 完成 `264/264` Vitest、空间生命周期 `70/70`、`vue-tsc`、Oxlint 和生产构建；真实 Chrome 分别通过 `127.0.0.1` 与本机 LAN 地址访问最终 JAR，均为 13 项页面断言和 20 次 API 请求，页面异常、控制台错误、失败请求和业务错误均为 0。
- 真实 MOVE、非空拆分清空、现场入出库和正式物料父业务写入继续关闭。PDA 仅完成当前源码静态检查，本机没有连接 PDA 真机或扫码设备，不能宣称真机验收通过。

## REV 逐项结果

| 编号 | 结果 | 当前证据 |
| --- | --- | --- |
| REV-01 | 已修复 | 空间计划由真实 SnailJob worker 在无 HTTP 登录会话下推进；普通账号发起、`SCHEDULED_WORKER` 执行，后台授权测试 `4/4`。运行中重启后计划完成，最终事件顺序 `VALIDATE_CHANGE>APPLY_CONFIGURATION>VERIFY_CHANGE`。 |
| REV-02 | 已修复并补漏 | 生产 `StorageRuleAttributeSourceProvider` 注册真实 `STORAGE` 来源；空库 6 个属性合同均为 `STORAGE-1`。正式 API 完成草稿、激活、规则包和发布计划。浏览器验收继续发现详情 Mapper 漏选 `attribute_bindings_json`，已修复并新增 `WMS READY` 回归；详情 API 现返回 `bindingSnapshotState=READY, executionReady=true`。 |
| REV-03 | 已修复 | 正式 API 对 MATERIAL 同类嵌套、DEVICE 跳层、STORAGE 直属 MATERIAL 三类非法父链均返回 `SPACE_CHANGE_INVALID`；非法对象数为 0。合法 WAREHOUSE→MATERIAL→DEVICE→STORAGE 链完成计划执行并回读有效指针。 |
| REV-04 | 已修复 | 储位有库存时，普通 UPDATE 尝试跨仓被拒绝，库区和子储位的 `warehouse_id` 均保持原仓，状态仍 `ACTIVE`。非空调整旁路保持关闭。 |
| REV-05 | 已修复 | `WmsSpaceChangeIntegrationTest` 真实 MySQL 用例覆盖 VALIDATE 后父节点退役、APPLY 前冲突；完整空间变更集成类 `8/8`，不会在 RETIRED 父节点下创建 ACTIVE 子节点。 |
| REV-06 | 已修复 | 普通公司角色通过正式挂载创建空间/规则计划，权限列表不含 `process:object:all`；跨公司仍返回 `COMPANY_SCOPE_DENIED`。撤权、公司授权撤销和角色停用各有通过用例。 |
| REV-07 | 已修复 | 正式 RETIRE 计划成功完成，成功事件顺序为 `CHECK_RETIREMENT>RETIRE_LOCATION`，储位 `RETIRED`、version 由 1 增至 2。 |
| REV-08 | 已修复 | 对已退役储位提交普通 UPDATE 被拒绝，生命周期和 revision 保持 `RETIRED|2`；普通属性写入不能复活对象。 |

## 验证证据

| 类别 | 结果 | 证据 |
| --- | --- | --- |
| WMS 隔离回归 | `481` tests，`0` failures/errors/skipped，`BUILD SUCCESS` | `wms-full-test-final-current.log` |
| 规则只读定向回归 | `18/18`，覆盖生产绑定快照进入详情 readiness | `wms-inventory-read-focused-current.log` |
| 空间授权/后台执行 | 背景授权 `4/4`；空间计划完整集成 `8/8` | `wms-space-authorization-tests-current.log`、全 WMS 日志 |
| 根 reactor 编译 | `43/43` clean test-compile，JDK 21 | `reactor-clean-test-compile-final-current.log` |
| 全应用打包 | `43/43` package，最终 JAR `4C2515415A427CF1AC1A516DA05A408F7491B8E227CBEB9DCC6D5552BD95EBC3` | `package-final-current.log` |
| Web 测试 | Vitest `264/264`；空间生命周期 `70/70` | `web-full-test-final-current.log`、`web-space-lifecycle-final-current.log` |
| Web 静态/构建 | `vue-tsc`、Oxlint、生产构建通过 | `web-vue-tsc-final-current.log`、`web-oxlint-final-current.log`、`web-build-final-current.log` |
| 浏览器（本机） | 普通账号真登录；空间仓库/库区/储位、规则 READY、规则包成员均通过；13 checks，20 API，0 页面/控制台/网络/API 错误 | `browser-current-20261103/summary.json` 及同目录截图 |
| 浏览器（LAN） | `192.168.1.21:18000` 经 Vite 代理访问本机最终 JAR；同一 13 checks、20 API、0 错误，绑定 JAR `4C2515…` | `browser-lan-final-20261103/summary.json` 及同目录截图 |
| 重启恢复 | 运行中断后由真实 worker 完成，checkpoint 顺序完整 | `restart-recovery-summary.json` |
| 正式空库 | `181` 表、`77` WMS 表、`256` 菜单，无额外测试 DDL | `isolated-empty-init.log`、`isolated-init-summary.json` |
| PDA | 仅静态语法与路由检查；未连接真机 | `pda-static-final-current.log` |

## 未完成与风险

- PDA H5/安装包、真实扫码枪、现场网络和离线/UNKNOWN 行为尚未在真机验证。
- 本轮 LAN 浏览器只验证本机最终 JAR 的 Web 代理访问链，不等同于部署、远程主机、生产网络或生产库验收。
- LAN 验收使用 `0.0.0.0:18000` Vite 代理至 `127.0.0.1:18080` 最终 JAR；验收完成后应用、SnailJob 和 Vite 已停止，隔离 MySQL `3430` 与 Redis `6381` 容器保留。
- 浏览器脚本按用户澄清从隔离运行配置动态读取需求密码，不把密码明文写入仓库或证据；隔离库另建 `wmsacceptance` 普通账号，仅用于避开登录页扫码识别器对连字符账号名的拆分，不是生产代码修改。
- 消息模块 `/resource/message` 的独立遗留问题不作为本期内库业务通过依据。
- 真实 MOVE、非空空间清空、现场入出库、正式物料父业务写入和真实业务库切换仍属于后续扩围，能力必须继续关闭或明确拒绝。
