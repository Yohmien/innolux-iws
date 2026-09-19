/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.workflow.listener;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.enums.BusinessStatusEnum;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.system.api.UserService;
import com.innolux.iws.system.api.domain.UserDTO;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.common.enums.MessageTypeEnum;
import com.innolux.iws.workflow.event.WorkflowCopyEvent;
import com.innolux.iws.workflow.event.WorkflowResultMessageEvent;
import com.innolux.iws.workflow.event.WorkflowTaskMessageEvent;
import com.innolux.iws.workflow.service.IFlwCommonService;
import com.innolux.iws.workflow.service.IFlwTaskService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 工作流副作用事件监听器。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@Component
@RequiredArgsConstructor
public class WorkflowSideEffectListener {

    private final IFlwTaskService flwTaskService;
    private final IFlwCommonService flwCommonService;
    private final UserService userService;

    /**
     * 保存工作流抄送记录。
     *
     * @param event 工作流抄送事件
     */
    @EventListener
    public void handleCopy(WorkflowCopyEvent event) {
        flwTaskService.setCopy(event.task(), event.flowCopyList());
    }

    /**
     * 发送工作流待办消息。
     *
     * @param event 工作流待办消息事件
     */
    @EventListener
    public void handleTaskMessage(WorkflowTaskMessageEvent event) {
        flwCommonService.sendMessage(event.flowName(), event.instanceId(), event.messageType(), event.notice());
    }

    /**
     * 发送工作流结果消息。
     *
     * @param event 工作流结果消息事件
     */
    @EventListener
    public void handleResultMessage(WorkflowResultMessageEvent event) {
        if (!StringUtils.equalsAny(event.status(), BusinessStatusEnum.FINISH.getStatus(), BusinessStatusEnum.BACK.getStatus())) {
            return;
        }
        Long createBy = Convert.toLong(event.createBy(), null);
        if (createBy == null) {
            return;
        }
        BusinessStatusEnum status = BusinessStatusEnum.getByStatus(event.status());
        if (status == null) {
            return;
        }
        UserDTO initiator = userService.selectById(createBy);
        if (initiator == null || initiator.getUserId() == null) {
            return;
        }
        List<String> messageType = CollUtil.isNotEmpty(event.messageType())
            ? event.messageType()
            : Collections.singletonList(MessageTypeEnum.SYSTEM_MESSAGE.getCode());
        flwCommonService.sendResultMessage(event.flowName(), status, messageType, Collections.singletonList(initiator));
    }

}
