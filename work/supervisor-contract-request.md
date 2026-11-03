# 监督切片窄接口协作请求

> 2026-10-22，SUPERVISOR 运行时实施切片。接收方：主代理 / IMMEDIATE 切片（Banach）。

IMMEDIATE 由 Banach 另切片，本切片不新增 Immediate* 类。以下端口拟在 com.innolux.iws.process.api 冻结，IMMEDIATE 只通过它取得监督轮次与 PLAN_SLOT，不直接改 iws_process_schedule_control。

## 最终冻结契约（不再 rename）

```java
public interface SupervisorAdmissionPort {
    SupervisorRoundLease openExclusive(long supervisorInstanceId, String commandKey, long maxDurationMs);
    String precheck(long supervisorInstanceId, PlanMode requiredMode);
    SupervisorSlot admit(long supervisorInstanceId, SupervisorRoundLease lease, long childInstanceId);
    boolean release(long supervisorInstanceId, long childInstanceId, String reason);
    boolean close(SupervisorRoundLease lease, RoundStatus status, String reason);
}

public interface ProcessControlIntentPort {
    List<ControlIntent> listPendingIntents(int limit);
    ControlIntent find(String scopeType, String scopeKey, String duty);
    ControlIntentAck recordResult(PlatformScopeResultCommand command);
    void ensureWorkerIntent(long scheduleId, long definitionVersionId);
    void wakeWorker(long scheduleId);
}

public record PlatformScopeResultCommand(String scopeType, String scopeKey, String duty,
                                         String action, long expectedRevision, boolean applied,
                                         String observedState, String platformJobId, String message) {
}
```

PlatformScopeResultCommand 为 9 字段；没有 epoch、没有 controlId。scopeType + scopeKey + duty 是稳定身份，action 必须原样使用 ControlIntent.action()。recordResult 在 expectedRevision CAS 后复核 action：applied 即消费该修订的 wake；TRIGGERED 只作为动作回执，不写入长期 observedState；失败保留原 observed 并置 wakePending=true。TRIGGER_ONCE 成功后下一次发现动作是 ENSURE_RUNNING（若实际观测仍未 RUNNING），不会每周期重复 TRIGGER_ONCE。

## 语义约束

- precheck 返回 null 表示可继续；拒绝码为 SUPERVISOR_PAUSED、MODE_MISMATCH、BUSY。
- openExclusive 在 DISPATCH 控制行上 CAS revision/epoch，已有他人活跃轮返回 null（映射 SUPERVISOR_BUSY）；不创建子实例、不占名额。
- SupervisorRoundLease.revision 是开轮时控制修订号，仅用于观测；同一轮内多次准入会推进 revision，因此 admit、close 以 roundNo + epoch + owner + roundStatus 围栏，不以开轮 revision 做等值条件。
- admit 在同一事务锁 DISPATCH 行，复核 epoch/owner/lease、暂停、mode、activePlanLimit，写唯一 HELD PLAN_SLOT，子实例 CREATED -> RUNNING 并 CAS instanceVersion。重复调用返回原结果；失败整体回滚。
- release 只允许 HELD -> RELEASED 一次；UNKNOWN/FAILED_HOLD 不释放。
- close 只接受当前 epoch+owner+open round，旧代次返回 false，不覆盖新轮。
- IMMEDIATE 在 LOCAL 提交后立即 close(COMPLETED)；子计划整个执行期间只持 PLAN_SLOT，监督 lease 不跨越业务驱动。
- 业务子实例创建仍归 InstanceService / IMMEDIATE 流程；先 precheck 再创建，admit 是最终原子门禁。

## 当前状态与阻塞

- 本切片已确认：现有 SupervisorTickServiceImpl 的轮次会在最后一个监督事件成功后被 PlanActivationService 聚合为 COMPLETED，下一轮无法认领；现有轮次也没有按候选生成独立 LOCAL、没有 epoch/owner 门禁、暂停会留下 OPEN。正在按上述契约重写五处独占文件并新增监督查询/控制服务。
- 构建协调：本切片不抢 Maven target。代码切片完成后会再次通知主代理安排构建窗口。
- 共享测试编译阻塞：iws-process/src/test/java/com/innolux/iws/process/runtime/EventRuntimeMysqlTest.java 导入 com.innolux.iws.process.api.ActionContext/ActionResult，但这两个类当前位于 process.runtime 包，api 下不存在同名类；该测试会导致模块 test-compile 失败。请 Event 内核负责人确认归属并修正，或明确由主代理统一处理。
