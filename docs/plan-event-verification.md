# 公共平台验收记录

2026-10-23。本次交付 endpoint、process、job 接线和 Web 公共能力；真实业务链路另行接入。实现验收时尚未提交；2026-10-24 的提交同步与初始化补充见[交付记录](plan-event-delivery.md)。

## 验证结果

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| 后端整合回归 | endpoint 14、process 118、job 38、菜单过滤 8，共 178 项通过，无跳过 | `final-regression-complete.log` |
| 最后契约修复 | 定义校验 15、查询 MySQL 8、QUERY 准入 9、事件命令 10、完整包扫描 5、真实 HTTP 5，共 52 项通过 | `contract-fixes-final-tests.log` |
| 租约与恢复 | T2 等锁后过期不发送；T5 校验期间过期整事务回滚；成功检查点跨认领保留；QUERY 证据只补原事件编排 | `EventRuntimeMysqlTest`、`TerminalLeaseMysqlTest`、`RecoveryEvidenceServiceMysqlTest` |
| 前端 | lint、vue-tsc、build 通过；5 个测试文件、23 项通过 | `web-delivery-*.log` |
| 主应用打包 | JDK 21、Maven Wrapper 完整 reactor 打包通过，明确跳过测试 | `admin-delivery-package.log` |
| SQL | endpoint/process/menu 空库首跑与重跑共 6 次通过；22 张表、53 行菜单；实体字段、索引及权限对齐 | [升级说明](plan-event-upgrade.md) |
| 独立审查 | 两轮运行内核及公共契约发现已修复，定向源码复核关闭 | `runtime-review-recheck.md`、`public-contract-final-review.md` |
| 真实隔离 HTTP | 空草稿→三类状态→INTERNAL 事件→系统模式预览/保存→发布→ACTIVE 回读通过，执行预算 60000ms | `public-definition-http-evidence.json` |
| 浏览器 | 环境新增回读；草稿创建、基本信息保存、状态新增；已发布只读、复制 v2、事件编辑保存及修订回读 | 本任务浏览器工具记录 |
| 使用 Skill | 项目版结构、链接检查通过；独立 Darwin dry_run 比较后已补恢复参数、预算和首次接单职责，并按源码复核。未实测新会话自动触发或生产业务接入 | [Skill 安装与评估记录](plan-event-skill-evaluation.md)；旧全局版历史证据 `m12-forward.md` |

日志目录：`C:/Users/yohmi/.codex/visualizations/2026/10/22/01a16176-b64c-78b2-9aa8-33beed46c04d`。不同测试集合存在重叠，不能累加为独立用例总数。日志保留失败轮次，表中仅列对应最终通过证据。

## 环境与边界

构建仅在进程内使用 jvms 已有 JDK 21，没有改变全局 Java 版本。数据库写入仅发生在核验端口与 datadir 后的 `127.0.0.1:13307` 专用 `_test` 库。

当前 dev master 为 `localhost:3307/innolux_iws`。MCP 首条 `SHOW GRANTS` 返回无匹配连接，真实目标结构核验未完成；没有改用应用凭据绕过。早期 3306 只读证据不代表当前 3307。

未进行生产部署、真实系统外呼、真实过账、角色菜单授权或 SnailJob 平台任务启停。SnailJob 适配测试证明本地接线与持久控制意图，不能替代实际平台联调。多级审批未启用，PDA 不在本次范围。

请求列表先按来源数据范围及筛选条件获取关联对象，再执行对象权限判定，最终分页和计数使用同一可见集合。一次查询最多涉及 5000 个关联对象，超限要求缩小筛选范围。

浏览器验收使用隔离账号、关闭该 fixture 的请求加密及真实本地 HTTP 服务；不代表生产加密登录或所有故障时序已在浏览器重演。部分网络乱序行为由单测及源码复核覆盖。

## 交付入口

- [操作与接入说明](plan-event-public-platform-guide.md)
- [数据库升级说明](plan-event-upgrade.md)
- [项目版领域 Skill](C:/code/company/innolux-iws/.agents/skills/iws-plan-event-project/SKILL.md)，安装在当前 IWS 后端仓库的 `.agents/skills`；全局 `iws-plan-event` 保留未修改。结构和链接检查见[安装与评估记录](plan-event-skill-evaluation.md)。
- [endpoint SQL](../script/sql/iws_plan_event_endpoint_schema.sql)、[process SQL](../script/sql/iws_plan_event_process_schema.sql)、[菜单 SQL](../script/sql/iws_plan_event_menu.sql)

上线前补齐真实链路的对象范围、可信入站身份、调用合同、本地动作、校验器、容量和超时参数，再单独完成目标库与调度平台验收。模块默认关闭。

本任务临时 HTTP、Vite 和 13307 MySQL 进程已关闭，隔离数据目录及脱敏证据保留。
