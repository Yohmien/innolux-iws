# IWS 底层变更历史

现行约定见 [agent.md](../agent.md)。本文件只记录事件、证据和经验，不参与普通任务的默认加载；记录流程见 [maintenance.md](../references/maintenance.md)。

## IWS-FOUNDATION-0001 · 2026-10-19 · 全局原版与项目规约分离

- 触发：用户要求保留上游原版、抽出全局能力并减少重复加载，随后确定项目规则改由 agent.md 承载。
- 变更类型：代理规范组织与本地安装变更；本次没有修改业务底层代码、SQL 或数据。
- 代码基线：后端 57391dd，Web 8bc9106，PDA ab76706；后端上一轮 README 与两份 docs 的未提交修改保持不变，Web/PDA 无跟踪文件修改。
- 通用来源：RuoYi-Vue-Plus 6.X 固定提交 d7eca3db857946fd4a9f2fe4c1c6873df75ccba6，全局五个上游文件保持原样，许可另行保留。
- 之前：项目 Skill 重述部分通用规则，并在三个仓库安装完整副本。
- 之后：仅全局安装 ruoyi-plus-ai-coding；三个 AGENTS.md 引用同一个集中 agent.md；项目差异进入普通 references；旧项目 Skill 可恢复归档。
- 影响：通用编码规则不再在项目重复维护；PDA 任务只按自身分支读取；项目底层变化形成可追踪的优化证据。
- 归属：project_only。将规则拆分方式用于其他项目前仍应按其边界判断，不自动新增全局补充 Skill。
- 原版保真证据：[source.json](C:/Users/yohmi/.codex/skill-maintenance/ruoyi-plus-ai-coding/source.json)；维护关联：[全局历史](C:/Users/yohmi/.codex/skill-maintenance/ruoyi-plus-ai-coding/history.md)。
- 验证：五个上游文件与固定提交归档逐字节一致，原版 Skill 校验通过；四份归档共 36 个文件与迁移前一致，本地入口、链接、Git 隔离和存量保护检查通过。证据见 [本批次交付报告](C:/Users/yohmi/.codex/visualizations/2026/10/19/01a1532e-9a06-74a0-8ef3-81876872e047/iws-global-agent-split/delivery.html) 和 [archive-manifest.json](archive-manifest.json)。
- 未验证：新会话自动选择和实际开发行为；没有启动应用或执行数据库脚本。显式读取不代表自动触发测试。
- 后续：将真实底层事件以新 ID 追加到本文件，通用候选用 global_candidate 标记并提供可验证理由，不直接改写上游原版。

## IWS-BASELINE-0001 · 2026-10-19 · 已有结构差异的初始观察

- 类型：baseline_observation，只登记当日核对结果，不推测历史代码变更发生时间。
- 已有差异：框架与 WMS SQL 拆分；位置权威为 wms_container.storage_id；标准 WMS 列表走 POST JSON 和 R<PageResult>；PDA 使用独立的 uni-app 请求与设备接入。
- 证据：后端 script/sql/iws_wms_smt_core.sql、IwsCrudController、IwsCrudMetaRegistry；Web src/api/wms/crud/index.ts；PDA utils/request/index.js 与 components/scanCode/scanCode.vue。
- 文档动作：上一轮已修正后端 README、WMS 数据模型和标准页面设计，本轮只迁移对应规则，不再次修改这些文档。
- 归属：project_only。IWS 表名、PDA 厂商广播和现有临时限制不推广为通用 RuoYi 规范。
- 验证边界：静态源码与 SQL 互证；没有真实数据库、运行服务或 PDA 真机验收。

## IWS-FOUNDATION-0002 · 2026-10-19 · Darwin 验证与项目规约优化

- 类型与状态：已实施的代理规范修正，不是业务底层代码变更；触发为用户要求使用 darwin-skill 验证并优化。
- 范围与基线：集中 agent.md、maintenance、pda、verification 参考；集中文档不在 Git 仓库，以本批次 before 字节快照为基线。上游仍使用 source.json 已登记的固定提交，未修改原版、许可或调用策略。
- 变化与原因：项目改包映射收窄到自有包，第三方 sms4j/warm.flow 保留；历史只记录受影响范围并区分正式变更、观察和演练；检查按触发条件执行；补充 PDA 桥接停用/恢复的静态边界。
- 代码证据：后端 BaseEntity、SmsAutoConfiguration/PlusSmsDao、WorkflowServiceImpl 的真实包与 import；PDA components/scanCode/scanCode.vue 的注册和注销钩子。仅只读核对，未借规约任务修业务代码。
- 验证：两个独立执行上下文完成相同五个隔离场景，分别通过自身检查；另做 metadata-only 触发判定和独立配对审查。不同检查集不构成统一覆盖率，完整结果见 [Darwin 证据](C:/Users/yohmi/.codex/visualizations/2026/10/19/01a1532e-9a06-74a0-8ef3-81876872e047/iws-darwin-validation/evaluation.md)。
- 规则同步：只更新受影响规约和普通参考；项目未新增 Skill，集中 context 的业务事实无需变更。
- 经验归属：项目差异为 project_only；“自有包与依赖包边界需分别验证”为 global_candidate，尚未验证其他项目适用性，不改写或推广到原版。
- 未验证与后续：没有真实宿主自动触发、编译、接口、数据库或 PDA 真机验证；最后参考提示补充仅静态审查，不能冒充重新执行全部前向场景。后续实际底层变更按新格式追加证据。

## IWS-FOUNDATION-0003 · 2026-10-19 · 全局框架指南覆盖与目录白名单

- 触发：用户要求全局 Skill 包含官方“框架功能”目录的全部 41 项并保持渐进披露，随后要求入口最前只对当前 IWS 三个仓库作目录适用判断。
- 范围与基线：全局 Skill 的本地扩展入口、41 项指南及全局维护记录；上游五个原文件和许可已保存快照，未改动。项目业务源码与上一轮后端三份未提交文档未动。
- 变化：入口增加三仓库完整边界白名单，未明确目标时使用 cwd，只判断一次；不匹配时不读后续资料。框架资料按 2/5/14/16/4 分为结构、框架相关、基础、扩展和功能说明，单项按任务加载。
- 项目适配：项目规约现在指向全局扩展入口；CRUD 仍先复用既有 `IwsCrudPage`、元数据和服务接入。41 项里的上游示例出现包名、路径、分页或部署地址时，以当前源码、调用方和配置为准。
- 证据与验证：核对 41 项清单、五组分类、本地链接、文件格式、原始快照哈希及示例凭据占位；全局入口已通过 `quick_validate.py`。独立前向验证因子代理限流中止，未完成，不宣称自动触发或真实框架能力已运行。
- 规则同步：全局历史 GLOBAL-RUOYI-0002；本项目 agent/context/sources 已同步。没有执行 SQL、启动服务、安装依赖或调用真实外部系统。
- 未验证：其他 RuoYi 派生项目不得自动套用；真实模块、数据库、消息、加解密、AI/MCP 和设备行为仍需对应环境与授权验证。新项目接入须先明确更新目录白名单。

## IWS-FOUNDATION-0004 · 2026-10-23 · 明确标准化项目的兼容边界

- 触发：用户确认项目为标准化项目，不考虑历史数据兼容，并要求补入项目规约。
- 类型与状态：代理规范与项目定位变更，已实施；不涉及业务代码、SQL 或数据。
- 代码基线：不涉及；本次只修改集中文档，未改动后端、Web、PDA 仓库的任何文件。
- 变化与原因：agent.md 新增“兼容边界”，明确不承担历史版本数据、旧结构与旧接口的兼容义务，不为既有数据保留双结构、过渡字段、双读双写或迁移代码；data.md 同步说明结构变更仍按 sql-idempotent-upgrade 交付可重复执行脚本并保证同一版本分批上线。
- 保留边界：本条不授予数据库写入或删除已有数据的权限，存量数据处置仍需任务级确认。
- 规则同步：agent.md、references/data.md；context.md 与三个仓库入口无需变更。
- 归属：project_only。其他项目是否采用同类兼容策略需自行确认，不推广到全局原版。
- 验证：回读两份文档，措辞与既有授权边界一致；集中文档非 Git 仓库，无 diff 检查可做。
- 未验证与后续：未在实际数据结构变更中演练该规则；如需更强的数据清理授权或旧版本豁免范围，需另行明确并追加记录。

## IWS-FOUNDATION-0005 · 2026-10-23 · 计划事件公共平台

- 状态与范围：已实施，后端 `C:/code/company/innolux-iws` 和 Web `C:/code/company/ui/innolux-iws-web` 未提交工作树；保留后端既有三份文档修改，PDA 不涉及。
- 变化：新增独立 endpoint、process 模块及 job 薄接线、菜单能力过滤和配套 Web。模块默认关闭；新增三份幂等 SQL，目录写锁与定义共享门禁、版本冻结、租约、检查点和请求关联均有隔离测试。
- 公共请求契约：Web 业务错误保留 code/data 与安全 HTTP 元数据，取消读取不弹失败提示；原有消息和 isHandled 语义保留。
- 证据：后端 [验收记录](C:/code/company/innolux-iws/docs/plan-event-verification.md)、[升级说明](C:/code/company/innolux-iws/docs/plan-event-upgrade.md)、[使用说明](C:/code/company/innolux-iws/docs/plan-event-public-platform-guide.md)。构建、隔离 MySQL、真实本地 HTTP、浏览器和独立源码复核分别记录，不互相替代。
- 未完成边界：当前 dev 3307 的 MCP 连接未匹配，未核验或写入真实目标；真实业务链路、生产部署及 SnailJob 平台联调另行进行。
- 规则同步与归属：project_only。集中入口沿用原规约，业务使用以源码和上述资料为准；未修改全局通用编码 Skill。

- M12：依用户实施规格 §7.1 的明确要求，全局单份新增 `iws-plan-event` 使用 Skill，集中入口增加领域指南例外；通用规则没有复制到项目。结构、引用与独立前向验证分别记录，文件存在不等于当前会话已自动发现。

## IWS-FOUNDATION-0006 · 2026-10-24 · 项目领域 Skill 与交付远端边界

- 触发与状态：用户要求 Darwin 优化计划事件接入指南、RuoYi 增加功能扩展引用，暂仅安装当前项目，并将小提交、文档仅入个人仓库、个人仓库包含 upstream 全量历史的要求纳入规约。已实施指令及路由调整。
- 范围与基线：后端产品提交 `4b9ad3efc`、`691a7c247`、`6cd772cbe`、`11fb4bf6c` 已同步三个远端；个人 `origin/main`、`github/main` 在产品祖先上追加测试 `e933cda5c`、文档 `c1a56d83d`、项目 Skill `c72dc53e4`。Web 产品 `56ba250` 已推送其 origin；PDA 产品不涉及。详见后端 `docs/plan-event-delivery.md`。
- 变化：后端 `.agents/skills` 增加 `iws-plan-event-project` 和 `ruoyi-plus-iws-project`。领域入口先判断平台复用和真实业务能力缺口，再按接入、配置、运行分支披露；RuoYi 仅增加薄路由，通用正文及全局两个 Skill 未修改。三个仓库本机入口仍统一引用集中规约。
- 边界：更正 0005 的本项目使用方式；旧全局指南保留原状，当前项目以项目版为准。后端来源 upstream 仅接收产品 main，个人 origin/github 在完整包含产品历史后叠加文档、测试与 Skill；不绕过钩子、不强推。
- 证据与验证：项目 Skill 元数据验证通过；链接及 DTO 按当前源码核对。独立 Darwin dry_run 配对评审支持新需求复用与渐进披露改善，并发现恢复参数缺漏；已补 `recoveryOnly=true`、正数恢复预算和首次接单实例定位职责。未声称自动发现或真实业务接入效果已实测。
- 规则同步：agent.md、三个忽略的 AGENTS.md、references/sources.md 与 verification.md；个人仓库留存 `docs/project-delivery-policy.md`。本次规则变更不授予真实库写入、生产部署或外呼权限。
- 归属：project_only。后续若推广或移除全局旧版须另按实际任务范围实施，不从本次项目安装推导全局变更授权。

## IWS-FOUNDATION-0007 · 2026-10-24 · 空库全量初始化入口

- 状态与基线：已实施，后端产品 `11fb4bf6c`；当前 123 张表对齐基础初始化、WMS 核心和 endpoint/process 升级结构，新增完整 SQL 与单目标建库薄入口，不改变现有业务运行逻辑。
- 变化：目标库仅由入口 `-Database` 指定；只接受不存在或完全空库，SQL 包含必要种子并核验最终状态。独立审查发现 SnailJob 首次管理员遗漏，已按基础模板补回及增加断言。非空库不重建、不清理，初始化不替代存量升级。
- 证据：`docs/iws-initialization.md`；独立 MySQL 8.0.45/13308 最终版首跑 123 表、225 菜单、53 平台菜单，IWS/SnailJob 模板管理员各 1。入口和直接 SQL 重跑拒绝且全表行数不变，错误排序空库拒绝且 0 新增表；隔离服务已关闭。未写真实 3306/3307，真实目标 MCP 限制未解决。
- 规则同步：references/data.md 新增空库入口与一次性初始化边界；文档仅同步个人仓库，脚本同步三个产品远端。归属 project_only，不授予生产部署、外呼或真实库执行权限。

## IWS-FOUNDATION-0008 · 2026-10-26 · APK 版本与 PDA 更新接入

- 状态与基线：已实施、未提交；后端 `C:/code/company/innolux-iws`、Web 和 PDA 当前工作树保留原有 dirty 内容。来源为 APK 自动识别、多版本及强制更新需求。
- 变化：system 模块增加 APK 解析依赖并复用 endpoint/process、OSS 业务前缀；独立应用包管理页面、版本表与精确公开更新接口。业务发布使用真实 SUPERVISOR/IMMEDIATE 和 BUSINESS 计划，本地 SAME_TX 事件发布版本；未准入 NOT_READY 可按持久回执恢复，UNKNOWN 不换键重放。PDA 登录及应用恢复门禁、下载进度和系统安装桥接上线前需真机验证。
- 数据与配置：`sys_apk_release` 和 5 项菜单内联全量初始化，新增幂等存量升级；空库终验 124 表、230 菜单。用户明确本机 dev 库为 `innolux-iws`，三个服务 JDBC 统一该目标；不改变其他环境标准库名。
- 证据与验证：后端 APK 单测、MySQL 8.0.45/13309 真实平台初始化/发布/幂等/未准入重试；隔离空库、升级首跑/重跑、冲突拒绝通过；Web 类型、构建、逻辑及隔离 API 浏览器检查；PDA 10 项逻辑测试与更新组件浏览器渲染。真实 3306 仅只读，不自动执行升级；真机安装、系统授权及真实 APK 上传联调未验收。
- 规则同步：references/data.md、pda.md；后端 `docs/apk-management.md` 与方案跟踪。归属 project_only，不授予生产数据库写入、部署或推送权限。
