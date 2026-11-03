/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务集成测试
 * @Version      : V1.0
 * @Description  : 验证锁序、实时账、通道互斥、成员迁移重试、实体槽及平台同事务；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.EventUpdateService;
import com.innolux.iws.process.runtime.EventTypeProvider;
import com.innolux.iws.process.runtime.EndpointTargetResolver;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionInputProvider;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.StateContext;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.ValidationResult;
import com.innolux.iws.process.support.DefinitionGraph;
import com.innolux.iws.wms.allocation.kernel.AllocationDemand;
import com.innolux.iws.wms.allocation.kernel.AllocationPolicy;
import com.innolux.iws.wms.allocation.kernel.AllocationProtection;
import com.innolux.iws.wms.allocation.kernel.AllocationQuantity;
import com.innolux.iws.wms.allocation.kernel.AllocationRequest;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectCommand;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectExpectation;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectIdentity;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectResult;
import com.innolux.iws.wms.allocation.effect.investigation.WmsAllocationEffectInvestigationService;
import com.innolux.iws.wms.allocation.effect.investigation.WmsAllocationEffectUnknownQuery;
import com.innolux.iws.wms.allocation.effect.investigation.WmsAllocationEffectUnknownView;
import com.innolux.iws.wms.allocation.effect.service.WmsAllocationEffectService;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationBusinessContext;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReservationResult;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReserveCommand;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationSnapshotInput;
import com.innolux.iws.wms.allocation.process.WmsAllocationEvaluateAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationCheckReleaseAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationLockStateValidator;
import com.innolux.iws.wms.allocation.process.WmsAllocationReleaseAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationReleaseStateValidator;
import com.innolux.iws.wms.allocation.process.WmsAllocationReserveAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationVerifyAction;
import com.innolux.iws.wms.allocation.process.WmsAllocationVerifyReleaseAction;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationEvidenceResult;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCheckCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseIdentity;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseResult;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseVerifyCommand;
import com.innolux.iws.wms.allocation.release.service.WmsAllocationReleaseService;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelMembershipChangeCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelMembershipChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelAccessOrderChangeCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelAccessOrderChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelDefinitionChangeCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelDefinitionChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsStorageAccessEdgeAddCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsStorageAccessEdgeChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsStorageAccessEdgeRemoveCommand;
import com.innolux.iws.wms.allocation.space.service.WmsChannelMembershipService;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeResult;
import com.innolux.iws.wms.load.change.domain.WmsLoadMutation;
import com.innolux.iws.wms.load.change.process.WmsLoadChangeAction;
import com.innolux.iws.wms.load.change.service.WmsLoadChangeService;
import com.innolux.iws.wms.material.change.domain.WmsMaterialAllocationConsumption;
import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeCommand;
import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeResult;
import com.innolux.iws.wms.material.change.service.WmsMaterialChangeService;
import com.innolux.iws.wms.material.process.FixtureMaterialChangeAction;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskTransitionCommand;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** P5 分配锁位真实 MySQL 集成测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationReservationTestContext.class)
class WmsAllocationReservationIntegrationTest {

    private static final long ZONE_ID = 6_101L;

    private static final long STORAGE_A = 7_101L;

    private static final long STORAGE_B = 7_102L;

    private static final long CHANNEL_A = 8_101L;

    private static final long CHANNEL_B = 8_102L;

    private static final long INSTANCE_ID = 88_001L;

    private static final long RELEASE_INSTANCE_ID = 89_001L;

    private static final long DEFINITION_VERSION_ID = 12_345L;

    private static final long TOKEN_ID = 77_001L;

    private static final String INPUT_HASH = "a".repeat(64);

    private static final String SPACE_HASH = "b".repeat(64);

    private static final String CAPACITY_HASH = "c".repeat(64);

    private static final String WEIGHT = "WEIGHT";

    private static final String VOLUME = "VOLUME";

    @Autowired
    private WmsAllocationReservationService reservationService;

    @Autowired
    private EventMutationService mutationService;

    @Autowired
    private FixtureAllocationReserveAction reserveAction;

    @Autowired
    private WmsAllocationReserveAction productionReserveAction;

    @Autowired
    private WmsAllocationEvaluateAction productionEvaluateAction;

    @Autowired
    private WmsAllocationVerifyAction productionVerifyAction;

    @Autowired
    private WmsAllocationCheckReleaseAction productionCheckReleaseAction;

    @Autowired
    private WmsAllocationReleaseAction productionReleaseAction;

    @Autowired
    private WmsAllocationVerifyReleaseAction productionVerifyReleaseAction;

    @Autowired
    private WmsLoadChangeAction loadChangeAction;

    @Autowired
    private WmsLoadChangeService loadChangeService;

    @Autowired
    private WmsMaterialChangeService materialChangeService;

    @Autowired
    private FixtureMaterialChangeAction materialChangeAction;

    @Autowired
    private WmsBusinessTaskService businessTaskService;

    @Autowired
    private WmsAllocationReleaseService releaseService;

    @Autowired
    private WmsAllocationEffectService effectService;

    @Autowired
    private WmsAllocationEffectInvestigationService effectInvestigationService;

    @Autowired
    private WmsChannelMembershipService channelMembershipService;

    @Autowired
    private WmsAllocationLockStateValidator lockStateValidator;

    @Autowired
    private WmsAllocationReleaseStateValidator releaseStateValidator;

    @Autowired
    private EventTypeProvider eventTypeProvider;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private RuntimeDao runtimeDao;

    @Autowired
    private PlanActivationService activationService;

    @Autowired
    private com.innolux.iws.process.runtime.CapabilityRegistry capabilityRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessEventMapper eventMapper;

    @Autowired
    private ProcessTokenMapper tokenMapper;

    @Autowired
    private ProcessInstanceMapper instanceMapper;

    @Autowired
    private RetrySpaceReader retrySpaceReader;

    @BeforeEach
    void resetDatabaseAndRuntimeStubs() {
        jdbcTemplate.update("delete from wms_business_task");
        jdbcTemplate.update("delete from wms_material_trace_edge");
        jdbcTemplate.update("delete from wms_material_trace_item");
        jdbcTemplate.update("delete from wms_material_trace_history");
        jdbcTemplate.update("delete from wms_inventory_flow");
        jdbcTemplate.update("delete from wms_inventory");
        jdbcTemplate.update("delete from wms_object_audit");
        jdbcTemplate.update("delete from iws_process_action_audit");
        jdbcTemplate.update("delete from iws_process_attempt");
        jdbcTemplate.update("delete from iws_process_event");
        jdbcTemplate.update("delete from iws_process_schedule_control");
        jdbcTemplate.update("delete from iws_process_token");
        jdbcTemplate.update("delete from iws_process_instance");
        jdbcTemplate.update("delete from wms_allocation_release");
        jdbcTemplate.update("delete from wms_inventory_reservation");
        jdbcTemplate.update("delete from wms_allocation_lock");
        jdbcTemplate.update("delete from wms_access_channel_lock");
        jdbcTemplate.update("delete from wms_allocation_candidate");
        jdbcTemplate.update("delete from wms_allocation_decision");
        jdbcTemplate.update("delete from wms_storage_occupancy");
        jdbcTemplate.update("delete from wms_occupancy_effect");
        jdbcTemplate.update("delete from wms_storage_capacity");
        jdbcTemplate.update("delete from wms_storage_access_member");
        jdbcTemplate.update("delete from wms_storage_access_edge");
        jdbcTemplate.update("delete from wms_access_channel");
        jdbcTemplate.update("delete from wms_load_relation_history");
        jdbcTemplate.update("delete from wms_storage_info");
        jdbcTemplate.update("delete from wms_load_relation");
        jdbcTemplate.update("delete from wms_load_node");
        jdbcTemplate.update("delete from wms_storage");
        jdbcTemplate.update("delete from wms_warehouse_zone");

        reset(runtimeDao, activationService, capabilityRegistry);
        when(runtimeDao.event(anyLong())).thenAnswer(invocation ->
            eventMapper.selectById(invocation.getArgument(0))
        );
        when(runtimeDao.token(anyLong())).thenAnswer(invocation ->
            tokenMapper.selectById(invocation.getArgument(0))
        );
        when(runtimeDao.instance(anyLong())).thenAnswer(invocation ->
            instanceMapper.selectById(invocation.getArgument(0))
        );
        when(runtimeDao.systemContext(anyLong(), anyString())).thenReturn(null);
        DefinitionGraph graph = mock(DefinitionGraph.class);
        com.innolux.iws.process.domain.ProcessState state =
            new com.innolux.iws.process.domain.ProcessState();
        state.setValidatorKey("fixture-state");
        state.setValidatorVersion("1");
        when(graph.state(anyString())).thenReturn(state);
        when(runtimeDao.graph(DEFINITION_VERSION_ID)).thenReturn(graph);
        StateValidator validator = mock(StateValidator.class);
        when(validator.validateBusiness(any())).thenReturn(ValidationResult.pass());
        when(validator.validateStructural(any())).thenReturn(ValidationResult.pass());
        when(capabilityRegistry.validator("fixture-state", "1")).thenReturn(validator);
    }

    @Test
    void reserveReplayAndVerifyCommitOneDecisionLockAndCandidate() {
        seedCapacitiedStorage(STORAGE_A, "A-01", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand command = capacityCommand(
            STORAGE_A, "INV-001", 1_001L, 1_001_001L, "WEIGHT-10",
            WEIGHT, "10.000", 3, INPUT_HASH
        );

        WmsAllocationReservationResult first = inTransaction(() ->
            reservationService.reserve(command));
        WmsAllocationReservationResult replay = inTransaction(() ->
            reservationService.reserve(command));
        WmsAllocationReservationResult verify = inTransaction(() ->
            reservationService.verify(command.effectKey()));

        assertThat(first.result()).isEqualTo("LOCKED");
        assertThat(first.selectedStorageId()).isEqualTo(STORAGE_A);
        assertThat(replay.decisionId()).isEqualTo(first.decisionId());
        assertThat(replay.allocationLockIds()).isEqualTo(first.allocationLockIds());
        assertThat(verify.decisionId()).isEqualTo(first.decisionId());
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_decision where effect_key = ?", command.effectKey()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_candidate where decision_id = ? and selected_flag = 1",
            first.decisionId()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel_lock where status = 'LOCKED'"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where status = 'LOCKED'"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select resource_domain from wms_allocation_lock where decision_id = ?",
            first.decisionId()
        )).isEqualTo("CHANNEL");
    }

    @Test
    void evaluatePersistsCandidatesWithoutLocksAndReserveReusesDecision() {
        seedCapacitiedStorage(STORAGE_A, "A-EVAL", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand command = capacityCommand(
            STORAGE_A, "INV-EVAL", 1_101L, 1_101_001L, "WEIGHT-EVAL",
            WEIGHT, "10.000", 3, INPUT_HASH
        );

        WmsAllocationReservationResult evaluated = inTransaction(() ->
            reservationService.evaluate(command));

        assertThat(evaluated.result()).isEqualTo("EVALUATED");
        assertThat(evaluated.selectedStorageId()).isEqualTo(STORAGE_A);
        assertThat(evaluated.channelLockIds()).isEmpty();
        assertThat(evaluated.allocationLockIds()).isEmpty();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock"
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel_lock"
        )).isZero();

        WmsAllocationReservationResult reserved = inTransaction(() ->
            reservationService.reserve(command));

        assertThat(reserved.result()).isEqualTo("LOCKED");
        assertThat(reserved.decisionId()).isEqualTo(evaluated.decisionId());
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_decision where effect_key = ?",
            command.effectKey()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where decision_id = ? and status = 'LOCKED'",
            reserved.decisionId()
        )).isOne();
    }

    @Test
    void evaluatedDecisionRejectsSameEffectKeyWithDifferentInputIdentity() {
        seedCapacitiedStorage(STORAGE_A, "A-EVAL-CONFLICT", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand evaluatedCommand = capacityCommand(
            STORAGE_A, "INV-EVAL-CONFLICT", 1_102L, 1_102_001L, "WEIGHT-EVAL-CONFLICT",
            WEIGHT, "10.000", 3, INPUT_HASH
        );
        inTransaction(() -> reservationService.evaluate(evaluatedCommand));

        WmsAllocationReserveCommand conflict = capacityCommand(
            STORAGE_A, "INV-EVAL-CONFLICT", 1_102L, 1_102_001L, "WEIGHT-EVAL-CONFLICT",
            WEIGHT, "10.000", 3, "d".repeat(64)
        );

        assertThatThrownBy(() -> inTransaction(() -> reservationService.reserve(conflict)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).startsWith(
                    WmsAllocationReservationService.ERROR_EFFECT_KEY_CONFLICT));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where decision_id = ("
                + "select decision_id from wms_allocation_decision where effect_key = ?)",
            conflict.effectKey()
        )).isZero();
    }

    @Test
    void productionReserveActionRegistersCapabilitiesAndCommitsThroughSameTx() {
        CapabilityRegistry registry = new CapabilityRegistry(
            List.of(eventTypeProvider),
            List.of(),
            List.of(
                productionEvaluateAction,
                productionReserveAction,
                productionVerifyAction,
                productionCheckReleaseAction,
                productionReleaseAction,
                productionVerifyReleaseAction
            ),
            List.of(),
            List.of()
        );
        assertThat(registry.eventType("WMS_INTERNAL", "1").supportedDirections())
            .containsExactly(EventDirection.INTERNAL);
        assertThat(registry.localActions())
            .extracting(com.innolux.iws.process.runtime.LocalActionDescriptor::key)
            .containsExactly(
                WmsAllocationEvaluateAction.ACTION_KEY,
                WmsAllocationReserveAction.ACTION_KEY,
                WmsAllocationVerifyAction.ACTION_KEY,
                WmsAllocationCheckReleaseAction.ACTION_KEY,
                WmsAllocationReleaseAction.ACTION_KEY,
                WmsAllocationVerifyReleaseAction.ACTION_KEY
            );

        seedCapacitiedStorage(STORAGE_A, "A-PROD", WEIGHT, "100.000", 3);
        long eventId = 66_001L;
        seedProductionProcess(
            eventId,
            "RESERVED",
            WmsAllocationReserveAction.ACTION_KEY,
            WmsAllocationReserveAction.LOCAL_EFFECT_KEY
        );
        WmsAllocationReserveCommand source = capacityCommand(
            STORAGE_A, "INV-PROD", 1_201L, INSTANCE_ID, "WEIGHT-PROD",
            WEIGHT, "10.000", 3, INPUT_HASH
        );
        WmsAllocationBusinessContext platformBusiness = new WmsAllocationBusinessContext(
            source.business().businessType(),
            source.business().businessName(),
            source.business().businessId(),
            source.business().businessNo(),
            source.business().businessLineId(),
            source.business().allocationUnitId(),
            source.business().roundNo(),
            "INV-008",
            INSTANCE_ID
        );
        WmsAllocationReserveCommand command = new WmsAllocationReserveCommand(
            platformBusiness,
            "WMS:" + INPUT_HASH,
            source.inputHash(),
            source.rulePackageVersionId(),
            source.logicalScopeVersionId(),
            source.spaceSnapshotHash(),
            source.capacitySnapshotHash(),
            source.storages(),
            source.request()
        );

        ProcessAttempt attempt = executeProduction(eventId, productionReserveAction, command);

        assertThat(attempt.getEffectResult())
            .isEqualTo(com.innolux.iws.process.enums.EffectResult.SUCCEEDED);
        assertThat(attempt.getCheckpointJson()).contains("\"status\":\"LOCKED\"");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where status = 'LOCKED'"
        )).isOne();
    }

    @Test
    void eventUpdateServiceReadsPersistedBusinessTaskInputForReserveAction() {
        seedCapacitiedStorage(STORAGE_A, "A-WORKER-INPUT", WEIGHT, "100.000", 3);
        long eventId = 66_501L;
        seedProductionProcess(
            eventId,
            "RESERVED",
            WmsAllocationReserveAction.ACTION_KEY,
            WmsAllocationReserveAction.LOCAL_EFFECT_KEY
        );
        WmsAllocationReserveCommand source = capacityCommand(
            STORAGE_A, "INV-WORKER", 1_501L, INSTANCE_ID, "WORKER-INPUT",
            WEIGHT, "10.000", 3, INPUT_HASH
        );
        WmsAllocationReserveCommand command = new WmsAllocationReserveCommand(
            new WmsAllocationBusinessContext(
                source.business().businessType(),
                source.business().businessName(),
                source.business().businessId(),
                source.business().businessNo(),
                source.business().businessLineId(),
                source.business().allocationUnitId(),
                source.business().roundNo(),
                "INV-008",
                INSTANCE_ID
            ),
            "WMS:" + INPUT_HASH,
            source.inputHash(),
            source.rulePackageVersionId(),
            source.logicalScopeVersionId(),
            source.spaceSnapshotHash(),
            source.capacitySnapshotHash(),
            source.storages(),
            source.request()
        );
        WmsBusinessTaskSnapshot task = businessTaskService.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                command.business().businessType(),
                command.business().businessId(),
                command.business().businessLineId(),
                command.business().allocationUnitId(),
                command.business().roundNo(),
                "EXECUTE",
                command.business().taskKey(),
                "ALLOCATION_LOCK",
                INSTANCE_ID,
                command.inputHash(),
                jsonMapper.writeValueAsString(command),
                9_001L,
                9_101L
            )
        );
        businessTaskService.transition(new WmsBusinessTaskTransitionCommand(
            task.taskId(), task.revision(), "RUNNING", 9_001L, 9_101L));

        PlanDefinition definition = mock(PlanDefinition.class);
        when(definition.getId()).thenReturn(DEFINITION_VERSION_ID);
        when(definition.getLeaseMs()).thenReturn(120_000L);
        when(runtimeDao.definition(DEFINITION_VERSION_ID)).thenReturn(definition);
        DefinitionGraph graph = runtimeDao.graph(DEFINITION_VERSION_ID);
        ProcessEventDefinition eventDefinition = mock(ProcessEventDefinition.class);
        when(eventDefinition.getEnabled()).thenReturn(true);
        when(eventDefinition.getLocalActionKey()).thenReturn(WmsAllocationReserveAction.ACTION_KEY);
        when(eventDefinition.getLocalActionVersion()).thenReturn("1");
        when(graph.event("RESERVED")).thenReturn(eventDefinition);
        when(capabilityRegistry.localAction(
            WmsAllocationReserveAction.ACTION_KEY, "1")).thenReturn(productionReserveAction);

        EventUpdateService service = new EventUpdateService(
            runtimeDao,
            mutationService,
            activationService,
            capabilityRegistry,
            mock(EndpointTargetResolver.class),
            mock(ProcessTriggerReceiptMapper.class),
            mock(ProcessAttemptMapper.class),
            jsonMapper
        );

        assertThat(service.update(eventId, "formal-worker"))
            .isEqualTo(EventUpdateService.UpdateResult.SUCCEEDED);
        ProcessAttempt attempt = jdbcTemplate.queryForObject(
            "select * from iws_process_attempt where event_id = ? order by id desc limit 1",
            (result, row) -> {
                ProcessAttempt stored = new ProcessAttempt();
                stored.setId(result.getLong("id"));
                stored.setEffectResult(
                    com.innolux.iws.process.enums.EffectResult.valueOf(
                        result.getString("effect_result")));
                stored.setEvidenceRef(result.getString("evidence_ref"));
                stored.setCheckpointJson(result.getString("checkpoint_json"));
                return stored;
            },
            eventId
        );
        assertThat(attempt.getEffectResult())
            .isEqualTo(com.innolux.iws.process.enums.EffectResult.SUCCEEDED);
        assertThat(attempt.getEvidenceRef()).startsWith("wms-business-task:");
        assertThat(attempt.getCheckpointJson()).contains("\"status\":\"LOCKED\"");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where status = 'LOCKED'"
        )).isOne();
    }

    @Test
    void productionAllocationPlanValidatesExactStatesAndPersistedEvidence() {
        seedCapacitiedStorage(STORAGE_A, "A-STATE", WEIGHT, "100.000", 3);
        String inputHash = "8".repeat(64);
        WmsAllocationReserveCommand source = capacityCommandForSingleStorage(
            STORAGE_A,
            "STATE-CHAIN",
            INSTANCE_ID,
            1_202L,
            "STATE-CHAIN",
            WEIGHT,
            "10.000",
            3,
            inputHash
        );
        WmsAllocationReserveCommand command = new WmsAllocationReserveCommand(
            source.business(),
            "WMS:" + inputHash,
            source.inputHash(),
            source.rulePackageVersionId(),
            source.logicalScopeVersionId(),
            source.spaceSnapshotHash(),
            source.capacitySnapshotHash(),
            source.storages(),
            source.request()
        );
        seedAllocationProcessChain(inputHash);

        ProcessAttempt evaluated = executeProduction(
            66_201L,
            INSTANCE_ID,
            productionEvaluateAction,
            WmsAllocationEvaluateAction.LOCAL_EFFECT_KEY,
            command,
            inputHash
        );

        var evaluateEvidence = jdbcTemplate.queryForMap("""
            SELECT a.effect_result AS attempt_effect,
                   JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.status')) AS checkpoint_status,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.decisionId')) AS UNSIGNED)
                       AS checkpoint_decision,
                   JSON_LENGTH(a.checkpoint_json, '$.allocationLockIds') AS checkpoint_locks,
                   d.decision_id AS persisted_decision,
                   d.plan_instance_id AS persisted_instance,
                   d.input_hash AS persisted_input,
                   d.result AS persisted_result,
                   d.phase AS persisted_phase,
                   (SELECT COUNT(*) FROM wms_allocation_lock l
                    WHERE l.decision_id = d.decision_id AND l.status = 'LOCKED') AS active_locks
            FROM iws_process_attempt a
            JOIN wms_allocation_decision d
              ON d.decision_id = CAST(
                   JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.decisionId')) AS UNSIGNED)
            WHERE a.event_id = 66201
            """);
        assertThat(evaluateEvidence)
            .as("评估状态证据=%s", evaluateEvidence)
            .containsEntry("checkpoint_status", "EVALUATED")
            .containsEntry("persisted_input", inputHash)
            .containsEntry("persisted_result", "EVALUATED")
            .containsEntry("persisted_phase", "LOCK");

        assertThat(lockStateValidator.validateBusiness(new StateContext(
            INSTANCE_ID,
            77_003L,
            66_201L,
            WmsAllocationLockStateValidator.STATE_START,
            WmsAllocationLockStateValidator.STATE_EVALUATED,
            PostingMode.AUTOMATIC,
            inputHash,
            evaluated.getEffectResult().name(),
            Map.of()
        )).passed()).isTrue();

        ProcessAttempt reserved = executeProduction(
            66_202L, productionReserveAction, command);
        var reserveEvidence = jdbcTemplate.queryForMap("""
            SELECT a.effect_result AS attempt_effect,
                   JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.status')) AS checkpoint_status,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.decisionId')) AS UNSIGNED)
                       AS checkpoint_decision,
                   JSON_LENGTH(a.checkpoint_json, '$.allocationLockIds') AS checkpoint_locks,
                   d.decision_id AS persisted_decision,
                   d.input_hash AS persisted_input,
                   d.result AS persisted_result,
                   (SELECT COUNT(*) FROM wms_allocation_lock l
                    WHERE l.decision_id = d.decision_id AND l.status = 'LOCKED') AS active_locks
            FROM iws_process_attempt a
            JOIN wms_allocation_decision d
              ON d.decision_id = CAST(
                   JSON_UNQUOTE(JSON_EXTRACT(a.checkpoint_json, '$.decisionId')) AS UNSIGNED)
            WHERE a.event_id = 66202
            """);
        assertThat(reserveEvidence)
            .as("锁位状态证据=%s", reserveEvidence)
            .containsEntry("checkpoint_status", "LOCKED")
            .containsEntry("persisted_input", inputHash)
            .containsEntry("persisted_result", "LOCKED")
            .containsEntry("active_locks", 1L)
            .containsEntry("checkpoint_locks", 1L);
        assertThat(lockStateValidator.validateBusiness(new StateContext(
            INSTANCE_ID,
            77_003L,
            66_202L,
            WmsAllocationLockStateValidator.STATE_EVALUATED,
            WmsAllocationLockStateValidator.STATE_RESERVED,
            PostingMode.AUTOMATIC,
            inputHash,
            reserved.getEffectResult().name(),
            Map.of()
        )).passed()).isTrue();

        ProcessAttempt verified = executeProduction(
            66_203L,
            INSTANCE_ID,
            productionVerifyAction,
            WmsAllocationVerifyAction.LOCAL_EFFECT_KEY,
            new WmsAllocationVerifyAction.VerifyPayload(command.effectKey()),
            inputHash
        );
        assertThat(lockStateValidator.validateBusiness(new StateContext(
            INSTANCE_ID,
            77_003L,
            66_203L,
            WmsAllocationLockStateValidator.STATE_RESERVED,
            WmsAllocationLockStateValidator.STATE_END,
            PostingMode.AUTOMATIC,
            inputHash,
            verified.getEffectResult().name(),
            Map.of()
        )).passed()).isTrue();
        assertThat(lockStateValidator.validateStructural(new StateContext(
            INSTANCE_ID,
            77_003L,
            66_203L,
            WmsAllocationLockStateValidator.STATE_START,
            WmsAllocationLockStateValidator.STATE_RESERVED,
            PostingMode.AUTOMATIC,
            inputHash,
            "SUCCEEDED",
            Map.of()
        )).passed()).isFalse();
        assertThat(lockStateValidator.validateBusiness(new StateContext(
            INSTANCE_ID,
            77_003L,
            999_999L,
            WmsAllocationLockStateValidator.STATE_RESERVED,
            WmsAllocationLockStateValidator.STATE_END,
            PostingMode.AUTOMATIC,
            inputHash,
            "SUCCEEDED",
            Map.of()
        )).passed()).isFalse();
    }

    @Test
    void releaseAllOwnedSharesTerminatesChannelWithoutDeletingPhysicalOccupancy() {
        seedChannel(STORAGE_A, "A-REL-ALL", 1);
        seedCapacity(STORAGE_A, WEIGHT, "100.000", "KG", 3);
        seedCapacity(STORAGE_A, VOLUME, "50.000", "M3", 3);
        WmsAllocationReserveCommand reserveCommand =
            multiCapacityCommand("RELEASE-ALL", "abcd".repeat(16));
        WmsAllocationReservationResult reserved =
            inTransaction(() -> reservationService.reserve(reserveCommand));

        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(
                releaseCheckCommand(reserveCommand, reserved.allocationLockIds())));
        WmsAllocationReleaseCommand releaseCommand =
            releaseCommand(reserveCommand, evidence, "RELEASE-ALL");
        WmsAllocationReleaseResult released =
            inTransaction(() -> releaseService.release(releaseCommand));

        assertThat(released.allocationLockIds())
            .containsExactlyElementsOf(reserved.allocationLockIds());
        assertThat(released.releaseIds()).hasSize(2);
        assertThat(released.releasedChannelLockIds())
            .containsExactlyElementsOf(reserved.channelLockIds());
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where status = 'RELEASED' "
                + "and effect_result = 'NO_EFFECT'"
        )).isEqualTo(2);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel_lock "
                + "where status = 'RELEASED' and effect_result = 'NO_EFFECT'"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_release"
        )).isEqualTo(2);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy"
        )).isZero();

        WmsAllocationReleaseResult verified = inTransaction(() ->
            releaseService.verifyRelease(new WmsAllocationReleaseVerifyCommand(
                releaseCommand.effectKey(),
                evidence.evidenceHash(),
                releaseIdentity(reserveCommand.business()),
                reserved.allocationLockIds()
            )));
        assertThat(verified.releaseIds()).containsExactlyElementsOf(released.releaseIds());
        assertThat(verified.releasedChannelLockIds())
            .containsExactlyElementsOf(reserved.channelLockIds());
    }

    @Test
    void releaseRejectsUnknownExternalEffectEvidence() {
        seedCapacitiedStorage(STORAGE_A, "A-REL-UNKNOWN", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand reserveCommand = capacityCommand(
            STORAGE_A, "INV-REL-UNKNOWN", 1_201L, 1_201_001L, "REL-UNKNOWN",
            WEIGHT, "10.000", 3, "d".repeat(64)
        );
        WmsAllocationReservationResult reserved =
            inTransaction(() -> reservationService.reserve(reserveCommand));
        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(
                releaseCheckCommand(reserveCommand, reserved.allocationLockIds())));
        WmsAllocationReleaseCommand releaseCommand =
            releaseCommand(reserveCommand, evidence, "REL-UNKNOWN");
        jdbcTemplate.update("""
            update wms_allocation_lock
            set effect_result = 'UNKNOWN', revision = revision + 1
            where allocation_lock_id = ?
            """, reserved.allocationLockIds().getFirst());

        assertThatThrownBy(() -> inTransaction(() -> releaseService.release(releaseCommand)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsAllocationReleaseService.ERROR_EVIDENCE_INVALID));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_release"
        )).isZero();
    }

    @Test
    void releaseRejectsLockRevisionChangedAfterEvidenceCheck() {
        seedCapacitiedStorage(STORAGE_A, "A-REL-CHANGED", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand reserveCommand = capacityCommand(
            STORAGE_A, "INV-REL-CHANGED", 1_202L, 1_202_001L, "REL-CHANGED",
            WEIGHT, "10.000", 3, "e".repeat(64)
        );
        WmsAllocationReservationResult reserved =
            inTransaction(() -> reservationService.reserve(reserveCommand));
        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(
                releaseCheckCommand(reserveCommand, reserved.allocationLockIds())));
        WmsAllocationReleaseCommand releaseCommand =
            releaseCommand(reserveCommand, evidence, "REL-CHANGED");
        jdbcTemplate.update("""
            update wms_allocation_lock set revision = revision + 1
            where allocation_lock_id = ?
            """, reserved.allocationLockIds().getFirst());

        assertThatThrownBy(() -> inTransaction(() -> releaseService.release(releaseCommand)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsAllocationReleaseService.ERROR_EVIDENCE_CHANGED));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_release"
        )).isZero();
    }

    @Test
    void releaseReplayReturnsOriginalReleaseRowsWithoutDuplicates() {
        seedCapacitiedStorage(STORAGE_A, "A-REL-REPLAY", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand reserveCommand = capacityCommand(
            STORAGE_A, "INV-REL-REPLAY", 1_203L, 1_203_001L, "REL-REPLAY",
            WEIGHT, "10.000", 3, "f".repeat(64)
        );
        WmsAllocationReservationResult reserved =
            inTransaction(() -> reservationService.reserve(reserveCommand));
        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(
                releaseCheckCommand(reserveCommand, reserved.allocationLockIds())));
        WmsAllocationReleaseCommand releaseCommand =
            releaseCommand(reserveCommand, evidence, "REL-REPLAY");
        WmsAllocationReleaseResult first =
            inTransaction(() -> releaseService.release(releaseCommand));
        WmsAllocationReleaseResult replay =
            inTransaction(() -> releaseService.release(releaseCommand));

        assertThat(replay.releaseIds()).containsExactlyElementsOf(first.releaseIds());
        assertThat(replay.releasedChannelLockIds())
            .containsExactlyElementsOf(first.releasedChannelLockIds());
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_release"
        )).isOne();
    }

    @Test
    void releaseRejectsAnotherLockHolderTaskKey() {
        seedCapacitiedStorage(STORAGE_A, "A-REL-IDENTITY", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand reserveCommand = capacityCommand(
            STORAGE_A, "INV-REL-IDENTITY", 1_204L, 1_204_001L, "REL-IDENTITY",
            WEIGHT, "10.000", 3, "1".repeat(64)
        );
        WmsAllocationReservationResult reserved =
            inTransaction(() -> reservationService.reserve(reserveCommand));
        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(
                releaseCheckCommand(reserveCommand, reserved.allocationLockIds())));
        WmsAllocationReleaseCommand original =
            releaseCommand(reserveCommand, evidence, "REL-IDENTITY");
        WmsAllocationReleaseIdentity wrong = new WmsAllocationReleaseIdentity(
            original.identity().businessType(),
            original.identity().businessName(),
            original.identity().businessId(),
            original.identity().businessNo(),
            original.identity().businessLineId(),
            original.identity().allocationUnitId(),
            original.identity().roundNo(),
            original.identity().taskKey(),
            "OTHER-LOCK-TASK",
            original.identity().planInstanceId()
        );
        WmsAllocationReleaseCommand conflict = new WmsAllocationReleaseCommand(
            wrong,
            original.effectKey(),
            original.inputHash(),
            original.evidenceHash(),
            original.evidenceRevision(),
            original.evidenceRef(),
            original.expectations(),
            original.occurredAt(),
            original.reason(),
            original.sourceSystem(),
            original.requestId(),
            original.eventId(),
            original.attemptId(),
            original.actorId(),
            original.departmentId()
        );

        assertThatThrownBy(() -> inTransaction(() -> releaseService.release(conflict)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsAllocationReleaseService.ERROR_EFFECT_CONFLICT));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_release"
        )).isZero();
    }

    @Test
    void releaseRequiresCallerTransaction() {
        seedCapacitiedStorage(STORAGE_A, "A-REL-MANDATORY", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand command = capacityCommand(
            STORAGE_A, "INV-REL-MANDATORY", 1_205L, 1_205_001L, "REL-MANDATORY",
            WEIGHT, "10.000", 3, "2".repeat(64)
        );

        assertThatThrownBy(() -> releaseService.checkEvidence(
            releaseCheckCommand(command, List.of(1L))))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void productionReleaseActionsCommitCheckReleaseVerifyThroughSameTx() {
        seedCapacitiedStorage(STORAGE_A, "A-REL-PLATFORM", WEIGHT, "100.000", 3);
        String releaseInputHash = sha256("FORMAL-RELEASE");
        WmsAllocationReserveCommand reserveCommand = capacityCommandForSingleStorage(
            STORAGE_A,
            "INV-008",
            INSTANCE_ID,
            1_301L,
            "FORMAL-RELEASE-LOCK",
            WEIGHT,
            "10.000",
            3,
            "3".repeat(64)
        );
        WmsAllocationReservationResult reserved = inTransaction(() ->
            reservationService.reserve(reserveCommand));
        WmsAllocationReleaseCheckCommand checkCommand = releaseCheckCommand(
            reserveCommand, reserved.allocationLockIds());

        seedReleaseProcessChain(releaseInputHash);
        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(checkCommand));
        WmsAllocationReleaseCommand releaseCommand = releaseCommand(
            checkCommand.identity(), evidence, "FORMAL-RELEASE");
        ProcessAttempt checked = executeProduction(
            66_101L,
            RELEASE_INSTANCE_ID,
            productionCheckReleaseAction,
            WmsAllocationCheckReleaseAction.LOCAL_EFFECT_KEY,
            releaseCommand,
            releaseInputHash
        );

        assertThat(checked.getEffectResult().name()).isEqualTo("SUCCEEDED");
        assertThat(checked.getCheckpointJson()).contains("\"status\":\"EVIDENCE_CHECKED\"");
        assertThat(releaseStateValidator.validateBusiness(new StateContext(
            RELEASE_INSTANCE_ID,
            77_002L,
            66_101L,
            WmsAllocationReleaseStateValidator.STATE_START,
            WmsAllocationReleaseStateValidator.STATE_EVIDENCE_CHECKED,
            PostingMode.AUTOMATIC,
            releaseInputHash,
            checked.getEffectResult().name(),
            Map.of()
        )).passed()).isTrue();
        var evidenceCheckpoint = jsonMapper.readTree(checked.getCheckpointJson());
        assertThat(evidenceCheckpoint.path("evidenceHash").asText())
            .isEqualTo(evidence.evidenceHash());
        assertThat(evidenceCheckpoint.path("evidenceRevision").asInt())
            .isEqualTo(evidence.evidenceRevision());
        ProcessAttempt released = executeProduction(
            66_102L,
            RELEASE_INSTANCE_ID,
            productionReleaseAction,
            WmsAllocationReleaseAction.LOCAL_EFFECT_KEY,
            releaseCommand,
            releaseInputHash
        );
        ProcessAttempt verified = executeProduction(
            66_103L,
            RELEASE_INSTANCE_ID,
            productionVerifyReleaseAction,
            WmsAllocationVerifyReleaseAction.LOCAL_EFFECT_KEY,
            releaseCommand,
            releaseInputHash
        );

        assertThat(released.getEffectResult().name()).isEqualTo("SUCCEEDED");
        assertThat(released.getCheckpointJson()).contains("\"status\":\"RELEASED\"");
        assertThat(verified.getEffectResult().name()).isEqualTo("SUCCEEDED");
        assertThat(releaseStateValidator.validateBusiness(new StateContext(
            RELEASE_INSTANCE_ID,
            77_002L,
            66_102L,
            WmsAllocationReleaseStateValidator.STATE_EVIDENCE_CHECKED,
            WmsAllocationReleaseStateValidator.STATE_RELEASED,
            PostingMode.AUTOMATIC,
            releaseInputHash,
            released.getEffectResult().name(),
            Map.of()
        )).passed()).isTrue();
        assertThat(releaseStateValidator.validateBusiness(new StateContext(
            RELEASE_INSTANCE_ID,
            77_002L,
            66_103L,
            WmsAllocationReleaseStateValidator.STATE_RELEASED,
            WmsAllocationReleaseStateValidator.STATE_END,
            PostingMode.AUTOMATIC,
            releaseInputHash,
            verified.getEffectResult().name(),
            Map.of()
        )).passed()).isTrue();
        assertThat(releaseStateValidator.validateBusiness(new StateContext(
            RELEASE_INSTANCE_ID,
            77_002L,
            999_998L,
            WmsAllocationReleaseStateValidator.STATE_RELEASED,
            WmsAllocationReleaseStateValidator.STATE_END,
            PostingMode.AUTOMATIC,
            releaseInputHash,
            "SUCCEEDED",
            Map.of()
        )).passed()).isFalse();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from iws_process_event where id in (66101, 66102, 66103) "
                + "and event_status = 'SUCCESS'"
        )).isEqualTo(3);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_release "
                + "where task_key = 'INV-008:RELEASE' and plan_instance_id = ?",
            RELEASE_INSTANCE_ID
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy"
        )).isZero();
    }

    @Test
    void effectGenerationsBlockReleaseAfterDispatchIntent() {
        seedCapacitiedStorage(STORAGE_A, "A-EFFECT-GEN", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand reserveCommand = capacityCommandForSingleStorage(
            STORAGE_A,
            "INV-EFFECT-GEN",
            INSTANCE_ID,
            1_401L,
            "EFFECT-GEN",
            WEIGHT,
            "10.000",
            3,
            "4".repeat(64)
        );
        WmsAllocationReservationResult reserved = inTransaction(() ->
            reservationService.reserve(reserveCommand));
        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(
                releaseCheckCommand(reserveCommand, reserved.allocationLockIds())));
        WmsAllocationReleaseCommand releaseCommand =
            releaseCommand(reserveCommand, evidence, "EFFECT-GEN-RELEASE");
        WmsAllocationEffectIdentity identity = effectIdentity(reserveCommand.business());
        long lockId = reserved.allocationLockIds().getFirst();

        WmsAllocationEffectResult sendIntent = inTransaction(() ->
            effectService.advance(effectCommand(
                identity, lockId, 1, "NONE", "SEND_INTENT", null, null)));
        assertThat(sendIntent.generations()).containsExactly(2);
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select effect_result from wms_allocation_lock where allocation_lock_id = ?",
            lockId
        )).isEqualTo("SEND_INTENT");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_object_audit "
                + "where action = 'ALLOCATION_EFFECT_SEND_INTENT'"
        )).isEqualTo(2);
        assertThatThrownBy(() -> inTransaction(() -> releaseService.release(releaseCommand)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsAllocationReleaseService.ERROR_EVIDENCE_INVALID));

        WmsAllocationEffectCommand unknown = effectCommand(
            identity, lockId, 2, "SEND_INTENT", "UNKNOWN", null, null);
        assertThat(inTransaction(() -> effectService.advance(unknown))
            .targetEffectResult()).isEqualTo("UNKNOWN");
        assertThat(inTransaction(() -> effectService.advance(unknown))
            .targetEffectResult()).isEqualTo("UNKNOWN");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_object_audit "
                + "where action = 'ALLOCATION_EFFECT_UNKNOWN'"
        )).isEqualTo(2);
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select effect_result from wms_access_channel_lock where status = 'LOCKED'"
        )).isEqualTo("UNKNOWN");
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select after_summary from wms_object_audit "
                + "where action = 'ALLOCATION_EFFECT_UNKNOWN' "
                + "and object_type = 'WMS_ALLOCATION_LOCK' and object_id = ?",
            lockId
        )).contains("\"effectResult\":\"UNKNOWN\"");
    }

    @Test
    void materialChangeConsumesAllocationAndCommitsOccupancyTraceTogether() {
        WmsMaterialCompositeFixture fixture = seedMaterialComposite();
        WmsMaterialChangeResult result = inTransaction(() -> materialChangeService.apply(
            fixture.materialCommand(),
            fixture.loadChange(),
            fixture.consumption()
        ));

        long traceId = result.trace().history().traceId();
        assertThat(result.allocationEffect().targetEffectResult()).isEqualTo("CONSUMED");
        assertThat(result.loadChange().activeOccupancyIds()).isNotEmpty();
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select storage_code from wms_inventory where inventory_id = ?",
            fixture.inventoryId()
        )).isEqualTo("B-COMPOSITE");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_inventory_flow where idempotency_key = ?",
            fixture.effectKey()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_material_trace_history where effect_key = ?",
            fixture.effectKey()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_occupancy_effect "
                + "where effect_key = ? and trace_id = ?",
            fixture.effectKey(), traceId
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock "
                + "where allocation_lock_id = ? and status = 'CONSUMED' "
                + "and terminal_effect_key = ? and consumption_trace_id = ?",
            fixture.allocationLockId(), fixture.effectKey(), traceId
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel_lock "
                + "where status = 'CONSUMED' and terminal_effect_key = ? "
                + "and consumption_trace_id = ?",
            fixture.effectKey(), traceId
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy "
                + "where storage_id = ? and status = 'ACTIVE'",
            STORAGE_B
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy "
                + "where storage_id = ? and status = 'ACTIVE'",
            STORAGE_A
        )).isZero();
    }

    @Test
    void materialChangeCapacityFailureRollsBackAllocationConsumptionAndTrace() {
        WmsMaterialCompositeFixture fixture = seedMaterialComposite();
        jdbcTemplate.update(
            "update wms_storage_capacity set capacity_value = 0 "
                + "where storage_id = ? and dimension_key = 'TOP_LEVEL_LOAD'",
            STORAGE_B
        );

        assertThatThrownBy(() -> inTransaction(() -> materialChangeService.apply(
            fixture.materialCommand(),
            fixture.loadChange(),
            fixture.consumption()
        ))).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsLoadChangeService.ERROR_CAPACITY_EXCEEDED)
        );

        assertThat(WmsAllocationReservationTestDatabase.string(
            "select storage_code from wms_inventory where inventory_id = ?",
            fixture.inventoryId()
        )).isEqualTo("A-COMPOSITE");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_inventory_flow where idempotency_key = ?",
            fixture.effectKey()
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_material_trace_history where effect_key = ?",
            fixture.effectKey()
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select concat(status, '/', effect_result) from wms_allocation_lock "
                + "where allocation_lock_id = ?",
            fixture.allocationLockId()
        )).isEqualTo("LOCKED/ACCEPTED");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_occupancy_effect where effect_key = ?",
            fixture.effectKey()
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy "
                + "where storage_id = ? and status = 'ACTIVE'",
            STORAGE_A
        )).isOne();
    }

    @Test
    void materialChangeLocalActionCommitsCompositeFactsAndCheckpoint() {
        WmsMaterialCompositeFixture fixture = seedMaterialComposite();
        long eventId = 99_201L;
        seedMaterialProcess(
            eventId,
            fixture.materialCommand().taskKey(),
            fixture.materialCommand().inputHash()
        );
        materialChangeAction.prepare(
            fixture.inventoryId(),
            fixture.materialCommand().expectedVersion(),
            STORAGE_B,
            fixture.materialCommand().occurredAt(),
            null,
            fixture.loadChange(),
            fixture.consumption()
        );

        ProcessAttempt attempt = executeProduction(
            eventId,
            INSTANCE_ID,
            materialChangeAction,
            FixtureMaterialChangeAction.LOCAL_EFFECT_KEY,
            fixture.materialCommand(),
            fixture.materialCommand().inputHash()
        );

        String checkpoint = WmsAllocationReservationTestDatabase.string(
            "select checkpoint_json from iws_process_attempt where id = ?", attempt.getId());
        assertThat(attempt.getEffectResult().name()).isEqualTo("SUCCEEDED");
        assertThat(checkpoint)
            .contains("\"occupancyEffectId\":")
            .contains("\"activeOccupancyIds\":[")
            .contains("\"allocationLockIds\":[")
            .contains("\"allocationEffectResult\":\"CONSUMED\"")
            .contains("\"consumptionTraceId\":");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock "
                + "where allocation_lock_id = ? and status = 'CONSUMED'",
            fixture.allocationLockId()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_material_trace_history "
                + "where effect_key = ? and plan_instance_id = ? and event_id = ? "
                + "and attempt_id = ? and effect_result = 'APPLIED'",
            fixture.effectKey(), INSTANCE_ID, eventId, attempt.getId()
        )).isOne();
    }

    @Test
    void materialChangeLocalActionCapacityFailureRollsBackCheckpointAndPlatformClose() {
        WmsMaterialCompositeFixture fixture = seedMaterialComposite();
        jdbcTemplate.update(
            "update wms_storage_capacity set capacity_value = 0 "
                + "where storage_id = ? and dimension_key = 'TOP_LEVEL_LOAD'",
            STORAGE_B
        );
        long eventId = 99_202L;
        seedMaterialProcess(
            eventId,
            fixture.materialCommand().taskKey(),
            fixture.materialCommand().inputHash()
        );
        materialChangeAction.prepare(
            fixture.inventoryId(),
            fixture.materialCommand().expectedVersion(),
            STORAGE_B,
            fixture.materialCommand().occurredAt(),
            null,
            fixture.loadChange(),
            fixture.consumption()
        );

        assertThatThrownBy(() -> executeProduction(
            eventId,
            INSTANCE_ID,
            materialChangeAction,
            FixtureMaterialChangeAction.LOCAL_EFFECT_KEY,
            fixture.materialCommand(),
            fixture.materialCommand().inputHash()
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(
                WmsLoadChangeService.ERROR_CAPACITY_EXCEEDED));

        assertThat(WmsAllocationReservationTestDatabase.string(
            "select storage_code from wms_inventory where inventory_id = ?",
            fixture.inventoryId()
        )).isEqualTo("A-COMPOSITE");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_inventory_flow where idempotency_key = ?",
            fixture.effectKey()
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_material_trace_history where effect_key = ?",
            fixture.effectKey()
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select concat(status, '/', effect_result) from wms_allocation_lock "
                + "where allocation_lock_id = ?",
            fixture.allocationLockId()
        )).isEqualTo("LOCKED/ACCEPTED");
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select event_status from iws_process_event where id = ?", eventId
        )).isEqualTo("PROCESSING");
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select checkpoint_status from iws_process_attempt where event_id = ?", eventId
        )).isEqualTo("CLAIMED");
    }

    @Test
    void unknownEffectInvestigationReturnsLockChannelAndLatestEvidence() {
        seedCapacitiedStorage(STORAGE_A, "A-UNKNOWN-QUERY", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand unknownCommand = capacityCommandForSingleStorage(
            STORAGE_A,
            "QUERY-UNKNOWN",
            INSTANCE_ID,
            1_901L,
            "QUERY-UNKNOWN",
            WEIGHT,
            "10.000",
            3,
            "4".repeat(64)
        );
        WmsAllocationReservationResult unknownLock = inTransaction(() ->
            reservationService.reserve(unknownCommand));
        WmsAllocationEffectIdentity unknownIdentity =
            effectIdentity(unknownCommand.business());
        long allocationLockId = unknownLock.allocationLockIds().getFirst();
        inTransaction(() -> effectService.advance(effectCommand(
            unknownIdentity, allocationLockId, 1, "NONE", "SEND_INTENT", null, null)));
        assertThat(effectInvestigationService.findUnknown(
            new WmsAllocationEffectUnknownQuery(
                "INBOUND_FIXTURE", 88_001L, "QUERY-UNKNOWN", 20)))
            .isEmpty();
        inTransaction(() -> effectService.advance(effectCommand(
            unknownIdentity, allocationLockId, 2, "SEND_INTENT", "UNKNOWN", null, null)));

        List<WmsAllocationEffectUnknownView> unknown =
            effectInvestigationService.findUnknown(
                new WmsAllocationEffectUnknownQuery(
                    "INBOUND_FIXTURE", 88_001L, "QUERY-UNKNOWN", 20));

        assertThat(unknown).singleElement().satisfies(view -> {
            assertThat(view.allocationLockId()).isEqualTo(String.valueOf(allocationLockId));
            assertThat(view.channelLockId()).isNotNull();
            assertThat(view.channelId()).isEqualTo(String.valueOf(CHANNEL_A));
            assertThat(view.storageId()).isEqualTo(String.valueOf(STORAGE_A));
            assertThat(view.businessType()).isEqualTo("INBOUND_FIXTURE");
            assertThat(view.taskKey()).isEqualTo("QUERY-UNKNOWN");
            assertThat(view.channelStatus()).isEqualTo("LOCKED");
            assertThat(view.channelEffectResult()).isEqualTo("UNKNOWN");
            assertThat(view.channelAligned()).isTrue();
            assertThat(view.latestUnknownAuditId()).isNotNull();
            assertThat(view.latestUnknownEvidenceJson())
                .contains("\"targetEffectResult\":\"UNKNOWN\"")
                .contains("\"taskKey\":\"QUERY-UNKNOWN\"");
            assertThat(view.unknownOccurredAt()).isNotNull();
            assertThat(view.lockUpdatedAt()).isNotNull();
        });
        assertThat(effectInvestigationService.findUnknown(
            new WmsAllocationEffectUnknownQuery(
                "INBOUND_FIXTURE", 88_001L, "ANOTHER-TASK", 20)))
            .isEmpty();
        assertThatThrownBy(() -> effectInvestigationService.findUnknown(
            new WmsAllocationEffectUnknownQuery(
                "INBOUND_FIXTURE", 88_001L, "QUERY-UNKNOWN", 0)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).contains("查询上限必须为1到200"));
    }

    @Test
    void reconciledUnknownEvidenceReleasesLockButPlainNoEffectDoesNot() {
        seedCapacitiedStorage(STORAGE_A, "A-UNKNOWN-RECONCILE", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand reserveCommand = capacityCommandForSingleStorage(
            STORAGE_A,
            "RECONCILE-UNKNOWN",
            INSTANCE_ID,
            1_902L,
            "RECONCILE-UNKNOWN",
            WEIGHT,
            "10.000",
            3,
            "5".repeat(64)
        );
        WmsAllocationReservationResult reserved = inTransaction(() ->
            reservationService.reserve(reserveCommand));
        WmsAllocationEffectIdentity identity = effectIdentity(reserveCommand.business());
        long lockId = reserved.allocationLockIds().getFirst();
        String reconciliationRef = "evidence://device/reconcile/no-effect";
        assertThatThrownBy(() -> inTransaction(() ->
            releaseService.checkEvidence(new WmsAllocationReleaseCheckCommand(
                releaseIdentity(reserveCommand.business()),
                "RECONCILED_NO_EFFECT",
                reconciliationRef,
                reserved.allocationLockIds()
            ))))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsAllocationReleaseService.ERROR_EVIDENCE_INVALID));
        inTransaction(() -> effectService.advance(effectCommand(
            identity, lockId, 1, "NONE", "SEND_INTENT", null, null)));
        inTransaction(() -> effectService.advance(effectCommand(
            identity, lockId, 2, "SEND_INTENT", "UNKNOWN", null, null)));

        assertThatThrownBy(() -> inTransaction(() ->
            releaseService.checkEvidence(
                releaseCheckCommand(reserveCommand, reserved.allocationLockIds()))))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsAllocationReleaseService.ERROR_EVIDENCE_INVALID));

        jdbcTemplate.update(
            "update wms_access_channel_lock set effect_result = 'ACCEPTED' "
                + "where status = 'LOCKED'");
        try {
            assertThatThrownBy(() -> inTransaction(() ->
                releaseService.checkEvidence(new WmsAllocationReleaseCheckCommand(
                    releaseIdentity(reserveCommand.business()),
                    "RECONCILED_NO_EFFECT",
                    reconciliationRef,
                    reserved.allocationLockIds()
                ))))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                    assertThat(error.getMessage())
                        .startsWith(WmsAllocationReleaseService.ERROR_EVIDENCE_INVALID));
        } finally {
            jdbcTemplate.update(
                "update wms_access_channel_lock set effect_result = 'UNKNOWN' "
                    + "where status = 'LOCKED'");
        }

        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(new WmsAllocationReleaseCheckCommand(
                releaseIdentity(reserveCommand.business()),
                "RECONCILED_NO_EFFECT",
                reconciliationRef,
                reserved.allocationLockIds()
            )));
        assertThat(evidence.expectations()).singleElement().satisfies(item -> {
            assertThat(item.allocationLockId()).isEqualTo(lockId);
            assertThat(item.effectResult()).isEqualTo("UNKNOWN");
        });
        WmsAllocationReleaseResult released = inTransaction(() ->
            releaseService.release(
                releaseCommand(
                    reserveCommand,
                    evidence,
                    "UNKNOWN-RECONCILED",
                    "RECONCILED_NO_EFFECT",
                    reconciliationRef
                )));
        assertThat(released.allocationLockIds()).containsExactly(lockId);
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select concat(status, '/', effect_result) from wms_allocation_lock "
                + "where allocation_lock_id = ?",
            lockId
        )).isEqualTo("RELEASED/NO_EFFECT");
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select evidence_ref from wms_allocation_release "
                + "where allocation_lock_id = ?",
            lockId
        )).isEqualTo(reconciliationRef);
        assertThat(effectInvestigationService.findUnknown(
            new WmsAllocationEffectUnknownQuery(
                "INBOUND_FIXTURE", 88_001L, "RECONCILE-UNKNOWN", 20)))
            .isEmpty();
    }

    @Test
    void membershipChangeLocksOldNewChannelsAndStorageInStableOrder() {
        seedCapacitiedStorage(STORAGE_A, "A-MEMBERSHIP-MOVE", WEIGHT, "100.000", 3);
        insertChannel(CHANNEL_B, "CH-B", ZONE_ID);
        WmsChannelMembershipChangeCommand command = new WmsChannelMembershipChangeCommand(
            STORAGE_A,
            CHANNEL_B,
            2,
            CHANNEL_A,
            9_000L + STORAGE_A,
            1,
            1,
            1,
            LocalDateTime.now(),
            9001L,
            9101L
        );

        WmsChannelMembershipChangeResult result = inTransaction(() ->
            channelMembershipService.change(command));

        assertThat(result.oldChannelId()).isEqualTo(CHANNEL_A);
        assertThat(result.newChannelId()).isEqualTo(CHANNEL_B);
        assertThat(result.memberId()).isNotEqualTo(9_000L + STORAGE_A);
        assertThat(result.memberRevision()).isOne();
        assertThat(result.oldChannelRevision()).isEqualTo(2);
        assertThat(result.newChannelRevision()).isEqualTo(2);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_member "
                + "where channel_id = ? and storage_id = ? and access_order = 2 and revision = 1",
            CHANNEL_B,
            STORAGE_A
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_member "
                + "where channel_id = ? and storage_id = ?",
            CHANNEL_A,
            STORAGE_A
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id in (?, ?) and revision = 2",
            CHANNEL_A,
            CHANNEL_B
        )).isEqualTo(2);
    }

    @Test
    void membershipChangeRejectsActiveLockThenUnknownEvidence() {
        seedCapacitiedStorage(STORAGE_A, "A-MEMBERSHIP-UNKNOWN", WEIGHT, "100.000", 3);
        insertChannel(CHANNEL_B, "CH-B-UNKNOWN", ZONE_ID);
        WmsAllocationReserveCommand reserveCommand = capacityCommandForSingleStorage(
            STORAGE_A,
            "INV-MEMBERSHIP-UNKNOWN",
            INSTANCE_ID,
            1_404L,
            "MEMBERSHIP-UNKNOWN",
            WEIGHT,
            "10.000",
            3,
            "7".repeat(64)
        );
        WmsAllocationReservationResult reserved = inTransaction(() ->
            reservationService.reserve(reserveCommand));
        WmsChannelMembershipChangeCommand command = new WmsChannelMembershipChangeCommand(
            STORAGE_A,
            CHANNEL_B,
            2,
            CHANNEL_A,
            9_000L + STORAGE_A,
            1,
            1,
            1,
            LocalDateTime.now(),
            9001L,
            9101L
        );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.change(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_CHANNEL_BUSY));

        WmsAllocationEffectIdentity identity = effectIdentity(reserveCommand.business());
        long lockId = reserved.allocationLockIds().getFirst();
        inTransaction(() -> effectService.advance(effectCommand(
            identity, lockId, 1, "NONE", "SEND_INTENT", null, null)));
        inTransaction(() -> effectService.advance(effectCommand(
            identity, lockId, 2, "SEND_INTENT", "UNKNOWN", null, null)));

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.change(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_EVIDENCE_UNKNOWN));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_member "
                + "where channel_id = ? and storage_id = ?",
            CHANNEL_A,
            STORAGE_A
        )).isOne();
    }

    @Test
    void membershipChangeAndEffectAdvanceSerializeWithoutDeadlock() throws Exception {
        seedCapacitiedStorage(STORAGE_A, "A-MEMBERSHIP-RACE", WEIGHT, "100.000", 3);
        insertChannel(CHANNEL_B, "CH-B-RACE", ZONE_ID);
        WmsAllocationReserveCommand reserveCommand = capacityCommandForSingleStorage(
            STORAGE_A,
            "INV-MEMBERSHIP-RACE",
            INSTANCE_ID,
            1_405L,
            "MEMBERSHIP-RACE",
            WEIGHT,
            "10.000",
            3,
            "9".repeat(64)
        );
        WmsAllocationReservationResult reserved = inTransaction(() ->
            reservationService.reserve(reserveCommand));
        WmsAllocationEffectCommand sendIntent = effectCommand(
            effectIdentity(reserveCommand.business()),
            reserved.allocationLockIds().getFirst(),
            1,
            "NONE",
            "SEND_INTENT",
            null,
            null
        );
        WmsChannelMembershipChangeCommand membership =
            new WmsChannelMembershipChangeCommand(
                STORAGE_A,
                CHANNEL_B,
                2,
                CHANNEL_A,
                9_000L + STORAGE_A,
                1,
                1,
                1,
                LocalDateTime.now(),
                9001L,
                9101L
            );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OperationOutcome> effect = executor.submit(operationWorker(
                () -> inTransaction(() -> effectService.advance(sendIntent)),
                ready,
                start
            ));
            Future<OperationOutcome> move = executor.submit(operationWorker(
                () -> inTransaction(() -> channelMembershipService.change(membership)),
                ready,
                start
            ));
            assertThat(ready.await(20L, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            OperationOutcome effectOutcome = effect.get(20L, TimeUnit.SECONDS);
            OperationOutcome moveOutcome = move.get(20L, TimeUnit.SECONDS);

            assertThat(effectOutcome.error()).isNull();
            assertThat(moveOutcome.error())
                .isInstanceOfSatisfying(ServiceException.class, error ->
                    assertThat(error.getMessage())
                        .startsWith(WmsChannelMembershipService.ERROR_CHANNEL_BUSY));
            assertThat(WmsAllocationReservationTestDatabase.string(
                "select effect_result from wms_allocation_lock where allocation_lock_id = ?",
                reserved.allocationLockIds().getFirst()
            )).isEqualTo("SEND_INTENT");
            assertThat(WmsAllocationReservationTestDatabase.count(
                "select count(*) from wms_storage_access_member "
                    + "where channel_id = ? and storage_id = ?",
                CHANNEL_A,
                STORAGE_A
            )).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void membershipChangeRejectsActiveOccupancy() {
        seedCapacitiedStorage(STORAGE_A, "A-MEMBERSHIP-OCCUPIED", WEIGHT, "100.000", 3);
        insertChannel(CHANNEL_B, "CH-B-OCCUPIED", ZONE_ID);
        seedActiveTopLevelOccupancy("membership-occupied", STORAGE_A, 77_001L);
        WmsChannelMembershipChangeCommand command = new WmsChannelMembershipChangeCommand(
            STORAGE_A,
            CHANNEL_B,
            2,
            CHANNEL_A,
            9_000L + STORAGE_A,
            1,
            1,
            1,
            LocalDateTime.now(),
            9001L,
            9101L
        );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.change(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_CHANNEL_BUSY)
                    .contains("活动占用"));
    }

    @Test
    void membershipChangeRejectsStaleChannelRevision() {
        seedCapacitiedStorage(STORAGE_A, "A-MEMBERSHIP-STALE", WEIGHT, "100.000", 3);
        insertChannel(CHANNEL_B, "CH-B-STALE", ZONE_ID);
        WmsChannelMembershipChangeCommand command = new WmsChannelMembershipChangeCommand(
            STORAGE_A,
            CHANNEL_B,
            2,
            CHANNEL_A,
            9_000L + STORAGE_A,
            1,
            1,
            2,
            LocalDateTime.now(),
            9001L,
            9101L
        );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.change(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_MEMBERSHIP_CONFLICT)
                    .contains("目标通道修订号"));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_member "
                + "where channel_id = ? and storage_id = ?",
            CHANNEL_A,
            STORAGE_A
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id in (?, ?) and revision = 1",
            CHANNEL_A,
            CHANNEL_B
        )).isEqualTo(2);
    }

    @Test
    void accessOrderChangeSwapsMembersAndPreservesTheirIdentities() {
        seedCapacitiedStorage(STORAGE_A, "A-ORDER-SWAP", WEIGHT, "100.000", 3);
        seedChannel(STORAGE_B, "B-ORDER-SWAP", 2);
        long firstMemberId = 9_000L + STORAGE_A;
        long secondMemberId = 9_000L + STORAGE_B;
        WmsChannelAccessOrderChangeCommand command =
            new WmsChannelAccessOrderChangeCommand(
                CHANNEL_A,
                1,
                List.of(
                    new WmsChannelAccessOrderChangeCommand.MemberOrder(
                        firstMemberId, 1, 2),
                    new WmsChannelAccessOrderChangeCommand.MemberOrder(
                        secondMemberId, 1, 1)
                ),
                LocalDateTime.now(),
                9001L,
                9101L
            );

        WmsChannelAccessOrderChangeResult result = inTransaction(() ->
            channelMembershipService.reorder(command));

        assertThat(result.channelRevision()).isEqualTo(2);
        assertThat(result.members())
            .extracting(
                WmsChannelAccessOrderChangeResult.MemberOrderResult::memberId,
                WmsChannelAccessOrderChangeResult.MemberOrderResult::storageId,
                WmsChannelAccessOrderChangeResult.MemberOrderResult::oldAccessOrder,
                WmsChannelAccessOrderChangeResult.MemberOrderResult::accessOrder,
                WmsChannelAccessOrderChangeResult.MemberOrderResult::revision
            )
            .containsExactly(
                tuple(secondMemberId, STORAGE_B, 2, 1, 2),
                tuple(firstMemberId, STORAGE_A, 1, 2, 2)
            );
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_member "
                + "where channel_id = ? and access_order in (1, 2) and revision = 2",
            CHANNEL_A
        )).isEqualTo(2);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id = ? and revision = 2",
            CHANNEL_A
        )).isOne();
    }

    @Test
    void accessOrderChangeRejectsOrderOwnedByUnchangedMember() {
        seedCapacitiedStorage(STORAGE_A, "A-ORDER-COLLISION", WEIGHT, "100.000", 3);
        seedChannel(STORAGE_B, "B-ORDER-COLLISION", 2);
        seedStorage(7_103L, "C-ORDER-COLLISION", 0);
        jdbcTemplate.update(
            "insert into wms_storage_access_member "
                + "(member_id, channel_id, storage_id, access_order, revision) "
                + "values (?, ?, ?, 3, 1)",
            9_000L + 7_103L,
            CHANNEL_A,
            7_103L
        );
        WmsChannelAccessOrderChangeCommand command =
            new WmsChannelAccessOrderChangeCommand(
                CHANNEL_A,
                1,
                List.of(new WmsChannelAccessOrderChangeCommand.MemberOrder(
                    9_000L + STORAGE_A, 1, 2)),
                LocalDateTime.now(),
                9001L,
                9101L
            );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.reorder(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_MEMBERSHIP_CONFLICT)
                    .contains("未调整成员"));

        assertThat(WmsAllocationReservationTestDatabase.string(
            "select access_order from wms_storage_access_member where member_id = ?",
            9_000L + STORAGE_A
        )).isEqualTo("1");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id = ? and revision = 1",
            CHANNEL_A
        )).isOne();
    }

    @Test
    void accessOrderChangeRejectsActiveAllocationLock() {
        seedCapacitiedStorage(STORAGE_A, "A-ORDER-BUSY", WEIGHT, "100.000", 3);
        seedChannel(STORAGE_B, "B-ORDER-BUSY", 2);
        inTransaction(() -> reservationService.reserve(
            capacityCommandForSingleStorage(
                STORAGE_A,
                "INV-ORDER-BUSY",
                INSTANCE_ID,
                1_406L,
                "ORDER-BUSY",
                WEIGHT,
                "10.000",
                3,
                "8".repeat(64)
            )));
        WmsChannelAccessOrderChangeCommand command =
            new WmsChannelAccessOrderChangeCommand(
                CHANNEL_A,
                1,
                List.of(
                    new WmsChannelAccessOrderChangeCommand.MemberOrder(
                        9_000L + STORAGE_A, 1, 2),
                    new WmsChannelAccessOrderChangeCommand.MemberOrder(
                        9_000L + STORAGE_B, 1, 1)
                ),
                LocalDateTime.now(),
                9001L,
                9101L
            );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.reorder(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_CHANNEL_BUSY));

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_member "
                + "where channel_id = ? and access_order in (1, 2) and revision = 1",
            CHANNEL_A
        )).isEqualTo(2);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id = ? and revision = 1",
            CHANNEL_A
        )).isOne();
    }

    @Test
    void accessOrderChangeRejectsStaleMemberRevisionWithoutPartialUpdate() {
        seedCapacitiedStorage(STORAGE_A, "A-ORDER-STALE", WEIGHT, "100.000", 3);
        seedChannel(STORAGE_B, "B-ORDER-STALE", 2);
        WmsChannelAccessOrderChangeCommand command =
            new WmsChannelAccessOrderChangeCommand(
                CHANNEL_A,
                1,
                List.of(
                    new WmsChannelAccessOrderChangeCommand.MemberOrder(
                        9_000L + STORAGE_A, 2, 2),
                    new WmsChannelAccessOrderChangeCommand.MemberOrder(
                        9_000L + STORAGE_B, 1, 1)
                ),
                LocalDateTime.now(),
                9001L,
                9101L
            );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.reorder(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_MEMBERSHIP_CONFLICT)
                    .contains("成员修订号"));

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_member "
                + "where channel_id = ? and access_order in (1, 2) and revision = 1",
            CHANNEL_A
        )).isEqualTo(2);
    }

    @Test
    void channelDefinitionChangeUpdatesCodeModeStatusAndRevision() {
        seedCapacitiedStorage(STORAGE_A, "A-CONFIG", WEIGHT, "100.000", 3);
        WmsChannelDefinitionChangeCommand command =
            new WmsChannelDefinitionChangeCommand(
                CHANNEL_A,
                1,
                "CH-A-NEW",
                "SHUTTLE",
                "RETIRING",
                LocalDateTime.now(),
                9001L,
                9101L
            );

        WmsChannelDefinitionChangeResult result = inTransaction(() ->
            channelMembershipService.changeDefinition(command));

        assertThat(result.channelCode()).isEqualTo("CH-A-NEW");
        assertThat(result.accessMode()).isEqualTo("SHUTTLE");
        assertThat(result.status()).isEqualTo("RETIRING");
        assertThat(result.revision()).isEqualTo(2);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id = ? and channel_code = 'CH-A-NEW' "
                + "and access_mode = 'SHUTTLE' and status = 'RETIRING' and revision = 2",
            CHANNEL_A
        )).isOne();
    }

    @Test
    void channelDefinitionChangeRejectsStaleRevision() {
        seedCapacitiedStorage(STORAGE_A, "A-CONFIG-STALE", WEIGHT, "100.000", 3);
        WmsChannelDefinitionChangeCommand command =
            new WmsChannelDefinitionChangeCommand(
                CHANNEL_A,
                2,
                "CH-A-STALE",
                "SHUTTLE",
                "RETIRING",
                LocalDateTime.now(),
                9001L,
                9101L
            );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.changeDefinition(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_CONFIGURATION_CONFLICT));

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id = ? and channel_code = 'CH-A' "
                + "and access_mode = 'FORK' and status = 'ACTIVE' and revision = 1",
            CHANNEL_A
        )).isOne();
    }

    @Test
    void channelDefinitionChangeRejectsActiveAllocationLock() {
        seedCapacitiedStorage(STORAGE_A, "A-CONFIG-BUSY", WEIGHT, "100.000", 3);
        inTransaction(() -> reservationService.reserve(
            capacityCommandForSingleStorage(
                STORAGE_A,
                "INV-CONFIG-BUSY",
                INSTANCE_ID,
                1_407L,
                "CONFIG-BUSY",
                WEIGHT,
                "10.000",
                3,
                "a".repeat(64)
            )));
        WmsChannelDefinitionChangeCommand command =
            new WmsChannelDefinitionChangeCommand(
                CHANNEL_A,
                1,
                "CH-A-BUSY",
                "SHUTTLE",
                "RETIRING",
                LocalDateTime.now(),
                9001L,
                9101L
            );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.changeDefinition(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_CHANNEL_BUSY));

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id = ? and channel_code = 'CH-A' and revision = 1",
            CHANNEL_A
        )).isOne();
    }

    @Test
    void storageAccessEdgeAddLocksAndRevisionsAllRelatedChannels() {
        seedCapacitiedStorage(STORAGE_A, "A-EDGE-ADD", WEIGHT, "100.000", 3);
        insertChannel(CHANNEL_B, "CH-B-EDGE-ADD", ZONE_ID);
        seedStorage(STORAGE_B, "B-EDGE-ADD", 0);
        jdbcTemplate.update(
            "insert into wms_storage_access_member "
                + "(member_id, channel_id, storage_id, access_order, revision) "
                + "values (?, ?, ?, 1, 1)",
            9_000L + STORAGE_B,
            CHANNEL_B,
            STORAGE_B
        );
        WmsStorageAccessEdgeAddCommand command = new WmsStorageAccessEdgeAddCommand(
            STORAGE_A,
            STORAGE_B,
            LocalDateTime.now(),
            9001L,
            9101L
        );

        WmsStorageAccessEdgeChangeResult result = inTransaction(() ->
            channelMembershipService.addEdge(command));

        assertThat(result.removed()).isFalse();
        assertThat(result.edgeRevision()).isOne();
        assertThat(result.channelRevisions())
            .extracting(
                WmsStorageAccessEdgeChangeResult.ChannelRevision::channelId,
                WmsStorageAccessEdgeChangeResult.ChannelRevision::revision)
            .containsExactly(
                tuple(CHANNEL_A, 2),
                tuple(CHANNEL_B, 2)
            );
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_edge "
                + "where blocker_storage_id = ? and blocked_storage_id = ? and revision = 1",
            STORAGE_A,
            STORAGE_B
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id in (?, ?) and revision = 2",
            CHANNEL_A,
            CHANNEL_B
        )).isEqualTo(2);
    }

    @Test
    void storageAccessEdgeRemoveChecksRevisionAndRevisionsChannels() {
        seedCapacitiedStorage(STORAGE_A, "A-EDGE-REMOVE", WEIGHT, "100.000", 3);
        seedChannel(STORAGE_B, "B-EDGE-REMOVE", 2);
        long edgeId = 9_101L;
        jdbcTemplate.update(
            "insert into wms_storage_access_edge "
                + "(access_edge_id, blocker_storage_id, blocked_storage_id, revision) "
                + "values (?, ?, ?, 1)",
            edgeId,
            STORAGE_A,
            STORAGE_B
        );
        WmsStorageAccessEdgeRemoveCommand command =
            new WmsStorageAccessEdgeRemoveCommand(
                edgeId,
                1,
                LocalDateTime.now(),
                9001L,
                9101L
            );

        WmsStorageAccessEdgeChangeResult result = inTransaction(() ->
            channelMembershipService.removeEdge(command));

        assertThat(result.removed()).isTrue();
        assertThat(result.edgeRevision()).isOne();
        assertThat(result.channelRevisions()).containsExactly(
            new WmsStorageAccessEdgeChangeResult.ChannelRevision(CHANNEL_A, 2));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_edge where access_edge_id = ?",
            edgeId
        )).isZero();
    }

    @Test
    void storageAccessEdgeAddRejectsActiveAllocationLock() {
        seedCapacitiedStorage(STORAGE_A, "A-EDGE-BUSY", WEIGHT, "100.000", 3);
        seedChannel(STORAGE_B, "B-EDGE-BUSY", 2);
        inTransaction(() -> reservationService.reserve(
            capacityCommandForSingleStorage(
                STORAGE_A,
                "INV-EDGE-BUSY",
                INSTANCE_ID,
                1_408L,
                "EDGE-BUSY",
                WEIGHT,
                "10.000",
                3,
                "b".repeat(64)
            )));
        WmsStorageAccessEdgeAddCommand command = new WmsStorageAccessEdgeAddCommand(
            STORAGE_A,
            STORAGE_B,
            LocalDateTime.now(),
            9001L,
            9101L
        );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.addEdge(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_CHANNEL_BUSY));

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_edge"
        )).isZero();
    }

    @Test
    void storageAccessEdgeRemoveRejectsStaleRevisionWithoutDeleting() {
        seedCapacitiedStorage(STORAGE_A, "A-EDGE-STALE", WEIGHT, "100.000", 3);
        seedChannel(STORAGE_B, "B-EDGE-STALE", 2);
        long edgeId = 9_102L;
        jdbcTemplate.update(
            "insert into wms_storage_access_edge "
                + "(access_edge_id, blocker_storage_id, blocked_storage_id, revision) "
                + "values (?, ?, ?, 1)",
            edgeId,
            STORAGE_A,
            STORAGE_B
        );
        WmsStorageAccessEdgeRemoveCommand command =
            new WmsStorageAccessEdgeRemoveCommand(
                edgeId,
                2,
                LocalDateTime.now(),
                9001L,
                9101L
            );

        assertThatThrownBy(() -> inTransaction(() ->
            channelMembershipService.removeEdge(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsChannelMembershipService.ERROR_EDGE_CONFLICT)
                    .contains("修订号"));

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_access_edge "
                + "where access_edge_id = ? and revision = 1",
            edgeId
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel "
                + "where channel_id = ? and revision = 1",
            CHANNEL_A
        )).isOne();
    }

    @Test
    void consumedEffectIsTerminalAndCannotBeReleased() {
        seedCapacitiedStorage(STORAGE_A, "A-EFFECT-CONSUME", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand reserveCommand = capacityCommandForSingleStorage(
            STORAGE_A,
            "INV-EFFECT-CONSUME",
            INSTANCE_ID,
            1_402L,
            "EFFECT-CONSUME",
            WEIGHT,
            "10.000",
            3,
            "5".repeat(64)
        );
        WmsAllocationReservationResult reserved = inTransaction(() ->
            reservationService.reserve(reserveCommand));
        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(
                releaseCheckCommand(reserveCommand, reserved.allocationLockIds())));
        WmsAllocationEffectIdentity identity = effectIdentity(reserveCommand.business());
        long lockId = reserved.allocationLockIds().getFirst();
        inTransaction(() -> effectService.advance(effectCommand(
            identity, lockId, 1, "NONE", "SEND_INTENT", null, null)));
        inTransaction(() -> effectService.advance(effectCommand(
            identity, lockId, 2, "SEND_INTENT", "ACCEPTED", null, null)));
        String terminalKey = effectKey("CONSUME-TERMINAL");
        WmsAllocationEffectCommand consumeCommand = effectCommand(
            identity, lockId, 3, "ACCEPTED", "CONSUMED", terminalKey, 55_001L);

        WmsAllocationEffectResult consumed = inTransaction(() ->
            effectService.advance(consumeCommand));

        assertThat(consumed.generations()).containsExactly(4);
        assertThat(inTransaction(() -> effectService.advance(consumeCommand))
            .generations()).containsExactly(4);
        assertThatThrownBy(() -> inTransaction(() -> effectService.advance(
            effectCommand(
                identity, lockId, 4, "CONSUMED", "CONSUMED",
                terminalKey, 55_002L))))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsAllocationEffectService.ERROR_STATE_CONFLICT));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock "
                + "where status = 'CONSUMED' and effect_result = 'CONSUMED' "
                + "and terminal_effect_key = ? and consumption_trace_id = 55001",
            terminalKey
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel_lock "
                + "where status = 'CONSUMED' and effect_result = 'CONSUMED' "
                + "and terminal_effect_key = ? and consumption_trace_id = 55001",
            terminalKey
        )).isOne();
        assertThatThrownBy(() -> inTransaction(() -> releaseService.release(
            releaseCommand(reserveCommand, evidence, "CONSUMED-RELEASE"))))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsAllocationReleaseService.ERROR_ALREADY_CONSUMED));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy"
        )).isZero();
    }

    @Test
    void releaseAndSendIntentRaceHasSingleGenerationWinner() throws Exception {
        seedCapacitiedStorage(STORAGE_A, "A-EFFECT-RACE", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand reserveCommand = capacityCommandForSingleStorage(
            STORAGE_A,
            "INV-EFFECT-RACE",
            INSTANCE_ID,
            1_403L,
            "EFFECT-RACE",
            WEIGHT,
            "10.000",
            3,
            "6".repeat(64)
        );
        WmsAllocationReservationResult reserved = inTransaction(() ->
            reservationService.reserve(reserveCommand));
        WmsAllocationEvidenceResult evidence = inTransaction(() ->
            releaseService.checkEvidence(
                releaseCheckCommand(reserveCommand, reserved.allocationLockIds())));
        WmsAllocationReleaseCommand releaseCommand =
            releaseCommand(reserveCommand, evidence, "EFFECT-RACE-RELEASE");
        WmsAllocationEffectCommand sendIntent = effectCommand(
            effectIdentity(reserveCommand.business()),
            reserved.allocationLockIds().getFirst(),
            1,
            "NONE",
            "SEND_INTENT",
            null,
            null
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OperationOutcome> release = executor.submit(operationWorker(
                () -> inTransaction(() -> releaseService.release(releaseCommand)),
                ready,
                start
            ));
            Future<OperationOutcome> dispatch = executor.submit(operationWorker(
                () -> inTransaction(() -> effectService.advance(sendIntent)),
                ready,
                start
            ));
            assertThat(ready.await(20L, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            OperationOutcome releaseOutcome = release.get(20L, TimeUnit.SECONDS);
            OperationOutcome dispatchOutcome = dispatch.get(20L, TimeUnit.SECONDS);

            assertThat(List.of(releaseOutcome, dispatchOutcome).stream()
                .filter(outcome -> outcome.error() == null).count()).isOne();
            String status = WmsAllocationReservationTestDatabase.string(
                "select status from wms_allocation_lock where allocation_lock_id = ?",
                reserved.allocationLockIds().getFirst()
            );
            assertThat(status).isIn("RELEASED", "LOCKED");
            assertThat(WmsAllocationReservationTestDatabase.string(
                "select effect_result from wms_allocation_lock where allocation_lock_id = ?",
                reserved.allocationLockIds().getFirst()
            )).isEqualTo("RELEASED".equals(status) ? "NO_EFFECT" : "SEND_INTENT");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void loadChangeClosesLeafSlotWhenMaterialIsLoadedIntoCarrier() {
        long carrierId = 21_001L;
        long materialId = 21_002L;
        seedStorage(STORAGE_A, "LOAD-A", 0);
        seedTopLevelCapacity(STORAGE_A, "2");
        seedLoadNode(carrierId, "CARRIER", null, 31_001L);
        seedLoadNode(materialId, "MATERIAL", 41_001L, null);
        seedStorageInfo(carrierId, STORAGE_A);
        seedStorageInfo(materialId, STORAGE_A);
        seedActiveTopLevelOccupancy("LOAD-INITIAL", STORAGE_A, carrierId, materialId);
        WmsLoadChangeCommand command = loadCommand(
            "LOAD-INTO-CARRIER", "INV-LOAD", "INBOUND_FIXTURE",
            List.of(new WmsLoadMutation(materialId, null, 1, carrierId, null, null))
        );

        WmsLoadChangeResult result = inTransaction(() -> loadChangeService.apply(command));

        assertThat(result.storageRootCounts()).containsEntry(STORAGE_A, 1);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_load_relation where child_node_id = ? and parent_node_id = ?",
            materialId, carrierId
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy where status = 'ACTIVE'"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy where status = 'CLOSED'"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_load_relation_history where effect_key = ?",
            command.effectKey()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select effect_result from wms_occupancy_effect where effect_key = ?",
            command.effectKey()
        )).isEqualTo("APPLIED");
    }

    @Test
    void loadChangeSplitsOneRootIntoTwoWithinTopLevelCapacity() {
        long carrierId = 22_001L;
        long materialId = 22_002L;
        seedStorage(STORAGE_A, "LOAD-SPLIT", 0);
        seedTopLevelCapacity(STORAGE_A, "2");
        seedLoadNode(carrierId, "CARRIER", null, 32_001L);
        seedLoadNode(materialId, "MATERIAL", 42_001L, null);
        seedLoadRelation(51_001L, materialId, carrierId);
        seedStorageInfo(carrierId, STORAGE_A);
        seedActiveTopLevelOccupancy("SPLIT-INITIAL", STORAGE_A, carrierId);
        WmsLoadChangeCommand command = loadCommand(
            "SPLIT-ROOT", "INV-LOAD", "INBOUND_FIXTURE",
            List.of(new WmsLoadMutation(
                materialId, carrierId, 1, null, "STORAGE", STORAGE_A))
        );

        WmsLoadChangeResult result = inTransaction(() -> loadChangeService.apply(command));

        assertThat(result.storageRootCounts()).containsEntry(STORAGE_A, 2);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_load_relation where child_node_id = ?", materialId
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy where status = 'ACTIVE'"
        )).isEqualTo(2);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_info where root_node_id = ?", materialId
        )).isOne();
    }

    @Test
    void loadChangeCapacityFailureRollsBackRelationHistoryAndOccupancy() {
        long carrierId = 23_001L;
        long materialId = 23_002L;
        seedStorage(STORAGE_A, "LOAD-CAP", 0);
        seedTopLevelCapacity(STORAGE_A, "1");
        seedLoadNode(carrierId, "CARRIER", null, 33_001L);
        seedLoadNode(materialId, "MATERIAL", 43_001L, null);
        seedLoadRelation(52_001L, materialId, carrierId);
        seedStorageInfo(carrierId, STORAGE_A);
        seedActiveTopLevelOccupancy("CAP-INITIAL", STORAGE_A, carrierId);
        WmsLoadChangeCommand command = loadCommand(
            "SPLIT-OVER-CAPACITY", "INV-LOAD", "INBOUND_FIXTURE",
            List.of(new WmsLoadMutation(
                materialId, carrierId, 1, null, "STORAGE", STORAGE_A))
        );

        assertThatThrownBy(() -> inTransaction(() -> loadChangeService.apply(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).startsWith(
                    WmsLoadChangeService.ERROR_CAPACITY_EXCEEDED));

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_load_relation where child_node_id = ? and parent_node_id = ?",
            materialId, carrierId
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_load_relation_history where effect_key = ?",
            command.effectKey()
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_occupancy_effect where effect_key = ?",
            command.effectKey()
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy where status = 'ACTIVE'"
        )).isOne();
    }

    @Test
    void loadChangeCountsActiveCapacityLockBeforeCommittingTopLevelCapacity() {
        long carrierId = 23_101L;
        long materialId = 23_102L;
        seedStorage(STORAGE_A, "LOAD-LOCK-CAP", 0);
        seedTopLevelCapacity(STORAGE_A, "2");
        seedLoadNode(carrierId, "CARRIER", null, 33_101L);
        seedLoadNode(materialId, "MATERIAL", 43_101L, null);
        seedLoadRelation(52_101L, materialId, carrierId);
        seedStorageInfo(carrierId, STORAGE_A);
        seedActiveTopLevelOccupancy("LOCK-CAP-INITIAL", STORAGE_A, carrierId);
        inTransaction(() -> reservationService.reserve(topLevelCapacityCommand(
            STORAGE_A, "INV-LOCK-CAP", 83_101L, 83_101_001L,
            "LOCK-CAP", "1", sha256("LOCK-CAP-INPUT")
        )));
        WmsLoadChangeCommand command = loadCommand(
            "SPLIT-WITH-LOCK-OVER-CAPACITY", "INV-LOAD", "INBOUND_FIXTURE",
            List.of(new WmsLoadMutation(
                materialId, carrierId, 1, null, "STORAGE", STORAGE_A))
        );

        assertThatThrownBy(() -> inTransaction(() -> loadChangeService.apply(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).startsWith(
                    WmsLoadChangeService.ERROR_CAPACITY_EXCEEDED));

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock "
                + "where storage_id = ? and status = 'LOCKED'",
            STORAGE_A
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_occupancy_effect where effect_key = ?",
            command.effectKey()
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_load_relation where child_node_id = ? and parent_node_id = ?",
            materialId, carrierId
        )).isOne();
    }

    @Test
    void loadChangeCoexistsWithActiveCapacityLockWithinTopLevelCapacity() {
        long carrierId = 23_201L;
        long materialId = 23_202L;
        seedStorage(STORAGE_A, "LOAD-LOCK-FIT", 0);
        seedTopLevelCapacity(STORAGE_A, "3");
        seedLoadNode(carrierId, "CARRIER", null, 33_201L);
        seedLoadNode(materialId, "MATERIAL", 43_201L, null);
        seedLoadRelation(52_201L, materialId, carrierId);
        seedStorageInfo(carrierId, STORAGE_A);
        seedActiveTopLevelOccupancy("LOCK-FIT-INITIAL", STORAGE_A, carrierId);
        WmsAllocationReservationResult reserved = inTransaction(
            () -> reservationService.reserve(topLevelCapacityCommand(
                STORAGE_A, "INV-LOCK-FIT", 83_201L, 83_201_001L,
                "LOCK-FIT", "1", sha256("LOCK-FIT-INPUT")
            )));
        WmsLoadChangeCommand command = loadCommand(
            "SPLIT-WITH-LOCK-WITHIN-CAPACITY", "INV-LOAD", "INBOUND_FIXTURE",
            List.of(new WmsLoadMutation(
                materialId, carrierId, 1, null, "STORAGE", STORAGE_A))
        );

        WmsLoadChangeResult result = inTransaction(() -> loadChangeService.apply(command));

        assertThat(result.storageRootCounts()).containsEntry(STORAGE_A, 2);
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select status from wms_allocation_lock where allocation_lock_id = ?",
            reserved.allocationLockIds().getFirst()
        )).isEqualTo("LOCKED");
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select effect_result from wms_allocation_lock where allocation_lock_id = ?",
            reserved.allocationLockIds().getFirst()
        )).isEqualTo("NONE");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy where status = 'ACTIVE'"
        )).isEqualTo(2);
    }

    @Test
    void loadChangeRejectsAllocationLockWithAdvancedExternalEffect() {
        long carrierId = 23_301L;
        long materialId = 23_302L;
        seedStorage(STORAGE_A, "LOAD-LOCK-EXTERNAL", 0);
        seedTopLevelCapacity(STORAGE_A, "3");
        seedLoadNode(carrierId, "CARRIER", null, 33_301L);
        seedLoadNode(materialId, "MATERIAL", 43_301L, null);
        seedLoadRelation(52_301L, materialId, carrierId);
        seedStorageInfo(carrierId, STORAGE_A);
        seedActiveTopLevelOccupancy("LOCK-EXTERNAL-INITIAL", STORAGE_A, carrierId);
        WmsAllocationReservationResult reserved = inTransaction(
            () -> reservationService.reserve(topLevelCapacityCommand(
                STORAGE_A, "INV-LOCK-EXTERNAL", 83_301L, 83_301_001L,
                "LOCK-EXTERNAL", "1", sha256("LOCK-EXTERNAL-INPUT")
            )));
        jdbcTemplate.update(
            "update wms_allocation_lock set effect_result = 'ACCEPTED' "
                + "where allocation_lock_id = ?",
            reserved.allocationLockIds().getFirst()
        );
        WmsLoadChangeCommand command = loadCommand(
            "SPLIT-WITH-EXTERNAL-LOCK", "INV-LOAD", "INBOUND_FIXTURE",
            List.of(new WmsLoadMutation(
                materialId, carrierId, 1, null, "STORAGE", STORAGE_A))
        );

        assertThatThrownBy(() -> inTransaction(() -> loadChangeService.apply(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).startsWith(
                    WmsLoadChangeService.ERROR_ALLOCATION_UNSUPPORTED));

        assertThat(WmsAllocationReservationTestDatabase.string(
            "select effect_result from wms_allocation_lock where allocation_lock_id = ?",
            reserved.allocationLockIds().getFirst()
        )).isEqualTo("ACCEPTED");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_occupancy_effect where effect_key = ?",
            command.effectKey()
        )).isZero();
    }

    @Test
    void loadChangeRejectsEntitySlotAllocationLock() {
        long carrierId = 23_401L;
        long materialId = 23_402L;
        seedStorage(STORAGE_A, "LOAD-ENTITY-LOCK", 0);
        seedTopLevelCapacity(STORAGE_A, "2");
        seedLoadNode(carrierId, "CARRIER", null, 33_401L);
        seedLoadNode(materialId, "MATERIAL", 43_401L, null);
        seedLoadRelation(52_401L, materialId, carrierId);
        seedStorageInfo(carrierId, STORAGE_A);
        seedActiveTopLevelOccupancy("ENTITY-LOCK-INITIAL", STORAGE_A, carrierId);
        inTransaction(() -> reservationService.reserve(slotCommand(
            slotInput(STORAGE_A, List.of("S1", "S2"), Set.of(), 0),
            "INV-ENTITY-LOCK", 83_401L, 83_401_001L,
            "ENTITY-LOCK", sha256("ENTITY-LOCK-INPUT")
        )));
        WmsLoadChangeCommand command = loadCommand(
            "SPLIT-WITH-ENTITY-LOCK", "INV-LOAD", "INBOUND_FIXTURE",
            List.of(new WmsLoadMutation(
                materialId, carrierId, 1, null, "STORAGE", STORAGE_A))
        );

        assertThatThrownBy(() -> inTransaction(() -> loadChangeService.apply(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).startsWith(
                    WmsLoadChangeService.ERROR_ALLOCATION_UNSUPPORTED));

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock "
                + "where storage_id = ? and lock_kind = 'ENTITY_SLOT' and status = 'LOCKED'",
            STORAGE_A
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_occupancy_effect where effect_key = ?",
            command.effectKey()
        )).isZero();
    }

    @Test
    void loadChangeReplayDoesNotAppendDuplicateHistoryOrEffect() {
        long carrierId = 24_001L;
        long materialId = 24_002L;
        seedStorage(STORAGE_A, "LOAD-REPLAY", 0);
        seedTopLevelCapacity(STORAGE_A, "2");
        seedLoadNode(carrierId, "CARRIER", null, 34_001L);
        seedLoadNode(materialId, "MATERIAL", 44_001L, null);
        seedStorageInfo(carrierId, STORAGE_A);
        seedStorageInfo(materialId, STORAGE_A);
        seedActiveTopLevelOccupancy("REPLAY-INITIAL", STORAGE_A, carrierId, materialId);
        WmsLoadChangeCommand command = loadCommand(
            "LOAD-REPLAY", "INV-LOAD", "INBOUND_FIXTURE",
            List.of(new WmsLoadMutation(materialId, null, 1, carrierId, null, null))
        );

        WmsLoadChangeResult first = inTransaction(() -> loadChangeService.apply(command));
        WmsLoadChangeResult replay = inTransaction(() -> loadChangeService.apply(command));

        assertThat(replay.occupancyEffectId()).isEqualTo(first.occupancyEffectId());
        assertThat(replay.activeOccupancyIds()).containsExactlyElementsOf(first.activeOccupancyIds());
        assertThat(replay.closedOccupancyIds()).isEmpty();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_occupancy_effect where effect_key = ?",
            command.effectKey()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_load_relation_history where effect_key = ?",
            command.effectKey()
        )).isOne();
    }

    @Test
    void concurrentLoadChangeWithSameEffectKeyConvergesWithoutDuplicateHistory() throws Exception {
        long carrierId = 24_101L;
        long materialId = 24_102L;
        seedStorage(STORAGE_A, "LOAD-RACE", 0);
        seedTopLevelCapacity(STORAGE_A, "2");
        seedLoadNode(carrierId, "CARRIER", null, 34_101L);
        seedLoadNode(materialId, "MATERIAL", 44_101L, null);
        seedStorageInfo(carrierId, STORAGE_A);
        seedStorageInfo(materialId, STORAGE_A);
        seedActiveTopLevelOccupancy("LOAD-RACE-INITIAL", STORAGE_A, carrierId, materialId);
        WmsLoadChangeCommand command = loadCommand(
            "LOAD-RACE", "INV-LOAD", "INBOUND_FIXTURE",
            List.of(new WmsLoadMutation(materialId, null, 1, carrierId, null, null))
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<LoadOutcome> worker = () -> {
            ready.countDown();
            start.await(20L, TimeUnit.SECONDS);
            try {
                return new LoadOutcome(inTransaction(() -> loadChangeService.apply(command)), null);
            } catch (Throwable error) {
                return new LoadOutcome(null, error);
            }
        };
        try {
            Future<LoadOutcome> first = executor.submit(worker);
            Future<LoadOutcome> second = executor.submit(worker);
            assertThat(ready.await(20L, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            LoadOutcome left = first.get(30L, TimeUnit.SECONDS);
            LoadOutcome right = second.get(30L, TimeUnit.SECONDS);
            assertThat(left.error()).isNull();
            assertThat(right.error()).isNull();
            assertThat(left.result().occupancyEffectId())
                .isEqualTo(right.result().occupancyEffectId());
        } finally {
            executor.shutdownNow();
        }

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_occupancy_effect where effect_key = ?",
            command.effectKey()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_load_relation_history where effect_key = ?",
            command.effectKey()
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy where status = 'ACTIVE'"
        )).isOne();
    }

    @Test
    void productionLoadChangeActionRunsThroughSameTxCheckpoint() {
        long carrierId = 25_001L;
        long materialId = 25_002L;
        seedStorage(STORAGE_A, "LOAD-PROD", 0);
        seedTopLevelCapacity(STORAGE_A, "2");
        seedLoadNode(carrierId, "CARRIER", null, 35_001L);
        seedLoadNode(materialId, "MATERIAL", 45_001L, null);
        seedStorageInfo(carrierId, STORAGE_A);
        seedStorageInfo(materialId, STORAGE_A);
        seedActiveTopLevelOccupancy("LOAD-PROD-INITIAL", STORAGE_A, carrierId, materialId);
        long eventId = 66_101L;
        seedProductionProcess(
            eventId,
            WmsLoadChangeAction.ACTION_KEY,
            WmsLoadChangeAction.LOCAL_EFFECT_KEY,
            "APPLIED"
        );
        WmsLoadChangeCommand command = loadCommandForProcess(
            INPUT_HASH,
            List.of(new WmsLoadMutation(materialId, null, 1, carrierId, null, null))
        );

        ProcessAttempt attempt = executeProduction(
            eventId, loadChangeAction, WmsLoadChangeAction.LOCAL_EFFECT_KEY, command);

        assertThat(attempt.getEffectResult())
            .isEqualTo(com.innolux.iws.process.enums.EffectResult.SUCCEEDED);
        assertThat(attempt.getCheckpointJson()).contains("\"status\":\"APPLIED\"");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_storage_occupancy where status = 'ACTIVE'"
        )).isOne();
    }

    @Test
    void sameEffectKeyWithDifferentInputHashIsRejected() {
        seedCapacitiedStorage(STORAGE_A, "A-02", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand first = capacityCommand(
            STORAGE_A, "INV-002", 1_002L, 1_002_001L, "WEIGHT-20",
            WEIGHT, "20.000", 3, INPUT_HASH
        );
        inTransaction(() -> reservationService.reserve(first));

        WmsAllocationReserveCommand conflict = capacityCommand(
            STORAGE_A, "INV-002", 1_002L, 1_002_001L, "WEIGHT-20",
            WEIGHT, "20.000", 3, "d".repeat(64)
        );
        conflict = new WmsAllocationReserveCommand(
            conflict.business(), first.effectKey(), conflict.inputHash(),
            conflict.rulePackageVersionId(), conflict.logicalScopeVersionId(),
            conflict.spaceSnapshotHash(), conflict.capacitySnapshotHash(),
            conflict.storages(), conflict.request()
        );
        WmsAllocationReserveCommand finalConflict = conflict;

        assertThatThrownBy(() -> inTransaction(() -> reservationService.reserve(finalConflict)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).startsWith(
                    WmsAllocationReservationService.ERROR_EFFECT_KEY_CONFLICT));
    }

    @Test
    void sameStorageCapacitySharesCoexistWhenNoChannel() {
        seedStoragelessChannelCapacity(STORAGE_B, "B-01", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand first = capacityCommand(
            STORAGE_B, "INV-003A", 1_003L, 1_003_001L, "WEIGHT-30",
            WEIGHT, "30.000", 3, INPUT_HASH
        );
        WmsAllocationReserveCommand second = capacityCommand(
            STORAGE_B, "INV-003B", 1_004L, 1_003_002L, "WEIGHT-40",
            WEIGHT, "40.000", 3, "e".repeat(64)
        );

        WmsAllocationReservationResult firstResult = inTransaction(() ->
            reservationService.reserve(first));
        WmsAllocationReservationResult secondResult = inTransaction(() ->
            reservationService.reserve(second));

        assertThat(firstResult.result()).isEqualTo("LOCKED");
        assertThat(secondResult.result()).isEqualTo("LOCKED");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel_lock where status = 'LOCKED'"
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where status = 'LOCKED'"
        )).isEqualTo(2L);
        assertThat(WmsAllocationReservationTestDatabase.decimal(
            "select sum(locked_qty) from wms_allocation_lock "
                + "where status = 'LOCKED' and dimension_key = 'WEIGHT'"
        )).isEqualByComparingTo("70.000000000000");
    }

    @Test
    void sameCandidateCanPersistMultipleCapacityDimensions() {
        seedStoragelessChannelCapacity(STORAGE_B, "B-02", WEIGHT, "100.000", 3);
        jdbcTemplate.update(
            "insert into wms_storage_capacity "
                + "(capacity_id, storage_id, dimension_key, capacity_value, unit, scale, "
                + "capacity_version, active_flag) values (?, ?, ?, ?, ?, ?, 1, 1)",
            9_002L, STORAGE_B, VOLUME, new BigDecimal("80.000"), "M3", 3
        );
        WmsAllocationReserveCommand command = new WmsAllocationReserveCommand(
            business(1_005L, 1_005_001L, "INV-004"),
            effectKey("multi-dimension"),
            INPUT_HASH,
            401L,
            402L,
            SPACE_HASH,
            CAPACITY_HASH,
            List.of(capacityInput(STORAGE_B, Map.of(
                WEIGHT, new BigDecimal("0.000"),
                VOLUME, new BigDecimal("0.000")
            ))),
            new AllocationRequest(
                new AllocationDemand(1_005L, Map.of(
                    WEIGHT, new AllocationQuantity(new BigDecimal("10.000"), "KG", 3),
                    VOLUME, new AllocationQuantity(new BigDecimal("20.000"), "M3", 3)
                ), 0),
                new AllocationPolicy(List.of(WEIGHT, VOLUME))
            )
        );

        WmsAllocationReservationResult result = inTransaction(() ->
            reservationService.reserve(command));

        assertThat(result.result()).isEqualTo("LOCKED");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where decision_id = ? and status = 'LOCKED'",
            result.decisionId()
        )).isEqualTo(2L);
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where decision_id = ? "
                + "and candidate_share_key is not null",
            result.decisionId()
        )).isEqualTo(2L);
    }

    @Test
    void concurrentStoragesSharingChannelProduceOnlyOneLegalClaim() throws Exception {
        seedChannel(STORAGE_A, "A-03", 1);
        seedChannel(STORAGE_B, "B-03", 2);
        seedCapacity(STORAGE_A, WEIGHT, "100.000", "KG", 3);
        seedCapacity(STORAGE_B, WEIGHT, "100.000", "KG", 3);
        WmsAllocationReserveCommand first = capacityCommandForSingleStorage(
            STORAGE_A, "INV-005A", 1_006L, 1_006_001L, "CONCURRENT-A",
            WEIGHT, "10.000", 3, "f".repeat(64)
        );
        WmsAllocationReserveCommand second = capacityCommandForSingleStorage(
            STORAGE_B, "INV-005B", 1_007L, 1_006_002L, "CONCURRENT-B",
            WEIGHT, "10.000", 3, "1".repeat(64)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> left = executor.submit(worker(
                () -> reserveWithDeadlockRetry(first), ready, start));
            Future<Outcome> right = executor.submit(worker(
                () -> reserveWithDeadlockRetry(second), ready, start));
            assertThat(ready.await(20L, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Outcome leftOutcome = left.get(60L, TimeUnit.SECONDS);
            Outcome rightOutcome = right.get(60L, TimeUnit.SECONDS);
            assertThat(leftOutcome.error()).isNull();
            assertThat(rightOutcome.error()).isNull();
            assertThat(List.of(leftOutcome.result().result(), rightOutcome.result().result()))
                .containsExactlyInAnyOrder("LOCKED", "NO_CANDIDATE");
        } finally {
            executor.shutdownNow();
        }

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel_lock where status = 'LOCKED'"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where status = 'LOCKED'"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_decision"
        )).isEqualTo(2L);
    }

    @Test
    void membershipChangeAfterInitialSnapshotRequiresRetryAndThenSucceeds() {
        seedChannel(STORAGE_A, "A-04", 1);
        seedCapacity(STORAGE_A, WEIGHT, "100.000", "KG", 3);
        insertChannel(CHANNEL_B, "CH-B", ZONE_ID);
        WmsAllocationReserveCommand command = capacityCommandForSingleStorage(
            STORAGE_A, "INV-006", 1_008L, 1_008_001L, "RETRY",
            WEIGHT, "10.000", 3, "2".repeat(64)
        );
        retrySpaceReader.moveAfterInitialSnapshot(STORAGE_A, CHANNEL_A, CHANNEL_B);

        assertThatThrownBy(() -> inTransaction(() -> reservationService.reserve(command)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).startsWith(
                    WmsAllocationReservationService.ERROR_LOCK_DOMAIN_CHANGED));
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_decision"
        )).isZero();

        WmsAllocationReservationResult retried = inTransaction(() ->
            reservationService.reserve(command));
        assertThat(retried.result()).isEqualTo("LOCKED");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_access_channel_lock where channel_id = ? and status = 'LOCKED'",
            CHANNEL_B
        )).isOne();
    }

    @Test
    void entitySlotLockIsExclusiveAndUsesPhysicalSlotKey() {
        seedStorage(STORAGE_B, "B-04", 0);
        WmsAllocationSnapshotInput slotInput = slotInput(
            STORAGE_B, List.of("SLOT-1", "SLOT-2"), Set.of("SLOT-1"), 0);
        WmsAllocationReserveCommand first = slotCommand(
            slotInput, "INV-007A", 1_009L, 1_009_001L, "SLOT-A", "3".repeat(64));
        WmsAllocationReserveCommand second = slotCommand(
            slotInput, "INV-007B", 1_010L, 1_009_002L, "SLOT-B", "4".repeat(64));

        WmsAllocationReservationResult firstResult = inTransaction(() ->
            reservationService.reserve(first));
        WmsAllocationReservationResult secondResult = inTransaction(() ->
            reservationService.reserve(second));

        assertThat(firstResult.result()).isEqualTo("LOCKED");
        assertThat(secondResult.result()).isEqualTo("NO_CANDIDATE");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock "
                + "where lock_kind = 'ENTITY_SLOT' and status = 'LOCKED'"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select slot_key from wms_allocation_lock where status = 'LOCKED'"
        )).isEqualTo("SLOT-2");
    }

    @Test
    void sameTxCommitsDecisionLockCheckpointAndPlatformClosureTogether() {
        seedCapacitiedStorage(STORAGE_A, "A-05", WEIGHT, "100.000", 3);
        seedProcess(99_101L, "RESERVE_STORAGE");
        WmsAllocationReserveCommand command = capacityCommandForSingleStorage(
            STORAGE_A, "INV-008", INSTANCE_ID, 1_010_001L, "SAME-TX",
            WEIGHT, "10.000", 3, "5".repeat(64)
        );
        reserveAction.prepare(command, null);

        ProcessAttempt attempt = execute(99_101L, "SAME-TX");

        assertThat(attempt.getEffectResult().name()).isEqualTo("SUCCEEDED");
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_decision"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock where status = 'LOCKED'"
        )).isOne();
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select checkpoint_json from iws_process_attempt where id = ?", attempt.getId()
        )).contains("\"status\":\"LOCKED\"", "\"decisionId\":");
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select event_status from iws_process_event where id = ?", 99_101L
        )).isEqualTo("SUCCESS");
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select state_code from iws_process_token where id = ?", TOKEN_ID
        )).isEqualTo("APPLIED");
    }

    @Test
    void checkpointFailureRollsBackDecisionCandidatesAndLocks() {
        seedCapacitiedStorage(STORAGE_A, "A-06", WEIGHT, "100.000", 3);
        seedProcess(99_102L, "RESERVE_STORAGE");
        WmsAllocationReserveCommand command = capacityCommandForSingleStorage(
            STORAGE_A, "INV-009", INSTANCE_ID, 1_011_001L, "CHECKPOINT-FAIL",
            WEIGHT, "10.000", 3, "6".repeat(64)
        );
        reserveAction.prepare(command, "CHECKPOINT");

        assertThatThrownBy(() -> execute(99_102L, "CHECKPOINT-FAIL"))
            .isInstanceOf(RuntimeException.class);

        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_decision"
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_allocation_lock"
        )).isZero();
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select event_status from iws_process_event where id = ?", 99_102L
        )).isEqualTo("PROCESSING");
        assertThat(WmsAllocationReservationTestDatabase.string(
            "select checkpoint_status from iws_process_attempt where event_id = ?", 99_102L
        )).isEqualTo("CLAIMED");
    }

    @Test
    void reserveRequiresCallerTransaction() {
        seedCapacitiedStorage(STORAGE_A, "A-07", WEIGHT, "100.000", 3);
        WmsAllocationReserveCommand command = capacityCommandForSingleStorage(
            STORAGE_A, "INV-010", 1_012L, 1_012_001L, "MANDATORY",
            WEIGHT, "10.000", 3, "7".repeat(64)
        );

        assertThatThrownBy(() -> reservationService.reserve(command))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private void seedCapacitiedStorage(
        long storageId,
        String code,
        String dimension,
        String capacity,
        int scale
    ) {
        seedChannel(storageId, code, 1);
        seedCapacity(storageId, dimension, capacity, dimension.equals(WEIGHT) ? "KG" : "M3", scale);
    }

    private WmsMaterialCompositeFixture seedMaterialComposite() {
        long inventoryId = 6_501_001L;
        long loadNodeId = 6_502_001L;
        seedChannel(STORAGE_A, "A-COMPOSITE", 1);
        seedTopLevelCapacity(STORAGE_A, "10");
        seedLoadNode(loadNodeId, "MATERIAL", inventoryId, null);
        seedStorageInfo(loadNodeId, STORAGE_A);
        seedActiveTopLevelOccupancy("MATERIAL-COMPOSITE-SOURCE", STORAGE_A, loadNodeId);
        seedInventory(inventoryId, "A-COMPOSITE", STORAGE_A);

        seedChannel(STORAGE_B, "B-COMPOSITE", 2);
        seedTopLevelCapacity(STORAGE_B, "10");
        WmsAllocationReserveCommand reserveCommand = topLevelCapacityCommand(
            STORAGE_B,
            "TASK-MATERIAL-COMPOSITE",
            9_301L,
            INSTANCE_ID,
            "MATERIAL-COMPOSITE-LOCK",
            "1",
            INPUT_HASH
        );
        WmsAllocationReservationResult reserved = inTransaction(
            () -> reservationService.reserve(reserveCommand));
        WmsAllocationEffectIdentity identity = effectIdentity(reserveCommand.business());
        long allocationLockId = reserved.allocationLockIds().getFirst();
        inTransaction(() -> effectService.advance(effectCommand(
            identity, allocationLockId, 1, "NONE", "SEND_INTENT", null, null)));
        inTransaction(() -> effectService.advance(effectCommand(
            identity, allocationLockId, 2, "SEND_INTENT", "ACCEPTED", null, null)));

        String inputHash = sha256("MATERIAL-COMPOSITE-EFFECT");
        String effectKey = "WMS:" + inputHash;
        WmsMaterialChangeCommand materialCommand = new WmsMaterialChangeCommand(
            effectKey,
            inputHash,
            inventoryId,
            0,
            STORAGE_B,
            LocalDateTime.now(),
            "物料复合事实测试",
            "evidence://material/composite",
            "IWS",
            "REQUEST-MATERIAL-COMPOSITE",
            identity.businessType(),
            identity.businessName(),
            identity.businessId(),
            identity.businessNo(),
            identity.businessLineId(),
            identity.businessType(),
            identity.businessId(),
            identity.taskKey(),
            identity.planInstanceId(),
            "MATERIAL_CHANGE",
            "MATERIAL_CHANGE",
            "1",
            null,
            null,
            9_001L,
            9_101L
        );
        WmsLoadChangeCommand loadChange = new WmsLoadChangeCommand(
            effectKey,
            inputHash,
            List.of(new WmsLoadMutation(
                loadNodeId, null, 1, null, "STORAGE", STORAGE_B)),
            materialCommand.occurredAt(),
            materialCommand.reason(),
            materialCommand.evidenceRef(),
            materialCommand.sourceSystem(),
            materialCommand.requestId(),
            materialCommand.businessType(),
            materialCommand.businessName(),
            materialCommand.businessId(),
            materialCommand.businessNo(),
            materialCommand.businessLineId(),
            materialCommand.taskKey(),
            materialCommand.planInstanceId(),
            materialCommand.definitionVersion(),
            materialCommand.eventId(),
            materialCommand.attemptId(),
            materialCommand.actorId(),
            materialCommand.departmentId()
        );
        WmsMaterialAllocationConsumption consumption =
            new WmsMaterialAllocationConsumption(
                identity,
                List.of(new WmsAllocationEffectExpectation(
                    allocationLockId, 3, "ACCEPTED")),
                "evidence://material/composite/consume"
            );
        return new WmsMaterialCompositeFixture(
            inventoryId, effectKey, allocationLockId,
            materialCommand, loadChange, consumption
        );
    }

    private void seedInventory(long inventoryId, String storageCode, long storageId) {
        jdbcTemplate.update("""
            insert into wms_inventory (
              inventory_id, stock_unit_code, material_code, warehouse_code,
              zone_id, zone_code, storage_id, storage_code, inventory_qty,
              reserved_qty, unit, status, version, create_dept, create_by
            ) values (?, ?, 'MAT-COMPOSITE', 'W-ALLOC', ?, 'Z-ALLOC', ?, ?,
                      1.000000, 0.000000, 'PCS', 1, 0, 9101, 9001)
            """,
            inventoryId, "STOCK-" + inventoryId, ZONE_ID, storageId, storageCode);
    }

    private void seedStoragelessChannelCapacity(
        long storageId,
        String code,
        String dimension,
        String capacity,
        int scale
    ) {
        seedStorage(storageId, code, 0);
        seedCapacity(storageId, dimension, capacity, "KG", scale);
    }

    private void seedChannel(long storageId, String code, int accessOrder) {
        seedStorage(storageId, code, 0);
        if (CHANNEL_A == 8_101L
            && WmsAllocationReservationTestDatabase.count(
                "select count(*) from wms_access_channel where channel_id = ?", CHANNEL_A
            ) == 0) {
            insertChannel(CHANNEL_A, "CH-A", ZONE_ID);
        }
        jdbcTemplate.update(
            "insert into wms_storage_access_member "
                + "(member_id, channel_id, storage_id, access_order, revision) "
                + "values (?, ?, ?, ?, 1)",
            9_000L + storageId, CHANNEL_A, storageId, accessOrder
        );
    }

    private void insertChannel(long channelId, String code, long zoneId) {
        jdbcTemplate.update(
            "insert into wms_access_channel "
                + "(channel_id, device_zone_id, channel_code, access_mode, status, revision) "
                + "values (?, ?, ?, 'FORK', 'ACTIVE', 1)",
            channelId, zoneId, code
        );
    }

    private void seedStorage(long storageId, String code, int lockStatus) {
        if (WmsAllocationReservationTestDatabase.count(
            "select count(*) from wms_warehouse_zone where zone_id = ?", ZONE_ID
        ) == 0) {
            jdbcTemplate.update(
                "insert into wms_warehouse_zone "
                    + "(zone_id, zone_code, zone_name, zone_kind, warehouse_id) "
                    + "values (?, ?, ?, 'DEVICE', 1)",
                ZONE_ID, "Z-ALLOC", "分配测试库区"
            );
        }
        jdbcTemplate.update(
            "insert into wms_storage "
                + "(storage_id, storage_code, zone_id, warehouse_id, lock_status, "
                + "disable_status, version, aisle_text) "
                + "values (?, ?, ?, 1, ?, 0, 0, ?)",
            storageId, code, ZONE_ID, lockStatus, code
        );
    }

    private void seedCapacity(long storageId, String dimension, String capacity, String unit, int scale) {
        long dimensionOrdinal = dimension.equals(WEIGHT) ? 1L : 2L;
        jdbcTemplate.update(
            "insert into wms_storage_capacity "
                + "(capacity_id, storage_id, dimension_key, capacity_value, unit, scale, "
                + "capacity_version, active_flag) values (?, ?, ?, ?, ?, ?, 1, 1)",
            storageId * 10L + dimensionOrdinal, storageId, dimension,
            new BigDecimal(capacity), unit, scale
        );
    }

    private void seedTopLevelCapacity(long storageId, String capacity) {
        seedCapacity(storageId, "TOP_LEVEL_LOAD", capacity, "LOAD", 0);
    }

    private void seedLoadNode(
        long nodeId,
        String nodeType,
        Long inventoryId,
        Long containerId
    ) {
        jdbcTemplate.update("""
            INSERT INTO wms_load_node (
              load_node_id, node_type, inventory_id, container_id, status, revision,
              create_dept, create_by
            ) VALUES (?, ?, ?, ?, 'ACTIVE', 1, 9101, 9001)
            """,
            nodeId, nodeType, inventoryId, containerId
        );
    }

    private void seedLoadRelation(long relationId, long childNodeId, long parentNodeId) {
        jdbcTemplate.update("""
            INSERT INTO wms_load_relation (
              relation_id, child_node_id, parent_node_id, revision, create_dept, create_by
            ) VALUES (?, ?, ?, 1, 9101, 9001)
            """,
            relationId, childNodeId, parentNodeId
        );
    }

    private void seedStorageInfo(long rootNodeId, long storageId) {
        jdbcTemplate.update("""
            INSERT INTO wms_storage_info (
              storage_info_id, root_node_id, location_kind, storage_id, revision,
              create_dept, create_by
            ) VALUES (?, ?, 'STORAGE', ?, 1, 9101, 9001)
            """,
            rootNodeId + 1_000_000L, rootNodeId, storageId
        );
    }

    private void seedActiveTopLevelOccupancy(
        String seed,
        long storageId,
        Long... rootNodeIds
    ) {
        long effectId = 71_001L;
        String effectKey = "WMS:" + sha256(seed);
        jdbcTemplate.update("""
            INSERT INTO wms_occupancy_effect (
              occupancy_effect_id, effect_key, business_type, business_id, business_line_id,
              task_key, plan_instance_id, effect_result, reason, occurred_at,
              create_dept, create_by
            ) VALUES (?, ?, 'LOAD_SEED', 88001, NULL, 'INV-008', ?, 'APPLIED',
              '测试初始占用', ?, 9101, 9001)
            """,
            effectId, effectKey, INSTANCE_ID, LocalDateTime.now()
        );
        long occupancyId = 72_001L;
        for (Long rootNodeId : rootNodeIds) {
            jdbcTemplate.update("""
                INSERT INTO wms_storage_occupancy (
                  occupancy_id, occupancy_effect_id, storage_id, root_node_id, dimension_key,
                  occupied_qty, unit, generation, status, revision, create_dept, create_by
                ) VALUES (?, ?, ?, ?, 'TOP_LEVEL_LOAD', 1, 'LOAD', 1, 'ACTIVE', 1, 9101, 9001)
                """,
                occupancyId++, effectId, storageId, rootNodeId
            );
        }
    }

    private WmsLoadChangeCommand loadCommand(
        String effectSeed,
        String taskKey,
        String businessType,
        List<WmsLoadMutation> mutations
    ) {
        String inputHash = sha256(effectSeed);
        return new WmsLoadChangeCommand(
            "WMS:" + inputHash,
            inputHash,
            mutations,
            LocalDateTime.now(),
            "装拆容量测试",
            "evidence://load-change/" + effectSeed,
            "IWS",
            "REQUEST-" + effectSeed,
            businessType,
            "装拆测试",
            88_001L,
            "BIZ-001",
            null,
            taskKey,
            INSTANCE_ID,
            "1",
            90_001L,
            90_002L,
            9001L,
            9101L
        );
    }

    private WmsLoadChangeCommand loadCommandForProcess(
        String inputHash,
        List<WmsLoadMutation> mutations
    ) {
        return new WmsLoadChangeCommand(
            "WMS:" + inputHash,
            inputHash,
            mutations,
            LocalDateTime.now(),
            "装拆容量平台测试",
            "evidence://load-change/platform",
            "IWS",
            "REQUEST-LOAD-PLATFORM",
            "INBOUND_FIXTURE",
            "装拆测试",
            88_001L,
            "BIZ-001",
            null,
            "INV-008",
            INSTANCE_ID,
            "1",
            null,
            null,
            9001L,
            9101L
        );
    }

    private WmsAllocationReserveCommand multiCapacityCommand(
        String task,
        String inputHash
    ) {
        long unitId = 1_301L;
        long planInstanceId = 1_301_001L;
        return new WmsAllocationReserveCommand(
            business(unitId, planInstanceId, task),
            effectKey(task),
            inputHash,
            401L,
            402L,
            SPACE_HASH,
            CAPACITY_HASH,
            List.of(capacityInput(
                STORAGE_A,
                Map.of(WEIGHT, new BigDecimal("0.000"), VOLUME, new BigDecimal("0.000"))
            )),
            new AllocationRequest(
                new AllocationDemand(unitId, Map.of(
                    WEIGHT, new AllocationQuantity(new BigDecimal("10.000"), "KG", 3),
                    VOLUME, new AllocationQuantity(new BigDecimal("5.000"), "M3", 3)
                ), 0),
                new AllocationPolicy(List.of(WEIGHT, VOLUME))
            )
        );
    }

    private WmsAllocationReleaseCheckCommand releaseCheckCommand(
        WmsAllocationReserveCommand source,
        List<Long> allocationLockIds
    ) {
        return new WmsAllocationReleaseCheckCommand(
            releaseIdentity(source.business()),
            "NO_EFFECT",
            "evidence://release/no-effect",
            allocationLockIds
        );
    }

    private WmsAllocationReleaseCommand releaseCommand(
        WmsAllocationReserveCommand source,
        WmsAllocationEvidenceResult evidence,
        String effectSeed
    ) {
        return releaseCommand(releaseIdentity(source.business()), evidence, effectSeed);
    }

    private WmsAllocationReleaseCommand releaseCommand(
        WmsAllocationReserveCommand source,
        WmsAllocationEvidenceResult evidence,
        String effectSeed,
        String evidenceType,
        String evidenceRef
    ) {
        WmsAllocationReleaseIdentity identity =
            releaseIdentity(source.business());
        String inputHash = sha256(effectSeed);
        return new WmsAllocationReleaseCommand(
            identity,
            "WMS:" + inputHash,
            inputHash,
            evidence.evidenceHash(),
            evidence.evidenceRevision(),
            evidenceRef,
            evidenceType,
            evidence.expectations(),
            LocalDateTime.now(),
            "对账确认无外部效果后释放",
            "IWS",
            "REQUEST-" + effectSeed,
            90_001L,
            90_002L,
            9001L,
            9101L
        );
    }

    private WmsAllocationReleaseCommand releaseCommand(
        WmsAllocationReleaseIdentity identity,
        WmsAllocationEvidenceResult evidence,
        String effectSeed
    ) {
        String inputHash = sha256(effectSeed);
        return new WmsAllocationReleaseCommand(
            identity,
            "WMS:" + inputHash,
            inputHash,
            evidence.evidenceHash(),
            evidence.evidenceRevision(),
            "evidence://release/no-effect",
            evidence.expectations(),
            LocalDateTime.now(),
            "明确无外部效果后释放",
            "IWS",
            "REQUEST-" + effectSeed,
            90_001L,
            90_002L,
            9001L,
            9101L
        );
    }

    private WmsAllocationReleaseIdentity releaseIdentity(
        WmsAllocationBusinessContext business
    ) {
        return new WmsAllocationReleaseIdentity(
            business.businessType(),
            business.businessName(),
            business.businessId(),
            business.businessNo(),
            business.businessLineId(),
            business.allocationUnitId(),
            business.roundNo(),
            business.taskKey() + ":RELEASE",
            business.taskKey(),
            RELEASE_INSTANCE_ID
        );
    }

    private WmsAllocationEffectIdentity effectIdentity(
        WmsAllocationBusinessContext business
    ) {
        return new WmsAllocationEffectIdentity(
            business.businessType(),
            business.businessName(),
            business.businessId(),
            business.businessNo(),
            business.businessLineId(),
            business.allocationUnitId(),
            business.roundNo(),
            business.taskKey(),
            business.planInstanceId()
        );
    }

    private WmsAllocationEffectCommand effectCommand(
        WmsAllocationEffectIdentity identity,
        long allocationLockId,
        int generation,
        String currentEffect,
        String targetEffect,
        String terminalEffectKey,
        Long consumptionTraceId
    ) {
        return new WmsAllocationEffectCommand(
            identity,
            targetEffect,
            List.of(new WmsAllocationEffectExpectation(
                allocationLockId, generation, currentEffect)),
            terminalEffectKey,
            consumptionTraceId,
            "evidence://allocation/effect",
            LocalDateTime.now(),
            9001L,
            9101L
        );
    }

    private WmsAllocationReserveCommand capacityCommand(
        long storageId,
        String task,
        long unitId,
        long planInstanceId,
        String effectSeed,
        String dimension,
        String amount,
        int scale,
        String inputHash
    ) {
        return capacityCommandForSingleStorage(
            storageId, task, planInstanceId, unitId, effectSeed, dimension, amount, scale, inputHash);
    }

    private WmsAllocationReserveCommand topLevelCapacityCommand(
        long storageId,
        String task,
        long unitId,
        long planInstanceId,
        String effectSeed,
        String amount,
        String inputHash
    ) {
        return new WmsAllocationReserveCommand(
            business(unitId, planInstanceId, task),
            effectKey(effectSeed),
            inputHash,
            401L,
            402L,
            SPACE_HASH,
            CAPACITY_HASH,
            List.of(capacityInput(
                storageId,
                Map.of("TOP_LEVEL_LOAD", new BigDecimal("0")))),
            new AllocationRequest(
                new AllocationDemand(unitId, Map.of(
                    "TOP_LEVEL_LOAD",
                    new AllocationQuantity(new BigDecimal(amount), "LOAD", 0)
                ), 0),
                new AllocationPolicy(List.of("TOP_LEVEL_LOAD"))
            )
        );
    }

    private WmsAllocationReserveCommand capacityCommandForSingleStorage(
        long storageId,
        String task,
        long planInstanceId,
        long unitId,
        String effectSeed,
        String dimension,
        String amount,
        int scale,
        String inputHash
    ) {
        return new WmsAllocationReserveCommand(
            business(unitId, planInstanceId, task),
            effectKey(effectSeed),
            inputHash,
            401L,
            402L,
            SPACE_HASH,
            CAPACITY_HASH,
            List.of(capacityInput(storageId, Map.of(dimension, new BigDecimal("0.000")))),
            new AllocationRequest(
                new AllocationDemand(unitId, Map.of(
                    dimension, new AllocationQuantity(new BigDecimal(amount), "KG", scale)
                ), 0),
                new AllocationPolicy(List.of(dimension))
            )
        );
    }

    private WmsAllocationReserveCommand slotCommand(
        WmsAllocationSnapshotInput input,
        String task,
        long unitId,
        long planInstanceId,
        String effectSeed,
        String inputHash
    ) {
        return new WmsAllocationReserveCommand(
            business(unitId, planInstanceId, task),
            effectKey(effectSeed),
            inputHash,
            401L,
            402L,
            SPACE_HASH,
            CAPACITY_HASH,
            List.of(input),
            new AllocationRequest(
                new AllocationDemand(unitId, Map.of(), 1),
                new AllocationPolicy(List.of())
            )
        );
    }

    private WmsAllocationBusinessContext business(long unitId, long planInstanceId, String task) {
        return new WmsAllocationBusinessContext(
            "INBOUND_FIXTURE", "入库fixture", 88_001L, "BIZ-001", null,
            unitId, 1, task, planInstanceId
        );
    }

    private WmsAllocationSnapshotInput capacityInput(
        long storageId,
        Map<String, BigDecimal> protectedRemaining
    ) {
        return new WmsAllocationSnapshotInput(
            storageId, true, false, true, false, false,
            AllocationProtection.NONE, protectedRemaining,
            List.of(), Set.of(), 0
        );
    }

    private WmsAllocationSnapshotInput slotInput(
        long storageId,
        List<String> slotKeys,
        Set<String> occupiedSlotKeys,
        int protectedFreeSlots
    ) {
        return new WmsAllocationSnapshotInput(
            storageId, true, false, true, false, false,
            AllocationProtection.NONE, Map.of(), slotKeys, occupiedSlotKeys, protectedFreeSlots
        );
    }

    private ProcessAttempt execute(long eventId, String tokenSeed) {
        EventMutationService.ClaimContext claim = mutationService.claim(eventId, tokenSeed, 120_000L);
        assertThat(claim).isNotNull();
        ActionContext context = new ActionContext(
            eventId,
            INSTANCE_ID,
            "E:" + FixtureAllocationReserveAction.ACTION_KEY,
            claim.attempt().getId(),
            "REQUEST-" + eventId,
            EventDirection.INTERNAL,
            PostingMode.AUTOMATIC,
            null,
            FixtureAllocationReserveAction.LOCAL_EFFECT_KEY,
            "IDEMP-" + eventId,
            INPUT_HASH,
            "{}",
            "evidence://fixture/" + eventId,
            1L,
            120_000L,
            Map.of()
        );
        return mutationService.executeLocalAndFinish(claim, reserveAction, context);
    }

    private ProcessAttempt executeProduction(
        long eventId,
        WmsAllocationReserveAction action,
        WmsAllocationReserveCommand command
    ) {
        return executeProduction(
            eventId, action, WmsAllocationReserveAction.LOCAL_EFFECT_KEY, command);
    }

    private ProcessAttempt executeProduction(
        long eventId,
        LocalAction action,
        String localEffectKey,
        Object payload
    ) {
        String inputHash = payload instanceof WmsAllocationReserveCommand command
            ? command.inputHash()
            : ((WmsLoadChangeCommand) payload).inputHash();
        return executeProduction(
            eventId, INSTANCE_ID, action, localEffectKey, payload, inputHash);
    }

    private ProcessAttempt executeProduction(
        long eventId,
        long instanceId,
        LocalAction action,
        String localEffectKey,
        Object payload,
        String inputHash
    ) {
        if (action instanceof LocalActionInputProvider) {
            freezeBusinessTask(instanceId, inputHash, payload);
        }
        EventMutationService.ClaimContext claim = mutationService.claim(
            eventId, "TOKEN-" + eventId, 120_000L);
        assertThat(claim).isNotNull();
        ActionContext context = new ActionContext(
            eventId,
            instanceId,
            "E:" + action.describe().key(),
            claim.attempt().getId(),
            "REQUEST-" + eventId,
            EventDirection.INTERNAL,
            PostingMode.AUTOMATIC,
            null,
            localEffectKey,
            "IDEMP-" + eventId,
            inputHash,
            jsonMapper.writeValueAsString(payload),
            "evidence://production/" + eventId,
            1L,
            120_000L,
            Map.of()
        );
        return mutationService.executeLocalAndFinish(claim, action, context);
    }

    private void freezeBusinessTask(long instanceId, String inputHash, Object payload) {
        if (payload instanceof WmsAllocationVerifyAction.VerifyPayload) {
            return;
        }
        Map<String, Object> instance = jdbcTemplate.queryForMap(
            "select plan_type from iws_process_instance where id = ?", instanceId);
        String businessType;
        long businessId;
        Long businessLineId;
        long allocationUnitId;
        int roundNo;
        String taskKey;
        if (payload instanceof WmsAllocationReserveCommand command) {
            businessType = command.business().businessType();
            businessId = command.business().businessId();
            businessLineId = command.business().businessLineId();
            allocationUnitId = command.business().allocationUnitId();
            roundNo = command.business().roundNo();
            taskKey = command.business().taskKey();
        } else if (payload instanceof WmsAllocationReleaseCommand command) {
            businessType = command.identity().businessType();
            businessId = command.identity().businessId();
            businessLineId = command.identity().businessLineId();
            allocationUnitId = command.identity().allocationUnitId();
            roundNo = command.identity().roundNo();
            taskKey = command.identity().taskKey();
        } else {
            WmsLoadChangeCommand command = (WmsLoadChangeCommand) payload;
            businessType = command.businessType();
            businessId = command.businessId();
            businessLineId = command.businessLineId();
            allocationUnitId = command.businessId();
            roundNo = 0;
            taskKey = command.taskKey();
        }
        jdbcTemplate.update(
            "insert into wms_business_task "
                + "(task_id, business_type, business_id, line_id, allocation_unit_id, "
                + "round_no, phase, task_key, plan_type, plan_instance_id, input_hash, "
                + "input_json, status, revision) "
                + "values (?, ?, ?, ?, ?, ?, 'EXECUTE', ?, ?, ?, ?, ?, 'RUNNING', 1) "
                + "on duplicate key update business_type = values(business_type), "
                + "business_id = values(business_id), line_id = values(line_id), "
                + "allocation_unit_id = values(allocation_unit_id), round_no = values(round_no), "
                + "task_key = values(task_key), plan_type = values(plan_type), "
                + "input_hash = values(input_hash), input_json = values(input_json), "
                + "status = values(status)",
            instanceId,
            businessType,
            businessId,
            businessLineId,
            allocationUnitId,
            roundNo,
            taskKey,
            instance.get("plan_type"),
            instanceId,
            inputHash,
            jsonMapper.writeValueAsString(payload)
        );
    }

    private void seedProcess(long eventId, String eventCode) {
        seedProcessInternal(
            eventId,
            eventCode,
            FixtureAllocationReserveAction.ACTION_KEY,
            FixtureAllocationReserveAction.LOCAL_EFFECT_KEY
        );
    }

    private void seedMaterialProcess(long eventId, String taskKey, String inputHash) {
        seedProcessShell(
            INSTANCE_ID,
            77_004L,
            10_004L,
            "MATERIAL_CHANGE",
            taskKey,
            inputHash
        );
        insertProcessEvent(
            eventId,
            INSTANCE_ID,
            77_004L,
            FixtureMaterialChangeAction.ACTION_KEY,
            FixtureMaterialChangeAction.ACTION_KEY,
            FixtureMaterialChangeAction.LOCAL_EFFECT_KEY,
            inputHash
        );
    }

    private void seedProductionProcess(
        long eventId,
        String eventCode,
        String localActionKey,
        String localEffectKey
    ) {
        seedProcessInternal(eventId, eventCode, localActionKey, localEffectKey);
    }

    private void seedProcessInternal(
        long eventId,
        String eventCode,
        String localActionKey,
        String localEffectKey
    ) {
        seedProcessShell(
            INSTANCE_ID,
            TOKEN_ID,
            10_001L,
            "ALLOCATION_LOCK",
            "INV-008",
            INPUT_HASH
        );
        insertProcessEvent(
            eventId,
            INSTANCE_ID,
            TOKEN_ID,
            eventCode,
            localActionKey,
            localEffectKey,
            INPUT_HASH
        );
    }

    private void seedReleaseProcessChain(String inputHash) {
        seedProcessShell(
            RELEASE_INSTANCE_ID,
            77_002L,
            10_002L,
            "ALLOCATION_RELEASE",
            "INV-008:RELEASE",
            inputHash
        );
        insertProcessEvent(
            66_101L,
            RELEASE_INSTANCE_ID,
            77_002L,
            WmsAllocationCheckReleaseAction.ACTION_KEY,
            WmsAllocationCheckReleaseAction.ACTION_KEY,
            WmsAllocationCheckReleaseAction.LOCAL_EFFECT_KEY,
            inputHash
        );
        insertProcessEvent(
            66_102L,
            RELEASE_INSTANCE_ID,
            77_002L,
            WmsAllocationReleaseAction.ACTION_KEY,
            WmsAllocationReleaseAction.ACTION_KEY,
            WmsAllocationReleaseAction.LOCAL_EFFECT_KEY,
            inputHash
        );
        insertProcessEvent(
            66_103L,
            RELEASE_INSTANCE_ID,
            77_002L,
            WmsAllocationVerifyReleaseAction.ACTION_KEY,
            WmsAllocationVerifyReleaseAction.ACTION_KEY,
            WmsAllocationVerifyReleaseAction.LOCAL_EFFECT_KEY,
            inputHash
        );
    }

    private void seedAllocationProcessChain(String inputHash) {
        seedProcessShell(
            INSTANCE_ID,
            77_003L,
            10_003L,
            "ALLOCATION_LOCK",
            "STATE-CHAIN",
            inputHash
        );
        insertProcessEvent(
            66_201L,
            INSTANCE_ID,
            77_003L,
            WmsAllocationEvaluateAction.ACTION_KEY,
            WmsAllocationEvaluateAction.ACTION_KEY,
            WmsAllocationEvaluateAction.LOCAL_EFFECT_KEY,
            inputHash
        );
        insertProcessEvent(
            66_202L,
            INSTANCE_ID,
            77_003L,
            WmsAllocationReserveAction.ACTION_KEY,
            WmsAllocationReserveAction.ACTION_KEY,
            WmsAllocationReserveAction.LOCAL_EFFECT_KEY,
            inputHash
        );
        insertProcessEvent(
            66_203L,
            INSTANCE_ID,
            77_003L,
            WmsAllocationVerifyAction.ACTION_KEY,
            WmsAllocationVerifyAction.ACTION_KEY,
            WmsAllocationVerifyAction.LOCAL_EFFECT_KEY,
            inputHash
        );
        jdbcTemplate.update(
            "update iws_process_event set source_state_code = 'START', target_state_code = 'EVALUATED' "
                + "where id = 66201"
        );
        jdbcTemplate.update(
            "update iws_process_event set source_state_code = 'EVALUATED', target_state_code = 'RESERVED' "
                + "where id = 66202"
        );
        jdbcTemplate.update(
            "update iws_process_event set source_state_code = 'RESERVED', target_state_code = 'END' "
                + "where id = 66203"
        );
    }

    private void seedProcessShell(
        long instanceId,
        long tokenId,
        long scheduleControlId,
        String planType,
        String taskKey,
        String inputHash
    ) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "insert into iws_process_instance "
                + "(id, plan_role, definition_version_id, definition_revision, schedule_id, "
                + "plan_type, business_schedule_code, business_task_key, business_type, business_id, "
                + "task_key, schedule_scope, mode_snapshot, plan_status, input_hash, instance_version, "
                + "create_dept, create_by, create_time, update_time) "
                + "values (?, 'BUSINESS', ?, 1, 10, ?, 'INBOUND', ?, "
                + "'INBOUND_FIXTURE', '88001', ?, 'ROOT', 'QUEUE', 'RUNNING', ?, 0, "
                + "9101, 9001, ?, ?)",
            instanceId,
            DEFINITION_VERSION_ID,
            planType,
            "INBOUND:INV-008",
            taskKey,
            inputHash,
            now,
            now
        );
        jdbcTemplate.update(
            "insert into iws_process_token "
                + "(id, instance_id, token_role, token_status, state_code, parent_scope_key, "
                + "scope_key, branch_key, token_version, round_no, create_time, update_time) "
                + "values (?, ?, 'BRANCH', 'BLOCKED', 'CHECKED', 'ROOT', 'ROOT:START', 'ROOT', 1, 0, ?, ?)",
            tokenId, instanceId, now, now
        );
        jdbcTemplate.update(
            "insert into iws_process_schedule_control "
                + "(id, scope_type, scope_key, duty, control_state, revision, wake_pending, "
                + "slot_state, fairness_count, create_time, update_time) "
                + "values (?, 'PLAN_SLOT', ?, 'PLAN_SLOT', 'AUTO_RUNNING', 1, 0, 'HELD', 0, ?, ?)",
            scheduleControlId, String.valueOf(instanceId), now, now
        );
    }

    private void insertProcessEvent(
        long eventId,
        long instanceId,
        long tokenId,
        String eventCode,
        String localActionKey,
        String localEffectKey,
        String inputHash
    ) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "insert into iws_process_event "
                + "(id, instance_id, token_id, definition_version_id, event_definition_id, "
                + "event_code, event_key, branch_identity, event_role, event_direction, system_code, "
                + "local_action_key, local_action_version, local_effect_key, source_state_code, "
                + "target_state_code, event_status, sent, payload_ready, input_hash, idempotency_key, "
                + "execution_purpose, priority, retry_count, lease_epoch, event_version, "
                + "recovery_requested, create_time, update_time) "
                + "values (?, ?, ?, ?, 1, ?, ?, 'ROOT', 'BUSINESS', 'INTERNAL', 'IWS', ?, '1', ?, "
                + "'CHECKED', 'APPLIED', 'PENDING', 0, 1, ?, ?, 'NORMAL', 0, 0, 0, 1, 0, ?, ?)",
            eventId, instanceId, tokenId, DEFINITION_VERSION_ID,
            eventCode, "E:" + eventCode, localActionKey,
            localEffectKey, inputHash, "IDEMP-" + eventId, now, now
        );
    }

    private String effectKey(String seed) {
        return "WMS:" + sha256(seed);
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private <T> T inTransaction(Callable<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return Objects.requireNonNull(template.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }));
    }

    private WmsAllocationReservationResult reserveWithDeadlockRetry(
        WmsAllocationReserveCommand command
    ) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return inTransaction(() -> reservationService.reserve(command));
            } catch (CannotAcquireLockException error) {
                if (attempt == 2) {
                    throw error;
                }
            }
        }
        throw new IllegalStateException("不可达");
    }

    private Callable<Outcome> worker(
        Callable<WmsAllocationReservationResult> action,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await(20L, TimeUnit.SECONDS);
            try {
                return new Outcome(action.call(), null);
            } catch (Throwable error) {
                return new Outcome(null, error);
            }
        };
    }

    private Callable<OperationOutcome> operationWorker(
        Callable<?> action,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await(20L, TimeUnit.SECONDS);
            try {
                action.call();
                return new OperationOutcome(null);
            } catch (Throwable error) {
                return new OperationOutcome(error);
            }
        };
    }

    private record Outcome(WmsAllocationReservationResult result, Throwable error) {
    }

    private record WmsMaterialCompositeFixture(
        long inventoryId,
        String effectKey,
        long allocationLockId,
        WmsMaterialChangeCommand materialCommand,
        WmsLoadChangeCommand loadChange,
        WmsMaterialAllocationConsumption consumption
    ) {
    }

    private record OperationOutcome(Throwable error) {
    }

    private record LoadOutcome(WmsLoadChangeResult result, Throwable error) {
    }
}
