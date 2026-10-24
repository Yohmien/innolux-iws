/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划同步结果、期限、失败停留、回执与名额隔离验收；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.immediate;

import com.innolux.iws.common.core.exception.ServiceException;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.api.ImmediateExecutionVO;
import com.innolux.iws.process.runtime.ImmediateAdmissionService;
import com.innolux.iws.process.runtime.ImmediateExecutionDriver;
import com.innolux.iws.process.runtime.ImmediateExecutionService;
import com.innolux.iws.process.runtime.ImmediateOutcomeService;
import com.innolux.iws.process.runtime.ImmediateInputEvidenceRef;
import com.innolux.iws.process.runtime.ProcessAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 即时计划真实 MySQL 隔离验收。
 */
@SpringBootTest(classes = ImmediateFixtureApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN", "iws.process.enabled=true",
        "iws.process.immediate.max-concurrency=4", "iws.process.immediate.response-reserve-ms=30"})
@EnabledIfSystemProperty(named = "iws.immediate.test.mysql-datadir", matches = ".+")
@Tag("dev")
class ImmediateExecutionMysqlIntegrationTest {

    private static final long SCHEDULE_ID = 8001L;

    private static final long SUPERVISOR_DEFINITION_ID = 9001L;

    private static final long BUSINESS_DEFINITION_ID = 9101L;

    @Autowired
    private ImmediateExecutionService service;

    @Autowired
    private ImmediateAdmissionService admissionService;

    @Autowired
    private ImmediateOutcomeService outcomeService;

    @Autowired
    private ImmediateActionFixture action;

    @Autowired
    private ImmediateInputEvidenceFixture inputEvidence;

    @Autowired
    private ProcessAccessPolicy accessPolicy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() throws Exception {
        SaTokenContextMockUtil.setMockContext();
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return List.of();
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return List.of();
            }
        });
        StpUtil.login(1001L);
        ImmediateMysqlFixture.resetSchema();
        seed(3000L);
        action.reset(ImmediateActionFixture.Behavior.SUCCEEDED, 0L);
        inputEvidence.reset();
    }

    @AfterEach
    void clearSession() {
        try {
            StpUtil.logout();
        } catch (RuntimeException ignored) {
            // 上下文可能已关闭。
        }
        SaTokenContextMockUtil.clearContext();
    }

    @Test
    void successIsSynchronousAndReceiptReadsDoNotExecuteOrAppend() {
        ExecuteImmediateCommand command = command("c-success", "t-success", "input-success");
        ImmediateExecutionVO result = service.execute(command);

        assertEquals("SUCCEEDED", result.resultCode());
        assertEquals("PROCESSING", result.originalResponseCode());
        assertEquals("SUCCEEDED", result.effectResult());
        assertNotNull(result.instanceId());
        assertNotNull(result.supervisorInstanceId());
        assertNotNull(result.startedAt());
        assertNotNull(result.finishedAt());
        assertFalse(result.reconciliationRequired());
        assertEquals("/process/schedule/8001/commands/c-success", result.queryUrl());
        assertEquals("COMPLETED", planStatus());
        assertEquals("RELEASED", slotState());
        assertEquals(2, count("SELECT COUNT(*) FROM iws_process_event WHERE instance_id = ? AND event_status = 'SUCCESS'",
            Long.parseLong(result.instanceId())));
        assertEquals(2, count("SELECT COUNT(*) FROM iws_process_attempt WHERE event_id IN"
            + " (SELECT id FROM iws_process_event WHERE instance_id = ?)", Long.parseLong(result.instanceId())));
        assertEquals(2, count("SELECT COUNT(*) FROM iws_process_action_audit WHERE command_key = 'c-success'"));
        LocalDateTime deadline = jdbcTemplate.queryForObject(
            "SELECT execution_deadline FROM iws_process_instance WHERE id = ?", LocalDateTime.class,
            Long.parseLong(result.instanceId()));
        assertNotNull(deadline);

        ImmediateExecutionVO replay = service.execute(command);
        ImmediateExecutionVO queried = service.find(SCHEDULE_ID, "c-success");
        assertEquals(result, replay);
        assertEquals(result.resultCode(), queried.resultCode());
        assertEquals(2, count("SELECT COUNT(*) FROM iws_process_action_audit WHERE command_key = 'c-success'"));
        assertEquals(2, action.invocations());
        assertEquals(deadline, jdbcTemplate.queryForObject(
            "SELECT execution_deadline FROM iws_process_instance WHERE id = ?", LocalDateTime.class,
            Long.parseLong(result.instanceId())));

        ExecuteImmediateCommand conflict = command("c-success", "t-success", "other-input");
        assertThrows(ServiceException.class, () -> service.execute(conflict));
        assertEquals(2, count("SELECT COUNT(*) FROM iws_process_action_audit WHERE command_key = 'c-success'"));

        ImmediateExecutionVO existingTask = service.execute(command("c-new-key", "t-success", "input-success"));
        assertEquals("BUSY", existingTask.resultCode());
        assertTrue(existingTask.message().contains("TASK_KEY_ALREADY_EXISTS"));
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_instance WHERE plan_role = 'BUSINESS'"));
        assertEquals(2, action.invocations());
    }

    @Test
    void deterministicFailureIsTerminalAndNeverAutomaticallyRetried() throws Exception {
        action.reset(ImmediateActionFixture.Behavior.FAILED, 0L);
        ImmediateExecutionVO result = service.execute(command("c-failed", "t-failed", "input-failed"));

        assertEquals("FAILED", result.resultCode());
        assertEquals("FAILED", result.effectResult());
        assertEquals("FAILED_FINAL", planStatus());
        assertEquals("RELEASED", slotState());
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_attempt"));
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_event WHERE event_status = 'RETRY_WAIT'"));
        Thread.sleep(400L);
        assertEquals(1, action.invocations());
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_attempt"));

        ImmediateExecutionVO newKey = service.execute(command("c-failed-retry", "t-failed", "input-failed"));
        assertEquals("BUSY", newKey.resultCode());
        assertEquals(1, action.invocations());
    }

    @Test
    void unknownKeepsPlanSlotAndBlocksAnotherTask() {
        action.reset(ImmediateActionFixture.Behavior.UNKNOWN, 0L);
        ImmediateExecutionVO result = service.execute(command("c-unknown", "t-unknown", "input-unknown"));

        assertEquals("RESULT_UNKNOWN", result.resultCode());
        assertTrue(result.reconciliationRequired());
        assertEquals("EXCEPTION_HOLD", planStatus());
        assertEquals("HELD", slotState());
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_event WHERE effect_result = 'UNKNOWN'"));

        ImmediateExecutionVO other = service.execute(command("c-other", "t-other", "input-other"));
        assertEquals("BUSY", other.resultCode());
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_instance WHERE plan_role = 'BUSINESS'"));
    }

    @Test
    void deadlinePersistsStopRequestAndPreventsLaterEventStart() throws Exception {
        jdbcTemplate.update("UPDATE iws_process_definition SET max_execution_ms = 250 WHERE id = ?",
            BUSINESS_DEFINITION_ID);
        action.reset(ImmediateActionFixture.Behavior.SLOW_SUCCEEDED, 900L);
        long started = System.nanoTime();
        ImmediateExecutionVO result = service.execute(command("c-timeout", "t-timeout", "input-timeout"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals("RESPONSE_TIMEOUT", result.resultCode());
        assertTrue(result.reconciliationRequired());
        assertTrue(elapsedMs < 800L, "同步响应应在动作完成前返回，实际=" + elapsedMs);
        Map<String, Object> instance = jdbcTemplate.queryForMap(
            "SELECT id, stop_requested, execution_deadline, plan_status FROM iws_process_instance"
                + " WHERE plan_role = 'BUSINESS'");
        assertEquals(Boolean.TRUE, instance.get("stop_requested"));
        assertNotNull(instance.get("execution_deadline"));
        assertEquals("HELD", slotState());
        waitFor("SELECT COUNT(*) FROM iws_process_event WHERE event_code = 'E_ONE' AND event_status = 'SUCCESS'");
        waitFor("SELECT COUNT(*) FROM iws_process_instance WHERE plan_role = 'BUSINESS'"
            + " AND plan_status = 'FAILED_FINAL'");
        assertEquals("PENDING", jdbcTemplate.queryForObject(
            "SELECT event_status FROM iws_process_event WHERE event_code = 'E_TWO'", String.class));
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_attempt a"
            + " JOIN iws_process_event e ON e.id = a.event_id WHERE e.event_code = 'E_TWO'"));
        assertEquals("RELEASED", slotState());
        ImmediateExecutionVO converged = service.find(SCHEDULE_ID, "c-timeout");
        assertEquals("PROCESSING", converged.originalResponseCode());
        assertEquals("FAILED", converged.resultCode());
        assertEquals(3, count("SELECT COUNT(*) FROM iws_process_action_audit WHERE command_key = 'c-timeout'"));

    }

    @Test
    void supervisorPauseAndBusyAreDistinctImmediateErrors() {
        service.execute(command("c-first", "t-first", "in-first"));
        jdbcTemplate.update("UPDATE iws_process_schedule_control SET control_state = 'MANUAL_PAUSED'"
            + " WHERE scope_type = 'SUPERVISOR' AND duty = 'DISPATCH'");
        ImmediateExecutionVO paused = service.execute(command("c-paused", "t-paused", "in-paused"));
        assertEquals("SUPERVISOR_PAUSED", paused.resultCode());

        jdbcTemplate.update("UPDATE iws_process_schedule_control SET control_state = 'AUTO_RUNNING',"
            + " round_status = 'OPEN', lease_until = DATE_ADD(NOW(3), INTERVAL 1 MINUTE)"
            + " WHERE scope_type = 'SUPERVISOR' AND duty = 'DISPATCH'");
        ImmediateExecutionVO busy = service.execute(command("c-busy", "t-busy", "in-busy"));
        assertEquals("SUPERVISOR_BUSY", busy.resultCode());
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_instance WHERE plan_role = 'BUSINESS'"));
    }

    @Test
    void queueSupervisorIsRejectedWithoutCreatingBusinessInstance() {
        jdbcTemplate.update("UPDATE iws_process_definition SET mode = 'QUEUE' WHERE id = ?",
            SUPERVISOR_DEFINITION_ID);
        ImmediateExecutionVO result = service.execute(command("c-mode", "t-mode", "in-mode"));

        assertEquals("MODE_MISMATCH", result.resultCode());
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_instance"));
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_schedule_control"));
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_action_audit WHERE receipt_key ="
            + " 'IMMEDIATE:8001:c-mode'"));
    }

    @Test
    void failedHoldKeepsSlotAndAppendsWithoutUpdatingAnchor() {
        ExecuteImmediateCommand command = command("c-hold", "t-hold", "in-hold");
        ImmediateAdmissionService.Admission admission = admissionService.admit(command);
        assertTrue(admission.admitted());
        long instanceId = admission.instance().getId();
        jdbcTemplate.update("UPDATE iws_process_event SET event_status = 'NOT_SELECTED', wait_reason = NULL"
            + " WHERE instance_id = ?", instanceId);

        ImmediateExecutionVO result = outcomeService.finalize(admission,
            new ImmediateExecutionDriver.DriveResult(ImmediateExecutionDriver.Outcome.BLOCKED, "无候选事件"));

        assertEquals("FAILED_HOLD", result.resultCode());
        assertEquals("PROCESSING", result.originalResponseCode());
        assertTrue(result.reconciliationRequired());
        assertEquals("EXCEPTION_HOLD", planStatus());
        assertEquals("HELD", slotState());
        assertEquals(0, action.invocations());
        assertEquals(2, count("SELECT COUNT(*) FROM iws_process_action_audit WHERE command_key = 'c-hold'"));
        String anchorReason = jdbcTemplate.queryForObject(
            "SELECT after_status FROM iws_process_action_audit WHERE receipt_key = 'IMMEDIATE:8001:c-hold'",
            String.class);
        assertEquals("PROCESSING", anchorReason);
    }

    @Test
    void fixtureRegistersOnlyDeclaredScheduleObjectScope() {
        assertTrue(accessPolicy.canAccess(new com.innolux.iws.process.runtime.ProcessAccessTarget(
            0L, null, SCHEDULE_ID, null, null, null, null, "EXECUTE_IMMEDIATE")));
        assertFalse(accessPolicy.canAccess(new com.innolux.iws.process.runtime.ProcessAccessTarget(
            0L, null, 8999L, null, null, null, null, "EXECUTE_IMMEDIATE")));
    }

    @Test
    void providedInboundBindsExistingReceiptWithoutCreatingAnother() {
        seedInbound();
        insertInboundReceipt(10001L, "in-hash");
        inputEvidence.set(List.of(new ImmediateInputEvidenceRef("E_IN", 10001L)));

        ImmediateExecutionVO result = service.execute(inboundCommand("c-in-ok", "in-hash"));

        assertEquals("SUCCEEDED", result.resultCode());
        assertEquals("COMPLETED", planStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_trigger_receipt"));
        Map<String, Object> receipt = jdbcTemplate.queryForMap(
            "SELECT instance_id, event_id, canonical, processing_status FROM iws_process_trigger_receipt"
                + " WHERE id = 10001");
        assertNotNull(receipt.get("instance_id"));
        assertNotNull(receipt.get("event_id"));
        assertEquals(Boolean.TRUE, receipt.get("canonical"));
        assertEquals("APPLIED", receipt.get("processing_status"));
        assertEquals(2, count("SELECT COUNT(*) FROM iws_process_event WHERE event_status = 'SUCCESS'"));
    }

    @Test
    void missingPersistedInboundEvidenceIsRejectedBeforeAdmission() {
        seedInbound();
        ImmediateExecutionVO result = service.execute(inboundCommand("c-in-missing", "in-hash"));

        assertEquals("NOT_READY", result.resultCode());
        assertTrue(result.message().contains("未提供本库持久入站证据"));
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_instance"));
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_schedule_control"));
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_trigger_receipt"));
    }

    @Test
    void persistedInboundEvidenceHashConflictIsRejectedWithoutBinding() {
        seedInbound();
        insertInboundReceipt(10002L, "different-hash");
        inputEvidence.set(List.of(new ImmediateInputEvidenceRef("E_IN", 10002L)));

        ImmediateExecutionVO result = service.execute(inboundCommand("c-in-conflict", "in-hash"));

        assertEquals("NOT_READY", result.resultCode());
        assertTrue(result.message().contains("INPUT_EVIDENCE_HASH_CONFLICT"));
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_instance"));
        Map<String, Object> receipt = jdbcTemplate.queryForMap(
            "SELECT instance_id, event_id, canonical FROM iws_process_trigger_receipt WHERE id = 10002");
        assertEquals(null, receipt.get("instance_id"));
        assertEquals(null, receipt.get("event_id"));
        assertEquals(Boolean.FALSE, receipt.get("canonical"));
    }

    private void seed(long maxExecutionMs) {
        jdbcTemplate.update("INSERT INTO iws_business_schedule (id, business_code, business_name, enabled,"
                + " fairness_window_size, revision, del_flag, create_time, update_time)"
                + " VALUES (?, 'SCH_IMM', '即时测试', 1, 5, 1, '0', NOW(3), NOW(3))", SCHEDULE_ID);
        jdbcTemplate.update("INSERT INTO iws_business_schedule_plan_type (id, schedule_id, plan_type,"
                + " plan_type_name, priority, enabled, create_time, update_time)"
                + " VALUES (8101, ?, 'IMM', '即时类型', 0, 1, NOW(3), NOW(3))", SCHEDULE_ID);
        jdbcTemplate.update("INSERT INTO iws_process_definition (id, plan_code, plan_version, plan_type,"
                + " business_type, business_schedule_code, plan_name, plan_role, status, revision, latest_key,"
                + " active_key, self_system_code, mode, active_plan_limit, max_execution_ms, completion_policy,"
                + " round_max_duration_ms, lease_ms, retry_max, del_flag, create_time, update_time)"
                + " VALUES (?, 'S_IMM', 1, 'IMM', 'BIZ', 'SCH_IMM', '即时监督', 'SUPERVISOR', 'ACTIVE', 1,"
                + " 'S_LATEST', 'S_ACTIVE', 'IWS', 'IMMEDIATE', 1, 5000, 'ALL_SELECTED', 5000, 5000, 0,"
                + " '0', NOW(3), NOW(3))", SUPERVISOR_DEFINITION_ID);
        jdbcTemplate.update("INSERT INTO iws_process_definition (id, plan_code, plan_version, plan_type,"
                + " business_type, business_schedule_code, plan_name, plan_role, status, revision, latest_key,"
                + " active_key, self_system_code, completion_policy, max_execution_ms, lease_ms, retry_max,"
                + " del_flag, create_time, update_time)"
                + " VALUES (?, 'B_IMM', 1, 'IMM', 'BIZ', 'SCH_IMM', '即时业务', 'BUSINESS', 'ACTIVE', 1,"
                + " 'B_LATEST', 'B_ACTIVE', 'IWS', 'ALL_SELECTED', ?, 2000, 0, '0', NOW(3), NOW(3))",
            BUSINESS_DEFINITION_ID, maxExecutionMs);
        jdbcTemplate.update("INSERT INTO iws_process_state (id, definition_version_id, state_code, display_name,"
                + " state_kind, validator_key, validator_version, terminal, sort_order, create_time, update_time)"
                + " VALUES (9301, ?, 'S_START', '开始', 'START', ?, '1', 0, 0, NOW(3), NOW(3)),"
                + " (9302, ?, 'S_MID', '中间', 'NORMAL', ?, '1', 0, 1, NOW(3), NOW(3)),"
                + " (9303, ?, 'S_END', '结束', 'END', ?, '1', 1, 2, NOW(3), NOW(3))",
            BUSINESS_DEFINITION_ID, ImmediateActionFixture.VALIDATOR_KEY,
            BUSINESS_DEFINITION_ID, ImmediateActionFixture.VALIDATOR_KEY,
            BUSINESS_DEFINITION_ID, ImmediateActionFixture.VALIDATOR_KEY);
        jdbcTemplate.update("INSERT INTO iws_process_event_definition (id, definition_version_id, event_code,"
                + " display_name, event_role, event_direction, event_type_key, event_type_version,"
                + " local_action_key, local_action_version, local_effect_key, system_code, target_system_code,"
                + " source_state_code, target_state_code, enabled, priority, edit_policy, graph_edit_policy,"
                + " create_time, update_time)"
                + " VALUES (9201, ?, 'E_ONE', '第一步', 'BUSINESS', 'INTERNAL', 'internal-type', '1', ?, '1',"
                + " 'effect-one', 'IWS', 'IWS', 'S_START', 'S_MID', 1, 0, 'EDITABLE', 'EDITABLE_NEW_VERSION',"
                + " NOW(3), NOW(3)),"
                + " (9202, ?, 'E_TWO', '第二步', 'BUSINESS', 'INTERNAL', 'internal-type', '1', ?, '1',"
                + " 'effect-two', 'IWS', 'IWS', 'S_MID', 'S_END', 1, 0, 'EDITABLE', 'EDITABLE_NEW_VERSION',"
                + " NOW(3), NOW(3))",
            BUSINESS_DEFINITION_ID, ImmediateActionFixture.LOCAL_KEY,
            BUSINESS_DEFINITION_ID, ImmediateActionFixture.LOCAL_KEY);
        jdbcTemplate.update("INSERT INTO iws_process_edge (id, definition_version_id, predecessor_event_code,"
                + " successor_event_code, branch_key, branch_kind, default_branch, priority, create_time, update_time)"
                + " VALUES (9401, ?, 'E_ONE', 'E_TWO', 'DEFAULT', 'SEQUENTIAL', 0, 0, NOW(3), NOW(3))",
            BUSINESS_DEFINITION_ID);
        jdbcTemplate.update("INSERT INTO iws_process_system_policy (id, definition_version_id, system_code,"
                + " posting_mode, create_time, update_time) VALUES (9501, ?, 'IWS', 'AUTOMATIC', NOW(3), NOW(3))",
            BUSINESS_DEFINITION_ID);
    }

    private void seedInbound() {
        jdbcTemplate.update("UPDATE iws_business_schedule SET business_code = 'SCH_IN',"
            + " business_name = '即时入站测试' WHERE id = ?", SCHEDULE_ID);
        jdbcTemplate.update("DELETE FROM iws_business_schedule_plan_type WHERE schedule_id = ?", SCHEDULE_ID);
        jdbcTemplate.update("INSERT INTO iws_business_schedule_plan_type (id, schedule_id, plan_type,"
                + " plan_type_name, priority, enabled, create_time, update_time)"
                + " VALUES (8102, ?, 'IN', '入站类型', 0, 1, NOW(3), NOW(3))", SCHEDULE_ID);
        jdbcTemplate.update("INSERT INTO iws_process_definition (id, plan_code, plan_version, plan_type,"
                + " business_type, business_schedule_code, plan_name, plan_role, status, revision, latest_key,"
                + " active_key, self_system_code, mode, active_plan_limit, max_execution_ms, completion_policy,"
                + " round_max_duration_ms, lease_ms, retry_max, del_flag, create_time, update_time)"
                + " VALUES (9021, 'S_IN', 1, 'IN', 'BIZ', 'SCH_IN', '入站监督', 'SUPERVISOR', 'ACTIVE', 1,"
                + " 'S_IN_LATEST', 'S_IN_ACTIVE', 'IWS', 'IMMEDIATE', 1, 5000, 'ALL_SELECTED', 5000, 5000, 0,"
                + " '0', NOW(3), NOW(3))");
        jdbcTemplate.update("INSERT INTO iws_process_definition (id, plan_code, plan_version, plan_type,"
                + " business_type, business_schedule_code, plan_name, plan_role, status, revision, latest_key,"
                + " active_key, self_system_code, completion_policy, max_execution_ms, lease_ms, retry_max,"
                + " del_flag, create_time, update_time)"
                + " VALUES (9121, 'B_IN', 1, 'IN', 'BIZ', 'SCH_IN', '入站业务', 'BUSINESS', 'ACTIVE', 1,"
                + " 'B_IN_LATEST', 'B_IN_ACTIVE', 'IWS', 'ALL_SELECTED', 3000, 2000, 0, '0', NOW(3), NOW(3))");
        jdbcTemplate.update("INSERT INTO iws_process_state (id, definition_version_id, state_code, display_name,"
                + " state_kind, validator_key, validator_version, terminal, sort_order, create_time, update_time)"
                + " VALUES (9321, 9121, 'S_START', '开始', 'START', ?, '1', 0, 0, NOW(3), NOW(3)),"
                + " (9322, 9121, 'S_MID', '中间', 'NORMAL', ?, '1', 0, 1, NOW(3), NOW(3)),"
                + " (9323, 9121, 'S_END', '结束', 'END', ?, '1', 1, 2, NOW(3), NOW(3))",
            ImmediateActionFixture.VALIDATOR_KEY, ImmediateActionFixture.VALIDATOR_KEY,
            ImmediateActionFixture.VALIDATOR_KEY);
        jdbcTemplate.update("INSERT INTO iws_process_event_definition (id, definition_version_id, event_code,"
                + " display_name, event_role, event_direction, event_type_key, event_type_version,"
                + " call_contract_key, call_contract_version, operation_kind, effect_key, system_code,"
                + " target_system_code, purpose, completion_contract, source_state_code, target_state_code,"
                + " enabled, priority, edit_policy, graph_edit_policy, create_time, update_time)"
                + " VALUES (9221, 9121, 'E_IN', '入站证据', 'BUSINESS', 'INBOUND', 'inbound-type', '1',"
                + " ?, '1', 'INBOUND_TRIGGER', 'effect-in', 'IWS', 'IWS', 'TRIGGER_EVENT',"
                + " 'BUSINESS_COMPLETION', 'S_START', 'S_MID', 1, 0, 'EDITABLE', 'EDITABLE_NEW_VERSION',"
                + " NOW(3), NOW(3))",
            ImmediateActionFixture.INBOUND_CONTRACT);
        jdbcTemplate.update("INSERT INTO iws_process_event_definition (id, definition_version_id, event_code,"
                + " display_name, event_role, event_direction, event_type_key, event_type_version,"
                + " local_action_key, local_action_version, local_effect_key, system_code, target_system_code,"
                + " source_state_code, target_state_code, enabled, priority, edit_policy, graph_edit_policy,"
                + " create_time, update_time) VALUES (9222, 9121, 'E_DONE', '完成', 'BUSINESS', 'INTERNAL',"
                + " 'internal-type', '1', ?, '1', 'effect-done', 'IWS', 'IWS', 'S_MID', 'S_END', 1, 0,"
                + " 'EDITABLE', 'EDITABLE_NEW_VERSION', NOW(3), NOW(3))", ImmediateActionFixture.LOCAL_KEY);
        jdbcTemplate.update("INSERT INTO iws_process_edge (id, definition_version_id, predecessor_event_code,"
                + " successor_event_code, branch_key, branch_kind, default_branch, priority, create_time, update_time)"
                + " VALUES (9421, 9121, 'E_IN', 'E_DONE', 'DEFAULT', 'SEQUENTIAL', 0, 0, NOW(3), NOW(3))");
        jdbcTemplate.update("INSERT INTO iws_process_system_policy (id, definition_version_id, system_code,"
                + " posting_mode, create_time, update_time) VALUES (9521, 9121, 'IWS', 'AUTOMATIC', NOW(3), NOW(3))");
    }

    private void insertInboundReceipt(long id, String payloadHash) {
        jdbcTemplate.update("INSERT INTO iws_process_trigger_receipt (id, caller_system_code, module_code,"
                + " call_contract_key, call_contract_version, source_request_id, payload_hash, effect_key,"
                + " logical_call_key, instance_id, event_id, purpose, canonical, processing_status,"
                + " ack_summary, evidence_summary, received_at, applied_at, create_time, update_time)"
                + " VALUES (?, 'IWS', 'IN', ?, '1', ?, ?, 'effect-in', ?, NULL, NULL, 'TRIGGER_EVENT', 0,"
                + " 'PENDING_LINK', '待绑定', '持久入站证据', NOW(3), NULL, NOW(3), NOW(3))",
            id, ImmediateActionFixture.INBOUND_CONTRACT, "REQ-" + id, payloadHash, "LOG-" + id);
    }

    private ExecuteImmediateCommand inboundCommand(String commandKey, String inputHash) {
        return new ExecuteImmediateCommand(SCHEDULE_ID, "IN", "BIZ", "BIZ-IN", "task-" + commandKey,
            inputHash, commandKey);
    }

    private ExecuteImmediateCommand command(String commandKey, String taskKey, String inputHash) {
        return new ExecuteImmediateCommand(SCHEDULE_ID, "IMM", "BIZ", "BIZ-1", taskKey, inputHash, commandKey);
    }

    private String planStatus() {
        return jdbcTemplate.queryForObject("SELECT plan_status FROM iws_process_instance WHERE plan_role = 'BUSINESS'",
            String.class);
    }

    private String slotState() {
        return jdbcTemplate.queryForObject("SELECT slot_state FROM iws_process_schedule_control"
            + " WHERE scope_type = 'PLAN_SLOT' AND duty = 'PLAN_SLOT'", String.class);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void waitFor(String sql) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (count(sql) > 0) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("等待隔离事实超时: " + sql);
    }
}
