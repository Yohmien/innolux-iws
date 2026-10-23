/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 从持久定义构建不可变 DAG；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.support;

import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图构建入口。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class DefinitionGraphBuilder {

    /**
     * 构建不可变图。
     *
     * @param events 事件
     * @param states 状态
     * @param edges 边
     * @return 图
     */
    public DefinitionGraph build(List<ProcessEventDefinition> events, List<ProcessState> states,
                                 List<ProcessEdge> edges) {
        return new DefinitionGraph(events, states, edges);
    }
}
