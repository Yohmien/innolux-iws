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

package com.innolux.iws.workflow.liteflow.instance;

import cn.hutool.core.collection.CollUtil;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.core.utils.StreamUtils;
import com.innolux.iws.common.mybatis.core.query.QueryBuilder;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.dromara.warm.flow.orm.mapper.FlowInstanceMapper;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.domain.context.InstanceDeleteContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 加载待删除流程实例。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@Slf4j
@LiteflowComponent("instanceDeleteLoad")
public class InstanceDeleteLoadComponent extends NodeComponent {

    private final FlowInstanceMapper flowInstanceMapper;

    @Override
    public void process() {
        InstanceDeleteContext context = getContextBean(InstanceDeleteContext.class);
        List<FlowInstance> flowInstances;
        if (CollUtil.isNotEmpty(context.getBusinessIds())) {
            flowInstances = flowInstanceMapper.selectList(QueryBuilder.lambda(FlowInstance.class)
                .in(FlowInstance::getBusinessId, context.getBusinessIds())
                .build());
            context.setDeleteInstanceIds(StreamUtils.toList(flowInstances, FlowInstance::getId));
        } else {
            flowInstances = flowInstanceMapper.selectByIds(context.getInstanceIds());
            context.setDeleteInstanceIds(new ArrayList<>(context.getInstanceIds()));
        }
        if (CollUtil.isEmpty(flowInstances)) {
            log.warn("未找到对应的流程实例信息，无法执行删除操作。");
            context.setResult(false);
            return;
        }
        context.setFlowInstances(flowInstances);
    }

}
