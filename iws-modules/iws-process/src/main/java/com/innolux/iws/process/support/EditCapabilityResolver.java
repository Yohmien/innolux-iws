/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 草稿字段白名单与代码绑定门禁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.support;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.api.DefinitionEventBo;
import com.innolux.iws.process.api.DefinitionStateBo;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.enums.EditPolicy;
import com.innolux.iws.process.enums.GraphEditPolicy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 服务端编辑门禁。界面按钮只是提示，所有写入口必须复用本组件。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class EditCapabilityResolver {

    /**
     * 校验基本信息不可改身份字段。
     *
     * @param current 当前定义
     * @param draft 新草稿
     */
    public void checkBase(PlanDefinition current, DefinitionDraftBo draft) {
        if (draft.planCode() != null && !Objects.equals(draft.planCode().trim(), current.getPlanCode())) {
            throw new ServiceException("计划编码不可修改");
        }
        if (draft.planVersion() != null && !Objects.equals(draft.planVersion(), current.getPlanVersion())) {
            throw new ServiceException("业务版本号仅由复制操作分配");
        }
        if (draft.planRole() != null
            && !Objects.equals(draft.planRole(), current.getPlanRole() == null ? null : current.getPlanRole().name())) {
            throw new ServiceException("计划角色为代码固定字段，不能修改");
        }
        if (draft.planType() != null && !Objects.equals(draft.planType(), current.getPlanType())) {
            throw new ServiceException("计划类型为身份字段，不能修改");
        }
    }

    /**
     * 校验状态校验器不可替换。
     *
     * @param current 当前状态，新增为 null
     * @param state 新状态
     */
    public void checkState(ProcessState current, DefinitionStateBo state) {
        if (current == null) {
            return;
        }
        if (!Objects.equals(current.getValidatorKey(), state.validatorKey())
            || !Objects.equals(current.getValidatorVersion(), state.validatorVersion())) {
            throw new ServiceException("状态校验器为代码固定能力，不能替换: " + current.getStateCode());
        }
        if (!Objects.equals(current.getStateKind() == null ? null : current.getStateKind().name(), state.stateKind())) {
            throw new ServiceException("状态类别为图结构字段，需新建版本: " + current.getStateCode());
        }
    }

    /**
     * 校验事件代码绑定字段与图编辑策略。
     *
     * <p>编辑策略、图编辑策略和锁定字段只由注册能力与既有行确定，任何请求都不能通过
     * 传空值、改成 EDITABLE 或重排锁定字段来解除代码锁。</p>
     *
     * @param current 当前事件，新增为 null
     * @param event 新事件参数
     */
    public void checkEvent(ProcessEventDefinition current, DefinitionEventBo event) {
        if (current == null) {
            return;
        }
        if (!Objects.equals(current.getEditPolicy() == null ? null : current.getEditPolicy().name(), event.editPolicy())
            || !Objects.equals(current.getGraphEditPolicy() == null ? null : current.getGraphEditPolicy().name(),
                event.graphEditPolicy())
            || !Objects.equals(current.getLockedFields(), event.lockedFields())) {
            throw new ServiceException("编辑策略由注册能力确定，不允许通过请求解除代码锁");
        }
        if (current.getEditPolicy() == EditPolicy.CODE_FIXED) {
            if (!Objects.equals(current.getEventDirection() == null ? null : current.getEventDirection().name(),
                event.eventDirection())
                || !Objects.equals(current.getEventRole() == null ? null : current.getEventRole().name(),
                event.eventRole())
                || !Objects.equals(current.getEventTypeKey(), event.eventTypeKey())
                || !Objects.equals(current.getEventTypeVersion(), event.eventTypeVersion())
                || !Objects.equals(current.getCallContractKey(), event.callContractKey())
                || !Objects.equals(current.getCallContractVersion(), event.callContractVersion())
                || !Objects.equals(current.getOperationKind() == null ? null : current.getOperationKind().name(),
                event.operationKind())
                || !Objects.equals(current.getLocalActionKey(), event.localActionKey())
                || !Objects.equals(current.getLocalActionVersion(), event.localActionVersion())
                || !Objects.equals(current.getEffectKey(), event.effectKey())
                || !Objects.equals(current.getLocalEffectKey(), event.localEffectKey())
                || !Objects.equals(current.getSystemCode(), event.systemCode())
                || !Objects.equals(current.getTargetSystemCode(), event.targetSystemCode())) {
                throw new ServiceException("事件代码绑定字段不可修改: " + current.getEventCode());
            }
        }
        if (current.getGraphEditPolicy() == GraphEditPolicy.CODE_FIXED
            && (!Objects.equals(current.getSourceStateCode(), event.sourceStateCode())
            || !Objects.equals(current.getTargetStateCode(), event.targetStateCode()))) {
            throw new ServiceException("事件源/目标状态为 CODE_FIXED，需代码重构并新建版本: "
                + current.getEventCode());
        }
    }

    /**
     * 校验删除事件是否被代码或图策略锁定。
     *
     * @param current 当前事件
     */
    public void checkEventDelete(ProcessEventDefinition current) {
        if (current.getEditPolicy() == EditPolicy.CODE_FIXED
            || current.getGraphEditPolicy() == GraphEditPolicy.CODE_FIXED) {
            throw new ServiceException("事件被代码绑定锁定，不能删除: " + current.getEventCode());
        }
    }

    /**
     * 完整草稿替换前的逐行门禁。
     *
     * <p>整份保存会先删后插子表，必须在这里恢复单事件/单状态入口的全部代码锁校验，
     * 并拒绝把代码固定事件从集合中删除。</p>
     *
     * @param currentStates 当前状态
     * @param currentEvents 当前事件
     * @param states 待保存状态，null 表示不修改
     * @param events 待保存事件，null 表示不修改
     */
    public void checkChildren(List<ProcessState> currentStates, List<ProcessEventDefinition> currentEvents,
                              List<DefinitionStateBo> states, List<DefinitionEventBo> events) {
        if (states != null) {
            Map<String, DefinitionStateBo> incoming = indexStates(states);
            for (ProcessState state : currentStates) {
                DefinitionStateBo next = incoming.get(state.getStateCode());
                if (next != null) {
                    checkState(state, next);
                }
            }
        }
        if (events != null) {
            Map<String, DefinitionEventBo> incoming = indexEvents(events);
            for (ProcessEventDefinition event : currentEvents) {
                DefinitionEventBo next = incoming.get(event.getEventCode());
                if (next == null) {
                    checkEventDelete(event);
                } else {
                    checkEvent(event, next);
                }
            }
        }
    }

    /**
     * 建立状态索引，同时拒绝重复编码。
     *
     * @param states 状态
     * @return 索引
     */
    public static Map<String, DefinitionStateBo> indexStates(List<DefinitionStateBo> states) {
        Map<String, DefinitionStateBo> result = new HashMap<>();
        for (DefinitionStateBo state : states) {
            if (state == null || state.stateCode() == null) {
                continue;
            }
            if (result.putIfAbsent(state.stateCode(), state) != null) {
                throw new ServiceException("状态编码重复: " + state.stateCode());
            }
        }
        return result;
    }

    /**
     * 建立事件索引，同时拒绝重复编码。
     *
     * @param events 事件
     * @return 索引
     */
    public static Map<String, DefinitionEventBo> indexEvents(List<DefinitionEventBo> events) {
        Map<String, DefinitionEventBo> result = new HashMap<>();
        for (DefinitionEventBo event : events) {
            if (event == null || event.eventCode() == null) {
                continue;
            }
            if (result.putIfAbsent(event.eventCode(), event) != null) {
                throw new ServiceException("事件编码重复: " + event.eventCode());
            }
        }
        return result;
    }
}
