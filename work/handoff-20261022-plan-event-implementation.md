# IWS 计划事件与系统端点模块实施交接

> 快照：2026-10-22 10:31（Asia/Shanghai）。接收方：后续实施代理。当前交付为方案与交接文档，尚未实现业务代码。

## 1. 接续目标与授权

目标是在现有IWS框架中落实计划事件引擎、两类长期监督调度、独立系统端点配置模块及Web管理页面。以现有四份权威方案的当前工作树内容为依据，不从旧提交或旧交接重建规格。

本次用户请求是提供实施交接文档，不是在本对话开始编码。后续对话若已明确要求按方案实施，直接在该授权范围内继续，不重复确认已明确的架构；尚缺真实业务输入时先核对代码/现有资料，只有无法查证且改变业务结果的决定才请求用户补充。

本交接不授予提交、推送、生产数据库写入、发布、真实过账、外部系统调用或任务启停权限。真实数据库保持只读；隔离验证和运行命令按接续任务的授权及项目规约执行。

## 2. 权威材料与读取顺序

1. 读取目标仓库当前AGENTS.md及[集中项目规约](C:/code/company/doc/innolux-iws/agent.md)，按本次实际工作选择其参考，不全量展开Skill。
2. [完整方案](C:/code/company/innolux-iws-plan-event-docs/docs/plan-event-state-machine-design.md)是行为规格；优先关注§4.2.1系统汇总/批量屏蔽、§17监督QUEUE/IMMEDIATE、§18独立endpoint模块/基础页面，再结合各章节核对完整上下文。
3. [实施计划](C:/code/company/innolux-iws-plan-event-docs/docs/plan-event-state-machine-implementation-plan.md)给出M01-M12、类职责、事务/恢复协议及D01-D48；§18列P1缺项，§24-§26补齐最新落点。
4. [场景与模式复核](C:/code/company/innolux-iws-plan-event-docs/docs/plan-event-state-machine-sequences.md)覆盖34个场景；27-32为监督/即时/调度记录，33为计划内屏蔽，34为独立基础配置。
5. [核验证据](C:/code/company/innolux-iws-plan-event-docs/docs/plan-event-state-machine-evidence.md)区分参考能力、目标现状与拟议实现；§16记录系统IP来源、目标库和独立模块边界。

四份文档已保存全部最新增量，不能只读HEAD。旧文件[2026-10-21交接](C:/code/company/innolux-iws-plan-event-docs/work/handoff-20261021-plan-event-state-machine.md)只作历史背景；其干净状态、场景数量、单模块方案和同步结果不能当作当前事实。本交接不复制整份规格，发生后续变更时更新权威规格及其验收引用。

## 3. 仓库与工作区快照

| 用途 | 路径 | 分支 / HEAD | 当前状态 |
| --- | --- | --- | --- |
| 权威方案工作树 | C:/code/company/innolux-iws-plan-event-docs | personal/plan-event-state-machine-design / b6aad43beba1170149e2f2ff9b02bdd692f59f8d | 四份方案modified，未暂存 |
| 后端代码工作树 | C:/code/company/innolux-iws | main / 57391dda3e8971cca434791686ebde690a9937b0 | 三份既存文档modified，未暂存 |
| Web代码工作树 | C:/code/company/ui/innolux-iws-web | main / 8bc9106844e00842450c91de7723b5ed02546c3b | 干净 |
| PDA工作树 | C:/code/company/ui/innolux-iws-pda-plus | master / ab767065e4e4477f4659cb2fc129f624a0ea162a | 干净；当前功能无需修改PDA |

后端必须保留的既存修改：`README.md`、`docs/iws-standard-crud-page-design.md`、`docs/wms-smt-standard-data-model.md`。文档工作树必须保留四份当前方案；本次交接另新增本文件。所有现有暂存区为空，接续时重新核验，不使用reset/checkout/stash或整仓格式化取得干净状态。

建议在后端/Web代码工作树开展实施，不把生产代码混入个人方案分支。需要隔离工作树时先核对现有附件/工作树；新工作树不会包含上述未提交规格，必须显式读取本方案绝对路径。新建分支默认codex/前缀，具体分支/隔离方式遵循接续授权。

后端本地AGENTS.md说明origin/github为个人仓库，upstream为来源仓库；规划、测试和本地材料不得推送到upstream。当前没有任何新提交或推送授权，不运行git同步别名；同步/远端状态在确有需求时再现场核验。

## 4. 最新需求与实施时不可丢失的边界

以下仅作定位检查，具体字段、错误、事务和API以权威章节为准。

| 主题 | 已明确的边界 | 规格入口 |
| --- | --- | --- |
| 事件粒度 | BUSINESS事件仅INBOUND/OUTBOUND/INTERNAL三方向，一事件一逻辑动作；多调用/多个独立本地效果拆事件；异步O_SUBMIT/I_RESULT分立 | 主§1、§4、§8；实施§19、§23 |
| 模式与恢复 | 按计划版本+systemCode一模式；实例冻结。BYPASS仅合法外部出站抛账，零发送不伪报外部成功；UNKNOWN不盲重放，首次确认与失败重试分开 | 主§4.2、§6、§9；D01-D34 |
| 长期监督 | 每业务/类型/受控scope唯一SUPERVISOR，只有DECISION/LOCAL INTERNAL调度事件；有限轮次可恢复，空队列不结束监督；不在监督事件中执行业务 | 主§17.1-17.3；D35/D40/D41 |
| 两类模式 | QUEUE整计划PLAN_SLOT串并行、未开始挂起、受控插队/公平；IMMEDIATE单计划、有界同步结果、无普通异步排队/自动重试，超时/未知/失败待安全收口不能误释放名额 | 主§17.4-17.8；D36-D42 |
| 系统/地址 | 逻辑systemCode、caller、目标和端点分责；多个系统可共享一个地址，IP只维护一处。改IP不改模式、不令旧SEND_INTENT/UNKNOWN漂移 | 主§4.2.1、§18；D43-D48 |
| 一次性屏蔽 | 在计划配置→系统模式内，从完整DAG汇总全部事件系统，逐系统选择屏蔽、影响预览、一次原子保存；未选系统保持原mode，取消BYPASS明确恢复模式 | 主§4.2.1；场景33；D43-D46 |
| 展示/政策集合 | displaySystems可含caller/target；policySystems才进入模式向量。只有来源的系统只读无模式，不臆造策略；INTERNAL所需政策不得遗漏 | 主§4.2.1；D43/D45 |
| 独立配置模块 | iws-endpoint可独立启用，iws-process按需依赖其窄只读Reader。endpoint不依赖process/job/wms；配置读取是基础设施协作，跨模块业务动作仍走HTTP | 主§18.1-18.2；实施§26；D47 |
| 可视化基础配置 | 独立“系统接入配置”菜单，system/environment/address三页，普通列表+表单/关联/权限；屏蔽菜单仍在计划页，地址维护跳基础页后重取目录/预览 | 主§18.3；场景34；D48 |
| 独立审计/事务 | endpoint自有system-address关联、audit和catalog_control；配置更改独占目录锁，process同库保存通过Reader共享门禁后锁definition；不反查process合同作为目录保存前提 | 主§4.2.1、§18.2；实施§25-26 |

用户明确了上述业务方向；类名、表名、iws-endpoint模块名及具体工程接口属于当前方案落点，尚未创建。若真实框架事实要求最小调整，保留行为和验收并同步规格，不静默改变公共语义。

## 5. P1缺项与下一步实施顺序

公共方案已复核闭合，首批真实业务还不能由示例猜定。缺项集中在实施计划§18及主§17.9，至少包括：

- 两条真实链路的任务唯一键、businessCode/planType、scope解析、系统/本系统码、实际业务状态及逐事件HTTP合同或LocalAction。
- 输入/产物、Skip/BYPASS替代合同、幂等/对账证明和即时计划安全失败合同；参考SAP JCo不等于目标已有HTTP接口。
- QUEUE/IMMEDIATE选择、PLAN_SLOT上限、公平窗口K、独立CALL资源键/容量及跨类型提升范围。
- 当前环境名、真实系统映射/端点关联、超时与租约/续租、worker批量/时长、重试参数及业务权限范围。

建议接续流程：

1. 核对本交接快照、当前规约和授权，确认四份规格与验证快照是否仍一致。
2. 先核实现有代码/POM/配置和可用只读连接，将P1已能查明的事实补入原方案；不能查明的业务选择保持待决，不阻塞与其无关且已授权的公共能力工作。
3. 已获得实施授权时，复用M01-M12依赖图：独立endpoint骨架/开关与基础模型→目录/独立审计/Reader→process骨架/定义与最小审计→挂载/单动作执行/恢复→分支/查询→监督/调度接线及Web→真实业务演练。M09必要审计贯穿，M08仅多级审批确需时启用。
4. 不另建重复规格/票据体系。实际拆分有独立写集/依赖的实施工作时，按当前项目规约委派并整合；先明确公共契约和验收，再派工。
5. endpoint基础页面可与稳定后的process页面并行；计划页不得另建共享地址编辑副本。全部代码及真实验收完成后才进入M12接入/使用Skill，不提前生成或声称已可用。

接续用户若只授权部分模块，就只完成该范围；不要从本表推导全量编码、数据库上线或外部调用授权。

## 6. 验证证据与适用版本

最新制品目录：`C:/Users/yohmi/.codex/visualizations/2026/10/22/01a16142-f235-7993-8a07-72541eac383a/system-endpoint-review`。

- [完整审阅版](C:/Users/yohmi/.codex/visualizations/2026/10/22/01a16142-f235-7993-8a07-72541eac383a/system-endpoint-review/plan-review-rendered.html)
- [本轮增量差异](C:/Users/yohmi/.codex/visualizations/2026/10/22/01a16142-f235-7993-8a07-72541eac383a/system-endpoint-review/delivery.html)：比较该目录baseline/docs与最终文档，不是HEAD以来全部修改。
- [格式/链接/快照验证](C:/Users/yohmi/.codex/visualizations/2026/10/22/01a16142-f235-7993-8a07-72541eac383a/system-endpoint-review/document-validation.json)、[图表/导航/独立SVG验证](C:/Users/yohmi/.codex/visualizations/2026/10/22/01a16142-f235-7993-8a07-72541eac383a/system-endpoint-review/diagram-validation.json)、[交付与Review摘要](C:/Users/yohmi/.codex/visualizations/2026/10/22/01a16142-f235-7993-8a07-72541eac383a/system-endpoint-review/delivery-context.json)。

已验证34个场景、34条模式说明、37张图成功渲染，页面错误0、失效锚点0，格式/链接/git diff --check通过。时序文档含36图，包含34场景图和2契约图，不应写成36个场景。有限独立方案Review已闭合展示/政策集合及endpoint反向依赖问题，无剩余阻断；不代表实现验收。

2026-10-22 10:31再次核对以下SHA-256均与document-validation.json的current快照一致：

| 文档文件名 | SHA-256 |
| --- | --- |
| plan-event-state-machine-design.md | a327e88f33602f665c44f4367592ca5088701b68109c559b90a460540d9c9586 |
| plan-event-state-machine-implementation-plan.md | 599aca4127e8bca352198573404aeb13c4778b27a4208b47a9d54b38f2436fac |
| plan-event-state-machine-sequences.md | 0de2c0bec093b5d740781dc72fdb6e48fa321b5cee34d08af4b206f4a62dc159 |
| plan-event-state-machine-evidence.md | 755c00d54fa1a55926407de0294702612517344b71e47830fb085cb55837417d |

这些仅覆盖四份方案；本交接新增文件不在旧制品快照内。文档继续改变时不得沿用旧渲染/Review结论，按实际影响复验。

未执行：业务代码编译、Mapper/并发/回滚测试、模块启停实测、基础页面或计划页面真实联调、SnailJob启停、真实HTTP过账或数据库写入。实施后按D01-D48及[项目验证入口](C:/code/company/doc/innolux-iws/references/verification.md)逐项取证，不能以文档渲染、Mock或0项测试替代实现验收。

数据库前轮目标由用户明确为localhost:3306/innolux_iws，覆盖源码dev的3307选择；核验只读账号授权仅SELECT/SHOW DATABASES/SHOW VIEW，process/schedule/endpoint拟议表未部署。接续查库必须重新解析当前配置或沿有效用户指定目标、首查SHOW GRANTS，不把旧授权/空表结论当实时事实。参考代码目录为C:/code/company/vietnam_pcba_avc，只读取证；不复制实际IP、凭据或旧PCBA业务实现。

## 7. 代理与整合状态

以下子代理状态已在交接时重新查询，均completed、已停止写入，无在途代理/命令/业务服务，无待整合补丁：

| 代理ID | 职责/原写集 | 状态 |
| --- | --- | --- |
| 01a16143-d229-76b3-93d8-0f5391f5bb88 | Flash/high，基线、验证及可视化制品，仅visualizations目录 | completed，制品已核验 |
| 01a16145-6e2e-78c3-87c4-5bb0692db53d | Flash/high，sequences.md场景33/34及一致性 | completed，已整合；最后Mermaid标签引号由主代理修复并复验 |
| 01a1615c-9cbb-7b53-bf4d-e14933af9206 | Flash/high，参考目录探索及implementation-plan.md模块/页面落点 | completed，已整合 |
| 01a1614a-bbce-7d23-8d99-d9cddeb5b718 | 继承主模型，独立方案Review，只读 | completed，有限审查结案 |

新会话可能不能恢复这些代理，先核对现有文件/制品再按新任务派工，不把completed当作业务已完成。按用户当前规约，实施/执行/探索子代理每次显式model="deepseek/deepseek-flash"、reasoning_effort="high"；独立Review继承主对话模型。共享契约、业务取舍、授权和最终验收由主代理负责；工具不支持指定模型时应说明限制，不能静默换模型或改用新用户聊天代替子代理。

## 8. suggested skills

仅按接续的下一实际动作读取，以下入口不要求一次全加载：

| 下一动作 | Skill入口 |
| --- | --- |
| 已授权实施 | [implement-spec](C:/Users/yohmi/.codex/skills/implement-spec/SKILL.md)、[boring-engineering](C:/Users/yohmi/.codex/skills/boring-engineering/SKILL.md)；复用已有规格/工作包 |
| 修改本地文本 | [patch-editing](C:/Users/yohmi/.codex/skills/patch-editing/SKILL.md) |
| 三个IWS代码仓库内后端/Web实现 | [ruoyi-plus-ai-coding](C:/Users/yohmi/.codex/skills/ruoyi-plus-ai-coding/SKILL.md)，再按集中规约选backend/web/数据参考 |
| 跨文件定位真实调用/影响面 | [search-gates](C:/Users/yohmi/.codex/skills/search-gates/SKILL.md)，精确文件/文本直接rg |
| 新模型/持久化的真实结构取证 | [database-readonly-mcp](C:/Users/yohmi/.codex/skills/database-readonly-mcp/SKILL.md)，真实库只读 |

审查、SQL升级、页面视觉/交互验证、提交及最终使用Skill等入口，在实际进入对应动作时再按当前规约加载，本交接不提前启动这些流程。

## 9. 可直接传给接收代理的任务入口

先读取本文件及其列出的当前权威规格，核对仓库/工作区/哈希和接续用户授权。沿用iws-process与独立iws-endpoint的职责、计划内逐系统屏蔽和独立基础配置页面设计，保留所有已有修改；先查证P1真实业务映射，再按已授权范围推进M01-M12及D01-D48。公共能力与真实业务接入分别验收，缺少的业务事实不得由示例猜定；不要把本交接或文档验证当作已获上线/真实过账授权。
