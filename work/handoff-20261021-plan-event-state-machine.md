# 计划事件状态机方案交接

> 交接时间：2026-10-21（Asia/Shanghai）。仅交接当前状态，不扩大原有授权。

## 1. 当前目标与边界

用户要求基于越南奇宏 SMT-PCBA 的接口日志、事件过账和重试方案，设计 IWS 的计划、事件、状态机、业务统一调度、前后端管理台、权限/租户和复用方案。当前阶段只允许修改方案文档，不授权业务代码、SQL、配置、数据库或真实过账操作。

本轮已交付四份方案文档，并完成 Git 多远端同步配置。交接代理应先核对现场，不应直接开始编码。

## 2. 仓库、分支与工作树

| 项目 | 当前值 |
| --- | --- |
| 代码工作树 | `C:\code\company\innolux-iws` |
| 代码分支 | `main`，`57391dd`，跟踪 `origin/main` |
| 文档工作树 | `C:\code\company\innolux-iws-plan-event-docs` |
| 文档分支 | `personal/plan-event-state-machine-design`，以文档工作树当前 HEAD 为准 |
| Gitee 个人远端 | `origin` → `https://gitee.com/yumuing/innolux-iws.git` |
| GitHub 个人远端 | `github` → `https://github.com/Yohmien/innolux-iws.git` |
| 来源远端 | `upstream` → `https://gitee.com/July-Yongjie/innolux-iws.git` |

三仓 `main` 已统一为 `57391dd`。个人仓库原有 `6.X` 分支保留，未改写；两个个人仓库的服务端默认分支仍是 `6.X`，`main` 和文档分支已额外建立。

文档分支内容已发布到个人 Gitee 和个人 GitHub，未发布到 `upstream`。GitHub HTTPS 曾间歇失败，部分文档提交通过 GitHub Git Data API 同步；远端内容等价，但提交哈希可能不同。网络恢复正常后可用本地 Gitee 分支做一次 `--force-with-lease` 对齐 GitHub 文档分支；不要推送到 `upstream`。

## 3. 用户已有修改

`main` 工作树存在任务开始前已有的未提交修改，必须保留：

- `README.md`
- `docs/iws-standard-crud-page-design.md`
- `docs/wms-smt-standard-data-model.md`

文档工作树在本交接提交后应为干净；若存在修改，先核对是否属于后续未完成工作。不要 `git add .`、不要 reset/checkout 主工作树这三个文件。

本地项目规约已补充到被忽略的 `C:\code\company\innolux-iws\AGENTS.md`，不会进入任何远端。

## 4. 已生成的方案文档

- [完整方案](plan-event-state-machine-design.md)：状态/事件分层、模式、跳过、分支、异常、恢复、统一调度、数据模型、前后端、审计、权限与验收。
- [分场景时序图](plan-event-state-machine-sequences.md)：16 个序列场景，覆盖挂载、确认、执行、跳过、分支、异常、续跑、监督、前后端查询、代码锁定和审计。
- [源码与数据库核验](plan-event-state-machine-evidence.md)：旧 PCBA/IWS/Warm-Flow/SnailJob 证据、目标库快照和限制。
- [模块复用、设计模式与实施计划](plan-event-state-machine-implementation-plan.md)：直接复用、参考模式、最小新增、按需依赖、M01-M11、设计模式选型、权限与租户规划。

可视化审阅版：`C:\Users\yohmi\.codex\visualizations\2026\10\21\01a15d91-81d3-7da0-9e68-94bfcee37109\plan-review-rendered.html`。

## 5. 已接受的关键设计决定

- 过账模式唯一键是 `(plan_definition_version_id, system_code)`；一个系统一条单值记录，不同系统可分别人工确认、自动、屏蔽。
- `MANUAL_CONFIRM` 首次将就绪事件置 `MANUAL_PENDING/FIRST_CONFIRM`，其余 `WAIT_PREDECESSOR`；确认按实例+系统保存。确认后自动执行，任何执行失败立即回 `MANUAL_PENDING/EXECUTION_FAILED`，后续为 `RETRY_EVENT`，不重新首次确认。
- `AUTOMATIC` 直接自动执行；`BYPASS` 只跳过非本系统的 `EXTERNAL_POSTING` 操作步骤，查询、控制、本系统与本地步骤继续执行。
- `enabled=false` 才整事件纯跳过：不调用任何 Operation，只做结构校验、状态迁移、审计和后继唤醒。
- 计划配置与事件配置在同一页面；可切换查看版本，只有最新草稿可编辑；事件列表只读，一次只编辑一个事件。
- 发布新版本后旧 ACTIVE 版本转 RETIRED，旧计划/实例保留原快照；新配置只供新计划和后续新实例。
- 每个业务要有统一调度计划；排序为计划类型优先级 → 实例优先级 → 事件优先级 → 就绪时间/ID。
- 单个统一事件更新入口负责执行/跳过；续跑复用原 updater/operation 和检查点。
- SnailJob 只做调度；Warm-Flow 只做人审；领域事件表是外部成功、租约、分支 token 和恢复的唯一事实。
- 当前 IWS 没有可用的多租户实现，不新增 `tenant_id` 或租户过滤；未来真正引入多租户后再按文档 §10.5 升级。
- 原后端已有 Sa-Token 接口权限（统一拦截器、217 处 `@SaCheckPermission`、`StpUtil.checkPermission`、`@DataPermission`）。复用现有机制，不新增第二套权限框架或启动扫描器。

## 6. 代码复用与最小新增

详细映射见 [模块复用与设计模式计划](plan-event-state-machine-implementation-plan.md)。核心结论：

- 新增一个 Maven 模块 `iws-modules/iws-process`，不拆多个子模块。
- 复用 SnailJob/`iws-job`、Warm-Flow/`iws-workflow`、Sa-Token、动态菜单、字典、日志、MyBatis-Plus、分页和通用前端 hooks。
- 仅新增领域表、统一事件更新、状态/分支校验、执行读模型、审计和两个薄执行器；复杂页面不使用 `IwsCrudPage`。
- 设计模式：适配器、命令、策略、外观、责任链、备忘录、有限 Builder/Prototype 适用；状态采用数据驱动而非每状态一个 GoF 类；不为每个事件建工厂或控制器。

## 7. Git 同步规则

- 完整代码、测试和说明文档：`git sync-personal`，只推 `origin` 与 `github`。
- 生产代码：`git sync-all-code`，依次推 `upstream`、`origin`、`github`。
- `upstream` 白名单只包括 POM/Maven 包装器、`*/src/main/**`、`script/sql/**`、`script/docker/**`、`script/bin/**`、Dockerfile；测试、README、docs、AGENTS、规划文件会被 `.git/hooks/pre-push` 拦截。
- 个人文档分支只能 `git push origin personal/plan-event-state-machine-design` 和 `git push github ...`。
- 不要删除或改写个人仓库 `6.X` 分支；不要向来源仓库推送本地文档或测试。

## 8. 验证与当前状态

- 方案文档：17 张 Mermaid 图全部解析/渲染成功，页面错误为 0。
- 文档编码、链接、代码围栏、尾随空格和 `git diff --check` 已通过。
- `git sync-personal` 与 `git sync-all-code` 均已实际执行成功（当前均为 no-op）；推送文档分支到 upstream 的 dry-run 被保护钩子拒绝。
- 交接时未发现仍在写入本任务的子代理；接续前按现场重新核验，不把本快照当作当前状态。
- 未修改后端/前端业务代码，未执行数据库 DDL/DML，未启动应用或真实过账。

## 9. 待确认与下一步

1. 用户尚未授权实施。先评审方案，不直接创建 `iws-process` 代码。
2. 实施前需要确定 P1 的两条真实业务链路：任务唯一键、`plan_type`、系统码、源/目标状态、操作实现、校验器、跳过合同、优先级数值、首批业务调度范围。
3. 实施前按需只读核验目标库；当前 `localhost:3306/innolux_iws` 尚无拟议计划事件表。
4. 若用户批准实施，再拆 tickets，先做 P2 最小配置/挂载，按 M01-M11 推进。
5. GitHub HTTPS 连接曾多次超时；本次文档内容已通过 API 同步，后续恢复网络时再对齐文档分支提交历史。

## 10. 下一步安全动作

先读取 `C:\code\company\innolux-iws-plan-event-docs` 下的四份文档，核对 `main` 与文档工作树状态，然后向用户确认 P1 业务映射或按用户指定范围继续文档评审。不要恢复或推送任何被忽略的本地材料；不要 commit 三个既有 dirty 文件。

## 11. Suggested skills

- 继续修改中文方案文档：`C:/Users/yohmi/.codex/skills/chinese-technical-writing/SKILL.md`。

## 12. 可恢复入口

- 主工作树：`C:\code\company\innolux-iws`。
- 文档工作树：`C:\code\company\innolux-iws-plan-event-docs`。
- 本地可视化：`C:\Users\yohmi\.codex\visualizations\2026\10\21\01a15d91-81d3-7da0-9e68-94bfcee37109`。
- 当前文档远端：个人 Gitee/GitHub 的 `personal/plan-event-state-machine-design`，内容已同步，提交哈希因 GitHub 网络故障暂不相同。
