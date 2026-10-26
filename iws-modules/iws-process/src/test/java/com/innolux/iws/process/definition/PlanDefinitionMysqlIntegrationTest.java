/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划定义 D01/D02/D10/D43-D46 真实隔离验收；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.definition;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.api.DefinitionEdgeBo;
import com.innolux.iws.process.api.DefinitionEventBo;
import com.innolux.iws.process.api.DefinitionEventPatchBo;
import com.innolux.iws.process.api.DefinitionStateBo;
import com.innolux.iws.process.api.DefinitionValidationVo;
import com.innolux.iws.process.api.ExternalSystemPostingOptionsVO;
import com.innolux.iws.process.api.ExternalSystemRowVO;
import com.innolux.iws.process.api.PostingImpactVO;
import com.innolux.iws.process.api.PostingModeVectorBo;
import com.innolux.iws.process.api.PostingPolicySaveResultVo;
import com.innolux.iws.process.api.SystemPolicyBo;
import com.innolux.iws.process.service.PlanDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划定义层隔离验收。
 */
@SpringBootTest(classes = DefinitionFixtureApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.main.banner-mode=off", "logging.level.root=WARN", "iws.process.enabled=true"})
@EnabledIfSystemProperty(named = "iws.process.test.mysql-datadir", matches = ".+")
@Tag("dev")
class PlanDefinitionMysqlIntegrationTest {

    private static final String PLAN_CODE = "D_BUILD_ORDER";

    private static final String ENV = "TEST";

    @Autowired
    private PlanDefinitionService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() throws Exception {
        DefinitionMysqlFixture.resetSchema();
    }

    @Test
    void d01ConcurrentSubTableSaveUsesRowLockAndRevisionCas() throws Exception {
        long id = createBusinessDraft(PLAN_CODE, "d01-create");
        DefinitionDetailVo detail = service.get(id);
        long revision = Long.parseLong(detail.summary().revision());
        DefinitionStateBo state = detail.states().get(1);
        DefinitionEventBo event = detail.events().get(0);
        List<DefinitionEdgeBo> eventEdges = edgesOf(detail, event.eventCode());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> stateSave = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                service.saveState(id, new DefinitionStateBo(state.stateCode(), "并发状态名", state.stateKind(),
                    state.validatorKey(), state.validatorVersion(), state.conditionDescription(), state.terminal(),
                    state.sortOrder()), revision, "d01-state", "并发保存状态");
                return true;
            } catch (ServiceException ex) {
                return false;
            }
        });
        Future<Boolean> eventSave = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                service.saveEvent(id, new DefinitionEventPatchBo(withDisplayName(event, "并发事件名"), eventEdges,
                    revision, "0"), "d01-event", "并发保存事件");
                return true;
            } catch (ServiceException ex) {
                return false;
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        boolean stateWon = stateSave.get(30, TimeUnit.SECONDS);
        boolean eventWon = eventSave.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();
        assertTrue(stateWon ^ eventWon, "必须一方成功、一方版本冲突");

        DefinitionDetailVo after = service.get(id);
        assertEquals(revision + 1, Long.parseLong(after.summary().revision()));
        String stateName = after.states().stream().filter(item -> item.stateCode().equals(state.stateCode()))
            .findFirst().orElseThrow().displayName();
        String eventName = after.events().stream().filter(item -> item.eventCode().equals(event.eventCode()))
            .findFirst().orElseThrow().displayName();
        if (stateWon) {
            assertEquals("并发状态名", stateName);
            assertEquals(event.displayName(), eventName);
        } else {
            assertEquals("并发事件名", eventName);
            assertEquals(state.displayName(), stateName);
        }
        assertEquals(8, after.edges().size());
    }

    @Test
    void d02ConcurrentCopyAndPublishFailureKeepSingleton() throws Exception {
        long id = createBusinessDraft(PLAN_CODE, "d02-create");
        long revision = Long.parseLong(service.get(id).summary().revision());
        service.publish(id, revision, "d02-publish", "首次发布");
        DefinitionDetailVo active = service.get(id);
        assertEquals("ACTIVE", active.summary().status());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Long> first = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                return Long.parseLong(service.copy(id, "d02-copy-a", "复制A").summary().versionId());
            } catch (ServiceException ex) {
                return -1L;
            }
        });
        Future<Long> second = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                return Long.parseLong(service.copy(id, "d02-copy-b", "复制B").summary().versionId());
            } catch (ServiceException ex) {
                return -1L;
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        long a = first.get(30, TimeUnit.SECONDS);
        long b = second.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();
        assertTrue((a > 0) ^ (b > 0), "并发复制只能成功一个");
        long draftId = a > 0 ? a : b;
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_definition WHERE plan_code = ?"
            + " AND latest_key = ?", PLAN_CODE, PLAN_CODE));

        DefinitionDetailVo draft = service.get(draftId);
        service.delete(draftId, Long.parseLong(draft.summary().revision()), "d02-delete", "删除草稿");
        DefinitionDetailVo copied = service.copy(id, "d02-copy-again", "删除后再复制");
        assertEquals(3, copied.summary().planVersion(), "已逻辑删除的最大业务版本不能被复用");

        long draftRevision = Long.parseLong(copied.summary().revision());
        long copiedId = Long.parseLong(copied.summary().versionId());
        jdbcTemplate.update("UPDATE iws_endpoint_address SET enabled = 0 WHERE endpoint_ref = 'mes'");
        ServiceException failure = assertThrows(ServiceException.class,
            () -> service.publish(copiedId, draftRevision, "d02-publish-bad", "地址缺失发布"));
        assertTrue(failure.getMessage().contains("发送地址不可用"));
        assertEquals("ACTIVE", service.get(id).summary().status());
        assertEquals("DRAFT", service.get(copiedId).summary().status());
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_definition WHERE plan_code = ?"
            + " AND status = 'ACTIVE'", PLAN_CODE));
    }

    @Test
    void d10ReplayReturnsOriginalReceiptAndRejectsDifferentPayload() {
        long id = createBusinessDraft(PLAN_CODE, "d10-create");
        DefinitionDetailVo created = service.createDraft(businessDraft(PLAN_CODE, "0"), "d10-create", "重投创建");
        assertEquals(id, Long.parseLong(created.summary().versionId()));
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_definition WHERE plan_code = ?", PLAN_CODE));

        ServiceException conflict = assertThrows(ServiceException.class,
            () -> service.createDraft(new DefinitionDraftBo(PLAN_CODE, "另一个名称", null, "DELIVERY", "OUTBOUND",
                null, "IWS", "BUSINESS", null, null, null, null, "ALL_SELECTED", "S_EXC", 10, 1000L, 10, 1000L,
                30000L, 3, 1000L, 1000L, null, 5000L, 0, "定义层验收", null, "0", states(), events(),
                edges(), policies()), "d10-create", "异参重投"));
        assertTrue(conflict.getMessage().contains("参数不同"));

        DefinitionDetailVo current = service.get(id);
        long revision = Long.parseLong(current.summary().revision());
        DefinitionStateBo state = current.states().get(1);
        DefinitionDetailVo saved = service.saveState(id, new DefinitionStateBo(state.stateCode(), "第一次",
            state.stateKind(), state.validatorKey(), state.validatorVersion(), state.conditionDescription(),
            state.terminal(), state.sortOrder()), revision, "d10-state", "第一次状态保存");
        long savedRevision = Long.parseLong(saved.summary().revision());
        DefinitionDetailVo replayed = service.saveState(id, new DefinitionStateBo(state.stateCode(), "第一次",
            state.stateKind(), state.validatorKey(), state.validatorVersion(), state.conditionDescription(),
            state.terminal(), state.sortOrder()), revision, "d10-state", "重投");
        assertEquals(savedRevision, Long.parseLong(replayed.summary().revision()));

        DefinitionDetailVo later = service.saveState(id, new DefinitionStateBo(state.stateCode(), "第二次",
            state.stateKind(), state.validatorKey(), state.validatorVersion(), state.conditionDescription(),
            state.terminal(), state.sortOrder()), savedRevision, "d10-state-2", "第二次状态保存");
        long laterRevision = Long.parseLong(later.summary().revision());
        DefinitionDetailVo replayAfterMove = service.saveState(id, new DefinitionStateBo(state.stateCode(), "第一次",
            state.stateKind(), state.validatorKey(), state.validatorVersion(), state.conditionDescription(),
            state.terminal(), state.sortOrder()), revision, "d10-state", "响应丢失后重投");
        assertEquals(savedRevision, Long.parseLong(replayAfterMove.summary().revision()));
        assertEquals(laterRevision, Long.parseLong(service.get(id).summary().revision()));
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_action_audit WHERE command_key = 'd10-state'"));
    }

    @Test
    void emptyDraftCanBeBuiltIncrementallyThenValidatedForPublish() {
        long id = Long.parseLong(service.createDraft(emptyDraft("D_EMPTY", "空稿"), "empty-create", "创建空稿")
            .summary().versionId());
        service.saveState(id, state("S_START", "开始", "START", false, 0), 1, "empty-s1", "加入起点");
        service.saveState(id, state("S_1", "步骤", "NORMAL", false, 1), 2, "empty-s2", "加入步骤");
        DefinitionEventBo event = inbound("E_IN", "入站触发", "inbound-type", DefinitionCapabilityFixture.CONTRACT_IN,
            "TRIGGER_EVENT", "INBOUND_TRIGGER", "MID_WMS", "IWS", null, "S_START", "S_1",
            "REQUIRE_REAL_INBOUND", null);
        service.saveEvent(id, new DefinitionEventPatchBo(event, List.of(), 3L, "0"), "empty-e1", "加入事件");
        service.saveSystemPolicies(id, new PostingModeVectorBo(4L, "0", ENV, "empty-p1", "加入模式",
            List.of(new SystemPolicyBo("MID_WMS", "AUTOMATIC", null, null, null))));
        assertFalse(service.validate(id).valid(), "发布检查必须继续要求终点");
        service.saveState(id, state("S_END", "结束", "END", true, 2), 5, "empty-s3", "加入终点");
        DefinitionEventBo completed = inbound("E_IN", "入站触发", "inbound-type",
            DefinitionCapabilityFixture.CONTRACT_IN, "TRIGGER_EVENT", "INBOUND_TRIGGER", "MID_WMS", "IWS", null,
            "S_START", "S_END", "REQUIRE_REAL_INBOUND", null);
        service.saveEvent(id, new DefinitionEventPatchBo(completed, List.of(), 6L, "0"), "empty-e2", "收敛终点");
        DefinitionValidationVo validation = service.validate(id);
        assertTrue(validation.valid(), String.join(";", validation.errors()));
        assertEquals(7, Long.parseLong(service.get(id).summary().revision()));
    }

    @Test
    void d43ExternalSystemsReadsWholeGraphAndSeparatesPolicyOwnership() {
        long id = createBusinessDraft(PLAN_CODE, "d43-create");
        DefinitionDetailVo detail = service.get(id);
        assertEquals(10, detail.roundBatchLimit());
        assertEquals(10, detail.workerBatchLimit());
        assertEquals(30000L, detail.leaseMs());
        assertEquals(3, detail.retryMax());
        assertEquals(1000L, detail.retryBackoffMs());
        assertEquals(1000L, detail.scheduleIntervalMs());
        assertEquals(5000L, detail.jobTimeoutMs());
        assertEquals(0, detail.jobMaxRetryTimes());
        assertEquals("定义层验收", detail.description());
        com.innolux.iws.process.api.EditCapabilitiesVo capabilities = service.editCapabilities(id);
        assertTrue(capabilities.fields().stream().anyMatch(item -> item.field().equals("planCode")
            && !item.editable() && item.editPolicy().equals("CODE_FIXED")));
        assertTrue(capabilities.events().stream().anyMatch(item -> item.eventCode().equals("E_OUT")));
        ExternalSystemPostingOptionsVO options = service.externalSystems(id);
        assertEquals("0", options.catalogRevision());
        assertEquals(ENV, options.environmentCode());
        assertEquals(List.of("IWS", "MES", "MID_WMS"), options.policySystems().stream()
            .map(ExternalSystemRowVO::systemCode).toList());
        assertEquals(4, options.displaySystems().size());
        ExternalSystemRowVO sap = row(options, "SAP");
        assertFalse(sap.policyOwner());
        assertNull(sap.currentMode());
        assertFalse(sap.modeEditable());
        assertTrue(sap.unaffectedEvents().contains("E_IN"));
        assertTrue(sap.endpointSummaries().contains("sap：启用"));
        assertTrue(sap.reasons().contains("仅入站来源，无政策归属"));
        ExternalSystemRowVO mid = row(options, "MID_WMS");
        assertEquals(List.of("E_OUT"), mid.bypassableEvents());
        assertTrue(mid.unaffectedEvents().contains("E_IN"));
        assertTrue(mid.unaffectedEvents().contains("E_RESULT"));
        assertTrue(mid.unaffectedEvents().contains("E_QUERY"));
        assertTrue(mid.endpointSummaries().get(0).contains("mid：启用"));
        ExternalSystemRowVO mes = row(options, "MES");
        assertEquals(List.of("E_DISABLED"), mes.disabledEvents());
        assertEquals(List.of("E_MES"), mes.bypassableEvents());
        assertTrue(mes.allowedModes().contains("BYPASS"));
        ExternalSystemRowVO iws = row(options, "IWS");
        assertTrue(iws.selfSystem());
        assertTrue(iws.bypassableEvents().isEmpty());
        assertFalse(iws.allowedModes().contains("BYPASS"));
        for (ExternalSystemRowVO row : options.displaySystems()) {
            assertTrue(row.endpointSummaries().stream().noneMatch(item -> item.toLowerCase().contains("password")));
            assertTrue(row.endpointSummaries().stream().noneMatch(item -> item.toLowerCase().contains("credential")));
        }
    }

    @Test
    void d44D45PolicyVectorIsCompleteAtomicAndImpactPreviewed() {
        long id = createBusinessDraft(PLAN_CODE, "d45-create");
        long revision = Long.parseLong(service.get(id).summary().revision());
        String catalogRevision = service.externalSystems(id).catalogRevision();
        List<SystemPolicyBo> baseline = service.systemPolicies(id);

        ServiceException missing = assertThrows(ServiceException.class,
            () -> service.saveSystemPolicies(id, vector(revision, catalogRevision, List.of(
                new SystemPolicyBo("MID_WMS", "BYPASS", null, null, null),
                new SystemPolicyBo("MES", "AUTOMATIC", null, null, null)))));
        assertTrue(missing.getMessage().contains("缺少系统"));
        ServiceException extra = assertThrows(ServiceException.class,
            () -> service.saveSystemPolicies(id, vector(revision, catalogRevision, List.of(
                new SystemPolicyBo("MID_WMS", "BYPASS", null, null, null),
                new SystemPolicyBo("MES", "AUTOMATIC", null, null, null),
                new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null),
                new SystemPolicyBo("SAP", "AUTOMATIC", null, null, null)))));
        assertTrue(extra.getMessage().contains("非政策系统"));
        ServiceException stale = assertThrows(ServiceException.class,
            () -> service.saveSystemPolicies(id, vector(revision, "999", baseline)));
        assertTrue(stale.getMessage().contains("目录已变化"));
        ServiceException wrongEnv = assertThrows(ServiceException.class,
            () -> service.saveSystemPolicies(id, new PostingModeVectorBo(revision, catalogRevision, "PROD",
                "d45-env", "错误环境", baseline)));
        assertTrue(wrongEnv.getMessage().contains("环境与当前运行环境不一致"));
        assertEquals(baseline, service.systemPolicies(id));
        assertEquals(0, count("SELECT COUNT(*) FROM iws_process_action_audit"
            + " WHERE command_key IN ('d45-env')"));

        List<SystemPolicyBo> bypass = List.of(new SystemPolicyBo("MID_WMS", "BYPASS", null, null, null),
            new SystemPolicyBo("MES", "AUTOMATIC", null, null, null),
            new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null));
        PostingImpactVO preview = service.previewSystemPolicies(id, vector(revision, catalogRevision, bypass));
        assertTrue(preview.valid(), String.join(";", preview.errors()));
        assertEquals(List.of("MID_WMS"), preview.bypassSystems());
        assertTrue(preview.affectedEvents().contains("E_OUT"));
        assertFalse(preview.affectedEvents().contains("E_IN"));
        PostingPolicySaveResultVo saved = service.saveSystemPolicies(id,
            vector(revision, catalogRevision, bypass));
        assertTrue(saved.valid());
        assertEquals(String.valueOf(revision + 1), saved.definitionRevision());
        assertEquals("BYPASS", service.systemPolicies(id).stream()
            .filter(item -> item.systemCode().equals("MID_WMS")).findFirst().orElseThrow().postingMode());

        PostingPolicySaveResultVo replay = service.saveSystemPolicies(id,
            vector(revision, catalogRevision, bypass));
        assertEquals(String.valueOf(revision + 1), replay.definitionRevision());
        assertEquals(revision + 1, Long.parseLong(service.get(id).summary().revision()));

        ServiceException duplicate = assertThrows(ServiceException.class,
            () -> service.saveSystemPolicies(id, vector(revision + 1, catalogRevision, List.of(
                new SystemPolicyBo("MID_WMS", "AUTOMATIC", null, null, null),
                new SystemPolicyBo("MID_WMS", "BYPASS", null, null, null),
                new SystemPolicyBo("MES", "AUTOMATIC", null, null, null),
                new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null)))));
        assertTrue(duplicate.getMessage().contains("系统模式重复"));
    }

    @Test
    void d44CancelBypassRequiresExplicitModeAndSupervisorCannotSave() {
        long id = createBusinessDraft(PLAN_CODE, "d44-create");
        long revision = Long.parseLong(service.get(id).summary().revision());
        String catalogRevision = service.externalSystems(id).catalogRevision();
        List<SystemPolicyBo> bypass = List.of(new SystemPolicyBo("MID_WMS", "BYPASS", null, null, null),
            new SystemPolicyBo("MES", "AUTOMATIC", null, null, null),
            new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null));
        service.saveSystemPolicies(id, vector(revision, catalogRevision, bypass));
        long next = revision + 1;
        List<SystemPolicyBo> restored = List.of(new SystemPolicyBo("MID_WMS", "MANUAL_CONFIRM", null, null, null),
            new SystemPolicyBo("MES", "AUTOMATIC", null, null, null),
            new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null));
        PostingPolicySaveResultVo result = service.saveSystemPolicies(id,
            vector(next, catalogRevision, restored));
        assertTrue(result.bypassSystems().isEmpty());
        assertEquals("MANUAL_CONFIRM", service.systemPolicies(id).stream()
            .filter(item -> item.systemCode().equals("MID_WMS")).findFirst().orElseThrow().postingMode());

        String supervisorCode = "D_SUPERVISOR";
        DefinitionDraftBo supervisor = new DefinitionDraftBo(supervisorCode, "监督计划", null, "SUPERVISOR", null,
            null, "IWS", "SUPERVISOR", "QUEUE", 2, 5, null, "ALL_SELECTED", null, 10, 1000L, 10, 1000L,
            30000L, 3, 1000L, 1000L, null, 5000L, 0, "监督", null, "0", statesForSupervisor(),
            eventsForSupervisor(), edgesForSupervisor(), List.of(new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null)));
        long supervisorId = Long.parseLong(service.createDraft(supervisor, "d44-supervisor", "创建监督")
            .summary().versionId());
        DefinitionValidationVo validation = service.validate(supervisorId);
        assertTrue(validation.valid(), String.join(";", validation.errors()));
        ServiceException rejected = assertThrows(ServiceException.class,
            () -> service.saveSystemPolicies(supervisorId, vector(1, catalogRevision,
                List.of(new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null)))));
        assertTrue(rejected.getMessage().contains("仅 BUSINESS 最新草稿"));
    }

    @Test
    void d45MissingResultSkipRejectsWholeBatch() {
        long id = createBusinessDraft(PLAN_CODE, "d45-skip-create");
        DefinitionDetailVo detail = service.get(id);
        long revision = Long.parseLong(detail.summary().revision());
        List<DefinitionEventBo> events = new ArrayList<>(detail.events());
        DefinitionEventBo result = events.stream().filter(item -> item.eventCode().equals("E_RESULT"))
            .findFirst().orElseThrow();
        events.set(events.indexOf(result), new DefinitionEventBo(result.eventCode(), result.displayName(),
            result.eventRole(), result.eventDirection(), result.eventTypeKey(), result.eventTypeVersion(),
            result.callContractKey(), result.callContractVersion(), result.operationKind(), result.localActionKey(),
            result.localActionVersion(), result.effectKey(), result.localEffectKey(), result.systemCode(),
            result.targetSystemCode(), result.endpointRef(), result.purpose(), result.completionContract(),
            result.sourceStateCode(), result.targetStateCode(), result.enabled(), "FORBID", null,
            result.recoveryForEventCode(), result.priority(), result.editPolicy(), result.graphEditPolicy(),
            result.lockedFields(), result.remark()));
        service.saveDraft(id, businessDraft(PLAN_CODE, "0", detail.states(), events, detail.edges(),
            detail.systemPolicies()), revision, "d45-skip-save", "移除结果跳过合同");
        List<SystemPolicyBo> baseline = service.systemPolicies(id);
        long next = Long.parseLong(service.get(id).summary().revision());
        String catalogRevision = service.externalSystems(id).catalogRevision();
        ServiceException rejected = assertThrows(ServiceException.class,
            () -> service.saveSystemPolicies(id, vector(next, catalogRevision, List.of(
                new SystemPolicyBo("MID_WMS", "BYPASS", null, null, null),
                new SystemPolicyBo("MES", "AUTOMATIC", null, null, null),
                new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null)))));
        assertTrue(rejected.getMessage().contains("缺少显式结果事件跳过替代"));
        assertEquals(baseline, service.systemPolicies(id));
        assertEquals(next, Long.parseLong(service.get(id).summary().revision()));
    }

    @Test
    void d46BypassNeedsNoAddressButRequiredEventDoes() {
        long id = createBusinessDraft(PLAN_CODE, "d46-create");
        long revision = Long.parseLong(service.get(id).summary().revision());
        String catalogRevision = service.externalSystems(id).catalogRevision();
        jdbcTemplate.update("UPDATE iws_endpoint_address SET enabled = 0 WHERE endpoint_ref = 'mes'");
        jdbcTemplate.update("UPDATE iws_endpoint_catalog_control SET revision = revision + 1");
        String newCatalogRevision = service.externalSystems(id).catalogRevision();
        ServiceException blocked = assertThrows(ServiceException.class,
            () -> service.publish(id, revision, "d46-publish-blocked", "缺地址发布"));
        assertTrue(blocked.getMessage().contains("发送地址不可用"));
        assertNull(service.get(id).summary().publishedAt());

        List<SystemPolicyBo> bypass = List.of(new SystemPolicyBo("MID_WMS", "AUTOMATIC", null, null, null),
            new SystemPolicyBo("MES", "BYPASS", null, null, null),
            new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null));
        service.saveSystemPolicies(id, vector(revision, newCatalogRevision, bypass));
        long next = revision + 1;
        DefinitionValidationVo published = service.publish(id, next, "d46-publish-bypass", "屏蔽后发布");
        assertTrue(published.valid());
        assertEquals("ACTIVE", service.get(id).summary().status());
        assertNotNull(service.get(id).summary().publishedAt());
        assertTrue(service.get(id).summary().publishedAt() != null);
        jdbcTemplate.update("UPDATE iws_endpoint_address SET host = '127.0.0.2' WHERE endpoint_ref = 'mid'");
        jdbcTemplate.update("UPDATE iws_endpoint_catalog_control SET revision = revision + 1");
        ExternalSystemPostingOptionsVO after = service.externalSystems(id);
        assertEquals("2", after.catalogRevision());
        assertEquals("BYPASS", after.policySystems().stream()
            .filter(item -> item.systemCode().equals("MES")).findFirst().orElseThrow().currentMode());
        assertEquals(1, count("SELECT COUNT(*) FROM iws_process_definition WHERE plan_code = ?"
            + " AND status = 'ACTIVE'", PLAN_CODE));
    }

    private long createBusinessDraft(String planCode, String commandKey) {
        long id = Long.parseLong(service.createDraft(businessDraft(planCode, "0"), commandKey, "创建草稿")
            .summary().versionId());
        DefinitionValidationVo validation = service.validate(id);
        assertTrue(validation.valid(), String.join(";", validation.errors()));
        return id;
    }

    private DefinitionDraftBo businessDraft(String planCode, String catalogRevision) {
        return businessDraft(planCode, catalogRevision, states(), events(), edges(), policies());
    }

    private static DefinitionDraftBo emptyDraft(String planCode, String name) {
        List<DefinitionStateBo> states = null;
        List<DefinitionEventBo> events = null;
        List<DefinitionEdgeBo> edges = null;
        List<SystemPolicyBo> policies = null;
        Long expectedVersion = null;
        String catalogRevision = "0";
        return new DefinitionDraftBo(planCode, name, null, "DELIVERY", "OUTBOUND", null, "IWS", "BUSINESS",
            null, null, null, null, "ALL_SELECTED", null, null, null, null, null, null, null, null, null, null,
            null, null, null, expectedVersion, catalogRevision, states, events, edges, policies);
    }

    private DefinitionDraftBo businessDraft(String planCode, String catalogRevision, List<DefinitionStateBo> states,
                                            List<DefinitionEventBo> events, List<DefinitionEdgeBo> edges,
                                            List<SystemPolicyBo> policies) {
        return new DefinitionDraftBo(planCode, "计划定义验收", null, "DELIVERY", "OUTBOUND", null, "IWS",
            "BUSINESS", null, null, null, null, "ALL_SELECTED", "S_EXC", 10, 1000L, 10, 1000L, 30000L, 3,
            1000L, 1000L, null, 5000L, 0, "定义层验收", null, catalogRevision, states, events, edges, policies);
    }

    private List<DefinitionStateBo> states() {
        List<DefinitionStateBo> states = new ArrayList<>();
        states.add(state("S_START", "开始", "START", false, 0));
        for (int i = 0; i <= 5; i++) {
            states.add(state("S_" + i, "步骤" + i, "NORMAL", false, i + 1));
        }
        states.add(state("S_END", "结束", "END", true, 7));
        states.add(state("S_EXC", "异常", "EXCEPTION", false, 8));
        return states;
    }

    private List<DefinitionEventBo> events() {
        return List.of(
            inbound("E_IN", "入站触发", "inbound-type", DefinitionCapabilityFixture.CONTRACT_IN,
                "TRIGGER_EVENT", "INBOUND_TRIGGER", "MID_WMS", "IWS", null, "S_START", "S_0", "REQUIRE_REAL_INBOUND",
                null),
            outbound("E_DISABLED", "禁用控制", "outbound-type", DefinitionCapabilityFixture.CONTRACT_CONTROL,
                "CONTROL", "CONTROL", "MES", "mes", "BUSINESS_COMPLETION", "S_0", "S_1", false,
                "ALLOW", "skip-control"),
            outbound("E_OUT", "外部抛账", "outbound-type", DefinitionCapabilityFixture.CONTRACT_POST,
                "EXECUTE_ACTION", "EXTERNAL_POSTING", "MID_WMS", "mid", "ACCEPTANCE", "S_1", "S_2", true,
                null, null),
            inbound("E_RESULT", "结果回调", "inbound-type", DefinitionCapabilityFixture.CONTRACT_RESULT,
                "RESULT_CALLBACK", "RESULT_CALLBACK", "MID_WMS", "IWS", null, "S_2", "S_3", "ALLOW",
                "skip-result"),
            withRecovery(outbound("E_QUERY", "恢复查询", "outbound-type", "out-query", "QUERY", "QUERY",
                "MID_WMS", "mid", "BUSINESS_COMPLETION", "S_2", "S_3", true, null, null), "E_OUT"),
            outbound("E_MES", "MES抛账", "outbound-type", DefinitionCapabilityFixture.CONTRACT_POST,
                "EXECUTE_ACTION", "EXTERNAL_POSTING", "MES", "mes", "ACCEPTANCE", "S_3", "S_4", true,
                null, null),
            inbound("E_MES_RESULT", "MES结果", "inbound-type", DefinitionCapabilityFixture.CONTRACT_RESULT,
                "RESULT_CALLBACK", "RESULT_CALLBACK", "MES", "IWS", null, "S_4", "S_5", "ALLOW",
                "skip-mes-result"),
            internal("E_INT", "本地动作", "internal-type", "S_5", "S_END"));
    }

    private List<DefinitionEdgeBo> edges() {
        return List.of(edge("E_IN", "E_DISABLED", "DEFAULT", "SEQUENTIAL"),
            edge("E_DISABLED", "E_OUT", "DEFAULT", "SEQUENTIAL"),
            edge("E_OUT", "E_RESULT", "result", "XOR"),
            new DefinitionEdgeBo("E_OUT", "E_QUERY", "recovery", "XOR", "fixture-cond", "1", false, null, 1),
            edge("E_RESULT", "E_MES", "DEFAULT", "SEQUENTIAL"),
            edge("E_QUERY", "E_MES", "DEFAULT", "SEQUENTIAL"),
            edge("E_MES", "E_MES_RESULT", "DEFAULT", "SEQUENTIAL"),
            edge("E_MES_RESULT", "E_INT", "DEFAULT", "SEQUENTIAL"));
    }

    private List<SystemPolicyBo> policies() {
        return List.of(new SystemPolicyBo("IWS", "AUTOMATIC", null, null, null),
            new SystemPolicyBo("MES", "AUTOMATIC", null, null, null),
            new SystemPolicyBo("MID_WMS", "AUTOMATIC", null, null, null));
    }

    private List<DefinitionStateBo> statesForSupervisor() {
        return List.of(state("S_START", "开始", "START", false, 0),
            state("S_NEXT", "调度", "NORMAL", false, 1),
            state("S_END", "结束", "END", true, 2));
    }

    private List<DefinitionEventBo> eventsForSupervisor() {
        DefinitionEventBo decision = new DefinitionEventBo("E_DECISION", "决策", "DECISION", "INTERNAL",
            "internal-type", "1", null, null, null, DefinitionCapabilityFixture.LOCAL_ACTION, "1", null,
            "local-decision", "IWS", "IWS", null, null, null, "S_START", "S_NEXT", true, null, null, null, 0,
            "EDITABLE", "EDITABLE_NEW_VERSION", null, null);
        DefinitionEventBo local = new DefinitionEventBo("E_LOCAL", "本地调度", "LOCAL", "INTERNAL",
            "internal-type", "1", null, null, null, DefinitionCapabilityFixture.LOCAL_ACTION, "1", null,
            "local-batch", "IWS", "IWS", null, null, null, "S_NEXT", "S_END", true, null, null, null, 0,
            "EDITABLE", "EDITABLE_NEW_VERSION", null, null);
        return List.of(decision, local);
    }

    private List<DefinitionEdgeBo> edgesForSupervisor() {
        return List.of(edge("E_DECISION", "E_LOCAL", "DEFAULT", "SEQUENTIAL"));
    }

    private static DefinitionStateBo state(String code, String name, String kind, boolean terminal, int order) {
        return new DefinitionStateBo(code, name, kind, DefinitionCapabilityFixture.VALIDATOR, "1", null, terminal,
            order);
    }

    private static DefinitionEventBo inbound(String code, String name, String type, String contract, String purpose,
                                             String operationKind, String system, String target, String endpointRef,
                                             String source, String dest, String skipPolicy, String skipContract) {
        return new DefinitionEventBo(code, name, "BUSINESS", "INBOUND", type, "1", contract, "1", operationKind,
            null, null, "effect-" + code, null, system, target, endpointRef, purpose, "BUSINESS_COMPLETION",
            source, dest, true, skipPolicy, skipContract, null, 0, "EDITABLE", "EDITABLE_NEW_VERSION", null, null);
    }

    private static DefinitionEventBo outbound(String code, String name, String type, String contract, String purpose,
                                              String operationKind, String system, String endpointRef,
                                              String completion, String source, String dest, boolean enabled,
                                              String skipPolicy, String skipContract) {
        return new DefinitionEventBo(code, name, "BUSINESS", "OUTBOUND", type, "1", contract, "1", operationKind,
            null, null, "effect-" + code, null, system, system, endpointRef, purpose, completion, source, dest,
            enabled, skipPolicy, skipContract, null, 0, "EDITABLE", "EDITABLE_NEW_VERSION", null, null);
    }

    private static DefinitionEventBo internal(String code, String name, String type, String source, String dest) {
        return new DefinitionEventBo(code, name, "BUSINESS", "INTERNAL", type, "1", null, null, null,
            DefinitionCapabilityFixture.LOCAL_ACTION, "1", null, "local-" + code, "IWS", "IWS", null, null, null,
            source, dest, true, null, null, null, 0, "EDITABLE", "EDITABLE_NEW_VERSION", null, null);
    }

    private static DefinitionEdgeBo edge(String from, String to, String branchKey, String kind) {
        boolean defaultBranch = "XOR".equals(kind) && "result".equals(branchKey);
        return new DefinitionEdgeBo(from, to, branchKey, kind, null, null, defaultBranch, null, 0);
    }

    private static DefinitionEventBo withDisplayName(DefinitionEventBo event, String name) {
        return new DefinitionEventBo(event.eventCode(), name, event.eventRole(), event.eventDirection(),
            event.eventTypeKey(), event.eventTypeVersion(), event.callContractKey(), event.callContractVersion(),
            event.operationKind(), event.localActionKey(), event.localActionVersion(), event.effectKey(),
            event.localEffectKey(), event.systemCode(), event.targetSystemCode(), event.endpointRef(),
            event.purpose(), event.completionContract(), event.sourceStateCode(), event.targetStateCode(),
            event.enabled(), event.skipPolicy(), event.skipContractKey(), event.recoveryForEventCode(),
            event.priority(), event.editPolicy(), event.graphEditPolicy(), event.lockedFields(), event.remark());
    }

    private static DefinitionEventBo withRecovery(DefinitionEventBo event, String originEventCode) {
        return new DefinitionEventBo(event.eventCode(), event.displayName(), event.eventRole(),
            event.eventDirection(), event.eventTypeKey(), event.eventTypeVersion(), event.callContractKey(),
            event.callContractVersion(), event.operationKind(), event.localActionKey(), event.localActionVersion(),
            event.effectKey(), event.localEffectKey(), event.systemCode(), event.targetSystemCode(),
            event.endpointRef(), event.purpose(), event.completionContract(), event.sourceStateCode(),
            event.targetStateCode(), event.enabled(), event.skipPolicy(), event.skipContractKey(), originEventCode,
            event.priority(), event.editPolicy(), event.graphEditPolicy(), event.lockedFields(), event.remark());
    }

    private static List<DefinitionEdgeBo> edgesOf(DefinitionDetailVo detail, String eventCode) {
        return detail.edges().stream()
            .filter(item -> item.predecessorEventCode().equals(eventCode)
                || item.successorEventCode().equals(eventCode))
            .toList();
    }

    private static PostingModeVectorBo vector(long revision, String catalogRevision, List<SystemPolicyBo> policies) {
        return new PostingModeVectorBo(revision, catalogRevision, ENV, "d45-vector-" + revision, "保存模式", policies);
    }

    private static ExternalSystemRowVO row(ExternalSystemPostingOptionsVO options, String systemCode) {
        return options.displaySystems().stream().filter(item -> item.systemCode().equals(systemCode))
            .findFirst().orElseThrow();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
