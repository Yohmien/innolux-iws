/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P1-P7 组合验收
 * @Version      : V1.0
 * @Description  : 普通公司角色经正式挂载后由无登录 QUEUE worker 完成空间计划；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.satoken.core.service.SaPermissionImpl;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.api.EventBatchResult;
import com.innolux.iws.process.api.SupervisorTickResult;
import com.innolux.iws.process.config.ProcessProperties;
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
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RecoveryEvidenceService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.SupervisorRoundMaterializer;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.runtime.SupervisorTickServiceImpl;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeCommand;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeStartResult;
import com.innolux.iws.wms.space.change.process.WmsSpaceChangePlanDefinitionFactory;
import com.innolux.iws.wms.space.change.process.WmsSpaceChangeProcess;
import com.innolux.iws.wms.space.change.process.WmsSpacePlanContract;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import com.innolux.iws.wms.rule.binding.RuleAttributeSourceRegistry;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingConfiguration;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackageCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleVersionActivateCommand;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import com.innolux.iws.wms.rule.process.WmsRulePlanContract;
import com.innolux.iws.wms.rule.process.WmsRulePlanDefinitionFactory;
import com.innolux.iws.wms.rule.process.WmsRulePublicationProcess;
import com.innolux.iws.wms.rule.process.WmsRulePublicationStartResult;
import com.innolux.iws.wms.rule.service.WmsRuleDraftService;
import com.innolux.iws.wms.rule.service.WmsRulePublishService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 正式空间计划挂载、QUEUE worker、撤权和重启组合验收。 */
@Tag("local")
@TestPropertySource(properties = {
    "iws.process.enabled=true",
    "iws.process.scheduler.enabled=true",
    "snail-job.enabled=true"
})
@SpringJUnitConfig(WmsSpacePlanTestContext.class)
class WmsSpacePlanWorkerIntegrationTest {

    private static final long COMPANY_ID = 10_001L;

    private static final long OTHER_COMPANY_ID = 10_002L;

    private static final long USER_ID = 10_101L;

    private static final long ROLE_ID = 10_201L;

    private static final long WAREHOUSE_ID = 10_301L;

    private static final long WAREHOUSE_VERSION_ID = 10_401L;

    private static final long MATERIAL_ZONE_ID = 10_311L;

    private static final long DEVICE_ZONE_ID = 10_312L;

    private static final long STORAGE_ID = 10_313L;

    private static final long MATERIAL_ZONE_VERSION_ID = 10_411L;

    private static final long DEVICE_ZONE_VERSION_ID = 10_412L;

    private static final long STORAGE_VERSION_ID = 10_413L;

    private static final long CHANGE_MENU_ID = 1761400000000052004L;

    private static final long QUERY_MENU_ID = 1761400000000052001L;

    private static final long RULE_DRAFT_MENU_ID = 1761400000000055002L;

    private static final long RULE_PUBLISH_MENU_ID = 1761400000000055003L;

    private static final long SPACE_SCHEDULE_ID = 1761400000000060002L;

    private static final long RULE_SCHEDULE_ID = 1761400000000060001L;

    private static final long SPACE_CHANGE_TYPE_ID = 1761400000000060102L;

    private static final long SPACE_RETIRE_TYPE_ID = 1761400000000060103L;

    private static final long RULE_PUBLISH_TYPE_ID = 1761400000000060101L;

    private static final String OUTPUT_HASH = "a".repeat(64);

    @Autowired
    private PlanDefinitionService definitionService;

    @Autowired
    private WmsSpaceChangeProcess spaceChangeProcess;

    @Autowired
    private SupervisorRuntimeService supervisorRuntimeService;

    @Autowired
    private ProcessProperties processProperties;

    @Autowired
    private ProcessAuditService auditService;

    @Autowired
    private ProcessObjectAuthorizer objectAuthorizer;

    @Autowired
    private CapabilityRegistry capabilityRegistry;

    @Autowired
    private RuleAttributeSourceRegistry ruleAttributeSources;

    @Autowired
    private WmsRuleBindingService ruleBindingService;

    @Autowired
    private WmsRuleDraftService ruleDraftService;

    @Autowired
    private WmsRulePublishService rulePublishService;

    @Autowired
    private WmsRulePublicationProcess rulePublicationProcess;

    @Autowired
    private EventMutationService eventMutationService;

    @Autowired
    private EventUpdateService eventUpdateService;

    @Autowired
    private RuntimeDao runtimeDao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

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

    @BeforeAll
    static void installSaToken() {
        TestAuth.install();
    }

    @BeforeEach
    void setUp() {
        cleanPersistence();
        seedPlanCatalog();
        seedAccessData();
        seedWarehouse();
        loginAdmin();
        publish(WmsSpaceChangePlanDefinitionFactory.changeDraft(
            WmsSpacePlanContract.CHANGE_PLAN_CODE));
        publish(WmsSpaceChangePlanDefinitionFactory.supervisorDraft(
            WmsSpacePlanContract.CHANGE_SUPERVISOR_PLAN_CODE,
            WmsSpaceChangeService.PLAN_TYPE_CHANGE,
            "空间配置变更监督"));
        publish(WmsSpaceChangePlanDefinitionFactory.retireDraft(
            WmsSpacePlanContract.RETIRE_PLAN_CODE));
        publish(WmsSpaceChangePlanDefinitionFactory.supervisorDraft(
            WmsSpacePlanContract.RETIRE_SUPERVISOR_PLAN_CODE,
            WmsSpaceChangeService.PLAN_TYPE_RETIRE,
            "空间退役监督"));
        publish(WmsRulePlanDefinitionFactory.publishDraft(
            WmsRulePlanContract.PLAN_CODE,
            WmsRulePlanContract.BUSINESS_CODE
        ));
        publish(WmsRulePlanDefinitionFactory.supervisorDraft(
            WmsRulePlanContract.SUPERVISOR_PLAN_CODE,
            WmsRulePlanContract.BUSINESS_CODE
        ));
        processProperties.getScheduler().setEnabled(true);
    }

    @AfterEach
    void clearLogin() {
        TestAuth.clear();
    }

    @Test
    void ordinaryCompanyRoleRunsFormalChangeAndResumesAcrossWorkerInstances() {
        loginOrdinary(Set.of());
        WmsSpaceChangeCommand command = materialZone("Z-WORKER", "worker空间区");
        WmsSpaceChangeStartResult started = spaceChangeProcess.start(command);

        WmsSpaceChangeCommand replayCommand = materialZone("Z-WORKER", "worker空间区");
        WmsSpaceChangeStartResult replayed = spaceChangeProcess.start(replayCommand);
        assertThat(replayed.changeOrderId()).isEqualTo(started.changeOrderId());
        assertThat(replayed.planInstanceId()).isEqualTo(started.planInstanceId());
        assertThat(replayed.businessTaskId()).isEqualTo(started.businessTaskId());
        assertThat(replayed.queueStatus()).isEqualTo("REPLAYED");

        admit(started.planInstanceId());
        TestAuth.clear();
        assertThat(TestAuth.isLogin()).isFalse();

        EventBatchResult validate = worker().runBatch(scheduleId(), 4, 20_000L);
        assertThat(validate.succeeded()).isEqualTo(1);
        assertThat(orderStatus(started.changeOrderId())).isEqualTo("VALIDATED");

        // 新 worker 实例模拟运行中业务重启；持久事件和 checkpoint 是唯一恢复输入。
        EventBatchResult apply = worker().runBatch(scheduleId(), 4, 20_000L);
        assertThat(apply.succeeded()).isEqualTo(1);
        EventBatchResult verify = worker().runBatch(scheduleId(), 4, 20_000L);
        assertThat(verify.succeeded()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
            select lifecycle_status from wms_warehouse_zone where zone_code = 'Z-WORKER'
            """, String.class)).isEqualTo("ACTIVE");
        assertThat(orderStatus(started.changeOrderId())).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
            select plan_status from iws_process_instance where id = ?
            """, String.class, started.planInstanceId())).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from iws_process_event
             where instance_id = ? and event_status = 'SUCCESS'
               and event_code in ('VALIDATE_CHANGE', 'APPLY_CONFIGURATION', 'VERIFY_CHANGE')
            """, Integer.class, started.planInstanceId())).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
            select json_unquote(json_extract(checkpoint_json, '$.status'))
              from iws_process_attempt attempt
              join iws_process_event event on event.id = attempt.event_id
             where event.instance_id = ? order by attempt.id
            """, String.class, started.planInstanceId()))
            .containsExactly("VALIDATED", "APPLIED", "COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_business_task
             where plan_instance_id = ? and business_id = ?
               and task_key = ? and input_hash is not null
            """, Integer.class, started.planInstanceId(), started.changeOrderId(),
            command.getCommandKey())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_location_version version
              join wms_location_effective effective
                on effective.object_type = version.object_type
               and effective.object_id = version.object_id
               and effective.location_version_id = version.location_version_id
             where version.object_type = 'ZONE'
               and version.object_id = (
                   select zone_id from wms_warehouse_zone where zone_code = 'Z-WORKER')
               and version.status = 'ACTIVE'
            """, Integer.class)).isOne();
    }

    @Test
    void productionRuleConfigurationUsesFormalStorageProviderAndCatalog() {
        assertThat(ruleAttributeSources
            .resolveExact("STORAGE", "STORAGE-1")
            .bindings()).hasSize(6).containsKey("storageCode");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wms_attribute_catalog where source_type = 'STORAGE'",
            Integer.class)).isEqualTo(6);

        WmsRuleDefinition definition = new WmsRuleDefinition();
        definition.setPurpose(WmsRuleBindingConfiguration.PURPOSE_FILTER);
        definition.setScopeType("STORAGE");
        WmsRuleVersion version = new WmsRuleVersion();
        version.setConditionJson("""
            {"schema":"iws.rule.condition/1","root":{
              "type":"SCALAR_COMPARE","attribute":"storageCode",
              "operator":"EQ","value":"S-WORKER"}}
            """);
        version.setResultJson("""
            {"schema":"iws.rule.result/1","type":"FILTER","value":true}
            """);

        WmsRuleBindingService.Compiled compiled = ruleBindingService.compile(definition, version);
        assertThat(compiled.snapshotJson()).contains("\"sourceType\":\"STORAGE\"")
            .contains("\"contractVersion\":\"STORAGE-1\"");
        assertThat(compiled.digest()).matches("[0-9a-f]{64}");
    }

    @Test
    void productionRuleDraftPublishesThroughPersistentWorkerAfterLoginIsCleared() {
        loginOrdinary(Set.of("inventory:rule:draft", "inventory:rule:publish"));
        seedRuleStorage();
        WmsRuleDefinitionCreateCommand create = new WmsRuleDefinitionCreateCommand();
        create.setCommandKey("rule-create-" + System.nanoTime());
        create.setRuleKey("RULE-PRODUCTION-WORKER");
        create.setPurpose(WmsRuleBindingConfiguration.PURPOSE_FILTER);
        create.setRuleName("生产来源规则");
        create.setScopeType("STORAGE");
        create.setCondition(jsonMapper.readTree("""
            {"schema":"iws.rule.condition/1","root":{
              "type":"SCALAR_COMPARE","attribute":"storageCode",
              "operator":"EQ","value":"S-WORKER"}}
            """));
        create.setResult(jsonMapper.readTree("""
            {"schema":"iws.rule.result/1","type":"FILTER","value":true}
            """));
        create.setReason("生产规则组合验收");
        WmsRuleDefinitionVo definition = ruleDraftService.createDefinition(create);
        WmsRuleVersionVo draft = definition.versions().getFirst();

        WmsRuleVersionActivateCommand activate = new WmsRuleVersionActivateCommand();
        activate.setCommandKey("rule-activate-" + System.nanoTime());
        activate.setRuleVersionId(draft.ruleVersionId());
        activate.setExpectedDigest(draft.canonicalDigest());
        activate.setReason("生产规则组合验收");
        WmsRuleVersionVo active = ruleDraftService.activateVersion(activate);
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.executionReady()).isTrue();
        assertThat(active.bindingSnapshotState()).isEqualTo("READY");

        WmsRulePackageCreateCommand.Member member =
            new WmsRulePackageCreateCommand.Member();
        member.setPurpose(WmsRuleBindingConfiguration.PURPOSE_FILTER);
        member.setRuleVersionId(active.ruleVersionId());
        WmsRulePackageCreateCommand packageCommand = new WmsRulePackageCreateCommand();
        packageCommand.setCommandKey("rule-package-" + System.nanoTime());
        packageCommand.setPackageCode("PKG-PRODUCTION-WORKER");
        packageCommand.setMembers(List.of(member));
        packageCommand.setReason("生产规则组合验收");
        WmsRulePackageVersionVo packageVersion =
            rulePublishService.createPackageVersion(packageCommand);

        WmsRulePackagePublishCommand publish = new WmsRulePackagePublishCommand();
        publish.setCommandKey("rule-publish-" + System.nanoTime());
        publish.setPackageVersionId(packageVersion.packageVersionId());
        publish.setScopeType("STORAGE");
        publish.setScopeId(STORAGE_ID);
        publish.setStorageIds(List.of(STORAGE_ID));
        publish.setReason("生产规则组合验收");
        WmsRulePublicationStartResult started = rulePublicationProcess.start(publish);
        admit(started.planInstanceId());
        TestAuth.clear();

        EventBatchResult validate = worker().runBatch(ruleScheduleId(), 4, 20_000L);
        assertThat(validate.succeeded()).isEqualTo(1);
        EventBatchResult publishResult = worker().runBatch(ruleScheduleId(), 4, 20_000L);
        assertThat(publishResult.succeeded()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
            select package_version_id from wms_rule_effective_pointer
             where scope_type = 'STORAGE' and scope_id = ?
            """, Long.class, STORAGE_ID)).isEqualTo(packageVersion.packageVersionId());
        assertThat(jdbcTemplate.queryForObject("""
            select plan_status from iws_process_instance where id = ?
            """, String.class, started.planInstanceId())).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForList("""
            select json_unquote(json_extract(checkpoint_json, '$.status'))
              from iws_process_attempt attempt
              join iws_process_event event on event.id = attempt.event_id
             where event.instance_id = ? order by attempt.id
            """, String.class, started.planInstanceId()))
            .containsExactly("VALIDATED", "PUBLISHED");
        assertThat(jdbcTemplate.queryForList("""
            select concat(initiator_type, ':', initiator_id, ':',
                          execution_subject_type, ':', execution_subject)
              from wms_object_audit
             where action = 'RULE_PACKAGE_PUBLISH'
             order by audit_id desc limit 1
            """, String.class)).containsExactly(
                "USER:" + USER_ID + ":SCHEDULED_WORKER:SCHEDULED_WORKER");
    }

    @Test
    void crossCompanyAndQueryOnlyRolesCannotMountChange() {
        loginOrdinary(Set.of());
        jdbcTemplate.update("""
            update wms_company_management_grant set company_id = ? where company_id = ?
            """, OTHER_COMPANY_ID, COMPANY_ID);
        WmsSpaceChangeCommand crossCompany = materialZone("Z-CROSS", "跨公司空间区");
        assertThatThrownBy(() -> spaceChangeProcess.start(crossCompany))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("COMPANY_SCOPE_DENIED");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wms_space_change_order", Integer.class)).isZero();

        jdbcTemplate.update("""
            update wms_company_management_grant set company_id = ? where company_id = ?
            """, COMPANY_ID, OTHER_COMPANY_ID);
        jdbcTemplate.update("delete from sys_role_menu where role_id = ? and menu_id = ?",
            ROLE_ID, CHANGE_MENU_ID);
        jdbcTemplate.update("""
            insert into sys_role_menu (role_id, menu_id) values (?, ?)
            """, ROLE_ID, QUERY_MENU_ID);
        WmsSpaceChangeCommand queryOnly = materialZone("Z-QUERY", "查询角色空间区");
        assertThatThrownBy(() -> spaceChangeProcess.start(queryOnly))
            .isInstanceOf(cn.dev33.satoken.exception.NotPermissionException.class);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from wms_space_change_order", Integer.class)).isZero();
    }

    @Test
    void revokedPermissionFailsWorkerWithoutApplyingDomainEffect() {
        loginOrdinary(Set.of());
        WmsSpaceChangeStartResult started =
            spaceChangeProcess.start(materialZone("Z-REVOKED", "撤权空间区"));
        admit(started.planInstanceId());
        jdbcTemplate.update("delete from sys_role_menu where role_id = ? and menu_id = ?",
            ROLE_ID, CHANGE_MENU_ID);
        TestAuth.clear();

        EventBatchResult result = worker().runBatch(scheduleId(), 4, 20_000L);
        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.succeeded()).isZero();
        assertThat(orderStatus(started.changeOrderId())).isEqualTo("CREATED");
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from wms_warehouse_zone where zone_code = 'Z-REVOKED'
            """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from iws_process_event
             where instance_id = ? and event_status = 'SUCCESS'
            """, Integer.class, started.planInstanceId())).isZero();
    }

    @Test
    void retirePlanUsesStartCheckedTransitionAndFormalWorker() {
        loginOrdinary(Set.of());
        WmsSpaceChangeCommand retire = retireCommand();
        WmsSpaceChangeStartResult started = spaceChangeProcess.start(retire);
        admit(started.planInstanceId());
        TestAuth.clear();

        EventBatchResult check = worker().runBatch(scheduleId(), 4, 20_000L);
        assertThat(check.succeeded()).isEqualTo(1);
        assertThat(orderStatus(started.changeOrderId())).isEqualTo("CHECKED");
        EventBatchResult retired = worker().runBatch(scheduleId(), 4, 20_000L);
        assertThat(retired.succeeded()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
            select lifecycle_status from wms_warehouse where warehouse_id = ?
            """, String.class, WAREHOUSE_ID)).isEqualTo("RETIRED");
        assertThat(orderStatus(started.changeOrderId())).isEqualTo("RETIRED");
        assertThat(jdbcTemplate.queryForList("""
            select json_unquote(json_extract(checkpoint_json, '$.status'))
              from iws_process_attempt attempt
              join iws_process_event event on event.id = attempt.event_id
             where event.instance_id = ? order by attempt.id
            """, String.class, started.planInstanceId()))
            .containsExactly("CHECKED", "RETIRED");
    }

    private void admit(long businessInstanceId) {
        Long supervisorId = jdbcTemplate.queryForObject("""
            select supervisor_instance_id from iws_process_instance where id = ?
            """, Long.class, businessInstanceId);
        SupervisorTickResult tick = supervisorTick().tick(supervisorId, 8, 20_000L);
        assertThat(tick.roundStatus()).isEqualTo("COMPLETED");
        assertThat(tick.selectedCount()).isEqualTo(1);
    }

    private PlanEventWorkerServiceImpl worker() {
        return new PlanEventWorkerServiceImpl(
            instanceMapper,
            eventMapper,
            eventUpdateService,
            eventMutationService,
            objectAuthorizer,
            new PlanCandidateQueryService(jdbcTemplate)
        );
    }

    private SupervisorTickServiceImpl supervisorTick() {
        EventEligibilityService eligibility = eligibility();
        return new SupervisorTickServiceImpl(
            runtimeDao,
            supervisorRuntimeService,
            instanceMapper,
            controlMapper,
            definitionMapper,
            eventMapper,
            attemptMapper,
            new SupervisorRoundMaterializer(
                runtimeDao, eventMapper, tokenMapper, eligibility),
            eventUpdateService
        );
    }

    private CapabilityRegistry registry() {
        return capabilityRegistry;
    }

    private EventEligibilityService eligibility() {
        return new EventEligibilityService(runtimeDao, eventMapper, receiptMapper, tokenMapper);
    }

    private PlanActivationService activation(
        EventEligibilityService eligibility,
        CapabilityRegistry registry
    ) {
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

    private void publish(DefinitionDraftBo draft) {
        var created = definitionService.createDraft(
            draft, "create-" + draft.planCode(), "空间计划组合验收");
        long versionId = Long.parseLong(created.summary().versionId());
        var validation = definitionService.validate(versionId);
        assertThat(validation.valid()).as(validation.errors().toString()).isTrue();
        var published = definitionService.publish(
            versionId,
            Long.parseLong(created.summary().revision()),
            "publish-" + draft.planCode(),
            "空间计划组合验收"
        );
        assertThat(published.valid()).isTrue();
    }

    private long scheduleId() {
        Long value = jdbcTemplate.queryForObject("""
            select id from iws_business_schedule where business_code = 'SPACE_CONFIG'
            """, Long.class);
        assertThat(value).isNotNull();
        return value;
    }

    private long ruleScheduleId() {
        Long value = jdbcTemplate.queryForObject("""
            select id from iws_business_schedule where business_code = 'INVENTORY_CONFIG'
            """, Long.class);
        assertThat(value).isNotNull();
        return value;
    }

    private String orderStatus(long changeOrderId) {
        return jdbcTemplate.queryForObject("""
            select status from wms_space_change_order where change_order_id = ?
            """, String.class, changeOrderId);
    }

    private void seedPlanCatalog() {
        jdbcTemplate.update("""
            insert into iws_business_schedule
                (id, business_code, business_name, enabled, fairness_window_size,
                 revision, remark, del_flag, create_time, update_time)
            values (?, 'INVENTORY_CONFIG', '库存配置', 1, 32, 0,
                    '规则发布组合验收', '0', current_timestamp(3), current_timestamp(3))
            on duplicate key update
                business_name = values(business_name),
                enabled = 1,
                del_flag = '0',
                update_time = current_timestamp(3)
            """, RULE_SCHEDULE_ID);
        jdbcTemplate.update("""
            insert into iws_business_schedule_plan_type
                (id, schedule_id, plan_type, plan_type_name, priority, enabled,
                 create_time, update_time)
            values (?, ?, 'RULESET_PUBLISH', '规则包发布', 10, 1,
                    current_timestamp(3), current_timestamp(3))
            on duplicate key update
                plan_type_name = values(plan_type_name),
                priority = values(priority),
                enabled = 1,
                update_time = current_timestamp(3)
            """, RULE_PUBLISH_TYPE_ID, RULE_SCHEDULE_ID);
        jdbcTemplate.update("""
            insert into iws_business_schedule
                (id, business_code, business_name, enabled, fairness_window_size,
                 revision, remark, del_flag, create_time, update_time)
            values (?, 'SPACE_CONFIG', '空间配置', 1, 32, 0,
                    '空间计划组合验收', '0', current_timestamp(3), current_timestamp(3))
            on duplicate key update
                business_name = values(business_name),
                enabled = 1,
                del_flag = '0',
                update_time = current_timestamp(3)
            """, SPACE_SCHEDULE_ID);
        jdbcTemplate.update("""
            insert into iws_business_schedule_plan_type
                (id, schedule_id, plan_type, plan_type_name, priority, enabled,
                 create_time, update_time)
            values (?, ?, 'SPACE_CHANGE', '空间配置变更', 10, 1,
                    current_timestamp(3), current_timestamp(3))
            on duplicate key update
                plan_type_name = values(plan_type_name),
                priority = values(priority),
                enabled = 1,
                update_time = current_timestamp(3)
            """, SPACE_CHANGE_TYPE_ID, SPACE_SCHEDULE_ID);
        jdbcTemplate.update("""
            insert into iws_business_schedule_plan_type
                (id, schedule_id, plan_type, plan_type_name, priority, enabled,
                 create_time, update_time)
            values (?, ?, 'SPACE_RETIRE', '空间退役', 20, 1,
                    current_timestamp(3), current_timestamp(3))
            on duplicate key update
                plan_type_name = values(plan_type_name),
                priority = values(priority),
                enabled = 1,
                update_time = current_timestamp(3)
            """, SPACE_RETIRE_TYPE_ID, SPACE_SCHEDULE_ID);
    }

    private void seedAccessData() {
        jdbcTemplate.update("""
            insert into wms_company
                (company_id, company_code, company_name, enabled, lifecycle_status, revision)
            values (?, 'C-WORKER', 'worker公司', 1, 'ACTIVE', 1),
                   (?, 'C-OTHER', '其他公司', 1, 'ACTIVE', 1)
            """, COMPANY_ID, OTHER_COMPANY_ID);
        jdbcTemplate.update("""
            insert into wms_company_management_scope (company_id, scope_revision)
            values (?, 0), (?, 0)
            """, COMPANY_ID, OTHER_COMPANY_ID);
        jdbcTemplate.update("""
            insert into sys_role
                (role_id, role_name, role_key, role_sort, data_scope,
                 menu_check_strictly, dept_check_strictly, status, del_flag,
                 create_dept, create_by, create_time, update_by, update_time)
            values (?, 'WMS空间普通角色', 'wms_space_user', 90, '1',
                    1, 1, '0', '0', 1761000000000000103,
                    1761100000000000001, current_timestamp(3), null, null)
            """, ROLE_ID);
        jdbcTemplate.update("""
            insert into sys_user
                (user_id, dept_id, user_name, nick_name, user_type, email, phone_number,
                 gender, password, status, del_flag, create_dept, create_by,
                 create_time, update_by, update_time, remark)
            values (?, 1761000000000000108, 'wms-space-user', '空间普通用户',
                    'sys_user', 'wms@innolux.com', '15888888888', '2',
                    '$2a$10$invalid.hash.is.not.used.in.session', '0', '0',
                    1761000000000000103, 1761100000000000001, current_timestamp(3),
                    null, null, '空间计划普通角色验收')
            """, USER_ID);
        jdbcTemplate.update("""
            insert into sys_user_role (user_id, role_id) values (?, ?)
            """, USER_ID, ROLE_ID);
        jdbcTemplate.update("""
            insert into sys_role_menu (role_id, menu_id) values (?, ?)
            """, ROLE_ID, CHANGE_MENU_ID);
        jdbcTemplate.update("""
            insert into sys_role_menu (role_id, menu_id) values (?, ?), (?, ?)
            """, ROLE_ID, RULE_DRAFT_MENU_ID, ROLE_ID, RULE_PUBLISH_MENU_ID);
        jdbcTemplate.update("""
            insert into wms_company_management_grant
                (grant_id, company_id, role_id)
            values (?, ?, ?)
            """, 10_501L, COMPANY_ID, ROLE_ID);
    }

    private void seedWarehouse() {
        jdbcTemplate.update("""
            insert into wms_warehouse
                (warehouse_id, company_id, warehouse_code, warehouse_name,
                 warehouse_type_code, lifecycle_status, enabled, revision)
            values (?, ?, 'WH-WORKER', 'worker父仓', 'GENERAL', 'ACTIVE', 1, 1)
            """, WAREHOUSE_ID, COMPANY_ID);
        jdbcTemplate.update("""
            insert into wms_location_version
                (location_version_id, object_type, object_id, version_no,
                 config_json, canonical_digest, status)
            values (?, 'WAREHOUSE', ?, 1, json_object(), ?, 'ACTIVE')
            """, WAREHOUSE_VERSION_ID, WAREHOUSE_ID, OUTPUT_HASH);
        jdbcTemplate.update("""
            insert into wms_location_effective
                (location_effective_id, object_type, object_id,
                 location_version_id, revision)
            values (?, 'WAREHOUSE', ?, ?, 1)
            """, WAREHOUSE_VERSION_ID, WAREHOUSE_ID, WAREHOUSE_VERSION_ID);
    }

    private void seedRuleStorage() {
        jdbcTemplate.update("""
            insert into wms_warehouse_zone
                (zone_id, zone_code, zone_name, zone_type, zone_kind,
                 lifecycle_status, parent_zone_id, warehouse_id, allow_inbound,
                 allow_outbound, status, revision, sort_no, del_flag)
            values (?, 'ZM-RULE', '规则物料区', 1, 'MATERIAL',
                    'ACTIVE', null, ?, 1, 1, 1, 1, 0, '0')
            """, MATERIAL_ZONE_ID, WAREHOUSE_ID);
        jdbcTemplate.update("""
            insert into wms_warehouse_zone
                (zone_id, zone_code, zone_name, zone_type, zone_kind,
                 lifecycle_status, parent_zone_id, warehouse_id, allow_inbound,
                 allow_outbound, status, revision, sort_no, del_flag)
            values (?, 'ZD-RULE', '规则设备区', 1, 'DEVICE',
                    'ACTIVE', ?, ?, 1, 1, 1, 1, 0, '0')
            """, DEVICE_ZONE_ID, MATERIAL_ZONE_ID, WAREHOUSE_ID);
        jdbcTemplate.update("""
            insert into wms_storage
                (storage_id, storage_code, zone_id, warehouse_id, storage_type,
                 status, disable_status, lifecycle_status, version, del_flag,
                 aisle_text, row_text, level_text, column_text, depth_text)
            values (?, 'S-RULE', ?, ?, 1, 1, 0, 'ACTIVE', 1, '0',
                    'A-RULE', 'R1', 'L1', 'C1', 'D1')
            """, STORAGE_ID, DEVICE_ZONE_ID, WAREHOUSE_ID);
        for (VersionSeed seed : List.of(
            new VersionSeed(MATERIAL_ZONE_ID, "ZONE", MATERIAL_ZONE_VERSION_ID),
            new VersionSeed(DEVICE_ZONE_ID, "ZONE", DEVICE_ZONE_VERSION_ID),
            new VersionSeed(STORAGE_ID, "STORAGE", STORAGE_VERSION_ID)
        )) {
            jdbcTemplate.update("""
                insert into wms_location_version
                    (location_version_id, object_type, object_id, version_no,
                     config_json, canonical_digest, status)
                values (?, ?, ?, 1, json_object(), ?, 'ACTIVE')
                """, seed.versionId(), seed.objectType(), seed.objectId(), OUTPUT_HASH);
            jdbcTemplate.update("""
                insert into wms_location_effective
                    (location_effective_id, object_type, object_id,
                     location_version_id, revision)
                values (?, ?, ?, ?, 1)
                """, seed.versionId(), seed.objectType(), seed.objectId(),
                seed.versionId());
        }
    }

    private void cleanPersistence() {
        for (String table : List.of(
            "wms_business_task_barrier",
            "wms_business_task",
            "iws_process_action_audit",
            "iws_process_request_detail",
            "iws_process_trigger_receipt",
            "iws_process_attempt",
            "iws_process_event",
            "iws_process_token",
            "iws_process_system_context",
            "iws_process_schedule_control",
            "iws_process_instance",
            "iws_process_edge",
            "iws_process_system_policy",
            "iws_process_event_definition",
            "iws_process_state",
            "iws_process_definition",
            "wms_rule_effective_pointer",
            "wms_logical_scope_storage",
            "wms_logical_scope_version",
            "wms_logical_scope",
            "wms_rule_package_member",
            "wms_rule_package_version",
            "wms_rule_version",
            "wms_rule_definition",
            "wms_object_audit",
            "wms_command_attempt",
            "wms_space_change_order",
            "wms_location_effective",
            "wms_location_version",
            "wms_inventory",
            "wms_container",
            "wms_storage",
            "wms_warehouse_zone",
            "wms_warehouse",
            "wms_company_management_grant",
            "wms_company_management_scope",
            "wms_company",
            "sys_user_role",
            "sys_role_menu",
            "sys_user",
            "sys_role"
        )) {
            jdbcTemplate.update("delete from " + table + " where 1 = 1");
        }
    }

    private WmsSpaceChangeCommand materialZone(String code, String name) {
        WmsSpaceChangeCommand command = new WmsSpaceChangeCommand();
        command.setCommandKey("space-worker-" + code);
        command.setChangeType("CREATE");
        command.setObjectType("ZONE");
        command.setCompanyId(COMPANY_ID);
        command.setWarehouseId(WAREHOUSE_ID);
        command.setCode(code);
        command.setName(name);
        command.setZoneKind("MATERIAL");
        command.setZoneType(1);
        command.setReason("普通公司角色空间计划验收");
        return command;
    }

    private WmsSpaceChangeCommand retireCommand() {
        WmsSpaceChangeCommand command = new WmsSpaceChangeCommand();
        command.setCommandKey("space-worker-retire-warehouse");
        command.setChangeType("RETIRE");
        command.setObjectType("WAREHOUSE");
        command.setObjectId(WAREHOUSE_ID);
        command.setExpectedRevision(1);
        command.setReason("空间退役正式计划验收");
        return command;
    }

    private void loginAdmin() {
        TestAuth.login(SystemConstants.SUPER_ADMIN_USER_ID, Set.of("*"));
    }

    private void loginOrdinary(Set<String> permissions) {
        TestAuth.login(USER_ID, permissions);
    }

    private static final class TestAuth {

        private static final AtomicBoolean INSTALLED = new AtomicBoolean();

        private TestAuth() {
        }

        static void install() {
            if (INSTALLED.compareAndSet(false, true)) {
                SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
                SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
                SaManager.setStpInterface(new SaPermissionImpl());
                SaManager.getConfig().setJwtSecretKey("abcdefghijklmnopqrstuvwxyz");
                StpLogic jwtLogic = new StpLogicJwtForSimple();
                SaManager.putStpLogic(jwtLogic);
                StpUtil.setStpLogic(jwtLogic);
            }
        }

        static void login(long userId, Set<String> permissions) {
            install();
            SaTokenContextMockUtil.setMockContext();
            LoginUser user = new LoginUser();
            user.setUserId(userId);
            user.setUserType("pc");
            user.setUsername("space-user-" + userId);
            user.setNickname("space-user-" + userId);
            user.setDeptId(1761000000000000108L);
            user.setMenuPermission(new HashSet<>(permissions));
            user.setRolePermission(new HashSet<>());
            LoginHelper.login(user, null);
        }

        static boolean isLogin() {
            try {
                return StpUtil.isLogin();
            } catch (cn.dev33.satoken.exception.SaTokenContextException error) {
                return false;
            }
        }

        static void clear() {
            SaTokenContextMockUtil.clearContext();
        }
    }

    private record VersionSeed(long objectId, String objectType, long versionId) {
    }
}
