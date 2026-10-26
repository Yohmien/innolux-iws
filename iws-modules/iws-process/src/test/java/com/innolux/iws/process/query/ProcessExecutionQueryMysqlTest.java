/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 计划执行查询 SQL 分页、链事实、数据范围与报文隔离验收；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.query;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import tools.jackson.databind.json.JsonMapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.domain.DataPermissionAccess;
import com.innolux.iws.common.mybatis.helper.DataPermissionHelper;
import com.innolux.iws.process.api.query.EventExecutionVO;
import com.innolux.iws.process.api.query.EventRequestVO;
import com.innolux.iws.process.api.query.ExecutionOverviewVO;
import com.innolux.iws.process.api.query.PlanChainVO;
import com.innolux.iws.process.api.query.ProcessAuditEntryVO;
import com.innolux.iws.process.api.query.ProcessAuditQuery;
import com.innolux.iws.process.api.query.ProcessExecutionOverviewQuery;
import com.innolux.iws.process.api.query.ProcessRequestQuery;
import com.innolux.iws.process.api.query.ProcessRequestRecordVO;
import com.innolux.iws.process.runtime.PlanCandidateQueryService;
import com.innolux.iws.process.service.ProcessExecutionQueryService;
import com.innolux.iws.system.api.domain.RoleDTO;
import com.innolux.iws.system.api.model.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划执行查询隔离 MySQL 验收。
 */
@Tag("dev")
@SpringBootTest(classes = QueryFixtureApplication.class,
    properties = "iws.process.enabled=true")
class ProcessExecutionQueryMysqlTest {

    @Autowired
    private ProcessExecutionQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper objectMapper;

    @Autowired
    private PlanCandidateQueryService candidateQueryService;

    /**
     * 每个用例重建事实，避免测试间队列位次和总数互相影响。
     */
    @BeforeEach
    void seed() {
        QueryFixtureApplication.denyBusinessId(null);
        clear();
        LocalDateTime now = LocalDateTime.now();
        Timestamp ts = Timestamp.valueOf(now);
        Timestamp earlier = Timestamp.valueOf(now.minusMinutes(2));

        jdbcTemplate.update("INSERT INTO iws_business_schedule"
                + "(id,business_code,business_name,enabled,fairness_window_size,revision,create_dept,create_by,"
                + "create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
            100L, "SMT", "SMT出库", true, 2, 7L, 10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_business_schedule_plan_type"
                + "(id,schedule_id,plan_type,plan_type_name,priority,enabled,create_dept,create_by,create_time,"
                + "update_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
            101L, 100L, "BOX", "整箱", 80, true, 10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_business_schedule_plan_type"
                + "(id,schedule_id,plan_type,plan_type_name,priority,enabled,create_dept,create_by,create_time,"
                + "update_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
            102L, 100L, "CUT", "截料", 60, true, 10L, 1L, ts, ts);

        definition(200L, "SUP_BOX", "BOX", "整箱监督", "SUPERVISOR", "ACTIVE", "QUEUE", 1, 2, "ALL_SELECTED");
        definition(300L, "BOX_PLAN", "BOX", "整箱计划", "BUSINESS", "ACTIVE", null, null, null, "ALL_SELECTED");
        state(400L, 300L, "S0", "开始", "START", false, 0);
        state(401L, 300L, "S1", "明细就绪", "NORMAL", false, 1);
        state(402L, 300L, "S2", "结束", "END", true, 2);
        state(403L, 300L, "S_EXC", "异常", "EXCEPTION", false, 3);
        eventDefinition(500L, 300L, "E1", "出库抛账", "BUSINESS", "OUTBOUND", "C1", "1",
            "MID", "S0", "S1", 10);
        eventDefinition(501L, 300L, "E2", "本地完成", "BUSINESS", "INTERNAL", null, null,
            "IWS", "S1", "S2", 5);
        jdbcTemplate.update("INSERT INTO iws_process_edge"
                + "(id,definition_version_id,predecessor_event_code,successor_event_code,branch_key,branch_kind,"
                + "priority,create_dept,create_by,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            600L, 300L, "E1", "E2", "DEFAULT", "SEQUENTIAL", 0, 10L, 1L, ts, ts);

        instance(1000L, "SUPERVISOR", 200L, "BOX", null, "SUPERVISOR:100:BOX:DEFAULT", null,
            null, null, "RUNNING", 0, false, 10L, null, null, earlier);
        instance(1001L, "BUSINESS", 300L, "BOX", 1000L, null, "OUT:T1", "B1", "T1",
            "RUNNING", 5, false, 10L, ts, null, earlier);
        instance(1002L, "BUSINESS", 300L, "BOX", 1000L, null, "OUT:T2", "B2", "T2",
            "CREATED", 7, false, 10L, null, null, earlier);
        instance(1003L, "BUSINESS", 300L, "BOX", 1000L, null, "OUT:T3", "B3", "T3",
            "CREATED", 3, false, 10L, null, null, earlier);
        instance(1004L, "BUSINESS", 300L, "BOX", 1000L, null, "OUT:T4", "B4", "T4",
            "CREATED", 9, true, 10L, null, null, earlier);
        instance(1005L, "BUSINESS", 300L, "BOX", 1000L, null, "OUT:T5", "B5", "T5",
            "COMPLETED", 0, false, 10L, earlier, ts, earlier);
        instance(1006L, "BUSINESS", 301L, "CUT", 1000L, null, "OUT:T6", "B6", "T6",
            "CREATED", 0, false, 10L, null, null, earlier);
        instance(1007L, "BUSINESS", 300L, "BOX", 1000L, null, "OUT:T7", "B7", "T7",
            "CREATED", 0, false, 20L, null, null, ts);

        jdbcTemplate.update("INSERT INTO iws_process_schedule_control"
                + "(id,scope_type,scope_key,duty,control_state,desired_state,observed_state,revision,epoch,"
                + "wake_pending,round_no,round_status,supervisor_instance_id,definition_version_id,fairness_count,"
                + "last_activity_at,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            700L, "SUPERVISOR", "SUPERVISOR:100:BOX:DEFAULT", "DISPATCH", "AUTO_RUNNING", "RUNNING",
            "RUNNING", 1L, 0L, false, 0L, "COMPLETED", 1000L, 200L, 0, ts, 10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_process_schedule_control"
                + "(id,scope_type,scope_key,duty,revision,epoch,supervisor_instance_id,definition_version_id,"
                + "child_instance_id,slot_state,last_admitted_at,last_activity_at,create_dept,create_by,"
                + "create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            701L, "PLAN_SLOT", "1001", "PLAN_SLOT", 1L, 0L, 1000L, 200L, 1001L, "HELD", ts, ts,
            10L, 1L, ts, ts);

        jdbcTemplate.update("INSERT INTO iws_process_token"
                + "(id,instance_id,token_role,token_status,state_code,parent_scope_key,scope_key,branch_key,"
                + "token_version,round_no,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            2000L, 1001L, "BRANCH", "ACTIVE", "S0", "DEFAULT", "DEFAULT", "DEFAULT", 2L, 0L,
            10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_process_event"
                + "(id,instance_id,token_id,definition_version_id,event_definition_id,event_code,event_key,"
                + "round_no,branch_identity,event_role,event_direction,system_code,call_contract_key,"
                + "call_contract_version,effect_key,source_state_code,target_state_code,event_status,sent,"
                + "payload_ready,input_hash,idempotency_key,execution_purpose,priority,retry_count,lease_epoch,"
                + "event_version,started_at,last_attempt_id,recovery_requested,create_dept,create_by,create_time,"
                + "update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            3000L, 1001L, 2000L, 300L, 500L, "E1", "E1", 0L, "DEFAULT", "BUSINESS", "OUTBOUND",
            "MID", "C1", "1", "EFF1", "S0", "S1", "PROCESSING", true, true, "h1", "idem1",
            "NORMAL", 10, 0, 3L, 3L, ts, 4000L, false, 10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_process_attempt"
                + "(id,event_id,attempt_no,checkpoint_status,sent,request_id,input_hash,idempotency_key,"
                + "operation_version,effect_result,error_code,error_message,source_state_code,target_state_code,"
                + "owner,lease_epoch,execution_purpose,started_at,recorded_at,create_dept,create_by,create_time,"
                + "update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            4000L, 3000L, 1, "SEND_INTENT", true, "REQ-1", "h1", "idem1", "1", "UNKNOWN",
            "TIMEOUT", "外部结果未知", "S0", "S1", "worker:1", 3L, "NORMAL", ts, ts, 10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_process_request_detail"
                + "(id,request_id,direction,purpose,correlation_id,contract_key,contract_version,effect_key,"
                + "instance_id,event_id,attempt_id,http_status,result_code,effect_result,sent,request_summary,"
                + "response_summary,started_at,ended_at,evidence_at,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            5000L, "REQ-1", "OUTBOUND", "EXECUTE_ACTION", "CORR-1", "C1", "1", "EFF1", 1001L,
            3000L, 4000L, 504, "TIMEOUT", "UNKNOWN", true, "{\"order\":\"***\"}",
            "{\"error\":\"timeout\"}", ts, ts, ts, 10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_process_action_audit"
                + "(id,action_code,action_source,instance_id,event_id,attempt_id,result,reason,"
                + "decision_summary,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            6000L, "EVENT_EXECUTE", "SCHEDULER", 1001L, 3000L, 4000L, "SUCCESS",
            "执行尝试", "外部结果未知，等待对账", ts);

        jdbcTemplate.update("INSERT INTO iws_process_token"
                + "(id,instance_id,token_role,token_status,state_code,parent_scope_key,scope_key,branch_key,"
                + "token_version,round_no,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            2001L, 1005L, "BRANCH", "FINISHED", "S2", "DEFAULT", "DEFAULT", "DEFAULT", 3L, 0L,
            10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_process_event"
                + "(id,instance_id,token_id,definition_version_id,event_definition_id,event_code,event_key,"
                + "round_no,branch_identity,event_role,event_direction,source_state_code,target_state_code,"
                + "event_status,sent,event_version,ended_at,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            3001L, 1005L, 2001L, 300L, 500L, "E1", "E1", 0L, "DEFAULT", "BUSINESS", "OUTBOUND",
            "S0", "S1", "SUCCESS", true, 2L, ts, 10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_process_event"
                + "(id,instance_id,token_id,definition_version_id,event_definition_id,event_code,event_key,"
                + "round_no,branch_identity,event_role,event_direction,source_state_code,target_state_code,"
                + "event_status,sent,event_version,ended_at,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            3002L, 1005L, 2001L, 300L, 501L, "E2", "E2", 0L, "DEFAULT", "BUSINESS", "INTERNAL",
            "S1", "S2", "SUCCESS", false, 2L, ts, 10L, 1L, ts, ts);
    }

    @Test
    void overviewUsesSqlPagingAndExactSameScopeTotals() {
        withScope(10L, "1", true, () -> {
            ExecutionOverviewVO overview = queryService.overview(query(1, 1));
            assertEquals(1L, overview.executedPlans().getTotal());
            assertEquals(1L, overview.runningPlans().getTotal());
            assertEquals(5L, overview.pendingPlans().getTotal());
            assertEquals(0L, overview.exceptionPlans().getTotal());
            assertEquals(5L, overview.totals().pending());
            assertEquals(1, overview.pendingPlans().getRows().size());
            ExecutionOverviewVO.ExecutionPlanVO first = first(overview.pendingPlans().getRows());
            assertEquals("1002", first.instanceId());
            assertEquals(1, first.queueRank());
            assertEquals("WAITING_PLAN_SLOT", first.queueStatus());
            assertEquals("WAIT_PLAN_SLOT", first.blockReason());
            assertTrue(first.eligible());
            assertEquals(80, first.priority().planType());

            ExecutionOverviewVO page2 = queryService.overview(query(2, 1));
            ExecutionOverviewVO.ExecutionPlanVO second = first(page2.pendingPlans().getRows());
            assertEquals("1003", second.instanceId());
            assertEquals(2, second.queueRank());

            ExecutionOverviewVO page5 = queryService.overview(query(5, 1));
            ExecutionOverviewVO.ExecutionPlanVO held = first(page5.pendingPlans().getRows());
            assertEquals("1004", held.instanceId());
            assertEquals("HELD_BEFORE_START", held.queueStatus());
            assertNull(held.queueRank());
            assertFalse(held.eligible());
        });
    }

    @Test
    void chainReadsPersistedFactsAndDoesNotExposePayload() throws Exception {
        withScope(10L, "1", true, () -> {
            PlanChainVO chain = queryService.chain(1001L);
            assertEquals("1001", chain.instanceId());
            assertEquals("1", chain.instanceVersion());
            assertEquals("S0", chain.branches().getFirst().stateCode());
            assertTrue(chain.branches().getFirst().currentState().current());
            assertTrue(chain.branches().getFirst().nextStates().stream()
                .anyMatch(item -> item.stateCode().equals("S1") && Boolean.TRUE.equals(item.decided())));
            assertEquals("3000", chain.branches().getFirst().currentEvent().eventId());
            assertEquals("NOT_EVALUATED", chain.branches().getFirst().currentState().validation().getFirst().result());
            try {
                String json = objectMapper.writeValueAsString(chain);
                assertFalse(json.contains("REQ-1"));
                assertFalse(json.contains("requestSummary"));
                assertFalse(json.contains("order"));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    @Test
    void eventFactsAndRequestPayloadUseSeparateQueries() throws Exception {
        withScope(10L, "1", true, () -> {
            EventExecutionVO event = queryService.event(3000L);
            assertEquals("PROCESSING", event.eventStatus());
            assertFalse(event.canRetry());
            assertEquals(1, event.requestRetries().size());
            assertEquals("CORR-1", event.correlationId());
            try {
                String json = objectMapper.writeValueAsString(event);
                assertFalse(json.contains("requestBody"));
                assertFalse(json.contains("responseBody"));
                assertFalse(json.contains("timeout"));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }

            EventRequestVO request = queryService.request(3000L, "REQ-1");
            assertTrue(request.redacted());
            assertTrue(request.requestBody().contains("***"));
            assertTrue(request.responseBody().contains("timeout"));
            assertThrows(ServiceException.class, () -> queryService.request(3000L, "REQ-X"));
        });
    }

    @Test
    void requestOverviewIncludesUnlinkedRecordsWithoutOriginalPayload() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Timestamp ts = Timestamp.valueOf(now);
        jdbcTemplate.update("INSERT INTO iws_process_request_detail"
                + "(id,request_id,direction,purpose,source_request_id,correlation_id,caller_system_code,"
                + "receiver_system_code,module_code,contract_key,contract_version,effect_key,http_status,"
                + "result_code,effect_result,sent,request_summary,response_summary,started_at,ended_at,"
                + "evidence_at,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            5001L, "REQ-EXT-1", "INBOUND", "TRIGGER_EVENT", "SOURCE-9", "CORR-EXT", "EXT", "IWS",
            "WMS", "C9", "1", "EFF-EXT", 202, "ACCEPTED", "ACCEPTED", false,
            "{\"secret\":\"raw-body\"}", "{\"received\":true}", ts, ts, ts, 10L, 1L, ts, ts);

        withScope(10L, "1", true, () -> {
            var page = queryService.requestPage(new ProcessRequestQuery(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 1, 20));
            assertEquals(2L, page.getTotal());
            ProcessRequestRecordVO unlinked = page.getRows().stream()
                .filter(item -> item.requestId().equals("REQ-EXT-1")).findFirst().orElseThrow();
            assertEquals("UNLINKED", unlinked.associationStatus());
            assertEquals("入站", unlinked.directionText());
            assertEquals("触发事件", unlinked.purposeText());
            assertNull(unlinked.instanceId());
            assertNull(unlinked.eventId());

            ProcessRequestRecordVO linked = queryService.requestRecord("REQ-1");
            assertEquals("LINKED", linked.associationStatus());
            assertEquals("SMT", linked.businessScheduleCode());
            ProcessRequestRecordVO unlinkedDetail = queryService.requestRecord("REQ-EXT-1");
            assertEquals("UNLINKED", unlinkedDetail.associationStatus());
            try {
                String json = objectMapper.writeValueAsString(page);
                assertFalse(json.contains("raw-body"));
                assertFalse(json.contains("requestSummary"));
                assertFalse(json.contains("responseSummary"));
                String detailJson = objectMapper.writeValueAsString(unlinkedDetail);
                assertFalse(detailJson.contains("raw-body"));
                assertFalse(detailJson.contains("requestSummary"));
                assertFalse(detailJson.contains("responseSummary"));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        withScope(20L, "3", true, () -> {
            var page = queryService.requestPage(new ProcessRequestQuery(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 1, 20));
            assertEquals(0L, page.getTotal());
            assertThrows(ServiceException.class, () -> queryService.requestRecord("REQ-EXT-1"));
        });
    }

    @Test
    void requestListAppliesObjectPolicyBeforePagingAndKeepsUnlinkedSourceScope() {
        LocalDateTime now = LocalDateTime.now();
        Timestamp ts = Timestamp.valueOf(now);
        jdbcTemplate.update("INSERT INTO iws_process_request_detail"
                + "(id,request_id,direction,purpose,correlation_id,contract_key,contract_version,"
                + "instance_id,event_id,http_status,result_code,effect_result,sent,started_at,ended_at,"
                + "evidence_at,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            5002L, "REQ-POLICY-DENIED", "OUTBOUND", "EXECUTE_ACTION", "CORR-DENIED", "C2", "1",
            1002L, null, 200, "ACCEPTED", "SUCCEEDED", true, ts, ts, ts, 10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_process_request_detail"
                + "(id,request_id,direction,purpose,correlation_id,contract_key,contract_version,"
                + "instance_id,event_id,http_status,result_code,effect_result,sent,started_at,ended_at,"
                + "evidence_at,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            5003L, "REQ-DANGLING-EVENT", "OUTBOUND", "EXECUTE_ACTION", "CORR-DANGLING", "C3", "1",
            1001L, 9999L, 200, "ACCEPTED", "SUCCEEDED", true, ts, ts, ts, 10L, 1L, ts, ts);
        jdbcTemplate.update("INSERT INTO iws_process_request_detail"
                + "(id,request_id,direction,purpose,correlation_id,contract_key,contract_version,"
                + "http_status,result_code,effect_result,sent,started_at,ended_at,evidence_at,create_dept,"
                + "create_by,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            5004L, "REQ-UNLINKED", "INBOUND", "TRIGGER_EVENT", "CORR-UNLINKED", "C4", "1",
            202, "ACCEPTED", "ACCEPTED", false, ts, ts, ts, 10L, 1L, ts, ts);

        QueryFixtureApplication.denyBusinessId("B2");
        withScope(10L, "1", true, () -> {
            var page = queryService.requestPage(new ProcessRequestQuery(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 1, 1));
            assertEquals(2L, page.getTotal());
            assertEquals(1, page.getRows().size());
            assertTrue(page.getRows().stream().noneMatch(item -> item.requestId().equals("REQ-POLICY-DENIED")));
            assertTrue(page.getRows().stream().noneMatch(item -> item.requestId().equals("REQ-DANGLING-EVENT")));

            var secondPage = queryService.requestPage(new ProcessRequestQuery(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 2, 1));
            assertEquals(2L, secondPage.getTotal());
            assertEquals(1, secondPage.getRows().size());

            var unlinked = queryService.requestPage(new ProcessRequestQuery(null, null, null, null, null,
                null, null, null, null, null, null, null, false, null, null, 1, 20));
            assertEquals(1L, unlinked.getTotal());
            assertEquals("REQ-UNLINKED", unlinked.getRows().iterator().next().requestId());
            assertThrows(ServiceException.class, () -> queryService.requestRecord("REQ-POLICY-DENIED"));
            assertThrows(ServiceException.class, () -> queryService.requestRecord("REQ-DANGLING-EVENT"));
            assertEquals("UNLINKED", queryService.requestRecord("REQ-UNLINKED").associationStatus());
        });
    }

    @Test
    void eventRanksUseWorkerCandidateOrder() {
        Timestamp ts = Timestamp.valueOf(LocalDateTime.now());
        instance(1008L, "BUSINESS", 300L, "CUT", 1000L, null, "OUT:T8", "B8", "T8",
            "RUNNING", 9, false, 10L, ts, null, ts);
        jdbcTemplate.update("INSERT INTO iws_process_schedule_control"
                + "(id,scope_type,scope_key,duty,revision,epoch,supervisor_instance_id,definition_version_id,"
                + "child_instance_id,slot_state,last_admitted_at,last_activity_at,create_dept,create_by,"
                + "create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            702L, "PLAN_SLOT", "1008", "PLAN_SLOT", 1L, 0L, 1000L, 300L, 1008L, "HELD", ts, ts,
            10L, 1L, ts, ts);
        candidateEvent(3003L, 1001L, 2000L, "E3", 1, ts);
        candidateEvent(3004L, 1008L, null, "E4", 99, ts);

        withScope(10L, "1", true, () -> {
            List<PlanCandidateQueryService.CandidateRow> candidates = candidateQueryService
                .loadQueueCandidates(100L, 20);
            assertEquals(List.of(3003L, 3004L), candidates.stream().map(row -> row.eventId()).toList());
            PlanChainVO chain = queryService.chain(1001L);
            PlanChainVO.EventChainEdgeVO first = chain.branches().stream()
                .flatMap(item -> item.events().stream())
                .filter(item -> "3003".equals(item.eventId())).findFirst().orElseThrow();
            assertEquals(1, first.queueRank());
            assertTrue(first.eligible());
        });
    }

    @Test
    void dataScopeFiltersIdentityAndFailsClosed() {
        withScope(10L, "3", true, () -> {
            ExecutionOverviewVO overview = queryService.overview(query(1, 10));
            assertEquals(4L, overview.pendingPlans().getTotal());
            assertThrows(ServiceException.class, () -> queryService.chain(1007L));
        });
        withScope(20L, "3", true, () -> {
            ExecutionOverviewVO overview = queryService.overview(query(1, 10));
            assertEquals(1L, overview.pendingPlans().getTotal());
            assertThrows(ServiceException.class, () -> queryService.chain(1001L));
        });
        withScope(10L, "3", false, () -> {
            ExecutionOverviewVO overview = queryService.overview(query(1, 10));
            assertEquals(0L, overview.pendingPlans().getTotal());
            assertEquals(0L, overview.totals().pending());
            assertThrows(ServiceException.class, () -> queryService.chain(1001L));
        });
    }

    @Test
    void auditJoinsScopedInstanceAndReturnsSafeFields() {
        withScope(10L, "1", true, () -> {
            var page = queryService.audit(new ProcessAuditQuery("SMT", null, null, null, null, null,
                null, null, 1, 20));
            assertEquals(1L, page.getTotal());
            List<ProcessAuditEntryVO> rows = new ArrayList<>(page.getRows());
            assertEquals("EVENT_EXECUTE", rows.getFirst().action());
            assertEquals("执行尝试", rows.getFirst().reason());
            assertEquals("外部结果未知，等待对账", rows.getFirst().evidence());
        });
        withScope(20L, "3", true, () -> {
            var page = queryService.audit(new ProcessAuditQuery("SMT", null, null, null, null, null,
                null, null, 1, 20));
            assertEquals(0L, page.getTotal());
        });
    }

    private ProcessExecutionOverviewQuery query(int pendingPage, int pendingSize) {
        return new ProcessExecutionOverviewQuery("SMT", null, null, null, null, 1, 10,
            pendingPage, pendingSize, 1, 10, 1, 10);
    }

    private ExecutionOverviewVO.ExecutionPlanVO first(java.util.Collection<ExecutionOverviewVO.ExecutionPlanVO> rows) {
        return new ArrayList<>(rows).getFirst();
    }

    private void withScope(Long deptId, String dataScope, boolean withRole, Runnable action) {
        SaTokenContextMockUtil.setMockContext(() -> {
            cn.dev33.satoken.SaManager.setSaTokenDao(new cn.dev33.satoken.dao.SaTokenDaoDefaultImpl());
            cn.dev33.satoken.SaManager.setStpInterface(new cn.dev33.satoken.stp.StpInterface() {
                public List<String> getPermissionList(Object loginId, String loginType) { return List.of(); }
                public List<String> getRoleList(Object loginId, String loginType) { return List.of(); }
            });
            cn.dev33.satoken.stp.StpUtil.login(101L);
            LoginUser user = new LoginUser();
            user.setUserId(1L);
            user.setDeptId(deptId);
            user.setUsername("tester");
            user.setUserType("PC");
            if (withRole) {
                RoleDTO role = new RoleDTO();
                role.setRoleId(9L);
                role.setRoleKey("process");
                role.setRoleName("计划执行");
                role.setDataScope(dataScope);
                user.setRoles(List.of(role));
                user.setDataScopeRoleMap(Map.of("process:execution:list", List.of(9L),
                    "process:request:list", List.of(9L), "process:request:query", List.of(9L)));
            } else {
                user.setRoles(List.of());
                user.setDataScopeRoleMap(Map.of());
            }
            DataPermissionHelper.setVariable("user", user);
            DataPermissionHelper.setAccess(new DataPermissionAccess(
                Set.of("process:execution:list", "process:request:list", "process:request:query"), Set.of()));
            try {
                action.run();
            } finally {
                cn.dev33.satoken.stp.StpUtil.logout();
                DataPermissionHelper.setVariable("user", null);
                DataPermissionHelper.setAccess(null);
            }
        });
    }

    private void definition(long id, String code, String planType, String name, String role, String status,
                            String mode, Integer limit, Integer window, String completionPolicy) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO iws_process_definition"
                + "(id,plan_code,plan_version,plan_type,business_type,business_schedule_code,plan_name,plan_role,"
                + "status,revision,self_system_code,mode,active_plan_limit,fairness_window_size,completion_policy,"
                + "exception_state_code,lease_ms,retry_max,retry_backoff_ms,del_flag,create_dept,create_by,"
                + "create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, code, 1, planType, "OUT", "SMT", name, role, status, 1L, "IWS", mode, limit, window,
            completionPolicy, "S_EXC", 30000L, 0, 1000L, "0", 10L, 1L,
            Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void state(long id, long definitionId, String code, String name, String kind, boolean terminal,
                       int order) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO iws_process_state"
                + "(id,definition_version_id,state_code,display_name,state_kind,validator_key,validator_version,"
                + "condition_description,terminal,sort_order,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, definitionId, code, name, kind, "validator-" + code, "1",
            name + "条件", terminal, order, 10L, 1L, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void eventDefinition(long id, long definitionId, String code, String name, String role,
                                 String direction, String contract, String contractVersion, String system,
                                 String source, String target, int priority) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO iws_process_event_definition"
                + "(id,definition_version_id,event_code,display_name,event_role,event_direction,event_type_key,"
                + "event_type_version,call_contract_key,call_contract_version,operation_kind,system_code,"
                + "target_system_code,source_state_code,target_state_code,enabled,priority,create_dept,create_by,"
                + "create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, definitionId, code, name, role, direction, "type-" + code, "1", contract,
            contractVersion, "EXTERNAL_POSTING", system, system, source, target, true, priority,
            10L, 1L, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void instance(long id, String role, long definitionId, String planType, Long supervisorId,
                          String supervisorKey, String businessTaskKey, String businessId, String taskKey,
                          String status, int priority, boolean hold, Long dept, Timestamp started,
                          Timestamp ended, Timestamp created) {
        jdbcTemplate.update("INSERT INTO iws_process_instance"
                + "(id,plan_role,definition_version_id,schedule_id,plan_type,business_schedule_code,"
                + "supervisor_instance_id,supervisor_key,business_task_key,business_type,business_id,task_key,"
                + "schedule_scope,mode_snapshot,hold_start,plan_status,instance_priority,input_hash,round_no,"
                + "stop_requested,cancel_requested,started_at,ended_at,instance_version,create_dept,create_by,"
                + "create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, role, definitionId, 100L, planType, "SMT", supervisorId, supervisorKey, businessTaskKey,
            "OUT", businessId, taskKey, "DEFAULT", "QUEUE", hold, status, priority, "hash-" + id, 0L,
            false, false, started, ended, 1L, dept, 1L, created, created);
    }

    private void candidateEvent(long id, long instanceId, Long tokenId, String eventCode, int priority,
                                Timestamp ts) {
        jdbcTemplate.update("INSERT INTO iws_process_event"
                + "(id,instance_id,token_id,definition_version_id,event_definition_id,event_code,event_key,"
                + "round_no,branch_identity,event_role,event_direction,system_code,call_contract_key,"
                + "call_contract_version,effect_key,source_state_code,target_state_code,event_status,sent,"
                + "payload_ready,input_hash,idempotency_key,execution_purpose,priority,retry_count,lease_epoch,"
                + "event_version,create_dept,create_by,create_time,update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, instanceId, tokenId, 300L, 500L, eventCode, eventCode, 0L, "DEFAULT", "BUSINESS",
            "OUTBOUND", "MID", "C1", "1", "EFF-" + eventCode, "S0", "S1", "PENDING", false,
            true, "h-" + id, "idem-" + id, "NORMAL", priority, 0, 0L, 1L, 10L, 1L, ts, ts);
    }

    private void clear() {
        for (String table : List.of("iws_process_action_audit", "iws_process_request_detail",
            "iws_process_trigger_receipt", "iws_process_attempt", "iws_process_event",
            "iws_process_token", "iws_process_system_context", "iws_process_schedule_control",
            "iws_process_instance", "iws_process_edge", "iws_process_event_definition",
            "iws_process_state", "iws_process_definition", "iws_business_schedule_plan_type",
            "iws_business_schedule")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
