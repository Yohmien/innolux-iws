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

package com.innolux.iws.workflow.domain.context;

import lombok.Data;
import org.dromara.warm.flow.orm.entity.FlowInstance;

import java.util.Collection;
import java.util.List;

/**
 * 删除流程实例 LiteFlow 上下文。
 *
 * @author YONGJIE.PENG
 */
@Data
public class InstanceDeleteContext {

    /**
     * 按业务 id 删除时的业务 id 集合。
     */
    private final List<String> businessIds;

    /**
     * 按实例 id 删除时的实例 id 集合。
     */
    private final Collection<Long> instanceIds;

    /**
     * 是否删除历史实例及其历史任务数据。
     */
    private final boolean history;

    /**
     * 待删除流程实例。
     */
    private List<FlowInstance> flowInstances;

    /**
     * 实际执行删除的实例 id。
     */
    private List<Long> deleteInstanceIds;

    /**
     * 删除结果。
     */
    private boolean result;

    public static InstanceDeleteContext byBusinessIds(List<String> businessIds) {
        return new InstanceDeleteContext(businessIds, null, false);
    }

    public static InstanceDeleteContext byInstanceIds(Collection<Long> instanceIds) {
        return new InstanceDeleteContext(null, instanceIds, false);
    }

    public static InstanceDeleteContext byHistoryInstanceIds(Collection<Long> instanceIds) {
        return new InstanceDeleteContext(null, instanceIds, true);
    }

}
