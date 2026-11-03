---
name: ruoyi-plus-iws-project
description: 仅在 innolux-iws、innolux-iws-web、innolux-iws-pda-plus 三仓库开发或排查 CRUD、前后端及框架功能时使用；先确认目录，再委托全局 ruoyi-plus-ai-coding 基线，并补读 IWS 项目覆盖与功能扩展索引。其他 RuoYi 项目、纯文案和 Codex 宿主配置不适用。
---

# IWS 项目 RuoYi 编码入口

按完整目录边界只检查一次以下目标：

- `C:/code/company/innolux-iws`
- `C:/code/company/ui/innolux-iws-web`
- `C:/code/company/ui/innolux-iws-pda-plus`

用户明确的目标仓库优先，否则使用当前工作目录。未命中时停止加载本 Skill。文档或输出路径不算目标仓库。

## 委托顺序

1. 当前上下文尚未加载通用入口时，读取[全局 RuoYi Plus 基线](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/SKILL.md)，只进入本次命中分支。
2. 再读[IWS 项目覆盖](references/project-overlay.md)，以当前源码、调用方和集中项目规约为准。
3. 涉及框架功能扩展时，从[IWS 功能扩展索引](references/extensions.md)选择一个命中项，再打开对应指南。
4. 计划事件业务接入、配置发布或运行恢复改用 `$iws-plan-event-project`；普通编码流程不因任务顺带涉及“事件”字样而加载它。

全局后端、前端、生成器资料与 41 项框架指南仍是唯一通用基线，本 Skill 只维护项目范围、映射和路由。已经读取且未变化的材料直接复用。

仓储页面没有特殊要求时，默认尽可能复用现有 `IwsCrudPage` 及其调整、查询和导入导出能力；特殊交互按[仓储页面复用边界](references/project-overlay.md#仓储页面复用)选择局部扩展或专门视图，不把复用当成强制限制。

## 裁决与交付

用户要求、适用项目规约和当前源码优先于示例。保留项目已有的模块/包名、请求方法、参数载体、响应层级、分页类型、权限与事务语义；框架支持某能力不代表当前环境已启用。诊断或审查保持只读范围；依赖安装、数据库写入、外部写入、付费调用和设备操作按实际授权执行。

按本次变化检查代码、契约、持久化或页面行为，分别报告静态检查、模拟、编译和真实环境结果。项目覆盖与功能扩展只提供读取条件和边界，不扩大任务授权。
