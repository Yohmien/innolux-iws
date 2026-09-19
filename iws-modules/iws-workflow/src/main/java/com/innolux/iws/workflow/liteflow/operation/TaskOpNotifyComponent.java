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

package com.innolux.iws.workflow.liteflow.operation;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.system.api.UserService;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.domain.bo.TaskOperationBo;
import com.innolux.iws.workflow.domain.context.TaskOperationContext;
import com.innolux.iws.workflow.service.IFlwCommonService;

import java.util.ArrayList;
import java.util.List;

import static com.innolux.iws.workflow.common.constant.FlowConstant.PATH_TASK_WAITING;

/**
 * 任务操作成功后发送待办消息。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@LiteflowComponent("taskOpNotify")
public class TaskOpNotifyComponent extends NodeComponent {

    private final IFlwCommonService flwCommonService;
    private final UserService userService;

    @Override
    public void process() {
        TaskOperationContext context = getContextBean(TaskOperationContext.class);
        TaskOperationBo bo = context.getTaskOperationBo();
        List<Long> userIdList = new ArrayList<>();
        if (StringUtils.isNotBlank(bo.getUserId())) {
            userIdList.add(Convert.toLong(bo.getUserId()));
        }
        if (CollUtil.isNotEmpty(bo.getUserIds())) {
            userIdList.addAll(bo.getUserIds().stream().map(Convert::toLong).toList());
        }
        if (CollUtil.isEmpty(userIdList)) {
            return;
        }
        // 转办、委托、加减签等运行时操作，消息接收人统一从“我的待办”进入处理。
        flwCommonService.sendMessage(
            bo.getMessageType(),
            StringUtils.isNotBlank(bo.getMessage()) ? bo.getMessage() : "单据「" + context.getOperation().getDesc() + "」通知",
            "单据「" + context.getOperation().getDesc() + "」提醒",
            userService.selectListByIds(userIdList),
            PATH_TASK_WAITING
        );
    }

}
