# iws-process 公共契约（冻结草案 2026-10-22）

> 本文件是 process 模块对 SQL、Web、job 的并行对齐基线。表名、字段名、枚举值和 HTTP 路径在实现中按此落地；业务动作、真实系统码、超时/名额等 P1 数值不由本模块猜定。

## 1. 模块与装配

- Maven：`com.innolux.iws:iws-process`，包根 `com.innolux.iws.process`。
- 开关：`iws.process.enabled=true` 才注册 process Bean/Controller；默认关闭。
- 依赖：`iws-endpoint` optional；运行时只通过 `com.innolux.iws.endpoint.catalog.EndpointCatalogReader` 读取目录并锁定 `catalogRevision`。
- 调度：process 只声明端口；job 调用 `ProcessSchedulerPort`，并实现 `SchedulerPlatformPort` 的平台适配。
- 调度开关：`iws.process.scheduler.enabled=true` 且 `snail-job.enabled=true` 由 job 侧判断；process 关闭调度仍保留配置、查询和 `wake_pending`。

## 2. 数据表

公共字段：除 `iws_process_action_audit` 外均继承 `create_dept/create_by/create_time/update_by/update_time`；有配置生命周期的表含 `del_flag`（`0` 存在、`1` 删除）。主键统一 `BIGINT`，ID 对前端以字符串输出。业务时间 `DATETIME(3)`。

### 2.1 `iws_business_schedule`

| 字段 | 类型 | 约束/含义 |
| --- | --- | --- |
| `id` | BIGINT PK | 业务调度 ID |
| `business_code` | VARCHAR(64) | UNIQUE，非空，稳定业务码 |
| `business_name` | VARCHAR(128) | 中文展示名 |
| `enabled` | TINYINT(1) | 停用只停新准入 |
| `fairness_window_size` | INT | 共享竞争 scope 的 K；启用 QUEUE 时要求 >0 |
| `revision` | BIGINT | 聚合乐观锁 |
| `remark` | VARCHAR(500) | 说明 |
| `del_flag` | CHAR(1) | 逻辑删除 |

### 2.2 `iws_business_schedule_plan_type`

`id` PK；`schedule_id` BIGINT；`plan_type` VARCHAR(64)；`plan_type_name` VARCHAR(128)；`priority` INT；`enabled` TINYINT(1)。UNIQUE(`schedule_id`,`plan_type`)。

### 2.3 `iws_process_definition`

`id` PK（versionId）；`plan_code` VARCHAR(64)；`plan_version` INT；`plan_type` VARCHAR(64)；`business_type` VARCHAR(64)；`business_schedule_code` VARCHAR(64)；`plan_name` VARCHAR(128)；`plan_role` VARCHAR(16)（BUSINESS/SUPERVISOR）；`status` VARCHAR(16)（DRAFT/ACTIVE/RETIRED）；`revision` BIGINT；`latest_key` VARCHAR(64) NULL；`active_key` VARCHAR(64) NULL；`self_system_code` VARCHAR(64)；`mode` VARCHAR(16) NULL（QUEUE/IMMEDIATE，仅监督）；`active_plan_limit` INT NULL；`fairness_window_size` INT NULL；`max_execution_ms` BIGINT NULL；`completion_policy` VARCHAR(24)；`exception_state_code` VARCHAR(64)；`round_batch_limit` INT NULL；`round_max_duration_ms` BIGINT NULL；`schedule_interval_ms` BIGINT NULL；`schedule_cron` VARCHAR(64) NULL；`job_timeout_ms` BIGINT NULL；`job_max_retry_times` INT NULL；`worker_batch_limit` INT NULL；`worker_max_duration_ms` BIGINT NULL；`lease_ms` BIGINT；`retry_max` INT；`retry_backoff_ms` BIGINT；`published_by` BIGINT；`published_at` DATETIME(3)；`published_summary` VARCHAR(500)；`description` VARCHAR(1000)；`del_flag` CHAR(1)。

索引：UNIQUE(`plan_code`,`plan_version`)；UNIQUE(`active_key`)；UNIQUE(`latest_key`)；INDEX(`plan_code`,`status`)。

### 2.4 `iws_process_system_policy`

`id` PK；`definition_version_id` BIGINT；`system_code` VARCHAR(64)；`posting_mode` VARCHAR(24)（AUTOMATIC/MANUAL_CONFIRM/BYPASS）；`first_confirm_policy` VARCHAR(32)；`failure_policy` VARCHAR(32)；`remark` VARCHAR(500)。UNIQUE(`definition_version_id`,`system_code`)。

### 2.5 `iws_process_state`

`id` PK；`definition_version_id`；`state_code` VARCHAR(64)；`display_name` VARCHAR(128)；`state_kind` VARCHAR(16)（START/NORMAL/END/EXCEPTION）；`validator_key` VARCHAR(128)；`validator_version` VARCHAR(32)；`condition_description` VARCHAR(1000)；`terminal` TINYINT(1)；`sort_order` INT。UNIQUE(`definition_version_id`,`state_code`)。

### 2.6 `iws_process_event_definition`

`id` PK；`definition_version_id`；`event_code` VARCHAR(64)；`display_name` VARCHAR(128)；`event_role` VARCHAR(16)（BUSINESS/DECISION/LOCAL）；`event_direction` VARCHAR(16)（INBOUND/OUTBOUND/INTERNAL）；`event_type_key` VARCHAR(128)；`event_type_version` VARCHAR(32)；`call_contract_key` VARCHAR(128) NULL；`call_contract_version` VARCHAR(32) NULL；`operation_kind` VARCHAR(32)（EXTERNAL_POSTING/QUERY/CONTROL/INBOUND_TRIGGER/RESULT_CALLBACK）；`local_action_key` VARCHAR(128) NULL；`local_action_version` VARCHAR(32) NULL；`effect_key` VARCHAR(128) NULL；`local_effect_key` VARCHAR(128) NULL；`system_code` VARCHAR(64)；`target_system_code` VARCHAR(64) NULL；`endpoint_ref` VARCHAR(128) NULL；`purpose` VARCHAR(32)；`completion_contract` VARCHAR(32)（BUSINESS_COMPLETION/ACCEPTANCE）；`source_state_code` VARCHAR(64)；`target_state_code` VARCHAR(64)；`enabled` TINYINT(1)；`skip_policy` VARCHAR(32)；`skip_contract_key` VARCHAR(128)；`recovery_for_event_code` VARCHAR(64) NULL；`priority` INT；`edit_policy` VARCHAR(24)；`graph_edit_policy` VARCHAR(24)；`locked_fields` VARCHAR(1000)；`remark` VARCHAR(500)。UNIQUE(`definition_version_id`,`event_code`)。

### 2.7 `iws_process_edge`

`id` PK；`definition_version_id`；`predecessor_event_code`；`successor_event_code`；`branch_key` VARCHAR(64)；`branch_kind` VARCHAR(16)（SEQUENTIAL/XOR/AND）；`condition_key` VARCHAR(128)；`condition_version` VARCHAR(32)；`default_branch` TINYINT(1)；`join_code` VARCHAR(64)；`priority` INT。UNIQUE(`definition_version_id`,`predecessor_event_code`,`successor_event_code`,`branch_key`)。

### 2.8 `iws_process_instance`

`id` PK；`plan_role`；`definition_version_id`；`definition_revision`；`schedule_id`；`plan_type`；`business_schedule_code`；`supervisor_instance_id`；`supervisor_key` VARCHAR(256) NULL；`business_task_key` VARCHAR(256) NULL；`business_type`；`business_id` VARCHAR(128)；`task_key` VARCHAR(128)；`schedule_scope` VARCHAR(128)；`mode_snapshot` VARCHAR(16)；`hold_start` TINYINT(1)；`first_admitted_at`；`plan_status`；`instance_priority` INT；`selected_root_set` TEXT；`input_hash` VARCHAR(128)`；`execution_deadline`；`round_no` BIGINT；`stop_requested` TINYINT(1)；`cancel_requested` TINYINT(1)；`exception_summary` VARCHAR(1000)；`started_at`；`ended_at`；`instance_version` BIGINT。

索引：UNIQUE(`supervisor_key`)（仅 SUPERVISOR 非空）；UNIQUE(`business_task_key`)（仅 BUSINESS 非空）；INDEX(`business_schedule_code`,`plan_status`,`instance_priority`)。

### 2.9 `iws_process_system_context`

`id` PK；`instance_id`；`system_code`；`posting_mode`；`first_confirmed` TINYINT(1)；`confirmed_by` BIGINT；`confirmed_name` VARCHAR(128)；`confirmed_at`；`revision` BIGINT。UNIQUE(`instance_id`,`system_code`)。

### 2.10 `iws_process_token`

`id` PK；`instance_id`；`token_role`（BRANCH/SCOPE）；`token_status`（ACTIVE/BLOCKED/FINISHED/NOT_SELECTED）；`state_code`；`decision_node_code`；`parent_scope_key` VARCHAR(256) NOT NULL；`scope_key` VARCHAR(256) NOT NULL；`branch_key` VARCHAR(128) NOT NULL；`decision_input_hash`；`rule_version`；`selected_keys` TEXT；`expected_keys` TEXT；`joined_keys` TEXT；`barrier_closed` TINYINT(1)；`blocked_event_id` BIGINT；`last_attempt_id` BIGINT；`token_version` BIGINT；`round_no` BIGINT。UNIQUE(`instance_id`,`scope_key`,`branch_key`)。

### 2.11 `iws_process_event`

`id` PK；`instance_id`；`token_id`；`definition_version_id`；`event_definition_id`；`event_code`；`event_key` VARCHAR(256)；`round_no` BIGINT；`branch_identity` VARCHAR(256)；`event_role`；`event_direction`；`system_code`；`call_contract_key`；`call_contract_version`；`local_action_key`；`local_action_version`；`effect_key`；`local_effect_key`；`source_state_code`；`target_state_code`；`event_status`；`wait_reason`；`outcome`；`effect_result`；`sent` TINYINT(1)；`payload_ready` TINYINT(1)；`input_hash`；`idempotency_key` VARCHAR(256)；`origin_outbound_event_id` BIGINT；`recovery_for_event_code`；`execution_purpose`；`priority` INT；`next_retry_time`；`retry_count` INT；`owner` VARCHAR(128)；`lease_epoch` BIGINT；`lease_until`；`event_version` BIGINT；`started_at`；`ended_at`；`last_attempt_id` BIGINT；`recovery_requested` TINYINT(1)；`recovery_deadline`；`error_code`；`error_message` VARCHAR(1000)。UNIQUE(`instance_id`,`event_key`)。

### 2.12 `iws_process_attempt`

`id` PK；`event_id`；`attempt_no` INT；`resume_from_attempt_id`；`checkpoint_status`；`sent` TINYINT(1)；`request_id` VARCHAR(64)；`input_hash`；`idempotency_key` VARCHAR(256)；`operation_version`；`local_action_version`；`local_effect_key` VARCHAR(256) NULL；`evidence_ref` VARCHAR(500)；`effect_result`；`error_code`；`error_message` VARCHAR(1000)；`source_state_code`；`target_state_code`；`owner`；`lease_epoch`；`execution_purpose`；`checkpoint_json` MEDIUMTEXT；`started_at`；`ended_at`；`transaction_id` VARCHAR(128)；`recorded_at`。UNIQUE(`event_id`,`attempt_no`)；INDEX(`event_id`,`local_effect_key`)。

### 2.13 `iws_process_trigger_receipt`

`id` PK；`caller_system_code`；`module_code`；`call_contract_key`；`call_contract_version`；`source_request_id` VARCHAR(128)；`payload_hash` VARCHAR(128)；`effect_key` VARCHAR(256)；`logical_call_key` VARCHAR(256)；`instance_id`；`event_id`；`external_task_id` VARCHAR(128)；`origin_outbound_event_id`；`purpose`；`canonical` TINYINT(1)；`processing_status`；`ack_summary` VARCHAR(500)；`evidence_summary` VARCHAR(1000)；`received_at`；`applied_at`。UNIQUE(`caller_system_code`,`call_contract_key`,`call_contract_version`,`source_request_id`)。

### 2.14 `iws_process_request_detail`

`id` PK；`request_id` VARCHAR(64)；`direction`（INBOUND/OUTBOUND）；`purpose`；`source_request_id`；`correlation_id`；`parent_request_id`；`peer_request_id`；`caller_system_code`；`receiver_system_code`；`module_code`；`contract_key`；`contract_version`；`effect_key`；`instance_id`；`event_id`；`attempt_id`；`receipt_id`；`http_status` INT；`result_code`；`effect_result`；`sent` TINYINT(1)；`request_summary` VARCHAR(2000)；`response_summary` VARCHAR(2000)；`started_at`；`ended_at`；`evidence_at`。INDEX(`event_id`,`direction`)。

### 2.15 `iws_process_action_audit`（追加式，无更新/删除）

`id` PK；`action_code`；`action_source`（USER/SYSTEM/SCHEDULER/EXTERNAL_CALLBACK）；`command_key`；`receipt_key` VARCHAR(256) NULL；`actor_user_id`；`actor_name`；`trace_id`；`schedule_id`；`definition_version_id`；`instance_id`；`token_id`；`event_id`；`attempt_id`；`supervisor_instance_id`；`round_no`；`dispatch_id`；`child_instance_id`；`request_id`；`before_status`；`before_version`；`after_status`；`after_version`；`result`；`reason` VARCHAR(1000)；`payload_summary` VARCHAR(2000)；`decision_summary` VARCHAR(2000)；`slot_before` INT；`slot_after` INT；`epoch` BIGINT；`control_revision` BIGINT；`platform_job_id` VARCHAR(128)；`job_batch_id` VARCHAR(128)；`occurred_at`；`duration_ms` BIGINT；`request_started_at`；`request_finished_at`；`client_ip`；`user_agent`；`entry_path`。UNIQUE(`receipt_key`)；INDEX(`instance_id`,`occurred_at`)；INDEX(`event_id`,`occurred_at`)。

### 2.16 `iws_process_schedule_control`

`id` PK；`scope_type`（BUSINESS/SUPERVISOR/PLAN/PLAN_SLOT/CALL）；`scope_key` VARCHAR(256)；`duty` VARCHAR(32)（DISPATCH/MANUAL/PLAN_SLOT/CALL_PERMIT）；`control_state`；`desired_state`；`observed_state`；`revision` BIGINT；`epoch` BIGINT；`owner`；`lease_until`；`wake_pending` TINYINT(1)；`round_no` BIGINT；`round_status`（OPEN/COMPLETED/FAILED）；`supervisor_instance_id`；`definition_version_id`；`child_instance_id`；`slot_state`（HELD/RELEASED）；`permit_state`（INTENT/IN_FLIGHT/ACCEPTED/WAIT_RESULT/RELEASED）；`resource_key`；`fairness_count` INT；`last_admitted_at`；`last_activity_at`；`error_message`。UNIQUE(`scope_type`,`scope_key`,`duty`)。

## 3. HTTP API（统一前缀 `/process`）

所有写命令含 `commandKey`、`expectedVersion`、`reason`；对象 ID/revision 为字符串。列表 GET + `PageQuery`，返回 `R<PageResult<T>>`；详情 `R<T>`。

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/schedule/page`、`/schedule/{id}`、`/schedule/options` | `process:schedule:list/query` |
| POST/PUT/DELETE | `/schedule`、`/schedule/{id}` | `process:schedule:add/edit/remove` |
| GET/PUT | `/schedule/{id}/plan-types` | `process:schedule:query/edit` |
| GET | `/schedule/{id}/supervisors` | `process:schedule:query` |
| GET | `/definition/page`、`/definition/{versionId}`、`/definition/{versionId}/edit-capabilities`、`/definition/options` | `process:definition:list/query` |
| POST | `/definition/draft` | `process:definition:add` |
| PUT | `/definition/{versionId}`（完整草稿原子保存） | `process:definition:edit` |
| PUT | `/definition/{versionId}/base`、`/states/{stateCode}`、`/events/{eventCode}`、`/system-policies` | `process:definition:edit` |
| DELETE | `/definition/{versionId}/events/{eventCode}`、`/definition/{versionId}` | `process:definition:edit/remove` |
| POST | `/definition/{versionId}/copy`、`/validate`、`/publish` | `process:definition:copy/publish` |
| GET | `/definition/{versionId}/external-systems` | `process:definition:query` |
| POST | `/definition/{versionId}/system-policies/preview` | `process:definition:query` |
| POST | `/instance/attach` | `process:instance:attach` |
| POST | `/instance/{id}/trigger` | `process:trigger:receive` |
| POST | `/instance/{id}/confirm-posting`、`/pause`、`/resume`、`/cancel`、`/hold-start`、`/release-start`、`/priority` | 对应 `process:instance:*` |
| GET | `/instance/{id}`、`/instance/{id}/chain`、`/instance/{id}/audit` | `process:instance:query/chain`、`process:audit:query` |
| POST | `/schedule/{id}/execute-immediate` | `process:instance:execute` |
| GET | `/schedule/{id}/commands/{commandKey}` | `process:instance:query` |
| POST | `/event/{eventId}/retry`、`/finalize`、`/revalidate`、`/priority` | 对应 `process:event:*` |
| GET | `/event/{eventId}`、`/attempts`、`/requests/{requestId}` | `process:event:query/request` |
| GET | `/execution/overview`、`/requests/page`、`/requests/{requestId}` | `process:execution:list`、`process:request:list/query` |
| GET | `/audit/page`、`/event/{eventId}/audit` | `process:audit:query` |
| GET | `/supervisor/{id}`、`/rounds`、`/dispatch-records` | `process:schedule:query` |
| POST | `/supervisor/{id}/pause`、`/resume`、`/restart-worker`、`/activate-version` | `process:schedule:pause/resume/restart/edit` |

### 关键 DTO

- `AttachTaskCommand(planType, businessType, businessId, taskKey, scheduleId, holdStart, inputHash, rootSet, commandKey)` → `AttachResult(instanceId, supervisorInstanceId, mode, queueStatus, eligible, blockReason, queueRank, instanceVersion)`。
- `ReceiveTriggerCommand(instanceId, callerSystemCode, moduleCode, callContractKey, callContractVersion, purpose, sourceRequestId, effectKey, payloadHash, payloadSummary, originOutboundEventId, externalTaskId)` → `TriggerReceiptResult(receiptId, eventId, accepted, duplicate, processingStatus)`。
- `ConfirmPostingCommand(instanceId, systemCodes, expectedVersion, commandKey, reason)` → `CommandResult(code, message, instanceId, revision, confirmedSystems)`。
- `RetryEventCommand(eventId, expectedVersion, commandKey, reason, recoveryOnly)`；`CancelPlanCommand(instanceId, expectedVersion, commandKey, reason)`。
- `ExecuteImmediateCommand(scheduleId, planType, taskKey, businessId, inputHash, commandKey)` → `R<ImmediateExecutionVO>`；`resultCode` 为 `SUCCEEDED/FAILED/FAILED_HOLD/BUSY/SUPERVISOR_BUSY/NOT_READY/SUPERVISOR_PAUSED/MODE_MISMATCH/RESULT_UNKNOWN/RESPONSE_TIMEOUT/PROCESSING`，附 `instanceId/queryUrl`。
- `DefinitionDraftBo(planCode, planName, versionId, planVersion, planType, businessType, businessScheduleCode, selfSystemCode, planRole, mode, activePlanLimit, fairnessWindowSize, maxExecutionMs, completionPolicy, exceptionStateCode, expectedVersion, states[], events[], edges[], systemPolicies[])`。

## 4. job 端口

process 公开、job 调用：

```java
public interface ProcessSchedulerPort {
    SupervisorTickResult tickSupervisor(long supervisorInstanceId, int batchLimit, long maxDurationMs);
    EventBatchResult runEventBatch(long scheduleId, int batchLimit, long maxDurationMs);
    ControlIntentSnapshot flushControlIntent(long supervisorInstanceId);
    PlatformResultAck recordPlatformResult(PlatformResultCommand command);
}

public record JobSpec(String executorName, String jobName, String bizId, String scheduleType,
                      String cronExpression, Long intervalMs, Long timeoutMs, Integer maxRetryTimes,
                      String paramsJson, String routeKey) {}
```

- `tickSupervisor`：续未收口轮次或创建下一有限轮次；只执行监督 DECISION/LOCAL 事件，返回 roundNo/status/候选数/选中数/耗时/错误。
- `runEventBatch`：只认领 QUEUE 业务事件，按 `planTypePriority/instancePriority/eventPriority/readyTime/id` 有界执行；返回处理数、成功数、失败数、跳过数、剩余是否可继续。IMMEDIATE 不进入此方法。
- `flushControlIntent`：读取 `iws_process_schedule_control` 的 desired/observed，返回 `desiredState/observedState/revision/platformJobId/jobSpec/lastError`；它只读，不代表平台已应用。
- `recordPlatformResult(PlatformResultCommand)`：job 在 `apply` 后按 `expectedControlRevision` CAS 回写 `applied/observedState/platformJobId/message`，返回 `recorded/conflict/currentRevision/observedState`。新控制修订已产生时返回冲突，不清除新意图。

job 实现、process 调用：

```java
public interface SchedulerPlatformPort {
    PlatformApplyResult apply(PlatformControlIntent intent);
}

public record PlatformControlIntent(String scopeType, String scopeKey, String action,
                                    long desiredRevision, JobSpec jobSpec) {}
public record PlatformApplyResult(boolean applied, long observedRevision, String observedState,
                                  String platformJobId, String message) {}
```

`jobSpec.scheduleType` 固定 `CRON/FIXED_RATE/ONE_SHOT`；`executorName` 为 `businessScheduleExecutor` 或 `planEventWorkerExecutor`；`maxRetryTimes` 领域默认 0；`paramsJson` 至少含 `supervisorInstanceId` 或 `scheduleId`、`batchLimit`、`maxDurationMs`。`action=ENSURE_RUNNING/ENSURE_STOPPED/TRIGGER_ONCE`。process 只持久控制意图；平台失败保留 `wake_pending` 和新 revision，下轮补做。

## 5. 执行与恢复不变量（摘要）

- INBOUND/OUTBOUND 恰一个 callContract；INTERNAL 恰一个 localAction；禁止 steps[]/多方向/多动作。
- 认领、SEND_INTENT、checkpoint、收口均按 `eventId + eventVersion + leaseEpoch + owner + PROCESSING` CAS；零行即停止。
- OUTBOUND T2 同事务检查取消/期限、用合同 `resourceKey` 做容量控制、写 SEND_INTENT、CALL control、requestId；无容量合同配置 fail-closed；INBOUND/INTERNAL 不建 CALL。
- 存在 SEND_INTENT 且无最终可信证据即 UNKNOWN；不得凭租约过期或一次未查到重发。
- 纯 Skip 零 operation/LocalAction/HTTP；BYPASS 仅 OUTBOUND 非本系统 EXTERNAL_POSTING。
- 命令回执以 `receipt_key` 唯一锚点写入 action_audit；后续结果只追加。
