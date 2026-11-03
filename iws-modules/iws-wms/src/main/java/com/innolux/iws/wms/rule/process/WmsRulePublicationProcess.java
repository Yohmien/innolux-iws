/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布计划
 * @Version      : V1.0
 * @Description  : 创建持久业务任务并挂载规则校验、激活计划；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.process;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.satoken.utils.LoginHelper;
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
import com.innolux.iws.wms.rule.support.WmsRuleValidation;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 规则发布计划入口。 */
@Service
public class WmsRulePublicationProcess {

    private static final String PROCESS_ENABLED = "iws.process.enabled";

    private static final int PAGE_SIZE = 50;

    private final Environment environment;

    private final ObjectProvider<BusinessScheduleService> scheduleProvider;

    private final ObjectProvider<PlanDefinitionService> definitionProvider;

    private final ObjectProvider<PlanInstanceService> instanceProvider;

    private final WmsBusinessTaskService taskService;

    private final ProcessInstanceMapper instanceMapper;

    private final WmsRuleAuditService auditService;

    private final WmsCompanyAccessPolicy accessPolicy;

    private final JsonMapper jsonMapper;

    public WmsRulePublicationProcess(
        Environment environment,
        ObjectProvider<BusinessScheduleService> scheduleProvider,
        ObjectProvider<PlanDefinitionService> definitionProvider,
        ObjectProvider<PlanInstanceService> instanceProvider,
        WmsBusinessTaskService taskService,
        ProcessInstanceMapper instanceMapper,
        WmsRuleAuditService auditService,
        WmsCompanyAccessPolicy accessPolicy,
        JsonMapper jsonMapper
    ) {
        this.environment = environment;
        this.scheduleProvider = scheduleProvider;
        this.definitionProvider = definitionProvider;
        this.instanceProvider = instanceProvider;
        this.taskService = taskService;
        this.instanceMapper = instanceMapper;
        this.auditService = auditService;
        this.accessPolicy = accessPolicy;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 受理规则发布命令并创建持久计划任务。
     *
     * @param source 发布命令
     * @return 计划与业务任务身份
     */
    @Transactional(rollbackFor = Exception.class)
    public WmsRulePublicationStartResult start(WmsRulePackagePublishCommand source) {
        requireProcessEnabled();
        accessPolicy.checkPermission(WmsRuleContract.PERMISSION_PUBLISH);
        WmsRulePackagePublishCommand command = normalize(source);
        BusinessScheduleService schedules = require(scheduleProvider, "业务调度服务");
        PlanDefinitionService definitions = require(definitionProvider, "计划定义服务");
        PlanInstanceService instances = require(instanceProvider, "计划实例服务");
        BusinessScheduleVo schedule = findSchedule(schedules);
        requirePlanType(schedule);
        verifyDefinition(definitions);

        String inputHash = auditService.digest(command);
        AttachResult attached = instances.attach(new AttachTaskCommand(
            parsePositiveLong(schedule.id(), "scheduleId"),
            WmsRulePlanContract.PLAN_TYPE,
            WmsRulePlanContract.BUSINESS_TYPE,
            String.valueOf(command.getPackageVersionId()),
            command.getCommandKey(),
            true,
            inputHash,
            List.of(),
            "RULE_PUBLISH_ATTACH:" + command.getCommandKey()
        ));
        if (attached == null || attached.instanceId() == null) {
            throw new ServiceException("规则发布计划挂载未返回实例");
        }
        long instanceId = parsePositiveLong(attached.instanceId(), "instanceId");
        WmsBusinessTaskSnapshot task = taskService.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                WmsRulePlanContract.BUSINESS_TYPE,
                command.getPackageVersionId(),
                null,
                command.getPackageVersionId(),
                1,
                "PUBLISH",
                command.getCommandKey(),
                WmsRulePlanContract.PLAN_TYPE,
                instanceId,
                inputHash,
                jsonMapper.writeValueAsString(command),
                LoginHelper.getUserId(),
                LoginHelper.getDeptId()
            )
        );
        ProcessInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new ServiceException("规则发布计划实例不存在: " + instanceId);
        }
        long expectedVersion = attached.instanceVersion() == null
            ? instance.getInstanceVersion()
            : parsePositiveLong(attached.instanceVersion(), "instanceVersion");
        CommandResult released = instances.releaseStart(new PlanControlCommand(
            instanceId,
            expectedVersion,
            "RULE_PUBLISH_RELEASE:" + command.getCommandKey(),
            command.getReason()
        ));
        if (released == null || !"SUCCEEDED".equals(released.code())) {
            throw new ServiceException("规则发布计划解除挂起失败");
        }
        return new WmsRulePublicationStartResult(
            instanceId,
            task.taskId(),
            inputHash,
            attached.queueStatus()
        );
    }

    private void verifyDefinition(PlanDefinitionService definitions) {
        PageResult<DefinitionSummaryVo> page = definitions.page(
            WmsRulePlanContract.PLAN_CODE,
            "ACTIVE",
            "BUSINESS",
            new PageQuery(PAGE_SIZE, 1)
        );
        List<DefinitionSummaryVo> matches = page.getRows().stream()
            .filter(item -> WmsRulePlanContract.PLAN_CODE.equals(item.planCode()))
            .filter(item -> WmsRulePlanContract.PLAN_TYPE.equals(item.planType()))
            .toList();
        if (matches.size() != 1) {
            throw new ServiceException("规则发布计划生效版本必须唯一");
        }
        DefinitionDetailVo detail = definitions.get(
            parsePositiveLong(matches.getFirst().versionId(), "versionId")
        );
        if (detail == null
            || !"BUSINESS".equals(detail.summary().planRole())
            || !WmsRulePlanContract.PLAN_TYPE.equals(detail.summary().planType())
            || !WmsRulePlanContract.BUSINESS_TYPE.equals(detail.businessType())
            || !WmsRulePlanContract.BUSINESS_CODE.equals(detail.businessScheduleCode())
            || detail.events() == null
            || detail.events().size() != 2
            || !detail.events().stream()
                .map(DefinitionEventBo::localActionKey)
                .collect(java.util.stream.Collectors.toSet())
                .equals(Set.of(
                    WmsRulePlanContract.VALIDATE_ACTION_KEY,
                    WmsRulePlanContract.ACTIVATE_ACTION_KEY
                ))) {
            throw new ServiceException("已发布规则计划与内置合同不一致");
        }
    }

    private BusinessScheduleVo findSchedule(BusinessScheduleService schedules) {
        PageResult<BusinessScheduleVo> page = schedules.page(
            WmsRulePlanContract.BUSINESS_CODE,
            true,
            new PageQuery(PAGE_SIZE, 1)
        );
        List<BusinessScheduleVo> matches = page.getRows().stream()
            .filter(item -> WmsRulePlanContract.BUSINESS_CODE.equals(item.businessCode()))
            .toList();
        if (matches.size() != 1) {
            throw new ServiceException("规则发布业务调度必须唯一且已启用");
        }
        return matches.getFirst();
    }

    private void requirePlanType(BusinessScheduleVo schedule) {
        PlanTypeVo type = schedule.planTypes() == null ? null : schedule.planTypes().stream()
            .filter(item -> WmsRulePlanContract.PLAN_TYPE.equals(item.planType()))
            .findFirst()
            .orElse(null);
        if (type == null || !Boolean.TRUE.equals(type.enabled())) {
            throw new ServiceException("规则发布计划类型未启用");
        }
    }

    private WmsRulePackagePublishCommand normalize(WmsRulePackagePublishCommand source) {
        if (source == null) {
            throw WmsRuleValidation.invalid("请求体不能为空");
        }
        WmsRulePackagePublishCommand command = new WmsRulePackagePublishCommand();
        command.setCommandKey(WmsRuleValidation.requireCommandKey(source.getCommandKey()));
        command.setPackageVersionId(
            WmsRuleValidation.requireId(source.getPackageVersionId(), "packageVersionId")
        );
        command.setScopeType(
            WmsRuleValidation.requireScopeType(source.getScopeType())
        );
        command.setScopeId(WmsRuleValidation.requireId(source.getScopeId(), "scopeId"));
        command.setStorageIds(
            WmsRuleValidation.requireStorageIds(source.getStorageIds()).stream()
                .sorted(Comparator.naturalOrder())
                .toList()
        );
        command.setReason(WmsRuleValidation.requiredText(source.getReason(), "reason", 500));
        return command;
    }

    private void requireProcessEnabled() {
        if (!Boolean.TRUE.equals(
            environment.getProperty(PROCESS_ENABLED, Boolean.class, Boolean.FALSE)
        )) {
            throw new ServiceException("计划事件平台未启用，拒绝规则发布");
        }
    }

    private static <T> T require(ObjectProvider<T> provider, String name) {
        T value = provider.getIfAvailable();
        if (value == null) {
            throw new ServiceException(name + " 未启用或不可用");
        }
        return value;
    }

    private static long parsePositiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new ServiceException(name + " 必须为正整数");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new ServiceException(name + " 必须为数字: " + value);
        }
    }
}
