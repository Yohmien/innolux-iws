/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布计划
 * @Version      : V1.0
 * @Description  : 验证规则发布先挂起建任务再解除挂起的持久计划合同；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.process;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.process.api.AttachResult;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.BusinessScheduleVo;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.DefinitionEventBo;
import com.innolux.iws.process.api.DefinitionSummaryVo;
import com.innolux.iws.process.api.PlanControlCommand;
import com.innolux.iws.process.api.PlanTypeVo;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.service.BusinessScheduleService;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.process.service.PlanInstanceService;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.support.WmsRuleAuditService;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 规则发布计划入口合同测试。 */
@Tag("local")
class WmsRulePublicationProcessTest {

    @Test
    void attachesHeldInstanceFreezesTaskAndThenReleasesStart() {
        Environment environment = mock(Environment.class);
        BusinessScheduleService schedules = mock(BusinessScheduleService.class);
        PlanDefinitionService definitions = mock(PlanDefinitionService.class);
        PlanInstanceService instances = mock(PlanInstanceService.class);
        WmsBusinessTaskService tasks = mock(WmsBusinessTaskService.class);
        ProcessInstanceMapper instanceMapper = mock(ProcessInstanceMapper.class);
        WmsRuleAuditService auditService = mock(WmsRuleAuditService.class);
        WmsCompanyAccessPolicy accessPolicy = mock(WmsCompanyAccessPolicy.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<BusinessScheduleService> scheduleProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PlanDefinitionService> definitionProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PlanInstanceService> instanceProvider = mock(ObjectProvider.class);

        when(environment.getProperty("iws.process.enabled", Boolean.class, Boolean.FALSE))
            .thenReturn(true);
        when(scheduleProvider.getIfAvailable()).thenReturn(schedules);
        when(definitionProvider.getIfAvailable()).thenReturn(definitions);
        when(instanceProvider.getIfAvailable()).thenReturn(instances);
        BusinessScheduleVo schedule = schedule();
        PageResult<BusinessScheduleVo> schedulePage = page(List.of(schedule));
        when(schedules.page(any(), any(), any())).thenReturn(schedulePage);
        DefinitionSummaryVo summary = mock(DefinitionSummaryVo.class);
        when(summary.planCode()).thenReturn(WmsRulePlanContract.PLAN_CODE);
        when(summary.planType()).thenReturn(WmsRulePlanContract.PLAN_TYPE);
        when(summary.planRole()).thenReturn("BUSINESS");
        when(summary.versionId()).thenReturn("301");
        PageResult<DefinitionSummaryVo> definitionPage = page(List.of(summary));
        when(definitions.page(any(), any(), any(), any())).thenReturn(definitionPage);
        DefinitionDetailVo detail = mock(DefinitionDetailVo.class);
        when(detail.summary()).thenReturn(summary);
        when(detail.businessType()).thenReturn(WmsRulePlanContract.BUSINESS_TYPE);
        when(detail.businessScheduleCode()).thenReturn(WmsRulePlanContract.BUSINESS_CODE);
        DefinitionEventBo validateEvent = event(WmsRulePlanContract.VALIDATE_ACTION_KEY);
        DefinitionEventBo activateEvent = event(WmsRulePlanContract.ACTIVATE_ACTION_KEY);
        when(detail.events()).thenReturn(List.of(validateEvent, activateEvent));
        when(definitions.get(301L)).thenReturn(detail);
        when(instances.attach(any())).thenReturn(new AttachResult(
            "9001",
            null,
            null,
            "HELD_BEFORE_START",
            false,
            null,
            null,
            "1"
        ));
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getInstanceVersion()).thenReturn(1L);
        when(instanceMapper.selectById(9001L)).thenReturn(instance);
        when(tasks.createOrReplay(any())).thenReturn(new WmsBusinessTaskSnapshot(
            9_101L,
            WmsRulePlanContract.BUSINESS_TYPE,
            71L,
            "rule-publish-1",
            WmsRulePlanContract.PLAN_TYPE,
            9_001L,
            "a".repeat(64),
            "{}",
            "ACTIVE",
            1
        ));
        when(instances.releaseStart(any())).thenReturn(
            CommandResult.success("9001", "2", List.of())
        );
        when(auditService.digest(any())).thenReturn("a".repeat(64));

        WmsRulePublicationProcess process = new WmsRulePublicationProcess(
            environment,
            scheduleProvider,
            definitionProvider,
            instanceProvider,
            tasks,
            instanceMapper,
            auditService,
            accessPolicy,
            JsonMapper.builder().build()
        );
        WmsRulePublicationStartResult result = process.start(command());

        ArgumentCaptor<AttachTaskCommand> attach = ArgumentCaptor.forClass(
            AttachTaskCommand.class
        );
        verify(instances).attach(attach.capture());
        assertThat(attach.getValue().holdStart()).isTrue();
        assertThat(attach.getValue().businessId()).isEqualTo("71");
        assertThat(attach.getValue().taskKey()).isEqualTo("rule-publish-1");
        ArgumentCaptor<WmsBusinessTaskCreateCommand> task = ArgumentCaptor.forClass(
            WmsBusinessTaskCreateCommand.class
        );
        verify(tasks).createOrReplay(task.capture());
        assertThat(task.getValue().planInstanceId()).isEqualTo(9_001L);
        assertThat(task.getValue().phase()).isEqualTo("PUBLISH");
        assertThat(task.getValue().inputJson()).contains("\"packageVersionId\":71");
        ArgumentCaptor<PlanControlCommand> release = ArgumentCaptor.forClass(
            PlanControlCommand.class
        );
        verify(instances).releaseStart(release.capture());
        assertThat(release.getValue().instanceId()).isEqualTo(9_001L);
        assertThat(release.getValue().expectedVersion()).isEqualTo(1L);
        assertThat(result.planInstanceId()).isEqualTo(9_001L);
        assertThat(result.businessTaskId()).isEqualTo(9_101L);
        verify(accessPolicy).checkPermission(WmsRuleContract.PERMISSION_PUBLISH);
    }

    private static BusinessScheduleVo schedule() {
        BusinessScheduleVo schedule = mock(BusinessScheduleVo.class);
        PlanTypeVo type = mock(PlanTypeVo.class);
        when(type.planType()).thenReturn(WmsRulePlanContract.PLAN_TYPE);
        when(type.enabled()).thenReturn(true);
        when(schedule.id()).thenReturn("8001");
        when(schedule.businessCode()).thenReturn(WmsRulePlanContract.BUSINESS_CODE);
        when(schedule.planTypes()).thenReturn(List.of(type));
        return schedule;
    }

    private static DefinitionEventBo event(String actionKey) {
        DefinitionEventBo event = mock(DefinitionEventBo.class);
        when(event.localActionKey()).thenReturn(actionKey);
        return event;
    }

    private static <T> PageResult<T> page(List<T> rows) {
        @SuppressWarnings("unchecked")
        PageResult<T> page = mock(PageResult.class);
        when(page.getRows()).thenReturn(rows);
        return page;
    }

    private static WmsRulePackagePublishCommand command() {
        WmsRulePackagePublishCommand command = new WmsRulePackagePublishCommand();
        command.setCommandKey("rule-publish-1");
        command.setPackageVersionId(71L);
        command.setScopeType("WAREHOUSE");
        command.setScopeId(501L);
        command.setStorageIds(List.of(20L, 10L));
        command.setReason("规则发布计划测试");
        return command;
    }
}
