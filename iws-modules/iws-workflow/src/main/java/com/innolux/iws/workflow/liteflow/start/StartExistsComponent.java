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

package com.innolux.iws.workflow.liteflow.start;

import cn.hutool.core.util.ObjectUtil;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeBooleanComponent;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.domain.context.StartProcessContext;

/**
 * 判断启动流程时是否走已有实例续提交分支。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@LiteflowComponent("startExists")
public class StartExistsComponent extends NodeBooleanComponent {

    @Override
    public boolean processBoolean() {
        StartProcessContext context = getContextBean(StartProcessContext.class);
        return ObjectUtil.isNotNull(context.getExistingInstance());
    }

}
