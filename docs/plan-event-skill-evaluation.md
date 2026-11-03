# IWS 项目 Skill 安装与评估记录

2026-10-23。范围为当前三个 IWS 仓库的项目 Skill，只做结构检查、链接核验和 dry_run 推演；没有启动独立前向代理，也没有把推演结果记为实测。

## 安装结果

- [iws-plan-event-project](../.agents/skills/iws-plan-event-project/SKILL.md)：项目入口、业务接入、配置发布、运行恢复四层渐进披露。
- [ruoyi-plus-iws-project](../.agents/skills/ruoyi-plus-iws-project/SKILL.md)：三仓库白名单、全局基线委托、IWS 项目覆盖、功能扩展指针及计划事件领域入口。
- 全局 iws-plan-event 与 ruoyi-plus-ai-coding 保持原样。

Skill 发现依据 OpenAI 当前文档：[Build skills](https://developers.openai.com/codex/skills/) 说明 Codex 从工作目录向仓库根扫描 .agents/skills；同名 Skill 会分别出现，不会合并。

## 基线成本

下表按 UTF-8 字节和 LF 行数统计；token 数为本机文本工具对正文返回的计数，不是计费账单，也不代表行为得分。

| 对象 | 文件 | 字节 | 行 | token |
| --- | ---: | ---: | ---: | ---: |
| 全局 IWS 入口 | 1 | 6566 | 38 | 1642 |
| 全局 IWS 四份全部 | 4 | 36445 | 449 | 9115 |
| 项目 IWS 四份全部 | 4 | 16179 | 155 | 4048 |
| 全局 RuoYi 入口 | 1 | 4754 | 51 | 1189 |
| 全局扩展索引 | 1 | 2098 | 24 | 525 |
| 项目 RuoYi 三份全部 | 3 | 7061 | 74 | 1768 |

全局 IWS 的“入口 + 一份动作参考”为 4062 至 4232 token；项目版为 1782 至 2206 token。项目版把已有平台、业务待接入和必须保留的风险边界留在入口，将字段级过程放到命中分支。

项目 RuoYi 增加的是三仓库范围判断、项目映射和扩展路由；通用编码正文继续由全局入口按需提供，没有复制原文。它带来的固定入口成本高于直接选择全局入口，换取的是唯一项目名称和明确的三仓库边界。

## 链接解析

全部 8 份项目文档通过解析检查，共 38 个 Markdown 目标存在。相对路径按声明它的文件目录解析：

- iws-plan-event-project/SKILL.md 的 ../../../docs/... 解析到 innolux-iws/docs/...。
- iws-plan-event-project/references/*.md 的 ../../../../docs/... 与 ../../../../iws-modules/... 解析到仓库根下的对应目录。
- ruoyi-plus-iws-project/references/extensions.md 的 ../../iws-plan-event-project/SKILL.md 解析到同级项目 Skill。
- plan-event-skill-evaluation.md 的 ../.agents/skills/... 解析到仓库根 .agents/skills。

## 代表案例推演

### 常见新业务

请求：“发货完成后调用 MES，MES 受理后回写结果，再更新 IWS 发货状态。”

预期入口先检查能否复用 iws-endpoint、iws-process 和已登记能力。当前公共平台已有调用编排；2026-10-23 源码核验时尚未发现生产业务合同、动作和身份策略。若已有真实能力可复用，则只配置目录、计划和事件；否则拆为 OUTBOUND、结果 INBOUND，再按需要拆本地状态动作。推荐用逐事件表说明唯一动作、完成合同和失败恢复，明确小接入可简表。该案例验证“先复用平台、再逐事件接入”的入口顺序，未执行代码或配置写入。

### UNKNOWN 恢复风险

请求：“MES 调用已经发出但响应超时，现在直接重试并标记失败。”

预期入口要求先读事件的 effectResult、最近检查点、尝试、请求、租约、计划名额和调用许可。响应丢失按原命令键查询；显式恢复只允许满足唯一 OUTBOUND + QUERY、无活动租约、仍有计划名额和调用许可、执行预算为正的事件，不得盲重发原出站。没有证据且副作用未知时继续对账。该案例验证恢复约束可由对应参考发现，未连接数据库或外部系统。

### 不应触发的普通 CRUD

| 请求 | iws-plan-event-project 预期 | 应走入口 |
| --- | --- | --- |
| “给库位档案增加分页 CRUD 和批量删除” | 不触发 | ruoyi-plus-iws-project 与全局后端/前端参考 |
| “物料 Excel 导入增加校验和错误回显” | 不触发 | ruoyi-plus-iws-project 与全局导入参考 |
| “调整角色页面菜单和按钮权限码” | 不触发 | ruoyi-plus-iws-project 与全局权限参考 |

这些案例只比较触发边界。真实自动选择、执行质量和相对全局版本的净收益仍需独立前向验证。

## 独立 dry_run 复核

独立评审发现并修复了以下静态问题：

- 恢复入口原先没有写清 POST /process/event/{id}/retry、RetryEventCommand 和 recoveryOnly=true；现区分新恢复命令使用新唯一键，以及请求响应丢失时沿用原键回查。
- 成功检查点收口原先遗漏正数 maxExecutionMs 恢复预算；现与源码拒绝条件对齐。
- 首次外部接单原先未说明实例前提；现明确业务适配层先挂载或定位实例，trigger 只匹配现有实例。
- RuoYi 入口改为“对应指南”，计划事件扩展项增加外部接入、多节点和异步回调的自然入口词。

以上仅是独立评审后的文本核对与 dry_run 推演；没有验证自动触发，也没有执行重试、恢复、数据库或外部调用。

## 并存与发现风险

- 新名称避免同名 Skill 并列；全局 IWS 与项目 IWS、全局 RuoYi 与项目 RuoYi 的描述仍有部分语义重合，自动选择时需用路径范围、任务类型和差分解耦。
- 后端仓库根的 .agents/skills 只会在从该仓库或其子目录启动时被自动扫描。当前 Web 与 PDA 的现有 AGENTS.md 均链接集中 agent.md，可按需到达项目版 Skill，无需再改 AGENTS。
- 集中 agent.md 已指向项目版入口，Web/PDA 现有 AGENTS.md 已通过它链接集中入口。全局 iws-plan-event 仍保留原文件，与项目版并存。
- 项目 RuoYi 依赖全局 ruoyi-plus-ai-coding 及本机集中路径；换到没有这些全局资料的机器时，项目范围和扩展索引可随仓库交付，但通用正文会缺失。
