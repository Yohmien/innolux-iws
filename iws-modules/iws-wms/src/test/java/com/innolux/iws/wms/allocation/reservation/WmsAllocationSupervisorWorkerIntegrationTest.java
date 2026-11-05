/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配计划
 * @Version      : V1.0
 * @Description  : 验证监督准入后 QUEUE worker 从持久业务任务执行分配三动作；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.process.api.AttachResult;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.EventBatchResult;
import com.innolux.iws.process.api.SupervisorTickResult;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessRequestDetailMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessSystemContextMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.innolux.iws.process.runtime.BranchSelector;
import com.innolux.iws.process.runtime.CallCapacityService;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.EndpointTargetResolver;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.EventUpdateService;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.PlanCandidateQueryService;
import com.innolux.iws.process.runtime.PlanEventWorkerServiceImpl;
import com.innolux.iws.process.runtime.PlanMaterializer;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RecoveryEvidenceService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.SupervisorAdmitQueueAction;
import com.innolux.iws.process.runtime.SupervisorCandidateDecisionAction;
import com.innolux.iws.process.runtime.SupervisorCandidateSelector;
import com.innolux.iws.process.runtime.SupervisorRoundMaterializer;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.runtime.SupervisorTickServiceImpl;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.process.service.PlanInstanceService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.wms.allocation.kernel.AllocationDemand;
import com.innolux.iws.wms.allocation.kernel.AllocationPolicy;
import com.innolux.iws.wms.allocation.kernel.AllocationProtection;
import com.innolux.iws.wms.allocation.kernel.AllocationQuantity;
import com.innolux.iws.wms.allocation.kernel.AllocationRequest;
import com.innolux.iws.wms.allocation.process.WmsAllocationCheckReleaseAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationEvaluateAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationLockStateValidator;
import com.innolux.iws.wms.allocation.process.WmsAllocationPlanDefinitionFactory;
import com.innolux.iws.wms.allocation.process.WmsAllocationReleaseAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationReleaseStateValidator;
import com.innolux.iws.wms.allocation.process.WmsAllocationReserveAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationSupervisorStateValidator;
import com.innolux.iws.wms.allocation.process.WmsAllocationVerifyAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationVerifyReleaseAction;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationEvidenceResult;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCheckCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseIdentity;
import com.innolux.iws.wms.allocation.release.service.WmsAllocationReleaseService;
import com.innolux.iws.wms.allocation.release.service.impl.WmsAllocationReleaseServiceImpl;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationBusinessContext;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReserveCommand;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationSnapshotInput;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilityEvaluator;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilitySnapshotService;
import com.innolux.iws.wms.allocation.reservation.service.impl.WmsAllocationReservationServiceImpl;
import com.innolux.iws.wms.allocation.reservation.space.WmsAllocationSpaceReaderImpl;
import com.innolux.iws.wms.catalog.WmsInventoryCatalogContract;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;
import com.innolux.iws.wms.load.change.domain.WmsLoadMutation;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeAction;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeCheckAction;
import com.innolux.iws.wms.load.change.process.WmsLoadChangePlanDefinitionFactory;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeStateValidator;
import com.innolux.iws.wms.load.change.service.WmsLoadChangeService;
import com.innolux.iws.wms.load.change.service.impl.WmsLoadChangeServiceImpl;
import com.innolux.iws.wms.process.WmsFrozenActionInputReader;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskTransitionCommand;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import com.innolux.iws.wms.task.service.impl.WmsBusinessTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 监督准入与 QUEUE worker 正式执行链隔离测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationPlanPublishTestContext.class)
class WmsAllocationSupervisorWorkerIntegrationTest {

    private static final long SCHEDULE_ID = 8_301L;

    private static final long ZONE_ID = 6_301L;

    private static final long STORAGE_ID = 7_301L;

    private static final long CHANNEL_ID = 8_311L;

    private static final long LOAD_STORAGE_ID = 7_302L;

    private static final long LOAD_CHANNEL_ID = 8_312L;

    private static final long LOAD_CARRIER_ID = 25_101L;

    private static final long LOAD_MATERIAL_ID = 25_102L;

    private static final long ALLOCATION_UNIT_ID = 1_301L;

    private static final String BUSINESS_TYPE = "INBOUND_FIXTURE";

    private static final String TASK_KEY = "TASK-SUPERVISOR-WORKER";

    private static final String LOAD_TASK_KEY = "TASK-SUPERVISOR-LOAD-WORKER";

    private static final String RELEASE_TASK_KEY = "TASK-SUPERVISOR-RELEASE-WORKER";

    private static final String RELEASE_LOCK_TASK_KEY = "TASK-SUPERVISOR-RELEASE-LOCK";

    private static final String INPUT_HASH = "6".repeat(64);

    private static final String LOAD_INPUT_HASH = "7".repeat(64);

    private static final String RELEASE_LOCK_INPUT_HASH = "5".repeat(64);

    private static final String RELEASE_INPUT_HASH = "9".repeat(64);

    private static final long RELEASE_BUSINESS_ID = 88_004L;

    private static final long RELEASE_BUSINESS_LINE_ID = 9_303L;

    private static final long RELEASE_LOCK_PLAN_INSTANCE_ID = 99_004L;

    private static final String WEIGHT = "WEIGHT";

    @Autowired
    private PlanDefinitionService definitionService;

    @Autowired
    private PlanInstanceService instanceService;

    @Autowired
    private SupervisorRuntimeService supervisorRuntimeService;

    @Autowired
    private ProcessProperties processProperties;

    @Autowired
    private ProcessAuditService auditService;

    @Autowired
    private ProcessObjectAuthorizer objectAuthorizer;

    @Autowired
    private RuntimeDao runtimeDao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator identifierGenerator;

    @Autowired
    private ProcessEventMapper eventMapper;

    @Autowired
    private ProcessAttemptMapper attemptMapper;

    @Autowired
    private ProcessTokenMapper tokenMapper;

    @Autowired
    private ProcessInstanceMapper instanceMapper;

    @Autowired
    private ProcessScheduleControlMapper controlMapper;

    @Autowired
    private ProcessTriggerReceiptMapper receiptMapper;

    @Autowired
    private ProcessRequestDetailMapper requestDetailMapper;

    @Autowired
    private ProcessSystemContextMapper contextMapper;

    @Autowired
    private PlanDefinitionMapper definitionMapper;

    @Autowired
    private BusinessScheduleMapper scheduleMapper;

    @BeforeEach
    void setUp() {
        cleanPersistence();
        seedCatalog();
        publish(WmsAllocationPlanDefinitionFactory.lockDraft(
            "WMS_TEST_ALLOCATION_LOCK",
            "WMS测试分配锁位",
            "INBOUND"
        ));
        publish(WmsAllocationPlanDefinitionFactory.supervisorLockDraft(
            "WMS_TEST_ALLOCATION_LOCK_SUPERVISOR",
            "WMS测试分配锁位监督",
            "INBOUND"
        ));
        publish(WmsLoadChangePlanDefinitionFactory.loadChangeDraft(
            "WMS_TEST_LOAD_CHANGE",
            "WMS测试装拆",
            "INBOUND"
        ));
        publish(WmsLoadChangePlanDefinitionFactory.supervisorLoadChangeDraft(
            "WMS_TEST_LOAD_CHANGE_SUPERVISOR",
            "WMS测试装拆监督",
            "INBOUND"
        ));
        publish(WmsAllocationPlanDefinitionFactory.releaseDraft(
            "WMS_TEST_ALLOCATION_RELEASE",
            "WMS测试分配释放",
            "INBOUND"
        ));
        publish(WmsAllocationPlanDefinitionFactory.supervisorReleaseDraft(
            "WMS_TEST_ALLOCATION_RELEASE_SUPERVISOR",
            "WMS测试分配释放监督",
            "INBOUND"
        ));
        processProperties.getScheduler().setEnabled(true);
        seedAllocationSpace();
    }

    @Test
    void supervisorAdmitsThenQueueWorkerExecutesPersistentAllocationChain() {
        AttachResult attached = instanceService.attach(new AttachTaskCommand(
            SCHEDULE_ID,
            WmsAllocationPlanDefinitionFactory.PLAN_TYPE_LOCK,
            BUSINESS_TYPE,
            "88001",
            TASK_KEY,
            false,
            INPUT_HASH,
            List.of(),
            "ATTACH-SUPERVISOR-WORKER"
        ));
        long businessInstanceId = Long.parseLong(attached.instanceId());
        WmsAllocationReserveCommand command = command(businessInstanceId);

        WmsBusinessTaskService taskService = businessTaskService();
        WmsBusinessTaskSnapshot task = taskService.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                command.business().businessType(),
                command.business().businessId(),
                command.business().businessLineId(),
                command.business().allocationUnitId(),
                command.business().roundNo(),
                "EXECUTE",
                command.business().taskKey(),
                WmsAllocationPlanDefinitionFactory.PLAN_TYPE_LOCK,
                businessInstanceId,
                command.inputHash(),
                jsonMapper.writeValueAsString(command),
                9_001L,
                9_101L
            )
        );
        WmsBusinessTaskSnapshot runningTask = taskService.transition(new WmsBusinessTaskTransitionCommand(
            task.taskId(), task.revision(), "RUNNING", 9_001L, 9_101L));

        ProcessInstance businessInstance = runtimeDao.instance(businessInstanceId);
        assertThat(businessInstance.getInputHash()).isEqualTo(INPUT_HASH);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            materializer(eligibility()).materialize(businessInstance));
        com.innolux.iws.process.domain.ProcessEvent materializedEvaluate =
            eventMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<com.innolux.iws.process.domain.ProcessEvent>lambdaQuery()
                .eq(com.innolux.iws.process.domain.ProcessEvent::getInstanceId, businessInstanceId)
                .eq(com.innolux.iws.process.domain.ProcessEvent::getEventCode,
                    WmsAllocationEvaluateAction.ACTION_KEY));
        assertThat(materializedEvaluate.getEventDirection().name()).isEqualTo("INTERNAL");
        assertThat(materializedEvaluate.getPayloadReady()).isTrue();
        assertThat(materializedEvaluate.getInputHash()).isEqualTo(INPUT_HASH);
        assertThat(jdbcTemplate.queryForObject(
            "select input_hash from iws_process_instance where id = ?",
            String.class,
            businessInstanceId
        )).isEqualTo(INPUT_HASH);
        assertThat(jdbcTemplate.queryForObject("""
            select event_direction from iws_process_event
            where instance_id = ? and event_code = 'EVALUATE_ALLOCATION'
            """, String.class, businessInstanceId)).isEqualTo("INTERNAL");
        assertThat(jdbcTemplate.queryForObject("""
            select input_hash from iws_process_event
            where instance_id = ? and event_code = 'EVALUATE_ALLOCATION'
            """, String.class, businessInstanceId)).isEqualTo(INPUT_HASH);

        long supervisorInstanceId = jdbcTemplate.queryForObject(
            "select supervisor_instance_id from iws_process_instance where id = ?",
            Long.class,
            businessInstanceId
        );
        SupervisorTickResult tick = supervisorTick().tick(supervisorInstanceId, 8, 20_000L);

        assertThat(tick.roundStatus()).isEqualTo("COMPLETED");
        assertThat(tick.selectedCount()).isEqualTo(1);
        assertThat(tick.eventCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "select plan_status from iws_process_instance where id = ?",
            String.class,
            businessInstanceId
        )).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from iws_process_schedule_control
            where supervisor_instance_id = ? and scope_type = 'PLAN_SLOT'
              and child_instance_id = ? and slot_state = 'HELD'
            """, Integer.class, supervisorInstanceId, businessInstanceId)).isEqualTo(1);

        PlanEventWorkerServiceImpl worker = worker();
        EventBatchResult evaluate = worker.runBatch(SCHEDULE_ID, 4, 20_000L);
        EventBatchResult reserve = worker.runBatch(SCHEDULE_ID, 4, 20_000L);
        EventBatchResult verify = worker.runBatch(SCHEDULE_ID, 4, 20_000L);

        assertThat(evaluate.processed()).isEqualTo(1);
        assertThat(evaluate.succeeded())
            .as(eventDiagnostic(businessInstanceId, WmsAllocationEvaluateAction.ACTION_KEY))
            .isEqualTo(1);
        assertThat(reserve.processed()).isEqualTo(1);
        assertThat(reserve.succeeded()).isEqualTo(1);
        assertThat(verify.processed()).isEqualTo(1);
        assertThat(verify.succeeded()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from iws_process_event
            where instance_id = ? and event_status = 'SUCCESS'
              and event_code in ('EVALUATE_ALLOCATION', 'RESERVE_STORAGE', 'VERIFY_ALLOCATION')
            """, Integer.class, businessInstanceId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
            select attempt.evidence_ref from iws_process_attempt attempt
            join iws_process_event event on event.id = attempt.event_id
            where event.instance_id = ? order by attempt.id
            """, String.class, businessInstanceId))
            .hasSize(3)
            .allMatch(value -> value.startsWith("wms-business-task:" + runningTask.taskId()));
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_allocation_lock
            where plan_instance_id = ? and status = 'LOCKED'
            """, Integer.class, businessInstanceId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select plan_status from iws_process_instance where id = ?",
            String.class,
            businessInstanceId
        )).isEqualTo("COMPLETED");
        assertThat(taskService.get(runningTask.taskId()).status()).isEqualTo("RUNNING");
    }

    @Test
    void supervisorAdmitsThenQueueWorkerChecksAndAppliesPersistentLoadChange() {
        seedLoadChangeData();
        AttachResult attached = instanceService.attach(new AttachTaskCommand(
            SCHEDULE_ID,
            WmsLoadChangePlanDefinitionFactory.PLAN_TYPE_LOAD_CHANGE,
            BUSINESS_TYPE,
            "88003",
            LOAD_TASK_KEY,
            false,
            LOAD_INPUT_HASH,
            List.of(),
            "ATTACH-LOAD-SUPERVISOR-WORKER"
        ));
        long businessInstanceId = Long.parseLong(attached.instanceId());
        WmsLoadChangeCommand command = loadChangeCommand(businessInstanceId);

        WmsBusinessTaskService taskService = businessTaskService();
        WmsBusinessTaskSnapshot task = taskService.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                command.businessType(),
                command.businessId(),
                command.businessLineId(),
                1_303L,
                0,
                "EXECUTE",
                command.taskKey(),
                WmsLoadChangePlanDefinitionFactory.PLAN_TYPE_LOAD_CHANGE,
                businessInstanceId,
                command.inputHash(),
                jsonMapper.writeValueAsString(command),
                9_001L,
                9_101L
            )
        );
        WmsBusinessTaskSnapshot runningTask = taskService.transition(
            new WmsBusinessTaskTransitionCommand(
                task.taskId(), task.revision(), "RUNNING", 9_001L, 9_101L));

        ProcessInstance businessInstance = runtimeDao.instance(businessInstanceId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            materializer(eligibility()).materialize(businessInstance));
        com.innolux.iws.process.domain.ProcessEvent materializedCheck =
            eventMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<com.innolux.iws.process.domain.ProcessEvent>lambdaQuery()
                .eq(com.innolux.iws.process.domain.ProcessEvent::getInstanceId, businessInstanceId)
                .eq(com.innolux.iws.process.domain.ProcessEvent::getEventCode,
                    WmsLoadChangeCheckAction.ACTION_KEY));
        assertThat(materializedCheck.getPayloadReady()).isTrue();
        assertThat(materializedCheck.getInputHash()).isEqualTo(LOAD_INPUT_HASH);

        long supervisorInstanceId = jdbcTemplate.queryForObject(
            "select supervisor_instance_id from iws_process_instance where id = ?",
            Long.class,
            businessInstanceId
        );
        SupervisorTickResult tick = supervisorTick().tick(supervisorInstanceId, 8, 20_000L);
        assertThat(tick.roundStatus()).isEqualTo("COMPLETED");
        assertThat(tick.selectedCount()).isEqualTo(1);

        PlanEventWorkerServiceImpl worker = worker();
        EventBatchResult check = worker.runBatch(SCHEDULE_ID, 4, 20_000L);
        assertThat(check.processed()).isEqualTo(1);
        assertThat(check.succeeded())
            .as(eventDiagnostic(businessInstanceId, WmsLoadChangeCheckAction.ACTION_KEY))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_load_relation
            where child_node_id = ? and parent_node_id = ?
            """, Integer.class, LOAD_MATERIAL_ID, LOAD_CARRIER_ID)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_occupancy_effect where effect_key = ?
            """, Integer.class, command.effectKey())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select json_unquote(json_extract(a.checkpoint_json, '$.status'))
            from iws_process_attempt a
            join iws_process_event e on e.id = a.event_id
            where e.instance_id = ? and e.event_code = ?
            """, String.class, businessInstanceId, WmsLoadChangeCheckAction.ACTION_KEY))
            .isEqualTo("CHECKED");

        EventBatchResult apply = worker.runBatch(SCHEDULE_ID, 4, 20_000L);
        assertThat(apply.processed()).isEqualTo(1);
        assertThat(apply.succeeded())
            .as(eventDiagnostic(businessInstanceId, WmsLoadChangeAction.ACTION_KEY))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from iws_process_event
            where instance_id = ? and event_status = 'SUCCESS'
              and event_code in ('CHECK_LOAD_CHANGE', 'APPLY_LOAD_CHANGE')
            """, Integer.class, businessInstanceId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
            select attempt.evidence_ref from iws_process_attempt attempt
            join iws_process_event event on event.id = attempt.event_id
            where event.instance_id = ? order by attempt.id
            """, String.class, businessInstanceId))
            .hasSize(2)
            .allMatch(value -> value.startsWith("wms-business-task:" + runningTask.taskId()));
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_load_relation where child_node_id = ?
            """, Integer.class, LOAD_MATERIAL_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_storage_info
            where root_node_id = ? and storage_id = ?
            """, Integer.class, LOAD_MATERIAL_ID, LOAD_STORAGE_ID)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_storage_occupancy
            where storage_id = ? and status = 'ACTIVE'
            """, Integer.class, LOAD_STORAGE_ID)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_load_relation_history where effect_key = ?
            """, Integer.class, command.effectKey())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select json_unquote(json_extract(a.checkpoint_json, '$.status'))
            from iws_process_attempt a
            join iws_process_event e on e.id = a.event_id
            where e.instance_id = ? and e.event_code = ?
            """, String.class, businessInstanceId, WmsLoadChangeAction.ACTION_KEY))
            .isEqualTo("APPLIED");
        assertThat(jdbcTemplate.queryForObject(
            "select plan_status from iws_process_instance where id = ?",
            String.class,
            businessInstanceId
        )).isEqualTo("COMPLETED");
        assertThat(taskService.get(runningTask.taskId()).status()).isEqualTo("RUNNING");
    }

    @Test
    void supervisorAdmitsThenQueueWorkerExecutesPersistentReleaseChain() {
        WmsAllocationReserveCommand lockCommand = releaseLockCommand();
        WmsAllocationReservationService reservationService = reservationService();
        var reserved = new TransactionTemplate(transactionManager).execute(status ->
            reservationService.reserve(lockCommand));
        assertThat(reserved).isNotNull();
        assertThat(reserved.allocationLockIds()).hasSize(1);

        AttachResult attached = instanceService.attach(new AttachTaskCommand(
            SCHEDULE_ID,
            WmsAllocationPlanDefinitionFactory.PLAN_TYPE_RELEASE,
            BUSINESS_TYPE,
            String.valueOf(RELEASE_BUSINESS_ID),
            RELEASE_TASK_KEY,
            false,
            RELEASE_INPUT_HASH,
            List.of(),
            "ATTACH-RELEASE-SUPERVISOR-WORKER"
        ));
        long businessInstanceId = Long.parseLong(attached.instanceId());
        WmsAllocationReleaseIdentity identity = releaseIdentity(businessInstanceId);
        WmsAllocationReleaseCheckCommand checkCommand =
            new WmsAllocationReleaseCheckCommand(
                identity,
                "NO_EFFECT",
                "evidence://release/worker-no-effect",
                reserved.allocationLockIds()
            );
        WmsAllocationReleaseService releaseService = releaseService();
        WmsAllocationEvidenceResult evidence =
            new TransactionTemplate(transactionManager).execute(status ->
                releaseService.checkEvidence(checkCommand));
        assertThat(evidence).isNotNull();
        WmsAllocationReleaseCommand command = releaseCommand(identity, evidence);

        WmsBusinessTaskService taskService = businessTaskService();
        WmsBusinessTaskSnapshot task = taskService.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                identity.businessType(),
                identity.businessId(),
                identity.businessLineId(),
                identity.allocationUnitId(),
                identity.roundNo(),
                "EXECUTE",
                identity.taskKey(),
                WmsAllocationPlanDefinitionFactory.PLAN_TYPE_RELEASE,
                businessInstanceId,
                command.inputHash(),
                jsonMapper.writeValueAsString(command),
                9_001L,
                9_101L
            )
        );
        WmsBusinessTaskSnapshot runningTask = taskService.transition(
            new WmsBusinessTaskTransitionCommand(
                task.taskId(), task.revision(), "RUNNING", 9_001L, 9_101L));

        ProcessInstance businessInstance = runtimeDao.instance(businessInstanceId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            materializer(eligibility()).materialize(businessInstance));
        com.innolux.iws.process.domain.ProcessEvent materializedCheck =
            eventMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<com.innolux.iws.process.domain.ProcessEvent>lambdaQuery()
                .eq(com.innolux.iws.process.domain.ProcessEvent::getInstanceId, businessInstanceId)
                .eq(com.innolux.iws.process.domain.ProcessEvent::getEventCode,
                    WmsAllocationCheckReleaseAction.ACTION_KEY));
        assertThat(materializedCheck.getPayloadReady()).isTrue();
        assertThat(materializedCheck.getInputHash()).isEqualTo(RELEASE_INPUT_HASH);

        long supervisorInstanceId = jdbcTemplate.queryForObject(
            "select supervisor_instance_id from iws_process_instance where id = ?",
            Long.class,
            businessInstanceId
        );
        SupervisorTickResult tick = supervisorTick().tick(
            supervisorInstanceId, 8, 20_000L);
        assertThat(tick.roundStatus()).isEqualTo("COMPLETED");
        assertThat(tick.selectedCount()).isOne();

        PlanEventWorkerServiceImpl worker = worker();
        EventBatchResult check = worker.runBatch(SCHEDULE_ID, 4, 20_000L);
        EventBatchResult release = worker.runBatch(SCHEDULE_ID, 4, 20_000L);
        EventBatchResult verify = worker.runBatch(SCHEDULE_ID, 4, 20_000L);

        assertThat(check.processed()).isOne();
        assertThat(check.succeeded())
            .as(eventDiagnostic(businessInstanceId, WmsAllocationCheckReleaseAction.ACTION_KEY))
            .isOne();
        assertThat(release.processed()).isOne();
        assertThat(release.succeeded())
            .as(eventDiagnostic(businessInstanceId, WmsAllocationReleaseAction.ACTION_KEY))
            .isOne();
        assertThat(verify.processed()).isOne();
        assertThat(verify.succeeded())
            .as(eventDiagnostic(businessInstanceId, WmsAllocationVerifyReleaseAction.ACTION_KEY))
            .isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from iws_process_event
            where instance_id = ? and event_status = 'SUCCESS'
              and event_code in (
                'CHECK_RELEASE_EVIDENCE',
                'RELEASE_ALLOCATION_LOCK',
                'VERIFY_RELEASE'
              )
            """, Integer.class, businessInstanceId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
            select attempt.evidence_ref from iws_process_attempt attempt
            join iws_process_event event on event.id = attempt.event_id
            where event.instance_id = ? order by attempt.id
            """, String.class, businessInstanceId))
            .hasSize(3)
            .allMatch(value -> value.startsWith(
                "wms-business-task:" + runningTask.taskId()));
        assertThat(jdbcTemplate.queryForObject("""
            select status from wms_allocation_lock where allocation_lock_id = ?
            """, String.class, reserved.allocationLockIds().getFirst()))
            .isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject("""
            select effect_result from wms_allocation_lock where allocation_lock_id = ?
            """, String.class, reserved.allocationLockIds().getFirst()))
            .isEqualTo("NO_EFFECT");
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_allocation_release
            where allocation_lock_id = ? and plan_instance_id = ?
            """, Integer.class, reserved.allocationLockIds().getFirst(),
            businessInstanceId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*)
            from wms_allocation_lock lock_row
            join wms_allocation_release release_row
              on release_row.release_id = lock_row.terminal_release_id
             and release_row.effect_key = lock_row.terminal_effect_key
            where lock_row.allocation_lock_id = ?
            """, Integer.class, reserved.allocationLockIds().getFirst())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select status from wms_access_channel_lock
            where channel_lock_id = (
              select channel_lock_id from wms_allocation_lock
              where allocation_lock_id = ?
            )
            """, String.class, reserved.allocationLockIds().getFirst()))
            .isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_storage_occupancy
            where storage_id = ? and status = 'ACTIVE'
            """, Integer.class, STORAGE_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select json_unquote(json_extract(a.checkpoint_json, '$.status'))
            from iws_process_attempt a
            join iws_process_event e on e.id = a.event_id
            where e.instance_id = ? and e.event_code = ?
            """, String.class, businessInstanceId,
            WmsAllocationVerifyReleaseAction.ACTION_KEY)).isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject(
            "select plan_status from iws_process_instance where id = ?",
            String.class,
            businessInstanceId
        )).isEqualTo("COMPLETED");
        assertThat(taskService.get(runningTask.taskId()).status()).isEqualTo("RUNNING");
    }

    @Test
    void failedApplyRollsBackAllLoadFactsAndRetriesWithSameFrozenTask() {
        seedLoadChangeData();
        AttachResult attached = instanceService.attach(new AttachTaskCommand(
            SCHEDULE_ID,
            WmsLoadChangePlanDefinitionFactory.PLAN_TYPE_LOAD_CHANGE,
            BUSINESS_TYPE,
            "88003",
            LOAD_TASK_KEY,
            false,
            LOAD_INPUT_HASH,
            List.of(),
            "ATTACH-LOAD-ROLLBACK"
        ));
        long businessInstanceId = Long.parseLong(attached.instanceId());
        WmsLoadChangeCommand command = loadChangeCommand(businessInstanceId);
        WmsBusinessTaskService taskService = businessTaskService();
        WmsBusinessTaskSnapshot task = taskService.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                command.businessType(),
                command.businessId(),
                command.businessLineId(),
                1_304L,
                0,
                "EXECUTE",
                command.taskKey(),
                WmsLoadChangePlanDefinitionFactory.PLAN_TYPE_LOAD_CHANGE,
                businessInstanceId,
                command.inputHash(),
                jsonMapper.writeValueAsString(command),
                9_001L,
                9_101L
            )
        );
        WmsBusinessTaskSnapshot runningTask = taskService.transition(
            new WmsBusinessTaskTransitionCommand(
                task.taskId(), task.revision(), "RUNNING", 9_001L, 9_101L));

        ProcessInstance businessInstance = runtimeDao.instance(businessInstanceId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            materializer(eligibility()).materialize(businessInstance));
        long supervisorInstanceId = jdbcTemplate.queryForObject(
            "select supervisor_instance_id from iws_process_instance where id = ?",
            Long.class,
            businessInstanceId
        );
        assertThat(supervisorTick().tick(supervisorInstanceId, 8, 20_000L).selectedCount()).isOne();

        PlanEventWorkerServiceImpl worker = worker();
        EventBatchResult check = worker.runBatch(SCHEDULE_ID, 4, 20_000L);
        assertThat(check.succeeded()).isOne();

        // CHECK captured capacity 2. Make APPLY fail after CHECK while retaining the frozen input.
        jdbcTemplate.update("""
            update wms_storage_capacity
            set capacity_value = 1, capacity_version = capacity_version + 1
            where storage_id = ? and dimension_key = 'TOP_LEVEL_LOAD' and active_flag = 1
            """, LOAD_STORAGE_ID);
        EventBatchResult failedApply = worker.runBatch(SCHEDULE_ID, 4, 20_000L);
        assertThat(failedApply.processed()).isOne();
        assertThat(failedApply.succeeded()).isZero();
        assertThat(failedApply.failed()).isOne();

        assertThat(jdbcTemplate.queryForObject("""
            select event_status from iws_process_event
            where instance_id = ? and event_code = ?
            """, String.class, businessInstanceId, WmsLoadChangeAction.ACTION_KEY))
            .isEqualTo("RETRY_WAIT");
        assertThat(jdbcTemplate.queryForObject("""
            select error_message from iws_process_event
            where instance_id = ? and event_code = ?
            """, String.class, businessInstanceId, WmsLoadChangeAction.ACTION_KEY))
            .contains("容量不足");
        assertThat(jdbcTemplate.queryForObject("""
            select a.checkpoint_status
            from iws_process_attempt a
            join iws_process_event e on e.id = a.event_id
            where e.instance_id = ? and e.event_code = ?
            order by a.id desc limit 1
            """, String.class, businessInstanceId, WmsLoadChangeAction.ACTION_KEY))
            .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_load_relation
            where child_node_id = ? and parent_node_id = ?
            """, Integer.class, LOAD_MATERIAL_ID, LOAD_CARRIER_ID)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_storage_info
            where root_node_id = ? and storage_id = ?
            """, Integer.class, LOAD_MATERIAL_ID, LOAD_STORAGE_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_storage_occupancy
            where storage_id = ? and status = 'ACTIVE'
            """, Integer.class, LOAD_STORAGE_ID)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_load_relation_history where effect_key = ?
            """, Integer.class, command.effectKey())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_occupancy_effect where effect_key = ?
            """, Integer.class, command.effectKey())).isZero();

        jdbcTemplate.update("""
            update wms_storage_capacity
            set capacity_value = 2, capacity_version = capacity_version + 1
            where storage_id = ? and dimension_key = 'TOP_LEVEL_LOAD' and active_flag = 1
            """, LOAD_STORAGE_ID);
        // Testcontainers MySQL uses UTC while the application writes Asia/Shanghai retry times.
        jdbcTemplate.update("""
            update iws_process_event
            set next_retry_time = date_sub(current_timestamp(3), interval 1 second)
            where instance_id = ? and event_code = ? and event_status = 'RETRY_WAIT'
            """, businessInstanceId, WmsLoadChangeAction.ACTION_KEY);
        EventBatchResult retriedApply = worker.runBatch(SCHEDULE_ID, 4, 20_000L);
        assertThat(retriedApply.processed()).isOne();
        assertThat(retriedApply.succeeded()).isOne();

        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from iws_process_attempt a
            join iws_process_event e on e.id = a.event_id
            where e.instance_id = ? and e.event_code = ?
            """, Integer.class, businessInstanceId, WmsLoadChangeAction.ACTION_KEY)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
            select json_unquote(json_extract(a.checkpoint_json, '$.status'))
            from iws_process_attempt a
            join iws_process_event e on e.id = a.event_id
            where e.instance_id = ? and e.event_code = ?
            order by a.id desc limit 1
            """, String.class, businessInstanceId, WmsLoadChangeAction.ACTION_KEY))
            .isEqualTo("APPLIED");
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_load_relation where child_node_id = ?
            """, Integer.class, LOAD_MATERIAL_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_storage_info
            where root_node_id = ? and storage_id = ?
            """, Integer.class, LOAD_MATERIAL_ID, LOAD_STORAGE_ID)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_storage_occupancy
            where storage_id = ? and status = 'ACTIVE'
            """, Integer.class, LOAD_STORAGE_ID)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_load_relation_history where effect_key = ?
            """, Integer.class, command.effectKey())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_occupancy_effect where effect_key = ?
            """, Integer.class, command.effectKey())).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "select plan_status from iws_process_instance where id = ?",
            String.class,
            businessInstanceId
        )).isEqualTo("COMPLETED");
        assertThat(taskService.get(runningTask.taskId()).status()).isEqualTo("RUNNING");
    }

    private WmsLoadChangeCommand loadChangeCommand(long planInstanceId) {
        return new WmsLoadChangeCommand(
            "WMS:" + LOAD_INPUT_HASH,
            LOAD_INPUT_HASH,
            List.of(new WmsLoadMutation(
                LOAD_MATERIAL_ID,
                LOAD_CARRIER_ID,
                1,
                null,
                "STORAGE",
                LOAD_STORAGE_ID
            )),
            LocalDateTime.now(),
            "worker装拆验证",
            "evidence://load-change/worker",
            "IWS",
            "REQUEST-LOAD-SUPERVISOR-WORKER",
            BUSINESS_TYPE,
            "入库worker验证",
            88_003L,
            "BIZ-SUPERVISOR-LOAD-WORKER",
            9_102L,
            LOAD_TASK_KEY,
            planInstanceId,
            "1",
            null,
            null,
            9_001L,
            9_101L
        );
    }

    private WmsAllocationReserveCommand command(long planInstanceId) {
        return new WmsAllocationReserveCommand(
            new WmsAllocationBusinessContext(
                BUSINESS_TYPE,
                "入库worker验证",
                88_001L,
                "BIZ-SUPERVISOR-WORKER",
                9_101L,
                ALLOCATION_UNIT_ID,
                1,
                TASK_KEY,
                planInstanceId
            ),
            "WMS:" + INPUT_HASH,
            INPUT_HASH,
            401L,
            402L,
            "b".repeat(64),
            "c".repeat(64),
            List.of(new WmsAllocationSnapshotInput(
                STORAGE_ID,
                true,
                false,
                true,
                false,
                false,
                AllocationProtection.NONE,
                Map.of(WEIGHT, new BigDecimal("0.000")),
                List.of(),
                Set.of(),
                0
            )),
            new AllocationRequest(
                new AllocationDemand(
                    ALLOCATION_UNIT_ID,
                    Map.of(WEIGHT, new AllocationQuantity(
                        new BigDecimal("10.000"), "KG", 3)),
                    0
                ),
                new AllocationPolicy(List.of(WEIGHT))
            )
        );
    }

    private WmsAllocationReserveCommand releaseLockCommand() {
        return new WmsAllocationReserveCommand(
            new WmsAllocationBusinessContext(
                BUSINESS_TYPE,
                "入库worker释放验证",
                RELEASE_BUSINESS_ID,
                "BIZ-SUPERVISOR-RELEASE-WORKER",
                RELEASE_BUSINESS_LINE_ID,
                ALLOCATION_UNIT_ID,
                1,
                RELEASE_LOCK_TASK_KEY,
                RELEASE_LOCK_PLAN_INSTANCE_ID
            ),
            "WMS:" + RELEASE_LOCK_INPUT_HASH,
            RELEASE_LOCK_INPUT_HASH,
            401L,
            402L,
            "b".repeat(64),
            "c".repeat(64),
            List.of(new WmsAllocationSnapshotInput(
                STORAGE_ID,
                true,
                false,
                true,
                false,
                false,
                AllocationProtection.NONE,
                Map.of(WEIGHT, new BigDecimal("0.000")),
                List.of(),
                Set.of(),
                0
            )),
            new AllocationRequest(
                new AllocationDemand(
                    ALLOCATION_UNIT_ID,
                    Map.of(WEIGHT, new AllocationQuantity(
                        new BigDecimal("10.000"), "KG", 3)),
                    0
                ),
                new AllocationPolicy(List.of(WEIGHT))
            )
        );
    }

    private WmsAllocationReleaseIdentity releaseIdentity(long planInstanceId) {
        return new WmsAllocationReleaseIdentity(
            BUSINESS_TYPE,
            "入库worker释放验证",
            RELEASE_BUSINESS_ID,
            "BIZ-SUPERVISOR-RELEASE-WORKER",
            RELEASE_BUSINESS_LINE_ID,
            ALLOCATION_UNIT_ID,
            1,
            RELEASE_TASK_KEY,
            RELEASE_LOCK_TASK_KEY,
            planInstanceId
        );
    }

    private WmsAllocationReleaseCommand releaseCommand(
        WmsAllocationReleaseIdentity identity,
        WmsAllocationEvidenceResult evidence
    ) {
        return new WmsAllocationReleaseCommand(
            identity,
            "WMS:" + RELEASE_INPUT_HASH,
            RELEASE_INPUT_HASH,
            evidence.evidenceHash(),
            evidence.evidenceRevision(),
            "evidence://release/worker-no-effect",
            evidence.expectations(),
            LocalDateTime.now(),
            "worker无外部效果释放",
            "IWS",
            "REQUEST-SUPERVISOR-RELEASE-WORKER",
            90_004L,
            90_005L,
            9_001L,
            9_101L
        );
    }

    private String eventDiagnostic(long businessInstanceId, String eventCode) {
        return jdbcTemplate.queryForObject("""
            select concat(e.event_status, '|', coalesce(e.error_code, ''), '|',
                          coalesce(e.error_message, ''), '|',
                          coalesce(a.error_code, ''), '|', coalesce(a.error_message, ''))
            from iws_process_event e
            left join iws_process_attempt a on a.id = e.last_attempt_id
            where e.instance_id = ? and e.event_code = ?
            """, String.class, businessInstanceId, eventCode);
    }

    private SupervisorTickServiceImpl supervisorTick() {
        EventEligibilityService eligibility = eligibility();
        CapabilityRegistry registry = registry();
        EventMutationService mutation = eventMutation(eligibility, registry);
        EventUpdateService update = new EventUpdateService(
            runtimeDao,
            mutation,
            activation(eligibility, registry),
            registry,
            mock(EndpointTargetResolver.class),
            receiptMapper,
            attemptMapper,
            jsonMapper
        );
        SupervisorRoundMaterializer roundMaterializer = new SupervisorRoundMaterializer(
            runtimeDao, eventMapper, tokenMapper, eligibility);
        return new SupervisorTickServiceImpl(
            runtimeDao,
            supervisorRuntimeService,
            instanceMapper,
            controlMapper,
            definitionMapper,
            eventMapper,
            attemptMapper,
            roundMaterializer,
            update
        );
    }

    private PlanEventWorkerServiceImpl worker() {
        EventEligibilityService eligibility = eligibility();
        CapabilityRegistry registry = registry();
        EventMutationService mutation = eventMutation(eligibility, registry);
        EventUpdateService update = new EventUpdateService(
            runtimeDao,
            mutation,
            activation(eligibility, registry),
            registry,
            mock(EndpointTargetResolver.class),
            receiptMapper,
            attemptMapper,
            jsonMapper
        );
        return new PlanEventWorkerServiceImpl(
            instanceMapper,
            eventMapper,
            update,
            mutation,
            objectAuthorizer,
            new PlanCandidateQueryService(jdbcTemplate)
        );
    }

    private EventMutationService eventMutation(EventEligibilityService eligibility,
                                               CapabilityRegistry registry) {
        return new EventMutationService(
            runtimeDao,
            eventMapper,
            attemptMapper,
            tokenMapper,
            instanceMapper,
            controlMapper,
            auditService,
            jsonMapper,
            activation(eligibility, registry),
            mock(CallCapacityService.class),
            registry,
            mock(RecoveryEvidenceService.class),
            requestDetailMapper
        );
    }

    private CapabilityRegistry registry() {
        WmsAllocationReservationService reservationService = reservationService();
        WmsLoadChangeService loadChangeService = loadChangeService();
        WmsFrozenActionInputReader inputReader =
            new WmsFrozenActionInputReader(jdbcTemplate, mock(IamIdentityFacts.class));
        SupervisorCandidateSelector selector = new SupervisorCandidateSelector(
            instanceMapper, controlMapper, definitionMapper, scheduleMapper);
        return new CapabilityRegistry(
            List.of(() -> List.of(new com.innolux.iws.process.runtime.EventTypeDescriptor(
                "WMS_INTERNAL",
                "1",
                "WMS内部业务动作",
                Set.of(com.innolux.iws.process.enums.EventDirection.INTERNAL)
            ))),
            List.of(),
            List.of(
                new WmsAllocationEvaluateAction(
                    reservationService, instanceMapper, jsonMapper, inputReader),
                new WmsAllocationReserveAction(
                    reservationService, instanceMapper, jsonMapper, inputReader),
                new WmsAllocationVerifyAction(
                    reservationService, jsonMapper, inputReader),
                new WmsAllocationCheckReleaseAction(
                    releaseService(), instanceMapper, jsonMapper, inputReader),
                new WmsAllocationReleaseAction(
                    releaseService(), instanceMapper, jsonMapper, inputReader),
                new WmsAllocationVerifyReleaseAction(
                    releaseService(), instanceMapper, jsonMapper, inputReader),
                new WmsLoadChangeCheckAction(
                    loadChangeService, instanceMapper, jsonMapper, inputReader),
                new WmsLoadChangeAction(
                    loadChangeService, instanceMapper, jsonMapper, inputReader),
                new SupervisorCandidateDecisionAction(
                    instanceMapper,
                    controlMapper,
                    definitionMapper,
                    scheduleMapper,
                    eventMapper,
                    selector
                ),
                new SupervisorAdmitQueueAction(eventMapper, supervisorRuntimeService)
            ),
            List.of(
                new WmsAllocationLockStateValidator(jdbcTemplate),
                new WmsAllocationReleaseStateValidator(jdbcTemplate),
                new WmsAllocationSupervisorStateValidator(jdbcTemplate),
                new WmsLoadChangeStateValidator(jdbcTemplate)
            ),
            List.of()
        );
    }

    private WmsAllocationReservationService reservationService() {
        return new WmsAllocationReservationServiceImpl(
            jdbcTemplate,
            new WmsAllocationSpaceReaderImpl(jdbcTemplate),
            identifierGenerator,
            jsonMapper,
            new WmsChannelReachabilitySnapshotService(
                jdbcTemplate, new WmsChannelReachabilityEvaluator())
        );
    }

    private WmsAllocationReleaseService releaseService() {
        return new WmsAllocationReleaseServiceImpl(
            jdbcTemplate,
            identifierGenerator,
            jsonMapper
        );
    }

    private WmsLoadChangeService loadChangeService() {
        return new WmsLoadChangeServiceImpl(
            jdbcTemplate,
            new WmsAllocationSpaceReaderImpl(jdbcTemplate),
            identifierGenerator
        );
    }

    private WmsBusinessTaskService businessTaskService() {
        return new WmsBusinessTaskServiceImpl(jdbcTemplate, identifierGenerator, jsonMapper);
    }

    private EventEligibilityService eligibility() {
        return new EventEligibilityService(runtimeDao, eventMapper, receiptMapper, tokenMapper);
    }

    private PlanActivationService activation(EventEligibilityService eligibility,
                                             CapabilityRegistry registry) {
        return new PlanActivationService(
            runtimeDao,
            eligibility,
            new BranchSelector(tokenMapper, instanceMapper, registry),
            eventMapper,
            tokenMapper,
            instanceMapper,
            controlMapper,
            jsonMapper
        );
    }

    private PlanMaterializer materializer(EventEligibilityService eligibility) {
        return new PlanMaterializer(runtimeDao, eventMapper, tokenMapper, contextMapper, eligibility);
    }

    private void seedCatalog() {
        jdbcTemplate.update("""
            insert into iws_business_schedule
                (id, business_code, business_name, enabled, fairness_window_size,
                 revision, del_flag, create_time, update_time)
            values (?, 'INBOUND', '测试入库', 1, 32, 0, '0', current_timestamp(3),
                    current_timestamp(3))
            """, SCHEDULE_ID);
        jdbcTemplate.update("""
            insert into iws_business_schedule_plan_type
                (id, schedule_id, plan_type, plan_type_name, priority, enabled,
                 create_time, update_time)
            values (?, ?, 'ALLOCATION_LOCK', '分配锁位', 10, 1,
                    current_timestamp(3), current_timestamp(3))
            """, 8_401L, SCHEDULE_ID);
        jdbcTemplate.update("""
            insert into iws_business_schedule_plan_type
                (id, schedule_id, plan_type, plan_type_name, priority, enabled,
                 create_time, update_time)
            values (?, ?, 'LOAD_CHANGE', '装拆变更', 20, 1,
                    current_timestamp(3), current_timestamp(3))
            """, 8_402L, SCHEDULE_ID);
        jdbcTemplate.update("""
            insert into iws_business_schedule_plan_type
                (id, schedule_id, plan_type, plan_type_name, priority, enabled,
                 create_time, update_time)
            values (?, ?, 'ALLOCATION_RELEASE', '分配释放', 30, 1,
                    current_timestamp(3), current_timestamp(3))
            """, 8_403L, SCHEDULE_ID);
        jdbcTemplate.update("""
            insert into wms_business_type
                (business_type_code, business_type_name, status, contract_version,
                 object_policy_key, revision, create_time)
            values (?, '入库测试业务', 'ACTIVE', ?, 'WMS_INBOUND_TEST', 1,
                    current_timestamp(3))
            """, BUSINESS_TYPE, WmsInventoryCatalogContract.CONTRACT_VERSION);
        jdbcTemplate.update("""
            insert into wms_business_plan_mount
                (mount_id, business_type_code, plan_type_code, schedule_code,
                 contract_version, enabled, revision, create_time)
            values (?, ?, 'ALLOCATION_LOCK', 'INBOUND', ?, 1, 1,
                    current_timestamp(3))
            """, 8_501L, BUSINESS_TYPE, WmsInventoryCatalogContract.CONTRACT_VERSION);
        jdbcTemplate.update("""
            insert into wms_business_plan_mount
                (mount_id, business_type_code, plan_type_code, schedule_code,
                 contract_version, enabled, revision, create_time)
            values (?, ?, 'LOAD_CHANGE', 'INBOUND', ?, 1, 1,
                    current_timestamp(3))
            """, 8_502L, BUSINESS_TYPE, WmsInventoryCatalogContract.CONTRACT_VERSION);
        jdbcTemplate.update("""
            insert into wms_business_plan_mount
                (mount_id, business_type_code, plan_type_code, schedule_code,
                 contract_version, enabled, revision, create_time)
            values (?, ?, 'ALLOCATION_RELEASE', 'INBOUND', ?, 1, 1,
                    current_timestamp(3))
            """, 8_503L, BUSINESS_TYPE, WmsInventoryCatalogContract.CONTRACT_VERSION);
    }

    private void seedAllocationSpace() {
        jdbcTemplate.update("""
            insert into wms_warehouse_zone
                (zone_id, zone_code, zone_name, zone_kind, warehouse_id)
            values (?, 'Z-WORKER', 'worker验证库区', 'DEVICE', 1)
            """, ZONE_ID);
        jdbcTemplate.update("""
            insert into wms_storage
                (storage_id, storage_code, zone_id, warehouse_id, lock_status,
                 disable_status, version, aisle_text)
            values (?, 'S-WORKER', ?, 1, 0, 0, 0, 'S-WORKER')
            """, STORAGE_ID, ZONE_ID);
        jdbcTemplate.update("""
            insert into wms_storage_capacity
                (capacity_id, storage_id, dimension_key, capacity_value, unit, scale,
                 capacity_version, active_flag)
            values (?, ?, ?, ?, 'KG', 3, 1, 1)
            """, STORAGE_ID * 10L + 1L, STORAGE_ID, WEIGHT, new BigDecimal("100.000"));
        jdbcTemplate.update("""
            insert into wms_access_channel
                (channel_id, device_zone_id, channel_code, access_mode, status, revision)
            values (?, ?, 'CH-WORKER', 'FORK', 'ACTIVE', 1)
            """, CHANNEL_ID, ZONE_ID);
        jdbcTemplate.update("""
            insert into wms_storage_access_member
                (member_id, channel_id, storage_id, access_order, revision)
            values (?, ?, ?, 1, 1)
            """, 9_301L, CHANNEL_ID, STORAGE_ID);
    }

    private void seedLoadChangeData() {
        jdbcTemplate.update("""
            insert into wms_storage
                (storage_id, storage_code, zone_id, warehouse_id, lock_status,
                 disable_status, version, aisle_text)
            values (?, 'S-LOAD-WORKER', ?, 1, 0, 0, 0, 'S-LOAD-WORKER')
            """, LOAD_STORAGE_ID, ZONE_ID);
        jdbcTemplate.update("""
            insert into wms_storage_capacity
                (capacity_id, storage_id, dimension_key, capacity_value, unit, scale,
                 capacity_version, active_flag)
            values (?, ?, 'TOP_LEVEL_LOAD', 2, 'LOAD', 0, 1, 1)
            """, LOAD_STORAGE_ID * 10L + 1L, LOAD_STORAGE_ID);
        jdbcTemplate.update("""
            insert into wms_access_channel
                (channel_id, device_zone_id, channel_code, access_mode, status, revision)
            values (?, ?, 'CH-LOAD-WORKER', 'FORK', 'ACTIVE', 1)
            """, LOAD_CHANNEL_ID, ZONE_ID);
        jdbcTemplate.update("""
            insert into wms_storage_access_member
                (member_id, channel_id, storage_id, access_order, revision)
            values (?, ?, ?, 1, 1)
            """, 9_302L, LOAD_CHANNEL_ID, LOAD_STORAGE_ID);
        jdbcTemplate.update("""
            insert into wms_load_node
                (load_node_id, node_type, inventory_id, container_id, status, revision,
                 create_dept, create_by)
            values (?, 'CARRIER', null, ?, 'ACTIVE', 1, 9101, 9001)
            """, LOAD_CARRIER_ID, LOAD_CARRIER_ID + 10_000L);
        jdbcTemplate.update("""
            insert into wms_load_node
                (load_node_id, node_type, inventory_id, container_id, status, revision,
                 create_dept, create_by)
            values (?, 'MATERIAL', ?, null, 'ACTIVE', 1, 9101, 9001)
            """, LOAD_MATERIAL_ID, LOAD_MATERIAL_ID + 10_000L);
        jdbcTemplate.update("""
            insert into wms_load_relation
                (relation_id, child_node_id, parent_node_id, revision, create_dept, create_by)
            values (?, ?, ?, 1, 9101, 9001)
            """, 26_101L, LOAD_MATERIAL_ID, LOAD_CARRIER_ID);
        jdbcTemplate.update("""
            insert into wms_storage_info
                (storage_info_id, root_node_id, location_kind, storage_id, revision,
                 create_dept, create_by)
            values (?, ?, 'STORAGE', ?, 1, 9101, 9001)
            """, 26_102L, LOAD_CARRIER_ID, LOAD_STORAGE_ID);
        jdbcTemplate.update("""
            insert into wms_occupancy_effect
                (occupancy_effect_id, effect_key, business_type, business_id, business_line_id,
                 task_key, plan_instance_id, effect_result, reason, occurred_at,
                 create_dept, create_by)
            values (?, ?, 'LOAD_SEED', 88002, null, ?, null, 'APPLIED',
                   'worker初始占用', current_timestamp(3), 9101, 9001)
            """, 26_103L, "WMS:" + "8".repeat(64), LOAD_TASK_KEY);
        jdbcTemplate.update("""
            insert into wms_storage_occupancy
                (occupancy_id, occupancy_effect_id, storage_id, root_node_id, dimension_key,
                 occupied_qty, unit, generation, status, revision, create_dept, create_by)
            values (?, ?, ?, ?, 'TOP_LEVEL_LOAD', 1, 'LOAD', 1, 'ACTIVE', 1, 9101, 9001)
            """, 26_104L, 26_103L, LOAD_STORAGE_ID, LOAD_CARRIER_ID);
    }

    private void publish(com.innolux.iws.process.api.DefinitionDraftBo draft) {
        var created = definitionService.createDraft(
            draft, "create-" + draft.planCode(), "监督worker隔离测试");
        long versionId = Long.parseLong(created.summary().versionId());
        var validation = definitionService.validate(versionId);
        assertThat(validation.errors()).isEmpty();
        assertThat(validation.valid()).isTrue();
        var published = definitionService.publish(
            versionId,
            Long.parseLong(created.summary().revision()),
            "publish-" + draft.planCode(),
            "监督worker隔离测试"
        );
        assertThat(published.valid()).isTrue();
    }

    private void cleanPersistence() {
        jdbcTemplate.update("delete from wms_business_task");
        jdbcTemplate.update("delete from iws_process_action_audit");
        jdbcTemplate.update("delete from iws_process_request_detail");
        jdbcTemplate.update("delete from iws_process_trigger_receipt");
        jdbcTemplate.update("delete from iws_process_attempt");
        jdbcTemplate.update("delete from iws_process_event");
        jdbcTemplate.update("delete from iws_process_token");
        jdbcTemplate.update("delete from iws_process_system_context");
        jdbcTemplate.update("delete from iws_process_schedule_control");
        jdbcTemplate.update("delete from iws_process_instance");
        jdbcTemplate.update("delete from iws_process_edge");
        jdbcTemplate.update("delete from iws_process_system_policy");
        jdbcTemplate.update("delete from iws_process_event_definition");
        jdbcTemplate.update("delete from iws_process_state");
        jdbcTemplate.update("delete from iws_process_definition");
        jdbcTemplate.update("delete from wms_business_plan_mount");
        jdbcTemplate.update("delete from wms_business_type");
        jdbcTemplate.update("delete from iws_business_schedule_plan_type");
        jdbcTemplate.update("delete from iws_business_schedule");
        jdbcTemplate.update("delete from wms_allocation_release");
        jdbcTemplate.update("delete from wms_inventory_reservation");
        jdbcTemplate.update("delete from wms_allocation_lock");
        jdbcTemplate.update("delete from wms_access_channel_lock");
        jdbcTemplate.update("delete from wms_allocation_candidate");
        jdbcTemplate.update("delete from wms_allocation_decision");
        jdbcTemplate.update("delete from wms_load_relation_history");
        jdbcTemplate.update("delete from wms_load_relation");
        jdbcTemplate.update("delete from wms_storage_info");
        jdbcTemplate.update("delete from wms_storage_occupancy");
        jdbcTemplate.update("delete from wms_occupancy_effect");
        jdbcTemplate.update("delete from wms_load_node");
        jdbcTemplate.update("delete from wms_storage_capacity");
        jdbcTemplate.update("delete from wms_storage_access_member");
        jdbcTemplate.update("delete from wms_storage_access_edge");
        jdbcTemplate.update("delete from wms_access_channel");
        jdbcTemplate.update("delete from wms_storage");
        jdbcTemplate.update("delete from wms_warehouse_zone");
    }
}
