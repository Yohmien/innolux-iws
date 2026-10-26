# 计划事件平台交付记录

2026-10-24。公共平台已交付，生产业务链路后续接入。

## 产品提交

| 仓库 | 提交 | 内容 |
| --- | --- | --- |
| 后端 | `4b9ad3efc` | endpoint 与 process 公共平台、默认关闭及模块装配 |
| 后端 | `691a7c247` | Job 调度接线与菜单能力过滤 |
| 后端 | `6cd772cbe` | endpoint/process/menu 升级 SQL |
| 后端 | `11fb4bf6c` | 完整空库初始化 SQL 与单目标建库入口 |
| Web | `b7c16db` | 保留业务错误上下文和静默取消请求 |
| Web | `1ae1240` | 系统、环境、共享地址配置页面 |
| Web | `56ba250` | 计划配置、执行追踪和恢复操作页面 |

后端来源 `upstream/main` 的产品交付点为 `11fb4bf6c`；产品历史同时同步个人 `origin`、`github`。Web 产品同步其 `origin/main`。PDA 产品无变更。

文档、测试与项目 Skill 在产品交付点之上的 `personal/plan-event-public-platform-20261024` 分支继续提交，仅用显式 refspec 推送个人 `origin/main`、`github/main`。个人交付完整包含 upstream 产品历史，本地产品 `main` 跟踪 `upstream/main`。规则见[提交与远端同步规约](project-delivery-policy.md)。

## 初始化与项目 Skill

- [从零初始化](iws-initialization.md)：123 张表和必要种子，不包含 WMS 演示业务数据；非空库拒绝重跑。存量平台升级仍见[升级说明](plan-event-upgrade.md)。
- [项目领域 Skill](../.agents/skills/iws-plan-event-project/SKILL.md)：新需求先判断平台复用，按接入、配置和运行逐层披露。
- [RuoYi 项目入口](../.agents/skills/ruoyi-plus-iws-project/SKILL.md)及[扩展索引](../.agents/skills/ruoyi-plus-iws-project/references/extensions.md)：按需引用领域指南，通用 RuoYi 正文不复制，全局 Skill 保持原状。
- [Skill 评估](plan-event-skill-evaluation.md)：独立 dry_run 后补全恢复参数、恢复预算和首次接单的实例定位职责，未宣称自动触发实测。

项目入口仅安装于当前后端仓库 `.agents/skills`，Web/PDA 经本机集中规约读取同一份内容。三个本机 `AGENTS.md` 仍保持忽略，集中规约已记录远端边界和项目路由。

## 验证与保留项

本次重新执行 Web 5 个测试文件、23 项及 lint，通过；初始化在独立 MySQL 8.0.45 完成首跑与拒绝路径验证，见初始化说明。项目 Skill 元数据、链接及受影响 DTO 经检查。后端功能逻辑沿用上一轮构建、178 项整合回归与定向契约修复的有效结果，见[原实施验收记录](plan-event-verification.md)；本轮 Java 仅定向移除交付新文件的多余 EOF 空行，未修改功能逻辑，未重复运行整套后端验收。

交付前检查完整暂存差异及 `git diff --cached --check`。任务开始前已有的 `README.md`、`docs/iws-standard-crud-page-design.md`、`docs/wms-smt-standard-data-model.md` 修改保留在工作树，不混入本次提交。Web 的契约对齐文档转入后端个人仓库的[对齐清单](plan-event-web-api-alignment.md)，来源 Web 仓库不推送该文档。

未在真实 3306/3307 执行数据库写入；当前目标 3307 的 MCP 连接匹配限制仍存在。未做生产部署、真实外呼、业务链路接入、角色授权或 SnailJob 平台联调。专用隔离 MySQL 13308 已正常关闭。
