/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理冻结输入
 * @Version      : V1.0
 * @Description  : 验证正式事件入口在本地动作事务内读取持久冻结输入；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.ActionResult;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.runtime.EndpointTargetResolver;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.EventUpdateService;
import com.innolux.iws.process.runtime.LocalAction;
import com.innolux.iws.process.runtime.LocalActionDescriptor;
import com.innolux.iws.process.runtime.LocalActionInput;
import com.innolux.iws.process.runtime.LocalActionInputProvider;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.ValidationResult;
import com.innolux.iws.process.support.DefinitionGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** 本地动作冻结输入提供器真实 MySQL 集成测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationReservationTestContext.class)
class WmsLocalActionInputProviderIntegrationTest {

    private static final long INSTANCE_ID = 99_001L;

    private static final long TOKEN_ID = 99_002L;

    private static final long CONTROL_ID = 99_003L;

    private static final long EVENT_ID = 99_004L;

    private static final long DEFINITION_VERSION_ID = 99_005L;

    private static final String ACTION_KEY = "WMS_FROZEN_INPUT_FIXTURE";

    private static final String LOCAL_EFFECT_KEY = "WMS:FROZEN_INPUT_FIXTURE";

    private static final String INPUT_HASH = "d".repeat(64);

    @Autowired
    private EventMutationService mutationService;

    @Autowired
    private RuntimeDao runtimeDao;

    @Autowired
    private PlanActivationService activationService;

    @Autowired
    private CapabilityRegistry capabilityRegistry;

    @Autowired
    private ProcessEventMapper eventMapper;

    @Autowired
    private ProcessInstanceMapper instanceMapper;

    @Autowired
    private ProcessTokenMapper tokenMapper;

    @Autowired
    private ProcessAttemptMapper attemptMapper;

    @Autowired
    private ProcessTriggerReceiptMapper receiptMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from iws_process_action_audit");
        jdbcTemplate.update("delete from iws_process_attempt");
        jdbcTemplate.update("delete from iws_process_event");
        jdbcTemplate.update("delete from iws_process_schedule_control");
        jdbcTemplate.update("delete from iws_process_token");
        jdbcTemplate.update("delete from iws_process_instance");
        jdbcTemplate.execute("""
            create table if not exists test_frozen_local_input (
                instance_id bigint not null primary key,
                payload_json mediumtext not null,
                evidence_ref varchar(128) not null
            ) engine=innodb default charset=utf8mb4
            """);
        jdbcTemplate.update("delete from test_frozen_local_input");
        seedProcess();
        reset(runtimeDao, activationService, capabilityRegistry);
        when(runtimeDao.event(anyLong())).thenAnswer(invocation ->
            eventMapper.selectById(invocation.getArgument(0))
        );
        when(runtimeDao.instance(anyLong())).thenAnswer(invocation ->
            instanceMapper.selectById(invocation.getArgument(0))
        );
        when(runtimeDao.token(anyLong())).thenAnswer(invocation ->
            tokenMapper.selectById(invocation.getArgument(0))
        );
        when(runtimeDao.systemContext(anyLong(), anyString())).thenReturn(null);
        when(runtimeDao.definition(DEFINITION_VERSION_ID)).thenReturn(definition());
        DefinitionGraph graph = mock(DefinitionGraph.class);
        when(graph.event("FROZEN_INPUT")).thenReturn(eventDefinition());
        ProcessState state = new ProcessState();
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
    void providerPayloadIsReadAndExecutedInsideTheFormalInternalPath() {
        jdbcTemplate.update(
            "insert into test_frozen_local_input(instance_id, payload_json, evidence_ref) values (?, ?, ?)",
            INSTANCE_ID,
            "{\"schema\":\"fixture/1\",\"command\":\"reserve\"}",
            "frozen-input:99001"
        );
        ProviderAction action = new ProviderAction(false);
        when(capabilityRegistry.localAction(ACTION_KEY, "1")).thenReturn(action);

        EventUpdateService.UpdateResult result = eventUpdateService().update(EVENT_ID, "provider-test");

        assertThat(result).isEqualTo(EventUpdateService.UpdateResult.SUCCEEDED);
        assertThat(action.executed).isTrue();
        assertThat(action.context.get().payloadJson())
            .isEqualTo("{\"schema\":\"fixture/1\",\"command\":\"reserve\"}");
        assertThat(action.context.get().evidenceRef()).isEqualTo("frozen-input:99001");
        assertThat(action.context.get().facts()).containsEntry("frozenInputSource", "test");
        ProcessEvent stored = eventMapper.selectById(EVENT_ID);
        assertThat(stored.getEventStatus()).isEqualTo(EventStatus.SUCCESS);
        assertThat(stored.getEffectResult()).isEqualTo(EffectResult.SUCCEEDED);
    }

    @Test
    void actionsWithoutProviderKeepThePlatformSummary() {
        PlainAction action = new PlainAction();
        when(capabilityRegistry.localAction(ACTION_KEY, "1")).thenReturn(action);

        EventUpdateService.UpdateResult result = eventUpdateService().update(EVENT_ID, "plain-test");

        assertThat(result).isEqualTo(EventUpdateService.UpdateResult.SUCCEEDED);
        assertThat(action.executed).isTrue();
        assertThat(action.context.get().payloadJson())
            .contains("\"eventKey\":\"E:FROZEN_INPUT\"")
            .contains("\"localEffectKey\":\"" + LOCAL_EFFECT_KEY + "\"");
    }

    @Test
    void blankProviderInputFailsClosedBeforeTheActionRuns() {
        jdbcTemplate.update(
            "insert into test_frozen_local_input(instance_id, payload_json, evidence_ref) values (?, '', ?)",
            INSTANCE_ID,
            "frozen-input:blank"
        );
        ProviderAction action = new ProviderAction(true);
        when(capabilityRegistry.localAction(ACTION_KEY, "1")).thenReturn(action);

        EventUpdateService.UpdateResult result = eventUpdateService().update(EVENT_ID, "blank-test");

        assertThat(result).isEqualTo(EventUpdateService.UpdateResult.FAILED);
        assertThat(action.executed).isFalse();
        ProcessEvent stored = eventMapper.selectById(EVENT_ID);
        assertThat(stored.getEffectResult()).isEqualTo(EffectResult.FAILED);
        assertThat(stored.getErrorMessage()).contains("冻结输入提供器返回空输入");
    }

    private EventUpdateService eventUpdateService() {
        return new EventUpdateService(
            runtimeDao,
            mutationService,
            activationService,
            capabilityRegistry,
            mock(EndpointTargetResolver.class),
            receiptMapper,
            attemptMapper,
            jsonMapper
        );
    }

    private void seedProcess() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "insert into iws_process_instance "
                + "(id, plan_role, definition_version_id, definition_revision, schedule_id, "
                + "plan_type, business_schedule_code, business_task_key, business_type, business_id, "
                + "task_key, schedule_scope, mode_snapshot, plan_status, input_hash, instance_version, "
                + "create_dept, create_by, create_time, update_time) "
                + "values (?, 'BUSINESS', ?, 1, 10, 'FROZEN_INPUT', 'INBOUND', "
                + "'INBOUND_FIXTURE:FROZEN-1', 'INBOUND_FIXTURE', '99001', 'FROZEN-1', "
                + "'DEFAULT', 'QUEUE', 'RUNNING', ?, 0, 9101, 9001, ?, ?)",
            INSTANCE_ID,
            DEFINITION_VERSION_ID,
            INPUT_HASH,
            now,
            now
        );
        jdbcTemplate.update(
            "insert into iws_process_token "
                + "(id, instance_id, token_role, token_status, state_code, parent_scope_key, "
                + "scope_key, branch_key, token_version, round_no, create_time, update_time) "
                + "values (?, ?, 'BRANCH', 'BLOCKED', 'CHECKED', 'ROOT', 'ROOT:CHECKED', "
                + "'ROOT', 1, 0, ?, ?)",
            TOKEN_ID,
            INSTANCE_ID,
            now,
            now
        );
        jdbcTemplate.update(
            "insert into iws_process_schedule_control "
                + "(id, scope_type, scope_key, duty, control_state, revision, wake_pending, "
                + "slot_state, fairness_count, create_time, update_time) "
                + "values (?, 'PLAN_SLOT', ?, 'PLAN_SLOT', 'AUTO_RUNNING', 1, 0, "
                + "'HELD', 0, ?, ?)",
            CONTROL_ID,
            String.valueOf(INSTANCE_ID),
            now,
            now
        );
        jdbcTemplate.update(
            "insert into iws_process_event "
                + "(id, instance_id, token_id, definition_version_id, event_definition_id, "
                + "event_code, event_key, branch_identity, event_role, event_direction, system_code, "
                + "local_action_key, local_action_version, local_effect_key, source_state_code, "
                + "target_state_code, event_status, sent, payload_ready, input_hash, idempotency_key, "
                + "execution_purpose, priority, retry_count, lease_epoch, event_version, "
                + "recovery_requested, create_time, update_time) "
                + "values (?, ?, ?, ?, 1, 'FROZEN_INPUT', 'E:FROZEN_INPUT', 'ROOT', 'BUSINESS', "
                + "'INTERNAL', 'IWS', ?, '1', ?, 'CHECKED', 'APPLIED', 'PENDING', 0, 1, ?, ?, "
                + "'NORMAL', 0, 0, 0, 1, 0, ?, ?)",
            EVENT_ID,
            INSTANCE_ID,
            TOKEN_ID,
            DEFINITION_VERSION_ID,
            ACTION_KEY,
            LOCAL_EFFECT_KEY,
            INPUT_HASH,
            "IDEMP-99004",
            now,
            now
        );
    }

    private PlanDefinition definition() {
        PlanDefinition definition = new PlanDefinition();
        definition.setId(DEFINITION_VERSION_ID);
        definition.setLeaseMs(120_000L);
        definition.setRetryMax(0);
        definition.setRetryBackoffMs(0L);
        definition.setMaxExecutionMs(120_000L);
        definition.setSelfSystemCode("IWS");
        return definition;
    }

    private ProcessEventDefinition eventDefinition() {
        ProcessEventDefinition definition = new ProcessEventDefinition();
        definition.setEventCode("FROZEN_INPUT");
        definition.setEventDirection(EventDirection.INTERNAL);
        definition.setLocalActionKey(ACTION_KEY);
        definition.setLocalActionVersion("1");
        definition.setLocalEffectKey(LOCAL_EFFECT_KEY);
        definition.setSourceStateCode("CHECKED");
        definition.setTargetStateCode("APPLIED");
        definition.setEnabled(Boolean.TRUE);
        return definition;
    }

    private final class ProviderAction implements LocalAction, LocalActionInputProvider {

        private final boolean blank;

        private final AtomicBoolean executed = new AtomicBoolean();

        private final AtomicReference<ActionContext> context = new AtomicReference<>();

        private ProviderAction(boolean blank) {
            this.blank = blank;
        }

        @Override
        public LocalActionDescriptor describe() {
            return new LocalActionDescriptor(ACTION_KEY, "1", "冻结输入fixture", "SAME_TX", true);
        }

        @Override
        public LocalActionInput provide(ActionContext ignored) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select payload_json, evidence_ref from test_frozen_local_input where instance_id = ?",
                INSTANCE_ID
            );
            if (rows.isEmpty()) {
                throw new ServiceException("测试冻结输入不存在");
            }
            String payload = blank ? "" : String.valueOf(rows.getFirst().get("payload_json"));
            return new LocalActionInput(
                payload,
                String.valueOf(rows.getFirst().get("evidence_ref")),
                Map.of("frozenInputSource", "test")
            );
        }

        @Override
        public ActionResult execute(ActionContext actionContext) {
            executed.set(true);
            context.set(actionContext);
            return new ActionResult(
                EffectResult.SUCCEEDED,
                actionContext.evidenceRef(),
                null,
                null,
                null,
                null,
                "{}",
                "fixture成功"
            );
        }
    }

    private final class PlainAction implements LocalAction {

        private final AtomicBoolean executed = new AtomicBoolean();

        private final AtomicReference<ActionContext> context = new AtomicReference<>();

        @Override
        public LocalActionDescriptor describe() {
            return new LocalActionDescriptor(ACTION_KEY, "1", "普通动作fixture", "SAME_TX", true);
        }

        @Override
        public ActionResult execute(ActionContext actionContext) {
            executed.set(true);
            context.set(actionContext);
            return new ActionResult(
                EffectResult.SUCCEEDED,
                actionContext.evidenceRef(),
                null,
                null,
                null,
                null,
                "{}",
                "fixture成功"
            );
        }
    }
}
