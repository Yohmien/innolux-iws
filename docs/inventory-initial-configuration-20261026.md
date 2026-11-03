# IWS+ 在库管理初始化配置值表

日期：2026-10-26。状态：本轮推荐初始方案；未部署、未写 SQL、未查询或写入数据库、未实施业务、未暂存或提交 Git，也未由用户逐项确认。本文只补[主设计](inventory-management-design-20261026.md)、[分配规则](inventory-allocation-rules-20261026.md)、[SQL 初始化与升级计划](inventory-management-sql-upgrade-plan-20261026.md)、[Web/CRUD 计划](inventory-management-web-crud-plan-20261026.md)的初始化配置值；四文档仍是业务、执行与验收权威。

下表是配置合同，不是已存在表结构或生效数据。稳定配置键、JSON 值和门禁可直接用于后续字段设计；表名、DTO 和最终字段名在 P1 统一冻结。除明确标注的硬不变量外，技术数值均为推荐初始值，可按权限创建新版本修改，不能解释为用户已接受。

`UNSET` 表示该配置项不存在或为空值，必须在配置状态中显式表达，入库时不得保存字符串 `"UNSET"`。`[]` 只表示当前没有任何登记项，绝不等于“允许全部”；每次使用都按所在行和字段语义解释。集合属性、空值、冲突值分别判断；类型目录保持可扩展，不为 `businessType`、`planType`、仓库类型或容量维度建立封闭 SQL 枚举。

## 发布、安全与启用

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `inventory.seed.version` | `1` | 在库配置种子包 | 同版本不覆盖，新值必须新版本 | 种子包整体 `DRAFT`；版本在 SQL、规则清单和应用 release 中一致 |
| `inventory.seed.status` | `"DRAFT"` | 种子包 | 不原位改成 `ACTIVE` | 仅规则发布业务可创建不可变 `ACTIVE` 版本 |
| `inventory.seed.enabled` | `false` | 种子包 | 不覆盖；显式发布后由生效指针表达 | 无生效指针时不得参与正式作业 |
| `inventory.seed.activePointer` | `UNSET` | 全局在库规则 | 新版本经 CAS 切换 | 初始不得自动生成；缺指针时新物料无法首次绑定 |
| `inventory.seed.digestPolicy` | `{"algorithm":"SHA-256","input":"UTF-8 canonical JSON","write":"immutable-lowercase-hex"}` | 每个 `seedCode + seedVersion` | 不覆盖；同 code/version 异摘要拒绝 | 构建种子时必须有 64 位摘要；缺失不得发布或执行 |
| `inventory.seed.conflictPolicy` | `"SAME_CODE_VERSION_DIGEST_SKIP_DIFFERENT_REJECT"` | 种子台账 | 否 | 人工已改行不被覆盖；需要变更时创建新版本 |
| `inventory.invariants.mode` | `"ALWAYS_ON"` | 结构、安全、锁与审计硬不变量 | 否 | 不依赖种子发布，不可被 DRAFT 或已发布站点规则关闭 |
| `inventory.space.newEntity.enabled` | `false` | 新公司、仓库、物料库区、设备库区和储位 | 仅可显式改真 | 按对象种类核验自身配置和实际依赖后启用；公司配置不依赖尚未创建的子仓库，避免循环门禁 |
| `inventory.space.activationGate` | `"LOCAL_CONFIG_EFFECTIVE_AND_DEPENDENCIES_PUBLISHED"` | 新空间实体 | 否 | 任一依赖为草稿、缺值或未注册即不得启用 |
| `inventory.seed.referenceObjects` | `{"companies":[],"warehouses":[],"devices":[],"materials":[],"units":[]}` | 空库初始化 | 站点通过受控配置创建，不覆盖种子为空 | 不自动创建默认公司、仓库、设备、物料或赋权 |
| `inventory.access.department.default` | `"INHERIT"` | 可继承的可见部门字段 | 子级可VALUE/CLEAR，需对象权限 | VALUE不得越权；CLEAR明确清空本字段、不回退上级，部门集合清空即无库存可见范围；INHERIT才解析父值 |
| `inventory.access.rootVisibility` | `"NONE"` | 根公司未授权范围 | 只能由明确授权改变 | 根值为 `UNSET` 或未授权时默认拒绝；发布前要求显式有效值 |
| `inventory.access.department.creationGrantsVisibility` | `false` | 新建部门与公司可见范围 | 否 | 建部门不会自动产生公司可见授权 |
| `inventory.access.inheritanceModes` | `["INHERIT","VALUE","CLEAR"]` | 可继承字段 | 按字段声明使用 | 根节点不得 `INHERIT` 或 `CLEAR` 绕过必填；根必须给出明确有效值 |
| `inventory.device.runtimeState.initial` | `"UNKNOWN"` | 设备运行状态 | 只由可信回报或授权校正改变 | 不是普通配置字段；不可用 `ACTIVE` 配置掩盖未知状态 |
| `inventory.device.outbound.enabled` | `false` | 每个设备端点 | 仅设备配置业务可显式启用 | 设备身份、能力合同、端点、安全门禁全部生效后才可启用 |
| `inventory.device.endpoint` | `UNSET` | 每个设备外呼端点 | 由设备配置业务赋值 | 为空不得启用设备或创建正式外呼；不得填写参考项目默认地址 |
| `inventory.device.mobility` | `UNSET` | 每个设备库区 | 现场显式填写 `FIXED` 或 `MOBILE` | 未填不得发布可用设备配置；不得按参考设备猜固定/移动 |
| `inventory.device.capabilityContracts` | `[]` | 每个设备库区 | 显式登记能力与合同版本 | 表示未登记任何能力，不表示全部允许；正式作业前必须补齐 |

## 空间与仓库类型

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `inventory.space.hierarchy` | `["COMPANY","WAREHOUSE","MATERIAL_ZONE","DEVICE_ZONE","STORAGE"]` | 全局物理树 | 否 | 必须逐层且唯一父级；同类不嵌套、不跳层，设备库区之间不重叠储位 |
| `inventory.warehouse.companyId` | `UNSET` | 每个仓库 | 由空间变更业务改挂 | 根级必填；空值、跨公司或不可证明映射阻断 |
| `inventory.warehouse.typeCatalog` | `["GENERAL","FLOOR"]` | 服务端可扩展仓库类型目录 | 可登记新类型，不覆盖已引用版本 | 名称仅示意；`PCBA` 只作为参考项目名，不进入类型码、表名或界面 |
| `inventory.warehouse.typeConfig.floorNo` | `UNSET` | 选择 `FLOOR` 的仓库类型参数 | 仓库配置业务赋值 | 选 `FLOOR` 后必填；不得从参考项目补默认楼层 |
| `inventory.warehouse.typeConfig.floorLabel` | `UNSET` | 选择 `FLOOR` 的仓库类型参数 | 仓库配置业务赋值 | 选 `FLOOR` 后必填；与 `floorNo` 同版本保存和快照 |
| `inventory.floor.targetMappings` | `[]` | 楼层到目标仓库的规则范围 | 新版本显式新增；不覆盖已发布映射 | `NOT_CONFIGURED`；没有真实映射时不能发布依赖楼层目标的规则 |
| `inventory.floor.routingRules` | `[]` | 站点前缀、比例、楼层与解析后仓库 ID | 新版本显式新增 | 每条必须含现场料号前缀、比例、`floorNo/floorLabel` 和已存在 `targetWarehouseId`；空数组禁止发布 |
| `inventory.warehouse.externalAliases` | `[]` | 每个外部系统/站点 | 新版本显式新增；不覆盖人工项 | 表示无别名，不是任意匹配；启用外部别名时必须系统内唯一解析 |
| `inventory.warehouse.companyAssignment` | `"ONE_CURRENT_COMPANY"` | 每个仓库 | 只能由空间变更业务调整 | 不得出现第二当前公司；跨公司改挂需影响评估 |

## 储位、载具与容量

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `inventory.storage.allowMultiMaterial` | `false` | 每个储位 | 储位配置业务可覆盖 | 改真时混放键必须有效；多料不表示容量、禁存或安全约束放宽 |
| `inventory.storage.mixedMaterialKey` | `"materialCode"` | 每个储位 | 可新版本改为已登记属性 | 未知键不得当通配；无法证明兼容、稳定性或索引能力时拒绝 |
| `inventory.storage.unknownMixedKeyPolicy` | `"REJECT"` | 分配硬筛选 | 否 | 未知键、空语义或不可比较值不得放行候选 |
| `inventory.storage.capacityMode` | `"TOP_LEVEL_LOAD_COUNT"` | 每个储位 | 可显式切换为已注册容量合同 | 初始只使用顶层载具/独立物料标签槽位，不启用体积、重量等物理测量维度 |
| `inventory.storage.topLevelSlotCapacity` | `{"unit":"TOP_LEVEL_LOAD","max":1}` | 每个储位 | 新版本可提高上限 | 每个顶层载具或独立物料标签占 1 槽，叶子数量不重复；`1` 仅是占位容量合同，不证明承重或尺寸已验证 |
| `inventory.storage.leafQuantityContribution` | `false` | 容量聚合 | 否 | 载具内叶子物料不再各占一个储位槽，物料数量按库存单位另算 |
| `inventory.storage.measurementDimensions` | `[]` | 物理容量维度 | 显式启用已登记维度 | 表示未启用体积、重量等维度，不是无限制；启用后必填维度、单位、精度和测量来源 |
| `inventory.storage.deviceContractMissingDimension` | `"REJECT_CANDIDATE"` | 设备合同覆盖的储位 | 否 | 合同要求尺寸或重量而储位未配置时阻止候选及对象启用，不猜测默认值 |
| `inventory.load.rootAutoSplit` | `false` | 顶层载具和独立物料标签 | 否 | 分配、容量不足或超深均不得自动拆载；拆分另走业务 |
| `inventory.load.attributeAggregation` | `"SINGLE_OR_CONFLICT_SET"` | 载具汇合属性 | 否 | 无冲突才呈单值；冲突保留去重集合与叶子引用，不移除原物料值 |
| `inventory.load.safetySetSemantics` | `"ALL"` | 安全、禁存、设备限制 | 否 | 所有叶子必须满足，不能用 `ANY` 绕过 |
| `inventory.load.businessSetEmptyPolicy` | `"REJECT"` | 业务集合约束 | 否 | 空集合不等于允许全部，无法证明符合时拒绝 |
| `inventory.load.batchShadowAccounting` | `"SIMULATION_ONLY"` | 批内候选计算 | 否 | 影子账不是正式占位；只有真实原子锁成功才进入 `LOCKED` |

## 物料、数量、日期与单位

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `inventory.quantity.decimal` | `{"precision":20,"scale":6,"maxScale":6}` | 物料与规则版本 | 更高精度需新结构合同 | `decimal(20,6)` 兼容；不得配置超过当前存储精度 |
| `inventory.quantity.jobRequest.rule` | `"POSITIVE_ONLY"` | 新入库/移动等作业请求量 | 否 | 只要求新作业请求量 `> 0`；不得拿此规则限制当前实存 |
| `inventory.quantity.currentStock.min` | `0` | 当前实存 | 否 | 允许 `= 0` 表示出库耗尽或终态；禁止全局 `quantity > 0` 阻断归零 |
| `inventory.quantity.traceDelta.sign` | `"NEGATIVE_ALLOWED"` | trace 变化量 | 否 | delta 可负，但只由业务事实事务生成，不是独立库存输入 |
| `inventory.quantity.overPrecisionPolicy` | `"REJECT"` | 所有数量输入 | 否 | 超精度拒绝，绝不静默舍入 |
| `inventory.unit.allowedUnits` | `[]` | 空间、规则包及物料子规则 | 站点显式登记 | 语义为 `NOT_CONFIGURED`，不能启用依赖单位的正式作业；不默认 `PCS` |
| `inventory.unit.autoConversion` | `false` | 库存与 trace | 显式换算策略可覆盖 | 未登记兼容换算时不自动改变单位或数量 |
| `inventory.material.production.optionalFields` | `{"supplierId":true,"customerId":true,"batchNo":true,"extensions":true}` | 物料子规则 | 业务规则可单独改必填 | 初值不额外限制非空；类型、来源白名单和当前业务要求仍校验 |
| `inventory.material.allowedValuePolicies` | `{"supplier":"NO_EXTRA_FILTER","customer":"NO_EXTRA_FILTER","batch":"NO_EXTRA_FILTER","extensions":"REGISTERED_FIELDS_ONLY"}` | 物料子规则 | 新规则版本可收紧 | NO_EXTRA_FILTER仅不额外限制合法非空值，不生成主数据、不绕过对象权限/来源与业务校验；字段未登记不能执行表达式 |
| `inventory.material.supplierCustomerSeed` | `[]` | 供应商/客户主数据 | 站点通过授权主数据维护 | 不凭空创建供应商/客户字典，也不把空值当通配 |
| `inventory.material.date.nullable` | `true` | 生产日期 | 子规则可改必填 | 非空时按冻结解析合同处理；无效日期拒绝 |
| `inventory.material.date.nonNullParser` | `{"contract":"ISO_LOCAL_DATE_STRICT","format":"yyyy-MM-dd"}` | 生产日期解析版本 | 新解析版本显式发布 | 不录入一段未经解析合同的 Java 正则；严格解析、无效日期拒绝 |
| `inventory.calendar.weekStart` | `"MONDAY"` | 周码解析 | 新日历版本可覆盖 | 与周编号规则一起冻结，变更不得重解释旧记录 |
| `inventory.calendar.weekNumberRule` | `"ISO8601_WEEK_DATE"` | 周码解析 | 新日历版本可覆盖 | 明确跨年周归属；不得按客户端本地周算法替代 |
| `inventory.calendar.weekRuleConsistency` | `"VALIDATE_AS_A_SET"` | 日历版本 | 否 | ISO8601_WEEK_DATE要求MONDAY；如改为周日，必须同时选择已注册的另一周编号合同，禁止只改单字段仍声称ISO周 |
| `inventory.calendar.businessTimezone` | `UNSET` | 每个站点/规则作用域 | 现场显式填写 IANA 时区 | 未填不得发布规则包；禁止用客户端或服务器默认时区冒充业务时区 |
| `inventory.material.shelfLifePolicy` | `UNSET` | 物料子规则 | 后续显式策略版本 | 无策略时不自动推定效期字段、单位或日期 |
| `inventory.material.bomConversionPolicy` | `UNSET` | 物料子规则 | 后续显式策略版本 | BOM 用量与库存数量分开；无换算策略不自动推定 |
| `inventory.material.hierarchyConstraint` | `[]` | 物料子规则属性目录 | 新版本可加已登记约束 | 表示未配置特定层级限制，不是允许成环；树无环、单父始终为硬不变量 |
| `inventory.assignment.existing.versionPolicy` | `"OWN_ASSIGNMENT_FROZEN"` | 已归属旧物料 | 仅显式归属合并可改后续版本 | 旧物料继续使用原逻辑归属和原规则包，新任务不自动切最新版 |
| `inventory.task.dependencySnapshot.freeze` | `true` | 每次正式任务绑定 | 否 | 首次绑定原子固化规则包、空间、日历、单位和容量依赖版本 |

## 汇总与显示

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `inventory.summary.groupBy` | `["materialCode","unit","rulePackageVersion"]` | 汇总规则版本 | 新汇总版本可覆盖 | 三个维度共同分组；发布后不可原位修改 |
| `inventory.summary.nullGrouping` | `"DISTINCT_NULL_BUCKET"` | 物料料号等可空分组值 | 否 | 空值独立桶；规则匹配只用显式 `IS_NULL`，不是通配 |
| `inventory.material.materialCodeRequiredForStorage` | `false` | 在库物料单元 | 业务规则可改接收条件 | 无料号仍可保存；是否匹配某规则由该规则明确判断 |
| `inventory.summary.crossUnitAggregate` | `false` | 汇总查询 | 只有冻结换算版本可覆盖 | 不跨单位相加，单位不同不得互相扣减 |
| `inventory.summary.crossRuleVersionAggregate` | `false` | 汇总查询 | 显式汇总版本可覆盖 | 默认不跨规则版本聚合，避免混用口径 |
| `inventory.summary.carrierQuantityContribution` | `false` | 载具树汇总 | 否 | 载具自身不贡献物料数量；按物料单元 ID 去重叶子 |
| `inventory.summary.displayScale` | `6` | 页面及导出投影 | 可按展示合同调整 | 仅显示精度，不回写真存量、预留量或 trace delta |

## 分配与候选

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `inventory.allocation.phaseOrder` | `["CARRIER_FIRST","ALL_LEAF_HARD_FILTER"]` | 每次分配执行 | 否 | 先做载具组级预筛，再逐叶子应用各版本硬约束；任一叶子不兼容即无候选 |
| `inventory.allocation.ranking` | `["COMPATIBLE_RESIDUAL","SCARCITY_OR_DEEP_PROTECTION","BEST_FIT_BY_DIMENSION","STORAGE_ID_ASC"]` | 规则包排序参数 | 新版本可替换已注册排序策略与参数 | 此为推荐顺序；硬筛选、保护硬下限与可达性不能被排序绕过，最后稳定消歧。单维与多维模式互斥，不连续叠加两种评分 |
| `inventory.allocation.scarcityProtection` | `{"weight":0,"protectedCapacity":0,"mode":"OPTIONAL_HEURISTIC"}` | 规则包/作用域 | 现场可新版本提高保护量 | 零表示无额外保护；设备可达、深位可达和安全门禁仍是始终生效的硬约束 |
| `inventory.allocation.singleDimension` | `"BEST_FIT"` | 单一可比容量单位 | 新排序版本可覆盖 | 只在同维度、同单位、同精度内比较剩余量 |
| `inventory.allocation.multiDimension` | `{"mode":"LEXICOGRAPHIC_NORMALIZED_RESIDUAL","orderedDims":[],"inventNormalization":false}` | 规则包排序参数 | 现场声明有序维度和归一化基准 | 空数组表示未启用额外多维归一化；只用槽位维度，不虚构总容量分 |
| `inventory.allocation.tieBreak` | `"STORAGE_ID_ASC"` | 相同排序键的候选 | 可选择其他已注册稳定唯一键 | 同一冻结快照必须有确定结果；不按数据库偶然返回顺序 |
| `inventory.allocation.batchStrategy` | `"BEST_FIT_DECREASING"` | 同维度/单位/精度批组 | 新策略版本可覆盖 | 不可拆单元先按已配置可比规模降序，再逐个best-fit；同规模以稳定单元ID消歧 |
| `inventory.allocation.batchGrouping` | `["capacityDimension","unit","scale"]` | 批量分配 | 只能按兼容合同扩展 | 同组才执行 BFD；不同维度、单位或精度不得混排相加 |
| `inventory.allocation.maxLoadDepth` | `16` | 每次装载树校验 | 新版本可调整 | 推荐上限，超限拒绝；绝不自动拆树或通过缩树满足深度 |
| `inventory.allocation.batchSize` | `100` | 单次批量父业务 | 新版本可调整 | 推荐初始值；不得通过批切破坏同一不可拆组或拆合原子性 |
| `inventory.allocation.atomicGroupMax` | `100` | 同一原子组 | 新版本可调整 | 超限整组拒绝，或经另行批准建立可恢复业务；不得拆事务提交半组 |
| `inventory.allocation.pageSize` | `{"default":50,"max":200}` | 分页 | 可按权限和性能合同调整 | 分页上限不是候选截断上限；服务端核对总数和稳定排序 |
| `inventory.allocation.candidateLimit` | `UNSET` | 单次候选评估 | 现场可设限额 | 未设时不得静默取前 N；设置后超限返回 `TOO_BROAD`，不丢弃候选 |
| `inventory.allocation.deepPositionOrder` | `UNSET` | 多深位设备 | 必须由设备访问合同赋值 | 多深位未配置访问顺序时拒绝候选；不默认 FIFO 或 FILO |
| `inventory.allocation.concurrentRetry` | `"PLATFORM_RETRY_POLICY"` | 同一任务原规则版本 | 否 | 固定版本重选，不无限重试；不杜撰应用专用次数，耗尽为 `NO_CANDIDATE` |

## 锁、释放与计划

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `inventory.lock.commitTransition` | `"LOCKED_TO_CONSUMED_PLUS_OCCUPIED_SAME_TX"` | 入库/内部到位提交 | 否 | 锁消费、实际占用、位置/数量、trace 和 checkpoint 同事务；任一失败全回滚 |
| `inventory.lock.failureRelease` | `"SAFE_RELEASE_ONLY"` | 已证明失败或安全取消 | 否 | 只释放本任务份额；不改其他锁/占用，未知外部效果不得释放 |
| `inventory.lock.unknown.autoRelease` | `false` | `UNKNOWN/ACCEPTED` 效果 | 否 | 只提示与对账；不得因超时或页面关闭自动释放 |
| `inventory.lock.unknown.reminder` | `"15m"` | 锁状态提醒 | 运营提示配置可调 | 仅通知，不释放锁、不新增定时任务、不自动外呼；具体通知载体由部署决定 |
| `inventory.lock.holdTtl.autoRelease` | `false` | 分配锁/业务扣留 | 否 | 初始禁用 TTL 自动释放；不得伪造 `expiresAt` 或超时成功 |
| `inventory.lock.release.fence` | `"OWN_TASK_FENCED_CAS"` | 每个原 LOCK | 否 | 重验 generation、证据 revision 和锁持有人；只释放本任务份额 |
| `inventory.lock.release.modifiesOccupancy` | `false` | 分配释放 | 否 | 释放锁不修改真实占用；已消费锁只返回事实，不撤销库存 |
| `inventory.lock.effectKey.reuse` | `"REUSE_ORIGINAL_ON_UNKNOWN"` | 外呼与幂等 | 否 | UNKNOWN 不换键重发；新证据仍关联原 effectKey |
| `inventory.business.parentType.required` | `true` | 正式 LOCK/RELEASE/MATERIAL_CHANGE | 否 | 父业务类型必须从动态注册目录解析；无父业务只允许查询或只读模拟 |
| `inventory.business.typeCatalog` | `[]` | 可扩展业务类型目录 | 仅补缺，不覆盖已发布/已引用版本 | 空数组表示本期未注册真实父业务模板，不是封闭枚举；挂载权限需显式登记 |
| `inventory.plan.typeCatalog` | `[]` | 可扩展计划类型目录 | 仅补缺，保留历史版本 | `ALLOCATION_LOCK`、`ALLOCATION_RELEASE`、`MATERIAL_CHANGE` 仅当前示例，注册后才能用于正式任务 |
| `inventory.process.dispatch.default` | `"QUEUE"` | 新建业务计划 | 仅显式合同可覆盖 | 多步骤及设备作业默认 QUEUE；IMMEDIATE 必须已声明安全失败合同且请求内可完成 |
| `inventory.process.scheduler.enabled` | `false` | 在库新增调度 | 部署另行授权可覆盖 | 本期不启用自动调度、设备外呼或后台驱动 |

## 审计、权限与历史

仓储表页默认优先复用现有IwsCrudPage；以下是拟新增复用策略元数据，不表示当前组件已实现所有扩展。特殊交互可按实际需求覆盖视图，后端权限、安全和审计约束不可覆盖。

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `inventory.ui.pageReuse` | `"PREFER_IWS_CRUD_PAGE"` | 仓储表页 | 特殊交互可选择局部扩展或专门视图 | 复用为默认选择，不是强制所有视图套表格 |
| `inventory.ui.userCustomization` | `["VISIBLE_COLUMNS","WIDTH","ORDER","FILTER","SORT","PAGE","EXPORT_FIELDS"]` | 授权用户的表页配置 | 可在服务端元数据许可范围调整 | 字段权限、只读标记、安全约束不因用户列配置改变 |
| `inventory.ui.writeDispatch` | `"REGISTERED_SERVER_ADAPTER"` | 表页新增/修改/删除/导入 | 按登记表服务选择受控CRUD或业务/计划 | 不接受任意表名SQL；前端不串联多个领域动作，导入预览后走相同受控写链 |
| `inventory.ui.mutationFeedback` | `"DISTINGUISH_ACCEPTED_AND_APPLIED"` | 标准表页及局部扩展 | 否 | 业务受理不代表事实已生效；按真实状态更新列表、待处理标记和实例链接 |

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `inventory.audit.immutable` | `true` | 成功领域历史与审计 | 否 | 不提供普通编辑、删除或导入；补偿追加新记录并关联原记录 |
| `inventory.audit.successWrite` | `"SAME_TX"` | 成功配置/物料事实 | 否 | 审计写失败时业务事实、历史、锁或占用和 checkpoint 全部回滚 |
| `inventory.audit.failedAttempt` | `"SEPARATE_APPEND_ONLY"` | 失败、拒绝、重试、UNKNOWN | 否 | 独立 attempt 留证，不伪造成功 trace 或库存 delta |
| `inventory.audit.identityFields` | `{"actor":"TRUSTED_AUTH_SNAPSHOT","initiator":"TRUSTED_AUTH_SNAPSHOT","executor":"CURRENT_AUTHENTICATED_PRINCIPAL"}` | 领域审计 | 否 | 同步人工作业取当前认证；异步保留父任务可信人员快照，执行者可为HUMAN/SYSTEM/SERVICE；服务账号不冒充自然人 |
| `inventory.audit.businessSnapshot` | `["businessType","businessTypeName","businessContractVersion","businessId","planType","planTypeName","planCode","planDefinitionVersion","instanceId","taskKey"]` | 成功事实 | 否 | 分类、单据、计划类型、定义与实例分别记录；名称/版本取当时快照，不用业务调度码冒充businessType |
| `inventory.audit.autoDeletion` | `false` | 历史与审计 | 否 | 自动清理禁用；被引用版本和处理器保留 |
| `inventory.audit.retention` | `"UNLIMITED_UNTIL_APPROVED_POLICY"` | 历史与审计 | 经明确批准的保留策略可覆盖 | 不得以默认值压缩、删除或改写历史 |
| `inventory.audit.historyActions` | `["QUERY","EXPORT_AUTHORIZED","COMPARE","OPEN_CURRENT"]` | 历史/家谱/审计页 | 按权限开放只读动作 | 不出现 edit、delete、import 或人工补造成功历史 |
| `inventory.audit.export.default` | `"DENY"` | 全部历史导出 | 仅显式角色授权可改 | 缺授权拒绝；前端隐藏不能代替后端校验 |
| `inventory.audit.trace.order` | `["occurredAt DESC","id DESC"]` | 追溯分页 | 可按稳定替代排序合同调整 | 服务端稳定分页，不用无限滚动假完成 |
| `inventory.audit.export.maxRows` | `10000` | 单次导出 | 部署可调，须服务端执行 | 导出始终叠加当前公司、对象、部门和筛选授权；不是全库上限 |
| `inventory.audit.rawPayload` | `{"store":false,"storeCredentials":false,"storeFullSensitiveLabel":false,"storeHashAndEvidenceRef":true}` | 审计、请求日志、外部报文 | 否 | 只约束日志/审计复制；物料实体仍按原需求保留原始标签，受对象权限与敏感字段控制，不因默认脱敏改写业务存储 |

## 十三项种子分类与初值

本期实际种子初版使用version=1；canonical_digest由规范JSON计算并在种子SQL固定，不伪造摘要。硬不变量code仅作能力/审计标识，不生成可关闭的草稿规则；后续参考项不写本期记录。同code/version/digest跳过，不同digest拒绝，不覆盖人工修改。

| seed code | 分类 | 初值/行为 | 发布与使用门禁 |
| --- | --- | --- | --- |
| `inv.company.warehouse_tree` | 本期硬不变量 | 公司→仓库→物料库区→设备库区→储位，单当前父级 | 始终生效；不可由 seed 关闭或覆盖 |
| `inv.safety.enabled_gate` | 本期硬不变量 | 启停、安全、设备健康与对象权限在真实执行时重验 | 始终生效；旧规则不能绕过当前安全停机 |
| `inv.safety.all_leaf` | 本期硬不变量 | 安全、禁存、设备限制和容量对全部叶子成立 | 始终生效；`ANY` 不能绕过 |
| `inv.load.no_auto_split` | 本期硬不变量 | 顶层载具和独立物料标签不可自动拆 | 深度、容量或候选不足均拒绝；另建拆分业务 |
| `inv.mix.compatible_only` | 本期硬不变量 | 只接受混放键可证明兼容的多料 | 未知键和非通配空值拒绝 |
| `inv.alloc.best_fit_stable` | DRAFT 配置 | 推荐兼容余量/保护/best-fit顺序、额外保护量0、多维有序维空、storageId稳定消歧 | 可新版本选择已注册策略；发布前校验维度合同，硬筛选和全叶安全始终保持 |
| `inv.ownership.legacy_version` | 本期硬不变量 | 旧物料继续使用原逻辑归属和原规则版本 | 新任务不自动切最新版；仅显式归属合并改变后续版本 |
| `inv.lock.atomic_consume` | 本期硬不变量 | 到位时锁转 CONSUMED、写 OCCUPIED 与 trace 同 TX | 任一失败全回滚，不先释放后占用 |
| `inv.release.evidence_only` | 本期硬不变量 | 仅凭本任务 fenced CAS 和确定无效果证据释放 | UNKNOWN/ACCEPTED 不释放 |
| `inv.effect.unknown_hold` | 本期硬不变量 | UNKNOWN 保持锁定，只提示与对账 | 无自动释放、自动外呼或超时成功 |
| `inv.outbound.fifo_stable` | 后续参考 | 不创建本期种子记录 | 仅在后续出库接入文档设计；不写首期 SQL、按钮或执行模板 |
| `inv.count.whole_reel` | 后续参考 | 不创建本期种子记录 | 仅在后续盘点接入文档设计；不写首期 SQL、按钮或执行模板 |
| `inv.floor.target_warehouse` | DRAFT 配置 | `targetMappings=[]`、`routingRules=[]`，即 `NOT_CONFIGURED` | 现场前缀/比例/楼层/解析后仓库 ID 全部填写并校验后才可发布；空映射不得发布 |

## 站点必填与发布快速路径

| 配置键 | 初始值 | 层级/作用域 | 是否覆盖 | 发布/必填门禁 |
| --- | --- | --- | --- | --- |
| `site.companies` | `[]` | 实际公司 | 受控配置业务新增 | 不 seed 自动公司；仓库不得引用空公司 |
| `site.visibleDepartments` | `[]` | 每个公司 | 显式授权/对象范围 | 不自动赋权；根值必须明确，缺授权默认拒绝 |
| `site.businessTimezone` | `UNSET` | 站点规则作用域 | 现场显式填写 | 缺失时规则包不能发布；不能以客户端时区代填 |
| `site.allowedUnits` | `[]` | 站点及物料规则包 | 现场显式登记 | 表示未配置；为空不能启用需要单位校验的正式作业 |
| `site.deviceIdentities` | `[]` | 设备库区 | 现场登记身份、固定/移动和能力合同 | 无身份/能力不得启用或外呼；`mobility` 不得猜 |
| `site.storageScope` | `[]` | 可分配储位集合 | 空间配置业务建立 | 空集合不得启用分配；每个储位必须有完整父链和可用容量合同 |
| `site.floorRouting` | `[]` | 楼层目标规则 | 新版本显式配置 | 每个现场规则需前缀、比例、楼层与仓库 ID；不沿用参考项目数值 |

发布快速路径保留“全部 DRAFT”的配置向导：填写公司、可见部门、业务时区、允许单位、设备身份/能力/端点与固定或移动属性、储位可用范围及所需楼层映射；校验并发布不可变规则包；再逐一显式启用已满足依赖的空间、设备和储位；最后用隔离 fixture 验收。任何一步都不会因向导完成而自动发布、自动赋权、自动创建默认公司或启用设备。

fixture 至少验证：空 seed 不产生真实公司/仓库/物料/单位；同摘要种子跳过、异摘要拒绝；空 `allowedUnits` 阻止启用；根 `UNSET` 拒绝发布；`quantity` 作业正数与实存归零、trace 负 delta 分离；槽位占位容量不推导承重尺寸；缺少设备要求维度时候选被拒；深度 16、批 100 和原子组 100 超限均拒绝；UNKNOWN 不自动释放；15 分钟提醒不创建定时任务；成功事实与 trace/audit 同 TX 回滚；物料实体原始标签仍按业务权限保存，而审计/请求日志不复制敏感完整标签。fixture 通过不等同于入库、出库、移库、冻结或设备外设真实接入验收。

本文件只把上述值写成待验收配置合同；实现、SQL、真实库验证、菜单/权限 seed、设备联调和 Git 提交均未执行。
