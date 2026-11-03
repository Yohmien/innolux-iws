# IWS 在库管理实施交接（2026-10-29）

> **2026-11-03 最终状态，覆盖本文件后续历史状态：** P1—P7 当前标准化范围已整合验收，复评 REV-01—REV-08 已关闭，`INV-I-01 = done`。最终证据为全 WMS `481/481`、根 reactor `43/43` clean `test-compile`、全应用 `43/43` package、Web `264/264`、空间生命周期 `70/70`；正式空库 `181` 表、`77` WMS 表、`256` 菜单，最终 JAR `4C2515415A427CF1AC1A516DA05A408F7491B8E227CBEB9DCC6D5552BD95EBC3`。普通账号本机与 `192.168.1.21:18000` LAN 代理浏览器均为 13 checks、20 API、0 错误。`INV-P5-05`、`INV-P6-03`、`INV-P7-05`、`INV-I-02` 继续承接真实 MOVE、非空清空、现场入出库和正式物料写入。真实库未写、未部署、未 commit/push。当前权威摘要见[整改与验收摘要](C:/code/company/innolux-iws/work/verification-current-20261103/summary.md)；以下 10-29 状态仅作历史恢复记录。

交接时间：2026-10-29 18:50（Asia/Shanghai）。来源会话 `01a17ac7-e01f-7cb3-ac81-b1769dac22a4`（标题“执行库存管理交接方案”，模型 `gpt-6.1-sol`）。

本文件只做交接，不增加实施、数据库写入、提交、推送、部署或设备调用授权。需求仍以 [P1 合同](inventory-p1-contract-20261027.md)、[任务图](inventory-implementation-tasks-20261027.md)、[后继切片清单](C:/code/company/innolux-iws/work/implementation-frontier-20261029/next-slices.md) 和主方案为准；本文件不复制其正文。

## 1. 工作区基线

核对时间 2026-10-29 18:45。下一位代理恢复前必须重新 `git status --short`，不得把本快照当作当时事实。

| 仓库 | 分支 | HEAD | 工作区 |
|---|---|---|---|
| `C:/code/company/innolux-iws` | `main` | `84bc57bc5` | 22 个已修改 + 70 个未跟踪，均为本轮在库管理实施内容 |
| `C:/code/company/ui/innolux-iws-web` | `main` | `0c7ec33` | 36 个已修改 + 23 个未跟踪 |
| `C:/code/company/ui/innolux-iws-pda-plus` | `master` | `c5d2e67` | 干净 |
| `C:/code/company/innolux-iws-inventory-design` | `codex/inventory-design-20261026` | `2d15d09b2` | 4 个已修改 + 11 个未跟踪（方案、任务图、本交接） |
| `C:/code/company/doc/innolux-iws` | 非 Git 工作区 | — | 以当前文件为准 |

禁止清理、重置或切换上述工作区来“恢复干净”；未跟踪文件是交付内容。

## 2. 用户目标与边界

- 目标：按 2026-10-27 设计交接执行 P1—P7 在库管理方案，当前处于实施与限定验收阶段。
- 已授权：隔离 MySQL 验证、本地 LAN HTTP、纯 JUnit/Maven、产品 SQL 文件编写、文档与证据产出。
- 未授权：真实库写入、替换 live、部署、commit/push、设备安装、外部真实账户联调。
- 根 Maven 为独占资源，原持有者 Gauss（`01a184e9-75e3-7780-a1bb-2e362a508e61`）已释放 Java 冻结但保留槽位；恢复时先确认谁持有。
- 2026-10-29 用户要求：`C:/Users/yohmi/.codex/AGENTS.md` 已删除 magpie 子代理配置引用和“默认派给子代理”条款（现 6119 字节、45 行，全文不含 `magpie` 与“默认派给子代理”）。Memory 中 `deepseek/deepseek-flash` 委派偏好未改，需用户另行决定。
- 恢复时不要重新引入“子代理默认委派”假设；是否派工由新会话按当时指令判断。

## 3. 已完成（限定验收）

| 切片 | 结论 | 证据路径 | 不得扩大为 |
|---|---|---|---|
| 空间十表 DDL/结构 | 隔离 MySQL 8.0.45 通过；后被 P4 同版覆盖 | [报告](C:/code/company/innolux-iws/work/space-core-init-20261029/report.md) | 完整 P2/S2、生命周期、数据回填 |
| 物料十一表目标 DDL | 11 表 / 220 列 / 85 索引列 / 34 CHECK；当前受影响矩阵 24/24 | [Spec 修复报告](C:/code/company/innolux-iws/work/material-foundation-20261029/report-spec-fixes.md) | 完整 P4、迁移、服务约束 |
| core/init 同版整合 | `core 66 / init 170 / menu 242 / role-menu 133`；旧 55/159 不变 | [报告](C:/code/company/innolux-iws/work/material-core-init-20261029/report.md) | 真实库结构、部署 |
| 条件 / 结果 / 装载内核 | 79 / 56 / 23 项通过 | `work/rule-dsl-20261029`、`work/rule-result-20261029`、`work/load-kernel-20261029` | 生产注册、持久化、事务 |
| Scope Guard | 最终有效 82 项（69 + 13）全类读完接受 | [报告](C:/code/company/innolux-iws/work/rule-scope-guard-20261029/report.md) | 正式结构门禁已完成 |
| 全量回归与构建 | 15 个 WMS 类 281 项 + SSE 12 项 = 293 项全绿；38 个模块 package 成功 | [报告](C:/code/company/innolux-iws/work/verification-current-20261029/report.md) | 新包 HTTP、真实库 |
| 新包 LAN HTTP | 2/2 通过：鉴权快基线、在途切号 | [run](C:/code/company/innolux-iws/work/http-verify/sse-auth-20261029/runs/20261029-182319/summary.json) | 旧 12 场大矩阵不可替代，OAuth 真实 provider 未验 |
| 公司闭环 / 空间只读 | 后端与 Web 页面可用（旧源码） | `work/http-verify/real-20261029/` | 空间生命周期、正式发布 |

固定产物：

- core `194ED2506EFC278AE8F6CDC732E40E13D055782EBEE5C623CAB022651BCD48C9`
- 当前 init `D23BE8DA101C1653C24DD6A793CDD2BA8D941A8D4B0A6EA6371D2CCCA25D1CE9`
- P4 结构验收时 init `252CDED3E7BD86725DA2B47C8C58746E1A19FB61BA8CFFD146B2EEB937BE2E58`（与当前不同，语义待 Tesla 核对）
- JAR `589E0FD0BE949E068C54562D66F9F7EBDB0955ED8437C0F2C9AF6BEFBF87E265`，263,075,306 字节，只读副本在 `C:/Users/yohmi/AppData/Local/Temp/iws-verification-current-20261029/589E0FD0…/iws-admin.jar`

## 4. 在途代理与写集

“interrupted”是父会话中断导致，不代表任务完成；重新派工前先核对其工作区产物。

| 代理 | 会话 ID | 状态 | 写集 / 产物 | 缺口 |
|---|---|---|---|---|
| Gauss（根 Maven） | `01a184e9-75e3-7780-a1bb-2e362a508e61` | 完成，保留 Maven 槽 | 仅证据与构建 | 不自行再跑；新任务需主调度放行 |
| Confucius（trace） | `01a18732-2c64-77e2-bccf-d56ee4c5fa83` | 切片代码完成，待 Maven | `iws-wms/.../material/trace/**`、Mapper XML、3 个测试类、`work/material-trace-20261029/**` | 13 个 MySQL 场景未跑；未接 SAME_TX |
| Tesla（空间菜单 / P4 init） | `01a184ef-fcca-7f62-96b7-f6bc21cf825d` | interrupted | `script/sql/iws_inventory_space_menu.sql`（`967741CB…`）、`iws_inventory_space_menu_verify.sql`（`3D5377D4…`）、`work/space-menu-20261029/**` | 报告未出；矩阵 67 次 /83 断言全过但未获主验收；`init` 未同步 |
| Kuhn（空间目标契约） | `01a18614-d3c4-77b3-a8c9-422c662010a5` | interrupted | `work/space-target-contract-20261029/**` | 提案未定稿；无 SQL/Java |
| 规则发布绑定 | `01a1861b-a280-7e63-840b-9fc521b92566` | interrupted | `work/rule-publication-binding-20261029/**` | 提案未定稿；provider、用途注册未实现 |
| Nietzsche（HTTP/LAN） | `01a183cc-4654-7c31-8392-a6f260242bab` | interrupted | `work/http-verify/sse-auth-20261029/**` | 2/2 已过；OAuth 隔离 stub、最终 5 场景报告未完成 |
| Docs 前沿 | `01a18616-2b1a-7452-a899-fc9d08e37d67` | 完成 | `work/implementation-frontier-20261029/next-slices.md` | 只读文档 |

注意：当前 `iws_inventory_space_menu.sql` 哈希 `967741CB…` 与 18:23 LAN 使用的 `827F2187…` 不同，说明菜单 SQL 在该次 LAN 之后又改过；再次 LAN 前必须复验。

## 5. 已签定技术决定

- 空间单一权威：`wms_location_effective` 是所有既有空间对象唯一当前指针；不新增 `zone/storage.current_location_version_id`，warehouse 已加指针列须同版删除，不做双写、双读或投影。
- 仓库 `enabled` 新增默认 0 + 独立 CHECK；zone 新增 `revision`；storage 用既有 `version` 单一 CAS；API 显式映射 revision，不加第二令牌。
- zone/status 与 storage/disable_status 的创建路径必须默认停用，不能沿用旧默认启用。
- 规则绑定：`rule_version.attribute_bindings_json` 服务端写入、DRAFT 可 NULL、ACTIVE 必须完整；旧 NULL 版本只读且不算执行就绪；快照取已锁目录 11 个合同字段，覆盖 condition+SORT 引用并集；保存用途/来源合同/limits 精确版本；digest v2 覆盖快照；未知来源或版本 fail-closed，不做 latest fallback。
- 锁序：activation version → definition → catalog 精确键排序 → ACTIVE UPDATE 与快照同事务；发布既有 pointer→def→scope 锁序不反转。
- 用途首期只注册 `STORAGE`；`ALLOCATION_CANDIDATE_FILTER/SORT` 仅保留 v1 代码，不扩充多 scope registry。
- 操作符先校验非法/重复再 canonical 排序，不用去重修复非法目录；DECIMAL 内核单叶 20/6、容量通用 38/12 不得注册为支持来源或截断。
- trace：`MANDATORY` 外层事务；头/item/edge 同事务；`effectKey` 正则 `^WMS:[0-9a-f]{64}$`；同键同摘要回放、异摘要 409；`effect_result=APPLIED`；补偿只追加。
- Scope Guard 82 已接受，但正式结构门禁仍缺 `zone_kind`、`zone.lifecycle_status`、`storage.lifecycle_status` 的产品 core/init 落地。
- 以上决定已于 2026-10-29 同步到 `work/inventory-p1-contract-20261027.md`（新增 C42—C45，修订 §5.2、§5.3）、`docs/inventory-management-design-20261026.md`（§4、§8、§11.1）、`docs/inventory-management-sql-upgrade-plan-20261026.md`（§3、§7）、`docs/inventory-management-web-crud-plan-20261026.md`（§3）和任务图新增的在途切片。

## 6. 未完成项

任务图 50 个节点：`done 13 / review 4 / in_progress 15 / pending 14 / blocked 4`（交接时状态，可能滞后于在途切片）。

- P1：合同仍 `review`；公共控制面与类型目录、升级断点/批重入、QUEUE 父任务与持久屏障在进行中。
- P2：空间版本解析与查询未完成；生命周期核心 `pending`；正式结构门禁字段未补。
- P3：属性目录与类型化规则预览 `pending`；发布 CAS 与归属绑定未实施，无真实 provider Bean。
- P4：trace 集成测试未跑；装载持久化未做；`MATERIAL_CHANGE` SAME_TX 原子事实 `pending`。
- P5：P5-01 至 P5-04 全部 `pending`；P5-05 真实父业务 `blocked`。
- P6：合并、后端整合与旧库承接、同版切换均 `pending`；P6-03 清空链 `blocked`。
- P7：规则/物料/业务中心/追踪页面 `pending`；受控写入 `blocked`；菜单权限与 PDA 入口未完成。
- 总验收 `INV-I-01` `pending`，`INV-I-02` `blocked`。

## 7. 阻碍与授权缺口

- 真实库写入、replace live、部署、commit/push 均无授权，所有证据仅限隔离库与本地 LAN。
- 旧库承接迁移未开始；`252CDED3…` 与 `D23BE8DA…` 的 init 差异语义待核对。
- 真实外部 provider（OAuth 等）未联调，现有仅为受控 stub。
- 端口 `18080`（后端）/`18000`（Web）在每次 LAN 前后确认释放；隔离 MySQL 容器卷用完即停。

## 8. 下一安全动作（按序）

1. 用根 Maven 槽执行 Confucius 的 trace 集成测试：`.\mvnw.cmd -pl iws-modules/iws-wms -am test "-Dtest=WmsMaterialTraceIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"`，核对 13 个 MySQL 场景与失败注入证据。
2. 主验收 Tesla 的空间菜单 SQL（当前 `967741CB…`/`3D5377D4…`）与 67 次矩阵，补 `report.md`，再同步 `iws_init_from_empty.sql`，之后用新包重跑 LAN。
3. 以 Confucius 实际接口组织 `INV-P4-04` SAME_TX 接线（领域事实 + trace + checkpoint + 平台事件同事务）。
4. 独立准备 `INV-P5-01` 目标 DDL 与隔离矩阵，可与 Java 适配并行。
5. 补 P2 正式结构门禁三字段，再谈正式发布门禁。

每片先声明写集与目录认领，避免多代理同时改 `core/init`、公共测试基类、同一 Mapper XML、POM、process 运行时或根 Maven。

## 9. 恢复核对清单

- 重新读取适用规约、P1 合同、任务图与 `next-slices.md`，确认无新指令覆盖。
- 对五个目录执行 `git status --short`，比对本文档基线，保留全部既有修改。
- 核对 core/init/JAR 哈希是否仍与本文档一致，不一致则视为新版本重新取证。
- 确认在途代理是否有未落盘改动；`interrupted` 代理恢复前先读其工作区与 `work/` 报告。
- 确认是否仍禁止真实库、部署与推送；未获新授权前保持隔离验证。

## suggested skills

- `C:/Users/yohmi/.codex/skills/implement-spec/SKILL.md`：继续 P4-04 SAME_TX、trace 集成等已授权实施。
- `C:/Users/yohmi/.codex/skills/sql-idempotent-upgrade/SKILL.md`：空间菜单 SQL 收尾、P5-01 目标 DDL、init 同步。
- `C:/Users/yohmi/.codex/skills/database-readonly-mcp/SKILL.md`：隔离 MySQL 结构与数据核验、只读连接配置。
- `C:/Users/yohmi/.codex/skills/code-review/SKILL.md`：对 Tesla/Confucius 交付做独立验收。
