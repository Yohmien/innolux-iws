/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 创建持久空间变更单、业务任务并挂载已发布计划；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.process;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.process.api.AttachResult;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.BusinessScheduleVo;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.DefinitionSummaryVo;
import com.innolux.iws.process.api.PlanControlCommand;
import com.innolux.iws.process.api.PlanTypeVo;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.service.BusinessScheduleService;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.process.service.PlanInstanceService;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeCommand;
import com.innolux.iws.wms.space.change.domain.WmsSpaceChangeStartResult;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 空间生命周期计划入口。 */
@Service
public class WmsSpaceChangeProcess {

    public static final String PERMISSION_SPACE_CHANGE = WmsSpaceChangeService.PERMISSION_CHANGE;

    private static final String PROCESS_ENABLED = "iws.process.enabled";

    private static final int PAGE_SIZE = 50;

    private final Environment environment;

    private final ObjectProvider<BusinessScheduleService> scheduleProvider;

    private final ObjectProvider<PlanDefinitionService> definitionProvider;

    private final ObjectProvider<PlanInstanceService> instanceProvider;

    private final WmsBusinessTaskService taskService;

    private final WmsSpaceChangeService changeService;

    private final ProcessInstanceMapper instanceMapper;

    private final JdbcTemplate jdbcTemplate;

    private final WmsCompanyAccessPolicy accessPolicy;

    private final JsonMapper jsonMapper;

    public WmsSpaceChangeProcess(
        Environment environment,
        ObjectProvider<BusinessScheduleService> scheduleProvider,
        ObjectProvider<PlanDefinitionService> definitionProvider,
        ObjectProvider<PlanInstanceService> instanceProvider,
        WmsBusinessTaskService taskService,
        WmsSpaceChangeService changeService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        WmsCompanyAccessPolicy accessPolicy,
        JsonMapper jsonMapper
    ) {
        this.environment = environment;
        this.scheduleProvider = scheduleProvider;
        this.definitionProvider = definitionProvider;
        this.instanceProvider = instanceProvider;
        this.taskService = taskService;
        this.changeService = changeService;
        this.instanceMapper = instanceMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.accessPolicy = accessPolicy;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsSpaceChangeStartResult start(WmsSpaceChangeCommand source) {
        requireProcessEnabled();
        accessPolicy.checkPermission(PERMISSION_SPACE_CHANGE);
        WmsSpaceChangeCommand command = normalize(source);
        String planType = "RETIRE".equals(command.getChangeType())
            ? WmsSpaceChangeService.PLAN_TYPE_RETIRE
            : WmsSpaceChangeService.PLAN_TYPE_CHANGE;
        String planCode = "RETIRE".equals(command.getChangeType())
            ? WmsSpacePlanContract.RETIRE_PLAN_CODE
            : WmsSpacePlanContract.CHANGE_PLAN_CODE;
        BusinessScheduleService schedules = require(scheduleProvider, "业务调度服务");
        PlanDefinitionService definitions = require(definitionProvider, "计划定义服务");
        PlanInstanceService instances = require(instanceProvider, "计划实例服务");
        BusinessScheduleVo schedule = findSchedule(schedules);
        requirePlanType(schedule, planType);
        verifyDefinitions(definitions, planCode, planType);

        String inputJson = jsonMapper.writeValueAsString(command);
        String inputHash = WmsRuleDigest.sha256(inputJson);
        long changeOrderId = changeService.createOrder(command, inputHash, inputJson);
        WmsSpaceChangeStartResult replayed = replayBoundOrder(
            changeOrderId, inputHash, command);
        if (replayed != null) {
            return replayed;
        }
        AttachResult attached = instances.attach(new AttachTaskCommand(
            parsePositiveLong(schedule.id(), "scheduleId"),
            planType,
            WmsSpaceChangeService.BUSINESS_TYPE,
            String.valueOf(changeOrderId),
            command.getCommandKey(),
            true,
            inputHash,
            List.of(),
            "SPACE_CHANGE_ATTACH:" + command.getCommandKey()
        ));
        if (attached == null || attached.instanceId() == null) {
            throw new ServiceException("空间变更计划挂载未返回实例");
        }
        long instanceId = parsePositiveLong(attached.instanceId(), "instanceId");
        WmsBusinessTaskSnapshot task = taskService.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                WmsSpaceChangeService.BUSINESS_TYPE,
                changeOrderId,
                null,
                changeOrderId,
                1,
                "SUBMIT",
                command.getCommandKey(),
                planType,
                instanceId,
                inputHash,
                inputJson,
                LoginHelper.getUserId(),
                LoginHelper.getDeptId()
            )
        );
        ProcessInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new ServiceException("空间变更计划实例不存在: " + instanceId);
        }
        long expectedVersion = attached.instanceVersion() == null
            ? instance.getInstanceVersion()
            : parsePositiveLong(attached.instanceVersion(), "instanceVersion");
        CommandResult released = instances.releaseStart(new PlanControlCommand(
            instanceId,
            expectedVersion,
            "SPACE_CHANGE_RELEASE:" + command.getCommandKey(),
            command.getReason()
        ));
        if (released == null || !"SUCCEEDED".equals(released.code())) {
            throw new ServiceException("空间变更计划解除挂起失败");
        }
        int rows = jdbcTemplate.update("""
            update wms_space_change_order
               set plan_instance_id = ?, business_task_id = ?,
                   revision = revision + 1, update_by = ?,
                   update_time = current_timestamp(3)
             where change_order_id = ? and plan_instance_id is null
            """, instanceId, task.taskId(), LoginHelper.getUserId(), changeOrderId);
        if (rows != 1) {
            WmsSpaceChangeStartResult raced = replayBoundOrder(
                changeOrderId, inputHash, command);
            if (raced != null) {
                return raced;
            }
            throw new ServiceException("空间变更单计划身份写入失败");
        }
        return new WmsSpaceChangeStartResult(
            changeOrderId,
            instanceId,
            task.taskId(),
            inputHash,
            attached.queueStatus()
        );
    }

    private WmsSpaceChangeStartResult replayBoundOrder(
        long changeOrderId,
        String inputHash,
        WmsSpaceChangeCommand command
    ) {
        return jdbcTemplate.query("""
            select plan_instance_id, business_task_id
              from wms_space_change_order
             where change_order_id = ? and command_key = ?
            """, (rs, rowNum) -> {
            Long instanceId = nullableLong(rs, "plan_instance_id");
            Long taskId = nullableLong(rs, "business_task_id");
            if (instanceId == null || taskId == null) {
                return null;
            }
            return new WmsSpaceChangeStartResult(
                changeOrderId, instanceId, taskId, inputHash, "REPLAYED");
        }, changeOrderId, command.getCommandKey()).stream()
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private BusinessScheduleVo findSchedule(BusinessScheduleService schedules) {
        PageResult<BusinessScheduleVo> page = schedules.page(
            WmsSpaceChangeService.SCHEDULE_CODE,
            true,
            new PageQuery(PAGE_SIZE, 1)
        );
        List<BusinessScheduleVo> matches = page.getRows().stream()
            .filter(item -> WmsSpaceChangeService.SCHEDULE_CODE.equals(item.businessCode()))
            .toList();
        if (matches.size() != 1) {
            throw new ServiceException("空间配置业务调度必须唯一且已启用");
        }
        return matches.getFirst();
    }

    private void requirePlanType(BusinessScheduleVo schedule, String planType) {
        PlanTypeVo type = schedule.planTypes() == null ? null : schedule.planTypes().stream()
            .filter(item -> planType.equals(item.planType()))
            .findFirst()
            .orElse(null);
        if (type == null || !Boolean.TRUE.equals(type.enabled())) {
            throw new ServiceException("空间计划类型未启用: " + planType);
        }
    }

    private void verifyDefinitions(
        PlanDefinitionService definitions,
        String planCode,
        String planType
    ) {
        verifyDefinition(definitions, planCode, planType, "BUSINESS");
        String supervisorCode = WmsSpaceChangeService.PLAN_TYPE_RETIRE.equals(planType)
            ? WmsSpacePlanContract.RETIRE_SUPERVISOR_PLAN_CODE
            : WmsSpacePlanContract.CHANGE_SUPERVISOR_PLAN_CODE;
        verifyDefinition(definitions, supervisorCode, planType, "SUPERVISOR");
    }

    private void verifyDefinition(
        PlanDefinitionService definitions,
        String planCode,
        String planType,
        String role
    ) {
        PageResult<DefinitionSummaryVo> page = definitions.page(
            planCode, "ACTIVE", role, new PageQuery(PAGE_SIZE, 1));
        List<DefinitionSummaryVo> matches = page.getRows().stream()
            .filter(item -> planCode.equals(item.planCode()))
            .filter(item -> planType.equals(item.planType()))
            .toList();
        if (matches.size() != 1) {
            throw new ServiceException("空间计划生效版本必须唯一: " + planCode);
        }
        DefinitionDetailVo detail = definitions.get(
            parsePositiveLong(matches.getFirst().versionId(), "versionId"));
        if (detail == null
            || !role.equals(detail.summary().planRole())
            || !planType.equals(detail.summary().planType())
            || ("BUSINESS".equals(role)
                && !WmsSpaceChangeService.BUSINESS_TYPE.equals(detail.businessType()))
            || !WmsSpaceChangeService.SCHEDULE_CODE.equals(detail.businessScheduleCode())) {
            throw new ServiceException("已发布空间计划与内置合同不一致: " + planCode);
        }
    }

    private WmsSpaceChangeCommand normalize(WmsSpaceChangeCommand source) {
        if (source == null) {
            throw new ServiceException(
                WmsSpaceChangeService.ERROR_INVALID + ": 请求体不能为空");
        }
        source.setChangeType(normalizeToken(source.getChangeType()));
        source.setObjectType(normalizeToken(source.getObjectType()));
        if (!Set.of("CREATE", "UPDATE", "RETIRE").contains(source.getChangeType())) {
            throw new ServiceException(
                WmsSpaceChangeService.ERROR_INVALID + ": changeType 必须为 CREATE/UPDATE/RETIRE");
        }
        if (!Set.of("WAREHOUSE", "ZONE", "STORAGE").contains(source.getObjectType())) {
            throw new ServiceException(
                WmsSpaceChangeService.ERROR_INVALID + ": objectType 必须为 WAREHOUSE/ZONE/STORAGE");
        }
        return source;
    }

    private static String normalizeToken(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private void requireProcessEnabled() {
        if (!Boolean.TRUE.equals(
            environment.getProperty(PROCESS_ENABLED, Boolean.class, Boolean.FALSE)
        )) {
            throw new ServiceException("计划事件平台未启用，拒绝空间变更");
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

    private static Long nullableLong(java.sql.ResultSet rs, String name)
        throws java.sql.SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }
}
