/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 业务统一调度配置聚合保存；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.process.api.BusinessScheduleBo;
import com.innolux.iws.process.api.BusinessScheduleVo;
import com.innolux.iws.process.api.PlanTypeBo;
import com.innolux.iws.process.api.PlanTypeVo;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.BusinessSchedulePlanType;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.BusinessSchedulePlanTypeMapper;
import com.innolux.iws.process.service.BusinessScheduleService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 业务统一调度配置实现。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class BusinessScheduleServiceImpl implements BusinessScheduleService {

    private final BusinessScheduleMapper scheduleMapper;

    private final BusinessSchedulePlanTypeMapper planTypeMapper;

    private final ProcessAuditService auditService;

    /**
     * 构造服务。
     *
     * @param scheduleMapper 业务调度 Mapper
     * @param planTypeMapper 计划类型 Mapper
     * @param auditService 审计服务
     */
    public BusinessScheduleServiceImpl(BusinessScheduleMapper scheduleMapper,
                                       BusinessSchedulePlanTypeMapper planTypeMapper,
                                       ProcessAuditService auditService) {
        this.scheduleMapper = scheduleMapper;
        this.planTypeMapper = planTypeMapper;
        this.auditService = auditService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PageResult<BusinessScheduleVo> page(String businessCode, Boolean enabled, PageQuery pageQuery) {
        Page<BusinessSchedule> page = scheduleMapper.selectPage(pageQuery.build(),
            Wrappers.<BusinessSchedule>lambdaQuery()
                .like(businessCode != null && !businessCode.isBlank(), BusinessSchedule::getBusinessCode, businessCode)
                .eq(enabled != null, BusinessSchedule::getEnabled, enabled)
                .orderByAsc(BusinessSchedule::getBusinessCode));
        return PageResult.build(page.getRecords().stream().map(this::toVo).toList(), page.getTotal());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BusinessScheduleVo get(long id) {
        return toVo(require(id));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public BusinessScheduleVo create(BusinessScheduleBo bo, String commandKey, String reason) {
        ProcessText.required(bo.businessCode(), "业务编码");
        ProcessText.required(bo.businessName(), "业务名称");
        if (scheduleMapper.selectCount(Wrappers.<BusinessSchedule>lambdaQuery()
            .eq(BusinessSchedule::getBusinessCode, bo.businessCode())) > 0) {
            throw new ServiceException("业务编码已存在: " + bo.businessCode());
        }
        BusinessSchedule schedule = new BusinessSchedule();
        schedule.setBusinessCode(bo.businessCode().trim());
        schedule.setBusinessName(bo.businessName());
        schedule.setEnabled(bo.enabled() == null ? Boolean.TRUE : bo.enabled());
        schedule.setFairnessWindowSize(bo.fairnessWindowSize());
        schedule.setRemark(bo.remark());
        schedule.setRevision(1L);
        schedule.setDelFlag("0");
        scheduleMapper.insert(schedule);
        appendAudit(ActionType.DEFINITION_CREATE, schedule.getId(), commandKey, reason);
        return get(schedule.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public BusinessScheduleVo update(long id, BusinessScheduleBo bo, long expectedRevision, String commandKey,
                                     String reason) {
        BusinessSchedule schedule = require(id);
        if (schedule.getRevision() != expectedRevision) {
            throw new ServiceException("版本冲突，当前 revision=" + schedule.getRevision());
        }
        if (bo.businessName() != null) {
            schedule.setBusinessName(bo.businessName());
        }
        if (bo.enabled() != null) {
            schedule.setEnabled(bo.enabled());
        }
        if (bo.fairnessWindowSize() != null) {
            schedule.setFairnessWindowSize(bo.fairnessWindowSize());
        }
        if (bo.remark() != null) {
            schedule.setRemark(bo.remark());
        }
        int rows = scheduleMapper.update(null, Wrappers.<BusinessSchedule>lambdaUpdate()
            .eq(BusinessSchedule::getId, id)
            .eq(BusinessSchedule::getRevision, expectedRevision)
            .set(BusinessSchedule::getBusinessName, schedule.getBusinessName())
            .set(BusinessSchedule::getEnabled, schedule.getEnabled())
            .set(BusinessSchedule::getFairnessWindowSize, schedule.getFairnessWindowSize())
            .set(BusinessSchedule::getRemark, schedule.getRemark())
            .set(BusinessSchedule::getRevision, expectedRevision + 1)
            .set(BusinessSchedule::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("版本已变化，请重新读取");
        }
        appendAudit(ActionType.DEFINITION_UPDATE, id, commandKey, reason);
        return get(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(long id, long expectedRevision, String commandKey, String reason) {
        BusinessSchedule schedule = require(id);
        if (schedule.getRevision() != expectedRevision) {
            throw new ServiceException("版本冲突，当前 revision=" + schedule.getRevision());
        }
        scheduleMapper.deleteById(id);
        planTypeMapper.delete(Wrappers.<BusinessSchedulePlanType>lambdaQuery()
            .eq(BusinessSchedulePlanType::getScheduleId, id));
        appendAudit(ActionType.DEFINITION_DELETE, id, commandKey, reason);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PlanTypeVo> planTypes(long id) {
        require(id);
        return planTypeMapper.selectList(Wrappers.<BusinessSchedulePlanType>lambdaQuery()
                .eq(BusinessSchedulePlanType::getScheduleId, id)
                .orderByDesc(BusinessSchedulePlanType::getPriority))
            .stream().map(this::toPlanTypeVo).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<PlanTypeVo> savePlanTypes(long id, List<PlanTypeBo> planTypes, long expectedRevision,
                                          String commandKey, String reason) {
        BusinessSchedule schedule = require(id);
        if (schedule.getRevision() != expectedRevision) {
            throw new ServiceException("版本冲突，当前 revision=" + schedule.getRevision());
        }
        Set<String> unique = new HashSet<>();
        if (planTypes != null) {
            for (PlanTypeBo item : planTypes) {
                ProcessText.required(item.planType(), "计划类型");
                if (!unique.add(item.planType())) {
                    throw new ServiceException("计划类型重复: " + item.planType());
                }
            }
        }
        int rows = scheduleMapper.update(null, Wrappers.<BusinessSchedule>lambdaUpdate()
            .eq(BusinessSchedule::getId, id)
            .eq(BusinessSchedule::getRevision, expectedRevision)
            .set(BusinessSchedule::getRevision, expectedRevision + 1)
            .set(BusinessSchedule::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("版本已变化，请重新读取");
        }
        planTypeMapper.delete(Wrappers.<BusinessSchedulePlanType>lambdaQuery()
            .eq(BusinessSchedulePlanType::getScheduleId, id));
        if (planTypes != null) {
            for (PlanTypeBo item : planTypes) {
                BusinessSchedulePlanType row = new BusinessSchedulePlanType();
                row.setScheduleId(id);
                row.setPlanType(item.planType());
                row.setPlanTypeName(item.planTypeName());
                row.setPriority(item.priority() == null ? 0 : item.priority());
                row.setEnabled(item.enabled() == null ? Boolean.TRUE : item.enabled());
                planTypeMapper.insert(row);
            }
        }
        appendAudit(ActionType.DEFINITION_UPDATE, id, commandKey, reason);
        return planTypes(id);
    }

    private BusinessSchedule require(long id) {
        BusinessSchedule schedule = scheduleMapper.selectById(id);
        if (schedule == null) {
            throw new ServiceException("业务调度不存在: " + id);
        }
        return schedule;
    }

    private BusinessScheduleVo toVo(BusinessSchedule schedule) {
        List<PlanTypeVo> planTypes = planTypeMapper.selectList(Wrappers.<BusinessSchedulePlanType>lambdaQuery()
                .eq(BusinessSchedulePlanType::getScheduleId, schedule.getId())
                .orderByDesc(BusinessSchedulePlanType::getPriority))
            .stream().map(this::toPlanTypeVo).toList();
        return new BusinessScheduleVo(ProcessText.id(schedule.getId()), schedule.getBusinessCode(),
            schedule.getBusinessName(), schedule.getEnabled(), schedule.getFairnessWindowSize(),
            ProcessText.id(schedule.getRevision()), schedule.getRemark(), planTypes);
    }

    private PlanTypeVo toPlanTypeVo(BusinessSchedulePlanType row) {
        return new PlanTypeVo(ProcessText.id(row.getId()), row.getPlanType(), row.getPlanTypeName(),
            row.getPriority(), row.getEnabled());
    }

    private void appendAudit(ActionType action, long scheduleId, String commandKey, String reason) {
        ProcessActionAudit audit = auditService.newAudit(action, ActionSource.USER, AuditResult.SUCCESS);
        audit.setScheduleId(scheduleId);
        audit.setCommandKey(commandKey);
        audit.setReason(reason);
        auditService.append(audit);
    }
}
