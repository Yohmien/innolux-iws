# 公共平台续接验收记录

> 本文保留续接中的检查快照；最终状态以 [公共平台验收记录](../../innolux-iws/docs/plan-event-verification.md) 为准。

本记录补充 [实施进度](implementation-20261022-progress.md)，不替代四份权威规格。范围沿用用户决定：先交公共平台，真实业务链路后续接入。未执行提交、推送、真实库写入、部署或真实外呼。

## 已取得的证据

产物目录：`C:/Users/yohmi/.codex/visualizations/2026/10/22/01a16176-b64c-78b2-9aa8-33beed46c04d`。

| 内容 | 实际结果 | 证据及适用范围 |
| --- | --- | --- |
| 查询与基础运行时 | 查询 5 项、运行时 4 项通过，0 跳过 | `continuation-query-runtime.log`；后续查询与运行时已有增量，不能作为最终版本绿灯 |
| 分支 | 9 项通过 | `continuation-recovery.log`；同次运行时新增用例发现出站请求记录缺少必填 purpose |
| Web 基线 | lint、vue-tsc、build 通过，4 文件共 16 项测试通过 | Web 代理实际执行；后端契约后续仍有增量，需再次对齐 |
| 独立运行时审查 | 发现 5 项 P1，尚未全部复验关闭 | `runtime-review-current.md`；成功检查点、UNKNOWN、QUERY 恢复期限及证据消费、T2 租约窗口 |
| 浏览器 | 尚未完成 | Edge 连接可用，原 9720/12950 本地服务已停止；不能将单测或构建当作页面验收 |

## 当前增量与待验收

- 请求总览 `/process/requests/page` 与 `/process/requests/{requestId}` 已补实现及隔离测试；保留未关联记录，安全投影不包含原报文。
- 查询事件位次改为复用 worker 的 `PlanCandidateQueryService`；新增用例发现数值转字符的连接排序规则冲突，已明确 utf8mb4/general_ci，待复验。
- 出站方向记录从冻结事件定义读取 purpose；新增真实交互记录用例待复验。
- TF 保留已经提交的成功效果与检查点；SEND_INTENT 后无确定结果统一保存 UNKNOWN。新增反例待复验。
- RECOVERY_ONLY QUERY 使用独立期限，保留原 stopRequested/executionDeadline；T2 在等锁后重验租约，CAS 同时要求租约未过期。新增反例待复验。
- 监督同 commandKey 重投先锁控制行再查回执，使用 READ_COMMITTED 读取等待期间已提交的回执；并发反例待复验。
- 实例动作元数据、attempt 分页、QUERY 证据消费、拒绝审计 MVC 验收、完整模块扫描矩阵由独立写集推进，尚未整合验收。
- 升级 SQL 对齐、最终独立复核、浏览器、M12 使用资料及统一 Diff 仍待完成。

构建使用现有 jvms JDK 21，仅设置进程 JAVA_HOME。所有 Maven `-D` 参数作为完整引用传递。MySQL 写入验证只允许显式 datadir 核验后的 13307 隔离测试库；真实目标仍保持只读，未迁移新表。

并行写入期间两次 Maven 编译读取到尚未写完的方法，属于中间版本失败，日志保留；整合后必须重新运行，不能据此声称当前版本通过或把进行中代码删除。
