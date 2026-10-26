# 计划事件实施进度与验收记录

> 2026-10-23 更新：下表保留初期执行快照；当前验收以 [公共平台验收记录](../../innolux-iws/docs/plan-event-verification.md) 为准。M01–M07、M09–M11 的公共实现和隔离验收已交付，M08 按无多级审批需求不启用；真实业务链路按用户决定另接。M12 使用 Skill 已完成结构、链接及独立书面前向验证，新会话自动发现尚未实测。未进行真实目标库迁移、调度平台启停或提交推送。

日期：2026-10-22。依据：[当前实施计划](../docs/plan-event-state-machine-implementation-plan.md)、[实施交接](handoff-20261022-plan-event-implementation.md)。沿用 M01–M12、D01–D48，不另建行为规格。

用户已授权代码实现、测试、验证和升级 SQL 文档交付；未授权提交、推送、真实库写入、真实过账或平台任务启停。后端既有三份文档修改保留，PDA 不在范围内。

用户后续明确本次先交公共平台，真实链路另接入。M11 的真实业务映射与过账演练移至后续接入范围；公共平台的隔离集成验收仍在本次范围。

| 工作包 | 状态 | 当前执行范围与依赖 | 实际证据 |
| --- | --- | --- | --- |
| M01 | in_progress | endpoint/process 模块、根 BOM/admin 装配、开关与菜单能力过滤 | JDK 21.0.10 / Maven 3.9.12；11:18 主应用及 38 个 reactor 模块编译通过，启停矩阵仍待验收 |
| M02 | in_progress | endpoint/process 持久模型及升级 SQL；按实际 Mapper 对齐字段 | 目标 localhost:3306/innolux_iws；SHOW GRANTS 仅 SELECT/SHOW DATABASES/SHOW VIEW；MySQL 8.0.45，utf8mb4_general_ci，新模块表查询返回 0 行 |
| M03 | in_progress | 定义聚合、版本、完整图校验及系统模式原子保存 | 待代码及隔离验收 |
| M04 | in_progress | 挂载、触发、确认与运行命令 | 待代码及隔离验收 |
| M05 | in_progress | 单动作执行、租约、检查点与恢复 | 待代码及隔离验收 |
| M06 | in_progress | 分支、汇合和查询 | 待代码及隔离验收 |
| M07 | in_progress | 已冻结 job/process 窄端口，SnailJob 薄接线实施中 | 未调用真实平台 |
| M08 | pending | 无已确定的多级审批需求，按规格暂不启用 | 不影响普通确认 |
| M09 | in_progress | 配置与运行审计随实现接入 | 待事务及拒绝路径验证 |
| M10 | in_progress | endpoint 基础三页及 process 管理页面；后端契约稳定后联调 | 待类型、构建及浏览器验收 |
| M11 | in_progress | 本次完成公共能力隔离整合；真实业务演练按用户决定移至后续接入 | 端点真实 MySQL 14 项、菜单 8 项通过；端点 HTTP 6 项通过报告已生成，浏览器仍待验收 |
| M12 | pending | 全部代码及 M11 验收通过后才生成使用 Skill | 尚不创建占位 Skill |

当前验证环境：默认 Java 8；本次构建通过进程 JAVA_HOME 使用 jvms 中现有 JDK 21，不修改全局版本。原生 MySQL 8.0.45 在 127.0.0.1:13307 使用本任务 visualizations 下独立数据目录运行，仅专用 `_test` 库写入；真实库始终只读。Web Vite 监听 127.0.0.1:9915，隔离 HTTP 宿主联调准备中。

独立 Review 发现地址权限裁剪、关联加载门禁、分权限选项加载、索引前缀核验与 SQL 续错失败控制五项问题。Web 两项已修复并 lint 通过；后端与 SQL 修复仍待最终复核。完整审查记录位于本任务 visualizations 的 review.md，不能将旧快照审查结论套用到后续文件。

所有 D 项仍待实际实现验收；文档、编译、Mock 和真实隔离数据库证据分别记录，不以子代理退出或零项测试作为完成信号。
