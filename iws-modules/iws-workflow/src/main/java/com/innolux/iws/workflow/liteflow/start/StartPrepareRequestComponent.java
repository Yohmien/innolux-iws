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

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.common.mybatis.core.query.QueryBuilder;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.dromara.warm.flow.orm.mapper.FlowInstanceMapper;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.domain.context.StartProcessContext;

import java.util.Map;

import static com.innolux.iws.workflow.common.constant.FlowConstant.*;

/**
 * 准备流程启动请求。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@LiteflowComponent("startPrepareRequest")
public class StartPrepareRequestComponent extends NodeComponent {

    private final FlowInstanceMapper flowInstanceMapper;

    @Override
    public void process() {
        StartProcessContext context = getContextBean(StartProcessContext.class);
        String businessId = context.getStartProcessBo().getBusinessId();
        if (StringUtils.isBlank(businessId)) {
            throw new ServiceException("启动工作流时必须包含业务ID");
        }
        context.setBusinessId(businessId);
        context.setVariables(context.getStartProcessBo().getVariables());
        context.setBizExt(context.getStartProcessBo().getBizExt());

        Map<String, Object> variables = context.getVariables();
        variables.put(INITIATOR, LoginHelper.getUserIdStr());
        variables.put(INITIATOR_DEPT_ID, LoginHelper.getDeptId());
        variables.put(BUSINESS_ID, businessId);

        FlowInstance flowInstance = flowInstanceMapper.selectOne(QueryBuilder.lambda(FlowInstance.class)
            .eq(FlowInstance::getBusinessId, businessId)
            .build());
        context.setExistingInstance(flowInstance);
    }

}
