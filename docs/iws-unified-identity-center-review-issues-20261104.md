# IWS 统一身份认证中心缺陷台账与收尾验收

记录起始：2026-11-04；最近更新：2026-11-05。实施会话：`01a1a107-b331-73a3-9300-5299c87176fb`。当前结论：按用户“除非严重缺口，否则交付”的最新决定，已交付本机隔离环境自测；当前未发现需阻止交付的已确认未修严重缺口。已知限制和未覆盖项继续保留。

本台账保存历轮独立评审缺陷、实际修复证据、验证欠账和交付门槛，作为后续收尾的持续记录。代码持续变化，历史测试通过不自动代表最终制品通过；只有核验了对应证据后才更新关闭状态。

“已修”表示该问题已取得所列范围的修复证据；当前版本整体验收仍须满足文末门槛。源码已确定的问题、尚需运行复现的风险、纯验证欠账和用户已延期事项分别记录。原方案的验收项不因实施会话宣称完成而自动关闭。

路径约定：后端为 `C:/code/company/innolux-iws`，Web 为 `C:/code/company/ui/innolux-iws-web`，PDA 为 `C:/code/company/ui/innolux-iws-pda-plus`。下文源文件按所属仓库定位；证据主要位于后端的 `work/iam-config-validation-20261103-v17`、`work/iam-web-runtime-20261103-v18`。这些是本机历史证据位置，不作为另一仓库 README 的契约替代物。

## 已接受的范围

- 不启用子代理；继续指导原实施会话完成实现和隔离验证。
- 沿用原用户、角色、公司、部门树、岗位、菜单及原统一管理账号，内部账号模型不变；原页面复用。
- 一次统一认证，各应用继续校验接入和原生资源权限；同主体、同应用类型只保留一个有效应用会话。
- 多个 IWS 后端复用 Web/PDA，系统清单复用中心 YAML；不同公开前端入口使用各自的 `frontendId`。
- 验证码关闭是基线。所有网页标签统一透明 IWS 图标；PDA 原生安装、桌面、启动和 splash 图标不变。
- 用户已确认暂无设备，先完成其余项目；PDA 真机、原生回调和设备深链验收暂缓。H5 与不依赖设备的代码修复继续。
- 真实数据库只读，不提交、不推送、不部署共享或生产环境，保留已有工作树修改。

## 代码缺陷及安全核验

| 编号 | 问题与依据 | 关闭条件 | 当前证据状态 |
| --- | --- | --- | --- |
| G01 / P1 | 中心门户在本域生成 state 后直接授权，目标 Web/Job/AI/Monitor 尚未建立本地事务；原生 launchUri 还曾指向不会启动 SSO 的控制台根路径 | 先进入登记的目标启动入口，再用已有中心身份授权；从门户真实点击各应用、不同 Web 入口及所选 A/B 均成功，权限和 state 不放宽 | 已修改、编译；最终前端与真实浏览器证据待核 |
| G02 / P1 | 原 authorize 在目标本地准备前替换占位，B 不可达或本地准备失败会误踢 A | authorize 仅准备，目标本地准备完成才提交；B 不可达、本地准备失败时 A 仍是中心当前有效会话 | 已改为 inspect/准备/exchange；增强后的不可达报告已核到 A 占位保留，本地准备失败分支与最终同版仍待核 |
| G03 / P1 | 迟到准备、旧 transaction 重试、提交结果缓存可能恢复旧身份或把票据变成重复登录凭据 | 原 session/generation 与 transaction 幂等；被替换旧事务拒绝；同票据并发仅一份有效结果；缓存绑定 state/commitId/实例，并复核根与当前占位 | 已核增强矩阵：旧事务重试及迟到旧提交均拒绝，直接比对 B 当前占位的 bStillCurrent 为真；新鲜票据错误 state 拒绝后，正确 state 仍可成功。最终制品仍随交付版本核对 |
| G04 / P1 | 占位切换与结果持久化原先分步写 Redis；固定 15 秒锁租期覆盖不了慢 DB，过期持有者可能继续改状态 | 票据消费、占位、结果与必要索引一致提交；提交后响应丢失按同 commitId 恢复；慢校验超过旧租期不破坏互斥 | 已改 Lua 原子提交、watchdog；曾修正项目 CompositeCodec 与脚本编码冲突，故障注入与慢校验的最终证据待核 |
| G05 / P1 | Job/AI 旧 JWT 只验本地签名就请求撤根；根撤销扇出的 GET-check-DEL 还可能误删其他根并发登录的新占位；锁失败不能被当作已退出 | 当前有效应用会话可全局退出；旧代次退出、迟到重试及扇出不能撤新会话；删除须与读到的完整载荷原子匹配；锁等待/中断属于可重试失败 | 已加当前会话条件和队列上下文；复核新增扇出误删后已改 CAS，真实 Redis 回归 1/1、零跳过，覆盖旧 A 读后 B 写入仍保留及正常匹配可删除；新制品重启复核和其余故障证据待盘点 |
| G06 / P1 | Monitor 旧回调异常无条件 invalidate 共享 HttpSession，可销毁新认证；Job/AI 可清掉新 bootstrap；三者还曾先删 state 再比较 | 只消费匹配事务；旧成功/旧失败均不覆盖或清掉后续会话；失败的新准备不销毁原有效 Monitor 身份 | 实施会话已报三模块编译通过；同 cookie 交错、B 后续实际可用的测试待核 |
| G07 / P1 已修 | Job/AI verify 原先只关联原生 JWT 签名与中心声明，未权威绑定实际 native 用户与中心主体 | 两个隔离身份验证 native-admin 签名与普通中心声明组合必须拒绝；映射移除/改绑使不匹配旧 Token 拒绝；保留原账号和密码模型 | 已用中心权威用户名复核当前本地用户 ID；已纠正签名前置假绿，合法映射正向与改绑/移除测试存在。真实 HTTP 伪造管理员签名主体和映射改为缺失目标时 Job/AI 均 401，合法映射均 200 |
| G08 / 身份流边界已核，上游限制待收口 | 原 AI ResponseBodyAdvice 不经过真实 Flux SSE 返回管线，未安装撤权订阅 | 在原生订阅前包装；正常先输出后撤权、中心失联、验证整体超时及机器无人员头对照成立；取消传播按实际层次报告 | 已核 MVC 5/5，包含原生映射查询挂起后在预算内失败关闭；当前真实 ai-full 报告人员 HTTP 流约 4.11 秒终止、之后 401、服务端 gRPC 取消均通过。原生 Agent SDK 尚未把取消传到模型 HTTP 请求，upstreamModelCancellation 为 false；不得把该上游限制与身份门禁通过混写，仍需源码依据与处理结论 |
| G09 / P1 已修 | Job/AI 守卫原把自动 401 当作明确全局退出，仅撤一应用准入可连带撤销其他系统根会话；真实退出按钮曾落到原生密码页，过期 JWT 也不能清理 | 明确退出与自动重认证分开；仅撤 AI 准入不撤 IWS/Job；无效本地凭据可安全清理/重认证且不猜 root 撤销；有效凭据遇中心故障不谎报全局退出；异步清理不影响新会话，连接/响应均有界 | 真实原按钮退出已观测中心撤销和其他旧会话 401；自然过期自动重新 SSO、全局退出请求数为 0、根会话保持 200；仅撤 AI 准入时 AI 401，IWS/Job/根保持 200，自动全局退出请求数为 0。最终制品收口仍随新打包复核 |
| G10 / P1 | PDA 旧 A 响应清掉 B、旧回调销毁新事务或覆写新 Token/菜单/用户；401 弹窗确认及退出响应也有异步拥有权边界 | 请求绑定会话快照；持续事务代次和提交拥有权覆盖每个异步边界；旧 200/401 不污染 B；本次失败只清理自己，不清 UNKNOWN/未决命令 | 复核会话独立运行 callback 与 request 测试 11/11 通过，包含旧回调、401 延迟确认、显式退出默认快照及退出中 B 插入；真实 H5 同入口 A/B、刷新、菜单映射及退出清理已有浏览器证据，原生设备仍按用户决定延后 |
| G11 / P2 阶段复验通过 | 不同入口误用frontendId、PDA hash可能丢失所选后端 | 入口、YAML、公开配置、回调一致；同构建Web及PDA收到A/B后建立正确事务 | 本阶段实际公开19082/web-main、19086/web-branch，除运行时配置外构建文件完全一致；同入口A/B与PDA H5报告已归档。19085本阶段为合法A2 WebSocket副本端口，不再作为旧分支Web入口；后续新窗口按清单重核 |
| G12 / P2 已修 | 独立 complete/error HTML 原未继承 admin/index.html 的 favicon，Monitor 自绘失败页曾遗漏 | 主页面、统一登录、错误/桥接页面引用透明 IWS 图标；PDA 原生图标不变 | 本阶段独立 HTTP 复核 Monitor 无效回调返回401，图标地址返回200/image-x-icon且SHA与原透明ICO一致；主页面及Job/AI历史有效结果保留。PDA本轮静态差异仅index.html，未据此宣称真机图标验收 |
| G13 / P1 已修 | Job/AI 原按原始 requestURI 后缀放行，MVC 忽略 matrix 参数后仍匹配人员接口；关闭本地密码登录又依赖原始路径精确相等 | 使用实际 Servlet/MVC 匹配路径判定，按真实公共资源位置放行；普通路径、matrix 及受支持编码变体均不能重开本地密码入口或绕过中心校验；合法机器认证保持原权限 | 已改框架路径解析及精确公共资源；复核会话独立 HTTP 验证 Job/AI 动态接口 matrix/编码变体均为 IAM 401，Job 原生登录与 AI 登录/注册 matrix 变体均 403；core 报告另有中心已撤销但原生 JWT 尚未过期的拒绝对照。真实机器集成继续按 B01/B02 单列 |
| G14 / P2 | PDA request 同块重复声明 meta，实际模块无法解析；修复 401 竞态时又把自身退出后的空上下文当旧响应，跳过正常登录页导航 | 真实 uni H5 构建；自身退出清 Token 后正常跳转，新 B 建立时拒绝旧退出跳转；正向测试必须模拟真实清 Token/getIamSessionContext 返回 null | 已修重复声明；复核对原正向 fixture 补真实清理行为后复现失败并退回修复。当前真实 LogOut/上下文串联用例已独立通过，11/11 包含相应正反例；H5 报告已有退出请求、Token/context 清理、跳回统一入口和旧 B 401 |
| G15 / P1 已修 | 真实普通 Job 用户仅获 group A，却可直接读取、触发未授权 group B 的任务及读取组详情；原生列表限制未覆盖命令/详情入口 | 复用原生角色和 groupNames；资源 ID 对应真实 namespace/group 校验；覆盖实际可达同源人员入口、matrix/编码路径及批量，保留原管理员和机器权限，不建新 ACL 模型 | 已改为按原控制器方法及明确 DTO/资源类型检查的切面，查询真实 namespace/group，整批前置校验。full 报告覆盖真实 A/B 资源、跨 namespace、批量拒绝且未修改、管理员与列表过滤；原不存在 ID 用例已补真实 executor/retryTask/deadLetter，复核 A 返回实际 data.id/groupName、B 对应操作 403。已核 release 与 target JAR SHA-256 均为 720e3887…，旧 Advice/Interceptor 类均不存在、新切面仅一份；最终运行清单仍须刷新 |
| G16 / P2 已修 | 新 Job 切面在全部方法返回后先读取人员会话，误拦原生公开版本、控制台入口与统计元数据 | 仅对确需结果过滤的原方法读取人员身份，受保护资源规则不放宽 | 已独立 HTTP 复核 system/version、admin、admin/、dashboard/consumer/bucket 全部 200；同时真实 B 资源仍拒绝 |
| G17 / P1 部分复验通过 | 主站 SSE/WS 建连后未见身份持续复核；SSE 心跳/发送只查本地连接，WS 监控只查 isOpen、消息只用握手 LoginUser；HTTP 守卫及在线缓存删除未覆盖已建立连接 | 对照 SSO-08，撤根、同型替换、撤权及中心失联时在规定窗口内断开或拒绝旧连接收发；合法连接保持，新连接不被旧清理误伤 | 已新增共享 Token 校验和独立巡检，使用 LoginHelper 读取主体；初版离线续租、串行阻塞及误踢已完成任务的问题已退回修正。当前 iam-push-sso08-acceptance.json 核实真实 SSE/WS 正常保持、中心退出后踢出/关闭/不能再收发；中心失联、窗口计时及最终同版矩阵仍待核，不整体关闭 |
| G18 / P1 已修 | AI 新增取消登记表 cancel 先 remove，迟到 register 的 computeIfAbsent 又创建未撤销条目，上游调用逃逸本次流撤销 | 流作用域状态不能因映射删除复活；撤销先于登记也必须立即取消；正常流不取消、完成与释放无无界残留 | 先独立 jshell 复现迟到调用取消次数 0；现改为每流持有状态作用域，无全局墓碑表。已独立对当前编译类复验：迟到取消 1/活动 0，正常登记 0、随后取消 1/活动 0，正常完成 0/活动 0，关闭后迟到登记取消 1/活动 0。相应 JUnit 3/3 零跳过；真实流链路仍按 G08 单列 |
| G19 / P2 部分复验通过 | Web push.ts 原把长期应用 Bearer Token 拼入 Authorization 查询参数；配置 websocket 传输时有效凭据进入 URL | 复用现有安全握手或一次性能力，URL 无长期有效凭据；真实浏览器握手、当前身份绑定、同型替换与故障失效通过，HTTP 保持支持；契约写入本仓 README | 已改 30 秒 UUID 票据及 Redis getAndDelete；真实浏览器报告已核 URL 无长期 Token/额外 Authorization 头，重放/错 clientId/过期/撤销拒绝。评审独立前端迟到试验 1/1 通过；Redis 并发核销和最终同版整合待核，不能以默认 SSE 代替可选 WS |
| G20 / P1 阶段复验已修 | 原身份服务及混合WMS查询依赖本地身份表，与一套权威账号及业务分库要求不符 | 原账号组织模型不变；共享/独立业务库只认原权威源，不复制人员组织；真实事务业务查询与后台动作通过 | 已核A/B各78业务表且身份表0、中心新身份和公司精确范围、真实事务库存范围报告及后台动作；新WMS整体验证493/493。09:26阶段清单五JAR散列一致，后续变更按影响补验；未声称本会话MCP查库成功 |
| G21 / P1 阶段复验已修 | 原30天角色/部门范围缓存跨服务不失效 | 预热后中心改范围/部门，再登录查实际业务行必须使用新范围，不手工清缓存 | 两处缓存已移除。已独立读回真实库存范围2/4：A/B自定义源记录精确切到目标记录；父子集合在移出子部门后仅父记录，删除子部门记录不出现；业务/身份fixture清理为0。组织列表旧证据保留，不再代替业务查询 |
| G22 / P1 受控回归通过、真实浏览器待验 | Web 原先先删 state 再比较；迟到 A 响应、Token/context 发布间隙及旧替换 401 可污染新 B，修复中又出现正常完成后不导航 | 错/旧 state 不消耗新事务；换票、发布、上下文、消息和导航属于仍有效的当前尝试；原跨标签锁及 HTTP 后备复用，赢家仍可用 | 已以共享原 transactionId、锁内 Token/context/条件清理修正。独立实际 callback+实际 helper+fake-indexeddb 回归 9/9，执行前后源码 SHA 相同，失败历史保留；仍需最终构建及真实浏览器多标签/HTTP 矩阵，见持续记录 |
| G23 / P2 受控回归已修、最终制品待验 | 中心独立构建使用 src/auth/request.ts，原两处 401 无条件 clearToken；A 请求迟到或携显式旧凭据时可删除 B 的 IAM-Center-Token | 请求绑定发出时实际凭据，响应仅清对应当前身份；旧/无凭据请求保 B，当前 401 仍清 A；复用原 snapshot/CAS | 独立实际拦截器+实际 token 6/6，输入散列未变。永久 HTTP 用例伪绿已纠正为真实 reject，实际 Axios adapter 版独立 5/5；失败历史保留，最终中心制品/浏览器待核 |
| G24 / P1 已修 | 独立identity共享锁曾让中心普通scope4部门移动等待自身另一连接 | 合法移动完成、范围约束保留；按实际连接来源处理，借用连接由Spring管理 | 原50297ms业务500已留档；普通角色移动78ms成功，最新报告包含后代ancestors更新、越界拒绝及原关系读回；493/493含事务复用/不同catalog不借用。包装、库名大小写及借用连接生命周期已按修正实现 |

## 验证欠账

这些是 B 类验证项；未复现代码失败前，不把“未测试”冒充已确认缺陷。

| 编号 | 验证范围 | 必须看到的证据 |
| --- | --- | --- |
| B01 已核 | Job 原生客户端与执行器 | 真实隔离 SDK 注册、实际任务执行、错误 group 凭据 Token authentication failed、执行器掉线/恢复后再执行、人员退出后机器仍执行的报告均已核；修正过把非空响应信封当恢复成功的弱断言 |
| B02 已核主要边界 | AI 原生权限与机器凭据 | 当前真实报告已有模型配置/连通性、普通用户原生管理接口权限拒绝、公开市场语义保留、未订阅禁止分发、会话主体隔离，以及 appToken/人员凭据互换拒绝、人员退出不误伤机器；原生模型 HTTP 取消限制按 G08 单列，最终同版制品仍须收口 |
| B03 | 原页面实际行为 | 从统一门户真实点击进入、复用的账号/角色/部门/菜单页面、普通用户拒绝；Job/AI 点击原退出按钮，不能以手动 removeItem 替代 UI 验收 |
| B04 | PDA H5 | 官方 Vue3/Vite uni-app CLI 可在隔离目录验证；构建与浏览器登录、A/B、菜单、hash 回调、存储、Web/PDA 互替单列，普通 Vite/静态 shell 不替代真实 uni-app |
| B05 | 故障与安全矩阵 | 隔离数据库/Redis/中心故障、通知丢失、重启、提交响应丢失、旧退出和迟到回调；本机自签 HTTPS/跨站/禁第三方 Cookie 可执行部分继续，HTTP 基线保留 |
| B06 | 配置和空库 | 逐服务 JAR/config 子目录、profile、不同 cwd 与 JAR 回退；SQL/账号结构输入未变可复用原空库证据，有变化才补隔离验收；原管理员 ID/凭据/角色/组织不重置 |

PDA 设备相关验收按用户决定暂缓。真实外部模型服务与共享/生产域名、证书或代理缺条件时单列具体外部限制，不能把本机可测项目一并挂起。

## 测试证据纠正

`work/iam-config-validation-20261103-v17/sso29_acceptance.py` 和 `sso29_matrix.py` 初版有弱断言，旧报告不能直接充当最终关闭证据：

- B 不可达后只看 A 的缓存 GET；已增强为确认 B 确实不可达、中心占位仍为 A。
- initialA 未强制非空 Token 与在线信息；已增强前置。
- wrongState 使用已消费票据；已改新鲜票据错误 state 后再用正确 state 成功。
- 迟到 A 失败后原只看 B 的缓存 GET；当前增强矩阵已直接核对 B 占位，旧提交与旧事务重试后的 bStillCurrent 均为真。
- 任意 500 不能都当预期拒绝；应核对场景原因、服务健康和被保留会话。并发 winner 必须实际可用。
- 脚本地址与端口统一头部配置，结果脱敏；测试数不能将不同类型结果累加为一项完整验收。

PDA 交错测试已读取真实 flow、adapter、user store action。测试 harness 的 Map.remove 已提示改为 delete，新增清理路径应实际执行。

证据脱敏须保留结构化结果。当前 AI 报告的 appToken/appTokenAfterPersonLogout 结果对象被按字段名整块置空，无法独立读到 HTTP/业务状态；已要求只脱敏真正秘密值或改用明确结果对象名，再补受影响的机器调用对照。检查布尔值为真不替代被抹去的事实。

G20 已开始按 identity 数据源标记原身份服务，但仍须核对实际连接及业务事务中的路由，不能只看线程数据源名。独立复评又找到 WmsFrozenActionInputReader 的业务任务/用户/部门联查、WmsCompanyRoleMapper 的身份表查询，以及 WmsCompanyAccessPolicy 的公司授权/角色嵌入 SQL。需拆清权威身份事实与业务关联，并保留原实时权限及后台动作语义；中心直接使用 SysUserMapper 的登录/注册策略也必须与账户写入保持同一权威源。

## 自测交付门槛

1. 按2026-11-05用户最新决定，仅已确认未修的严重缺口阻止交付，如身份/权限绕过、跨系统串身份或数据泄露、数据损坏、常规登录/切换不可用。未知边界不冒充已通过，也不因未跑全矩阵自动阻止自测。
2. 已验证结果、非严重限制和后续事项分别列明，不能宣称真机或上游模型HTTP取消已通过；不再要求所有B类扩展矩阵完成才开放自测。
3. 最终源码、JAR/前端构建和正在运行进程对应同一版本；从交付入口实际复验，不混用旧端口、旧构建和旧报告。
4. 提供中心、主/分支 Web、A/B、Job、AI、Monitor、可用 H5 的准确入口、配置位置、启动/停止/恢复方式与原账号取用渠道；不在报告写凭据。
5. 用户步骤覆盖一次登录、选 A/B、界面/菜单、同类互替、跨类型共存、普通权限拒绝、改密/停用/退出、机器调用及 favicon。
6. 测试临时数据按已授权隔离范围清理，保留用户已有工作；根目录既有 `dump.rdb` 未触碰，不纳入提交，进一步处置先确认归属。
7. 方案、任务图、README和实际验证状态保持真实。当前本机隔离环境已开放自测，不等同全部生产验收或未覆盖事项已完成；历史阶段结论由最新交付决议覆盖。

## 历轮问题追溯

以下为原方案 §13.1 已记录的历史证据，保留问题来源；它们不自动覆盖后续重构。

| 原编号 | 缺陷及触发 | 历史结果与当前关联 |
| --- | --- | --- |
| R1 / P1 | 缓存成功读身份被用于写入准入，中心停止后仍能接受写入 | 已真实验证中心停机时 POST 拒绝且未落库，读租约超时拒绝。G17 初版又出现离线兜底续租，必须按当前实现重新验证，不能沿用历史通过结论 |
| R2 / P1 | 监控机器认证链与人员链混用，人员撤权后仍能访问实例管理 | 历史人员 Cookie/CSRF 撤权后 401，机器 Basic 保持 200；证据为 review-repair-acceptance.json、pending-iwa-acceptance.json。最终故障矩阵继续核对人员/机器分离 |
| R3 / P1 | 授权指纹只覆盖菜单，角色数据范围、自定义部门及部门树变化未及时使旧会话失效 | 已扩展共享指纹并验证旧 Token 拒绝。G21 另查重新登录后的数据范围缓存，旧会话失效不能替代新会话真实数据权限正确 |
| R4 / P1 | 中心撤销失败仍向用户承诺全局退出成功 | 已改明确本地退出、中心未确认、待重试状态，并验证恢复后撤销；证据为 review-logout-pending-acceptance.json。旧队列不误撤新会话仍按 G05/B05 补证 |
| R5 / P1 | state/returnPath 校验太迟、失败登录误踢原会话及激活回滚不完整 | 历史非法参数保留 A 已验；后续已更换 prepare/inspect/exchange 原子提交设计，当前 G01—G06 继续按新流程核验，不复用旧回滚用例冒充现方案通过 |
| N1 / P1 | 监控重试完成用旧文件快照覆盖并发新增的待撤销项 | 已改完成时重读并只删除确认成功项，相关队列测试已验；最终重启/并发恢复按 B05 收口 |
| N2 / P1 | 监控队列文件读取/写入/替换失败仍返回 pending=true，造成假恢复承诺 | 已显式返回 persistenceFailed 并区分退出提示；持久化失败不能归类为已可靠入队 |
| N3 / P1 | 只撤某应用接入权限却全局踢出所有类型会话 | 已将应用准入与共享权限指纹分离，真实单应用撤权后其他类型保持；G09 又覆盖原生页面自动 401 不调用全局退出 |

## 当前重点缺陷的复现和修复边界

### G17：存量推送连接的身份失效

触发条件：先建立 SSE/WS，此后不再发送新的业务 HTTP 请求，再撤根、同类型切换 A→B、撤销准入或停止中心。原实现只在建连时验证，SSE 心跳只发消息，WS 只检查连接是否打开。默认 SSE 超时为一天，心跳间隔为 60 秒，不能作为设计最长 30 秒失效的保证。

影响：旧身份可能继续收发消息；不能以“下一次 HTTP 已返回 401”证明已有连接失效。初版修复还出现中心离线时刷新 iamRootValidatedAt、单连接阻塞拖延整批、总预算耗尽误踢已经完成校验的合法连接等问题，均已退回修订。真实联调另发现框架登录标识为 sys_user:<id>，不能在新校验服务中当纯数字解析。

当前处理：复用原身份事实抽取按 Token 的校验服务；推送定期在线复核，HTTP 读保留有界租约；只有中心真实验证成功可刷新证据时间。校验任务相互隔离，清理失败不能阻断其他连接。必须从刚完成中心成功验证的真实连接开始测故障，并核对实际断开时间、合法连接对照及旧清理不影响新连接。

主要源码：iws-common-push 的 SseEmitterSessionManager、WebSocketSessionManager、PlusWebSocketHandler；iws-admin 的 IamSessionValidationService、IamPushSessionGuard。

### G19：WebSocket URL 中的长期凭据

触发条件：Web 选择 websocket 消息传输，旧 buildWsUrl 把长期 Authorization Bearer 放在查询参数。默认 SSE 已使用 header，不能替代该可选传输的验证。

影响：长期应用凭据进入 URL，与原方案安全约束冲突。当前改为服务端保存长期 Token、浏览器携带 30 秒一次性票据；已见 Redis getAndDelete 原子消费和 UUID.randomUUID 安全随机源，仍需运行证据。

关闭条件：实际浏览器通过前端原路径握手成功，URL 不含长期 Token；票据重放、并发消费、过期、错 clientid、签票后撤权或同型替换均拒绝。旧绑定的出票响应不得为新绑定建立错误连接。浏览器测试不能用自动注入 Authorization 的额外 HTTP 头替代普通浏览器能力；完整握手契约写入各自仓库 README。

主要源码：Web src/utils/push.ts；iws-common-push 的 PushTicketService、PushTicketController、PlusWebSocketInterceptor；iws-admin 的 IamPushConnectionAuthorizer。

### G20：独立业务库与唯一权威账号源

需求依据：原方案 §12 对多 IWS 的补充已明确，业务服务和业务库可独立部署，账号权威只有一套，不得回退到各业务库账号表或复制组织人员数据。现有 A/B 同库测试不证明这项实现。

已确认的源码缺口：IWS 回调和共享校验原本读取默认 master 的用户/角色/部门；WmsFrozenActionInputReader 把业务任务与 sys_user/sys_dept 联查；WmsCompanyRoleMapper 直接读身份表；WmsCompanyAccessPolicy 将公司授权业务表与身份角色表嵌入同一 SQL。仅给部分身份服务增加 @DS 不能自动解决这些混合查询，也不证明外层业务事务已持有连接时仍路由正确。

影响：独立业务库缺少身份表时登录、公司范围或后台动作失败；若保留旧身份数据，则可能使用第二套人员/角色事实。中心创建/改密写 identity、验密策略直接读 master 的配置若允许分离，还会产生身份来源分裂。

关闭条件：保持原模型，明确原权威账号库；同库模式显式配置，独立模式缺配置失败关闭；真实中心 API 创建专属用户、改角色/部门和重置该用户密码后，IWS 只认权威结果。原管理员凭据不改。业务库无身份表或故意放置冲突身份数据时，登录、实际公司范围、业务查询及后台动作仍正确；先执行业务 SQL 建立外层事务再读取身份事实，验证实际连接和业务回滚归属。保留实时权限检查语义，不能通过复制表、忽略数据权限或旧 LoginUser 缓存通过测试。

### G21：跨服务的数据权限缓存

触发条件：A 先缓存角色自定义部门或部门子树，中心再修改范围/部门树，用户重新登录。原 Cacheable 仍按 roleId/deptId 取 30 天缓存；中心与 A 使用不同 Redisson keyPrefix，中心 CacheEvict 不会自然清掉 A 的同名缓存。

影响：旧 Root 被授权指纹注销后，新 Root 仍可能使用旧数据范围，继续看到已撤部门的数据；该风险与普通只读租约不同。须检查所有实际参与授权的身份派生缓存，而非只验证菜单或旧 Token。

关闭条件：不手动清 A 缓存，预热→中心修改→重新登录→查询真实记录，各节点均使用新范围。选择关键权限事实不缓存或现有权威版本/可靠失效机制，避免另建缓存平台。本轮通过移除两处缓存并补强精确集合矩阵获得行为证据，见下方持续收尾记录；最终同版整合尚待完成。

## AI 原生 SDK 的取消边界

当前真实报告分别记录：personStreamRevocation=true、serverGrpcCancellation=true、upstreamModelCancellation=false。人员 HTTP 流已结束，后续人员请求拒绝；服务端 gRPC 已取消；原生 Agent SDK 尚未将取消继续传到模型 HTTP 请求。三层结果不得合并成一个“AI 流全部通过”。

已读取本轮所用 SDK 的 ChatDispatchStreamingHandler 和 ClientChatExecutor：分发处理器将回调交给运行时，executeStream 订阅模型流但未保留可用于后续取消的 Disposable。该信息用于解释观察边界，不等于已交付上游 SDK 修复。应记录确切 SDK/制品版本、实际资源影响及本轮处理结论；不得为把布尔值改绿而替换原生 SDK、伪造取消或扩写新客户端协议。人员身份门禁与上游资源回收分别验收。

## 收尾任务与依赖

### 本轮新增证据核对

评审方已读取后端 `work/iam-config-validation-20261103-v17/` 下列报告；这些是实施方执行产物的独立核读，不表述为评审方重新运行全部测试，也不替代最终整合版本复验。

后续源码复核：`JdbcIamIdentityFactReader` 已采用物理 identity 连接读取 WMS 身份事实，并将锁持有至业务事务结束；`SysDataScopeServiceImpl` 已移除自定义部门和部门子树的两处 Redis 缓存。源码变化尚不能替代 G20/G21 的业务运行验收，尤其是其他 MyBatis 身份查询在已绑定业务事务中的实际连接、重新登录后的真实数据范围，以及缺失 identity 配置时真实动态数据源的失败行为。自管 ThreadLocal 的事务挂起/恢复只按当前真实调用路径核对，不据未证实的调用另列缺陷或扩展设计。

- `iam-identity-routing-acceptance.json` 与对应 Python 脚本：中心 API 创建、改密和删除命中 identity，业务库冲突用户未改变，旧密码拒绝、新密码成功，清理通过。这关闭 G20 的中心账号生命周期子项；未覆盖业务 A/B、已有外层事务、混合公司范围查询及 FrozenAction 后台动作，G20 继续修复验收。
- `iam-push-sso08-acceptance.json`：合法 SSE/WS 保持连接，根退出后两类连接关闭，旧连接不再心跳/PONG。中心停止、校验预算和真实关闭耗时另取故障证据，G17 不据此整体关闭。
- `iam-ws-ticket-browser-acceptance.json`：报告记录普通浏览器握手成功，无额外 Authorization 请求头，URL 无长期 Token，重放、错 clientId、旧 Bearer 查询、过期票据及发票后撤权拒绝。原 Web 客户端迟到响应绑定与并发一次核销仍按 G19 关闭条件逐项核对；最终同版制品未确定前不标记整体完成。

| 任务 | 覆盖问题与交付 | 状态 | 真实前置与验收 |
| --- | --- | --- | --- |
| IAM-CLOSE-01 | G17/G19 推送校验、故障预算、一次性握手及原客户端适配 | in_progress | 无新增业务前置；共享库真实 SSE/WS、普通浏览器、重放/错绑定/撤权/停中心及合法对照。涉及后端推送、主站守卫、Web 与本仓 README |
| IAM-CLOSE-02 | G20/G21/G24 原权威身份路由、业务混合查询/事务及权限缓存 | 自测交付范围已完成 | 真实分库库存、中心普通部门移动、后代ancestors及越界拒绝已通过，原账号模型不变；后续源码变化按影响重验，不复用失效输入 |
| IAM-CLOSE-03 | Job/AI 原生权限和机器证据、SDK 取消边界、报告脱敏 | review | Job 主要真实链路已核；AI 人员/gRPC 与模型 HTTP 分层；补保留 HTTP/业务状态的机器前后对照，实际限制写清。历史通过绑定原输入，最终代码变化按影响补验 |
| IAM-CLOSE-04 | G02/G04/G05/G06/G22/G23/B05 故障证据，以及同一Web入口A→B、HTTPS/跨站矩阵 | 已交付，极端矩阵后续 | 双标签旧401、本机HTTPS/跨站/禁第三方Cookie、非安全HTTP IndexedDB及MySQL恢复、Monitor CSRF/队列跨重启已有证据；生产和极端未覆盖项保留，不冒充全过，按用户决定不单独阻止自测 |
| IAM-CLOSE-05 | 最终源码/制品/进程、初始化与配置输入、方案/README、自测入口和恢复说明 | 自测交付完成 | 11:41清单五JAR散列再次核对一致，四个公开前端页面独立读取200；稳定烟测通过、TLS测试代理停止，原账号保留，精确入口及现有恢复方式写入自测文档；非完整生产验收 |
| PDA-DEVICE | APK、原生回调/深链和实际设备行为 | 用户明确暂缓 | 用户目前暂无设备；不阻塞其他独立项，不能把 H5 或源码测试写成真机通过 |

01/02 共用代码、端口和数据库，测试需串行协调；这属于调度约束，不伪造业务依赖。台账后续新增问题延续 G 编号，保留已关闭问题的触发、修复证据和适用版本；状态变化按实际证据维护，不删除失败历史。

## 持续收尾记录

### 2026-11-04：接续跟进至自测交付

- 用户要求继续指导原实施会话、持续持久化缺陷直至收尾并交付自测；继续禁止子代理。
- 复核基线为当前 V1.17、上述 G01—G21/B01—B06 与 IAM-CLOSE-01—05；历史 R/N 已有后续证据，不按旧报告机械重新打开。
- 实施会话已恢复中心、A1/A2/B、Job、AI、Monitor 七个服务，正在补 G20/G21、真实写入故障与监控改密传播；“端口已监听”不计作对应行为通过。
- 评审会话维护本台账及用户自测说明，实施会话负责源码、隔离证据、方案与各仓自包含 README；避免同时改同一台账造成覆盖。
- 交付前须核对同版源码/制品/进程，停止故障注入后保留稳定自测窗口。待完成证据不得写成通过；实际外部或设备限制明确列入自测边界。
- 验证码关闭、PDA 原生图标不变、真实库只读及不提交推送/共享部署等现有边界保持。

本轮独立复核：G21 新增脚本 `iam_g21_scope_business_acceptance.py` 的初版对用户列表采用 `all(...)`，空集合仍可通过；B 前后只断言业务码，尚无精确可见/禁见集合。其角色固定 `dataScope=2`，仅覆盖自定义部门路径，未覆盖另一处 `getDeptAndChild` 缓存。已通知实施会话分别补源/目标 fixture 的实际 ID 对照、A/B 非空精确集合、独立的部门树父子关系热缓存用例。公司 API 的 `SCHEMA_STATE_UNSUPPORTED` 属业务门禁，保留失败证据并允许在隔离库预置后用真实 API 查询；不能放宽门禁，亦不能以该同库 fixture 代替 G20 的独立库/外层事务证据。G21 继续保持未关闭。

### 2026-11-04 22:00：独立票据迟到试验

- 对实际 `src/utils/push.ts` 运行临时 Vitest 试验：A 取票挂起，切入 B 并先接收 B 票据，随后返回 A 票据。断言 A 不创建连接、不覆盖 B URL，旧绑定 `closePush` 返回 false、不关闭 B，旧断线回调不追加请求；当前 B 绑定仍可正常关闭连接。1/1 通过，退出码 0，临时测试文件已移除，未改变业务源码或运行服务。
- 受测源码 SHA-256：`6B404CF2BE4F1F6F826DEA3424A6C04094D4887A38D332BDF476977FBDCD5E1F`。命令：`pnpm exec vitest run src/utils/__tests__/push-ws-closeout-review.spec.ts --reporter=verbose`。这是纯前端异步行为证据，不替代浏览器真实握手与 Redis 并发核销；G19 仍待后两者与最终同版整合核对。
- 已读回 G20 `WmsIdentitySplitIntegrationTest` 现有 Surefire 结果：5/5、无失败/错误/跳过。测试实际分离身份和业务 schema，包含外层业务事务后的撤权、跨连接共享锁、公司范围 SQL 及冻结操作者；其中路由对象和部分 mapper 为 Mock，故只计组件/真实数据库边界证据，不等同真实 A/B 完整服务验收。

### 2026-11-04：分库与权限集合补证

- 已独立读回 `iam-g20-split-runtime.json`、`iam-g20-split-acceptance.json` 及脚本断言：实际 A/B 分别使用 `iws_iam_g20_business_a/b`，各 78 表，无账号组织身份表；中心新增同一普通身份可进入两端，A/B 公司代码精确为各自 fixture，B 成功后旧 A 为 401。不能再将“仅同库”写作当前全部证据，但后台动作和最终制品等未完成项继续保留。
- 已独立读回 `iam-g21-scope-matrix-acceptance.json` 及脚本 276—422 行：自定义范围由源部门改为目标部门，精确断言源用户消失/目标用户出现；部门树场景先含父/子用户，中心移走子部门后两端只含父用户。两个场景均先经 A/B 真实 API 预热，再修改、重新登录；脚本无 flush。新结果替代初版弱断言作为 G21 行为关闭依据，历史初版及退回原因保留。
- 用户/角色/部门清理计数在矩阵报告中留存；最终收尾仍需读回真实清理结果与同版运行清单。两份新报告与当前源码仍需在 CLOSE-05 绑定版本，不能以 `passed=true` 单字段越过剩余门槛。

### 2026-11-04：G12 Monitor 登录失败页遗漏

独立请求当前 Monitor 的 `/admin/sso/callback`，传无效票据/state 且不带 Cookie，返回 401“统一身份回调缺少浏览器会话”。该可停留 HTML 的 `IamMonitorSsoController.error()` 未声明 favicon。默认 `/favicon.ico` 与 `/admin/favicon.ico` 都返回 200 `text/html`、111032 字节，不能算图标成功；现有正确图像 `/admin/assets/img/iws-auth-icon-transparent-20261104.ico` 返回 200 `image/x-icon`、50312 字节，SHA-256 为 `ece0d437427cd0a97ba6f8a03774f20cca4529c2cd7b77ad453180ab3d401089`。

证据：后端 `work/iam-acceptance-review-20261104-closeout/monitor-favicon-check.json`。已交实施复用现有透明图标给 Monitor 自绘错误/完成/退出 HTML 补声明，并在最终制品按实际 HTML 链接、类型和散列复验。G12 恢复部分修复状态；Job/AI 已通过的四页结果保留，不新增平行缺陷或图标体系。

### 2026-11-04：事务/后台动作复验及停机写对照补强

- 已读回 G20 新 Surefire：身份分库 7/7，后台动作 4/4，无失败/错误/跳过。新增缺 identity 的 strict 路由拒绝、业务回滚后的线程/连接收口及实际分库 worker。后台拒绝断言由旧阶段改为准确的实时菜单权限或公司范围异常，并保留目标业务记录数为零；未放宽生产鉴权，不把默认 profile 的 0 tests 算通过。
- B05 当前停中心报告证明真实 HTTP 写方法返回 503、恢复后读请求 200；但脚本只向供应商新增提交 commandKey，未证明同 payload 在线可合法写入。已要求补在线成功→停机拒绝且无变化→恢复成功并清理的有效写入对照，不绕开 WMS 结构门禁。此为验收证据欠账，尚无据另列业务缺陷。
- 现场存在多个历史 PID 清单。故障注入及最终停止/恢复指令须核对当前监听进程与制品路径；历史 PID 文件不直接作为杀进程依据。最终保留稳定自测窗口，不能把故障脚本直接作为用户启动流程。

### 2026-11-04：G20/G21 事务内数据权限路径仍待补证

进一步源码定位发现，`WmsInventoryReadServiceImpl.queryMaterialSummaries` 等方法实际存在 REPEATABLE_READ 业务事务；其 Mapper 标记 `@DataPermission`，CUSTOM/DEPT_AND_CHILD 会通过 SpEL 调用 `sdss.getRoleCustom/getDeptAndChild`。当前 sdss 虽有 `@DS("identity")`，仍使用 MyBatis Mapper；现有物理身份读取器未覆盖这两种范围读取。业务事务已绑定连接时，动态路由上下文是否能改变实际连接，不能由注解名称推定。

已要求在现有无身份表 A/B 拓扑，以非管理员范围 2/4 实际调用 `/wms/inventory/read/materials/summary/page` 并设置可辨识业务数据，记录实际响应/连接或 SQL 失败证据；若错取业务库，复用现有权威身份读取器修正，不能复制身份表或改为全数据角色。修改后对真实业务路径补范围/部门树变更的精确集合对照。现有组织列表矩阵、公司范围及 7+4 组件通过结果保留，但不能覆盖该明确调用路径；G20/G21 与 CLOSE-02 整体仍未关闭。此时记录为已定位的高风险待验证路径，尚未把源码推断写成运行复现。

22:21 独立下层探针已确认绑定语义：使用本轮 WMS Surefire classpath 中真实的 DynamicRoutingDataSource 与 DataSourceTransactionManager，物理 Connection 只做 Mock 计数。readOnly 业务事务开启后切换 identity 上下文，DataSourceUtils 仍复用 business Connection，identity 物理取连接次数为 0、master 为 1；编译/运行退出码均 0。证据为后端 `work/iam-acceptance-review-20261104-closeout/identity-routing-probe-result.json`。这是本轮实际依赖库的事务机制证据，未查询真实库，也未冒充库存 HTTP 故障已复现；真实业务路径仍由实施按上文补证。临时 Java/编译产物清理，仅保留结果。

### 2026-11-04：推送故障与票据并发补证

- 已读回 `iam-push-center-outage-v2-acceptance.json`：真实 SSE/WS 建连并收发成功，停止中心后 15.84 秒均踢出/关闭，小于 30 秒，最终恢复中心并 ready。结合此前正常连接保持及撤根报告，接受对应行为证据；源/制品与其他故障路径仍按最终版本绑定。
- 已读回 `iam-ws-ticket-browser-v2-acceptance.json` 及生成脚本：实际浏览器同票两个并发握手，opened=1、rejected=1；重放、错渠道、过期和签票后撤销再握手均拒绝。结合独立前端迟到试验，G19 关键行为已有证据。
- 报告质量退回：脚本把 `issuedAfterRevocation=true` 写为常量，但实际在撤销前签票。该字段不参与核心 passed，不能据此宣称撤销后签票已验证或认为生产允许撤销后签票。已要求改成实际含义/计算值，或补实际请求状态；此项不抹掉已经成立的握手、并发与撤销拒绝证据。

### 2026-11-04：G20 范围读取迁移的逻辑删除规则

实施已把 sdss 的两个范围读取迁到现有物理 identity 接缝。读回初版发现 `deptAndChildIdsForShare` 的 raw SQL 只按 dept_id/ancestors 取部门，遗漏原 `SysDept.delFlag` 的 `@TableLogic` 过滤；原 Mapper lambda 会自动过滤已逻辑删除的子部门。该差异可能把已删除子部门的历史业务行重新纳入父部门范围，属于新增迁移中的授权规则缺口，不能因连接正确就关闭。

已要求保留 `del_flag='0'` 与正确 AND/OR 括号，并补“已删除子部门仍有祖先链/历史业务记录”的禁见对照；原规则没有过滤 status，不顺带扩展为新的停用部门策略。此项按 G20 原权限行为保持继续跟踪，最终源码与业务矩阵验收后再关闭；未将源码证据冒充实际数据泄露已复现。

文档收尾另需纠正：`script/config/iam/README.md` 末段当前要求独立 IWS 使用不同“回调”，但同一 Web/PDA 接多个 IWS 可复用同一受信回调；不同的是 applicationId、目录/协议凭据及会话绑定。该说明应对齐自包含 README 的现有契约，不增加每后端一套前端要求。

后续读回：SQL 已补 `(dept_id = ? or find_in_set(?, ancestors)) and del_flag = '0'`；新 8/8 组件测试包含已删除子部门祖先链并精确排除它，后台 4/4 仍通过。真实业务矩阵脚本已纳入 deleted-child 业务记录和清理读回，但初版 customBefore 段先建 A 再建 B 才读 A，会正确触发旧 A 401；已要求改为每端登录后立即读取各自正向集合、单列旧 A 拒绝，保持生产互踢规则。该脚本缺陷不误报为生产认证失败。

### 2026-11-04：存量权限回归的装配缺口

本轮公司授权事实接缝变动会影响原范围守卫，已要求复用 `WmsCompanyAccessPolicyTest` 与 `WmsRuleScopeGuardIntegrationTest` 的相关存量用例，避免只靠新增测试自证。首轮范围守卫 13 项在 Spring 上下文阶段因未注册新增 IamIdentityFacts 失败，尚未进入业务断言；实施正在以真实 JDBC identity 读取器补测试装配并重跑，不以放行 Mock 或跳过替代。该失败按其真实层次保留，不能把“编译通过”写为这些旧行为已回归通过。

后续实施反馈 2+13 已通过，并开始全仓 JDK 21 clean package。评审读取时旧 target/surefire 已被 clean 清除；已要求归档该原运行的命令、时间、计数和 stdout/XML，以可复查证据收口，不以打包后默认 profile 的新报告替代原 local 结果。此前独立读回的身份 8/8、后台 4/4 结果保留其各自阶段与输入。Monitor 错误页源码已补 favicon 声明，WS 报告字段已改为实际的 ticketIssuedBeforeRevocation，均待新制品或新报告最终读回。

后续完整构建又暴露分配/任务等五组历史测试上下文未注册同一接缝，需修复真实装配并复验，不能只消除 Spring 缓存上下文错误。此外独立读回公司服务报告为 27 项中 1 失败：`revokedAfterCommitDoesNotLeakManagementScope`，实际是撤权在 30 秒内未完成。其故障注入器在物理业务提交后、Spring afterCompletion 前同步等待权威撤权，而新 identity 共享锁在 afterCompletion 释放，形成屏障互等。

已要求保留 COMMIT_THEN_THROW 的物理提交/ACK 丢失注入，校正外部撤权同步点：身份锁释放后、恢复结果与管理范围再次读取前，撤权确实提交；仍断言 NotPermission 及已提交 scopeRevision/roleIds。不以接受超时、移除断言或跳过制造通过。如生产有同类同步 afterCommit 权威写入，应按真实路径另处理；当前仅确认该测试注入器顺序，不把它直接当作生产死锁已复现。

中断恢复后独立读回定向报告为 1/1、无失败/错误/跳过。当前注入器仍先 super.doCommit，再抛原 COMMIT_THEN_THROW 异常；只将并发撤权动作安排到身份锁释放后的 afterCompletion，原 NotPermission 及已提交范围断言保留。此结论仅指该定向用例，不覆盖原 27 项公司服务整体；全量 WMS local 正在执行，尚未按旧的混合报告宣称全绿。

### 2026-11-04 23:00：G22 Web 迟到登录发布

直接源码定位发现 `consumeIamState` 初版先删除再比较 state，Web `sso/callback.vue` 换票成功后无条件 setToken/目标 context。中心只能阻止尚未提交的旧票据，不能撤回已提交但仍在网络中的旧响应；因此后端旧事务拒绝证据不能代替前端发布保护。

通知后实施已先修复 state 比较顺序、卸载标记、捕获 AuthStateSnapshot 及原子 Token 替换。独立临时 Vitest 执行实际 `.vue` scriptSetup（仅转译环境 clientId、桩化 IO/生命周期），并设置合法当前回调对照；修复中的受测 callback SHA-256 为 `d8a8eb699ce2c0974487f9797e4808979c6f72c36a66d1e57e3dc87f8a76e4ac`。正式有效试验 4 项 3 过 1 失败：错 state 保留 B、fetch 期间卸载/被 B 替换、合法 A 发布与导航通过；在原子 Token 操作完成与回调继续执行之间 B 发布后，A 仍执行 setIamSessionContext，出现 `B-valid` Token 配 `iws-a` context。

结果保存在后端 `work/iam-acceptance-review-20261104-closeout/web-sso-g22-result.json`，退出码 1。此前 VM 环境 import.meta/模块互操作装配错误不算业务失败；纠正装配且正向对照成立后才记录上述结论。已要求目标 context 也在原跨标签身份锁内比较绑定后写入，并修正 getter 绑定不符时无条件 removeItem 可能擦掉新 context 的问题；所有相关异步调用、错误清理和导航须复验。临时试验仍用于本轮复验，完成后移除，不修改业务源文件。

后续源码已改 context 为 async+原身份锁内比较绑定，getter 不再无条件删除。原临时 fixture 已完成失败取证并移除，避免新接缝变化后把旧 IO 桩的不适配误报为业务失败；失败 JSON 保留。新清理路径仍待补：HTTP 401 error 分支在 await 解析消息前缓存 authState，之后不能拿该旧结果清当前身份；应传原请求绑定条件清理。另需双标签“两端响应都挂起，B 已中心提交、先放 A 再放 B”的当前尝试验证，不能仅靠 Token 已变化或单页卸载标记。不再新增身份 epoch，复用原事务标识与跨标签身份能力。

进一步读回：共享当前尝试标识已复用 transactionId，Token/context 的条件在原锁内检查；请求开始状态已贯穿替换 401 清理。中间版本的 handleSessionReplaced 在持锁同步 action 内又调用带锁 async clearToken 且不等待，形成先返回清理成功、Token 另行排队的缺口；不能用补 await 造成非重入嵌套锁死。已要求复用一次锁下的同步 Token 清理内核，派生 context 同步收口；新 B 已发布时旧事件、提示与导航均不应继续。这些改动仍在实施中，未记为通过。

G22 最终矩阵还需包含新 B 尚未发布时的旧 A 清理：B 捕获 A 的本地状态、中心已提交 B 而响应挂起，A 轮询收到正确的替换 401 并清理 A，然后放 B 成功响应。B 仍为当前尝试，应能建立；不能把旧身份的正常失效当作“被更新登录取代”。该用例与“B 已发布后旧 A 401 不得清 B”分别记录，不以仅有负向拒绝替代当前赢家可用性。

### 2026-11-04：中断恢复与 G22 回调契约核对

用户明确继续后重新核对现场，实施会话已进入新一轮执行；此前中断不代表构建、测试或清理成功。只读检查未见既有验收端口监听，尚无稳定自测窗口，后续由实施统一恢复并刷新清单，评审不并发重启。

G22 中间版本新增 Web `route.query.transactionId` 必填检查，但当前中心 `IamTicketService.buildRedirectUri` 只输出 ticket/state/returnPath/applicationId/frontendId/terminal。真实回调缺参会直接失败；已要求优先复用已匹配 state 的本地 transaction.transactionId 校验当前尝试，或完整更新实际中心协议与测试，不能靠 fixture 人工补 query 通过。

另已读回新 Token helper 允许旧身份被清理后当前尝试继续，但 Web await exchange 后仍有提前 `!isAuthStateCurrent(authState)` 返回，会阻断该恢复分支；同时当前尝试 guard 需覆盖锁等待中的组件卸载，以及 await 清尝试后的旧导航。以上仍属 G22 未关闭项，待真实回调正反矩阵与新制品验证。

### 2026-11-04 23:36：G22 实际 helper 独立回归

中断后源码已修正 query 契约、旧身份被清空后的发布入口，以及卸载 guard。新增独立临时 Vitest 使用实际 callback script、实际 auth/sso/iamSessionState 与 fake-indexeddb 仲裁，不桩化身份、CAS 或上下文 helper；仅控制网络、用户信息 IO 和 Vue 生命周期，中心回调输入不手造 transactionId query。

7 项结果为 3 过 4 失败：错误 state 不消耗新事务、等待身份锁期间卸载不发布、清理等待中新 C 启动不导航通过；合法 A、两种 A/B 响应顺序和旧 A 正常清理后的 B，均已成功写入正确 Token/context，却不能导航。读回原因是删除当前尝试 marker 后，又要求同一 marker 仍为 current，必然拦截合法完成。已交实施修正“完成后没有更新尝试且绑定仍当前”的判定，保留正向导航断言，不能通过删断言/手造 marker 通过。

失败结果：后端 `work/iam-acceptance-review-20261104-closeout/web-sso-g22-real-helpers-result.json`，退出码 1；临时测试 `g22-closeout-independent.spec.ts` 供本轮修复后复验，完成即移除。这是实际回调和身份 helper 的受控 IO 测试，不冒充真实浏览器多标签或生产环境验收。

### 2026-11-04 23:39：G22 独立复验 9/9

修正完成后，原中心回调参数直接成功，完成判定不再要求已删除 marker 仍存在。使用实际回调脚本与实际身份/helper，IndexedDB 作为仲裁且无 Web Locks，9/9、退出码 0：合法当前回调、错 state 保留新事务、双流程 A-first/B-first 均由 B 获胜、旧 A 在 B 响应挂起时正常清理而 B 仍成功、等待身份锁时卸载不发布、A Token 已换但 context 锁等待时 B 获胜且目标不被旧 A 覆盖、已发布 B 后旧 A 替换 401 不清身份/context/不派事件、清尝试等待期间新 C 启动则旧 B 不导航。

前后五份输入 SHA-256 一致。通过报告为后端 `work/iam-acceptance-review-20261104-closeout/web-sso-g22-real-helpers-retest-1.json`；来源散列与范围为同目录 `web-sso-g22-retest-inputs.json`。临时测试已移除，仅保留脱敏证据；此前失败 JSON 不覆盖。受控 IO/IndexedDB 测试不等同真实浏览器多标签或 HTTP 非安全上下文，G22 在最终制品浏览器矩阵完成前仍不整体关闭。

### 2026-11-04 23:48：G23 认证中心迟到 401 已独立复现

业务 Web 的修复不能替代认证中心独立构建。中心 `src/auth/request.ts` 在业务 code=401 和 HTTP 401 两处分支无条件 clearToken，没有绑定发出请求的身份。实际拦截器和实际 `src/auth/token.ts` 独立回归（仅桩网络和界面）：发出 A 请求、发布 B、再交付 A 401，两个分支均把 B Token 删除，期望保 B 失败；当前 A 收到自己的 401 正例仍可清 A，故不是简单禁用清理即可修正。3 项 1 过 2 失败、退出码 1，报告为后端 `work/iam-acceptance-review-20261104-closeout/center-late-401-result.json`。已要求复用原中心 snapshot/CAS 条件清理，不新增锁平台或身份 epoch；临时独立断言 `center-401-closeout-independent.spec.ts` 待修正后复验、再移除。

同期独立读回 WMS 公司服务完整报告已为 27/27、无失败/错误/跳过，包含原提交后撤权失败用例；它补充前述 1/1 定向结果。其余 WMS 全量仍执行中，不汇总混合版本 XML 冒充最终结果。

### 2026-11-04 23:53：WMS 全量通过，G23 首修仍有缺口

实施侧完整 WMS local 结束后，独立汇总 42 份 Surefire XML，491/491、失败 0、错误 0、跳过 0。报告名、内容散列和计数已另存后端 `work/iam-acceptance-review-20261104-closeout/wms-full-local-report-readback.json`，不带 systemProperties 或敏感输出；最终部署制品绑定由后续清单确认。该完整结果替代前述执行中状态。

G23 首修已用 WeakMap 记录请求 snapshot，并复用 CAS，原来的两种迟到 401 已保护 B。但实际请求若携显式旧 A Token，进入拦截器时中心当前身份已经是 B，直接 captureAuthState 会错把请求关联到 B；tokenless 登录请求也不应绑定 B。新增这些实际请求形态后 6 项仅 3 过、3 失败，报告为同目录 `center-late-401-retest-1.json`。已要求仅实际发送 Token 与 snapshot 相符时绑定请求；无绑定时不回退读取当前身份来清理。当前身份收到自身 401 的清理正例保留，不能将所有 401 忽略作为修复。

### 2026-11-04 23:58：G23 独立复验 6/6，永久 HTTP 测试退回

修复后独立 6 项全过、退出码 0，执行前后 request/token/测试三份输入散列相同，来源为后端 `work/iam-acceptance-review-20261104-closeout/center-late-401-retest-inputs.json`，报告为同目录 `center-late-401-retest-2.json`。覆盖两类旧响应、当前 401 正常清理、两类显式旧凭据和无凭据登录请求；临时测试已移除，历史失败不覆盖。

独立读回新增永久 `src/auth/__tests__/center-late-401.spec.ts`：其中 HTTP 401 用例调用 Promise 的 resolve（finish）传入 AxiosError，并非 reject，因此不会进入 Axios 错误拦截器；只检查 B 未被清理也不能证明该错误分支已执行。已退回使用真实拒绝回调，并断言 HTTP 401 与 isHandled，再保留 B。它属于测试证据缺口，不能以该伪 HTTP 成功关闭验收；不影响上述实际错误拦截器独立 6/6 的范围结论。

### 2026-11-05 00:03：G23 永久测试真实错误分支已核

永久 HTTP 用例已改真实 reject(AxiosError)，新增 error.response.status=401 与 error.isHandled=true 断言，再检查 B 保留。独立运行实际 Axios（只替换 adapter、界面与存储）5/5、退出码 0，报告为后端 `work/iam-acceptance-review-20261104-closeout/center-late-401-permanent-round-1.json`。该结果与独立 6/6 是不同覆盖面，不相加宣称 11 个独立场景。已建议永久固化“进入拦截器前 B 已发布、仍显式发送旧 A”及当前 HTTP 401 正例；当前产品行为已有独立实际拦截器证据，最终独立中心构建和浏览器仍随交付清单核对。

### 2026-11-05：部署构建与会话验证测试装配

独立读回 admin 模块失败报告：`IamSessionValidationServiceTest` 3 项均为 Mockito UnfinishedStubbingException，activeUser 内的另一个 when 嵌套在尚未完成的 thenReturn 参数求值中，尚未执行到身份验证断言。当前源码已先构造 activeUser，再配置 users.selectUserById 返回值；未见将有效性或租约/本地密码/权限断言移除。旧报告保留，修正后的实际复验仍待部署构建结束核对。推送并发用例此轮为 1/1，但不能代替这三项验证。

另一次 reactor `-rf :iws-admin` 因前序只 package、未向本机 Maven 缓存 install 项目依赖而失败，属于构建恢复欠账，不作为运行缺陷或新测试通过。已建议同版依赖先编译安装到本机缓存（明确跳过执行不计通过），再真实执行 admin 及后续部署模块；已经启动的完整回归不为优化时长中断。最终清单须绑定实际通过的源码/测试/制品，不以仅“已打包”覆盖这些失败。

### 2026-11-05 00:17 后：构建通过与标签漏选分开

新 admin 三份 XML 实际为 5/5，失败/错误/跳过均 0，原会话验证三项装配错误已复验消除。部署模块逐报告时间/散列/计数保存在后端 `work/iam-acceptance-review-20261104-closeout/deployable-tests-independent-readback.json`。

不能把该构建绿色计为各模块本轮测试全过：Job 的五个 IAM 测试类标记 dev，而 local 构建只选择 local，target 无 Surefire 报告；AI 的 21 项仍为 11 月 4 日 16:47/19:10 的旧 XML；auth Redis 的单项是 21:12 旧跳过报告。已要求对已有 dev 测试定向执行，或对确实未变输入提供可核的历史证据绑定；Job 权限/回调测试若无实际通过记录须补，Redis 的假设跳过不冒充真实回归。Monitor 没有此类单测可由真实 HTTP 验收，不为凑数新增镜像用例。不改变产品默认配置掩盖漏选，不重复源码未变且已通过的 WMS。

### 2026-11-05：G24 中心本库部门移动的锁路径

中心沿用的 SysDeptController.edit 先做范围检查，再进入 SysDeptServiceImpl.updateDept（@Transactional）。当父级变化时，service 内再次 checkDeptDataScope(newParent)，实际 countDeptById 标记 DataPermission；普通 dataScope4 经 sdss.getDeptAndChild 调用独立 identity 连接 FOR SHARE 读整个授权子树，并保留至外层事务完成。随后主事务的 updateDeptChildren、updateById 及启用祖级要写同一权威库中的这些行，出现自身另一连接共享锁与事务完成的循环等待路径。中心 master/identity 都是权威库；仅证明 IWS 业务库分离后可查询，不能覆盖它。

已要求隔离实证：有 system:dept:edit 的普通用户属于 P、scope4，C1/C2 为 P 下兄弟节点，将 C1 移至 C2 下；校验正常完成、后代 ancestors、父级状态和越界拒绝。超级管理员会跳过范围检查，不能作为对照替代。当前为源码定位，尚无运行结果。若复现，复用权威侧原事务读取语义并隔离 WMS 跨库持锁职责；不能仅凭 DS context 判断事务已绑定的物理连接，也不能全局去锁或新增另一套身份/权限模型掩盖。

### 2026-11-05：dev 标签漏选已补真实运行

独立读回本次新 XML：Job 12/12、AI 21/21、Monitor 7/7、auth Redis 1/1，失败/错误/跳过均为 0。每份时间、散列和计数在后端 `work/iam-acceptance-review-20261104-closeout/deployable-dev-tests-independent-readback.json`；前一次旧/缺报告清单保留，作为退回与修正依据。部署构建、admin 5/5 和这些 dev 测试均分别陈述，不用构建绿色代替被漏选的执行。实施侧接着恢复隔离服务并核 G24，运行验收与最终制品清单仍未关闭。

### 2026-11-05：G24 已真实复现并开始修复

独立读回 `iam-g24-dept-move-scope-acceptance.json`：moveRequest 实际 HTTP200、body.code=500、elapsedMs=50297，cleanup.passed=true。不能把业务码500写成HTTP500。初始失败的脱敏状态、计时和原报告 SHA 已另存后端 `work/iam-acceptance-review-20261104-closeout/g24-dept-move-initial-failure.json`，避免后续复验覆盖历史。普通 scope4 部门移动确实未完成，原仅源码定位状态升级为已复现。

初修开始借用中心本库的 Spring 事务连接，借用连接不自行 commit/rollback/close。已退回核对来源判定：不能只比较配置 master/identity 就声称当前 ConnectionHolder 属权威库；实际 dynamic-datasource 4.5.0 的 DefaultDataSourceCreator 使用 ItemDataSource 包装，单纯 instanceof 裸 Hikari 也不足。须以实际绑定连接/真实包装为据，保证中心可移动、WMS 分库事务不误借、外层连接仍由 Spring 管理。当前仍修复中，之前 WMS 491/491 对新的读取器改动不自动生效。

后续同属 G24 的修复评审：不能保留 master==identity 的配置短路绕开实际连接来源检查，也不能把整个 JDBC URL 转小写；MySQL 在 lower_case_table_names=0 时数据库名区分大小写，iws/IWS 可能是不同库，须保留 catalog 原样。[MySQL 官方大小写规则](https://dev.mysql.com/doc/refman/8.4/en/identifier-case-sensitivity.html)。这里是依据数据库规则检查判等逻辑，尚未另做大小写库的真实运行复现。

本机实际依赖 javap 同时确认：ItemDataSource.unwrap(DataSource.class) 会因自身实现该接口而直接返回自身，原 depth5 循环不能展开它，会退到再次借连接探测。若当前中心事务已占满单连接池，首次判定可能因此等待自身池资源。已要求按项目实际 ItemDataSource/Hikari 直接展开或标准定向 unwrap，简化而非扩通用反射平台，并用单连接池、真实包装及不同 catalog 断言覆盖。实施侧此前所报 22/22 属这些新判定改动之前，不直接作为最终通过。

### 2026-11-05：阶段性交付独立复核与下一阶段

本轮读回 `final-runtime-20261105.json`（生成于09:26+08:00），逐文件重新计算五个JAR、主/分支Web和PDA H5入口散列均一致；主/分支Web除frontend-config.json外全文件散列相同，实际公开frontendId分别为web-main/web-branch；中心JAR内IAM入口与清单一致。结果留于后端 `work/iam-acceptance-review-20261104-closeout/stage-20261105-artifact-readback.json`。这证明本阶段实物一致，不自动证明后续改动或所有运行分支。

重新汇总当前报告：WMS493、admin5、Job12、AI21、Monitor7、auth Redis1，均失败0/错误0/跳过0。G21实际库存精确集合和清理已读回，G24从50297ms业务500变为78ms业务200；原失败不覆盖。有效写入停中心报告为HTTP200/业务503且目标记录0，恢复后同请求业务200且记录1，最终清理0，不把业务码当HTTP状态。Monitor错误页401及图标200/正确ICO散列已独立HTTP验证，记录在 `stage-20261105-public-http-readback.json`。

已授权原实施会话继续收尾，未开子代理：优先补齐G02/G04/G05/G06/G22剩余事务与回调交错；已有证据按输入绑定复用，缺项逐项执行。然后完成本机可执行的自签HTTPS、真正跨站顶层/iframe、禁第三方Cookie、HTTP非安全上下文IndexedDB，以及通知丢失、Redis/数据库故障与节点重启的剩余矩阵。本机隔离TLS不要求共享生产证书，不能整项笼统列外部阻塞。

AI HTTP人员流/gRPC取消与上游模型HTTP取消分开，后者仍false，需给实际SDK版本、源码调用链和影响依据，能在既有接缝解决则解决，不另造模型平台。PDA真机/原生深链仍按用户决定暂缓；WMS独立SCHEMA_STATE_UNSUPPORTED不混称认证已修。最终完成后须停止故障注入、刷新同版清单、启动/停止/恢复及安全凭据渠道，再开放用户自测。目前仍是阶段通过，非整体验收通过。

### 2026-11-05：最终收尾已核，可交付自测

已读取实施会话最近两次完整收尾结果，而非沿用09:26阶段状态。最终清单更新至11:41（+08:00），五个实际JAR重新计算散列全部一致；中心、主Web、分支Web、PDA H5四个公开页面独立读取均为200。独立回读记录为后端 `work/iam-acceptance-review-20261104-closeout/delivery-readback-20261105.json`。稳定烟测14端点正常，TLS临时代理停止，正式中心配置无临时TLS前端和危险全量安全排除。

此前欠账已有新增证据：本机HTTPS跨站顶层A/B、跨站iframe、第三方Cookie限制、真实isSecureContext=false的IndexedDB、MySQL失败关闭/恢复、双标签B获胜及旧A401不污染B；G24报告含后代更新和越界拒绝，清理读回保留。来源为最终验证汇总、TLS跨站报告及MySQL故障报告；不再写成这些本机项目仍未执行。

最新Monitor已修中心停机注销，并保留CSRF：无CSRF返回403且会话保留，合法注销302，待撤销队列写入并跨Monitor重启保留，中心恢复后清空，旧中心会话被拒绝。独立读回定向XML10/10、零失败/错误/跳过，`iam-monitor-pending-restart-acceptance-v2.json`相关标记成立；Monitor新JAR散列为730854928ED34FDDC7B06A2FD7732F3087E1C65A5647CB9217B14648474A152C，与最终清单一致。此前Monitor7项及旧JAR散列仅为历史，不能继续当当前版本。

用户明确“除非严重缺口，否则交付”，本轮未发现需阻止自测的已确认未修严重缺口，故开放本机隔离环境自测。已通知实施会话不再重复故障注入、重启或扩展验收，保留稳定窗口。自测文档已填写精确入口、原管理员凭据渠道、配置位置和现有恢复方式；完整一键恢复脚本尚无，不因此阻止当前已运行环境自测。

后续保留：AI上游模型HTTP取消false（人员HTTP/gRPC终止已核）、PDA真机/原生构建、生产代理和外部证书、通知重投极端矩阵/全部节点集群重启、Job/AI上游前端源码维护限制、WMS独立业务门禁。它们未冒充完成，也不再一概升级为自测阻断项。历史缺陷和失败证据不删除，今后如发现严重问题再按实际影响处置。
